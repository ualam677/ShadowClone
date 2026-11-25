package com.shadowclone

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Replays a macro. Per frame, in this order: no-tap rules, the foreground check, watchers, then the
 * current step. Nothing is dispatched below its threshold, and a step that never shows up gives up
 * after its timeout rather than guessing.
 *
 * The foreground check sits above watchers on purpose: a watcher dispatches a real gesture, so
 * letting it run while another app is in front would tap inside that app.
 */
class Runner(
    private val svc: ShadowService,
    private val macro: Macro,
    private val onState: (String) -> Unit,
    /**
     * The same progress as [onState], as values. The pill reads this; the readout reads the
     * string. Defaulted so existing construction keeps compiling.
     */
    private val onRun: (RunState) -> Unit = {},
) {
    private companion object {
        /** A popup that lingers would otherwise be tapped on every frame. */
        const val WATCHER_COOLDOWN_MS = 2_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val watcherFiredAt = HashMap<String, Long>()

    private var i = 0
    private var loop = 1
    private var stepStart = 0L
    private var heldFor = 0L

    /** When the next frame is due, so Pause can give back the rest of a delay instead of eating it. */
    private var tickDueAt = 0L
    private var heldTick = 0L
    private var running = false
    private var paused = false

    val active get() = running
    val isPaused get() = paused

    fun start() {
        if (macro.steps.isEmpty()) {
            onState("nothing recorded yet")
            return
        }
        i = 0
        loop = 1
        running = true
        paused = false
        stepStart = System.currentTimeMillis()
        schedule(0)
    }

    /** Holds the step clock, so resuming does not immediately time the step out. */
    fun pause() {
        if (!running || paused) return
        paused = true
        val now = System.currentTimeMillis()
        heldFor = now - stepStart
        // Pausing inside a post-step delay must not swallow the rest of it: that delay is what
        // keeps the next step from matching the screen the app has not navigated away from yet.
        heldTick = (tickDueAt - now).coerceAtLeast(0)
        main.removeCallbacks(tick)
        say("paused by you  ${where()}")
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        stepStart = System.currentTimeMillis() - heldFor
        schedule(heldTick)
    }

    private fun schedule(delayMs: Long) {
        tickDueAt = System.currentTimeMillis() + delayMs
        main.postDelayed(tick, delayMs)
    }

    fun stop(why: String) {
        if (!running) return
        running = false
        paused = false
        main.removeCallbacks(tick)
        say(why)
    }

    private fun halt(why: String) {
        stop(why)
        Toast.makeText(svc, "Bunshin: $why", Toast.LENGTH_LONG).show()
    }

    private fun where(): String {
        val of = if (macro.loops == 0) "$loop" else "$loop/${macro.loops}"
        return "step ${i + 1}/${macro.steps.size}  loop $of"
    }

    /** The most recent score for the current step, so the pill has a number between frames. */
    private var lastScore = 0.0

    /**
     * Reports progress as values alongside the string [onState] already sends. Purely additive:
     * it reads the same fields [where] does and changes no control flow.
     */
    private fun report(pausedFor: String? = null) {
        onRun(
            RunState(
                step = i + 1,
                steps = macro.steps.size,
                loop = loop,
                loops = macro.loops,
                score = lastScore,
                paused = paused || pausedFor != null,
                pausedFor = pausedFor,
            )
        )
    }

    /** [onState] and [report] together, for the common case. */
    private fun say(text: String, pausedFor: String? = null) {
        onState(text)
        report(pausedFor)
    }

    private val tick = Runnable {
        if (!running || paused) return@Runnable
        // An open editor is our own screen. Capturing now would score the editor, every template
        // would read near the not-on-screen baseline, and the step would burn its timeout -- all
        // with the overlay gated off and no Stop anywhere. Hold the clock instead, exactly as the
        // foreground guard does, and take no frame at all.
        if (svc.editorInFront()) {
            stepStart = System.currentTimeMillis()
            say("paused: the editor is open", "editor open")
            again()
            return@Runnable
        }
        svc.capture { frame -> if (running && !paused) onFrame(frame) }
    }

    private fun again() {
        if (running && !paused) schedule(ShadowService.CAPTURE_INTERVAL_MS)
    }

    private fun onFrame(frame: Bitmap?) {
        if (frame == null) {
            again(); return
        }
        if (i >= macro.steps.size) {
            // The step list was edited out from under us.
            stop("macro changed while running")
            return
        }

        // No-tap rules win over everything, and never dispatch anything.
        for (s in macro.stops) {
            val m = Templates.match(svc, frame, s) ?: continue
            Scores.put(s.template, m.score)
            if (m.score >= s.threshold) {
                halt("stopped by a no-tap rule (%.3f)".format(m.score))
                return
            }
        }

        val fg = svc.currentPackage()
        val want = macro.pkgs
        // A null reading pauses too: a lookup that quietly fails must not disable the guard.
        if (want.isNotEmpty() && fg !in want) {
            // Hold the clock, or coming back would time the step out at once.
            stepStart = System.currentTimeMillis()
            say("paused: ${fg ?: "unknown"} in front, not one of the ${want.size} recorded", fg ?: "unknown")
            again(); return
        }

        // Watchers fire whatever step we are on. The step clock keeps running, so a popup that
        // will not dismiss still ends the run on the step timeout instead of looping for ever.
        val now = System.currentTimeMillis()
        for (w in macro.watchers) {
            if (now - (watcherFiredAt[w.template] ?: 0L) < WATCHER_COOLDOWN_MS) continue
            val m = Templates.match(svc, frame, w) ?: continue
            Scores.put(w.template, m.score)
            if (m.score < w.threshold) continue
            watcherFiredAt[w.template] = now
            say("watcher %.3f  %s  %s".format(m.score, w.act, where()))
            dispatch(w, m) { ok ->
                if (!running) return@dispatch
                if (!ok) return@dispatch halt("the system rejected a watcher gesture")
                schedule(w.postDelayMs)
            }
            return
        }

        val step = macro.steps[i]
        val m = Templates.match(svc, frame, step)
        val score = m?.score ?: 0.0
        lastScore = score
        Scores.put(step.template, score)
        if (m != null && score >= step.threshold) {
            say("%s  %.3f  %s".format(where(), score, step.act))
            dispatch(step, m) { ok ->
                if (!running) return@dispatch
                if (!ok) return@dispatch halt("the system rejected the gesture on step ${i + 1}")
                advance(step)
            }
            return
        }

        val waited = System.currentTimeMillis() - stepStart
        if (waited > step.timeoutMs) {
            halt("timed out on step %d after %ds (best %.3f)".format(i + 1, waited / 1000, score))
            return
        }
        say("%s waiting  %.3f  %ds".format(where(), score, waited / 1000))
        again()
    }

    private fun advance(step: Step) {
        i++
        if (i < macro.steps.size) {
            stepStart = System.currentTimeMillis()
            schedule(step.postDelayMs)
            return
        }
        if (macro.loops != 0 && loop >= macro.loops) {
            running = false
            say("done: ${macro.steps.size} steps x $loop")
            return
        }
        loop++
        i = 0
        stepStart = System.currentTimeMillis()
        schedule(step.postDelayMs)
    }

    private fun dispatch(s: Step, m: MatchResult, done: (Boolean) -> Unit) {
        val off = Templates.offset(svc, s, m) ?: return halt("template ${s.template} is missing")
        svc.gesture(s, off.first, off.second, done)
    }
}
