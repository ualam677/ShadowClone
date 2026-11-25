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
enum class Key { REC, NO_TAP, WATCH, PLAY, MORE, STOP, SCORE, CLEAR, HIDE }

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

    /** The whole dock: strip, sheet, keys. */
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(strip, LinearLayout.LayoutParams(dockWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = gap })
        addView(sheet, LinearLayout.LayoutParams(dockWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = gap })
        addView(row, LinearLayout.LayoutParams(dockWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        // The fifth key is More while idle and Stop while running, so Stop is always one
        // tap. Until Task 6 brings the status pill there would otherwise be no Stop at all
        // during a run, which is the one thing this design must never allow.
        keys[Key.MORE]?.setOnClickListener { onKey(if (running) Key.STOP else Key.MORE) }

        sheet.addView(summary)
        sheetButton(Key.SCORE, "Score")
        sheetButton(Key.CLEAR, "Clear")
        sheetButton(Key.HIDE, "Hide")
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

    private var running = false

    /** Swaps the fifth key between More and Stop. */
    fun setRunning(active: Boolean) {
        running = active
        glyphs[Key.MORE]?.apply {
            text = if (active) "■" else "⋯"
            setTextColor(colour(if (active) R.color.rec else R.color.muted))
        }
        labels[Key.MORE]?.text = if (active) "Stop" else "More"
        if (active) closeSheet()
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
    }
}
