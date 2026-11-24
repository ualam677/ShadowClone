package com.shadowclone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Milestone 1 overlay: crop a template off the screen, watch for it live, tap where it matched.
 * Two windows: a full-screen HUD that only draws, and a small touchable control panel.
 */
class Overlay(private val svc: ShadowService) {

    private companion object {
        const val TEMPLATE_FILE = "m1_template.png"
        const val TAP_THRESHOLD = 0.8
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val hud = HudView(svc)
    private val readout = TextView(svc)
    private val panel = LinearLayout(svc)
    private val watchBtn = Button(svc)

    private var template: Bitmap? = null
    private var cropSource: Bitmap? = null
    private var watching = false
    private var last: MatchResult? = null
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
        readout.text = "no template"

        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0x99000000.toInt())
        panel.addView(readout)

        // Two rows: six buttons in one row overflow 1080 px and the last ones fall off screen.
        val top = LinearLayout(svc)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(button("Crop") { startCrop() })
        top.addView(watchBtn.also { b ->
            b.text = "Watch"
            b.textSize = 11f
            b.setOnClickListener { if (watching) stopWatch() else startWatch() }
        })
        top.addView(button("Tap") { tapMatch() })
        panel.addView(top)

        val bottom = LinearLayout(svc)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.addView(button("Frame") { saveFrame() })
        bottom.addView(button("Test") { selfTest() })
        bottom.addView(button("Move") { movePanel() })
        panel.addView(bottom)

        hud.onCropDone = { r -> finishCrop(r) }
        wm.addView(hud, hudParams)
        wm.addView(panel, panelParams)

        // Survive a service restart: the last template is on disk.
        val saved = File(svc.filesDir, TEMPLATE_FILE)
        if (saved.exists()) {
            template = BitmapFactory.decodeFile(saved.path)
            template?.let { readout.text = "template ${it.width}x${it.height} (restored)" }
        }
    }

    fun hide() {
        watching = false
        runCatching { wm.removeView(panel) }
        runCatching { wm.removeView(hud) }
    }

    /** Called around gesture dispatch so the injected touch reaches the app underneath, not us. */
    fun setTouchable(touchable: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        panelParams.flags =
            if (touchable) panelParams.flags and flag.inv() else panelParams.flags or flag
        runCatching { wm.updateViewLayout(panel, panelParams) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(svc).apply {
        text = label
        textSize = 11f
        setOnClickListener { onClick() }
    }

    private fun movePanel() {
        panelAtTop = !panelAtTop
        panelParams.gravity = (if (panelAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
        wm.updateViewLayout(panel, panelParams)
    }

    private fun startCrop() {
        stopWatch()
        readout.text = "cropping: capturing frame..."
        svc.capture { frame ->
            if (frame == null) {
                readout.text = "capture failed"
                return@capture
            }
            cropSource = frame
            hud.cropMode = true
            setHudTouchable(true)
            readout.text = "drag a box around the thing to match"
        }
    }

    private fun finishCrop(r: Rect) {
        hud.cropMode = false
        setHudTouchable(false)
        val src = cropSource ?: return
        val clipped = Rect(
            r.left.coerceIn(0, src.width - 1), r.top.coerceIn(0, src.height - 1),
            r.right.coerceIn(1, src.width), r.bottom.coerceIn(1, src.height)
        )
        if (clipped.width() < 8 || clipped.height() < 8) {
            readout.text = "crop too small"
            return
        }
        val t = Bitmap.createBitmap(src, clipped.left, clipped.top, clipped.width(), clipped.height())
        template = t
        FileOutputStream(File(svc.filesDir, TEMPLATE_FILE)).use {
            t.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        hud.matchBox = null
        hud.invalidate()
        readout.text = "template ${clipped.width()}x${clipped.height()} at (${clipped.left},${clipped.top})"
    }

    private fun setHudTouchable(touchable: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        hudParams.flags =
            if (touchable) hudParams.flags and flag.inv() else hudParams.flags or flag
        wm.updateViewLayout(hud, hudParams)
    }

    private fun startWatch() {
        if (template == null) {
            readout.text = "crop a template first"
            return
        }
        watching = true
        watchBtn.text = "Stop"
        main.post(tick)
    }

    private fun stopWatch() {
        watching = false
        watchBtn.text = "Watch"
        main.removeCallbacks(tick)
    }

    // Matching runs on the main thread: ~40 ms every 380 ms. Move it off-thread when replay needs the headroom.
    private val tick = object : Runnable {
        override fun run() {
            if (!watching) return
            val t = template ?: return
            // takeScreenshot captures our own windows too, so the match box we drew last tick would
            // sit in this frame and drag the score down. Hide it, let the compositor catch up, shoot.
            hud.visibility = View.INVISIBLE
            main.postDelayed({ shoot(t) }, 40L)
        }
    }

    private fun shoot(t: Bitmap) {
        svc.capture { frame ->
            hud.visibility = View.VISIBLE
                if (frame == null) {
                    readout.text = "capture failed"
                } else {
                    val m = Matcher.match(frame, t)
                    last = m
                    hud.matchBox = m?.let {
                        Rect(
                            it.cx - t.width / 2, it.cy - t.height / 2,
                            it.cx + t.width / 2, it.cy + t.height / 2
                        )
                    }
                    hud.invalidate()
                    readout.text = if (m == null) "no result" else
                        "score %.3f  at (%d,%d)  %dms  fg=%s".format(
                            m.score, m.cx, m.cy, m.ms, svc.foregroundPackage ?: "?"
                        )
                }
            if (watching) main.postDelayed(tick, ShadowService.CAPTURE_INTERVAL_MS)
        }
    }

    private fun tapMatch() {
        val m = last
        if (m == null) {
            readout.text = "nothing matched yet"
            return
        }
        // Never tap a guess: the best location is still returned when nothing on screen matches.
        if (m.score < TAP_THRESHOLD) {
            readout.text = "score %.3f below %.2f - not tapping".format(m.score, TAP_THRESHOLD)
            return
        }
        readout.text = "tapping (${m.cx},${m.cy})..."
        svc.tap(m.cx.toFloat(), m.cy.toFloat()) { ok ->
            readout.text = "tap at (${m.cx},${m.cy}) ${if (ok) "done" else "FAILED"}"
        }
    }

    private fun saveFrame() {
        val f = svc.latestFrame
        if (f == null) {
            readout.text = "no frame yet"
            return
        }
        val out = File(svc.filesDir, "frame.png")
        FileOutputStream(out).use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
        readout.text = "saved ${f.width}x${f.height} -> ${out.name}"
    }

    private fun selfTest() {
        val f = svc.latestFrame
        if (f == null) {
            readout.text = "no frame yet (use Crop or Watch once)"
            return
        }
        readout.text = Matcher.selfTest(f)
    }
}
