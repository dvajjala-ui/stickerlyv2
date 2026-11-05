package app.stickerdock.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import app.stickerdock.core.AnimatedWebpMuxer
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Webp
import com.facebook.animated.gif.GifImage
import com.facebook.animated.webp.WebPImage
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo
import com.facebook.imagepipeline.animated.base.AnimatedImage
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.soloader.nativeloader.NativeLoader
import com.facebook.soloader.nativeloader.SystemDelegate
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Re-encodes animated WebP/GIF into a WhatsApp animated sticker: frames are decoded and composited
 * (blend + disposal) by Fresco's native decoders, scaled to fit a transparent 512² canvas, encoded with
 * Android's WebP encoder and muxed with their real durations. Frame count and quality step down until
 * the file fits 500 KB. Used when the container-level fix can't help (canvas > 512, delta frames, GIF).
 */
object AnimatedReencoder {
    private const val MAX_FRAMES = 48
    private val QUALITIES = intArrayOf(80, 65, 50, 38, 28)

    private val nativeReady by lazy {
        if (!NativeLoader.isInitialized()) NativeLoader.init(SystemDelegate())
        true
    }

    fun reencode(input: ByteArray, maxBytes: Int = StickerRules.ANIMATED_MAX_BYTES): ByteArray? = runCatching {
        check(nativeReady)
        val image: AnimatedImage = when {
            Webp.isWebp(input) -> WebPImage.createFromByteArray(input, ImageDecodeOptions.defaults())
            StickerNormalizer.isGif(input) -> GifImage.createFromByteArray(input)
            else -> return null
        }
        try {
            if (image.frameCount < 2) return null
            var step = max(1, ceil(image.frameCount / MAX_FRAMES.toDouble()).toInt())
            while (true) {
                for (quality in QUALITIES) {
                    val out = attempt(image, step, quality) ?: continue
                    if (out.size <= maxBytes && StickerRules.problem(StickerKind.ANIMATED, Webp.inspect(out), out.size) == null) return out
                }
                if (image.frameCount / step < 4) return null
                step *= 2
            }
            @Suppress("UNREACHABLE_CODE") null
        } finally {
            image.dispose()
        }
    }.getOrNull()

    /** One full decode → composite → scale → encode pass keeping every [step]-th frame (time merged). */
    private fun attempt(image: AnimatedImage, step: Int, quality: Int): ByteArray? {
        val width = image.width; val height = image.height
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val out512 = Bitmap.createBitmap(StickerRules.PIXELS, StickerRules.PIXELS, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out512)
        val scale = min(StickerRules.PIXELS / width.toFloat(), StickerRules.PIXELS / height.toFloat())
        val dst = RectF((StickerRules.PIXELS - width * scale) / 2, (StickerRules.PIXELS - height * scale) / 2,
            (StickerRules.PIXELS + width * scale) / 2, (StickerRules.PIXELS + height * scale) / 2)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val durations = image.frameDurations
        val frames = mutableListOf<ByteArray>()
        val frameTimes = mutableListOf<Int>()
        var elapsed = 0
        var previous: Bitmap? = null
        try {
            for (i in 0 until image.frameCount) {
                val info = image.getFrameInfo(i)
                val rect = Rect(info.xOffset, info.yOffset, info.xOffset + info.width, info.yOffset + info.height)
                if (info.disposalMethod == AnimatedDrawableFrameInfo.DisposalMethod.DISPOSE_TO_PREVIOUS) {
                    previous?.recycle(); previous = canvasBitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (info.blendOperation == AnimatedDrawableFrameInfo.BlendOperation.NO_BLEND) canvas.drawRect(rect, clear)
                val frame = image.getFrame(i)
                val frameBitmap = Bitmap.createBitmap(max(1, info.width), max(1, info.height), Bitmap.Config.ARGB_8888)
                try {
                    frame.renderFrame(info.width, info.height, frameBitmap)
                    canvas.drawBitmap(frameBitmap, info.xOffset.toFloat(), info.yOffset.toFloat(), null)
                } finally {
                    frameBitmap.recycle(); frame.dispose()
                }
                val duration = max(StickerRules.MIN_FRAME_MS, durations.getOrElse(i) { 100 }.let { if (it <= 10) 100 else it })
                if (elapsed + duration > StickerRules.MAX_ANIMATION_MS) break
                if (i % step == 0) {
                    outCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
                    outCanvas.drawBitmap(canvasBitmap, null, dst, filter)
                    val bytes = ByteArrayOutputStream()
                    check(out512.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, bytes))
                    frames += bytes.toByteArray()
                    frameTimes += duration
                } else if (frameTimes.isNotEmpty()) {
                    frameTimes[frameTimes.lastIndex] += duration
                }
                elapsed += duration
                when (info.disposalMethod) {
                    AnimatedDrawableFrameInfo.DisposalMethod.DISPOSE_TO_BACKGROUND -> canvas.drawRect(rect, clear)
                    AnimatedDrawableFrameInfo.DisposalMethod.DISPOSE_TO_PREVIOUS -> previous?.let {
                        canvas.drawColor(0, PorterDuff.Mode.CLEAR); canvas.drawBitmap(it, 0f, 0f, null)
                    }
                    else -> {}
                }
            }
            if (frames.size < 2) return null
            return runCatching { AnimatedWebpMuxer.mux(frames, frameTimes) }.getOrNull()
        } finally {
            canvasBitmap.recycle(); out512.recycle(); previous?.recycle()
        }
    }
}
