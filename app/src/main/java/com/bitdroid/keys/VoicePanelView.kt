package com.bitdroid.keys

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Voice-typing panel: three flowing light ribbons that swell with your voice level,
 * a pulsing pause/resume button, back-to-keyboard and delete-word buttons.
 */
class VoicePanelView(context: Context) : View(context) {

    interface Listener {
        fun onVoiceKeyboard()
        fun onVoiceToggle()
        fun onVoiceDeleteWord()
    }

    var listener: Listener? = null

    /** Matches the keyboard's height so switching doesn't make the app jump. */
    var fixedHeight = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** Navigation-bar inset to keep the buttons clear of the gesture bar / nav buttons. */
    var bottomInset = 0

    private var palette = Themes.palette(context, KeyboardPrefs.load(context))
    private var listening = false
    private var status = "Listening…"
    private var targetLevel = 0f
    private var level = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(14f) }
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }

    private class Wave(val color: Int, val alpha: Int, val amp: Float, val speed: Float, val width: Float, val phase: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    private var waves: List<Wave> = emptyList()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    private val keyboardBtn = RectF()
    private val mainBtn = RectF()
    private val deleteBtn = RectF()
    private var pressed: RectF? = null

    private val language: String = Locale.getDefault().getDisplayName(Locale.getDefault())

    init {
        rebuildWaves()
    }

    fun setPalette(p: Palette) {
        palette = p
        rebuildWaves()
        invalidate()
    }

    fun setListening(on: Boolean, statusText: String) {
        listening = on
        status = statusText
        if (!on) targetLevel = 0f
        invalidate()
    }

    /** 0..1 microphone level. */
    fun setLevel(v: Float) {
        targetLevel = v.coerceIn(0f, 1f)
    }

    private fun rebuildWaves() {
        waves = listOf(
            Wave(Color.parseColor("#D7BAFF"), 180, 0.55f, 2.0f, dp(3f), 2.6f),
            Wave(Color.parseColor("#A8C7FA"), 205, 0.75f, -2.7f, dp(4f), 1.3f),
            Wave(palette.accent, 255, 1.0f, 3.9f, dp(5f), 0f),
        )
        applyShaders()
    }

    private fun fadeShader(color: Int, alpha: Int): Shader {
        val solid = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        val clear = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        return LinearGradient(
            0f, 0f, width.toFloat().coerceAtLeast(1f), 0f,
            intArrayOf(clear, solid, solid, clear),
            floatArrayOf(0f, 0.18f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun applyShaders() {
        for (w in waves) {
            w.paint.strokeWidth = w.width
            w.paint.shader = fadeShader(w.color, w.alpha)
        }
        glow.strokeWidth = dp(12f)
        glow.maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
        glow.shader = fadeShader(palette.accent, 90)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (fixedHeight > 0) fixedHeight else dp(320f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyShaders()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.bg)
        val w = width.toFloat()
        val pad = dp(16f)
        val t = SystemClock.uptimeMillis() / 1000f
        level += (targetLevel - level) * 0.18f

        // --- header: status + language chip
        val headerY = dp(28f)
        fill.color = palette.accent
        canvas.drawCircle(pad + dp(4f), headerY, dp(4f), fill)
        text.color = palette.sub
        text.textSize = sp(14f)
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(status, pad + dp(16f), headerY - (text.descent() + text.ascent()) / 2f, text)

        text.textSize = sp(13f)
        val chipW = text.measureText(language) + dp(24f)
        val chip = RectF(w - pad - chipW, headerY - dp(16f), w - pad, headerY + dp(16f))
        chipStroke.color = palette.border
        canvas.drawRoundRect(chip, dp(16f), dp(16f), chipStroke)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(language, chip.centerX(), headerY - (text.descent() + text.ascent()) / 2f, text)

        // --- bottom controls layout
        val gestureSpace = dp(16f) + bottomInset
        val btnCy = height - gestureSpace - dp(42f)
        val big = dp(42f)
        val small = dp(28f)
        mainBtn.set(w / 2 - big, btnCy - big, w / 2 + big, btnCy + big)
        keyboardBtn.set(pad, btnCy - small, pad + small * 2, btnCy + small)
        deleteBtn.set(w - pad - small * 2, btnCy - small, w - pad, btnCy + small)

        // --- ribbons, centered between header and buttons
        val ribbonTop = headerY + dp(20f)
        val ribbonBottom = mainBtn.top - dp(12f)
        val cy = (ribbonTop + ribbonBottom) / 2f
        val maxAmp = (ribbonBottom - ribbonTop) / 2f - dp(8f)
        val breathe = 0.78f + 0.22f * sin(t * 2.6f)
        val baseAmp = if (listening) dp(10f) + level * (maxAmp - dp(10f)) else dp(3f)
        val k = (2 * PI / dp(195f)).toFloat()
        for ((idx, wave) in waves.withIndex()) {
            val amp = baseAmp * wave.amp * if (listening) breathe else 1f
            buildWave(w, cy, amp, k, t * wave.speed + wave.phase)
            if (idx == waves.lastIndex) canvas.drawPath(path, glow)
            canvas.drawPath(path, wave.paint)
        }

        // --- buttons
        drawRoundButton(canvas, keyboardBtn, palette.key, Icon.KEYBOARD, palette.text)
        drawRoundButton(canvas, deleteBtn, palette.key, Icon.DELETE, palette.text)

        if (listening) {
            val f = (t % 1.8f) / 1.8f
            fill.color = palette.accent
            fill.alpha = ((1f - f) * 115).toInt()
            canvas.drawCircle(mainBtn.centerX(), mainBtn.centerY(), big * (1f + 0.7f * f), fill)
            fill.alpha = 255
        }
        fill.color = palette.accent
        val s = if (pressed === mainBtn) 0.94f else 1f
        canvas.drawCircle(mainBtn.centerX(), mainBtn.centerY(), big * s, fill)
        Icons.draw(
            canvas, if (listening) Icon.PAUSE else Icon.MIC,
            mainBtn.centerX(), mainBtn.centerY(), dp(30f), palette.onAccent,
            filled = false, weight = 2f,
        )

        // gesture handle spacing is left empty; keep animating while visible
        if (isShown) postInvalidateOnAnimation()
    }

    private fun buildWave(w: Float, cy: Float, amp: Float, k: Float, phase: Float) {
        path.reset()
        val step = dp(4f)
        var x = 0f
        path.moveTo(0f, cy + amp * sin(phase))
        while (x <= w) {
            x += step
            path.lineTo(x, cy + amp * sin(k * x + phase))
        }
    }

    private fun drawRoundButton(canvas: Canvas, r: RectF, bg: Int, icon: Icon, fg: Int) {
        fill.color = if (pressed === r) palette.pressed else bg
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill)
        Icons.draw(canvas, icon, r.centerX(), r.centerY(), dp(24f), fg)
    }

    private fun hit(x: Float, y: Float): RectF? = listOf(mainBtn, keyboardBtn, deleteBtn).firstOrNull {
        hypot(x - it.centerX(), y - it.centerY()) <= it.width() / 2 + dp(6f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(e.x, e.y)
                if (pressed != null) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_UP -> {
                val target = pressed
                pressed = null
                if (target != null && hit(e.x, e.y) === target) {
                    when {
                        target === mainBtn -> listener?.onVoiceToggle()
                        target === keyboardBtn -> listener?.onVoiceKeyboard()
                        target === deleteBtn -> listener?.onVoiceDeleteWord()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        invalidate()
        return true
    }
}
