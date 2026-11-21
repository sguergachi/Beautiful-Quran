package com.beautifulquran.ui.reader

import androidx.compose.runtime.mutableStateOf
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The active-word wash both [AyahBlock] and the mushaf build through
 * [rememberAyahWordWash]. Values were captured from the two inline copies
 * this replaced (ee074d9e2), so a drift here is a drift in the ink.
 */
class AyahWordWashTest {
    /** 3:8, which has a short-word lead (بَعۡدَ → إِذۡ) and a wasl (مِن → لَّدُنكَ). */
    private val ayah = Ayah(
        surahId = 3, number = 8, text = "", translation = "", words = listOf(
            "رَبَّنَا", "لَا", "تُزِغۡ", "قُلُوبَنَا", "بَعۡدَ", "إِذۡ", "هَدَيۡتَنَا", "وَهَبۡ", "لَنَا",
            "مِن", "لَّدُنكَ", "رَحۡمَةًۚ", "إِنَّكَ", "أَنتَ", "ٱلۡوَهَّابُ",
        ).mapIndexed { i, arabic -> Word(i + 1, arabic, "", "") },
    )
    private val full = mutableStateOf(1f)
    private val outgoing = mutableStateOf(0.4f)
    private val lead = mutableStateOf(0.6f)

    private val baadh = ActiveWord(ayah = 8, wordPosition = 5, durationMs = 579, nextWordPosition = 6, nextDurationMs = 503)

    private fun leadFor(
        word: ActiveWord = baadh,
        activeIndex: Int = 4,
        speed: Float = 1f,
        enabled: Boolean = true,
    ) = outgoingShortWordLead(
        words = ayah.words,
        activeIndex = activeIndex,
        activeWord = word,
        sweepMs = InkEngine.sweepMs(word, speed),
        activePaced = false,
        connected = false,
        playbackSpeed = speed,
        enabled = enabled,
    )

    @Test
    fun `a short next word borrows the active word's tail`() {
        assertEquals(InkEngine.ShortWordLead(202, 0.28652483f, 0.27486062f), leadFor())
        assertEquals(InkEngine.ShortWordLead(135, 0.28723404f, 0.27561447f), leadFor(speed = 1.5f))
    }

    @Test
    fun `no short-word lead for English, a tap, a backtrack or the verse's last word`() {
        assertNull(leadFor(enabled = false))
        assertNull(leadFor(word = baadh.copy(fromTap = true)))
        assertNull(leadFor(word = baadh.copy(nextWordPosition = 3)))
        assertNull(leadFor(word = baadh.copy(wordPosition = 15, nextWordPosition = 16), activeIndex = 14))
    }

    @Test
    fun `pacing follows the word the ayah owns`() {
        fun pacing(position: Int) =
            activeWordPacing(ayah.words, ActiveWord(ayah = 8, wordPosition = position, durationMs = 1200, spokenMs = 900))
        val paced = (1..15).filter { pacing(it) != null }
        assertEquals(listOf(11, 13, 15), paced)
        // The verse-closing word keeps its waqf hold.
        val last = requireNotNull(pacing(15))
        assertEquals(5, last.letterCount)
        assertTrue(last.hasStrongHold)
        assertEquals(0.43256256f, last.at(0.25f), 0f)
        assertEquals(0.68982965f, last.at(0.5f), 0f)
        assertEquals(1f, last.at(0.75f), 0f)
        assertEquals(0.90038866f, last.atLetters(0.5f), 0f)
        val ladunka = requireNotNull(pacing(11))
        assertEquals(4, ladunka.letterCount)
        assertFalse(ladunka.hasStrongHold)
        assertEquals(0.5296933f, ladunka.at(0.5f), 0f)
        assertNull(activeWordPacing(ayah.words, null))
        assertNull(activeWordPacing(ayah.words, ActiveWord(ayah = 8, wordPosition = 99, durationMs = 1200)))
    }

