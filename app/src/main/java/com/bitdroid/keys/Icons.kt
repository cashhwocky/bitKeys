package com.bitdroid.keys

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Stroke icons drawn on a 24×24 grid, scaled to any size. No bitmap assets needed. */
enum class Icon { SHIFT, SHIFT_LOCK, DELETE, ARROW, NEWLINE, SEARCH, DONE, MIC, EMOJI, CLIPBOARD, SETTINGS, KEYBOARD, PAUSE }

object Icons {

    private val paths = HashMap<Icon, Path>()
    private val fillPaths = HashMap<Icon, Path>()

    private fun build(icon: Icon): Path = Path().apply {
        when (icon) {
            Icon.SHIFT, Icon.SHIFT_LOCK -> {
                moveTo(12f, 3.5f); lineTo(20f, 11.7f); lineTo(15.8f, 11.7f); lineTo(15.8f, 18.5f)
                lineTo(8.2f, 18.5f); lineTo(8.2f, 11.7f); lineTo(4f, 11.7f); close()
                if (icon == Icon.SHIFT_LOCK) { moveTo(8.2f, 21.5f); lineTo(15.8f, 21.5f) }
            }
            Icon.DELETE -> {
                moveTo(9.2f, 5.5f); lineTo(21f, 5.5f); lineTo(21f, 18.5f); lineTo(9.2f, 18.5f); lineTo(3.5f, 12f); close()
                moveTo(11.8f, 9.3f); lineTo(16.8f, 14.7f)
                moveTo(16.8f, 9.3f); lineTo(11.8f, 14.7f)
            }
            Icon.ARROW -> {
                moveTo(4.5f, 12f); lineTo(19.5f, 12f)
                moveTo(13.5f, 6f); lineTo(19.5f, 12f); lineTo(13.5f, 18f)
            }
            Icon.NEWLINE -> {
                moveTo(19f, 5f); lineTo(19f, 12f); quadTo(19f, 14.5f, 16.5f, 14.5f); lineTo(5f, 14.5f)
                moveTo(9f, 10.5f); lineTo(5f, 14.5f); lineTo(9f, 18.5f)
            }
            Icon.SEARCH -> {
                addCircle(10.5f, 10.5f, 6f, Path.Direction.CW)
                moveTo(15f, 15f); lineTo(20f, 20f)
            }
            Icon.DONE -> {
                moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19.5f, 7f)
            }
            Icon.MIC -> {
                addRoundRect(RectF(9f, 3f, 15f, 14.5f), 3f, 3f, Path.Direction.CW)
                arcTo(RectF(5.5f, 4.5f, 18.5f, 17.5f), 180f, -180f, true)
                moveTo(12f, 17.5f); lineTo(12f, 21f)
            }
            Icon.EMOJI -> {
                addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                arcTo(RectF(8.3f, 9.8f, 15.7f, 16.4f), 20f, 140f, true)
                addCircle(9.2f, 9.6f, 0.4f, Path.Direction.CW)
                addCircle(14.8f, 9.6f, 0.4f, Path.Direction.CW)
            }
            Icon.CLIPBOARD -> {
                addRoundRect(RectF(5.5f, 4.5f, 18.5f, 20.5f), 2.5f, 2.5f, Path.Direction.CW)
                addRoundRect(RectF(9f, 3f, 15f, 6.5f), 1.2f, 1.2f, Path.Direction.CW)
            }
            Icon.SETTINGS -> {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17f); lineTo(20f, 17f)
            }
            Icon.KEYBOARD -> {
                addRoundRect(RectF(2.5f, 5.5f, 21.5f, 18.5f), 3f, 3f, Path.Direction.CW)
                moveTo(7.5f, 14.5f); lineTo(16.5f, 14.5f)
                for (x in listOf(6f, 9.3f, 12.6f, 15.9f, 18.2f)) { addCircle(x, 9.7f, 0.35f, Path.Direction.CW) }
            }
            Icon.PAUSE -> {
                addRoundRect(RectF(6.5f, 5f, 10.5f, 19f), 1.6f, 1.6f, Path.Direction.CW)
                addRoundRect(RectF(13.5f, 5f, 17.5f, 19f), 1.6f, 1.6f, Path.Direction.CW)
            }
        }
    }

    /** Extra filled shapes for some icons (slider knobs, mic capsule). */
    private fun buildFill(icon: Icon): Path? = when (icon) {
        Icon.SETTINGS -> Path().apply {
            addCircle(9f, 7f, 2.2f, Path.Direction.CW)
            addCircle(15f, 12f, 2.2f, Path.Direction.CW)
            addCircle(8f, 17f, 2.2f, Path.Direction.CW)
        }
        else -> null
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Draws [icon] centered at (cx, cy), [size] px wide.
     * [filled] fills the outline (used for active shift and pause).
     */
    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, filled: Boolean = false, weight: Float = 1.8f) {
        val path = paths.getOrPut(icon) { build(icon) }
        val s = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(s, s)
        if (filled || icon == Icon.PAUSE) {
            fill.color = color
            canvas.drawPath(path, fill)
        }
        stroke.color = color
        stroke.strokeWidth = weight
        canvas.drawPath(path, stroke)
        val extra = fillPaths[icon] ?: buildFill(icon)?.also { fillPaths[icon] = it }
        if (extra != null) {
            fill.color = color
            canvas.drawPath(extra, fill)
        }
        canvas.restore()
    }
}
