package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.EnglishPhraseWord
import com.beautifulquran.domain.EnglishWordSpan
import com.beautifulquran.domain.englishPhraseSpans
import com.beautifulquran.domain.EnglishVerseRun
import com.beautifulquran.domain.englishBookOf
import com.beautifulquran.domain.HighlightEngine
import com.beautifulquran.domain.HighlightItem
import com.beautifulquran.domain.HighlightPlaybackSample
import com.beautifulquran.domain.HighlightSync
import com.beautifulquran.domain.HighlightTimingContext
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.runtime.mutableStateOf

class EnglishPhrasePacingTest {
    private val row = javaClass.getResourceAsStream("/english-glorification-owners.tsv")!!
        .bufferedReader().use { it.readLines().first { row -> row.startsWith("64:1\t") } }.split('\t')
    private val text = row[1]
    private val alignment = EnglishWordAlignment.align(text, row[2].split('|'), row[4].split('|'))!!
    private val hani = listOf(
        Segment(1, 1020, 2140), Segment(2, 2140, 3150), Segment(3, 3150, 3450),
        Segment(4, 3450, 3660), Segment(5, 3660, 5090), Segment(6, 5090, 5750),
        Segment(7, 5750, 5950), Segment(8, 5950, 6770), Segment(9, 6770, 7150),
        Segment(10, 7150, 8060), Segment(11, 8060, 8590), Segment(12, 8590, 9730),
        Segment(13, 9730, 10370), Segment(14, 10370, 10870), Segment(15, 10870, 11570),
        Segment(16, 11570, 12890), Segment(17, 12890, 14625),
    )

    @Test
    fun `Hani opening stays on its reordered phrase until earth has been spoken`() {
        val plan = englishPhrasePlan(alignment, hani)
        assertEquals(0, plan.sourceOverlapFallbacks)
        assertEquals(6770L, plan.windows.getValue(1).endMs)
        assertEquals(73, (plan.spans.first().to * text.length).toInt())
        assertEquals(6770L, plan.windows.getValue(9).startMs)
        val through = englishWashThrough(plan, 2140)!!
        assertTrue("first Arabic word must not consume 13 English words", through * text.length < 20)
    }

    @Test
    fun `every opening word has an exposure window and semantic owners stay unchanged`() {
        val plan = englishPhrasePlan(alignment, hani)
        val words = Regex("\\S+").findAll(text.substring(0, 73)).toList()
        assertEquals(14, words.size)
        val exposure = words.map { word ->
            englishWordExposure(plan, word.range.first.toFloat() / text.length,
                (word.range.last + 1f) / text.length)!!.also {
                println("Hani64:1 ${word.value}: ${it.startMs}..${it.endMs}")
                assertTrue("${word.value} must have a real reveal", it.endMs > it.startMs)
            }
        }
        assertEquals(1020L, exposure.first().startMs)
        assertEquals(6770L, exposure.last().endMs)
        assertTrue(exposure.first().endMs in 1649L..1651L)
        assertTrue(exposure.last().startMs > 6300L)
        exposure.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
        assertEquals(1, alignment.positionAt((text.indexOf("exalting") + 3f) / text.length))
        assertEquals(5, alignment.positionAt((text.indexOf("heavens") + 3f) / text.length))
        var previous = 0f
        for (at in 0L..14625L step 7) {
            val edge = englishWashThrough(plan, at)!!
            assertTrue("continuous prefix cannot retreat at $at", edge >= previous)
            previous = edge
        }
    }

    private fun synthetic(owners: List<Int>, reliable: Set<Int> = owners.indices.toSet(), clauses: List<Int> = owners.map { 0 }): EnglishAlignment {
        val words = owners.mapIndexed { i, owner ->
            EnglishPhraseWord(i.toFloat() / owners.size, (i + 1f) / owners.size, owner, clauses[i], i in reliable)
        }
        return EnglishAlignment(FloatArray(owners.max()), words.map { EnglishWordSpan(it.from, it.to, it.position) },
            englishPhraseSpans(words))
    }

