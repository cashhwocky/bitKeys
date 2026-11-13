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

    enum class ToolbarAction { EMOJI, CLIPBOARD, SETTINGS, MIC }
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
    private val bottomPad = dp(22f) // breathing room above the system back / IME-switcher row
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
            insets
        }
        buildRows()
    }

    // ---------------------------------------------------------------- public API

    fun applyPrefs(p: KeyboardPrefs) {
        prefs = p
        palette = Themes.palette(context, p)
        buildRows()
        requestLayout()
        invalidate()
    }

    fun palette(): Palette = palette

    /** Phone-number fields get * # + on the number pad instead of - , . */
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

    // ---------------------------------------------------------------- layout

    private fun chars(s: String, alts: String? = null): List<Key> =
        s.mapIndexed { i, ch -> Key(Kind.CHAR, ch.toString(), 1f, alts?.getOrNull(i)?.toString()) }

    private fun bottomRow(modeLabel: String, numpadKey: Boolean = false): List<Key> = listOfNotNull(
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
        val h = topPad + stripH + stripGap + rows.size * keyH + (rows.size - 1) * rowGap + bottomPad + navInset
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
            val avail = width - 2 * sidePad - (row.size - 1) * keyGap
            val unit = avail / totalWeight
            var x = sidePad
            for (k in row) {
                val w = k.weight * unit
                k.rect.set(x, y, x + w, y + keyH)
                x += w + keyGap
            }
            y += keyH + rowGap
        }
    }

    private val stripPill = RectF()
    private var slotLeft = 0f
    private var slotW = 0f

    private fun layoutStrip() {
        strip.clear()
        if (width == 0) return
        stripPill.set(sidePad, topPad, width - sidePad, topPad + stripH)
        val cy = stripPill.centerY()
        val mic = dp(42f)
        val micRight = stripPill.right - dp(5f)
        strip.add(StripTarget(RectF(micRight - mic, cy - mic / 2, micRight, cy + mic / 2), action = ToolbarAction.MIC))

        if (slots.isEmpty()) {
            val tool = dp(40f)
            val icons = listOf<Pair<ToolbarAction?, Boolean>>(
                null to true, // emoji panel
                ToolbarAction.CLIPBOARD to false,
                ToolbarAction.SETTINGS to false,
            )
            icons.forEachIndexed { i, (action, isEmoji) ->
                val left = stripPill.left + dp(6f) + i * (tool + dp(6f))
                strip.add(StripTarget(RectF(left, cy - tool / 2, left + tool, cy + tool / 2), action = action, emoji = isEmoji))
            }
        } else {
            slotLeft = stripPill.left + dp(8f)
            val right = micRight - mic - dp(6f)
            slotW = (right - slotLeft) / 3f
            slots.forEachIndexed { i, word ->
                if (word.isNotEmpty()) {
                    val l = slotLeft + i * slotW
                    strip.add(StripTarget(RectF(l, cy - dp(20f), l + slotW, cy + dp(20f)), word = word, slot = i))
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private val sheetRadius = dp(28f)
    private val wavePath = Path()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        // Sheet with soft top corners, like the home-screen widgets.
        fillPaint.color = palette.bg
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height + sheetRadius, sheetRadius, sheetRadius, fillPaint)
        drawStrip(canvas)
        drawKeys(canvas)
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
                t.action == ToolbarAction.MIC -> {
                    fillPaint.color = palette.micBg
                    canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2 * if (pressed) 0.92f else 1f, fillPaint)
                    Icons.draw(canvas, Icon.MIC, r.centerX(), r.centerY(), dp(22f), palette.micFg, weight = 2f)
                }
                else -> {
                    // toolbar squircles, like the app icons
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

            if (k.kind == Kind.ENTER) {
                // Scalloped "cookie" Enter, like the date widget.
                val radius = min(tmp.height() / 2f + dp(4f), tmp.width() / 2f) * if (pressed) 0.9f else 1f
                fillPaint.color = palette.accent
                Shapes.drawCookie(canvas, cx, cy, radius, fillPaint)
                val icon = when (enterStyle) {
                    EnterStyle.SEARCH -> Icon.SEARCH
                    EnterStyle.DONE -> Icon.DONE
                    EnterStyle.ENTER -> Icon.NEWLINE
                    else -> Icon.ARROW
                }
                Icons.draw(canvas, icon, cx, cy, dp(23f), palette.onAccent, weight = 2.4f)
                continue
            }

            if (pressed) tmp.inset(dp(1.5f), dp(1.5f))
            val isMod = k.mod || k.kind == Kind.SHIFT || k.kind == Kind.DELETE
            val bg = when {
                pressed && isMod -> blend(palette.mod, palette.onMod, 0.18f)
                pressed -> palette.pressed
                isMod -> palette.mod
                else -> palette.key
            }
            val wide = k.kind == Kind.SPACE || k.kind == Kind.MODE || k.kind == Kind.ABC || k.kind == Kind.NUMPAD
            val r = if (wide && !square) tmp.height() / 2 else min(baseRadius, min(tmp.width(), tmp.height()) / 2)

            fillPaint.color = bg
            canvas.drawRoundRect(tmp, r, r, fillPaint)
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
                Kind.SPACE -> {
                    // The wavy line from the volume slider.
                    val inset = tmp.width() * 0.09f
                    Shapes.squiggle(wavePath, tmp.left + inset, tmp.right - inset, cy, dp(4f), 4f)
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

    // ---------------------------------------------------------------- touch

    private fun findKey(x: Float, y: Float): Key? {
        val half = rowGap / 2
        for (row in rows) {
            val first = row.firstOrNull() ?: continue
            if (y < first.rect.top - half || y > first.rect.bottom + half) continue
            // The key whose bounds contain x wins; gaps and spacers go to the key whose
            // nearest EDGE is closest (not center, or wide keys like space lose their ends).
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
        if (y > topPad + stripH + stripGap / 2) return null
        return strip.firstOrNull { x >= it.rect.left && x <= it.rect.right }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                // rollover: a new finger commits any letter still held by another finger
                for (other in pointers.values) {
                    val k = other.key
                    if (k != null && k.kind == Kind.CHAR && !other.longFired) {
                        cancelTimers(other)
                        pressedKeys.remove(k)
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
        p.key?.let { pressedKeys.remove(it) }
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
