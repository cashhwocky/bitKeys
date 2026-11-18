package com.bitdroid.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onCharacter(ch: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onSpace()
        fun onEnter()
        fun onSuggestionClicked(word: String, index: Int)
        fun onCursorMove(delta: Int)
        fun onOpenSettings()
        fun onSwitchIme()
    }

    enum class Mode { LETTERS, SYMBOLS, SYMBOLS_MORE }
    enum class ShiftState { LOWER, SHIFTED, CAPS_LOCK }

    data class KeyCap(
        val code: String,
        val label: String,
        val weight: Float = 1f,
        val isFunc: Boolean = false,
        val isAccent: Boolean = false,
        val rect: RectF = RectF()
    )

    var listener: Listener? = null
    var theme: KeyboardTheme = Themes.byId(Storage.themeId)
        set(value) {
            field = value
            invalidate()
        }

    var mode: Mode = Mode.LETTERS
        set(value) {
            field = value
            rebuildLayout()
            invalidate()
        }

    var shiftState: ShiftState = ShiftState.SHIFTED
        set(value) {
            field = value
            rebuildLayout()
            invalidate()
        }

    private var suggestions: List<String> = listOf("I", "the", "you")
    private var primaryHighlight: String? = null
    private var rawComposing: String = ""

    private val rows = mutableListOf<List<KeyCap>>()
    private val suggestionRects = Array(3) { RectF() }
    private val settingsChipRect = RectF()
    private val imeSwitchChipRect = RectF()

    private var activeKey: KeyCap? = null
    private var downX = 0f
    private var downY = 0f
    private var isSpaceScrubbing = false
    private var lastScrubX = 0f
    private var lastShiftTapTime = 0L

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val numberHints = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
    )

    init {
        rebuildLayout()
    }

    fun updateSuggestions(candidates: List<String>, primary: String?, composing: String) {
        suggestions = candidates.take(3)
        primaryHighlight = primary
        rawComposing = composing
        invalidate()
    }

    fun refreshConfig() {
        Storage.load(context)
        theme = Themes.byId(Storage.themeId)
        rebuildLayout()
        requestLayout()
        invalidate()
    }

    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * scaledDensity

    private fun rebuildLayout() {
        rows.clear()
        val upper = shiftState != ShiftState.LOWER

        if (Storage.showNumberRow && mode == Mode.LETTERS) {
            rows.add(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
                KeyCap(code = it, label = it)
            })
        }

        when (mode) {
            Mode.LETTERS -> {
                rows.add(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map {
                    val l = if (upper) it.uppercase() else it
                    KeyCap(code = l, label = l)
                })
                rows.add(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map {
                    val l = if (upper) it.uppercase() else it
                    KeyCap(code = l, label = l)
                })
                val shiftLabel = when (shiftState) {
                    ShiftState.LOWER -> "⇧"
                    ShiftState.SHIFTED -> "⬆"
                    ShiftState.CAPS_LOCK -> "⇪"
                }
                val r3 = mutableListOf<KeyCap>()
                r3.add(KeyCap("SHIFT", shiftLabel, weight = 1.48f, isFunc = true))
                listOf("z", "x", "c", "v", "b", "n", "m").forEach {
                    val l = if (upper) it.uppercase() else it
                    r3.add(KeyCap(code = l, label = l))
                }
                r3.add(KeyCap("BACKSPACE", "⌫", weight = 1.48f, isFunc = true))
                rows.add(r3)

                rows.add(
                    listOf(
                        KeyCap("MODE_SYM", "?123", weight = 1.45f, isFunc = true),
                        KeyCap(",", ",", weight = 0.95f, isFunc = true),
                        KeyCap("SPACE", "English", weight = 4.6f),
                        KeyCap(".", ".", weight = 0.95f, isFunc = true),
                        KeyCap("ENTER", "↵", weight = 1.55f, isFunc = true, isAccent = true)
                    )
                )
            }

            Mode.SYMBOLS -> {
                rows.add(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
                    KeyCap(it, it)
                })
                rows.add(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map {
                    KeyCap(it, it)
                })
                val r3 = mutableListOf<KeyCap>()
                r3.add(KeyCap("MODE_SYM2", "=\\<", weight = 1.48f, isFunc = true))
                listOf("*", "\"", "'", ":", ";", "!", "?").forEach {
                    r3.add(KeyCap(it, it))
                }
                r3.add(KeyCap("BACKSPACE", "⌫", weight = 1.48f, isFunc = true))
                rows.add(r3)

                rows.add(
                    listOf(
                        KeyCap("MODE_ABC", "ABC", weight = 1.45f, isFunc = true),
                        KeyCap(",", ",", weight = 0.95f, isFunc = true),
                        KeyCap("SPACE", "space", weight = 4.6f),
                        KeyCap(".", ".", weight = 0.95f, isFunc = true),
                        KeyCap("ENTER", "↵", weight = 1.55f, isFunc = true, isAccent = true)
                    )
                )
            }

            Mode.SYMBOLS_MORE -> {
                rows.add(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map {
                    KeyCap(it, it)
                })
                rows.add(listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map {
                    KeyCap(it, it)
                })
                val r3 = mutableListOf<KeyCap>()
                r3.add(KeyCap("MODE_SYM", "?123", weight = 1.48f, isFunc = true))
                listOf("%", "©", "®", "™", "✓", "[", "]").forEach {
                    r3.add(KeyCap(it, it))
                }
                r3.add(KeyCap("BACKSPACE", "⌫", weight = 1.48f, isFunc = true))
                rows.add(r3)

                rows.add(
                    listOf(
                        KeyCap("MODE_ABC", "ABC", weight = 1.45f, isFunc = true),
                        KeyCap("<", "<", weight = 0.95f, isFunc = true),
                        KeyCap("SPACE", "space", weight = 4.6f),
                        KeyCap(">", ">", weight = 0.95f, isFunc = true),
                        KeyCap("ENTER", "↵", weight = 1.55f, isFunc = true, isAccent = true)
                    )
                )
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val stripHeight = dp(44f)
        val rowCount = rows.size
        val baseRowH = dp(51f) * Storage.keyHeightScale
        val bottomPad = dp(10f)
        val totalH = (stripHeight + (rowCount * baseRowH) + bottomPad).toInt()
        setMeasuredDimension(w, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeKeyBounds(w.toFloat(), h.toFloat())
    }

    private fun computeKeyBounds(w: Float, h: Float) {
        val stripH = dp(44f)

        // Left action icon (Settings), 3 Gboard suggestion slots, right action icon (Globe/IME)
        val sideBtnW = dp(42f)
        settingsChipRect.set(dp(4f), dp(4f), sideBtnW, stripH - dp(4f))
        imeSwitchChipRect.set(w - sideBtnW, dp(4f), w - dp(4f), stripH - dp(4f))

        val stripLeft = sideBtnW + dp(2f)
        val stripRight = w - sideBtnW - dp(2f)
        val slotW = (stripRight - stripLeft) / 3f
        for (i in 0..2) {
            suggestionRects[i].set(
                stripLeft + i * slotW,
                dp(3f),
                stripLeft + (i + 1) * slotW,
                stripH - dp(3f)
            )
        }

        val keyboardTop = stripH + dp(2f)
        val keyboardBottom = h - dp(8f)
        val availH = max(dp(160f), keyboardBottom - keyboardTop)
        val rowH = availH / rows.size.toFloat()

        val hGap = if (Storage.keyBorders) dp(3.0f) else dp(1.5f)
        val vGap = if (Storage.keyBorders) dp(4.2f) else dp(2.0f)

        rows.forEachIndexed { rIdx, row ->
            val top = keyboardTop + rIdx * rowH + vGap
            val bottom = keyboardTop + (rIdx + 1) * rowH - vGap
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()

            // Indent 9-key home row slightly like Gboard
            val sideInset = if (row.size == 9 && mode == Mode.LETTERS) w * 0.048f else dp(4f)
            val usableW = w - (sideInset * 2f)
            var cursorX = sideInset

            row.forEach { key ->
                val kw = usableW * (key.weight / totalWeight)
                key.rect.set(
                    cursorX + hGap,
                    top,
                    cursorX + kw - hGap,
                    bottom
                )
                cursorX += kw
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty() || rows[0].isEmpty() || rows[0][0].rect.isEmpty) {
            computeKeyBounds(width.toFloat(), height.toFloat())
        }

        // 1. Board Background
        bgPaint.color = theme.boardBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Gboard Top Suggestion Strip
        stripPaint.color = theme.stripBg
        canvas.drawRect(0f, 0f, width.toFloat(), dp(44f), stripPaint)

        // Left Settings Icon Pill & Right Globe Icon Pill
        subTextPaint.color = theme.subText
        subTextPaint.textSize = sp(15f)
        canvas.drawText("⚙", settingsChipRect.centerX(), settingsChipRect.centerY() + dp(5f), subTextPaint)
        canvas.drawText("🌐", imeSwitchChipRect.centerX(), imeSwitchChipRect.centerY() + dp(5f), subTextPaint)

        // Gboard 3-slot layout:
        // Center slot (slot 1) = Primary Bold / Autocorrect
        // Left slot (slot 0) = Exact typed or 2nd suggestion
        // Right slot (slot 2) = 3rd suggestion
        val slotDisplay = arrayOf(
            suggestions.getOrNull(1) ?: "",
            suggestions.getOrNull(0) ?: "",
            suggestions.getOrNull(2) ?: ""
        )

        dividerPaint.color = theme.dividerColor
        dividerPaint.strokeWidth = dp(1f)

        for (i in 0..2) {
            val r = suggestionRects[i]
            val word = slotDisplay[i]
            if (word.isNotEmpty()) {
                val isCenterPrimary = (i == 1)
                if (isCenterPrimary && primaryHighlight != null && Storage.autoCorrect) {
                    // Subtle Gboard pill highlight behind active autocorrect
                    keyPaint.color = theme.keyBg
                    val pill = RectF(r.left + dp(6f), r.top + dp(3f), r.right - dp(6f), r.bottom - dp(3f))
                    canvas.drawRoundRect(pill, dp(16f), dp(16f), keyPaint)
                }

                val paint = if (isCenterPrimary) boldTextPaint else textPaint
                paint.textSize = sp(15.5f)
                paint.color = if (isCenterPrimary && primaryHighlight != null) theme.accentBg else theme.keyText

                val displayWord = if (word.length > 13) word.take(12) + "…" else word
                val fm = paint.fontMetrics
                val textY = r.centerY() - (fm.ascent + fm.descent) / 2f
                canvas.drawText(displayWord, r.centerX(), textY, paint)
            }

            if (i < 2) {
                canvas.drawLine(r.right, dp(11f), r.right, dp(33f), dividerPaint)
            }
        }

        // 3. Draw Keys
        val corner = dp(theme.cornerRadiusDp)
        rows.forEach { row ->
            row.forEach { key ->
                val isPressed = (activeKey === key)

                keyPaint.color = when {
                    key.isAccent -> theme.accentBg
                    isPressed -> theme.keyPressedBg
                    key.isFunc -> theme.funcKeyBg
                    Storage.keyBorders -> theme.keyBg
                    else -> theme.boardBg
                }

                if (Storage.keyBorders || key.isFunc || key.isAccent || isPressed || key.code == "SPACE") {
                    val drawRect = if (key.code == "SPACE" && !Storage.keyBorders) {
                        RectF(key.rect.left, key.rect.top + dp(5f), key.rect.right, key.rect.bottom - dp(5f))
                    } else key.rect
                    if (key.code == "SPACE" && !Storage.keyBorders) {
                        keyPaint.color = theme.keyBg
                    }
                    canvas.drawRoundRect(drawRect, corner, corner, keyPaint)
                }

                val labelPaint = if (key.isFunc || key.isAccent) boldTextPaint else textPaint
                labelPaint.color = when {
                    key.isAccent -> theme.accentText
                    key.code == "SHIFT" && shiftState == ShiftState.CAPS_LOCK -> theme.accentBg
                    key.code == "SPACE" -> theme.subText
                    else -> theme.keyText
                }
                labelPaint.textSize = when {
                    key.code == "SPACE" -> sp(13f)
                    key.label.length > 1 -> sp(13.5f)
                    else -> sp(20.5f)
                }

                val fm = labelPaint.fontMetrics
                val cy = key.rect.centerY() - (fm.ascent + fm.descent) / 2f
                canvas.drawText(key.label, key.rect.centerX(), cy, labelPaint)

                // Top-right secondary number hint on Q-P row when number row is hidden
                val hint = numberHints[key.code.lowercase()]
                if (hint != null && !Storage.showNumberRow && mode == Mode.LETTERS) {
                    subTextPaint.color = theme.subText
                    subTextPaint.textSize = sp(9.5f)
                    canvas.drawText(
                        hint,
                        key.rect.right - dp(7f),
                        key.rect.top + dp(11f),
                        subTextPaint
                    )
                }
            }
        }

        // 4. Floating Gboard Key Preview Popup on active character key
        val pressed = activeKey
        if (pressed != null && !pressed.isFunc && pressed.code != "SPACE") {
            val popW = max(pressed.rect.width() * 1.28f, dp(46f))
            val popH = pressed.rect.height() * 1.32f
            val popLeft = (pressed.rect.centerX() - popW / 2f).coerceIn(dp(4f), width - popW - dp(4f))
            val popTop = max(dp(2f), pressed.rect.top - popH - dp(6f))
            val popRect = RectF(popLeft, popTop, popLeft + popW, popTop + popH)

            popupPaint.color = theme.popupBg
            canvas.drawRoundRect(popRect, dp(12f), dp(12f), popupPaint)

            boldTextPaint.color = theme.keyText
            boldTextPaint.textSize = sp(26f)
            val fm = boldTextPaint.fontMetrics
            val ty = popRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(pressed.label, popRect.centerX(), ty, boldTextPaint)
        }
    }

    /**
     * Finds the closest key using cushioned hitboxes so slightly off-center presses
     * still land on the intended key.
     */
    private fun findKeyAt(x: Float, y: Float): KeyCap? {
        if (y <= dp(44f)) return null
        var bestKey: KeyCap? = null
        var bestDist = Float.MAX_VALUE

        for (row in rows) {
            for (k in row) {
                if (k.rect.contains(x, y)) return k
                val dx = when {
                    x < k.rect.left -> k.rect.left - x
                    x > k.rect.right -> x - k.rect.right
                    else -> 0f
                }
                val dy = when {
                    y < k.rect.top -> k.rect.top - y
                    y > k.rect.bottom -> y - k.rect.bottom
                    else -> 0f
                }
                val dist = hypot(dx, dy)
                if (dist < bestDist && dist <= dp(18f)) {
                    bestDist = dist
                    bestKey = k
                }
            }
        }
        return bestKey
    }

    private fun triggerHaptic() {
        if (!Storage.hapticEnabled) return
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(Storage.hapticStrength.toLong().coerceIn(5L, 60L), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(15L)
            }
        } catch (_: Exception) {
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                isSpaceScrubbing = false
                lastScrubX = x

                if (y <= dp(44f)) {
                    triggerHaptic()
                    return true
                }

                activeKey = findKeyAt(x, y)
                if (activeKey != null) {
                    triggerHaptic()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Spacebar cursor trackpad scrubbing
                if (activeKey?.code == "SPACE") {
                    val deltaX = x - lastScrubX
                    if (abs(x - downX) > dp(14f) || isSpaceScrubbing) {
                        isSpaceScrubbing = true
                        val step = dp(11f)
                        if (abs(deltaX) >= step) {
                            val ticks = (deltaX / step).toInt()
                            if (ticks != 0) {
                                listener?.onCursorMove(ticks)
                                triggerHaptic()
                                lastScrubX += ticks * step
                            }
                        }
                        return true
                    }
                }

                // Backspace swipe-left to delete whole word
                if (activeKey?.code == "BACKSPACE" && (downX - x) > dp(42f)) {
                    listener?.onDeleteWord()
                    triggerHaptic()
                    activeKey = null
                    invalidate()
                    return true
                }

                val hovered = findKeyAt(x, y)
                if (hovered !== activeKey) {
                    activeKey = hovered
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isSpaceScrubbing) {
                    isSpaceScrubbing = false
                    activeKey = null
                    invalidate()
                    return true
                }

                // Top bar tap
                if (y <= dp(44f) && downY <= dp(44f)) {
                    if (settingsChipRect.contains(x, y)) {
                        listener?.onOpenSettings()
                        return true
                    }
                    if (imeSwitchChipRect.contains(x, y)) {
                        listener?.onSwitchIme()
                        return true
                    }
                    val slotDisplay = arrayOf(
                        suggestions.getOrNull(1) ?: "",
                        suggestions.getOrNull(0) ?: "",
                        suggestions.getOrNull(2) ?: ""
                    )
                    for (i in 0..2) {
                        if (suggestionRects[i].contains(x, y)) {
                            val picked = slotDisplay[i]
                            if (picked.isNotEmpty()) {
                                listener?.onSuggestionClicked(picked, i)
                            }
                            return true
                        }
                    }
                    return true
                }

                val released = activeKey ?: findKeyAt(x, y)
                activeKey = null
                invalidate()

                if (released != null) {
                    handleKeyTap(released)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeKey = null
                isSpaceScrubbing = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleKeyTap(key: KeyCap) {
        when (key.code) {
            "SHIFT" -> {
                val now = System.currentTimeMillis()
                shiftState = when {
                    shiftState == ShiftState.SHIFTED && (now - lastShiftTapTime) < 380L -> ShiftState.CAPS_LOCK
                    shiftState == ShiftState.LOWER -> ShiftState.SHIFTED
                    else -> ShiftState.LOWER
                }
                lastShiftTapTime = now
            }
            "BACKSPACE" -> listener?.onBackspace()
            "SPACE" -> listener?.onSpace()
            "ENTER" -> listener?.onEnter()
            "MODE_SYM" -> mode = Mode.SYMBOLS
            "MODE_SYM2" -> mode = Mode.SYMBOLS_MORE
            "MODE_ABC" -> mode = Mode.LETTERS
            else -> {
                listener?.onCharacter(key.code)
                if (shiftState == ShiftState.SHIFTED) {
                    shiftState = ShiftState.LOWER
                }
            }
        }
    }
}
