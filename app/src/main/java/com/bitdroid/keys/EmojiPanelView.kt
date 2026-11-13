package com.bitdroid.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Full emoji keyboard: every Unicode emoji the phone's font can draw, grouped by category,
 * with recents, fling scrolling, category tabs and skin tones on long-press.
 */
class EmojiPanelView(context: Context) : View(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onEmojiDelete()
        fun onEmojiSpace()
        fun onEmojiAbc()
    }

    class Item(val base: String, val variants: List<String>)
    private class Category(val name: String, val icon: String, val items: List<Item>)

    private sealed class Line(val top: Float, val height: Float, val cat: Int) {
        class Header(top: Float, height: Float, cat: Int, val title: String) : Line(top, height, cat)
        class Cells(top: Float, height: Float, cat: Int, val items: List<Item>) : Line(top, height, cat)
    }

    private class Popup(val item: Item, val options: List<String>, val rects: List<RectF>, val box: RectF)

    var listener: Listener? = null

    var fixedHeight = 0
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }
    var bottomInset = 0

    private var palette = Themes.palette(context, KeyboardPrefs.load(context))
    private var shape = "round"

    private val store = Storage.prefs(context, KeyboardPrefs.NAME)
    private val recents = ArrayList<String>()
    private val tones = HashMap<String, String>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val tabsH = dp(46f)
    private val barH = dp(58f)
    private val cellH = dp(48f)
    private val headerH = dp(34f)
    private val pad = dp(6f)

    private var categories: List<Category> = emptyList()
    private var lines: List<Line> = emptyList()
    private var contentH = 0f
    private var scrollPos = 0f
    private var cols = 8
    private var cellW = 0f
    private val tabRects = ArrayList<RectF>()
    private val abcBtn = RectF()
    private val spaceBtn = RectF()
    private val deleteBtn = RectF()

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // touch state
    private enum class Zone { NONE, GRID, TAB, BAR, POPUP }
    private var zone = Zone.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedItem: Item? = null
    private var pressedRect: RectF? = null
    private var pressedBtn: RectF? = null
    private var longFired = false
    private var popup: Popup? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val longPress = Runnable { onLongPress() }
    private val repeatDelete = object : Runnable {
        override fun run() {
            listener?.onEmojiDelete()
            uiHandler.postDelayed(this, 45)
        }
    }

    init {
        store.getString("recent_emoji", "")?.split('\n')?.filter { it.isNotEmpty() }?.let { recents.addAll(it) }
        store.getString("emoji_tones", "")?.split('\n')?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) tones[parts[0]] = parts[1]
        }
    }

    // ------------------------------------------------------------ public

    fun setPalette(p: Palette, keyShape: String) {
        palette = p
        shape = keyShape
        invalidate()
    }

    /** Call each time the panel is shown: refreshes recents and makes sure data is loaded. */
    fun onOpen() {
        popup = null
        val data = cache
        if (data == null) {
            load(context.applicationContext) { post { rebuild() } }
        }
        rebuild()
    }

    // ------------------------------------------------------------ data

    private fun rebuild() {
        val data = cache ?: run { lines = emptyList(); invalidate(); return }
        val cats = ArrayList<Category>()
        if (recents.isNotEmpty()) {
            cats.add(Category("Recently used", "🕘", recents.map { r -> Item(r, emptyList()) }))
        }
        for ((name, items) in data) cats.add(Category(name, ICONS[name] ?: items.first().base, items))
        categories = cats
        layoutLines()
        invalidate()
    }

    private fun layoutLines() {
        if (width == 0) return
        cols = max(6, ((width - 2 * pad) / dp(46f)).toInt())
        cellW = (width - 2 * pad) / cols
        val out = ArrayList<Line>()
        var y = 0f
        categories.forEachIndexed { ci, cat ->
            out.add(Line.Header(y, headerH, ci, cat.name))
            y += headerH
            for (chunk in cat.items.chunked(cols)) {
                out.add(Line.Cells(y, cellH, ci, chunk))
                y += cellH
            }
        }
        lines = out
        contentH = y + dp(8f)
        scrollPos = scrollPos.coerceIn(0f, maxScroll())
        layoutChrome()
    }

    private fun layoutChrome() {
        tabRects.clear()
        if (categories.isNotEmpty()) {
            val w = (width - 2 * pad) / categories.size
            categories.indices.forEach { i ->
                val l = pad + i * w
                tabRects.add(RectF(l, dp(4f), l + w, tabsH - dp(4f)))
            }
        }
        val top = height - bottomInset - barH + dp(4f)
        val bottom = top + dp(50f)
        val unit = (width - 2 * pad - 2 * dp(6f)) / 8f
        abcBtn.set(pad, top, pad + unit * 1.5f, bottom)
        spaceBtn.set(abcBtn.right + dp(6f), top, abcBtn.right + dp(6f) + unit * 5f, bottom)
        deleteBtn.set(spaceBtn.right + dp(6f), top, width - pad, bottom)
    }

    private fun gridTop() = tabsH
    private fun gridBottom() = height - bottomInset - barH
    private fun maxScroll() = max(0f, contentH - (gridBottom() - gridTop()))

    private fun saveRecents() {
        store.edit().putString("recent_emoji", recents.joinToString("\n")).apply()
    }

    private fun commit(item: Item, choice: String? = null) {
        val out = choice ?: tones[item.base] ?: item.base
        if (choice != null && item.variants.isNotEmpty()) {
            tones[item.base] = choice
            store.edit().putString("emoji_tones", tones.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
        }
        listener?.onEmoji(out)
        recents.remove(out)
        recents.add(0, out)
        while (recents.size > 32) recents.removeAt(recents.size - 1)
        saveRecents()
    }

    // ------------------------------------------------------------ measure / draw

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (fixedHeight > 0) fixedHeight else dp(320f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutLines()
        layoutChrome()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    private fun activeCategory(): Int {
        var cat = 0
        for (l in lines) {
            if (l.top <= scrollPos + dp(2f)) cat = l.cat else break
        }
        return cat
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = palette.bg
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height + dp(28f), dp(28f), dp(28f), fill)
        val top = gridTop()
        val bottom = gridBottom()

        // grid
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        if (lines.isEmpty()) {
            textPaint.color = palette.sub
            textPaint.textSize = sp(14f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Loading emoji…", width / 2f, (top + bottom) / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        emojiPaint.textSize = sp(26f)
        val emojiOffset = -(emojiPaint.descent() + emojiPaint.ascent()) / 2f
        for (l in lines) {
            val y = top + l.top - scrollPos
            if (y + l.height < top) continue
            if (y > bottom) break
            when (l) {
                is Line.Header -> {
                    textPaint.color = palette.sub
                    textPaint.textSize = sp(13f)
                    canvas.drawText(l.title, pad + dp(8f), y + l.height - dp(10f), textPaint)
                }
                is Line.Cells -> l.items.forEachIndexed { i, item ->
                    val cx = pad + cellW * i + cellW / 2f
                    val cy = y + cellH / 2f
                    if (item === pressedItem && !dragging) {
                        fill.color = palette.pressed
                        canvas.drawRoundRect(cx - cellW / 2f + dp(2f), y + dp(2f), cx + cellW / 2f - dp(2f), y + cellH - dp(2f), dp(14f), dp(14f), fill)
                    }
                    val shown = tones[item.base] ?: item.base
                    canvas.drawText(shown, cx, cy + emojiOffset, emojiPaint)
                    if (item.variants.isNotEmpty()) {
                        // tiny corner mark: long-press for skin tones
                        fill.color = palette.sub
                        canvas.drawCircle(cx + cellW / 2f - dp(7f), y + cellH - dp(7f), dp(1.6f), fill)
                    }
                }
            }
        }
        canvas.restore()

        // tabs
        val active = activeCategory()
        emojiPaint.textSize = sp(19f)
        val tabOffset = -(emojiPaint.descent() + emojiPaint.ascent()) / 2f
        tabRects.forEachIndexed { i, r ->
            if (i == active) {
                fill.color = palette.key
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                fill.color = palette.accent
                canvas.drawRoundRect(r.centerX() - dp(8f), r.bottom - dp(4f), r.centerX() + dp(8f), r.bottom - dp(1.5f), dp(2f), dp(2f), fill)
            }
            emojiPaint.alpha = if (i == active) 255 else 150
            canvas.drawText(categories[i].icon, r.centerX(), r.centerY() + tabOffset, emojiPaint)
        }
        emojiPaint.alpha = 255

        // bottom bar
        val square = shape == "square"
        drawButton(canvas, abcBtn, palette.mod, if (square) dp(6f) else abcBtn.height() / 2)
        textPaint.color = palette.onMod
        textPaint.textSize = sp(15f)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("ABC", abcBtn.centerX(), abcBtn.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        drawButton(canvas, spaceBtn, palette.key, if (square) dp(6f) else spaceBtn.height() / 2)
        textPaint.color = palette.sub
        textPaint.textSize = sp(13f)
        canvas.drawText("space", spaceBtn.centerX(), spaceBtn.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        drawButton(canvas, deleteBtn, palette.mod, min(dp(Themes.keyRadiusDp(shape)), deleteBtn.height() / 2))
        Icons.draw(canvas, Icon.DELETE, deleteBtn.centerX(), deleteBtn.centerY(), dp(24f), palette.onMod)

        // skin-tone popup
        popup?.let { p ->
            fill.color = palette.mod
            canvas.drawRoundRect(p.box, dp(18f), dp(18f), fill)
            emojiPaint.textSize = sp(26f)
            p.options.forEachIndexed { i, opt ->
                val r = p.rects[i]
                canvas.drawText(opt, r.centerX(), r.centerY() + emojiOffset, emojiPaint)
            }
        }
    }

    private fun drawButton(canvas: Canvas, r: RectF, color: Int, radius: Float) {
        fill.color = if (pressedBtn === r) palette.pressed else color
        canvas.drawRoundRect(r, radius, radius, fill)
    }

    // ------------------------------------------------------------ touch

    private fun cellAt(x: Float, y: Float): Pair<Item, RectF>? {
        val cy = y - gridTop() + scrollPos
        val line = lines.firstOrNull { cy >= it.top && cy < it.top + it.height } as? Line.Cells ?: return null
        val col = ((x - pad) / cellW).toInt()
        val item = line.items.getOrNull(col) ?: return null
        val top = gridTop() + line.top - scrollPos
        val left = pad + col * cellW
        return item to RectF(left, top, left + cellW, top + cellH)
    }

    private fun onLongPress() {
        val item = pressedItem ?: return
        val anchor = pressedRect ?: return
        if (item.variants.isEmpty()) return
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val options = listOf(item.base) + item.variants
        val size = dp(46f)
        val perRow = min(options.size, 6)
        val rows = ceil(options.size / perRow.toFloat()).toInt()
        val boxW = perRow * size + dp(12f)
        val boxH = rows * size + dp(12f)
        val left = (anchor.centerX() - boxW / 2f).coerceIn(pad, width - pad - boxW)
        var top = anchor.top - boxH - dp(6f)
        if (top < 0f) top = (anchor.bottom + dp(6f)).coerceAtMost(height - boxH)
        val box = RectF(left, top, left + boxW, top + boxH)
        val rects = options.indices.map { i ->
            val r = i / perRow
            val c = i % perRow
            val l = box.left + dp(6f) + c * size
            val t = box.top + dp(6f) + r * size
            RectF(l, t, l + size, t + size)
        }
        popup = Popup(item, options, rects, box)
        pressedItem = null
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastY = y
                dragging = false
                longFired = false
                val p = popup
                if (p != null) {
                    zone = Zone.POPUP
                    return true
                }
                when {
                    y < gridTop() -> {
                        zone = Zone.TAB
                    }
                    y > gridBottom() -> {
                        zone = Zone.BAR
                        pressedBtn = listOf(abcBtn, spaceBtn, deleteBtn).firstOrNull { it.contains(x, y) }
                        if (pressedBtn != null) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (pressedBtn === deleteBtn) {
                            listener?.onEmojiDelete()
                            uiHandler.postDelayed(repeatDelete, 380)
                        }
                    }
                    else -> {
                        zone = Zone.GRID
                        scroller.forceFinished(true)
                        velocity?.recycle()
                        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                        val hit = cellAt(x, y)
                        pressedItem = hit?.first
                        pressedRect = hit?.second
                        if (hit != null) uiHandler.postDelayed(longPress, 380)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (zone == Zone.GRID) {
                velocity?.addMovement(e)
                if (!dragging && abs(y - downY) > slop) {
                    dragging = true
                    uiHandler.removeCallbacks(longPress)
                    pressedItem = null
                }
                if (dragging) {
                    scrollPos = (scrollPos + (lastY - y)).coerceIn(0f, maxScroll())
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(longPress)
                uiHandler.removeCallbacks(repeatDelete)
                when (zone) {
                    Zone.POPUP -> {
                        val p = popup
                        if (p != null) {
                            val i = p.rects.indexOfFirst { it.contains(x, y) }
                            if (i >= 0) {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                commit(p.item, p.options[i])
                            }
                        }
                        popup = null
                    }
                    Zone.GRID -> {
                        val vt = velocity
                        if (dragging && vt != null) {
                            vt.addMovement(e)
                            vt.computeCurrentVelocity(1000)
                            scroller.fling(0, scrollPos.toInt(), 0, -vt.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                            postInvalidateOnAnimation()
                        } else if (!longFired) {
                            pressedItem?.let {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                commit(it)
                            }
                        }
                        velocity?.recycle()
                        velocity = null
                    }
                    Zone.TAB -> {
                        val i = tabRects.indexOfFirst { it.contains(x, y) }
                        val header = lines.firstOrNull { it is Line.Header && it.cat == i }
                        if (i >= 0 && header != null) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            scroller.forceFinished(true)
                            scrollPos = header.top.coerceIn(0f, maxScroll())
                        }
                    }
                    Zone.BAR -> {
                        val b = pressedBtn
                        if (b != null && b.contains(x, y)) {
                            when {
                                b === abcBtn -> listener?.onEmojiAbc()
                                b === spaceBtn -> listener?.onEmojiSpace()
                            }
                        }
                    }
                    Zone.NONE -> Unit
                }
                pressedItem = null
                pressedBtn = null
                zone = Zone.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longPress)
                uiHandler.removeCallbacks(repeatDelete)
                pressedItem = null
                pressedBtn = null
                velocity?.recycle()
                velocity = null
                zone = Zone.NONE
            }
        }
        invalidate()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacks(longPress)
        uiHandler.removeCallbacks(repeatDelete)
    }

    companion object {
        private val ICONS = mapOf(
            "Smileys & Emotion" to "😀",
            "People & Body" to "👋",
            "Animals & Nature" to "🐻",
            "Food & Drink" to "🍔",
            "Travel & Places" to "🚗",
            "Activities" to "⚽",
            "Objects" to "💡",
            "Symbols" to "🔣",
            "Flags" to "🏁",
        )

        @Volatile
        private var cache: List<Pair<String, List<Item>>>? = null
        @Volatile
        private var loading = false

        /** Reads assets/emoji.txt once, keeping only emoji the installed font can draw. */
        private fun load(context: Context, done: () -> Unit) {
            if (cache != null) { done(); return }
            if (loading) return
            loading = true
            Thread {
                val paint = Paint()
                val result = ArrayList<Pair<String, List<Item>>>()
                var name: String? = null
                var items = ArrayList<Item>()
                context.assets.open("emoji.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                    for (line in seq) {
                        if (line.isEmpty()) continue
                        if (line.startsWith("@")) {
                            name?.let { if (items.isNotEmpty()) result.add(it to items) }
                            name = line.substring(1)
                            items = ArrayList()
                            continue
                        }
                        val parts = line.split('\t')
                        val base = parts[0]
                        if (!paint.hasGlyph(base)) continue
                        val variants = parts.drop(1).filter { paint.hasGlyph(it) }
                        items.add(Item(base, variants))
                    }
                }
                name?.let { if (items.isNotEmpty()) result.add(it to items) }
                cache = result
                loading = false
                done()
            }.start()
        }
    }
}
