package com.beautifulquran.ui.reader

import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.EnglishLeafVerse
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English leaf blooms one word at a time, as the scrolling reader does. The
 * three bands are how a sentence says which word that is — see
 * `englishVerseBlooms`.
 */
class EnglishWashBandsTest {

    /** A sentence occupying characters 10..109 of the paragraph. */
    private val sentence = 10..109

    @Test
    fun `iqamah first pass and Alafasy repeat use the performance ink range`() {
        val (_, text, glosses) = javaClass.getResourceAsStream("/english-prayer-alignment.tsv")!!
            .bufferedReader().use { it.readLines().first { row -> row.startsWith("24:37\t") } }.split('\t')
        val alignment = EnglishWordAlignment.align(text, glosses.split('|'))!!
        val spans = englishOwnedSpans(text.indices, alignment, text = text, forwardWash = false)
        val performance = spans.single { text.indexOf("performance") in it.range }
        val prayer = spans.single { text.indexOf("prayer") in it.range }
        // Shipped Alafasy 24:37, including the return from prayer to iqamah.
        val timings = HighlightEngine.PreparedTimings.prepare(listOf(
            Segment(9, 9050, 10040), Segment(10, 10040, 11160), Segment(11, 11160, 13520),
            Segment(10, 13520, 15300), Segment(11, 15300, 16620), Segment(12, 16620, 19630),
        ))
        assertEquals(10, performance.position)
        assertEquals(11, prayer.position)
        for (clock in listOf(10500L, 14000L)) {
            val heard = timings.activeInfo(clock)!!
            val word = ActiveWord(
                ayah = 37, wordPosition = heard.position, durationMs = heard.endMs - heard.startMs,
                activation = heard.startMs, highWater = heard.highWater,
                isRepeat = heard.isRepeat, repeatStart = heard.repeatStart,
            )
            val ink = InkEngine.word(performance.position, word, isActiveAyah = true, dimmed = false)
            assertEquals(InkEngine.State.Active, ink.state)
            assertEquals(heard.isRepeat, ink.repeat)
            assertTrue(!InkEngine.inRepeatChain(prayer.position, word))
            val lit = englishOwnedSpans(text.indices, alignment, text = text)
                .filter { it.position <= heard.highWater }.flatMap { it.range.toList() }
            assertEquals(text.indices.take(lit.last() + 1), lit)
        }
    }

    @Test
    fun `stale glow cannot survive ahead of a backward seek but repeat history can`() {
        fun visible(word: ActiveWord, fadingPositions: List<Int>) = fadingPositions.filter { position ->
            englishInkLayerAllowed(InkEngine.word(position, word, isActiveAyah = true, dimmed = false).state)
        }
        val seek = ActiveWord(ayah = 31, wordPosition = 5, durationMs = 100, activation = 1)
        assertEquals(listOf(5), visible(seek, listOf(5, 27)))
        val repeat = seek.copy(wordPosition = 34, highWater = 35, isRepeat = true, repeatStart = 34)
        assertEquals(listOf(34, 35), visible(repeat, listOf(34, 35, 37)))
    }

    @Test
    fun `a later spoken meaning fills every intervening English word`() {
        val reported = javaClass.getResourceAsStream("/english-alignment.tsv")!!.bufferedReader()
            .use { it.readLines().first { line -> line.startsWith("24:31\t") } }.split('\t')
        for ((text, glosses) in listOf(
            "Allah has set a seal" to listOf("has set a seal", "Allah"),
            "their husbands' fathers, their husbands' sons" to
                listOf("fathers", "of their husbands", "sons", "of their husbands"),
            reported[1] to reported[2].split('|'),
        )) {
            val alignment = EnglishWordAlignment.align(text, glosses)!!
            val spans = englishOwnedSpans(text.indices, alignment, text = text)
            for (position in glosses.indices) {
                val lit = spans.filter { it.position <= position + 1 }.flatMap { it.range.toList() }
                assertEquals("$text at Arabic word ${position + 1}",
                    text.indices.take((lit.lastOrNull() ?: -1) + 1), lit)
            }
        }
    }

    @Test
    fun `a reordered wash remains continuous when asides are hidden and a verse is carried`() {
        val source = "a [very] painful punishment"
        val alignment = EnglishWordAlignment.align(source, listOf("a punishment", "painful"))!!
        val whole = EnglishLeafVerse(2, 10, "a painful punishment", source = source)
        val spans = englishOwnedSpans(whole.text.indices, alignment, whole::fragmentInkProgress, whole.text)
        assertEquals(whole.text.indices, spans.single().range)
        assertEquals(1, spans.single().position)
    }

