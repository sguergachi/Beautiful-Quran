package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class MushafInkFrontierTest {
    @Test
    fun `completed verses never recess during the 24 29 to 30 handoff`() {
        // Media3, the word poll and the fade-led focus publish independently.
        val moments = listOf(
            Moment(29, 30, 29, true),
            Moment(null, 30, 29, true),
            Moment(null, null, 29, true),
            Moment(null, 29, 30, false),
            Moment(null, null, 30, false),
            Moment(30, 30, 30, true),
        )
        moments.forEach { moment ->
            (25..28).forEach { ayah ->
                assertEquals("$moment ayah=$ayah", MushafInkPackKind.STATIC, kind(ayah, moment))
            }
            assertEquals("$moment", MushafInkPackKind.ACTIVE_WORD, kind(29, moment, drying = true))
            assertEquals(
                "$moment",
                if (moment.word == 30) MushafInkPackKind.ACTIVE_WORD else MushafInkPackKind.UPCOMING,
                kind(30, moment),
            )
            assertEquals("$moment", MushafInkPackKind.UPCOMING, kind(31, moment))
        }
    }

    @Test
    fun `a repeat loop covers later verses before its first word arrives`() {
        val moment = Moment(null, 31, 29, false)
        (29..32).forEach { ayah ->
            assertEquals(MushafInkPackKind.UPCOMING, kind(ayah, moment, drying = true))
        }
    }

    @Test
    fun `the word retains its wash while the playlist moves ahead or rewinds`() {
        listOf(29, 31).forEach { playing ->
            assertEquals(30, mushafInkFrontier(30, playing, playing))
            assertEquals(MushafInkPackKind.ACTIVE_WORD, kind(30, Moment(30, playing, playing, false)))
        }
    }

    private data class Moment(val word: Int?, val focus: Int?, val playing: Int, val hasWord: Boolean)

    private fun kind(ayah: Int, moment: Moment, drying: Boolean = false): MushafInkPackKind {
        val frontier = mushafInkFrontier(moment.word, moment.focus, moment.playing)
        return mushafInkPackKind(
            pageOwnsVoice = true,
            ayah = ayah,
            activeWordAyah = moment.word,
            frontierAyah = frontier,
            basmalahActive = false,
            hasSearchFlash = false,
            frontierWaitingForFirstWord = moment.word == null &&
                (frontier != moment.playing || !moment.hasWord),
            drying = drying,
        )
    }
}
