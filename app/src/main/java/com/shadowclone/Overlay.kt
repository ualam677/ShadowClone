package com.shadowclone

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Record / replay controls. Two windows: the HUD draws, and eats touches while recording; the panel
 * holds the buttons and is never recorded, because touches on it never reach the HUD.
 */
class Overlay(private val svc: ShadowService) {

    private companion object {
        /** Below this grayscale spread a crop is near-flat and would match almost any screen. */
        const val FLAT_STDDEV = 8.0
        val CROP_HALF = intArrayOf(100, 200, 320)
        val PREFIX = mapOf(Kind.STEP to "t", Kind.WATCH to "w", Kind.STOP to "n")
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val hud = HudView(svc)
    private val dock = Dock(svc) { key -> onKey(key) }
    private val prefs =
        svc.getSharedPreferences("overlay", android.content.Context.MODE_PRIVATE)

    /** Always the process-wide macro: the editor mutates the same object. */
    private val macro get() = Macro.current(svc)

    private var armed: Kind? = null

    /** Set from touch-up until the step is saved and re-dispatched; further touches are refused
     *  rather than passed through, or the recording would drift from what the app actually got. */
    private var busy = false

    private val hudParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val panelParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        // TOP|START plus x/y, so the dock can be dropped anywhere instead of toggled
        // between two corners.
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun show() {
        panelParams.x = prefs.getInt("x", 0)
        panelParams.y = prefs.getInt("y", 0)

        // The run belongs to the service; the dock is one view onto it.
        svc.onRunText = { s ->
            dock.say(s)
            labels()
        }
        svc.onRun = { }

        hud.onGesture = { sx, sy, ex, ey, held -> recorded(sx, sy, ex, ey, held) }
        wm.addView(hud, hudParams)
        wm.addView(dock.view, panelParams)
        dock.onDrag(
            onMove = { dx, dy -> moveBy(dx, dy) },
            onDrop = {
                prefs.edit().putInt("x", panelParams.x).putInt("y", panelParams.y).apply()
            },
        )
        // First show: park it at the bottom-left, clear of the gesture area.
        if (!prefs.contains("x")) dock.view.post { moveBy(0f, Float.MAX_VALUE) }
        status()
        labels()
    }

    /** One key press from the dock. */
    private fun onKey(key: Key) {
        // Logged because a Clear once had no effect and there was no way to tell whether the
        // touch reached the button at all.
        Log.i(ShadowService.TAG, "panel $key")
        when (key) {
            Key.REC -> arm(Kind.STEP)
            Key.NO_TAP -> arm(Kind.STOP)
            Key.WATCH -> arm(Kind.WATCH)
            Key.PLAY -> playPause()
            Key.STOP -> stopAll("stopped")
            Key.MORE -> dock.toggleSheet()
            Key.SCORE -> { dock.closeSheet(); score() }
            Key.CLEAR -> { dock.closeSheet(); clear() }
            Key.HIDE -> {
                // Hide is the user asking for the panel to go. A run with no panel has no
                // Stop, so it ends here rather than carrying on invisibly.
                svc.stopRun("stopped")
                main.post { svc.toggleOverlay() }
            }
        }
    }

    /**
     * Moves the dock, clamped so no part of it can leave the display. Stop lives on this bar, and
     * a dock dragged half off screen would be a Stop that cannot be pressed.
     */
    private fun moveBy(dx: Float, dy: Float) {
        val bounds = wm.currentWindowMetrics.bounds
        val insets = wm.currentWindowMetrics.windowInsets
            .getInsets(android.view.WindowInsets.Type.systemBars())
        val w = dock.view.width.takeIf { it > 0 } ?: dock.width
        val h = dock.view.height.takeIf { it > 0 } ?: 0
        panelParams.x = (panelParams.x + dx).toInt()
            .coerceIn(0, (bounds.width() - w).coerceAtLeast(0))
        panelParams.y = (panelParams.y + dy).toInt()
            .coerceIn(insets.top, (bounds.height() - h - insets.bottom).coerceAtLeast(insets.top))
        runCatching { wm.updateViewLayout(dock.view, panelParams) }
    }

    /**
     * Takes the panel off screen. Deliberately does NOT stop the run: the editor hides the panel
     * too, and there the run only pauses. The Hide button stops the run itself before calling
     * this, so pressing Hide never leaves a run going with no Stop.
     */
    fun hide() {
        disarm()
        busy = false
        svc.onRunText = null
        svc.onRun = null
        runCatching { wm.removeView(dock.view) }
        runCatching { wm.removeView(hud) }
    }

    /** Called around gesture dispatch so the injected touch reaches the app underneath, not us. */
    fun setTouchable(touchable: Boolean) {
        hud.blocked = !touchable
        flag(dock.view, panelParams, touchable)
        flag(hud, hudParams, touchable && armed != null)
    }

    private fun flag(v: View, p: WindowManager.LayoutParams, touchable: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (touchable) p.flags and f.inv() else p.flags or f
        runCatching { wm.updateViewLayout(v, p) }
    }

    // ---- recording ----

    private fun arm(kind: Kind) {
        if (armed == kind) {
            disarm()
            status("not recording")
            return
        }
        // One capture loop at a time: concurrent takeScreenshot calls hit the rate limit and fail.
        svc.stopRun("stopped")
        armed = kind
        hud.recordMode = true
        hud.matchBox = null
        svc.currentPackage()?.let { macro.pkgs += it }
        setTouchable(true)
        hud.invalidate()
        labels()
        status(
            when (kind) {
                Kind.STEP -> Macro.replaceAt?.let { "touch the new spot for step ${it + 1}" }
                    ?: "use the app: every touch is a step"
                Kind.WATCH -> "touch what should be handled whenever it appears"
                Kind.STOP -> "touch what must NEVER be tapped"
            }
        )
    }

    private fun disarm() {
        armed = null
        hud.recordMode = false
        setTouchable(true)
        hud.invalidate()
        labels()
    }

    private fun labels() {
        dock.setArmed(armed)
        dock.setRunning(svc.runActive)
        dock.setPlayLabel(
            when {
                !svc.runActive -> "Play"
                svc.runPaused -> "Resume"
                else -> "Pause"
            }
        )
    }

    private fun recorded(sx: Float, sy: Float, ex: Float, ey: Float, heldMs: Long) {
        val kind = armed ?: return
        Log.i(ShadowService.TAG, "touch ($sx,$sy)->($ex,$ey) ${heldMs}ms busy=$busy kind=$kind")
        if (busy) {
            status("too fast - wait for the step to save")
            return
        }
        busy = true
        val act = if (kind == Kind.STOP) Act.STOP else Macro.classify(ex - sx, ey - sy, heldMs)
        // takeScreenshot catches our own windows, so anything the HUD is drawing would end up in
        // the template and drag the score down at replay, when it is not there.
        hud.showMarks = false
        hud.matchBox = null
        hud.invalidate()
        // We ate the touch, so the app has not reacted yet: a frame taken now is the screen the
        // user acted on, which is exactly the screen replay has to wait for.
        main.postDelayed({
            svc.captureWhenAllowed { frame -> saveStep(frame, sx, sy, ex, ey, heldMs, act, kind) }
        }, 40L)
    }

    private fun saveStep(
        frame: Bitmap?, sx: Float, sy: Float, ex: Float, ey: Float,
        heldMs: Long, act: Act, kind: Kind,
    ) {
        hud.showMarks = true
        if (frame == null) {
            busy = false
            Log.w(ShadowService.TAG, "record: capture failed")
            status("capture failed")
            return
        }
        val c = crop(frame, sx.toInt(), sy.toInt())
        if (c == null) {
            busy = false
            Log.w(ShadowService.TAG, "record: crop too flat at ($sx,$sy)")
            status("that area is blank - touch something with detail")
            return
        }
        val (bmp, box) = c
        val name = PREFIX.getValue(kind) + System.currentTimeMillis() + ".png"
        FileOutputStream(File(Macro.dir(svc), name)).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        var step = Step(
            act = act, tx = box.left, ty = box.top,
            x = sx.toInt(), y = sy.toInt(), x2 = ex.toInt(), y2 = ey.toInt(),
            durationMs = heldMs.coerceIn(60L, 10_000L), template = name,
            threshold = if (kind == Kind.STOP) Macro.STOP_THRESHOLD else Macro.STEP_THRESHOLD,
        )

        val replace = Macro.replaceAt?.takeIf { kind == Kind.STEP && it in macro.steps.indices }
        if (replace != null) {
            // Re-record keeps everything the editor tuned, and only moves the picture and the touch.
            val old = macro.steps[replace]
            step = step.copy(
                threshold = old.threshold, timeoutMs = old.timeoutMs,
                postDelayMs = old.postDelayMs, searchPad = old.searchPad,
            )
            Templates.forget(svc, old.template)
            macro.steps[replace] = step
        } else {
            macro.list(kind) += step
        }
        // Part of an app's own flow can live in another package, so collect them as we record.
        svc.currentPackage()?.let { macro.pkgs += it }
        Macro.replaceAt = null
        Macro.save(svc, macro)
        hud.matchBox = box
        hud.invalidate()
        Log.i(ShadowService.TAG, "record: saved $act $name box=$box kind=$kind replace=$replace")

        if (kind == Kind.STOP) {
            // A no-tap screen must never be touched, so this touch is not passed through.
            busy = false
            disarm()
            status("no-tap ${macro.stops.size} saved, touch NOT passed through")
        } else {
            svc.gesture(step, 0, 0) { ok ->
                busy = false
                if (replace != null) disarm()
                val what = if (kind == Kind.WATCH) "watcher ${macro.watchers.size}"
                else if (replace != null) "step ${replace + 1} re-recorded"
                else "step ${macro.steps.size}"
                status("$what: $act" + if (ok) "" else " (not passed through!)")
            }
        }
    }

    /** Flat crops false-match anything, so grow the box until it has some detail. */
    private fun crop(frame: Bitmap, cx: Int, cy: Int): Pair<Bitmap, Rect>? {
        for (half in CROP_HALF) {
            val l = (cx - half).coerceIn(0, frame.width - 32)
            val t = (cy - half).coerceIn(0, frame.height - 32)
            val r = (cx + half).coerceIn(l + 32, frame.width)
            val b = (cy + half).coerceIn(t + 32, frame.height)
            val bmp = Bitmap.createBitmap(frame, l, t, r - l, b - t)
            if (Matcher.stdDev(bmp) >= FLAT_STDDEV) return bmp to Rect(l, t, r, b)
        }
        return null
    }

    // ---- replay ----

    private fun playPause() {
        if (svc.runActive) {
            svc.togglePause()
            labels()
            return
        }
        disarm()
        hud.matchBox = null
        hud.invalidate()
        // The service owns the run; it reports "nothing recorded yet" through onRunText.
        svc.startRun()
        labels()
    }

    private fun stopAll(why: String) {
        disarm()
        svc.stopRun(why)
        busy = false
        labels()
        status(why)
    }

    private fun clear() {
        stopAll("stopped")
        Macro.wipe(svc)
        hud.matchBox = null
        hud.invalidate()
        status("cleared")
    }

    /**
     * Scores every trigger against the screen as it is now, the same way replay does, so a
     * threshold can be tuned against the real app instead of guessed.
     */
    private fun score() {
        if (svc.runActive) {
            status("stop the run first")
            return
        }
        if (macro.steps.isEmpty() && macro.watchers.isEmpty() && macro.stops.isEmpty()) {
            status("nothing recorded yet")
            return
        }
        disarm()
        hud.showMarks = false
        hud.matchBox = null
        hud.invalidate()
        dock.say("scoring...")
        main.postDelayed({
            svc.captureWhenAllowed { frame ->
                hud.showMarks = true
                if (frame == null) {
                    status("capture failed")
                    return@captureWhenAllowed
                }
                val lines = mutableListOf<String>()
                for ((tag, list) in listOf("S" to macro.steps, "W" to macro.watchers, "N" to macro.stops)) {
                    list.forEachIndexed { n, s ->
                        val sc = Templates.match(svc, frame, s)?.score ?: 0.0
                        // The step sheet cannot score live, so it reads what Score found here.
                        Scores.put(s.template, sc)
                        lines += "%s%d %.3f/%.2f %s".format(
                            tag, n + 1, sc, s.threshold, if (sc >= s.threshold) "HIT" else "-"
                        )
                    }
                }
                dock.say(lines.take(12).joinToString("\n"))
            }
        }, 40L)
    }

    private fun apps(): String {
        val p = macro.pkgs
        return when (p.size) {
            0 -> "?"
            1 -> p.first().substringAfterLast('.')
            else -> p.first().substringAfterLast('.') + "+${p.size - 1}"
        }
    }

    private fun status(extra: String? = null) {
        val loops = if (macro.loops == 0) "until stopped" else "x${macro.loops}"
        dock.setSummary(
            "${macro.steps.size} steps \u00b7 ${macro.watchers.size} watch \u00b7 " +
                "${macro.stops.size} no-tap \u00b7 $loops \u00b7 ${apps()}"
        )
        if (extra != null) dock.say(extra)
    }
}
