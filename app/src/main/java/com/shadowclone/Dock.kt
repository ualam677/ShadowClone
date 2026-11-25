package com.shadowclone

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper

/** What the user pressed. The overlay decides what each one does. */
enum class Key { REC, NO_TAP, WATCH, PLAY, MORE, STOP, PAUSE, SCORE, CLEAR, HIDE }

/**
 * The floating control bar.
 *
 * One row of equal keys behind a drag grip, replacing a 3x3 grid of system buttons that was
 * 260 x 168 dp of grey in the corner. Above it sits a message strip, because the keys have no
 * room for the recording prompts and errors the old readout carried -- and some of those,
 * like the re-record prompt, have no other home anywhere in the app.
 *
 * The dock is a fixed width. The old panel was WRAP_CONTENT, so it resized every tick as the
 * readout text changed and visibly jumped.
 */
class Dock(svc: ShadowService, private val onKey: (Key) -> Unit) {

    private val ctx = ContextThemeWrapper(svc, R.style.Theme_Bunshin)
    private val res = ctx.resources
    private val main = Handler(Looper.getMainLooper())

    private fun px(id: Int) = res.getDimensionPixelSize(id)
    private fun colour(id: Int) = res.getColor(id, ctx.theme)

    private val gutter = px(R.dimen.space_8)
    private val gap = px(R.dimen.space_4)
    private val keyW = px(R.dimen.dock_key_w)
    private val keyH = px(R.dimen.dock_key_h)
    private val gripW = px(R.dimen.dock_grip_w)

    /**
     * Fixed, so nothing the strip or the sheet says can resize the dock.
     *
     * Not named `width`: inside an `apply` on a View that would resolve to View.width, which is
     * 0 at construction, and the window silently comes out zero pixels wide.
     */
    val dockWidth = gutter * 2 + gripW + 5 * keyW + 5 * gap

    private val keys = HashMap<Key, LinearLayout>()
    private val glyphs = HashMap<Key, TextView>()
    private val labels = HashMap<Key, TextView>()

    private val strip = TextView(ctx).apply {
        setTextColor(colour(R.color.text))
        textSize = res.getDimension(R.dimen.text_secondary) / res.displayMetrics.scaledDensity
        setPadding(px(R.dimen.space_12), px(R.dimen.space_8), px(R.dimen.space_12), px(R.dimen.space_8))
        background = panelBackground()
        visibility = View.GONE
        maxLines = 6
    }

