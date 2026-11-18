package com.bitdroid.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var palette: KeyboardTheme = Themes.byId(Storage.themeId)
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun dp(v: Float): Float = v * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(88f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(palette.cornerRadiusDp)

        paint.color = palette.boardBg
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)

        val cols = 6
        val pad = dp(8f)
        val gap = dp(4f)
        val cellW = (w - pad * 2 - gap * (cols - 1)) / cols
        val cellH = (h - pad * 2 - gap) / 2f

        for (row in 0..1) {
            for (col in 0 until cols) {
                val left = pad + col * (cellW + gap)
                val top = pad + row * (cellH + gap)
                rect.set(left, top, left + cellW, top + cellH)
                paint.color = if (row == 1 && col == cols - 1) palette.accentBg else palette.keyBg
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }
}
