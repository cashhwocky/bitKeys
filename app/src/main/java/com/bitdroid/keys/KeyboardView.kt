package com.bitdroid.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.RoundedCorner
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.min

/**
 * The whole keyboard surface — toolbar/suggestion strip plus key rows — drawn on one Canvas.
 * Rounded keys, AMOLED-first palette, multi-touch rollover, long-press for digits,
 * swipe on the space bar to move the cursor.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onDelete()
        fun onEnter()
        fun onSuggestion(word: String)
        fun onToolbar(action: ToolbarAction)
        fun onCursorMove(steps: Int)
    }

    enum class ToolbarAction { EMOJI, CLIPBOARD, SETTINGS, MIC, HIDE }
    enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD }
    enum class Shift { OFF, ON, LOCKED }
    enum class EnterStyle { ENTER, SEND, SEARCH, GO, NEXT, DONE }

    private enum class Kind { CHAR, SHIFT, DELETE, MODE, PAGE, ABC, NUMPAD, ENTER, SPACE, SPACER }

    private class Key(
        val kind: Kind,
        val label: String = "",
        val weight: Float = 1f,
        val alt: String? = null,
        val mod: Boolean = false,
    ) {
        val rect = RectF()
    }

    private class StripTarget(val rect: RectF, val word: String? = null, val action: ToolbarAction? = null, val emoji: Boolean = false, val slot: Int = -1)

    private class Pointer(
        val id: Int,
        var key: Key?,
        var strip: StripTarget?,
        var anchorX: Float,
        var longFired: Boolean = false,
        var cursorMode: Boolean = false,
        var longPress: Runnable? = null,
        var repeat: Runnable? = null,
    )

    var listener: Listener? = null

    private var prefs = KeyboardPrefs.load(context)
    private var palette = Themes.palette(context, prefs)

    var mode = Mode.LETTERS
        private set
    var shift = Shift.OFF
        private set
    private var lastShiftTap = 0L
    private var enterStyle = EnterStyle.ENTER
    private var slots: List<String> = emptyList()

    private var rows: List<List<Key>> = emptyList()
    private val strip = ArrayList<StripTarget>()
    private val pointers = HashMap<Int, Pointer>()
    private val pressedKeys = HashSet<Key>()
    private val pressedStrip = HashSet<StripTarget>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val stripH = dp(52f)
    private val keyH = dp(54f)
    private val rowGap = dp(8f)
    private val keyGap = dp(5f)
    private val sidePad = dp(6f)
    private val topPad = dp(4f)
    private val stripGap = dp(6f)
    private val bottomPad = dp(22f)

    /** iOS-style Glass theme: floating panel, flat keys, 123|space|action row, emoji + mic bar. */
    private val glass get() = prefs.theme == "glass"
    private val glassBarH = dp(42f)
    private val hPad get() = if (glass) dp(9f) else sidePad
    private var barTop = 0f

    // ---- key animations: squish + glow, letter bubble, ring; spinning Enter and flowing space line
    private val downAt = HashMap<Key, Long>()
    private val upAt = HashMap<Key, Long>()
    private var bubbleKey: Key? = null
    private var bubbleAt = 0L
    private val animPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubbleRect = RectF()

    private fun markDown(k: Key) {
        val now = SystemClock.uptimeMillis()
        downAt[k] = now
        upAt.remove(k)
        if (k.kind == Kind.CHAR) { bubbleKey = k; bubbleAt = now }
        invalidate()
    }

    private fun markUp(k: Key) {
        if (downAt.containsKey(k)) upAt[k] = SystemClock.uptimeMillis()
        invalidate()
    }

    /** 0..1 press amount for a key: ramps in fast, springs back out. */
    private fun pressAmount(k: Key, now: Long): Float {
        val d = downAt[k] ?: return 0f
        val inA = ((now - d) / 70f).coerceIn(0f, 1f)
        val u = upAt[k] ?: return inA
        val out = 1f - ((now - u) / 150f).coerceIn(0f, 1f)
        if (out <= 0f) { downAt.remove(k); upAt.remove(k) }
        return min(inA, out)
    }
    /** The phone's own bottom screen-corner radius (px), so the glass panel can follow it. */
    private var screenCorner = 0
    private val panelSide get() = dp(5f)
    private val panelBottomRadius get() = maxOf(dp(30f), screenCorner - panelSide)
    var navInset = 0
        private set

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tmp = RectF()

    init {
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            if (bottom != navInset) {
                navInset = bottom
                requestLayout()
            }
            val corner = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            if (corner != screenCorner) {
                screenCorner = corner
                layoutStrip()
                invalidate()
            }
            insets
        }
        buildRows()
    }

    fun applyPrefs(p: KeyboardPrefs) {
        prefs = p
        palette = Themes.palette(context, p)
        buildRows()
        requestLayout()
        invalidate()
    }

    fun palette(): Palette = palette

    private var phonePad = false

    fun openNumpad(phone: Boolean) {
        phonePad = phone
        mode = Mode.NUMPAD
        buildRows()
        requestLayout()
        invalidate()
    }

    fun switchMode(m: Mode) {
        if (m == mode) return
        mode = m
        buildRows()
        requestLayout()
        invalidate()
    }

    fun setAutoShift(on: Boolean) {
        if (shift == Shift.LOCKED) return
        val next = if (on) Shift.ON else Shift.OFF
        if (next != shift) {
            shift = next
            invalidate()
        }
    }

    fun setEnterStyle(style: EnterStyle) {
        enterStyle = style
        invalidate()
    }

    /** Three slots [verbatim, best, second]; an empty list shows the toolbar instead. */
    fun setSuggestions(list: List<String>) {
        if (list == slots) return
        slots = list
        layoutStrip()
        invalidate()
    }

    private fun chars(s: String, alts: String? = null): List<Key> =
        s.mapIndexed { i, ch -> Key(Kind.CHAR, ch.toString(), 1f, alts?.getOrNull(i)?.toString()) }

    private fun bottomRow(modeLabel: String, numpadKey: Boolean = false): List<Key> =
        if (glass) listOfNotNull(
            Key(Kind.MODE, if (modeLabel == "?123") "123" else modeLabel, if (numpadKey) 1.4f else 1.75f, mod = true),
            if (numpadKey) Key(Kind.NUMPAD, "1234", 1.4f, mod = true) else null,
            Key(Kind.CHAR, ",", 1f, mod = true),
            Key(Kind.SPACE, "", if (numpadKey) 3.4f else 4.5f),
            Key(Kind.CHAR, ".", 1f, mod = true),
            Key(Kind.ENTER, "", 1.75f),
        ) else classicBottomRow(modeLabel, numpadKey)

    private fun classicBottomRow(modeLabel: String, numpadKey: Boolean = false): List<Key> = listOfNotNull(
        Key(Kind.MODE, modeLabel, 1.5f, mod = true),
        if (numpadKey) Key(Kind.NUMPAD, "1234", 1.3f, mod = true) else null,
        Key(Kind.CHAR, ",", 1f, mod = true),
        Key(Kind.SPACE, "", if (numpadKey) 3.3f else 4.6f),
        Key(Kind.CHAR, ".", 1f, mod = true),
        Key(Kind.ENTER, "", 1.5f),
    )

    private fun buildRows() {
        rows = when (mode) {
            Mode.LETTERS -> buildList {
                if (prefs.numberRow) add(chars("1234567890"))
                add(chars("qwertyuiop", if (prefs.numberRow) null else "1234567890"))
                add(listOf(Key(Kind.SPACER, weight = 0.5f)) + chars("asdfghjkl") + Key(Kind.SPACER, weight = 0.5f))
                add(listOf(Key(Kind.SHIFT, weight = 1.5f, mod = true)) + chars("zxcvbnm") + Key(Kind.DELETE, weight = 1.5f, mod = true))
                add(bottomRow("?123"))
            }
            Mode.SYMBOLS -> listOf(
                chars("1234567890"),
                chars("@#\$_&-+()/"),
                listOf(Key(Kind.PAGE, "=\\<", 1.5f, mod = true)) + chars("*\"':;!?") + Key(Kind.DELETE, weight = 1.5f, mod = true),
                bottomRow("ABC", numpadKey = true),
            )
            Mode.SYMBOLS2 -> listOf(
                chars("~`|•√π÷×¶∆"),
                chars("£¢€¥^°={}\\"),
                listOf(Key(Kind.PAGE, "?123", 1.5f, mod = true)) + chars("%©®™✓[]") + Key(Kind.DELETE, weight = 1.5f, mod = true),
                bottomRow("ABC", numpadKey = true),
            )
            Mode.NUMPAD -> listOf(
                chars("123") + Key(Kind.CHAR, if (phonePad) "+" else "-", mod = true),
                chars("456") + Key(Kind.CHAR, if (phonePad) "*" else ",", mod = true),
                chars("789") + Key(Kind.DELETE, mod = true),
                listOf(
                    Key(Kind.ABC, "ABC", mod = true),
                    Key(Kind.CHAR, "0"),
                    Key(Kind.CHAR, if (phonePad) "#" else ".", mod = true),
                    Key(Kind.ENTER),
                ),
            )
        }
        if (width > 0) layoutKeys()
        layoutStrip()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = topPad + stripH + stripGap + rows.size * keyH + (rows.size - 1) * rowGap +
            (if (glass) glassBarH + dp(2f) else bottomPad) + navInset
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
        layoutStrip()
    }

    private fun layoutKeys() {
        var y = topPad + stripH + stripGap
        for (row in rows) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val avail = width - 2 * hPad - (row.size - 1) * keyGap
            val unit = avail / totalWeight
            var x = hPad
            for (k in row) {
                val w = k.weight * unit
                k.rect.set(x, y, x + w, y + keyH)
                x += w + keyGap
            }
            y += keyH + rowGap
        }
        barTop = y - rowGap + dp(2f)
    }

    private val stripPill = RectF()
    private var slotLeft = 0f
    private var slotW = 0f

    private fun layoutStrip() {
        strip.clear()
        if (width == 0) return
        if (glass) {
            layoutGlassStrip()
            return
        }
        stripPill.set(sidePad, topPad, width - sidePad, topPad + stripH)
        val cy = stripPill.centerY()
        val mic = dp(42f)
        val tool = dp(38f)
        val micRight = stripPill.right - dp(5f)
        strip.add(StripTarget(RectF(micRight - mic, cy - mic / 2, micRight, cy + mic / 2), action = ToolbarAction.MIC))

        // Always keep the top Hide/Close Keyboard arrow right next to the Mic button!
        val hideRight = micRight - mic - dp(4f)
        strip.add(StripTarget(RectF(hideRight - tool, cy - tool / 2, hideRight, cy + tool / 2), action = ToolbarAction.HIDE))

        if (slots.isEmpty()) {
            val icons = listOf<Pair<ToolbarAction?, Boolean>>(
                null to true, // emoji panel
                ToolbarAction.CLIPBOARD to false,
                ToolbarAction.SETTINGS to false,
            )
            icons.forEachIndexed { i, (action, isEmoji) ->
                val left = stripPill.left + dp(6f) + i * ( dp(40f) + dp(6f))
                strip.add(StripTarget(RectF(left, cy - dp(20f), left + dp(40f), cy + dp(20f)), action = action, emoji = isEmoji))
            }
        } else {
            slotLeft = stripPill.left + dp(8f)
            val right = hideRight - tool - dp(4f)
            slotW = (right - slotLeft) / 3f
            slots.forEachIndexed { i, word ->
                if (word.isNotEmpty()) {
                    val l = slotLeft + i * slotW
                    strip.add(StripTarget(RectF(l, cy - dp(20f), l + slotW, cy + dp(20f)), word = word, slot = i))
                }
            }
        }
    }

    private fun layoutGlassStrip() {
        val top = topPad
        val cy = top + stripH / 2f
        val size = dp(44f)
        if (slots.isEmpty()) {
            // quiet tools: clipboard + settings on the left, hide arrow on the right
            listOf(ToolbarAction.CLIPBOARD, ToolbarAction.SETTINGS).forEachIndexed { i, a ->
                val l = hPad + dp(4f) + i * (size + dp(4f))
                strip.add(StripTarget(RectF(l, cy - size / 2, l + size, cy + size / 2), action = a))
            }
            val r = width - hPad - dp(4f)
            strip.add(StripTarget(RectF(r - size, cy - size / 2, r, cy + size / 2), action = ToolbarAction.HIDE))
        } else {
            slotLeft = hPad + dp(4f)
            slotW = (width - hPad - dp(4f) - slotLeft) / 3f
            slots.forEachIndexed { i, word ->
                if (word.isNotEmpty()) {
                    val l = slotLeft + i * slotW
                    strip.add(StripTarget(RectF(l, cy - dp(21f), l + slotW, cy + dp(21f)), word = word, slot = i))
                }
            }
        }
        // emoji (where the globe would be) and mic under the keys
        val by = barTop + glassBarH / 2f
        val inward = maxOf(dp(26f), panelBottomRadius * 0.55f)
        val ex = hPad + inward
        val mx = width - hPad - inward
        strip.add(StripTarget(RectF(ex - size / 2, by - size / 2, ex + size / 2, by + size / 2), emoji = true))
        strip.add(StripTarget(RectF(mx - size / 2, by - size / 2, mx + size / 2, by + size / 2), action = ToolbarAction.MIC))
    }

    private val glassPanel = RectF()
    private val panelPath = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val sheetRadius = dp(28f)
    private val wavePath = Path()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        if (glass) {
            // floating smoked-glass panel with big corners and a faint light edge
            glassPanel.set(panelSide, 0f, width - panelSide, height - panelSide)
            val tr = dp(34f)
            val br = panelBottomRadius
            panelPath.reset()
            panelPath.addRoundRect(glassPanel, floatArrayOf(tr, tr, tr, tr, br, br, br, br), Path.Direction.CW)
            fillPaint.color = palette.bg
            canvas.drawPath(panelPath, fillPaint)
            edgePaint.color = Color.argb(34, 255, 255, 255)
            edgePaint.strokeWidth = dp(0.8f)
            canvas.drawPath(panelPath, edgePaint)
            drawGlassStrip(canvas)
        } else {
            fillPaint.color = palette.bg
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height + sheetRadius, sheetRadius, sheetRadius, fillPaint)
            drawStrip(canvas)
        }
        drawKeys(canvas)
        drawPressEffects(canvas)
        // Spinning Enter + flowing space line run continuously; key effects run while active.
        if (isShown && (!glass || downAt.isNotEmpty() || bubbleKey != null)) postInvalidateOnAnimation()
    }

    /** Ring that expands around the pressed key, and the letter bubble above it. */
    private fun drawPressEffects(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val k = bubbleKey ?: return
        val dt = now - bubbleAt
        val held = k in pressedKeys
        if (!held && dt > 260) { bubbleKey = null; return }

        // ring
        val rr = (dt / 300f).coerceIn(0f, 1f)
        if (rr < 1f) {
            ringPaint.color = palette.accent
            ringPaint.alpha = (180 * (1f - rr)).toInt()
            ringPaint.strokeWidth = dp(2f)
            val grow = dp(6f) * rr
            tmp.set(k.rect.left - grow, k.rect.top - grow, k.rect.right + grow, k.rect.bottom + grow)
            canvas.drawRoundRect(tmp, dp(12f), dp(12f), ringPaint)
        }

        // letter bubble (stays while held, fades after release)
        val label = if (mode == Mode.LETTERS && shift != Shift.OFF) k.label.uppercase() else k.label
        val inS = (dt / 60f).coerceIn(0f, 1f)
        val eased = 1f - (1f - inS) * (1f - inS) * (1f - inS)
        val alpha = if (held) 1f else (1f - ((now - (upAt[k] ?: now)) / 90f)).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val w = k.rect.width() * 1.35f
        val h = dp(58f)
        val bottom = k.rect.top + dp(6f)
        val cx = k.rect.centerX().coerceIn(w / 2f + dp(2f), width - w / 2f - dp(2f))
        val scale = 0.6f + 0.4f * eased
        bubbleRect.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        canvas.save()
        canvas.translate(0f, (1f - eased) * dp(14f))
        canvas.scale(scale, scale, cx, bottom)
        animPaint.color = Color.BLACK
        animPaint.alpha = (70 * alpha).toInt()
        canvas.drawRoundRect(bubbleRect.left, bubbleRect.top + dp(3f), bubbleRect.right, bubbleRect.bottom + dp(3f), dp(16f), dp(16f), animPaint)
        animPaint.color = blend(palette.key, palette.text, 0.18f)
        animPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(bubbleRect, dp(16f), dp(16f), animPaint)
        textPaint.color = palette.text
        textPaint.alpha = (255 * alpha).toInt()
        textPaint.textSize = sp(30f)
        canvas.drawText(label, cx, baseline(bubbleRect, textPaint), textPaint)
        textPaint.alpha = 255
        canvas.restore()
    }

    private fun drawGlassStrip(canvas: Canvas) {
        if (slots.isNotEmpty() && strip.any { it.word != null }) {
            fillPaint.color = Color.argb(40, 255, 255, 255)
            val cy = topPad + stripH / 2f
            for (i in 1..2) {
                val x = slotLeft + i * slotW
                canvas.drawRect(x - dp(0.4f), cy - dp(12f), x + dp(0.4f), cy + dp(12f), fillPaint)
            }
        }
        for (t in strip) {
            val r = t.rect
            if (t in pressedStrip) {
                fillPaint.color = Color.argb(50, 255, 255, 255)
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fillPaint)
            }
            when {
                t.word != null -> {
                    textPaint.color = if (t.slot == 0) palette.sub else palette.text
                    textPaint.textSize = sp(17f)
                    textPaint.typeface = Typeface.DEFAULT
                    val shown = if (t.slot == 0) "\u201C${t.word}\u201D" else t.word
                    canvas.drawText(ellipsize(shown, textPaint, r.width() - dp(8f)), r.centerX(), baseline(r, textPaint), textPaint)
                }
                t.emoji -> Icons.draw(canvas, Icon.EMOJI, r.centerX(), r.centerY(), dp(27f), palette.text, weight = 1.6f)
                t.action == ToolbarAction.MIC -> Icons.draw(canvas, Icon.MIC, r.centerX(), r.centerY(), dp(25f), palette.micFg, weight = 1.7f)
                t.action == ToolbarAction.HIDE -> Icons.draw(canvas, Icon.CHEVRON_DOWN, r.centerX(), r.centerY(), dp(25f), palette.sub, weight = 2f)
                t.action == ToolbarAction.CLIPBOARD -> Icons.draw(canvas, Icon.CLIPBOARD, r.centerX(), r.centerY(), dp(21f), palette.sub, weight = 1.8f)
                else -> Icons.draw(canvas, Icon.SETTINGS, r.centerX(), r.centerY(), dp(21f), palette.sub, weight = 1.8f)
            }
        }
    }

    private fun drawStrip(canvas: Canvas) {
        fillPaint.color = palette.strip
        canvas.drawRoundRect(stripPill, stripPill.height() / 2, stripPill.height() / 2, fillPaint)

        if (slots.isNotEmpty() && strip.any { it.word != null }) {
            fillPaint.color = palette.text
            fillPaint.alpha = 90
            val cy = stripPill.centerY()
            for (i in 1..2) {
                val x = slotLeft + i * slotW
                canvas.drawRect(x - dp(0.5f), cy - dp(11f), x + dp(0.5f), cy + dp(11f), fillPaint)
            }
            fillPaint.alpha = 255
        }
        val iconSize = dp(21f)
        for (t in strip) {
            val r = t.rect
            val pressed = t in pressedStrip
            when {
                t.word != null -> {
                    if (pressed) {
                        fillPaint.color = palette.pressed
                        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fillPaint)
                    }
                    val best = t.slot == 1
                    textPaint.color = palette.text
                    textPaint.textSize = sp(16f)
                    textPaint.typeface = if (best) Typeface.create(Typeface.DEFAULT, 700, false) else Typeface.DEFAULT
                    val label = ellipsize(t.word, textPaint, r.width() - dp(8f))
                    canvas.drawText(label, r.centerX(), baseline(r, textPaint), textPaint)
                    textPaint.typeface = Typeface.DEFAULT
                }
                t.action == ToolbarAction.HIDE -> {
                    if (pressed) {
                        fillPaint.color = palette.pressed
                        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fillPaint)
                    }
                    Icons.draw(canvas, Icon.CHEVRON_DOWN, r.centerX(), r.centerY(), dp(26f), palette.text, weight = 2.2f)
                }
                t.action == ToolbarAction.MIC -> {
                    fillPaint.color = palette.micBg
                    canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2 * if (pressed) 0.92f else 1f, fillPaint)
                    Icons.draw(canvas, Icon.MIC, r.centerX(), r.centerY(), dp(22f), palette.micFg, weight = 2f)
                }
                else -> {
                    fillPaint.color = palette.tool
                    tmp.set(r)
                    if (pressed) tmp.inset(dp(1.5f), dp(1.5f))
                    canvas.drawRoundRect(tmp, dp(14f), dp(14f), fillPaint)
                    val icon = when {
                        t.emoji -> Icon.EMOJI
                        t.action == ToolbarAction.CLIPBOARD -> Icon.CLIPBOARD
                        else -> Icon.SETTINGS
                    }
                    Icons.draw(canvas, icon, r.centerX(), r.centerY(), if (icon == Icon.EMOJI) iconSize else dp(20f), palette.onTool, weight = 2f)
                }
            }
        }
    }

    private fun drawKeys(canvas: Canvas) {
        val baseRadius = dp(Themes.keyRadiusDp(prefs.shape))
        val square = prefs.shape == "square"
        val upper = mode == Mode.LETTERS && shift != Shift.OFF
        for (row in rows) for (k in row) {
            if (k.kind == Kind.SPACER) continue
            val pressed = k in pressedKeys
            tmp.set(k.rect)
            val cx = tmp.centerX()
            val cy = tmp.centerY()

            if (k.kind == Kind.ENTER && glass) {
                val rr = dp(9f)
                canvas.drawRoundRect(tmp.left, tmp.top + dp(1f), tmp.right, tmp.bottom + dp(1f), rr, rr, shadowPaint)
                fillPaint.color = if (pressed) blend(palette.accent, Color.WHITE, 0.2f) else palette.accent
                canvas.drawRoundRect(tmp, rr, rr, fillPaint)
                val icon = when (enterStyle) {
                    EnterStyle.SEARCH -> Icon.SEARCH
                    EnterStyle.DONE -> Icon.DONE
                    EnterStyle.ENTER -> Icon.NEWLINE
                    else -> Icon.ARROW
                }
                Icons.draw(canvas, icon, cx, cy, dp(22f), palette.onAccent, weight = 2.3f)
                continue
            }

            if (k.kind == Kind.ENTER) {
                val radius = min(tmp.height() / 2f + dp(4f), tmp.width() / 2f) * if (pressed) 0.9f else 1f
                fillPaint.color = palette.accent
                canvas.save()
                canvas.rotate((SystemClock.uptimeMillis() % 9000L) / 9000f * 360f, cx, cy)
                Shapes.drawCookie(canvas, cx, cy, radius, fillPaint)
                canvas.restore()
                val icon = when (enterStyle) {
                    EnterStyle.SEARCH -> Icon.SEARCH
                    EnterStyle.DONE -> Icon.DONE
                    EnterStyle.ENTER -> Icon.NEWLINE
                    else -> Icon.ARROW
                }
                Icons.draw(canvas, icon, cx, cy, dp(23f), palette.onAccent, weight = 2.4f)
                continue
            }

            val a = pressAmount(k, SystemClock.uptimeMillis())
            if (a > 0f) {
                val sq = 0.09f * a
                tmp.inset(tmp.width() * sq / 2f, tmp.height() * sq / 2f)
                tmp.offset(0f, dp(1.5f) * a)
            }
            val isMod = k.mod || k.kind == Kind.SHIFT || k.kind == Kind.DELETE
            val bg = when {
                pressed && isMod -> blend(palette.mod, palette.onMod, 0.18f)
                pressed -> palette.pressed
                isMod -> palette.mod
                else -> palette.key
            }
            val wide = k.kind == Kind.SPACE || k.kind == Kind.MODE || k.kind == Kind.ABC || k.kind == Kind.NUMPAD
            val r = when {
                glass && (square || prefs.shape == "round") -> dp(9f)
                wide && !square -> tmp.height() / 2
                else -> min(baseRadius, min(tmp.width(), tmp.height()) / 2)
            }

            if (glass) canvas.drawRoundRect(tmp.left, tmp.top + dp(1f), tmp.right, tmp.bottom + dp(1f), r, r, shadowPaint)
            fillPaint.color = bg
            canvas.drawRoundRect(tmp, r, r, fillPaint)
            if (a > 0f) {
                animPaint.color = Color.WHITE
                animPaint.alpha = (40 * a).toInt()
                canvas.drawRoundRect(tmp, r, r, animPaint)
            }
            if (prefs.borders) {
                borderPaint.color = palette.border
                canvas.drawRoundRect(tmp, r, r, borderPaint)
            }

            val fg = if (isMod) palette.onMod else palette.text
            when (k.kind) {
                Kind.CHAR -> {
                    textPaint.color = fg
                    textPaint.textSize = sp(if (mode == Mode.NUMPAD) 26f else 23f)
                    val label = if (upper && k.label[0].isLetter()) k.label.uppercase() else k.label
                    canvas.drawText(label, cx, baseline(tmp, textPaint), textPaint)
                    if (k.alt != null) {
                        textPaint.color = palette.sub
                        textPaint.textSize = sp(10f)
                        canvas.drawText(k.alt, tmp.right - dp(8f), tmp.top + dp(13f), textPaint)
                    }
                }
                Kind.MODE, Kind.PAGE, Kind.ABC, Kind.NUMPAD -> {
                    labelPaint.color = fg
                    labelPaint.textSize = sp(15f)
                    canvas.drawText(k.label, cx, baseline(tmp, labelPaint), labelPaint)
                }
                Kind.SPACE -> if (!glass) {
                    val inset = tmp.width() * 0.09f
                    val phase = (SystemClock.uptimeMillis() % 4000L) / 4000f * 2f * Math.PI.toFloat()
                    Shapes.squiggle(wavePath, tmp.left + inset, tmp.right - inset, tmp.centerY(), dp(4f), 4f, -phase)
                    wavePaint.color = palette.wave
                    wavePaint.strokeWidth = dp(3f)
                    canvas.drawPath(wavePath, wavePaint)
                }
                Kind.SHIFT -> Icons.draw(
                    canvas, if (shift == Shift.LOCKED) Icon.SHIFT_LOCK else Icon.SHIFT,
                    cx, cy, dp(23f), fg, filled = shift != Shift.OFF,
                )
                Kind.DELETE -> Icons.draw(canvas, Icon.DELETE, cx, cy, dp(25f), fg, weight = 1.9f)
                Kind.ENTER, Kind.SPACER -> Unit
            }
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    private fun baseline(r: RectF, p: Paint) = r.centerY() - (p.descent() + p.ascent()) / 2f

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > max) end--
        return s.substring(0, end) + "…"
    }

    private fun findKey(x: Float, y: Float): Key? {
        val half = rowGap / 2
        for (row in rows) {
            val first = row.firstOrNull() ?: continue
            if (y < first.rect.top - half || y > first.rect.bottom + half) continue
            val real = row.filter { it.kind != Kind.SPACER }
            real.firstOrNull { x >= it.rect.left && x <= it.rect.right }?.let { return it }
            return real.minByOrNull { k ->
                when {
                    x < k.rect.left -> k.rect.left - x
                    x > k.rect.right -> x - k.rect.right
                    else -> 0f
                }
            }
        }
        return null
    }

    private fun findStrip(x: Float, y: Float): StripTarget? {
        val stripBottom = topPad + stripH + stripGap / 2
        if (y <= stripBottom) {
            return strip.firstOrNull { it.rect.centerY() < stripBottom && x >= it.rect.left && x <= it.rect.right }
        }
        if (glass && y >= barTop - rowGap / 2) {
            return strip.filter { it.rect.top >= barTop - dp(6f) }
                .minByOrNull { abs(it.rect.centerX() - x) }
                ?.takeIf { abs(it.rect.centerX() - x) <= dp(56f) }
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                for (other in pointers.values) {
                    val k = other.key
                    if (k != null && k.kind == Kind.CHAR && !other.longFired) {
                        cancelTimers(other)
                        pressedKeys.remove(k)
                        markUp(k)
                        commitChar(k)
                        other.key = null
                    }
                }
                val key = findKey(x, y)
                val target = if (key == null) findStrip(x, y) else null
                val p = Pointer(e.getPointerId(i), key, target, x)
                pointers[p.id] = p
                press(p)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = pointers[e.getPointerId(i)] ?: continue
                    val k = p.key ?: continue
                    if (k.kind == Kind.SPACE) {
                        val step = dp(14f)
                        val dx = e.getX(i) - p.anchorX
                        val steps = (dx / step).toInt()
                        if (steps != 0) {
                            if (!p.cursorMode) {
                                p.cursorMode = true
                                cancelTimers(p)
                            }
                            p.anchorX += steps * step
                            listener?.onCursorMove(steps)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val p = pointers.remove(e.getPointerId(e.actionIndex))
                if (p != null) release(p, commit = true)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (p in pointers.values) release(p, commit = false)
                pointers.clear()
            }
        }
        invalidate()
        return true
    }

    private fun press(p: Pointer) {
        val k = p.key
        val t = p.strip
        if (k == null && t == null) return
        feedback(k?.kind)
        if (t != null) {
            pressedStrip.add(t)
            return
        }
        if (k == null) return
        pressedKeys.add(k)
        markDown(k)
        when (k.kind) {
            Kind.DELETE -> {
                listener?.onDelete()
                val r = object : Runnable {
                    override fun run() {
                        listener?.onDelete()
                        uiHandler.postDelayed(this, 45)
                    }
                }
                p.repeat = r
                uiHandler.postDelayed(r, 380)
            }
            Kind.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.LOCKED -> Shift.OFF
                    shift == Shift.ON && now - lastShiftTap < 320 -> Shift.LOCKED
                    shift == Shift.ON -> Shift.OFF
                    else -> Shift.ON
                }
                lastShiftTap = now
            }
            Kind.CHAR -> {
                val alt = k.alt
                if (alt != null) {
                    val r = Runnable {
                        p.longFired = true
                        pressedKeys.remove(k)
                        markUp(k)
                        feedback(Kind.CHAR)
                        listener?.onText(alt)
                        invalidate()
                    }
                    p.longPress = r
                    uiHandler.postDelayed(r, 350)
                }
            }
            else -> Unit
        }
    }

    private fun release(p: Pointer, commit: Boolean) {
        cancelTimers(p)
        p.key?.let { pressedKeys.remove(it); markUp(it) }
        p.strip?.let { pressedStrip.remove(it) }
        if (!commit) return

        p.key?.let { k ->
            when (k.kind) {
                Kind.CHAR -> if (!p.longFired) commitChar(k)
                Kind.SPACE -> if (!p.cursorMode) listener?.onText(" ")
                Kind.ENTER -> listener?.onEnter()
                Kind.MODE -> switchMode(if (mode == Mode.LETTERS) Mode.SYMBOLS else Mode.LETTERS)
                Kind.PAGE -> switchMode(if (mode == Mode.SYMBOLS) Mode.SYMBOLS2 else Mode.SYMBOLS)
                Kind.ABC -> switchMode(Mode.LETTERS)
                Kind.NUMPAD -> openNumpad(phonePad)
                else -> Unit
            }
        }
        p.strip?.let { t ->
            when {
                t.word != null -> listener?.onSuggestion(t.word)
                t.emoji -> listener?.onToolbar(ToolbarAction.EMOJI)
                t.action != null -> listener?.onToolbar(t.action)
            }
        }
    }

    private fun commitChar(k: Key) {
        val upper = mode == Mode.LETTERS && shift != Shift.OFF
        val text = if (upper) k.label.uppercase() else k.label
        if (shift == Shift.ON) shift = Shift.OFF
        listener?.onText(text)
    }

    private fun cancelTimers(p: Pointer) {
        p.longPress?.let { uiHandler.removeCallbacks(it) }
        p.repeat?.let { uiHandler.removeCallbacks(it) }
        p.longPress = null
        p.repeat = null
    }

    private fun feedback(kind: Kind?) {
        if (prefs.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (prefs.sound) {
            val fx = when (kind) {
                Kind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Kind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                Kind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (p in pointers.values) cancelTimers(p)
        pointers.clear()
        pressedKeys.clear()
        pressedStrip.clear()
    }
}
