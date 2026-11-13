package com.bitdroid.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Miniature keyboard used on the theme cards in settings. */
class ThemePreviewView(context: Context) : View(context) {

    var palette: Palette? = null
        set(value) { field = value; invalidate() }
    var shape: String = "round"
        set(value) { field = value; invalidate() }
    var chosen: Boolean = false
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val p = palette ?: return
        val inset = 3 * d
        r.set(inset, inset, width - inset, height - inset)
        val corner = 20 * d
        fill.color = p.bg
        canvas.drawRoundRect(r, corner, corner, fill)
        ring.strokeWidth = if (chosen) 2.5f * d else 1f * d
        ring.color = if (chosen) p.accent else 0xFF2C2C31.toInt()
        canvas.drawRoundRect(r, corner, corner, ring)

        val padX = 10 * d
        val gap = 3 * d
        val keyH = 15 * d
        val rowGap = 5 * d
        val rows = 3
        val total = rows * keyH + (rows - 1) * rowGap
        var y = r.centerY() - total / 2
        val innerW = r.width() - 2 * padX
        val radius = when (shape) {
            "pill" -> keyH / 2
            "square" -> 2 * d
            else -> 5 * d
        }

        fun key(x: Float, w: Float, color: Int, pill: Boolean = false) {
            fill.color = color
            val rr = RectF(x, y, x + w, y + keyH)
            val rad = if (pill && shape != "square") keyH / 2 else min(radius, w / 2)
            canvas.drawRoundRect(rr, rad, rad, fill)
        }

        // row 1: ten keys
        var kw = (innerW - 9 * gap) / 10
        for (i in 0 until 10) key(r.left + padX + i * (kw + gap), kw, p.key)
        y += keyH + rowGap
        // row 2: nine keys, inset half a key
        val off = kw / 2
        for (i in 0 until 9) key(r.left + padX + off + i * (kw + gap), kw, p.key)
        y += keyH + rowGap
        // row 3: mode, space, scalloped enter
        val unit = (innerW - 2 * gap) / 8f
        key(r.left + padX, unit * 1.5f, p.mod, pill = true)
        key(r.left + padX + unit * 1.5f + gap, unit * 5f, p.key, pill = true)
        kw = unit * 1.5f
        fill.color = p.accent
        Shapes.drawCookie(canvas, r.right - padX - kw / 2f, y + keyH / 2f, keyH / 2f + 2 * d, fill)
    }
}
