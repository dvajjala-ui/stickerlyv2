package app.stickerdock.android.diagnostics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent ring buffer of integration evidence: which app queried our WhatsApp provider,
 * which MIME types a text field accepted from the keyboard, pack-add results. Shown on the
 * Diagnostics screen and mirrored to logcat (tag StickerDock) for adb-driven device checks.
 */
class EventLog(context: Context) {
    private val prefs = context.getSharedPreferences("event_log", Context.MODE_PRIVATE)
    private val lock = Any()

    fun record(kind: String, message: String) {
        val line = "${stamp()} [$kind] $message"
        Log.i(TAG, line)
        synchronized(lock) {
            val current = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
            val next = JSONArray()
            val start = maxOf(0, current.length() - (MAX - 1))
            for (i in start until current.length()) next.put(current.getString(i))
            next.put(line)
            prefs.edit().putString(KEY, next.toString()).apply()
        }
    }

    fun lines(): List<String> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        List(array.length()) { array.getString(it) }
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        const val TAG = "StickerDock"
        private const val KEY = "lines"
        private const val MAX = 200
    }
}