    @Test
    fun `weak auxiliary and next-clause owner cannot enlarge inversion closure`() {
        val aligned = synthetic(listOf(5, 8, 9, 1, 2, 9, 10), setOf(0, 1, 3, 4, 6), listOf(0, 0, 0, 0, 0, 1, 1))
        val phrase = aligned.presentationSpans.first()
        assertEquals(1, phrase.position)
        assertEquals(8, phrase.lastPosition)
        assertEquals(5f / 7, phrase.to, 0f)
        val tied = synthetic(listOf(2, 1, 2, 3)).presentationSpans
        assertEquals(2, tied.first().lastPosition)
        assertEquals(.75f, tied.first().to, 0f)
        // Reliable crossing evidence closes across punctuation; punctuation is
        // a grammar attachment fence, not permission to truncate a source span.
        assertEquals(1, synthetic(listOf(3, 1, 2), clauses = listOf(0, 1, 1)).presentationSpans.size)
    }

    @Test
    fun `lower leading grammar stays outside an inversion and its shared remainder stays inside`() {
        val aligned = synthetic(listOf(7, 8, 9, 12, 10, 11, 12, 13), setOf(3, 4, 7))
        val phrase = aligned.presentationSpans.single { it.lastPosition > it.position }
        assertEquals(3f / 8, phrase.from, 0f)
        assertEquals(7f / 8, phrase.to, 0f)
        assertEquals(10, phrase.position)
        assertEquals(12, phrase.lastPosition)
        val plan = englishPhrasePlan(aligned, (1..13).map { Segment(it, it * 1000L, (it + 1) * 1000L) })
        plan.spans.map { plan.windows.getValue(it.position) }.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endMs <= b.startMs)
        }
        assertEquals(7000L, plan.windows.getValue(7).startMs)
    }

    private fun fixture(key: String): Pair<String, EnglishAlignment> {
        val row = javaClass.getResourceAsStream("/english-phrase-pacing.tsv")!!.bufferedReader()
            .use { it.readLines().first { row -> row.startsWith("$key\t") } }.split('\t')
        return row[1] to EnglishWordAlignment.align(row[1], row[2].split('|'), row[3].split('|'), row[4].split('|'))!!
    }

    private fun fixtureTimings(key: String): List<Segment> =
        javaClass.getResourceAsStream("/english-phrase-timings.tsv")!!.bufferedReader()
            .use { it.readLines().first { row -> row.startsWith("1\t$key\t") } }.split('\t')[2]
            .split(';').map { encoded ->
                val (owner, start, end) = encoded.split(',').map(String::toLong)
                Segment(owner.toInt(), start, end)
            }

    @Test
    fun `Al Baqarah 123 trimmed inversion gap attaches to the preceding closed phrase`() {
        val (text, aligned) = fixture("2:123")
        val plan = englishPhrasePlan(aligned, fixtureTimings("2:123"))
        val phrase = aligned.presentationSpans.single { it.position == 10 }
        assertEquals(12, phrase.lastPosition)
        assertEquals(text.indexOf(" no compensation").toFloat(), phrase.from * text.length, .0001f)
        assertEquals(text.indexOf(" any intercession").toFloat(), phrase.to * text.length, .0001f)
        assertEquals(0, plan.sourceOverlapFallbacks)
        val legacy = EnglishWashPlan(aligned.washSpans, englishWashWindows(aligned.washSpans.map { it.position }, fixtureTimings("2:123")))
        for (word in Regex("\\p{L}+").findAll(text)) {
            val from = word.range.first.toFloat() / text.length
            val to = (word.range.last + 1f) / text.length
            val exposure = englishWordExposure(plan, from, to)!!
            assertTrue("${word.value} has positive exposure", exposure.endMs > exposure.startMs)
            val old = englishWordExposure(legacy, from, to)!!
            assertTrue("${word.value} had positive legacy exposure", old.endMs > old.startMs)
        }
    }

    @Test
    fun `Al Baqarah 175 weak next-clause overlap uses explicit legacy occurrence bands`() {
        val (text, aligned) = fixture("2:175")
        val semantic = aligned.spans.toList()
        val timings = fixtureTimings("2:175")
        assertTrue(aligned.presentationSpans.zipWithNext().any { (a, b) -> a.lastPosition >= b.position })
        val plan = englishPhrasePlan(aligned, timings)
        assertEquals(1, plan.sourceOverlapFallbacks)
        assertEquals(0, plan.repeatFallbacks)
        assertEquals(aligned.washSpans, plan.spans)
        assertEquals(7510L, plan.windows.getValue(7).startMs)
        assertEquals(12550L, plan.windows.getValue(7).endMs)
        assertEquals(10, plan.tailOwners.getValue(7))
        assertEquals(listOf(7510L..12550L), plan.windows.getValue(7).spoken)
        for (word in Regex("\\p{L}+").findAll(text).filter { it.range.first >= text.indexOf("How") }) {
            val exposure = englishWordExposure(plan, word.range.first.toFloat() / text.length,
                (word.range.last + 1f) / text.length)!!
            assertTrue("${word.value} must not be clamped to a spent source", exposure.endMs > exposure.startMs)
        }
        assertEquals(semantic, aligned.spans)
    }

    @Test
    fun `source overlap fallback keeps voiced gaps voice gate and occurrence fence`() {
        val aligned = synthetic(listOf(2, 1, 2, 4), setOf(0, 1, 3), listOf(0, 0, 1, 1))
        val plan = englishPhrasePlan(aligned, listOf(Segment(1, 0, 400), Segment(2, 500, 700),
            Segment(3, 1000, 1200), Segment(2, 1500, 1700), Segment(4, 2000, 2500)), 200)
        assertEquals(1, plan.sourceOverlapFallbacks)
        assertEquals(200L, plan.windows.getValue(1).startMs)
        val band = plan.windows.getValue(2)
        assertEquals(1500L, band.endMs)
        assertEquals(listOf(500L..700L, 1000L..1200L), band.spoken)
        assertEquals(.5f, band.progress(900), 0f)
        assertEquals(3, plan.tailOwners.getValue(2))
        assertEquals(2000L, plan.windows.getValue(4).startMs)
    }

    @Test
    fun `ordinary order retains word windows and isolated semantic gaps hold ink`() {
        val monotone = englishPhrasePlan(synthetic(listOf(1, 2, 3)),
            listOf(Segment(1, 0, 400), Segment(2, 1000, 1400), Segment(3, 2000, 2400)))
        assertEquals(listOf(1, 2, 3), monotone.spans.map { it.position })
        assertEquals(400L, monotone.windows.getValue(1).endMs)
        assertEquals(1000L, monotone.windows.getValue(2).startMs)
        assertEquals(1f / 3, englishWashThrough(monotone, 700)!!, 0f)
        val skipped = englishPhrasePlan(synthetic(listOf(3, 1, 2, 9)), hani)
        assertEquals(3450L, skipped.windows.getValue(1).endMs)
        assertEquals(.75f, englishWashThrough(skipped, 5000)!!, 0f)
        // A normal band owning only Allah ends with Allah, not owner9.
        val isolated = englishPhrasePlan(synthetic(listOf(2, 9)), hani)
        assertEquals(3150L, isolated.windows.getValue(2).endMs)
        assertEquals(.5f, englishWashThrough(isolated, 6000)!!, 0f)
    }

    @Test
    fun `word-card English retains legacy linear pacing through silence and voice gate`() {
        val aligned = synthetic(listOf(1, 2)).copy(phrases = null)
        val timings = listOf(Segment(1, 0, 400), Segment(2, 1000, 1400))
        val plan = englishPresentationPlan(aligned, timings)
        val legacy = englishWashWindows(aligned.washSpans.map { it.position }, timings)
        assertEquals(legacy, plan.windows)
        assertEquals(1000L, plan.windows.getValue(1).endMs)
        assertEquals(.5f, plan.windows.getValue(1).progress(500), 0f)
        assertEquals(.25f, englishWashThrough(plan, 500)!!, 0f)
        assertNull(plan.windows.getValue(1).spoken)
        val gated = englishPresentationPlan(aligned, timings, 200)
        assertEquals(200L, gated.windows.getValue(1).startMs)
        assertEquals(.5f, gated.windows.getValue(1).progress(600), 0f)
        assertEquals(600L, gated.windows.getValue(1).mediaAt(.5f))
        assertEquals(1, gated.tailOwners.getValue(1))
        assertEquals(2, gated.tailOwners.getValue(2))
        assertEquals(0, gated.sourceOverlapFallbacks)
        assertEquals(0, gated.repeatFallbacks)
    }

    @Test
    fun `phrase clock holds in silence and unions overlapping source intervals`() {
        val aligned = synthetic(listOf(2, 1, 3))
        val plan = englishPhrasePlan(aligned,
            listOf(Segment(1, 0, 400), Segment(2, 1000, 1400), Segment(3, 2000, 2400)))
        val clock = plan.windows.getValue(1)
        assertEquals(.5f, clock.progress(400), 0f)
        assertEquals(.5f, clock.progress(900), 0f)
        assertEquals(.75f, clock.progress(1200), 0f)
        assertEquals(400L, clock.mediaAt(.5f))
        assertEquals(1000L, clock.mediaAt(.5f, afterGap = true))
        val overlap = englishPhrasePlan(aligned,
            listOf(Segment(1, 0, 700), Segment(2, 500, 1000), Segment(3, 1500, 2000)))
        assertEquals(listOf(0L..1000L), overlap.windows.getValue(1).spoken)
        assertEquals(.5f, overlap.windows.getValue(1).progress(500), 0f)
    }

    @Test
    fun `repeat inside a merged block conservatively retains occurrence fences`() {
        val plan = englishPhrasePlan(synthetic(listOf(3, 1, 2, 4)),
            listOf(1, 2, 1, 2, 3, 4).mapIndexed { i, owner -> Segment(owner, i * 1000L, (i + 1) * 1000L) })
        assertEquals(1, plan.repeatFallbacks)
        assertEquals(2000L, plan.windows.getValue(2).endMs)
        assertEquals(.75f, englishWashThrough(plan, 2000)!!, 0f)
        assertEquals(.75f, englishWashThrough(plan, 3500)!!, 0f)
        val sameWord = englishPhrasePlan(synthetic(listOf(1, 2)),
            listOf(Segment(1, 0, 600), Segment(1, 1000, 1600), Segment(2, 2000, 2600)))
        assertEquals(600L, sameWord.windows.getValue(1).endMs)
        assertEquals(.5f, englishWashThrough(sameWord, 1600)!!, 0f)
    }

    @Test
    fun `terminal reordered phrase keeps its verse tail and carried cut`() {
        val row = javaClass.getResourceAsStream("/english-quran-ai-opening-owners.tsv")!!.bufferedReader()
            .use { it.readLines().first { row -> row.startsWith("24:2\t") } }.split('\t')
        val text = row[1]
        val aligned = EnglishWordAlignment.align(text, row[2].split('|'), row[4].split('|'), row[5].split('|'))!!
        val tail = listOf(Segment(22, 25530, 26800), Segment(23, 26800, 28490),
            Segment(24, 28490, 32560), Segment(25, 32560, 32930), Segment(26, 32930, 36250))
        val plan = englishPhrasePlan(aligned, tail)
        assertEquals(36250L, plan.windows.getValue(22).endMs)
        val cut = text.indexOf("punishment")
        val book = englishBookOf(listOf(listOf(EnglishVerseRun(24, 2, 0, cut)),
            listOf(EnglishVerseRun(24, 2, cut, text.length))), { _, _ -> 350 }, { _, _ -> text })
        val held = englishWashThrough(plan, 28490)!!
        assertEquals(1, mushafLeafNumber(book, 24, 2, 350, held))
        val crossed = englishWashThrough(plan, 35500)!!
        assertEquals(2, mushafLeafNumber(book, 24, 2, 350, crossed))
        assertEquals(1f, englishWashThrough(plan, 36250)!!, 0f)
        val phrase = plan.spans.last()
        val window = plan.windows.getValue(phrase.position)
        for (at in listOf(26000L, 28490L, 32000L, 35500L)) {
            val progress = window.progress(at)
            val render = phrase.from + (phrase.to - phrase.from) * progress
            assertEquals(render, englishWashThrough(plan, at)!!, .000001f)
        }
    }

    @Test
    fun `An Nur repeat completes prior ink and fresh material crosses Arabic boundaries`() {
        val row = javaClass.getResourceAsStream("/english-quran-ai-opening-owners.tsv")!!.bufferedReader()
            .use { it.readLines().first { row -> row.startsWith("24:1\t") } }.split('\t')
        val aligned = EnglishWordAlignment.align(row[1], row[2].split('|'), row[4].split('|'), row[5].split('|'))!!
        val plan = englishPhrasePlan(aligned, listOf(1, 2, 3, 4, 5, 6, 7, 4, 5, 6, 7, 8, 9)
            .mapIndexed { i, owner -> Segment(owner, i * 1000L, (i + 1) * 1000L) })
        val before = englishWashThrough(plan, 7000)!!
        assertEquals(before, englishWashThrough(plan, 9000)!!, 0f)
        for (at in hani.take(8).map { it.startMs }) {
            assertEquals(1f, englishPaperWordWetness(.5f, at, 6770, 1000, 1f), 0f)
        }
        assertEquals(.5f, englishPaperWordWetness(1f, 7270, 6770, 1000, 1f), 0f)
        assertEquals(0f, englishPaperWordWetness(.5f, 3000, 6770, 1000, 0f), 0f)
        assertEquals(0f, englishPaperWordWetness(-1f, 3000, 6770, 1000, 1f), 0f)
        val dry = PaperInkDeposit(1f, 0f, 0f, 0f, 0f)
        assertEquals(0f, englishPaperWordWetness(1f, 6800, 6770, 1000, 1f, dry), 0f)
        assertEquals(1f, englishPaperWordWetness(.5f, 3000, 6770, 1000, 1f, dry), 0f)
        assertSame(dry, englishDepartedWord(dry, -1f, .5f))
        assertNull(englishDepartedWord(dry, .2f, .5f))
        assertNull(englishDepartedWord(dry, -1f, 0f))
    }

    @Test
    fun `shared speed pause seek and reciter frames drive the same phrase edge`() {
        val plan = englishPresentationPlan(alignment, hani)
        val item = HighlightItem(64, 1, 7)
        val context = HighlightTimingContext(1, HighlightEngine.PreparedTimings.prepare(hani), 1020)
        val input = HighlightPlaybackSample(item, 3000, 0, true, speed = 2f, inkGeneration = 8)
        val state = HighlightSync.sample(null, input, context)
        val clock = EnglishWashClock(64, 1, 8, 3000, 1020, hani, 7)
        val fast = clock.onFrame(HighlightSync.frame(state, 100_000_000))
        assertEquals(3200L, fast.positionMs)
        val paused = HighlightSync.sample(state, input.copy(positionMs = 3200,
            sampledAtNanos = 100_000_000, isPlaying = false), context)
        val pausedClock = clock.onFrame(HighlightSync.frame(paused, 2_000_000_000))
        assertEquals(englishWashThrough(plan, 3200)!!,
            englishWashThrough(plan, pausedClock.positionMs)!!, 0f)
        val seek = HighlightSync.sample(paused, input.copy(positionMs = 1200,
            sampledAtNanos = 2_100_000_000, isPlaying = false, inkGeneration = 9, clockRevision = 1), context)
        val seekClock = clock.copy(activation = 9).onFrame(HighlightSync.frame(seek, 2_200_000_000))
        assertEquals(1200L, seekClock.positionMs)
        assertTrue(englishWashThrough(plan, seekClock.positionMs)!! < englishWashThrough(plan, 3200)!!)
        assertEquals(3000L, clock.onFrame(HighlightSync.frame(state, 100_000_000).copy(item = item.copy(reciter = 1))).positionMs)
        val shifted = englishPresentationPlan(alignment, hani.map { it.copy(startMs = it.startMs + 500, endMs = it.endMs + 500) })
        assertEquals(englishWashThrough(plan, 3200)!!, englishWashThrough(shifted, 3700)!!, .000001f)
    }

    @Test
    fun `delayed voice gate changes forward inverse and printed finishes together`() {
        val window = EnglishWashWindow(0, 1000, listOf(0L..1000L))
        assertEquals(.5f, window.progress(750, 500), 0f)
        assertEquals(750L, window.mediaAt(.5f, voiceStartMs = 500))
        val plan = englishPhrasePlan(synthetic(listOf(2, 1, 3)),
            listOf(Segment(1, 0, 400), Segment(2, 500, 1000), Segment(3, 1200, 1500)), 500)
        assertEquals(500L, plan.windows.getValue(1).startMs)
        assertEquals(.5f, plan.windows.getValue(1).progress(750), 0f)
        assertEquals(750L, englishWordExposure(plan, 0f, 1f / 3)!!.endMs)
        assertEquals(0f, englishWashThrough(plan, 499)!!, 0f)
    }

    @Test
    fun `three backward generations keep an untouched coat fading without resurrection`() {
        val coat = PaperInkDeposit(1f, 1f, 0f, 0f, 0f)
        val firstClock = mutableStateOf(.6f)
        val first = EnglishPaperDeparture(mapOf(10 to coat), firstClock)
        assertSame(coat, first.word(10, -1f))
        val secondClock = mutableStateOf(1f)
        val second = EnglishPaperDeparture(mapOf(10 to coat), secondClock, mapOf(10 to first.snapshotOpacity(10)))
        assertSame(coat, second.word(10, -1f))
        assertEquals(.6f, second.opacity(10), 0f)
        secondClock.value = .5f
        val third = EnglishPaperDeparture(mapOf(10 to coat), mutableStateOf(1f), mapOf(10 to second.snapshotOpacity(10)))
        assertSame(coat, third.word(10, -1f))
        assertEquals(.3f, third.opacity(10), .000001f)
        assertNull(third.word(10, .2f))
        assertEquals(1f, third.snapshotOpacity(10), 0f)
    }
}
