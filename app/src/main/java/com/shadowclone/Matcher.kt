package com.shadowclone

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

data class MatchResult(val score: Double, val cx: Int, val cy: Int, val ms: Long)

object Matcher {

    /** Match on half-size grayscale: 4x less work, no accuracy loss worth caring about at these sizes. */
    private const val SCALE = 0.5

    /** Returns the best match of [template] inside [region] of [frame], in full-resolution coordinates. */
    fun match(frame: Bitmap, template: Bitmap, region: Rect? = null): MatchResult? {
        val t0 = System.nanoTime()
        val r = region ?: Rect(0, 0, frame.width, frame.height)
        if (r.width() < template.width || r.height() < template.height) return null
        val full = r.left == 0 && r.top == 0 && r.width() == frame.width && r.height() == frame.height
        val sub = if (full) frame else Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())

        val f = gray(sub)
        val t = gray(template)
        if (t.rows() < 2 || t.cols() < 2 || t.rows() > f.rows() || t.cols() > f.cols()) {
            f.release(); t.release(); return null
        }
        val out = Mat()
        Imgproc.matchTemplate(f, t, out, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(out)
        val cx = r.left + ((mm.maxLoc.x + t.cols() / 2.0) / SCALE).toInt()
        val cy = r.top + ((mm.maxLoc.y + t.rows() / 2.0) / SCALE).toInt()
        f.release(); t.release(); out.release()
        return MatchResult(mm.maxVal, cx, cy, (System.nanoTime() - t0) / 1_000_000)
    }

    /** Grayscale spread. A flat crop (plain background) matches almost anything, so recording checks it. */
    fun stdDev(bmp: Bitmap): Double {
        val g = gray(bmp)
        val mean = MatOfDouble()
        val sd = MatOfDouble()
        Core.meanStdDev(g, mean, sd)
        val v = sd.toArray().firstOrNull() ?: 0.0
        g.release(); mean.release(); sd.release()
        return v
    }

    private fun gray(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val small = Mat()
        Imgproc.resize(g, small, Size(), SCALE, SCALE, Imgproc.INTER_AREA)
        g.release()
        return small
    }

    /** On-device check of the score and the coordinate mapping: crop a known patch, then find it again. */
    fun selfTest(frame: Bitmap): String {
        val rect = Rect(frame.width / 4, frame.height / 2, frame.width / 4 + 160, frame.height / 2 + 160)
        val patch = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
        val m = match(frame, patch) ?: return "self-test: no result"
        val dx = m.cx - rect.centerX()
        val dy = m.cy - rect.centerY()
        val ok = m.score > 0.95 && abs(dx) <= 4 && abs(dy) <= 4
        return "self-test %s  score=%.3f  off=(%d,%d)  %dms".format(
            if (ok) "PASS" else "FAIL", m.score, dx, dy, m.ms
        )
    }
}
