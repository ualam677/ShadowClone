package com.shadowclone

/**
 * The last score each trigger got, and when.
 *
 * The step sheet cannot score live: `takeScreenshot` captures whatever is in front, and while
 * the editor is open that is the editor, so every template would read near 0.43 -- the measured
 * not-on-screen baseline. It shows the last real score instead, with its age.
 *
 * In memory only. A score's worth is its freshness; one that outlived the process would be a
 * number with no relationship to what is on screen now.
 */
object Scores {

    private val last = HashMap<String, Pair<Double, Long>>()

    fun put(template: String, score: Double) {
        synchronized(last) { last[template] = score to System.currentTimeMillis() }
    }

    /** The score and the `System.currentTimeMillis()` it was taken at, or null if never scored. */
    fun get(template: String): Pair<Double, Long>? = synchronized(last) { last[template] }
}