    @Test
    fun `ownership projects onto printed ranges without restoring English order`() {
        val text = "Allah has set a seal"
        val alignment = EnglishWordAlignment.align(text, listOf("has set a seal", "Allah"))!!
        val spans = englishOwnedSpans(text.indices, alignment, text = text, forwardWash = false)
        assertEquals(listOf(2, 1), spans.map { it.position })
        assertEquals(text.indices.toList(), spans.flatMap { it.range.toList() })
        assertEquals("Allah", text.substring(spans.first().range))
        assertEquals(" has set a seal", text.substring(spans.last().range))
    }

    @Test
    fun `ownership survives removed asides and a carried fragment`() {
        val source = "a [very] painful punishment"
        val alignment = EnglishWordAlignment.align(source, listOf("a punishment", "painful"))!!
        val whole = EnglishLeafVerse(2, 10, "a painful punishment", source = source)
        val spans = englishOwnedSpans(whole.text.indices, alignment, whole::fragmentInkProgress, whole.text, forwardWash = false)
        val punishment = spans.first { it.position == 1 && whole.text.substring(it.range).contains("punishment") }
        assertTrue(whole.text.substring(punishment.range).contains("punishment"))
        val carried = EnglishLeafVerse(
            2, 10, "punishment", from = source.indexOf("punishment"), to = source.length,
            verseLength = source.length, textFrom = source.indexOf("punishment"), source = source,
        )
        val overleaf = englishOwnedSpans(carried.text.indices, alignment, carried::fragmentInkProgress, carried.text)
        assertEquals(listOf(1), overleaf.map { it.position })
        assertEquals(carried.text.indices, overleaf.single().range)
    }

    @Test
    fun `the bands tile the sentence with no gap and no overlap`() {
        val bands = englishWashBands(sentence, from = 0.3f, to = 0.4f, unread = 0.4f)
        assertEquals(10 until 40, bands.read)
        assertEquals(40 until 50, bands.saying)
        assertEquals(50..109, bands.ahead)
        // Every character of the sentence is in exactly one band.
        val covered = bands.read.toList() + bands.saying.toList() +
            bands.retained.toList() + bands.ahead.toList()
        assertEquals(sentence.toList(), covered)
    }

    @Test
    fun `the first word opens the sentence and the last closes it`() {
        val opening = englishWashBands(sentence, from = 0f, to = 0.1f, unread = 0.1f)
        assertTrue(opening.read.isEmpty())
        assertEquals(10 until 20, opening.saying)

        val closing = englishWashBands(sentence, from = 0.9f, to = 1f, unread = 1f)
        assertEquals(100..109, closing.saying)
        assertTrue(closing.ahead.isEmpty())
    }

    @Test
    fun `a word with no English of its own blooms nothing`() {
        // The alignment collapses two Arabic words onto one English span where
        // the translation has no separate words for them. The second gets an
        // empty middle band — nothing to bloom — and the split stays put.
        val bands = englishWashBands(sentence, from = 0.5f, to = 0.5f, unread = 0.5f)
        assertTrue(bands.saying.isEmpty())
        assertEquals(10 until 60, bands.read)
        assertEquals(60..109, bands.ahead)
    }

    @Test
    fun `the half of a carried verse the voice is not on needs no special case`() {
        // Fragment progress clamps, so a voice before this leaf's half comes
        // out all ahead, and a voice past it comes out all read.
        val before = englishWashBands(sentence, from = 0f, to = 0f, unread = 0f)
        assertTrue(before.read.isEmpty())
        assertTrue(before.saying.isEmpty())
        assertEquals(sentence, before.ahead)

        val after = englishWashBands(sentence, from = 1f, to = 1f, unread = 1f)
        assertEquals(sentence, after.read)
        assertTrue(after.saying.isEmpty())
        assertTrue(after.ahead.isEmpty())
    }

    @Test
    fun `bands never run backwards, whatever they are handed`() {
        val bands = englishWashBands(sentence, from = 0.8f, to = 0.2f, unread = 0.2f)
        assertTrue("saying must not start before it ends", bands.saying.isEmpty())
        assertEquals(10 until 90, bands.read)
        assertEquals(90..109, bands.ahead)
        // Out of range fractions are clamped rather than read off the end.
        val wild = englishWashBands(sentence, from = -3f, to = 9f, unread = 9f)
        assertTrue(wild.read.isEmpty())
        assertEquals(sentence, wild.saying)
        assertTrue(wild.ahead.isEmpty())
    }

