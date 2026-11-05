package app.stickerdock.android.create

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.stickerdock.core.StickerRules
import kotlin.math.max

/**
 * Bold white caption with a dark outline, auto-sized to ≤3 lines, at the top or bottom of a 512² sticker.
 * Layouts are built once, so drawing it on every frame of an animation is cheap.
 */
class CaptionLayer(caption: String, private val atTop: Boolean = false, scale: Float = 1f) {
    private val text = caption.trim().take(80)
    private val width = (StickerRules.PIXELS - 2 * MARGIN).toInt()
    private val fill: StaticLayout
    private val stroke: StaticLayout

    init {
        var size = 64f * scale.coerceIn(0.6f, 1.6f)
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = size
        }
        var layout = build(fillPaint)
        while ((layout.height > StickerRules.PIXELS * 0.3f || (0 until layout.lineCount).any { layout.getLineWidth(it) > width }) && size > 26f) {
            size -= 4f; fillPaint.textSize = size; layout = build(fillPaint)
        }
        fill = layout
        stroke = build(TextPaint(fillPaint).apply {
            style = Paint.Style.STROKE; strokeWidth = max(6f, size / 7f); color = Color.parseColor("#12130F")
            strokeJoin = Paint.Join.ROUND
        })
    }

    val height: Int get() = fill.height

    fun draw(canvas: Canvas) {
        if (text.isEmpty()) return
        val top = if (atTop) MARGIN else StickerRules.PIXELS - MARGIN - fill.height
        canvas.save()
        canvas.translate(MARGIN, top)
        stroke.draw(canvas)
        fill.draw(canvas)
        canvas.restore()
    }

    private fun build(paint: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 0.92f).setMaxLines(3).build()

    companion object { const val MARGIN = 18f }
}
