package app.stickerdock.core

/** Normalizes labels, tags and emojis so search, WhatsApp emoji fields and sync agree. */
object Tags {
    const val MAX_LABEL = 80
    const val MAX_TAGS = 24
    const val MAX_TAG_LENGTH = 32

    fun cleanLabel(raw: String): String =
        raw.replace(Regex("\\.(webp|png|jpe?g|gif)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)

    /** Lowercase, split on separators, drop junk, dedupe, keep order. */
    fun normalize(raw: String): String = split(raw).joinToString(" ")

    fun split(raw: String): List<String> =
        raw.lowercase()
            .split(Regex("[\\s,;#|/]+"))
            .map { it.trim('.', '-', '_', '"', '\'', '(', ')', '[', ']') }
            .filter { it.length in 2..MAX_TAG_LENGTH && it.none { ch -> ch.code < 32 } && !isEmojiOnly(it) }
            .distinct()
            .take(MAX_TAGS)

    /** Terms for LIKE search; each must match somewhere. */
    fun searchPattern(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    // ---------- emoji ----------

    /** Extracts up to [max] emoji grapheme-ish clusters (base + modifiers/ZWJ sequences). */
    fun emojis(text: String, max: Int = StickerRules.MAX_EMOJIS): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length && result.size < max) {
            val cp = codePointAt(text, i)
            val width = if (cp > 0xffff) 2 else 1
            if (isEmojiBase(cp)) {
                val sb = StringBuilder(text.substring(i, i + width))
                var j = i + width
                while (j < text.length) {
                    val next = codePointAt(text, j)
                    val nw = if (next > 0xffff) 2 else 1
                    val joinsNext = next == 0x200d && j + 1 < text.length
                    if (next == 0xfe0f || next in 0x1f3fb..0x1f3ff || next == 0x20e3 || next in 0xe0020..0xe007f) {
                        sb.append(text, j, j + nw); j += nw
                    } else if (joinsNext) {
                        val after = codePointAt(text, j + 1)
                        val aw = if (after > 0xffff) 2 else 1
                        sb.append(text, j, j + 1 + aw); j += 1 + aw
                    } else if (isRegionalIndicator(cp) && isRegionalIndicator(next) && sb.length == width) {
                        sb.append(text, j, j + nw); j += nw
                    } else break
                }
                val emoji = sb.toString()
                if (emoji !in result) result += emoji
                i = j
            } else i += width
        }
        return result
    }

    /** WhatsApp wants 1–3 emojis per sticker; infer from text when the user gave none. */
    fun whatsAppEmojis(emojiField: String, label: String, tags: String): List<String> {
        val explicit = emojis(emojiField)
        if (explicit.isNotEmpty()) return explicit
        val fromText = emojis("$label $tags")
        if (fromText.isNotEmpty()) return fromText
        val words = split("$label $tags")
        val inferred = words.firstNotNullOfOrNull { word -> keywordEmoji.entries.firstOrNull { (k, _) -> word.startsWith(k) }?.value }
        return listOf(inferred ?: "😀")
    }

    private val keywordEmoji = linkedMapOf(
        "lol" to "😂", "haha" to "😂", "funny" to "😂", "hasi" to "😂", "laugh" to "😂", "rofl" to "🤣",
        "love" to "❤️", "pyar" to "❤️", "heart" to "❤️", "kiss" to "😘", "miss" to "🥺",
        "sad" to "😢", "cry" to "😭", "rona" to "😭", "dukh" to "😢", "sorry" to "🙏",
        "angry" to "😡", "gussa" to "😡", "mad" to "😠", "thank" to "🙏", "dhanyavad" to "🙏", "shukriya" to "🙏",
        "ok" to "👍", "yes" to "👍", "haan" to "👍", "done" to "👍", "no" to "🙅", "nahi" to "🙅",
        "wow" to "😮", "shock" to "😱", "omg" to "😱", "sleep" to "😴", "neend" to "😴", "tired" to "😩",
        "party" to "🎉", "birthday" to "🎂", "congrat" to "🎉", "cool" to "😎", "swag" to "😎",
        "think" to "🤔", "soch" to "🤔", "confus" to "😕", "facepalm" to "🤦", "bye" to "👋", "hello" to "👋",
        "hi" to "👋", "namaste" to "🙏", "morning" to "☀️", "night" to "🌙", "chai" to "☕", "food" to "🍲",
        "money" to "💰", "paisa" to "💰", "fire" to "🔥", "savage" to "🔥", "cute" to "🥰", "baby" to "👶",
        "dance" to "💃", "fight" to "🥊", "work" to "💼", "office" to "💼", "exam" to "📚", "study" to "📚",
    )

    private fun isEmojiOnly(s: String) = s.isNotEmpty() && emojis(s, 8).joinToString("").length == s.length

    private fun isRegionalIndicator(cp: Int) = cp in 0x1f1e6..0x1f1ff

    private fun isEmojiBase(cp: Int) = cp in 0x1f000..0x1faff || cp in 0x2600..0x27bf || cp in 0x2300..0x23ff ||
        cp in 0x2b00..0x2bff || cp == 0x00a9 || cp == 0x00ae || cp in 0x2190..0x21ff || cp == 0x203c ||
        cp == 0x2049 || cp in 0x3297..0x3299

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xd800) shl 10) + (s[i + 1].code - 0xdc00)
        }
        return c.code
    }
}
