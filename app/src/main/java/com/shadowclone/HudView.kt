package com.shadowclone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen transparent view. Draws the match box, and while recording it eats the user's touches
 * so they can be stored as steps (the service re-dispatches them afterwards).
 */
class HudView(context: Context) : View(context) {

    var matchBox: Rect? = null

    /** Eat touches and report them as gestures. */
    var recordMode = false

    /** Set around gesture dispatch: the FLAG_NOT_TOUCHABLE flip is async, so our own injected
     *  gesture can still arrive here and would be recorded as a step, which then loops. */
    var blocked = false

    /** (startX, startY, endX, endY, heldMs) in screen pixels. */
    var onGesture: ((Float, Float, Float, Float, Long) -> Unit)? = null

    private var sx = 0f
    private var sy = 0f
    private var down = 0L

    private val green = Paint().apply {
        color = 0xFF00FF66.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val red = Paint().apply {
        color = 0xFFFF3B30.toInt(); style = Paint.Style.STROKE; strokeWidth = 10f
    }

    init {
        setWillNotDraw(false)
    }

    /** Cleared while a template is being captured: whatever we draw lands in the screenshot. */
    var showMarks = true

    override fun onDraw(canvas: Canvas) {
        if (!showMarks) return
        if (recordMode) canvas.drawRect(5f, 5f, width - 5f, height - 5f, red)
        matchBox?.let { canvas.drawRect(it, green) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (blocked || !recordMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.rawX; sy = event.rawY; down = event.eventTime
            }
            MotionEvent.ACTION_UP ->
                onGesture?.invoke(sx, sy, event.rawX, event.rawY, event.eventTime - down)
        }
        return true
    }
}