    @Test
    fun `a band edge is never left in the middle of a word`() {
        // The bands abut, so an edge inside a word is a hard cut down a letter.
        val prose = "one two three four five six seven eight nine ten"
        val whole = prose.indices.first..prose.indices.last
        for (cut in 1 until prose.length) {
            val bands = englishWashBands(
                range = whole,
                from = cut.toFloat() / prose.length,
                to = 1f,
                unread = 1f,
                text = prose,
            )
            val edge = bands.saying.first
            if (edge <= 0 || edge > prose.lastIndex) continue
            assertTrue(
                "edge $edge splits '${prose.substring(edge - 1, edge + 1)}'",
                !prose[edge - 1].isLetter() || !prose[edge].isLetter(),
            )
        }
    }

    @Test
    fun `a bracket goes with the word it closes, not the word after it`() {
        // A repeat on "that" tinted the "]" of "[think]" orange: the alignment
        // ends "think" at its last letter, and the bracket fell to "that".
        val prose = "created and [think] that perhaps"
        val whole = prose.indices.first..prose.indices.last
        val thinkEnd = prose.indexOf("]").toFloat() / prose.length
        val thatEnd = (prose.indexOf("that") + 4).toFloat() / prose.length
        val bands = englishWashBands(whole, thinkEnd, thatEnd, thatEnd, prose)
        assertEquals("created and [think]", prose.substring(bands.read))
        assertEquals(" that", prose.substring(bands.saying))
    }

    @Test
    fun `an opening bracket and closing punctuation keep to their own words`() {
        val prose = "leave [the company of] those, who"
        val whole = prose.indices.first..prose.indices.last
        // An edge between "[" and "the" goes back before the bracket.
        val opening = englishWashBands(whole, 7f / prose.length, 1f, 1f, prose)
        assertEquals("leave ", prose.substring(opening.read))
        // An edge after "those" takes its comma with it.
        val at = (prose.indexOf("those") + 5).toFloat() / prose.length
        val comma = englishWashBands(whole, at, 1f, 1f, prose)
        assertEquals("leave [the company of] those,", prose.substring(comma.read))
    }

    @Test
    fun `a mark joining two words leaves the edge where it is`() {
        val prose = "a well-known way"
        val whole = prose.indices.first..prose.indices.last
        val at = prose.indexOf("-").toFloat() / prose.length
        val bands = englishWashBands(whole, at, 1f, 1f, prose)
        assertEquals("a well", prose.substring(bands.read))
    }

    @Test
    fun `an edge already between words is left where it is`() {
        val prose = "one two three"
        val whole = prose.indices.first..prose.indices.last
        // Character 3 is the space after "one".
        val bands = englishWashBands(whole, 3f / prose.length, 1f, 1f, prose)
        assertEquals(0 until 3, bands.read)
        assertEquals("one", prose.substring(bands.read))
    }

    @Test
    fun `a reciter going back over a phrase does not take its ink away`() {
        // The voice is at 0.3, but it has already said as far as 0.7 once —
        // so only what is past 0.7 is paper. Everything between the word being
        // said and there keeps the ink it was given.
        val bands = englishWashBands(sentence, from = 0.3f, to = 0.4f, unread = 0.7f)
        assertEquals(10 until 40, bands.read)
        assertEquals(40 until 50, bands.saying)
        assertEquals(50 until 80, bands.retained)
        assertEquals(80..109, bands.ahead)
    }

    @Test
    fun `at the voice's own furthest point nothing is retained`() {
        // Which is nearly always: the band exists only for a backtrack.
        val bands = englishWashBands(sentence, from = 0.3f, to = 0.4f, unread = 0.4f)
        assertTrue(bands.retained.isEmpty())
        assertEquals(50..109, bands.ahead)
    }

    @Test
    fun `an unread mark before the voice cannot pull the paper backwards`() {
        val bands = englishWashBands(sentence, from = 0.3f, to = 0.4f, unread = 0.1f)
        assertTrue(bands.retained.isEmpty())
        assertEquals(50..109, bands.ahead)
    }

    @Test
    fun `an empty sentence has no bands to draw`() {
        val bands = englishWashBands(IntRange.EMPTY, from = 0f, to = 1f, unread = 1f)
        assertTrue(bands.read.isEmpty())
        assertTrue(bands.saying.isEmpty())
        assertTrue(bands.ahead.isEmpty())
    }
}
