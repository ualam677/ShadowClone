package com.shadowclone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
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
        const val TAG = "ShadowClone"
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var overlay: Overlay? = null

    @Volatile
    var latestFrame: Bitmap? = null

    @Volatile
    var foregroundPackage: String? = null

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Our own windows and the shade must not count as "the app moved to the foreground".
        if (pkg != packageName && pkg != "com.android.systemui") foregroundPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "unbind ${System.identityHashCode(this)}")
        overlay?.hide()
        overlay = null
        instance = null
        return super.onUnbind(intent)
    }

    fun capture(onFrame: (Bitmap?) -> Unit) {
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

    /**
     * dispatchGesture injects at the input layer, so it lands on the topmost touchable window --
     * ours. Make the overlay untouchable, let the window manager apply it, then dispatch.
     */
    fun tap(x: Float, y: Float, done: (Boolean) -> Unit) {
        val ov = overlay
        ov?.setTouchable(false)
        main.postDelayed({
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    ov?.setTouchable(true); done(true)
                }

                override fun onCancelled(d: GestureDescription?) {
                    ov?.setTouchable(true); done(false)
                }
            }, null)
            if (!accepted) {
                ov?.setTouchable(true); done(false)
            }
        }, 60L)
    }
}
