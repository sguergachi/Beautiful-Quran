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
        )

        val aligned = alignToAudioOnset(edited, onsetMs = 1_179)
        assertEquals(
            listOf(
                Segment(position = 1, startMs = 1_179, endMs = 2_094),
                Segment(position = 2, startMs = 2_094, endMs = 2_814),
            ),
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
        val edited = listOf(Segment(position = 1, startMs = 1_300, endMs = 2_000))

        assertEquals(edited, alignToAudioOnset(edited, onsetMs = 1_179))
    }
}
