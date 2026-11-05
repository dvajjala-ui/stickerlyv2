package app.stickerdock.android.tags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.stickerdock.android.media.StickerNormalizer
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Reads a sticker on the device: printed text (Latin and Devanagari) and what it shows (image labels).
 * Nothing leaves the phone. Models come from Google Play services the first time ([ModelNotReady] until then).
 */
class AutoTagger(private val context: Context) : Closeable {
    class ModelNotReady(cause: Throwable) : Exception("On-device text models are still downloading", cause)

    data class Reading(val caption: String, val allText: String, val labels: List<String>)

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.72f).build())

    /** Asks Play services to fetch the text models now instead of on first use (no-op when present). */
    fun requestModels() {
        runCatching {
            ModuleInstall.getClient(context).installModules(
                ModuleInstallRequest.newBuilder().addApi(latin).addApi(devanagari).build())
        }
    }

    /** Null when the bytes don't decode. Throws [ModelNotReady] while models are missing (retry later). */
    fun read(bytes: ByteArray): Reading? {
        val decoded = StickerNormalizer.decode(bytes, 512) ?: return null
        // Stickers are transparent; recognizers read best on a flat background. Mid-grey keeps both the usual
        // white caption fill and its black outline visible.
        val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.rgb(128, 128, 128)); drawBitmap(decoded, 0f, 0f, null) }
        decoded.recycle()
        try {
            val image = InputImage.fromBitmap(flat, 0)
            val latinText = readable(await(latin.process(image)), flat.height)
            val hindiText = readable(await(devanagari.process(image)), flat.height)
            val labels = runCatching { await(labeler.process(image)).orEmpty().map { it.text } }.getOrDefault(emptyList())
            // The Devanagari model also reads Latin script; the caption comes from whichever read more letters.
            val caption = listOf(latinText, hindiText).maxByOrNull { t -> t.count { it.isLetter() } }.orEmpty()
            val all = listOf(latinText, hindiText).filter { it.isNotBlank() }.distinct().joinToString("\n")
            return Reading(caption, all, labels)
        } finally {
            flat.recycle()
        }
    }

    /** Lines big enough to be the sticker's caption; small print (watermarks, @handles, app names) is skipped. */
    private fun readable(text: Text?, height: Int): String {
        if (text == null) return ""
        val min = height * MIN_LINE_HEIGHT
        return text.textBlocks.flatMap { it.lines }.filter { (it.boundingBox?.height() ?: 0) >= min }.joinToString("\n") { it.text }
    }

    private fun <T> await(task: Task<T>): T? = try {
        Tasks.await(task, 20, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        val cause = e.cause
        if (cause is MlKitException && cause.errorCode == MlKitException.UNAVAILABLE) throw ModelNotReady(cause)
        null
    } catch (e: java.util.concurrent.TimeoutException) {
        null
    }

    companion object {
        /** Caption lines are usually 25–80 px on a 512 px sticker; watermarks 8–18 px. */
        const val MIN_LINE_HEIGHT = 0.045f
    }

    override fun close() {
        latin.close(); devanagari.close(); labeler.close()
    }
}
