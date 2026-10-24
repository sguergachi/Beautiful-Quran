package com.beautifulquran.ui.reader

import com.beautifulquran.domain.EnglishVerseRun
import com.beautifulquran.domain.englishBookOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafTapHoldTest {
    private val book = englishBookOf(
        listOf(
            listOf(EnglishVerseRun(24, 29, 0, 70)),
            listOf(EnglishVerseRun(24, 29, 70, 100), EnglishVerseRun(24, 30, 0, 100)),
        ),
        pageOf = { _, _ -> 353 },
        text = { _, _ -> "x".repeat(100) },
    )
    private val ends = floatArrayOf(0.1f, 0.2f, 0.8f, 0.9f, 1f)

    @Test
    fun `a real word on the carried leaf releases its tap hold`() {
        val word = ActiveWord(ayah = 29, wordPosition = 4, durationMs = 500)
        val leaf = mushafWordLeafNumber(book, 24, word, 353, 5, ends)
        assertEquals(2, leaf)
        assertTrue(mushafWaitingLeafReleased(2, leaf, word.fromTap))
        // With the hold gone, a null word is the outgoing verse's audio tail.
        assertEquals(
            MushafInkPackKind.ACTIVE_WORD,
            mushafInkPackKind(
                pageOwnsVoice = true, ayah = 29, activeWordAyah = null,
                frontierAyah = 29, basmalahActive = false, hasSearchFlash = false,
                waitingForVoice = !mushafWaitingLeafReleased(2, leaf, word.fromTap),
                drying = true,
            ),
        )
    }

    @Test
    fun `the opening portion cannot release a hold on the carried leaf`() {
        val word = ActiveWord(ayah = 29, wordPosition = 3, durationMs = 500)
        val leaf = mushafWordLeafNumber(book, 24, word, 353, 5, ends)
        assertEquals(1, leaf)
        assertFalse(mushafWaitingLeafReleased(2, leaf, word.fromTap))
    }

    @Test
    fun `a tap seed on the carried leaf still waits for the real poll`() {
        val word = ActiveWord(ayah = 29, wordPosition = 4, durationMs = 500, fromTap = true)
        val leaf = mushafWordLeafNumber(book, 24, word, 353, 5, ends)
        assertEquals(2, leaf)
        assertFalse(mushafWaitingLeafReleased(2, leaf, word.fromTap))
    }

    @Test
    fun `finishing a carried verse and starting the next never follows its opening leaf`() {
        val seed = ActiveWord(ayah = 29, wordPosition = 5, durationMs = 500, activation = 8)
        val last = WordTapInk(seed).resolve(seed.copy(activation = 9, playbackActivation = 9))!!
        fun leaf(word: ActiveWord?, ayah: Int, inkId: Long): Int {
            val location = mushafLocationWord(word, last, ayah, inkId)
            return mushafLeafNumber(
                book, 24, location?.ayah ?: ayah, 353,
                mushafVerseThrough(location?.wordPosition, 5, ends),
            )
        }
        assertEquals(
            listOf(2, 2, 2, 2),
            listOf(
                leaf(last, 29, 9),
                leaf(null, 29, 9),
                leaf(null, 30, 10),
                leaf(last.copy(ayah = 30, wordPosition = 1, activation = 10, playbackActivation = 10), 30, 10),
            ),
        )
        // An intentional restart still opens the verse's first leaf.
        assertEquals(1, leaf(null, 29, 10))
    }

    @Test
    fun `Arabic tap holds use the native page`() {
        val word = ActiveWord(ayah = 29, wordPosition = 4, durationMs = 500)
        val leaf = mushafWordLeafNumber(null, 24, word, 353, 5, ends)
        assertEquals(353, leaf)
        assertTrue(mushafWaitingLeafReleased(353, leaf, word.fromTap))
        assertFalse(mushafWaitingLeafReleased(354, leaf, word.fromTap))
    }
}
