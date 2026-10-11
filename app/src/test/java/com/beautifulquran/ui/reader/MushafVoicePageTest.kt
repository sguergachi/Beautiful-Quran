package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.MushafSourceWord
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.ENGLISH_LEAF_CAPACITY_CHARS
import com.beautifulquran.domain.buildEnglishBook
import com.beautifulquran.domain.buildMushafCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ink ownership on the leaf turns on this one answer, so the thing it must
 * never do is lose the voice while the voice is plainly here.
 */
class MushafVoicePageTest {

    @Test
    fun `carried English follows the continuous wash without returning to advanced words`() {
        val text = "painful punishment"
        val alignment = EnglishWordAlignment.align(text, listOf("punishment", "painful"))!!
        assertEquals(0f, mushafVerseThrough(
            ActiveWord(ayah = 10, wordPosition = 1, durationMs = 100), 2, alignment = alignment,
        ), 0.00001f)
        assertEquals(1f, mushafVerseThrough(
            ActiveWord(ayah = 10, wordPosition = 2, durationMs = 100), 2, alignment = alignment,
        ), 0f)
    }

    // 2:2 opens page 3 and runs onto page 4; 2:3 opens page 4.
    private val catalog = buildMushafCatalog(
        listOf(
            source(2, 1, 1, page = 3, line = 1),
            source(2, 2, 1, page = 3, line = 2),
            source(2, 2, 2, page = 4, line = 1),
            source(2, 3, 1, page = 4, line = 1),
        ),
    )

    @Test
    fun `previous verse starts on its opening Arabic page despite its carried last word`() {
        assertEquals(4, catalog.pageOf(2, 2, 2))
        assertEquals(2, mushafVerseStartLeaf(catalog, null, 2, 2))
    }

    @Test
    fun `restarting a carried English verse returns to its opening leaf`() {
        val catalog = buildMushafCatalog(listOf(
            source(2, 2, 1, page = 3, line = 1),
            source(2, 3, 1, page = 3, line = 2),
        ))
        val cap = ENGLISH_LEAF_CAPACITY_CHARS
        val book = buildEnglishBook(catalog) { _, ayah -> if (ayah == 2) cap / 2 else cap }
        val opening = mushafVerseStartLeaf(catalog, book, 2, 3)
        val tail = mushafLeafNumber(book, 2, 3, page = 3, through = 0.99f) - 1
        assertTrue(tail > opening)
        assertEquals(book.firstLeafOf(3), opening)
    }

    @Test
    fun `a voice on another reading owns no leaf here`() {
        assertNull(page(playback(playingHere = false, activeAyah = 2, playingAyah = 2)))
    }

    @Test
    fun `between two words the leaf is still the one Media3 is playing`() {
        // No active word and no ink frontier — the poll is between words and
        // the fade has let go of the last verse. The reader has not moved.
        assertEquals(
            4,
            page(playback(activeAyah = null, playingAyah = 3)),
        )
    }

    @Test
    fun `the ink frontier wins over the playlist while both are known`() {
        // The frontier leads the playlist across a verse end; the leaf follows
        // what the reader is looking at.
        assertEquals(3, page(playback(activeAyah = 2, playingAyah = 3)))
    }

    @Test
    fun `an active word owns the leaf, and the English leaf reads its verse's own`() {
        // The word is printed on page 4; the sentence was set whole on page 3.
        val word = ActiveWord(ayah = 2, wordPosition = 2, durationMs = 100)
        assertEquals(4, page(playback(), word = word, wholeVerses = false))
        assertEquals(3, page(playback(), word = word, wholeVerses = true))
    }

    @Test
    fun `the chapter's basmalah owns its opening leaf`() {
        assertEquals(
            3,
            page(playback(basmalahActive = true, activeAyah = null, playingAyah = null)),
        )
    }

    @Test
    fun `only a voice that has named nothing at all leaves the leaf unowned`() {
        assertNull(page(playback(activeAyah = null, playingAyah = null)))
    }

    private fun page(
        voice: MushafPlayback,
        word: ActiveWord? = null,
        wholeVerses: Boolean = true,
    ) = mushafVoicePage(
        catalog = catalog,
        surahId = 2,
        voice = voice,
        word = word,
        wholeVerses = wholeVerses,
    )

    private fun playback(
        playingHere: Boolean = true,
        basmalahActive: Boolean = false,
        activeAyah: Int? = 2,
        playingAyah: Int? = 2,
    ) = MushafPlayback(
        activeAyah = activeAyah,
        reciting = true,
        playingHere = playingHere,
        basmalahActive = basmalahActive,
        isPlaying = true,
        playingAyah = playingAyah,
    )
}

private fun source(surahId: Int, ayah: Int, position: Int, page: Int, line: Int) =
    MushafSourceWord(
        surahId = surahId,
        ayah = ayah,
        word = Word(
            position = position,
            arabic = "و",
            translation = "",
            transliteration = "",
            qcfPage = page,
            qcfLine = line,
        ),
    )
