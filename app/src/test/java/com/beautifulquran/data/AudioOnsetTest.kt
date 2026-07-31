package com.beautifulquran.data

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.domain.OutputLatency
import com.beautifulquran.ui.reader.InkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOnsetTest {

    @Test
    fun `saved zero-based edit stays faded until immutable audio onset`() {
        val edited = listOf(
            Segment(position = 1, startMs = 0, endMs = 915),
            Segment(position = 2, startMs = 915, endMs = 1_635),
            Segment(position = 3, startMs = 1_635, endMs = 2_765),
            Segment(position = 4, startMs = 2_765, endMs = 3_665),
        )
        val bundled = listOf(
            Segment(position = 1, startMs = 1_179, endMs = 1_650),
            Segment(position = 2, startMs = 1_650, endMs = 2_370),
            Segment(position = 3, startMs = 2_370, endMs = 3_500),
            Segment(position = 4, startMs = 3_500, endMs = 4_400),
        )

        val aligned = alignToAudioClock(
            edited, bundled, onsetMs = 1_179, migrateWholeRow = true
        )
        assertEquals(
            bundled,
            aligned,
        )

        val duringSilence = OutputLatency.highlightMs(
            mediaPositionMs = 1_100,
            latencyMs = 0,
            leadMs = 114,
            leadNotBeforeMs = aligned.first().startMs,
        )
        assertNull(HighlightEngine.activeWord(aligned, duringSilence))
        assertEquals(
            InkEngine.State.Upcoming,
            InkEngine.wordState(
                position = 1,
                activeWord = null,
                isActiveAyah = true,
                dimmed = false,
            ),
        )

        val voiceStart = OutputLatency.highlightMs(
            mediaPositionMs = 1_179,
            latencyMs = 0,
            leadMs = 114,
            leadNotBeforeMs = aligned.first().startMs,
        )
        assertEquals(1, HighlightEngine.activeWord(aligned, voiceStart))
    }

    @Test
    fun `edit already after voice onset is unchanged`() {
        val bundled = listOf(
            Segment(position = 1, startMs = 1_179, endMs = 1_650),
            Segment(position = 2, startMs = 1_650, endMs = 2_370),
            Segment(position = 3, startMs = 2_370, endMs = 3_500),
            Segment(position = 4, startMs = 3_500, endMs = 4_400),
        )
        val edited = bundled.toMutableList().apply {
            this[1] = this[1].copy(startMs = 1_700)
        }

        assertEquals(
            edited,
            alignToAudioClock(
                edited, bundled, onsetMs = 1_179, migrateWholeRow = false
            ),
        )
    }

    @Test
    fun `previous onset-only migration is corrected across every word`() {
        val onsetOnly = listOf(
            Segment(position = 1, startMs = 1_179, endMs = 2_094),
            Segment(position = 2, startMs = 2_094, endMs = 2_814),
            Segment(position = 3, startMs = 2_814, endMs = 3_944),
            Segment(position = 4, startMs = 3_944, endMs = 4_844),
        )
        val bundled = listOf(
            Segment(position = 1, startMs = 1_179, endMs = 1_650),
            Segment(position = 2, startMs = 1_650, endMs = 2_370),
            Segment(position = 3, startMs = 2_370, endMs = 3_500),
            Segment(position = 4, startMs = 3_500, endMs = 4_400),
        )

        assertEquals(
            bundled,
            alignToAudioClock(
                onsetOnly, bundled, onsetMs = 1_179, migrateWholeRow = true
            ),
        )
    }

    @Test
    fun `repeat occurrences do not distort the whole row clock offset`() {
        val edited = listOf(
            Segment(1, 0, 915),
            Segment(2, 915, 1_635),
            Segment(3, 1_635, 2_765),
            Segment(2, 4_000, 4_500),
            Segment(3, 4_500, 5_000),
            Segment(4, 5_000, 5_900),
        )
        val bundled = edited.map {
            it.copy(startMs = it.startMs + 735, endMs = it.endMs + 735)
        }.toMutableList().apply {
            this[0] = first().copy(startMs = 1_179)
        }

        assertEquals(
            bundled,
            alignToAudioClock(
                edited, bundled, onsetMs = 1_179, migrateWholeRow = true
            ),
        )
    }

    @Test
    fun `two and three word rows migrate every boundary to the current clock`() {
        val onsetOnly = listOf(
            Segment(1, 1_179, 2_094),
            Segment(2, 2_094, 2_814),
            Segment(3, 2_814, 3_944),
        )
        val bundled = listOf(
            Segment(1, 1_179, 1_650),
            Segment(2, 1_650, 2_370),
            Segment(3, 2_370, 3_500),
        )

        for (size in 2..3) {
            assertEquals(
                bundled.take(size),
                alignToAudioClock(
                    onsetOnly.take(size),
                    bundled.take(size),
                    onsetMs = 1_179,
                    migrateWholeRow = true,
                ),
            )
        }
    }

    @Test
    fun `opening floor cannot invert a short edited first segment`() {
        val edited = listOf(
            Segment(1, 0, 500),
            Segment(2, 1_650, 2_370),
            Segment(3, 2_370, 3_500),
        )
        val bundled = listOf(
            Segment(1, 1_179, 1_650),
            Segment(2, 1_650, 2_370),
            Segment(3, 2_370, 3_500),
        )

        assertEquals(
            bundled,
            alignToAudioClock(
                edited, bundled, onsetMs = 1_179, migrateWholeRow = false
            ),
        )
    }

    @Test
    fun `bad first end cannot move a correct second word`() {
        val edited = listOf(
            Segment(1, 0, 200),
            Segment(2, 1_650, 2_370),
        )
        val bundled = listOf(
            Segment(1, 1_179, 1_650),
            Segment(2, 1_650, 2_370),
        )

        assertEquals(
            bundled,
            alignToAudioClock(
                edited, bundled, onsetMs = 1_179, migrateWholeRow = false
            ),
        )
    }

    @Test
    fun `opening floor keeps starts ordered when word two also predates speech`() {
        val edited = listOf(
            Segment(1, 0, 400),
            Segment(2, 800, 1_500),
            Segment(3, 1_500, 2_200),
        )
        val bundled = listOf(
            Segment(1, 1_179, 1_650),
            Segment(2, 1_650, 2_370),
            Segment(3, 2_370, 3_500),
        )

        assertEquals(
            listOf(
                Segment(1, 1_179, 1_579),
                Segment(2, 1_979, 2_679),
                Segment(3, 2_679, 3_379),
            ),
            alignToAudioClock(
                edited, bundled, onsetMs = 1_179, migrateWholeRow = false
            ),
        )
    }

    @Test
    fun `a migrated row is written back once and never migrates again`() {
        val legacy = listOf(
            Segment(1, 0, 915),
            Segment(2, 915, 1_635),
            Segment(3, 1_635, 2_765),
        )
        val bundled = listOf(
            Segment(1, 1_179, 1_650),
            Segment(2, 1_650, 2_370),
            Segment(3, 2_370, 3_500),
        )

        // What QuranRepository.timings() stores back after the one migration.
        val migrated = alignToAudioClock(
            legacy, bundled, onsetMs = 1_179, migrateWholeRow = true
        )
        assertEquals(bundled, migrated)
        assertEquals(
            migrated,
            alignToAudioClock(
                migrated, bundled, onsetMs = 1_179, migrateWholeRow = false
            ),
        )
    }
}
