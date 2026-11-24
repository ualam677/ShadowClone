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
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val hud = HudView(svc)
    private val readout = TextView(svc)
    private val panel = LinearLayout(svc)
    private val recBtn = Button(svc)
    private val noTapBtn = Button(svc)

    private var macro = Macro.load(svc)
    private var runner: Runner? = null
    private var recording = false
    private var recordingStops = false

    /** Set from touch-up until the step is saved and re-dispatched; further touches are refused
     *  rather than passed through, or the recording would drift from what the app actually got. */
    private var busy = false
    private var panelAtTop = false

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
        gravity = Gravity.BOTTOM or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun show() {
        readout.setBackgroundColor(0xCC000000.toInt())
        readout.setTextColor(0xFFFFFFFF.toInt())
        readout.textSize = 11f
        readout.setPadding(12, 8, 12, 8)

        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0x99000000.toInt())
        panel.addView(readout)

        // Three per row: six buttons in one row overflow 1080 px and the last ones fall off screen.
        panel.addView(
            row(
                recBtn.also { style(it, "Rec") { arm(stops = false) } },
                noTapBtn.also { style(it, "No-Tap") { arm(stops = true) } },
                button("Play") { play() },
            )
        )
        panel.addView(
            row(
                button("Stop") { stopAll("stopped") },
                button("Clear") { clear() },
                button("Test") { selfTest() },
            )
        )
        panel.addView(
            row(
                button("Move") { movePanel() },
                button("Hide") { main.post { svc.toggleOverlay() } },
            )
        )

        hud.onGesture = { sx, sy, ex, ey, held -> recorded(sx, sy, ex, ey, held) }
        wm.addView(hud, hudParams)
        wm.addView(panel, panelParams)
        status()
    }

    fun hide() {
        // Never leave a run or a recording going with no Stop button on screen.
        stopAll("stopped")
        runCatching { wm.removeView(panel) }
        runCatching { wm.removeView(hud) }
    }

    /** Called around gesture dispatch so the injected touch reaches the app underneath, not us. */
    fun setTouchable(touchable: Boolean) {
        hud.blocked = !touchable
        flag(panel, panelParams, touchable)
        flag(hud, hudParams, touchable && (recording || recordingStops))
    }

    private fun flag(v: View, p: WindowManager.LayoutParams, touchable: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (touchable) p.flags and f.inv() else p.flags or f
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun row(vararg views: View) = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun style(b: Button, label: String, onClick: () -> Unit) {
        b.text = label
        b.textSize = 11f
        b.setOnClickListener { onClick() }
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(svc).also { style(it, label, onClick) }

    private fun movePanel() {
        panelAtTop = !panelAtTop
        panelParams.gravity = (if (panelAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
        wm.updateViewLayout(panel, panelParams)
    }

    // ---- recording ----

    private fun arm(stops: Boolean) {
        // One capture loop at a time: concurrent takeScreenshot calls hit the rate limit and fail.
        runner?.stop("stopped")
        runner = null
        recording = !stops
        recordingStops = stops
        hud.recordMode = true
        hud.matchBox = null
        if (macro.pkg == null) macro.pkg = svc.currentPackage()
        setTouchable(true)
        hud.invalidate()
        recBtn.text = if (stops) "Rec" else "REC *"
        noTapBtn.text = if (stops) "NOTAP *" else "No-Tap"
        status(if (stops) "touch what must NEVER be tapped" else "use the app: every touch is a step")
    }

    private fun disarm() {
        recording = false
        recordingStops = false
        hud.recordMode = false
        setTouchable(true)
        hud.invalidate()
        recBtn.text = "Rec"
        noTapBtn.text = "No-Tap"
    }

    private fun recorded(sx: Float, sy: Float, ex: Float, ey: Float, heldMs: Long) {
        Log.i(ShadowService.TAG, "touch ($sx,$sy)->($ex,$ey) ${heldMs}ms busy=$busy stops=$recordingStops")
        if (busy) {
            status("too fast - wait for the step to save")
            return
        }
        busy = true
        val stop = recordingStops
        val act = if (stop) Act.STOP else Macro.classify(ex - sx, ey - sy, heldMs)
        // takeScreenshot catches our own windows, so anything the HUD is drawing would end up in
        // the template and drag the score down at replay, when it is not there.
        hud.showMarks = false
        hud.matchBox = null
        hud.invalidate()
        // We ate the touch, so the app has not reacted yet: a frame taken now is the screen the
        // user acted on, which is exactly the screen replay has to wait for.
        main.postDelayed({ svc.captureWhenAllowed { frame -> saveStep(frame, sx, sy, ex, ey, heldMs, act, stop) } }, 40L)
    }

    private fun saveStep(
        frame: Bitmap?, sx: Float, sy: Float, ex: Float, ey: Float,
        heldMs: Long, act: Act, stop: Boolean,
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
        val name = (if (stop) "s" else "t") + System.currentTimeMillis() + ".png"
        FileOutputStream(File(Macro.dir(svc), name)).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val step = Step(
            act = act, tx = box.left, ty = box.top,
            x = sx.toInt(), y = sy.toInt(), x2 = ex.toInt(), y2 = ey.toInt(),
            durationMs = heldMs.coerceIn(60L, 10_000L), template = name,
            threshold = if (stop) Macro.STOP_THRESHOLD else Macro.STEP_THRESHOLD,
        )
        if (stop) macro.stops += step else macro.steps += step
        Macro.save(svc, macro)
        hud.matchBox = box
        hud.invalidate()
        Log.i(ShadowService.TAG, "record: saved $act $name box=$box stops=$stop")

        if (stop) {
            // A no-tap screen must never be touched, so this touch is not passed through.
            busy = false
            status("no-tap ${macro.stops.size} saved, touch NOT passed through")
        } else {
            svc.gesture(step, 0, 0) { ok ->
                busy = false
                status("step ${macro.steps.size}: $act" + if (ok) "" else " (not passed through!)")
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

    private fun play() {
        disarm()
        runner?.stop("stopped")
        if (macro.steps.isEmpty()) {
            status("nothing recorded yet")
            return
        }
        hud.matchBox = null
        hud.invalidate()
        runner = Runner(svc, macro) { s -> readout.text = s }.also { it.start() }
    }

    private fun stopAll(why: String) {
        disarm()
        runner?.stop(why)
        runner = null
        busy = false
        status(why)
    }

    private fun clear() {
        stopAll("stopped")
        Macro.dir(svc).listFiles()?.forEach { it.delete() }
        macro = Macro()
        hud.matchBox = null
        hud.invalidate()
        status("cleared")
    }

    private fun selfTest() {
        val f = svc.latestFrame
        readout.text = Macro.selfTest() + "\n" +
            if (f == null) "(no frame yet - Rec or Play once)" else Matcher.selfTest(f)
    }

    private fun status(extra: String? = null) {
        val base = "${macro.steps.size} steps, ${macro.stops.size} no-tap, app=${macro.pkg ?: "?"}"
        readout.text = if (extra == null) base else "$extra\n$base"
    }
}
