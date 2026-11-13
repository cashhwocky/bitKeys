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
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Voice-typing panel in the Material You style: "Listening…" pill, the wavy slider line
 * as flowing voice waves, rounded-square side buttons and a scalloped pause/mic button.
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Wave(val color: Int, val alpha: Int, val amp: Float, val waves: Float, val speed: Float, val width: Float, val phase: Float) {
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
            Wave(palette.accent, 140, 0.72f, 2.2f, 2.1f, dp(4f), 1.2f),
            Wave(Color.parseColor("#7FE0C4"), 115, 0.52f, 3.1f, -2.8f, dp(3f), 2.4f),
            Wave(palette.wave, 255, 1.0f, 2.6f, 3.6f, dp(6f), 0f),
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
        glow.strokeWidth = dp(14f)
        glow.maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
        glow.shader = fadeShader(palette.wave, 90)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (fixedHeight > 0) fixedHeight else dp(344f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyShaders()
    }

    private fun drawPill(canvas: Canvas, r: RectF) {
        fill.color = palette.strip
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val sheet = dp(28f)
        fill.color = palette.bg
        canvas.drawRoundRect(0f, 0f, w, height + sheet, sheet, sheet, fill)

        val pad = dp(18f)
        val t = SystemClock.uptimeMillis() / 1000f
        level += (targetLevel - level) * 0.18f

        // --- header pills
        text.textSize = sp(14f)
        text.typeface = Typeface.DEFAULT
        val pillH = dp(34f)
        val top = dp(14f)
        val statusW = text.measureText(status) + dp(44f)
        val statusPill = RectF(pad, top, pad + statusW, top + pillH)
        drawPill(canvas, statusPill)
        fill.color = palette.wave
        canvas.drawCircle(statusPill.left + dp(18f), statusPill.centerY(), dp(4f), fill)
        text.color = palette.text
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(status, statusPill.left + dp(30f), statusPill.centerY() - (text.descent() + text.ascent()) / 2f, text)

        val langW = text.measureText(language) + dp(28f)
        val langPill = RectF(w - pad - langW, top, w - pad, top + pillH)
        drawPill(canvas, langPill)
        text.color = palette.sub
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(language, langPill.centerX(), langPill.centerY() - (text.descent() + text.ascent()) / 2f, text)

        // --- bottom controls
        val btnCy = height - bottomInset - dp(26f) - dp(52f)
        val big = dp(52f)
        val side = dp(29f)
        mainBtn.set(w / 2 - big, btnCy - big, w / 2 + big, btnCy + big)
        keyboardBtn.set(pad, btnCy - side, pad + side * 2, btnCy + side)
        deleteBtn.set(w - pad - side * 2, btnCy - side, w - pad, btnCy + side)

        // --- waves between header and buttons
        val ribbonTop = statusPill.bottom + dp(8f)
        val ribbonBottom = mainBtn.top - dp(4f)
        val cy = (ribbonTop + ribbonBottom) / 2f
        val maxAmp = (ribbonBottom - ribbonTop) / 2f - dp(8f)
        val breathe = 0.8f + 0.2f * sin(t * 2.6f)
        val baseAmp = if (listening) dp(12f) + level * (maxAmp - dp(12f)) else dp(4f)
        for ((idx, wave) in waves.withIndex()) {
            val amp = (baseAmp * wave.amp * (if (listening) breathe else 1f)).coerceAtMost(maxAmp)
            val phase = if (listening) t * wave.speed + wave.phase else wave.phase
            Shapes.squiggle(path, 0f, w, cy, amp, wave.waves * w / dp(412f), phase)
            if (idx == waves.lastIndex) canvas.drawPath(path, glow)
            canvas.drawPath(path, wave.paint)
        }

        // --- buttons: rounded squares + scalloped center
        drawSquareButton(canvas, keyboardBtn, Icon.KEYBOARD)
        drawSquareButton(canvas, deleteBtn, Icon.DELETE)

        if (listening) {
            val f = (t % 1.8f) / 1.8f
            fill.color = palette.mod
            fill.alpha = ((1f - f) * 90).toInt()
            Shapes.drawCookie(canvas, mainBtn.centerX(), mainBtn.centerY(), big * (1f + 0.45f * f), fill, lobes = 12, amp = 0.07f)
            fill.alpha = 255
        }
        fill.color = palette.mod
        val s = if (pressed === mainBtn) 0.93f else 1f
        val spin = if (listening) (t * 12f) % 360f else 0f
        canvas.save()
        canvas.rotate(spin, mainBtn.centerX(), mainBtn.centerY())
        Shapes.drawCookie(canvas, mainBtn.centerX(), mainBtn.centerY(), big * s, fill, lobes = 12, amp = 0.07f)
        canvas.restore()
        Icons.draw(
            canvas, if (listening) Icon.PAUSE else Icon.MIC,
            mainBtn.centerX(), mainBtn.centerY(), dp(32f), palette.onMod, weight = 2.2f,
        )

        if (isShown) postInvalidateOnAnimation()
    }

    private fun drawSquareButton(canvas: Canvas, r: RectF, icon: Icon) {
        fill.color = if (pressed === r) palette.pressed else palette.key
        canvas.drawRoundRect(r, dp(22f), dp(22f), fill)
        Icons.draw(canvas, icon, r.centerX(), r.centerY(), dp(25f), palette.text, weight = 1.9f)
    }

    private fun hit(x: Float, y: Float): RectF? = listOf(mainBtn, keyboardBtn, deleteBtn).firstOrNull {
        hypot(x - it.centerX(), y - it.centerY()) <= it.width() / 2 + dp(8f)
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
