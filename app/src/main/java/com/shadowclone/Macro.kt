package com.shadowclone

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

enum class Act { TAP, LONG_PRESS, SWIPE, STOP }

/** Which list a trigger belongs to, which is also what the recorder is armed for. */
enum class Kind { STEP, WATCH, STOP }

/**
 * One trigger: wait until [template] shows up where it was recorded, then do [act] there.
 * No-tap rules are triggers with [act] == STOP; they never dispatch anything.
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

    /**
     * Editor values come from typed text, so hold them to what is safe. A step below 0.6 starts
     * tapping guesses (an absent template scores about 0.43), and a no-tap rule above 0.9 can miss
     * the real purchase screen over a pixel of difference.
     */
    fun clamp(kind: Kind) = copy(
        threshold = if (kind == Kind.STOP) threshold.coerceIn(0.4, 0.9)
        else threshold.coerceIn(0.6, 0.999),
        timeoutMs = timeoutMs.coerceIn(1_000L, 600_000L),
        postDelayMs = postDelayMs.coerceIn(0L, 60_000L),
        searchPad = searchPad.coerceIn(0, 4_000),
    )

    fun label(n: Int) = "%d  %s  thr %.2f  to %ds  delay %dms  pad %d"
        .format(n, act, threshold, timeoutMs / 1000, postDelayMs, searchPad)

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
    val watchers: MutableList<Step> = mutableListOf(),
    val stops: MutableList<Step> = mutableListOf(),
    /**
     * Every package seen while recording. One is not enough: an app can hand off to another
     * package for part of its own flow (OnePlus Settings opens its Bluetooth screen as
     * `com.oplus.wirelesssettings`), and a run would then pause in the middle of itself.
     */
    val pkgs: MutableSet<String> = linkedSetOf(),
    /** How many times to run the steps. 0 means keep going until stopped or a no-tap rule fires. */
    var loops: Int = 1,
) {
    fun list(kind: Kind): MutableList<Step> = when (kind) {
        Kind.STEP -> steps
        Kind.WATCH -> watchers
        Kind.STOP -> stops
    }

    fun text(): String = JSONObject()
        .put("pkgs", JSONArray(pkgs.toList()))
        .put("loops", loops)
        .put("steps", array(steps))
        .put("watchers", array(watchers))
        .put("stops", array(stops))
        .toString(2)

    private fun array(l: List<Step>) = JSONArray().also { a -> l.forEach { a.put(it.json()) } }

    companion object {
        /** A gesture that travels further than this is a swipe, not a tap that wobbled. */
        const val SWIPE_MIN_PX = 40f
        const val LONG_PRESS_MS = 400L
        const val STEP_THRESHOLD = 0.85

        /** A false stop costs a run; a missed one can cost money. Fire more readily than a step. */
        const val STOP_THRESHOLD = 0.7

        /**
         * The index the next recorded touch replaces instead of appending to, set by the editor's
         * Re-record. Lives here because the editor (an activity) and the recorder (the service)
         * are different objects in the same process.
         */
        @Volatile
        var replaceAt: Int? = null

        /**
         * One macro per process. The editor and the recorder both mutate it, so they must not hold
         * separate copies: the last one to save would otherwise wipe the other's changes.
         */
        @Volatile
        private var cached: Macro? = null

        fun current(ctx: Context): Macro = cached ?: load(ctx).also { cached = it }

        fun classify(dx: Float, dy: Float, heldMs: Long): Act = when {
            hypot(dx, dy) > SWIPE_MIN_PX -> Act.SWIPE
            heldMs >= LONG_PRESS_MS -> Act.LONG_PRESS
            else -> Act.TAP
        }

        fun dir(ctx: Context): File = File(ctx.filesDir, "macros/default").apply { mkdirs() }

        private fun load(ctx: Context): Macro {
            val f = File(dir(ctx), "macro.json")
            if (!f.exists()) return Macro()
            return runCatching { parse(f.readText()) }.getOrElse { Macro() }
        }

        fun save(ctx: Context, m: Macro) {
            cached = m
            File(dir(ctx), "macro.json").writeText(m.text())
        }

        /** Deletes the macro and every template with it. */
        fun wipe(ctx: Context) {
            dir(ctx).listFiles()?.forEach { it.delete() }
            Templates.forgetAll()
            replaceAt = null
            cached = Macro()
        }

        fun parse(s: String): Macro {
            val o = JSONObject(s)
            val m = Macro(loops = o.optInt("loops", 1))
            val a = o.optJSONArray("pkgs")
            if (a != null) for (i in 0 until a.length()) m.pkgs += a.getString(i)
            // Macros recorded before this held a single package.
            else if (!o.isNull("pkg")) m.pkgs += o.getString("pkg")
            for ((key, into) in listOf(
                "steps" to m.steps, "watchers" to m.watchers, "stops" to m.stops
            )) {
                val a = o.optJSONArray(key) ?: continue
                for (i in 0 until a.length()) into.add(Step.of(a.getJSONObject(i)))
            }
            return m
        }

        /** Checks the logic that has no UI: classification, the JSON round trip and the clamps. */
        fun selfTest(): String {
            val bad = mutableListOf<String>()
            if (classify(0f, 0f, 100) != Act.TAP) bad += "tap"
            if (classify(5f, 5f, 900) != Act.LONG_PRESS) bad += "longpress"
            if (classify(0f, 200f, 120) != Act.SWIPE) bad += "swipe-quick"
            if (classify(300f, 0f, 900) != Act.SWIPE) bad += "swipe-held"

            val m = Macro(loops = 7)
            m.pkgs += "com.example"
            m.pkgs += "com.example.sub"
            m.steps += Step(Act.TAP, 80, 80, 100, 100, 100, 100, 60, "t0.png", STEP_THRESHOLD)
            m.steps += Step(Act.SWIPE, 0, 0, 1, 2, 3, 4, 250, "t1.png", 0.9, 9000, 100, 300)
            m.watchers += Step(Act.TAP, 7, 8, 9, 10, 9, 10, 60, "w0.png", 0.8)
            m.stops += Step(Act.STOP, 5, 6, 5, 6, 5, 6, 0, "s0.png", STOP_THRESHOLD)
            val back = parse(m.text())
            if (back.pkgs != m.pkgs || back.loops != 7 || back.steps != m.steps ||
                back.watchers != m.watchers || back.stops != m.stops
            ) bad += "json"

            // Old files have no loops or watchers, and named a single package.
            val old = parse("""{"pkg":"com.old","steps":[]}""")
            if (old.loops != 1 || old.watchers.isNotEmpty() ||
                old.pkgs != setOf("com.old")
            ) bad += "old-json"
            if (parse("""{"pkg":null,"steps":[]}""").pkgs.isNotEmpty()) bad += "old-json-null"

            val loose = m.steps[0].copy(threshold = 0.1, timeoutMs = 10, postDelayMs = -5, searchPad = -9)
            val step = loose.clamp(Kind.STEP)
            if (step.threshold != 0.6 || step.timeoutMs != 1000L ||
                step.postDelayMs != 0L || step.searchPad != 0
            ) bad += "clamp-step"
            if (loose.copy(threshold = 0.99).clamp(Kind.STOP).threshold != 0.9) bad += "clamp-stop"

            val r = m.steps[0].copy(searchPad = 50).region(40, 40, 1080, 2376)
            if (r != Rect(30, 30, 170, 170)) bad += "region=$r"

            return if (bad.isEmpty()) "macro self-test PASS"
            else "macro self-test FAIL: " + bad.joinToString()
        }
    }
}
