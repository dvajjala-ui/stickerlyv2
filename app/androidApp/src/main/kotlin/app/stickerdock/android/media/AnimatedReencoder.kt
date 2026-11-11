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
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.facebook.animated.gif.GifImage
import com.facebook.animated.webp.WebPImage
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo
import com.facebook.imagepipeline.animated.base.AnimatedImage
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.soloader.nativeloader.NativeLoader
import com.facebook.soloader.nativeloader.SystemDelegate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Re-encodes animations into WhatsApp animated stickers: frames come from a [FrameSource] (Fresco for animated
 * WebP/GIF with blend + disposal compositing; Lottie for WhatsApp's own `.was` stickers), are scaled onto a
 * transparent 512² canvas, encoded with Android's WebP encoder and muxed with their real durations. A budgeted
 * search (≤4 passes) picks quality / frame step so the result fits 500 KB.
 */
object AnimatedReencoder {
    private const val MAX_FRAMES = 32
    private const val START_QUALITY = 72

    private val nativeReady by lazy {
        if (!NativeLoader.isInitialized()) NativeLoader.init(SystemDelegate())
        true
    }

    /** Animated WebP or GIF bytes → sticker, or null. */
    fun reencode(input: ByteArray, maxBytes: Int = StickerRules.ANIMATED_MAX_BYTES): ByteArray? = runCatching {
        check(nativeReady)
        val image: AnimatedImage = when {
            Webp.isWebp(input) -> WebPImage.createFromByteArray(input, ImageDecodeOptions.defaults())
            StickerNormalizer.isGif(input) -> GifImage.createFromByteArray(input)
            else -> return null
        }
        FrescoSource(image).use { encode(it, maxBytes) }
    }.getOrNull()

    /** WhatsApp `.was` (ZIP with animation/animation.json, Lottie) → sticker, or null. */
    fun fromWhatsAppLottie(input: ByteArray, maxBytes: Int = StickerRules.ANIMATED_MAX_BYTES): ByteArray? = runCatching {
        val json = lottieJson(input) ?: return null
        val result = LottieCompositionFactory.fromJsonStringSync(json, null)
        val composition = result.value ?: return null
        LottieSource(composition).use { encode(it, maxBytes) }
    }.getOrNull()

    /**
     * A section of a video → animated sticker at 10 fps, with an optional outlined caption on every frame.
     * [startMs]/[durationMs] pick the section (≤3 s keeps size and timing comfortable for WhatsApp).
     */
    fun fromVideo(context: android.content.Context, uri: android.net.Uri, startMs: Long, durationMs: Long,
                  caption: String = "", maxBytes: Int = StickerRules.ANIMATED_MAX_BYTES): ByteArray? = runCatching {
        VideoSource(context, uri, startMs, durationMs.coerceIn(500, 3_000)).use { source ->
            val captionLayer = if (caption.isBlank()) null else app.stickerdock.android.create.CaptionLayer(caption)
            encode(source, maxBytes, captionLayer?.let { layer -> { canvas: Canvas -> layer.draw(canvas) } })
        }
    }.getOrNull()

    fun isZip(data: ByteArray) = data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() &&
        data[2].toInt() == 3 && data[3].toInt() == 4

    private fun lottieJson(zipBytes: ByteArray): String? {
        if (!isZip(zipBytes)) return null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name.endsWith("animation.json")) {
                    val bytes = app.stickerdock.android.imports.readBounded(zip, 6_000_000) ?: return null
                    return bytes.decodeToString()
                }
            }
        }
    }

    private fun encode(source: FrameSource, maxBytes: Int, overlay: ((Canvas) -> Unit)? = null): ByteArray? {
        if (source.count < 2) return null
        var step = max(1, ceil(source.count / MAX_FRAMES.toDouble()).toInt())
        var quality = START_QUALITY
        repeat(4) {
            val out = attempt(source, step, quality, overlay) ?: return null
            if (out.size <= maxBytes && StickerRules.problem(StickerKind.ANIMATED, Webp.inspect(out), out.size) == null) return out
            val ratio = maxBytes / out.size.toDouble()
            when {
                ratio >= 0.7 && quality > 40 -> quality = max(40, (quality * ratio).toInt() - 5)
                source.count / (step * 2) >= 2 -> { step = max(step + 1, ceil(step / ratio).toInt()); quality = 55 }
                quality > 28 -> quality = 28
                else -> return null
            }
        }
        return null
    }

    /** One pass: render every frame in order (compositing needs it), keep every [step]-th, merge time. */
    private fun attempt(source: FrameSource, step: Int, quality: Int, overlay: ((Canvas) -> Unit)? = null): ByteArray? {
        val out512 = Bitmap.createBitmap(StickerRules.PIXELS, StickerRules.PIXELS, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out512)
        val scale = min(StickerRules.PIXELS / source.width.toFloat(), StickerRules.PIXELS / source.height.toFloat())
        val dst = RectF((StickerRules.PIXELS - source.width * scale) / 2, (StickerRules.PIXELS - source.height * scale) / 2,
            (StickerRules.PIXELS + source.width * scale) / 2, (StickerRules.PIXELS + source.height * scale) / 2)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val frames = mutableListOf<ByteArray>()
        val times = mutableListOf<Int>()
        var elapsed = 0
        try {
            source.reset()
            for (i in 0 until source.count) {
                val frame = source.render(i)
                val duration = source.duration(i)
                if (elapsed + duration > StickerRules.MAX_ANIMATION_MS) break
                if (i % step == 0) {
                    outCanvas.drawColor(0, PorterDuff.Mode.CLEAR)
                    outCanvas.drawBitmap(frame, null, dst, filter)
                    overlay?.invoke(outCanvas)
                    val bytes = ByteArrayOutputStream()
                    check(out512.compress(LOSSY_WEBP, quality, bytes))
                    frames += bytes.toByteArray()
                    times += duration
                } else if (times.isNotEmpty()) {
                    times[times.lastIndex] += duration
                }
                elapsed += duration
            }
            if (frames.size < 2) return null
            return runCatching { AnimatedWebpMuxer.mux(frames, times) }.getOrNull()
        } finally {
            out512.recycle()
        }
    }

    private interface FrameSource : AutoCloseable {
        val width: Int
        val height: Int
        val count: Int
        fun duration(index: Int): Int
        fun reset()
        /** Full composited frame at native size; the bitmap is reused between calls. */
        fun render(index: Int): Bitmap
    }

    private class FrescoSource(private val image: AnimatedImage) : FrameSource {
        override val width = image.width
        override val height = image.height
        override val count = image.frameCount
        private val durations = image.frameDurations
        private val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(canvasBitmap)
        private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private var previous: Bitmap? = null
        private var pendingDispose: (() -> Unit)? = null

        override fun duration(index: Int) = max(StickerRules.MIN_FRAME_MS, durations.getOrElse(index) { 100 }.let { if (it <= 10) 100 else it })

        override fun reset() {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            pendingDispose = null
        }

        override fun render(index: Int): Bitmap {
            pendingDispose?.invoke(); pendingDispose = null
            val info = image.getFrameInfo(index)
            val rect = Rect(info.xOffset, info.yOffset, info.xOffset + info.width, info.yOffset + info.height)
            if (info.disposalMethod == AnimatedDrawableFrameInfo.DisposalMethod.DISPOSE_TO_PREVIOUS) {
                previous?.recycle(); previous = canvasBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (info.blendOperation == AnimatedDrawableFrameInfo.BlendOperation.NO_BLEND) canvas.drawRect(rect, clear)
            val frame = image.getFrame(index)
            val frameBitmap = Bitmap.createBitmap(max(1, info.width), max(1, info.height), Bitmap.Config.ARGB_8888)
            try {
                frame.renderFrame(info.width, info.height, frameBitmap)
                canvas.drawBitmap(frameBitmap, info.xOffset.toFloat(), info.yOffset.toFloat(), null)
            } finally {
                frameBitmap.recycle(); frame.dispose()
            }
            // Disposal applies before the next frame is drawn, after this one has been shown.
            pendingDispose = when (info.disposalMethod) {
                AnimatedDrawableFrameInfo.DisposalMethod.DISPOSE_TO_BACKGROUND -> { { canvas.drawRect(rect, clear) } }
                AnimatedDrawableFrameInfo.DisposalMethod.DISPOSE_TO_PREVIOUS -> { { previous?.let { canvas.drawColor(0, PorterDuff.Mode.CLEAR); canvas.drawBitmap(it, 0f, 0f, null) } } }
                else -> null
            }
            return canvasBitmap
        }

        override fun close() {
            canvasBitmap.recycle(); previous?.recycle(); image.dispose()
        }
    }

    /** A video section sampled at 10 fps; frames are fetched pre-scaled (≤512 px) by the platform decoder. */
    private class VideoSource(context: android.content.Context, uri: android.net.Uri, private val startMs: Long,
                              durationMs: Long) : FrameSource {
        private val retriever = android.media.MediaMetadataRetriever().apply { setDataSource(context, uri) }
        private val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        private val rawW = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 512
        private val rawH = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 512
        private val portrait = rotation == 90 || rotation == 270
        override val width = if (portrait) rawH else rawW
        override val height = if (portrait) rawW else rawH
        override val count = max(2, (durationMs * FPS / 1000).toInt())
        private var last: Bitmap? = null

        override fun duration(index: Int) = 1000 / FPS
        override fun reset() {}
        override fun render(index: Int): Bitmap {
            val timeUs = (startMs + index * 1000L / FPS) * 1000L
            val scale = min(1f, StickerRules.PIXELS / max(width, height).toFloat())
            val frame = retriever.getScaledFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
                ?: last ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            if (frame !== last) last?.recycle()
            last = frame
            return frame
        }
        override fun close() { last?.recycle(); retriever.release() }

        companion object { const val FPS = 10 }
    }

    /** Lottie rendered straight at 512 px, sampled at 15 fps. */
    private class LottieSource(composition: LottieComposition) : FrameSource {
        override val width = StickerRules.PIXELS
        override val height = StickerRules.PIXELS
        private val durationMs = composition.duration.toInt().coerceIn(200, StickerRules.MAX_ANIMATION_MS)
        override val count = max(2, ceil(durationMs / 1000.0 * FPS).toInt())
        private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private val drawable = LottieDrawable().apply {
            setComposition(composition)
            val bounds = composition.bounds
            val s = min(width / bounds.width().toFloat(), height / bounds.height().toFloat())
            val w = (bounds.width() * s).toInt(); val h = (bounds.height() * s).toInt()
            setBounds((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        override fun duration(index: Int) = 1000 / FPS
        override fun reset() {}
        override fun render(index: Int): Bitmap {
            bitmap.eraseColor(0)
            drawable.progress = index / (count - 1).toFloat()
            drawable.draw(canvas)
            return bitmap
        }
        override fun close() { bitmap.recycle() }

        companion object { const val FPS = 15 }
    }
}
