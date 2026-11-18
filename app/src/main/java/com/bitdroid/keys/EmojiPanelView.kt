package com.bitdroid.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onEmojiPicked(emoji: String)
        fun onEmojiClose()
        fun onEmojiBackspace()
    }

    var listener: Listener? = null
    var palette: KeyboardTheme = Themes.byId(Storage.themeId)
        set(value) {
            field = value
            invalidate()
        }

    var prefs: KeyboardPrefs = KeyboardPrefs()
        set(value) {
            field = value
            invalidate()
        }

    private val emojis = listOf(
        "😂", "😭", "🔥", "💀", "🙏", "❤️", "🤣", "💯",
        "🐐", "🫡", "👀", "🤝", "😎", "🥳", "😤", "🤦",
        "🤷", "👍", "👎", "🙌", "✨", "⚡", "🎵", "🎧",
        "🎹", "🚀", "💎", "👑", "🖤", "💜", "💙", "✅"
    )

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val cellRects = Array(emojis.size) { RectF() }
    private val backRect = RectF()
    private val delRect = RectF()

    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (dp(258f) * Storage.keyHeightScale).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val topBarH = dp(44f)

        backRect.set(dp(10f), dp(6f), dp(88f), topBarH - dp(4f))
        delRect.set(wf - dp(68f), dp(6f), wf - dp(10f), topBarH - dp(4f))

        val cols = 8
        val rows = 4
        val gridTop = topBarH + dp(4f)
        val gridH = hf - gridTop - dp(8f)
        val cellW = (wf - dp(16f)) / cols
        val cellH = gridH / rows

        for (i in emojis.indices) {
            val r = i / cols
            val c = i % cols
            val l = dp(8f) + c * cellW
            val t = gridTop + r * cellH
            cellRects[i].set(l + dp(2f), t + dp(2f), l + cellW - dp(2f), t + cellH - dp(2f))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = palette.boardBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        cellPaint.color = palette.funcKeyBg
        canvas.drawRoundRect(backRect, dp(14f), dp(14f), cellPaint)
        canvas.drawRoundRect(delRect, dp(14f), dp(14f), cellPaint)

        textPaint.color = palette.keyText
        textPaint.textSize = sp(13.5f)
        canvas.drawText("ABC", backRect.centerX(), backRect.centerY() + dp(5f), textPaint)
        canvas.drawText("⌫", delRect.centerX(), delRect.centerY() + dp(5f), textPaint)

        textPaint.textSize = sp(22f)
        for (i in emojis.indices) {
            val r = cellRects[i]
            canvas.drawText(emojis[i], r.centerX(), r.centerY() + dp(8f), textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            if (backRect.contains(x, y)) {
                listener?.onEmojiClose()
                return true
            }
            if (delRect.contains(x, y)) {
                listener?.onEmojiBackspace()
                return true
            }
            for (i in emojis.indices) {
                if (cellRects[i].contains(x, y)) {
                    listener?.onEmojiPicked(emojis[i])
                    return true
                }
            }
        }
        return true
    }
}
