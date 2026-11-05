package app.stickerdock.core

/**
 * Search words a phone can work out by itself for stickers that arrive without any (WhatsApp's received stickers
 * are named like "STK-20241027-WA0008" and carry at most a few emojis). Platforms supply on-device text
 * recognition and image labels; this object turns them into Hinglish/English search words. Kept separate from
 * the user's own tags, so it can be recomputed and never overwrites what the user typed.
 */
object AutoTags {
    /** Bump when the rules below change: stickers read with older rules are read again by themselves. */
    const val VERSION = 2

    private val machineNames = listOf(
        Regex("^(STK|IMG|VID|PTT|AUD|DOC)[-_]\\d{8}[-_]WA\\d+(\\.\\w{2,10})?$", RegexOption.IGNORE_CASE), // WhatsApp media, .was too
        Regex("^(IMG|VID|PXL|Screenshot|Screen_Recording)[-_]\\d{8}[-_ ]?\\d*.*$", RegexOption.IGNORE_CASE),
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE),
        Regex("^[0-9a-f_-]{16,}$", RegexOption.IGNORE_CASE),
        Regex("^\\d{6,}$"),
        Regex("^(sticker|image|file|untitled)[-_ ]?\\d*$", RegexOption.IGNORE_CASE),
    )

    /** File names that say nothing about the sticker; treated as "no name". */
    fun isMachineName(label: String): Boolean {
        val l = label.trim()
        return l.isNotEmpty() && machineNames.any { it.matches(l) }
    }

    /** A readable name, or "" for machine names. */
    fun displayName(label: String): String = if (isMachineName(label)) "" else label

    private val emojiWords = linkedMapOf(
        "😂" to "lol haha hasi funny laugh", "🤣" to "rofl lol hasi funny", "😆" to "haha hasi", "😅" to "hehe awkward",
        "😀" to "happy khush smile", "😃" to "happy khush", "😄" to "happy khush", "😁" to "happy khush grin",
        "😊" to "happy khush smile", "🙂" to "smile theek", "☺" to "smile shy", "🥲" to "smile cry emotional",
        "😍" to "love pyaar", "🥰" to "love pyaar cute", "😘" to "kiss love pyaar", "❤" to "love pyaar dil heart",
        "💕" to "love pyaar", "💖" to "love pyaar", "♥" to "love pyaar", "💔" to "heartbreak dil toota sad",
        "😢" to "sad dukhi udaas", "😥" to "sad dukhi", "😞" to "sad dukhi", "😔" to "sad dukhi", "🥺" to "please cute miss",
        "😭" to "cry rona sad", "😡" to "angry gussa", "😠" to "angry gussa", "🤬" to "angry gussa gaali",
        "😱" to "shock omg", "😨" to "shock dar", "😮" to "wow shock", "😲" to "wow shock", "😳" to "embarrassed sharam shock",
        "😎" to "cool swag", "🤔" to "thinking soch", "😴" to "sleep neend", "🥱" to "sleep neend bore", "😪" to "sleep neend",
        "🙏" to "please thanks namaste dhanyavaad sorry", "👍" to "ok theek haan done", "👌" to "ok perfect badhiya",
        "👎" to "no nahi bad", "🎉" to "party celebrate badhai", "🥳" to "party celebrate birthday", "🎊" to "party celebrate",
        "🎂" to "birthday bday cake", "🤦" to "facepalm", "🙄" to "eyeroll whatever", "🤗" to "hug", "👋" to "hi hello bye",
        "🔥" to "fire savage hot", "💩" to "poop", "🤪" to "crazy pagal masti", "😜" to "masti crazy", "😝" to "masti",
        "😛" to "masti tongue", "😇" to "innocent", "😏" to "smirk attitude", "🤫" to "secret chup", "🤐" to "chup quiet",
        "😤" to "frustrated gussa", "😩" to "tired thak", "😫" to "tired thak", "🤮" to "yuck", "🤢" to "yuck",
        "🤷" to "pata nahi dunno", "💪" to "strong power", "🙈" to "sharam shy", "😬" to "awkward", "🤡" to "joker clown",
        "☕" to "chai coffee", "🌙" to "good night", "☀" to "good morning", "🌞" to "good morning", "💰" to "money paisa",
        "🤑" to "money paisa", "🏏" to "cricket", "🎶" to "song gaana music", "🎵" to "song gaana music",
        "💃" to "dance naach", "🕺" to "dance naach", "👀" to "dekho look", "🙌" to "yay celebrate", "👏" to "clap wah",
        "💯" to "hundred perfect", "😈" to "naughty devil", "👻" to "ghost bhoot", "🐶" to "dog kutta", "🐱" to "cat billi",
    )

    private val labelWords = mapOf(
        "smile" to "smile happy khush", "fun" to "fun masti mazaa", "cool" to "cool swag", "party" to "party celebrate",
        "dog" to "dog kutta", "cat" to "cat billi", "baby" to "baby bachcha cute", "flower" to "flower phool",
        "cake" to "cake birthday", "food" to "food khana", "car" to "car gaadi", "bird" to "bird chidiya",
        "monkey" to "monkey bandar", "dance" to "dance naach", "musical instrument" to "music gaana", "selfie" to "selfie",
        "beard" to "beard dadhi", "moustache" to "moustache mooch", "hat" to "hat topi", "glasses" to "glasses chashma",
        "sunglasses" to "sunglasses cool swag", "crowd" to "crowd bheed", "cartoon" to "cartoon", "comics" to "comic cartoon",
        "team" to "team", "sports" to "sports khel", "jersey" to "jersey cricket", "love" to "love pyaar", "bride" to "shaadi wedding",
        "wedding" to "shaadi wedding", "money" to "money paisa", "phone" to "phone mobile", "tea" to "chai", "coffee" to "coffee",
        "sleep" to "sleep neend", "superhero" to "superhero", "toy" to "toy khilona", "pet" to "pet",
    )

    /** Labels too generic to help anyone search. */
    private val noise = setOf("skin", "room", "pattern", "font", "rectangle", "circle", "event", "nail", "metal", "paper",
        "poster", "muscle", "hair", "standing", "sitting", "jaw", "tooth", "eye", "iris", "face", "head", "close-up", "flash photography",
        "plant", "sky", "cloud", "tableware", "wheel", "gesture", "flesh", "hand", "eyelash", "eyebrow", "ear", "nose",
        "mouth", "lip", "cheek", "chin", "forehead", "neck", "sleeve", "collar", "textile", "pocket", "wood", "brick",
        "stairs", "shelf", "curtain", "window", "building", "road", "grass", "sand", "water", "foot", "leg", "arm")

    /** Search words for the emojis WhatsApp stored with a sticker. */
    fun wordsForEmojis(emojis: String): List<String> =
        Tags.emojis(emojis.replace(',', ' '), max = 6).mapNotNull { e ->
            val base = e.filter { it.code != 0xfe0f }.let { stripped -> emojiWords.keys.firstOrNull { stripped.startsWith(it) } }
            base?.let { emojiWords[it] }
        }.flatMap { it.split(' ') }

    /** Search words for on-device image labels (English, as the labeller returns them). */
    fun wordsForLabels(labels: List<String>): List<String> = labels.flatMap { raw ->
        val l = raw.trim().lowercase()
        when {
            l.isEmpty() || l in noise -> emptyList()
            else -> (labelWords[l] ?: l).split(' ')
        }
    }

    /**
     * A name from text recognized on the sticker: its lines joined, junk stripped, ALL-CAPS softened, ≤60 chars.
     * Null when nothing word-like was read.
     */
    fun captionFrom(recognized: String): String? {
        val tokens = recognized.lines().flatMap { it.trim().split(Regex("\\s+")) }
            .filterNot(::looksLikeWatermark) // before trimming, which would strip the "@" of a handle
            .map { it.trim { c -> !c.isWordChar() && c != '?' && c != '!' && c !in "'’" } }
            .filter { t -> t.any { it.isLetter() } && (t.length > 1 || t.lowercase() in setOf("a", "i", "o", "h", "k")) }
            .filter { t -> t.count { it.isDigit() } <= t.count { it.isLetter() } } // "O000" is OCR noise, "2day" is fine
        // Real captions have two words, or one long one; a lone short token ("Son") is usually misread texture.
        val words = tokens.filter { t -> t.count { it.isLetter() } >= 2 }
        if (words.size < 2 && words.none { w -> w.count { it.isLetter() } >= 4 }) return null
        val text = tokens.joinToString(" ").take(60).trim()
        val letters = text.filter { it.isLetter() && it.code < 0x0900 }
        val shouting = letters.length >= 4 && letters.all { it.isUpperCase() }
        return if (shouting) text.lowercase().replaceFirstChar { it.uppercase() } else text
    }

    /** Letters, digits and combining marks (Devanagari vowel signs are marks, not letters). */
    private fun Char.isWordChar() = isLetterOrDigit() || category == CharCategory.NON_SPACING_MARK ||
        category == CharCategory.COMBINING_SPACING_MARK

    private val watermarkWords = Regex("(?i)tiktok|tikiok|sonyliv|instagram|youtube|facebook|snapchat|sharechat|giphy|tenor|" +
        "imgflip|glitch|www|\\.com|\\.in\\b|\\.net|\\.co\\b")

    /** Domains, @handles, user_names and app watermarks that ride along on forwarded stickers. */
    fun looksLikeWatermark(token: String): Boolean =
        token.contains('@') || token.contains('_') || Regex("\\p{L}\\.\\p{L}").containsMatchIn(token) || watermarkWords.containsMatchIn(token)

    /** Everything combined into normalized, de-duplicated search words. */
    fun build(recognized: String, imageLabels: List<String>, emojis: String): String {
        val words = mutableListOf<String>()
        words += Tags.split(recognized).filterNot(::looksLikeWatermark)
        words += Devanagari.romanize(recognized).flatMap { Tags.split(it) }
        words += wordsForLabels(imageLabels)
        words += wordsForEmojis(emojis)
        return words.map { it.lowercase() }.filter { it.length in 2..Tags.MAX_TAG_LENGTH }.distinct().take(48).joinToString(" ")
    }
}

