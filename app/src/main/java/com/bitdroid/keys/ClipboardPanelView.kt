package com.bitdroid.keys

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Interactive Clipboard Panel that opens when tapping the Clipboard button in the top toolbar.
 * Shows recent copied items, lets you tap any item to paste, pin items, or clear history.
 */
class ClipboardPanelView(context: Context) : View(context) {

    interface Listener {
        fun onClipCommit(text: String)
        fun onClipClose()
    }

    data class ClipEntry(val text: String, val pinned: Boolean)

    private class CardHit(
        val entry: ClipEntry,
        val cardRect: RectF,
        val pinRect: RectF,
        val delRect: RectF
    )

    var listener: Listener? = null

    var fixedHeight = 0
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }
    var bottomInset = 0

    private var palette = Themes.palette(context, KeyboardPrefs.load(context))
    private val store = Storage.prefs(context, KeyboardPrefs.NAME)

    private val entries = ArrayList<ClipEntry>()
    private val cardHits = ArrayList<CardHit>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val headerH = dp(48f)
    private val bottomBarH = dp(54f)
    private val pad = dp(10f)

    private val backRect = RectF()
    private val clearRect = RectF()

    private var scrollY = 0f
    private var contentH = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init {
        loadSavedClips()
    }

    fun setPalette(p: Palette) {
        palette = p
        invalidate()
    }

    fun syncFromSystemClipboard() {
        loadSavedClips()
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                for (i in 0 until clip.itemCount) {
                    val raw = clip.getItemAt(i).coerceToText(context)?.toString()?.trim() ?: ""
                    if (raw.isNotEmpty()) {
                        addClipInternal(raw)
                    }
                }
            }
        }
        rebuildCards()
        invalidate()
    }

    fun addClipInternal(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val existingIdx = entries.indexOfFirst { it.text == clean }
        val wasPinned = if (existingIdx >= 0) entries[existingIdx].pinned else false
        if (existingIdx >= 0) entries.removeAt(existingIdx)
        val insertIdx = if (wasPinned) 0 else entries.indexOfFirst { !it.pinned }.let { if (it < 0) entries.size else it }
        entries.add(insertIdx, ClipEntry(clean, wasPinned))
        while (entries.size > 25) entries.removeAt(entries.lastIndex)
        saveClips()
    }

    private fun loadSavedClips() {
        entries.clear()
        val raw = store.getString("clipboard_history_v1", "") ?: ""
        if (raw.isNotEmpty()) {
            raw.split("\u001E").forEach { record ->
                if (record.length > 2) {
                    val pinned = record.startsWith("1:")
                    val body = record.substring(2)
                    if (body.isNotEmpty()) entries.add(ClipEntry(body, pinned))
                }
            }
        }
    }

    private fun saveClips() {
        val serialized = entries.joinToString("\u001E") { "${if (it.pinned) "1" else "0"}:${it.text}" }
        store.edit().putString("clipboard_history_v1", serialized).apply()
    }

    private fun rebuildCards() {
        cardHits.clear()
        if (width == 0) return
        val w = width.toFloat()
        backRect.set(pad, dp(8f), pad + dp(82f), headerH - dp(4f))
        clearRect.set(w - pad - dp(112f), dp(8f), w - pad, headerH - dp(4f))

        var y = 0f
        val cardH = dp(56f)
        val gap = dp(8f)
        for (e in entries) {
            val r = RectF(pad, y, w - pad, y + cardH)
            val delR = RectF(r.right - dp(40f), r.top, r.right, r.bottom)
            val pinR = RectF(delR.left - dp(40f), r.top, delR.left, r.bottom)
            cardHits.add(CardHit(e, r, pinR, delR))
            y += cardH + gap
        }
        contentH = y + dp(12f)
        scrollY = scrollY.coerceIn(0f, maxScroll())
    }

    private fun listTop() = headerH + dp(4f)
    private fun listBottom() = height - bottomInset - dp(12f)
    private fun maxScroll() = max(0f, contentH - (listBottom() - listTop()))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (fixedHeight > 0) fixedHeight else dp(320f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCards()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        fill.color = palette.bg
        canvas.drawRoundRect(0f, 0f, w, height + dp(28f), dp(28f), dp(28f), fill)

        // Header Bar: ABC Back Button, Title, Clear Unpinned Button
        fill.color = palette.mod
        canvas.drawRoundRect(backRect, backRect.height() / 2f, backRect.height() / 2f, fill)
        textPaint.color = palette.onMod
        textPaint.textSize = sp(14f)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("ABC", backRect.centerX(), backRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        textPaint.color = palette.text
        textPaint.textSize = sp(15f)
        canvas.drawText("Clipboard (${entries.size})", w / 2f - dp(10f), backRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        if (entries.any { !it.pinned }) {
            fill.color = palette.strip
            canvas.drawRoundRect(clearRect, clearRect.height() / 2f, clearRect.height() / 2f, fill)
            textPaint.color = palette.sub
            textPaint.textSize = sp(12.5f)
            canvas.drawText("Clear all", clearRect.centerX(), clearRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        val top = listTop()
        val bottom = listBottom()

        canvas.save()
        canvas.clipRect(0f, top, w, bottom)

        if (entries.isEmpty()) {
            textPaint.color = palette.sub
            textPaint.textSize = sp(14f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Copied text will appear here — tap any clip to paste", w / 2f, (top + bottom) / 2f, textPaint)
        } else {
            for (hit in cardHits) {
                val cyTop = top + hit.cardRect.top - scrollY
                val cyBottom = top + hit.cardRect.bottom - scrollY
                if (cyBottom < top || cyTop > bottom) continue

                val drawR = RectF(hit.cardRect.left, cyTop, hit.cardRect.right, cyBottom)
                fill.color = if (hit.entry.pinned) palette.strip else palette.key
                canvas.drawRoundRect(drawR, dp(16f), dp(16f), fill)

                if (hit.entry.pinned) {
                    fill.color = palette.accent
                    canvas.drawRoundRect(drawR.left, drawR.top + dp(10f), drawR.left + dp(4f), drawR.bottom - dp(10f), dp(2f), dp(2f), fill)
                }

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = palette.text
                textPaint.textSize = sp(14f)
                val singleLine = hit.entry.text.replace('\n', ' ')
                val maxTextW = drawR.width() - dp(100f)
                var shown = singleLine
                while (shown.length > 4 && textPaint.measureText(shown) > maxTextW) {
                    shown = shown.dropLast(2) + "…"
                }
                canvas.drawText(shown, drawR.left + dp(14f), drawR.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

                // Pin & Delete actions
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = sp(14f)
                textPaint.color = if (hit.entry.pinned) palette.accent else palette.sub
                canvas.drawText("📌", hit.pinRect.centerX(), drawR.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

                textPaint.color = palette.sub
                canvas.drawText("✕", hit.delRect.centerX(), drawR.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = y
                lastY = y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(y - downY) > dp(8f)) dragging = true
                if (dragging) {
                    scrollY = (scrollY + (lastY - y)).coerceIn(0f, maxScroll())
                    lastY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    if (backRect.contains(x, y)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onClipClose()
                        return true
                    }
                    if (clearRect.contains(x, y) && entries.any { !it.pinned }) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        entries.removeAll { !it.pinned }
                        saveClips()
                        rebuildCards()
                        invalidate()
                        return true
                    }
                    if (y >= listTop() && y <= listBottom()) {
                        val contentY = y - listTop() + scrollY
                        val hit = cardHits.firstOrNull { contentY >= it.cardRect.top && contentY <= it.cardRect.bottom }
                        if (hit != null) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            when {
                                x >= hit.delRect.left -> {
                                    entries.remove(hit.entry)
                                    saveClips()
                                    rebuildCards()
                                    invalidate()
                                }
                                x >= hit.pinRect.left -> {
                                    val idx = entries.indexOf(hit.entry)
                                    if (idx >= 0) {
                                        entries[idx] = hit.entry.copy(pinned = !hit.entry.pinned)
                                        entries.sortByDescending { it.pinned }
                                        saveClips()
                                        rebuildCards()
                                        invalidate()
                                    }
                                }
                                else -> {
                                    listener?.onClipCommit(hit.entry.text)
                                }
                            }
                        }
                    }
                }
                dragging = false
            }
        }
        return true
    }
}
