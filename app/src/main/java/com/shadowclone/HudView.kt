package com.shadowclone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Full-screen transparent view: draws the match box, and catches the drag when cropping a template. */
class HudView(context: Context) : View(context) {

    var matchBox: Rect? = null
    var cropMode = false
    var onCropDone: ((Rect) -> Unit)? = null

    private var selection: Rect? = null
    private var startX = 0f
    private var startY = 0f

    private val green = Paint().apply {
        color = 0xFF00FF66.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val yellow = Paint().apply {
        color = 0xFFFFCC00.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f
    }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        matchBox?.let { canvas.drawRect(it, green) }
        selection?.let { canvas.drawRect(it, yellow) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!cropMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                selection = Rect(); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                selection = rect(startX, startY, event.rawX, event.rawY); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val r = rect(startX, startY, event.rawX, event.rawY)
                selection = null; invalidate()
                onCropDone?.invoke(r)
            }
        }
        return true
    }

    private fun rect(x1: Float, y1: Float, x2: Float, y2: Float) = Rect(
        min(x1, x2).toInt(), min(y1, y2).toInt(), max(x1, x2).toInt(), max(y1, y2).toInt()
    )
}
