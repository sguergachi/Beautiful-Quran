package com.beautifulquran.ui.rootviewer

import com.beautifulquran.data.model.DictionaryEntry
import com.beautifulquran.data.model.DictionarySenseGroup
import com.beautifulquran.data.parseDictionaryPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryTextTest {

    @Test
    fun `parsePayload reads POS groups`() {
        val groups = parseDictionaryPayload(
            """[{"pos":"verb","glosses":["to say","to tell"]},{"pos":"noun","glosses":["speech"]}]""",
        )
        assertEquals(2, groups.size)
        assertEquals("verb", groups[0].pos)
        assertEquals(listOf("to say", "to tell"), groups[0].glosses)
        assertEquals("noun", groups[1].pos)
    }

    @Test
    fun `parsePayload tolerates spaced JSON from the packed DB`() {
        // build_dictionary_db emits spaces after ':' — must parse on Android ICU too.
        val groups = parseDictionaryPayload(
            """[{"pos": "noun", "glosses": ["fire", "conflagration", "gunfire"]}]""",
        )
        assertEquals(1, groups.size)
        assertEquals("noun", groups[0].pos)
        assertEquals(listOf("fire", "conflagration", "gunfire"), groups[0].glosses)
    }

    @Test
    fun `parsePayload unescapes quoted glosses`() {
        val groups = parseDictionaryPayload(
            """[{"pos":"noun","glosses":["a \"quoted\" sense","line\nbreak"]}]""",
        )
        assertEquals(listOf("a \"quoted\" sense", "line\nbreak"), groups[0].glosses)
    }

    @Test
    fun `preferred QAC POS comes first`() {
        val entry = DictionaryEntry(
            lemma = "قَالَ",
            word = "قال",
            groups = listOf(
                DictionarySenseGroup("noun", listOf("speech")),
                DictionarySenseGroup("verb", listOf("to say")),
            ),
            credit = "credit",
        )
        val glosses = dictionaryGlosses(entry, "V")
        assertEquals("Verb", glosses.first().first)
        assertEquals("to say", glosses.first().second)
    }

    @Test
    fun `preview threshold matches Classical-lexicon style expand`() {
        assertFalse(dictionaryNeedsExpand(DICTIONARY_PREVIEW_SENSES))
        assertTrue(dictionaryNeedsExpand(DICTIONARY_PREVIEW_SENSES + 1))
    }

    @Test
    fun `wiktionary URL encodes the Arabic headword`() {
        assertTrue(wiktionaryArabicUrl("كتاب").contains("كتاب") ||
            wiktionaryArabicUrl("كتاب").contains("%D9%83"))
        assertTrue(wiktionaryArabicUrl("كتاب").endsWith("#Arabic"))
    }
}
