package com.shadowclone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/**
 * Replays a macro. Per frame: stop rules first, then foreground check, then the current step.
 * Never dispatches below the step threshold, and gives up rather than guessing after the timeout.
 */
class Runner(
    private val svc: ShadowService,
    private val macro: Macro,
    private val onState: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Bitmap>()
    private var i = 0
    private var stepStart = 0L
    private var running = false

    val active get() = running

    fun start() {
        if (macro.steps.isEmpty()) {
            onState("nothing recorded yet")
            return
        }
        i = 0
        running = true
        stepStart = System.currentTimeMillis()
        main.post(tick)
    }

    fun stop(why: String) {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        onState(why)
    }

    private fun halt(why: String) {
        stop(why)
        Toast.makeText(svc, "ShadowClone: $why", Toast.LENGTH_LONG).show()
    }

    private val tick = Runnable {
        if (running) svc.capture { frame -> if (running) onFrame(frame) }
    }

    private fun again() {
        if (running) main.postDelayed(tick, ShadowService.CAPTURE_INTERVAL_MS)
    }

    private fun onFrame(frame: Bitmap?) {
        if (frame == null) {
            again(); return
        }

        // Stop rules win over the step we are waiting for, and never dispatch anything.
        for (s in macro.stops) {
            val m = matchOf(frame, s) ?: continue
            if (m.score >= s.threshold) {
                halt("stopped by a no-tap rule (%.3f)".format(m.score))
                return
            }
        }

        val fg = svc.currentPackage()
        val want = macro.pkg
        if (want != null && fg != null && fg != want) {
            // Paused, not failed: hold the clock, or coming back would time the step out at once.
            stepStart = System.currentTimeMillis()
            onState("paused: $fg in front, want $want")
            again(); return
        }

        val step = macro.steps[i]
        val m = matchOf(frame, step)
        val score = m?.score ?: 0.0
        if (m != null && score >= step.threshold) {
            onState("step %d/%d  %.3f  %s".format(i + 1, macro.steps.size, score, step.act))
            fire(step, m)
            return
        }

        val waited = System.currentTimeMillis() - stepStart
        if (waited > step.timeoutMs) {
            halt("timed out on step %d after %ds (best %.3f)".format(i + 1, waited / 1000, score))
            return
        }
        onState("step %d/%d waiting  %.3f  %ds".format(i + 1, macro.steps.size, score, waited / 1000))
        again()
    }

    private fun fire(step: Step, m: MatchResult) {
        val t = bmp(step.template) ?: return halt("template ${step.template} is missing")
        // The match gives the template's centre; shift the whole gesture by where it moved to.
        val dx = (m.cx - t.width / 2) - step.tx
        val dy = (m.cy - t.height / 2) - step.ty
        svc.gesture(step, dx, dy) { ok ->
            if (!running) return@gesture
            if (!ok) return@gesture halt("the system rejected the gesture on step ${i + 1}")
            i++
            if (i >= macro.steps.size) {
                running = false
                onState("done: ${macro.steps.size} steps")
                return@gesture
            }
            stepStart = System.currentTimeMillis()
            main.postDelayed(tick, step.postDelayMs)
        }
    }

    private fun matchOf(frame: Bitmap, s: Step): MatchResult? {
        val t = bmp(s.template) ?: return null
        return Matcher.match(frame, t, s.region(t.width, t.height, frame.width, frame.height))
    }

    private fun bmp(name: String): Bitmap? {
        cache[name]?.let { return it }
        val b = BitmapFactory.decodeFile(File(Macro.dir(svc), name).path) ?: return null
        cache[name] = b
        return b
    }
}
