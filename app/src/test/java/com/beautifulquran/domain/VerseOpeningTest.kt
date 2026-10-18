package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseOpeningTest {

    @Test
    fun `only a hizb opening seeks to the voice`() {
        assertTrue(isHizbStop("۞وَأَيُّوبَ إِذۡ نَادَىٰ"))
        assertFalse(isHizbStop("وَمِنَ ٱلشَّيَٰطِينِ مَن يَغُوصُونَ"))
        // Hani 4:148. Click at 1951, speech at 4283.
        val nisa148 = listOf(Segment(1, 1_951, 4_790), Segment(2, 4_790, 5_510))
        val silence = listOf(AudibleSilence(0, 4_283))
        assertEquals(4_283L, hizbOpeningSeekMs(true, 0L, nisa148, silence))
        assertNull(hizbOpeningSeekMs(true, 4_300L, nisa148, silence))
        assertNull(hizbOpeningSeekMs(false, 0L, nisa148, silence))
    }

    @Test
    fun `only murattal skips silence`() {
        assertTrue(silenceSkipAllowed("Murattal"))
        assertFalse(silenceSkipAllowed("Mujawwad"))
        assertFalse(silenceSkipAllowed("Muallim"))
    }

    @Test
    fun `the first word highlights when the voice starts`() {
        // Hani 4:148. Click at 1951, speech at 4283, word ends at 4790.
        val verse = listOf(Segment(1, 1_951, 4_790), Segment(2, 4_790, 5_510))
        val silence = listOf(AudibleSilence(0, 4_283))
        val voice = firstWordHighlightMs(verse, silence)
        assertEquals(4_283L, voice)
        assertEquals(4_790L - 4_283L, verse[1].startMs - voice)
        assertNull(
            openingWashStartMs(
                startMs = 1_951,
                position = 1,
                segments = verse,
                positionMs = 2_000,
                voiceMs = voice,
            ),
        )
        assertEquals(
            4_283L,
            openingWashStartMs(
                startMs = 1_951,
                position = 1,
                segments = verse,
                positionMs = 4_300,
                voiceMs = voice,
            ),
        )
        // Word 2 keeps its stored start.
        assertEquals(
            4_790L,
            openingWashStartMs(
                startMs = 4_790,
                position = 2,
                segments = verse,
                positionMs = 4_800,
                voiceMs = voice,
            ),
        )
    }

    @Test
    fun `a word clock that already meets the voice stays`() {
        // Hani 4:58. Speech and the first word meet.
        val verse = listOf(Segment(1, 5_600, 7_080))
        assertEquals(5_600L, firstWordHighlightMs(verse, emptyList()))
        assertEquals(
            5_600L,
            openingWashStartMs(
                startMs = 5_600,
                position = 1,
                segments = verse,
                positionMs = 5_700,
                voiceMs = 5_600,
            ),
        )
    }

    @Test
    fun `a voice that returns in a later word leaves the first word clock`() {
        val verse = listOf(Segment(1, 40, 890), Segment(2, 900, 4_000))
        val silence = listOf(AudibleSilence(0, 3_350))
        assertEquals(40L, firstWordHighlightMs(verse, silence))
    }

    @Test
    fun `the silence table ignores a torn line`() {
        val parsed = parseAudibleSilence(
            """
            # comment
            4 148 0 4283
            bad
            4 88 19360 20560
            4 88 1 1

            """.trimIndent(),
        )
        assertEquals(listOf(AudibleSilence(0, 4_283)), parsed[silenceKey(4, 148)])
        assertEquals(listOf(AudibleSilence(19_360, 20_560)), parsed[silenceKey(4, 88)])
    }
}
