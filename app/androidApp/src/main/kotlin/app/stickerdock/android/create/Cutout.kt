package app.stickerdock.android.create

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device subject cutout (ML Kit; the model downloads once through Google Play services). */
object Cutout {
    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    }

    suspend fun subject(source: Bitmap): Bitmap? {
        val foreground = suspendCancellableCoroutine<Bitmap?> { cont ->
            segmenter.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener { cont.resume(it.foregroundBitmap) }
                .addOnFailureListener { cont.resumeWithException(it) }
        } ?: return null
        return trim(foreground)
    }

    /** Crops fully transparent borders so the subject fills the sticker. */
    fun trim(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) > 16) {
                if (x < left) left = x; if (x > right) right = x
                if (y < top) top = y; if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }
}
