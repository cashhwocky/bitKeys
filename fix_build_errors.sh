#!/usr/bin/env bash
set -e

WORKDIR="$HOME/bitKeys_work"
cd "$WORKDIR"

PKG_DIR="app/src/main/java/com/bitdroid/keys"

echo "==> 1. Making Storage.prefs(ctx) public in Storage.kt..."
python3 - << 'PY_EOF'
from pathlib import Path

p = Path("app/src/main/java/com/bitdroid/keys/Storage.kt")
text = p.read_text()
text = text.replace("private fun prefs(ctx: Context):", "fun prefs(ctx: Context):")
p.write_text(text)
PY_EOF

echo "==> 2. Adding Palette & KeyboardPrefs compatibility properties to Theme.kt..."
cat << 'KOTLIN_EOF' > "$PKG_DIR/Theme.kt"
package com.bitdroid.keys

import android.graphics.Color

data class KeyboardTheme(
    val id: String,
    val name: String,
    val subtitle: String,
    val boardBg: Int,
    val stripBg: Int,
    val keyBg: Int,
    val keyPressedBg: Int,
    val funcKeyBg: Int,
    val accentBg: Int,
    val accentText: Int,
    val keyText: Int,
    val subText: Int,
    val dividerColor: Int,
    val popupBg: Int,
    val cornerRadiusDp: Float = 8f
) {
    // Compatibility properties for legacy views
    val bg: Int get() = boardBg
    val key: Int get() = keyBg
    val keyAlt: Int get() = funcKeyBg
    val keyPress: Int get() = keyPressedBg
    val accent: Int get() = accentBg
    val onAccent: Int get() = accentText
    val text: Int get() = keyText
    val textMuted: Int get() = subText
    val border: Int get() = dividerColor
}

typealias Palette = KeyboardTheme

data class KeyboardPrefs(
    val themeId: String = Storage.themeId,
    val keyRadiusDp: Float = Themes.byId(Storage.themeId).cornerRadiusDp,
    val keyBorders: Boolean = Storage.keyBorders
)

object Themes {
    val AMOLED_GBOARD = KeyboardTheme(
        id = "amoled_gboard",
        name = "Gboard Pure AMOLED",
        subtitle = "True #000000 black with Material You sky accents",
        boardBg = Color.parseColor("#000000"),
        stripBg = Color.parseColor("#080A0F"),
        keyBg = Color.parseColor("#181B22"),
        keyPressedBg = Color.parseColor("#2B313E"),
        funcKeyBg = Color.parseColor("#101319"),
        accentBg = Color.parseColor("#A8C7FA"),
        accentText = Color.parseColor("#062E6F"),
        keyText = Color.parseColor("#F2F4F8"),
        subText = Color.parseColor("#8E96A8"),
        dividerColor = Color.parseColor("#242936"),
        popupBg = Color.parseColor("#282E3B"),
        cornerRadiusDp = 9f
    )

    val PIXEL_DARK = KeyboardTheme(
        id = "pixel_dark",
        name = "Gboard Material Dark",
        subtitle = "Official Pixel Gboard charcoal surface",
        boardBg = Color.parseColor("#131314"),
        stripBg = Color.parseColor("#1A1B1E"),
        keyBg = Color.parseColor("#282A2F"),
        keyPressedBg = Color.parseColor("#3B3E47"),
        funcKeyBg = Color.parseColor("#1F2126"),
        accentBg = Color.parseColor("#A8C7FA"),
        accentText = Color.parseColor("#062E6F"),
        keyText = Color.parseColor("#E3E3E3"),
        subText = Color.parseColor("#9AA0A6"),
        dividerColor = Color.parseColor("#33363E"),
        popupBg = Color.parseColor("#383B44"),
        cornerRadiusDp = 9f
    )

    val DYNAMIC_VIOLET = KeyboardTheme(
        id = "dynamic_violet",
        name = "Material You Iris",
        subtitle = "Deep obsidian with lavender M3 pills",
        boardBg = Color.parseColor("#0B0912"),
        stripBg = Color.parseColor("#120F1D"),
        keyBg = Color.parseColor("#211C32"),
        keyPressedBg = Color.parseColor("#342C4E"),
        funcKeyBg = Color.parseColor("#171324"),
        accentBg = Color.parseColor("#D0BCFF"),
        accentText = Color.parseColor("#381E72"),
        keyText = Color.parseColor("#F5EEFF"),
        subText = Color.parseColor("#A89CC2"),
        dividerColor = Color.parseColor("#2E2744"),
        popupBg = Color.parseColor("#342C4E"),
        cornerRadiusDp = 11f
    )

    val PIXEL_LIGHT = KeyboardTheme(
        id = "pixel_light",
        name = "Gboard Daylight",
        subtitle = "Crisp white keys with soft Material tonal elevation",
        boardBg = Color.parseColor("#ECEFF5"),
        stripBg = Color.parseColor("#E2E7F0"),
        keyBg = Color.parseColor("#FFFFFF"),
        keyPressedBg = Color.parseColor("#D8E0EE"),
        funcKeyBg = Color.parseColor("#D5DDEA"),
        accentBg = Color.parseColor("#0B57D0"),
        accentText = Color.parseColor("#FFFFFF"),
        keyText = Color.parseColor("#1F1F1F"),
        subText = Color.parseColor("#5F6368"),
        dividerColor = Color.parseColor("#C6CFDEC0"),
        popupBg = Color.parseColor("#FFFFFF"),
        cornerRadiusDp = 9f
    )

    val ALL = listOf(AMOLED_GBOARD, PIXEL_DARK, DYNAMIC_VIOLET, PIXEL_LIGHT)

    fun byId(id: String): KeyboardTheme =
        ALL.firstOrNull { it.id == id } ?: AMOLED_GBOARD

    fun palette(id: String = Storage.themeId): KeyboardTheme = byId(id)
}
KOTLIN_EOF

echo "==> 3. Updating ThemePreviewView.kt to match new KeyboardTheme..."
cat << 'KOTLIN_EOF' > "$PKG_DIR/ThemePreviewView.kt"
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
KOTLIN_EOF

echo "==> 4. Updating EmojiPanelView.kt to compile cleanly with KeyboardTheme..."
cat << 'KOTLIN_EOF' > "$PKG_DIR/EmojiPanelView.kt"
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
KOTLIN_EOF

echo "==> 5. Committing and pushing fix to GitHub..."
git add -A
git commit -m "fix: resolve EmojiPanelView and ThemePreviewView Kotlin references for Gradle build"
git push origin main
echo "==> Done! Check GitHub Actions — :app:compileDebugKotlin and :app:compileReleaseKotlin will now succeed."