    private val sheet = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        setPadding(px(R.dimen.space_12), px(R.dimen.space_12), px(R.dimen.space_12), px(R.dimen.space_12))
        visibility = View.GONE
    }

    private val summary = TextView(ctx).apply {
        setTextColor(colour(R.color.muted))
        textSize = res.getDimension(R.dimen.text_secondary) / res.displayMetrics.scaledDensity
        setPadding(0, 0, 0, px(R.dimen.space_8))
    }

    private val grip = View(ctx).apply {
        // The view is a comfortable touch target; the visible bar is inset inside it, or the
        // grip reads as a sixth, blank key.
        val bar = GradientDrawable().apply {
            cornerRadius = px(R.dimen.space_4).toFloat() / 2
            setColor(colour(R.color.line))
        }
        val side = (px(R.dimen.dock_grip_w) - px(R.dimen.space_4)) / 2
        background = android.graphics.drawable.InsetDrawable(
            bar, side, px(R.dimen.space_12), side, px(R.dimen.space_12)
        )
    }

    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = panelBackground()
        setPadding(gutter, gutter, gutter, gutter)
    }

    // ---- the pill ----

    /** Fills clockwise with step/steps. Same open ring as the app mark. */
    private inner class Ring(c: android.content.Context) : View(c) {
        var progress = 0f
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = colour(R.color.line)
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = colour(R.color.primary)
        }
        private val box = android.graphics.RectF()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = px(R.dimen.space_4).toFloat() * 0.9f
            track.strokeWidth = w
            fill.strokeWidth = w
            val pad = w / 2 + 1
            box.set(pad, pad, width - pad, height - pad)
            // 285 degrees of arc with the gap at the top right, as the mark has it.
            canvas.drawArc(box, START, SWEEP, false, track)
            if (progress > 0f) canvas.drawArc(box, START, SWEEP * progress, false, fill)
        }
    }

    private val ring = Ring(ctx)

    private val pillText = TextView(ctx).apply {
        setTextColor(colour(R.color.text))
        textSize = res.getDimension(R.dimen.text_secondary) / res.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
        maxLines = 2
    }

    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val stopKey = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = keyBackground(colour(R.color.rec))
        addView(TextView(ctx).apply {
            text = "\u25A0"
            setTextColor(colour(R.color.rec))
            textSize = res.getDimension(R.dimen.dock_glyph) / res.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
        })
        addView(TextView(ctx).apply {
            text = "Stop"
            setTextColor(colour(R.color.text))
            textSize = res.getDimension(R.dimen.text_secondary) / res.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
        })
        setOnClickListener { onKey(Key.STOP) }
    }

    /**
     * Replaces the key row while a run is going. It keeps the row's exact footprint rather than
     * shrinking to a smaller pill: the panel is in both record frames and replay frames, and a
     * different shape at replay would mean replay frames contain pixels no record frame had.
     */
    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = panelBackground()
        setPadding(gutter, gutter, gutter, gutter)
        visibility = View.GONE
    }

    /** The whole dock: strip, sheet, keys. */
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(strip, LinearLayout.LayoutParams(dockWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = gap })
        addView(sheet, LinearLayout.LayoutParams(dockWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = gap })
        addView(row, LinearLayout.LayoutParams(dockWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(pill, LinearLayout.LayoutParams(dockWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Where the dock is on screen, for the crop-overlap warning. */
    val rect: Rect
        get() {
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }

    init {
        row.addView(grip, LinearLayout.LayoutParams(gripW, keyH)
            .apply { marginEnd = gap; gravity = Gravity.CENTER_VERTICAL })
        key(Key.REC, "●", "Rec", R.color.rec)
        key(Key.NO_TAP, "⊘", "No-Tap", R.color.no_tap)
        key(Key.WATCH, "◉", "Watch", R.color.watcher)
        key(Key.PLAY, "▶", "Play", R.color.go)
        key(Key.MORE, "⋯", "More", R.color.muted)

        sheet.addView(summary)
        sheetButton(Key.SCORE, "Score")
        sheetButton(Key.CLEAR, "Clear")
        sheetButton(Key.HIDE, "Hide")

        // Tapping the body toggles pause; Stop is its own button. Both are one tap, which is
        // what keeps the anti-goal true while the keys are hidden.
        body.addView(ring, LinearLayout.LayoutParams(px(R.dimen.space_32), px(R.dimen.space_32))
            .apply { marginEnd = px(R.dimen.space_12); gravity = Gravity.CENTER_VERTICAL })
        body.addView(pillText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { gravity = Gravity.CENTER_VERTICAL })
        body.setOnClickListener { onKey(Key.PAUSE) }
        pill.addView(body, LinearLayout.LayoutParams(0, keyH, 1f))
        pill.addView(stopKey, LinearLayout.LayoutParams(keyW, keyH)
            .apply { marginStart = gap })
    }

    /** Non-null collapses the dock to the pill; null restores the keys. */
    fun showPill(state: RunState?) {
        if (state == null) {
            pill.visibility = View.GONE
            row.visibility = View.VISIBLE
            return
        }
        closeSheet()
        row.visibility = View.GONE
        pill.visibility = View.VISIBLE
        ring.progress =
            if (state.steps > 0) state.step.toFloat() / state.steps else 0f
        ring.invalidate()
        val loops = if (state.loops == 0) "L${state.loop}" else "L${state.loop}/${state.loops}"
        pillText.text = when {
            state.pausedFor != null -> "waiting for ${state.pausedFor}"
            state.paused -> "paused  ${state.step}/${state.steps}  $loops"
            else -> "${state.step}/${state.steps}  $loops  %.2f".format(state.score)
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = px(R.dimen.corner_large).toFloat()
        setColor(Color.argb(238, 24, 26, 30))
        setStroke(px(R.dimen.space_4) / 4, colour(R.color.line))
    }

    private fun keyBackground(tint: Int?) = GradientDrawable().apply {
        cornerRadius = px(R.dimen.corner).toFloat()
        if (tint == null) {
            setColor(colour(R.color.raised))
        } else {
            setColor(Color.argb(46, Color.red(tint), Color.green(tint), Color.blue(tint)))
            setStroke(px(R.dimen.space_4) / 2, tint)
        }
    }

    private fun key(k: Key, glyph: String, label: String, colourId: Int) {
        val g = TextView(ctx).apply {
            text = glyph
            setTextColor(colour(colourId))
            textSize = res.getDimension(R.dimen.dock_glyph) / res.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
        }
        val l = TextView(ctx).apply {
            text = label
            setTextColor(colour(R.color.muted))
            textSize = res.getDimension(R.dimen.text_secondary) / res.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
        }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = keyBackground(null)
            addView(g)
            addView(l)
            setOnClickListener { onKey(k) }
        }
        row.addView(cell, LinearLayout.LayoutParams(keyW, keyH).apply { marginEnd = gap })
        keys[k] = cell
        glyphs[k] = g
        labels[k] = l
    }

    private fun sheetButton(k: Key, label: String) {
        val b = TextView(ctx).apply {
            text = label
            setTextColor(colour(R.color.text))
            textSize = res.getDimension(R.dimen.text_row) / res.displayMetrics.scaledDensity
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(R.dimen.space_12), 0, px(R.dimen.space_12), 0)
            background = keyBackground(null)
            setOnClickListener { onKey(k) }
        }
        sheet.addView(
            b,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(R.dimen.touch_min))
                .apply { topMargin = gap }
        )
    }

    // ---- state the overlay drives ----

    /** Armed state is the key's own colour, replacing the old "REC *" label swap. */
    fun setArmed(kind: Kind?) {
        val armedKey = when (kind) {
            Kind.STEP -> Key.REC
            Kind.STOP -> Key.NO_TAP
            Kind.WATCH -> Key.WATCH
            null -> null
        }
        for ((k, cell) in keys) {
            val tint = if (k == armedKey) glyphs[k]?.currentTextColor else null
            cell.background = keyBackground(tint)
            labels[k]?.setTextColor(colour(if (k == armedKey) R.color.text else R.color.muted))
        }
    }

    fun setPlayLabel(text: String) {
        labels[Key.PLAY]?.text = text
    }

    fun setSummary(text: String) {
        summary.text = text
    }

    private val hideStrip = Runnable { strip.visibility = View.GONE }

    /**
     * Everything the old readout carried: recording prompts, "that area is blank", "too fast",
     * "capture failed", the re-record prompt, Score's table. None of it may be dropped.
     */
    fun say(message: String) {
        strip.text = message
        strip.visibility = View.VISIBLE
        main.removeCallbacks(hideStrip)
        main.postDelayed(hideStrip, STRIP_MS)
    }

    fun toggleSheet() {
        sheet.visibility = if (sheet.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    fun closeSheet() {
        sheet.visibility = View.GONE
    }

    /** Drag by the grip. [onMove] gets the delta in pixels; the overlay owns the clamp. */
    fun onDrag(onMove: (Float, Float) -> Unit, onDrop: () -> Unit) {
        var dx = 0f
        var dy = 0f
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    onMove(e.rawX - dx, e.rawY - dy)
                    dx = e.rawX; dy = e.rawY; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDrop(); true
                }
                else -> false
            }
        }

    }

    private companion object {
        const val STRIP_MS = 4_000L

        /** The mark's ring: 285 degrees with the gap at the top right. */
        const val START = 60f
        const val SWEEP = 285f
    }
}
