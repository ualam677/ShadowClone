package com.shadowclone

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The macro editor. The overlay cannot do this job: its window takes no keyboard, and the panel
 * would sit on top of the list. So the overlay is hidden while this screen is in front.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * True while this screen is up. The service checks it before building the overlay: the
         * service can be re-bound at any time, and would otherwise put the panel back on top of
         * the editor seconds after we hid it.
         */
        @Volatile
        var editorOpen = false
    }

    private val macro get() = Macro.current(this)

    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var loopsField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = ScrollView(this).apply { addView(body) }
        // No action bar, so pad past the status bar and the gesture area ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(48, bars.top + 32, 48, bars.bottom + 32)
            insets
        }
        setContentView(root)
    }

    // onStart/onStop, not onResume/onPause: a dialog does not stop the activity, so the panel
    // stays away while one is open.
    override fun onStart() {
        super.onStart()
        editorOpen = true
        // The panel would cover the list and the keyboard.
        ShadowService.instance?.setOverlayVisible(false)
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun onStop() {
        super.onStop()
        saveLoops()
        editorOpen = false
        ShadowService.instance?.setOverlayVisible(true)
    }

    private fun saveLoops() {
        val n = loopsField.text.toString().toIntOrNull() ?: return
        val clamped = n.coerceIn(0, 9999)
        if (clamped != macro.loops) {
            macro.loops = clamped
            Macro.save(this, macro)
        }
    }

    // ---- the list ----

    private fun rebuild() {
        body.removeAllViews()

        status = TextView(this).apply { textSize = 15f }
        status.text = if (ShadowService.instance != null) "Service: running" else "Service: OFF"
        body.addView(status)

        body.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        body.addView(button("Self-test") { status.text = Macro.selfTest() })

        body.addView(label("Repeat (0 = until stopped or a no-tap rule fires)"))
        loopsField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(macro.loops.toString())
        }
        body.addView(loopsField)
        body.addView(button("Save repeat") {
            saveLoops()
            status.text = "repeat saved: ${macro.loops}"
        })

        section(Kind.STEP, "Steps (in order)")
        section(Kind.WATCH, "Watchers (any time they appear)")
        section(Kind.STOP, "No-tap rules (stop the run)")

        body.addView(label(
            "Tap a row to change its threshold, timeout, delay or search area, to move it, " +
                "re-record it or delete it.\n\n" +
                "Record on the overlay: Rec for steps, Watch for watchers, No-Tap for screens that " +
                "must never be tapped. Score shows what every trigger scores against the screen " +
                "right now, so thresholds can be tuned against the real app.\n\n" +
                "The panel is hidden while this screen is open, and comes back when you leave it. " +
                "That is also how to get it back after pressing Hide."
        ))
    }

    private fun section(kind: Kind, title: String) {
        body.addView(label(title))
        val list = macro.list(kind)
        if (list.isEmpty()) {
            body.addView(label("  (none)"))
            return
        }
        list.forEachIndexed { i, s ->
            body.addView(button(s.label(i + 1)) { edit(kind, i) })
        }
    }

    // ---- one trigger ----

    private fun edit(kind: Kind, index: Int) {
        val list = macro.list(kind)
        if (index !in list.indices) return
        val s = list[index]

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        Templates.bitmap(this, s.template)?.let {
            box.addView(ImageView(this).apply {
                setImageBitmap(it)
                adjustViewBounds = true
                maxHeight = 400
            })
        }
        box.addView(label("recorded at (${s.x},${s.y})" +
            if (s.act == Act.SWIPE) " to (${s.x2},${s.y2})" else ""))

        val thr = field(box, "Match threshold", "%.2f".format(s.threshold))
        val timeout = field(box, "Timeout (seconds)", (s.timeoutMs / 1000).toString())
        val delay = field(box, "Delay after (ms)", s.postDelayMs.toString())
        val pad = field(box, "Search area padding (px)", s.searchPad.toString())

        lateinit var dialog: AlertDialog
        val moves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        moves.addView(button("Up") { move(kind, index, -1); dialog.dismiss() })
        moves.addView(button("Down") { move(kind, index, 1); dialog.dismiss() })
        if (kind == Kind.STEP) {
            moves.addView(button("Re-record") {
                Macro.replaceAt = index
                dialog.dismiss()
                finish() // back to the app being automated; press Rec and touch the new spot
            })
        }
        box.addView(moves)

        dialog = AlertDialog.Builder(this)
            .setTitle("${kind.name} ${index + 1}: ${s.act}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                list[index] = s.copy(
                    threshold = thr.text.toString().toDoubleOrNull() ?: s.threshold,
                    timeoutMs = (timeout.text.toString().toLongOrNull()
                        ?: (s.timeoutMs / 1000)) * 1000,
                    postDelayMs = delay.text.toString().toLongOrNull() ?: s.postDelayMs,
                    searchPad = pad.text.toString().toIntOrNull() ?: s.searchPad,
                ).clamp(kind)
                Macro.save(this, macro)
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                Templates.forget(this, s.template)
                list.removeAt(index)
                Macro.save(this, macro)
                rebuild()
            }
            .create()
        dialog.show()
    }

    private fun move(kind: Kind, index: Int, by: Int) {
        val list = macro.list(kind)
        val to = index + by
        if (to !in list.indices) return
        list.add(to, list.removeAt(index))
        Macro.save(this, macro)
        rebuild()
    }

    // ---- small view helpers ----

    private fun label(text: String) = TextView(this).apply {
        textSize = 13f
        setPadding(0, 24, 0, 4)
        this.text = text
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun field(into: LinearLayout, name: String, value: String): EditText {
        into.addView(label(name))
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(value)
        }
        into.addView(e)
        return e
    }
}

