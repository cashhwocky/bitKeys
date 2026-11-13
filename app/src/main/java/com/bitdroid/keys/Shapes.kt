package com.bitdroid.keys

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Material You "expressive" shapes used by the keyboard. */
object Shapes {

    private val cookies = HashMap<Int, Path>()

    /** A scalloped "cookie" in unit space (radius ≈ 1, centered on 0,0). */
    private fun cookie(lobes: Int, amp: Float): Path = cookies.getOrPut(lobes * 1000 + (amp * 1000).toInt()) {
        Path().apply {
            val steps = 240
            for (i in 0..steps) {
                val t = i / steps.toFloat() * 2f * PI.toFloat()
                val r = (1f + amp * cos(lobes * t)) / (1f + amp)
                val x = r * cos(t)
                val y = r * sin(t)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    /** Draws a scalloped badge (like the date widget) of [radius] px at (cx, cy). */
    fun drawCookie(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint, lobes: Int = 9, amp: Float = 0.075f) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius, radius)
        canvas.drawPath(cookie(lobes, amp), paint)
        canvas.restore()
    }

    /** Fills [out] with a sine squiggle from (left, cy) to (right, cy). */
    fun squiggle(out: Path, left: Float, right: Float, cy: Float, amp: Float, waves: Float, phase: Float = 0f, step: Float = 3f) {
        out.reset()
        val w = right - left
        var x = 0f
        out.moveTo(left, cy + amp * sin(phase))
        while (x < w) {
            x = (x + step).coerceAtMost(w)
            out.lineTo(left + x, cy + amp * sin(x / w * 2f * PI.toFloat() * waves + phase))
        }
    }
}
