package com.shadowclone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

class ShadowService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ShadowService? = null

        /** takeScreenshot is rate limited to roughly one call per 333 ms; stay above it. */
        const val CAPTURE_INTERVAL_MS = 380L

        /** The FLAG_NOT_TOUCHABLE flip is applied asynchronously; give the window manager a frame. */
        const val TOUCHABLE_SETTLE_MS = 60L
        const val TAG = "ShadowClone"
    }

    /**
     * Packages that must not count as "another app came to the front", or touching the overlay,
     * pulling down the shade or typing would pause a run. `com.oplus.games` is the OnePlus game
     * shell, which raises window events over the game that is actually running.
     */
    private val ignoredPackages: Set<String> by lazy {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        setOfNotNull(packageName, "com.android.systemui", "com.oplus.games", ime)
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var overlay: Overlay? = null

    @Volatile
    var latestFrame: Bitmap? = null

    @Volatile
    var foregroundPackage: String? = null

    @Volatile
    private var lastCaptureAt = 0L

    override fun onServiceConnected() {
        OpenCVLoader.initLocal()
        instance = this
        // Which of the two happens -- rebind of this instance, or a fresh instance -- decides what
        // M2 has to do to keep a run alive, so make it identifiable in logcat.
        Log.i(TAG, "connected ${System.identityHashCode(this)}")
        // onServiceConnected can fire again on an already running service; a second Overlay would
        // stack an empty panel on top of the live one and lose its state.
        if (overlay == null) overlay = Overlay(this).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /**
     * Which app the user is actually looking at.
     *
     * Window-state events are not enough: coming back to the OnePlus launcher fires none, so the
     * last value would stay stale and a run would sit paused for ever. Ask for the active window
     * instead, and keep the last known app while ours, the shade or the keyboard is active.
     */
    fun currentPackage(): String? {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (pkg != null && pkg !in ignoredPackages) foregroundPackage = pkg
        return foregroundPackage
    }

    override fun onInterrupt() {}

    /** The panel sits over the gesture-nav area, so there has to be a way to get it out of the way. */
    fun toggleOverlay(): Boolean {
        val ov = overlay
        return if (ov != null) {
            ov.hide(); overlay = null; false
        } else {
            overlay = Overlay(this).also { it.show() }; true
        }
    }

    fun overlayVisible() = overlay != null

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "unbind ${System.identityHashCode(this)}")
        overlay?.hide()
        overlay = null
        instance = null
        return super.onUnbind(intent)
    }

    fun capture(onFrame: (Bitmap?) -> Unit) {
        lastCaptureAt = System.currentTimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bmp != null) latestFrame = bmp
                main.post { onFrame(bmp) }
            }

            override fun onFailure(errorCode: Int) {
                main.post { onFrame(null) }
            }
        })
    }

    /** takeScreenshot fails if called inside its rate-limit window, so wait that window out. */
    fun captureWhenAllowed(onFrame: (Bitmap?) -> Unit) {
        val wait = lastCaptureAt + CAPTURE_INTERVAL_MS - System.currentTimeMillis()
        main.postDelayed({ capture(onFrame) }, wait.coerceAtLeast(0L))
    }

    /**
     * Performs [step]'s gesture, shifted by ([dx],[dy]) from where it was recorded.
     *
     * dispatchGesture injects at the input layer, so it lands on the topmost touchable window --
     * ours. Make the overlay untouchable, let the window manager apply it, then dispatch.
     */
    fun gesture(step: Step, dx: Int, dy: Int, done: (Boolean) -> Unit) {
        val ov = overlay
        ov?.setTouchable(false)
        main.postDelayed({
            fun finish(ok: Boolean) {
                ov?.setTouchable(true); done(ok)
            }
            // A stroke whose path leaves the display throws, so never let that kill the service.
            val g = runCatching { build(step, dx, dy) }.getOrNull() ?: return@postDelayed finish(false)
            val accepted = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) = finish(true)
                override fun onCancelled(d: GestureDescription?) = finish(false)
            }, null)
            if (!accepted) finish(false)
        }, TOUCHABLE_SETTLE_MS)
    }

    private fun build(step: Step, dx: Int, dy: Int): GestureDescription {
        val path = Path().apply {
            moveTo((step.x + dx).toFloat(), (step.y + dy).toFloat())
            if (step.act == Act.SWIPE) lineTo((step.x2 + dx).toFloat(), (step.y2 + dy).toFloat())
        }
        val ms = if (step.act == Act.TAP) 60L else step.durationMs.coerceIn(60L, 10_000L)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, ms))
            .build()
    }
}
