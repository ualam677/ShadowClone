package com.shadowclone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The template PNGs, and the one way to score a trigger against a frame.
 *
 * Replay and the overlay's Score button share this on purpose: a score shown while tuning a
 * threshold has to be the score replay will get, search region included.
 */
object Templates {

    private val cache = HashMap<String, Bitmap>()

    fun bitmap(ctx: Context, name: String): Bitmap? {
        synchronized(cache) { cache[name]?.let { return it } }
        val b = BitmapFactory.decodeFile(File(Macro.dir(ctx), name).path) ?: return null
        synchronized(cache) { cache[name] = b }
        return b
    }

    fun match(ctx: Context, frame: Bitmap, s: Step): MatchResult? {
        val t = bitmap(ctx, s.template) ?: return null
        return Matcher.match(frame, t, s.region(t.width, t.height, frame.width, frame.height))
    }

    /** Where the match puts the gesture: how far the template moved from where it was recorded. */
    fun offset(ctx: Context, s: Step, m: MatchResult): Pair<Int, Int>? {
        val t = bitmap(ctx, s.template) ?: return null
        return (m.cx - t.width / 2) - s.tx to (m.cy - t.height / 2) - s.ty
    }

    /** Called when a template file is replaced or deleted, so the stale bitmap is not reused. */
    fun forget(ctx: Context, name: String) {
        synchronized(cache) { cache.remove(name) }
        File(Macro.dir(ctx), name).delete()
    }

    fun forgetAll() {
        synchronized(cache) { cache.clear() }
    }
}
