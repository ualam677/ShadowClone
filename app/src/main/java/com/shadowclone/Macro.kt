package com.shadowclone

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

enum class Act { TAP, LONG_PRESS, SWIPE, STOP }

/**
 * One trigger: wait until [template] shows up where it was recorded, then do [act] there.
 * Stop rules are steps with [act] == STOP; they never dispatch anything.
 *
 * Coordinates are screen pixels as recorded. ([tx],[ty]) is the template's top-left, which is what
 * replay re-locates; the gesture points are stored separately so a crop clipped at a screen edge
 * still maps back to the right touch point.
 */
data class Step(
    val act: Act,
    val tx: Int,
    val ty: Int,
    val x: Int,
    val y: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long,
    val template: String,
    val threshold: Double,
    val timeoutMs: Long = 15_000,
    val postDelayMs: Long = 500,
    val searchPad: Int = 250,
) {
    /** Where to look: the recorded template box plus [searchPad] on every side. */
    fun region(tw: Int, th: Int, fw: Int, fh: Int) = Rect(
        (tx - searchPad).coerceAtLeast(0),
        (ty - searchPad).coerceAtLeast(0),
        (tx + tw + searchPad).coerceAtMost(fw),
        (ty + th + searchPad).coerceAtMost(fh),
    )

    fun json(): JSONObject = JSONObject()
        .put("act", act.name).put("tx", tx).put("ty", ty)
        .put("x", x).put("y", y).put("x2", x2).put("y2", y2)
        .put("durationMs", durationMs).put("template", template).put("threshold", threshold)
        .put("timeoutMs", timeoutMs).put("postDelayMs", postDelayMs).put("searchPad", searchPad)

    companion object {
        fun of(o: JSONObject) = Step(
            Act.valueOf(o.getString("act")), o.getInt("tx"), o.getInt("ty"),
            o.getInt("x"), o.getInt("y"), o.getInt("x2"), o.getInt("y2"),
            o.getLong("durationMs"), o.getString("template"), o.getDouble("threshold"),
            o.getLong("timeoutMs"), o.getLong("postDelayMs"), o.getInt("searchPad"),
        )
    }
}

class Macro(
    val steps: MutableList<Step> = mutableListOf(),
    val stops: MutableList<Step> = mutableListOf(),
    var pkg: String? = null,
) {
    fun text(): String = JSONObject()
        .put("pkg", pkg ?: JSONObject.NULL)
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.json()) } })
        .put("stops", JSONArray().also { a -> stops.forEach { a.put(it.json()) } })
        .toString(2)

    companion object {
        /** A gesture that travels further than this is a swipe, not a tap that wobbled. */
        const val SWIPE_MIN_PX = 40f
        const val LONG_PRESS_MS = 400L
        const val STEP_THRESHOLD = 0.85

        /** A false stop costs a run; a missed one can cost money. Fire more readily than a step. */
        const val STOP_THRESHOLD = 0.7

        fun classify(dx: Float, dy: Float, heldMs: Long): Act = when {
            hypot(dx, dy) > SWIPE_MIN_PX -> Act.SWIPE
            heldMs >= LONG_PRESS_MS -> Act.LONG_PRESS
            else -> Act.TAP
        }

        fun dir(ctx: Context): File = File(ctx.filesDir, "macros/default").apply { mkdirs() }

        fun load(ctx: Context): Macro {
            val f = File(dir(ctx), "macro.json")
            if (!f.exists()) return Macro()
            return runCatching { parse(f.readText()) }.getOrElse { Macro() }
        }

        fun save(ctx: Context, m: Macro) = File(dir(ctx), "macro.json").writeText(m.text())

        fun parse(s: String): Macro {
            val o = JSONObject(s)
            val m = Macro(pkg = if (o.isNull("pkg")) null else o.getString("pkg"))
            for ((key, into) in listOf("steps" to m.steps, "stops" to m.stops)) {
                val a = o.optJSONArray(key) ?: continue
                for (i in 0 until a.length()) into.add(Step.of(a.getJSONObject(i)))
            }
            return m
        }

        /** Checks the two pieces of logic with no UI: gesture classification and the JSON round trip. */
        fun selfTest(): String {
            val bad = mutableListOf<String>()
            if (classify(0f, 0f, 100) != Act.TAP) bad += "tap"
            if (classify(5f, 5f, 900) != Act.LONG_PRESS) bad += "longpress"
            if (classify(0f, 200f, 120) != Act.SWIPE) bad += "swipe-quick"
            if (classify(300f, 0f, 900) != Act.SWIPE) bad += "swipe-held"

            val m = Macro(pkg = "com.example")
            m.steps += Step(Act.TAP, 80, 80, 100, 100, 100, 100, 60, "t0.png", STEP_THRESHOLD)
            m.steps += Step(Act.SWIPE, 0, 0, 1, 2, 3, 4, 250, "t1.png", 0.9, 9000, 100, 300)
            m.stops += Step(Act.STOP, 5, 6, 5, 6, 5, 6, 0, "s0.png", STOP_THRESHOLD)
            val back = parse(m.text())
            if (back.pkg != m.pkg || back.steps != m.steps || back.stops != m.stops) bad += "json"

            val r = m.steps[0].copy(searchPad = 50).region(40, 40, 1080, 2376)
            if (r != Rect(30, 30, 170, 170)) bad += "region=$r"

            return if (bad.isEmpty()) "macro self-test PASS"
            else "macro self-test FAIL: " + bad.joinToString()
        }
    }
}