    @Test
    fun `a carried wasl starts the main wash where the bloom reached`() {
        val min = requireNotNull(InkEngine.connection(ayah.words[9].arabic, ayah.words[10].arabic))
        assertEquals(0.25f, min.prefixFraction, 0f)
        val feather = InkEngine.tuning.washFeather
        assertEquals(0.23076923f, activeWashRevealStart(true, min, 0.8f, feather, 0f), 0f)
        assertEquals(0.28446805f, activeWashRevealStart(true, min, 0.8f, InkEngine.pacedFeather(), 0f), 0f)
        // Not carried (a seek), or no connection: only a short-word lead's reach.
        assertEquals(0.21f, activeWashRevealStart(false, min, 0.8f, feather, 0.21f), 0f)
        assertEquals(0.21f, activeWashRevealStart(true, null, 0.8f, feather, 0.21f), 0f)
    }

    private fun prefixes(
        activeIndex: Int,
        carried: Boolean = false,
        incoming: Boolean = false,
        outgoingConn: Boolean = false,
        handoff: Float = 0f,
        revealStart: Float = 0f,
        leadReach: Float = 0f,
        outgoingLead: InkEngine.ShortWordLead? = null,
    ): List<WaslPrefix?> {
        val min = InkEngine.connection(ayah.words[9].arabic, ayah.words[10].arabic)
        return ayahWaslPrefixes(
            wordCount = ayah.words.size,
            activeIndex = activeIndex,
            carriedIncoming = carried,
            incomingConnection = min.takeIf { incoming },
            outgoingConnection = min.takeIf { outgoingConn },
            incomingHandoff = handoff,
            activeRevealStart = revealStart,
            incomingLeadReach = leadReach,
            outgoingLead = outgoingLead,
            mainFeather = InkEngine.tuning.washFeather,
            fullWindow = full,
            outgoingWindow = outgoing,
            leadWindow = lead,
        )
    }

    private fun List<WaslPrefix?>.only(index: Int): WaslPrefix {
        assertEquals(listOf(index), indices.filter { this[it] != null })
        return requireNotNull(this[index])
    }

    @Test
    fun `wasl bloom on the next word rides the outgoing window`() {
        val p = prefixes(activeIndex = 9, outgoingConn = true).only(10)
        assertSame(outgoing, p.windowProgress)
        assertEquals(0.28846154f, p.endProgress, 0f)
        assertEquals(1.6f, p.feather, 0f)
        assertEquals(0.25f, p.letterEndProgress, 0f)
    }

    @Test
    fun `a carried wasl redraws its reached edge on the full window`() {
        val p = prefixes(activeIndex = 10, carried = true, incoming = true, handoff = 0.8f, revealStart = 0.23076923f).only(10)
        assertSame(full, p.windowProgress)
        assertEquals(0.23076923f, p.endProgress, 0f)
        assertEquals(1.6f, p.feather, 0f)
        assertEquals(0.2f, p.letterEndProgress, 0f)
    }

    @Test
    fun `a short-word lead blooms on the lead window, then holds its reach`() {
        val reach = 0.27486062f
        val lending = prefixes(activeIndex = 4, outgoingLead = leadFor()).only(5)
        assertSame(lead, lending.windowProgress)
        assertEquals(reach, lending.endProgress, 0f)
        assertEquals(InkEngine.tuning.washFeather, lending.feather, 0f)
        assertEquals(reach, lending.letterEndProgress, 0f)
        val ledIn = prefixes(activeIndex = 5, carried = true, revealStart = reach, leadReach = reach).only(5)
        assertSame(full, ledIn.windowProgress)
        assertEquals(reach, ledIn.endProgress, 0f)
        assertEquals(reach, ledIn.letterEndProgress, 0f)
    }

    @Test
    fun `no active word carries nothing`() {
        assertTrue(prefixes(activeIndex = -1).all { it == null })
        assertEquals(ayah.words.size, prefixes(activeIndex = -1).size)
    }
}
