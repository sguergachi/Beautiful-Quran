package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MushafLocationWordTest {
    private val last = ActiveWord(ayah = 29, wordPosition = 17, durationMs = 500, activation = 8)

    @Test
    fun `the silent tail keeps its last heard location`() {
        assertEquals(last, mushafLocationWord(null, last, 29, 8))
    }

    @Test
    fun `a playlist advance or lost playback releases the old location`() {
        assertNull(mushafLocationWord(null, last, 30, 8))
        assertNull(mushafLocationWord(null, last, null, 8))
    }

    @Test
    fun `a seek or repeat of the same ayah cannot inherit its old tail`() {
        assertNull(mushafLocationWord(null, last, 29, 9))
        assertNull(mushafLocationWord(null, last.copy(fromTap = true), 29, 8))
    }

    @Test
    fun `the last word keeps its player generation when a tap pins its wash generation`() {
        val polled = ActiveWord(ayah = 29, wordPosition = 17, durationMs = 500, activation = 9)
        val shown = WordTapInk(last).resolve(polled)!!
        assertEquals(8, shown.activation)
        assertEquals(9, shown.playbackActivation)
        assertEquals(shown, mushafLocationWord(null, shown, 29, 9))
        assertNull(mushafLocationWord(null, shown, 29, 10))
    }

    @Test
    fun `the live word still owns the location during a seek handoff`() {
        val current = last.copy(wordPosition = 2, activation = 9, playbackActivation = 9, fromTap = true)
        assertEquals(current, mushafLocationWord(current, last, 29, 8))
    }
}
