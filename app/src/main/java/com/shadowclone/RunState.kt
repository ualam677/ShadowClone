package com.shadowclone

/**
 * Where a run has got to, as values rather than as a sentence.
 *
 * [Runner] already reports progress through `onState`, but only as a formatted string for the
 * readout. The status pill needs the parts separately -- a ring filled to step/steps, numbers in
 * their own columns, a pause reason to name -- and parsing the display strings back apart would
 * break the first time one is reworded.
 *
 * @param step 1-based, so it reads as "2/5" without arithmetic at the call site.
 * @param loops 0 means "until stopped", matching [Macro.loops].
 * @param pausedFor the package in front when the foreground guard is what paused the run, or
 *   null when the run is not paused or the user paused it themselves.
 */
data class RunState(
    val step: Int,
    val steps: Int,
    val loop: Int,
    val loops: Int,
    val score: Double,
    val paused: Boolean,
    val pausedFor: String?,
)
