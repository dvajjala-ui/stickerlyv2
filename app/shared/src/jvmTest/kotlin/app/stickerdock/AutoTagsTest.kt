package app.stickerdock

import app.stickerdock.core.AutoTags
import app.stickerdock.core.Devanagari
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoTagsTest {
    @Test fun whatsAppFileNamesAreNotNames() {
        listOf("STK-20241027-WA0008", "STK-20250905-WA0003.was", "IMG-20240101-WA0012", "stk_20231105_wa0001", "d57b1c3f-e6fe-4172-9c31-a68c42caf316",
            "IMG_20240101_123456", "1699000000000", "sticker_12").forEach { assertTrue(AutoTags.isMachineName(it), it) }
        listOf("Chai pe charcha", "2024 memes", "Office ke dukh", "STK fan club", "").forEach { assertFalse(AutoTags.isMachineName(it), it) }
        assertEquals("", AutoTags.displayName("STK-20241027-WA0008"))
        assertEquals("Kya baat hai", AutoTags.displayName("Kya baat hai"))
    }

    @Test fun hindiCaptionsRomanizeTheWayPeopleType() {
        val words = Devanagari.romanize("क्या बात है")
        listOf("kyaa", "kya", "baat", "bat", "hai").forEach { assertTrue(it in words, "$it in $words") }
        assertTrue("mazaa" in Devanagari.romanize("मज़ा"), "nukta: ज़ is z")
        assertTrue(Devanagari.romanize("नहीं").any { it.startsWith("nahi") })
        assertTrue("samajh" in Devanagari.romanize("समझ"), "no stray final a")
        assertTrue(Devanagari.romanize("hello 123").isEmpty())
    }

    @Test fun captionFromRecognizedText() {
        assertEquals("When the wifi drops right before the match", AutoTags.captionFrom("WHEN THE WIFI DROPS\nRIGHT BEFORE THE MATCH"))
        assertEquals("Kya baat hai boss", AutoTags.captionFrom("Kya baat hai\nboss"))
        assertEquals("क्या बात है", AutoTags.captionFrom("क्या बात है"), "vowel signs are part of the word")
        assertNull(AutoTags.captionFrom("® | .."))
        assertNull(AutoTags.captionFrom(""))
        // Seen on the phone: texture misread as text must not become a name.
        assertNull(AutoTags.captionFrom("Son O000"))
        assertNull(AutoTags.captionFrom("T"))
        assertEquals("another smart phone in the wrong hands", AutoTags.captionFrom("another smart phone in the wrong hands"))
        assertEquals("बतो होना ही था", AutoTags.captionFrom("बतो होना ही था"))
        assertEquals("Bakwaas", AutoTags.captionFrom("Bakwaas"), "one long word is a caption")
        // Seen on the phone: app watermarks and usernames on forwarded stickers.
        assertNull(AutoTags.captionFrom("SonyV.com SON"))
        assertEquals("AISA THODI HOTA HAI".lowercase().replaceFirstChar { it.uppercase() }, AutoTags.captionFrom("newt_sch AISA THODI HOTA HAI"))
        assertNull(AutoTags.captionFrom("TIKTOK @ekabyalarinturhan"))
        assertEquals("Aao Saalo", AutoTags.captionFrom("Aao Saalo\nmumbaiindians.com"))
        assertFalse("sonyliv" in AutoTags.build("SonyLIV Waah!", emptyList(), "").split(' '))
    }

    @Test fun buildCombinesTextLabelsAndEmojiWords() {
        val tags = AutoTags.build("Gussa mat karo", listOf("Smile", "Skin", "Dog"), "😡,🙏").split(' ')
        listOf("gussa", "karo", "smile", "khush", "kutta", "angry", "namaste").forEach { assertTrue(it in tags, "$it in $tags") }
        assertFalse("skin" in tags, "generic labels are dropped")
        assertEquals(tags.distinct(), tags)
        val hindi = AutoTags.build("मज़ा आ गया", emptyList(), "").split(' ')
        assertTrue("mazaa" in hindi && "maza" in hindi && "मज़ा" in hindi)
        assertTrue(AutoTags.wordsForEmojis("👍🏽").contains("ok"), "skin tones still map")
    }
}
