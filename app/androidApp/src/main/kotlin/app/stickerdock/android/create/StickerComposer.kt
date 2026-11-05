package app.stickerdock.android.create

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.stickerdock.core.StickerRules
import kotlin.math.max
import kotlin.math.min

/** What the user chose in the creator; rendering is deterministic so preview == saved sticker. */
data class CreateRecipe(
    val caption: String = "",
    val captionAtTop: Boolean = false,
    val captionScale: Float = 1f,
    val outline: Boolean = true,
    val zoom: Float = 1f,
    val offsetX: Float = 0f, // fraction of canvas, -0.5..0.5
    val offsetY: Float = 0f,
)

/**
 * Draws a 512² sticker: the (optionally cut-out) subject fitted on a transparent canvas, a white sticker
 * outline traced from its alpha, and a bold white caption with a dark outline — the classic WhatsApp look.
 */
object StickerComposer {
    private const val SIZE = StickerRules.PIXELS
    private const val MARGIN = 18f

    fun render(subject: Bitmap, recipe: CreateRecipe): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val captionSpace = if (recipe.caption.isBlank()) 0f else SIZE * 0.24f * recipe.captionScale.coerceIn(0.6f, 1.6f)
        val box = RectF(MARGIN, MARGIN + if (recipe.captionAtTop) captionSpace else 0f,
            SIZE - MARGIN, SIZE - MARGIN - if (recipe.captionAtTop) 0f else captionSpace)
        val fit = min(box.width() / subject.width, box.height() / subject.height) * recipe.zoom.coerceIn(0.5f, 3f)
        val w = subject.width * fit; val h = subject.height * fit
        val cx = box.centerX() + recipe.offsetX * SIZE; val cy = box.centerY() + recipe.offsetY * SIZE
        val dst = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (recipe.outline && subject.hasAlpha()) drawOutline(canvas, subject, dst)
        canvas.drawBitmap(subject, null, dst, paint)
        if (recipe.caption.isNotBlank()) CaptionLayer(recipe.caption, recipe.captionAtTop, recipe.captionScale).draw(canvas)
        return out
    }

    /** White border: the subject's alpha, blurred and tinted, drawn slightly larger underneath. */
    private fun drawOutline(canvas: Canvas, subject: Bitmap, dst: RectF) {
        val alpha = subject.extractAlpha()
        try {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.SOLID)
            }
            val grow = 7f
            for (dx in floatArrayOf(-grow, 0f, grow)) for (dy in floatArrayOf(-grow, 0f, grow)) {
                canvas.drawBitmap(alpha, null, RectF(dst.left + dx, dst.top + dy, dst.right + dx, dst.bottom + dy), stroke)
            }
        } finally {
            alpha.recycle()
        }
    }

}
