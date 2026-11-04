package app.stickerdock.android.keyboard

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import app.stickerdock.android.AppGraph

/**
 * A text field that accepts stickers exactly like WhatsApp's composer (image/webp.wasticker,
 * image/webp, image/png). Lets us verify the keyboard on a device without opening any chat.
 */
class KeyboardTestActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Switch to the StickerDock keyboard and tap a sticker."; textSize = 16f; setPadding(32, 48, 32, 16) }
        preview = ImageView(this).apply { minimumHeight = 512; adjustViewBounds = true }
        val field = object : EditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val base = super.onCreateInputConnection(outAttrs) ?: return null
                EditorInfoCompat.setContentMimeTypes(outAttrs, MIME_TYPES)
                return InputConnectionCompat.createWrapper(this, base, outAttrs)
            }
        }.apply { hint = "Sticker test field"; gravity = Gravity.TOP; minLines = 2 }
        ViewCompat.setOnReceiveContentListener(field, MIME_TYPES) { _, payload ->
            val clip = payload.clip
            val item = clip.getItemAt(0)
            val uri = item.uri
            val mime = clip.description.getMimeType(0)
            val bytes = uri?.let { runCatching { contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }.getOrNull() }
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            preview.setImageBitmap(bitmap)
            val line = "received $mime ${bytes?.size ?: 0} bytes from keyboard uri=$uri decoded=${bitmap != null}"
            status.text = line
            AppGraph.get(this).events.record("keyboard-test", line)
            null as ContentInfoCompat?
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(field); addView(preview)
        })
        field.requestFocus()
    }

    companion object {
        val MIME_TYPES = arrayOf("image/webp.wasticker", "image/webp", "image/png")
    }
}