/**
 * Rough Devanagari → Latin so a Hindi caption can be found by typing Hinglish ("क्या बात है" → "kya baat hai").
 * Each word comes out twice when vowels are long: as written ("baat") and the way people type it ("bat").
 */
object Devanagari {
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d",
        'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r",
        'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
        'क़' to "q", 'ख़' to "kh", 'ग़' to "gh", 'ज़' to "z", 'ड़' to "d", 'ढ़' to "dh", 'फ़' to "f",
    )
    private val nuktaForms = mapOf('क' to 'क़', 'ख' to 'ख़', 'ग' to 'ग़', 'ज' to 'ज़', 'ड' to 'ड़', 'ढ' to 'ढ़', 'फ' to 'फ़')
    private val vowels = mapOf('अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o")
    private val signs = mapOf('ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri", 'े' to "e",
        'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o")
    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    /** Romanized forms of each Devanagari word in [text] (other words are ignored). */
    fun romanize(text: String): List<String> = text.split(Regex("[^\\u0900-\\u097F]+")).filter { it.isNotBlank() }.flatMap { word ->
        val long = word(word)
        val short = long.replace("aa", "a").replace("ee", "i").replace("oo", "u")
        listOf(long, short).distinct()
    }.filter { it.length >= 2 }

    private fun word(w: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            var c = w[i]
            if (i + 1 < w.length && w[i + 1] == NUKTA) { c = nuktaForms[c] ?: c; i++ }
            val consonant = consonants[c]
            when {
                consonant != null -> {
                    out.append(consonant)
                    val next = w.getOrNull(i + 1)
                    when {
                        next == VIRAMA -> i++
                        next != null && signs.containsKey(next) -> { out.append(signs.getValue(next)); i++ }
                        // Inherent "a", dropped at the end of a word as spoken Hindi does ("बात" → "baat").
                        next != null && next != 'ं' && next != 'ँ' && next != 'ः' -> out.append('a')
                        next == 'ं' || next == 'ँ' -> out.append('a')
                    }
                }
                vowels.containsKey(c) -> out.append(vowels.getValue(c))
                c == 'ं' || c == 'ँ' -> out.append('n')
                c == 'ः' -> out.append('h')
                c in '०'..'९' -> out.append('0' + (c - '०'))
            }
            i++
        }
        return out.toString()
    }
}
