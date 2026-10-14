package com.beautifulquran.domain

import com.beautifulquran.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ReviewedEnglishAlignmentTest {
    @Test
    fun `fixed contract agrees with offline SHA and tokenizer and real asset parses`() {
        val (text, surfaces, fingerprint, encoded) = javaClass.getResourceAsStream("/english-ledger-contract.tsv")!!
            .bufferedReader().use { it.readText().trim().split('\t') }
        assertEquals(fingerprint, ReviewedEnglishAlignment.fingerprint(text, surfaces.split('|')))
        val actual = ReviewedEnglishAlignment.tokens(text)
        val expected = encoded.split(';').map { it.split(',') }
        assertEquals(expected.map { it[2] }, actual.map { text.substring(it) })
        assertEquals(expected.map { it[0].toInt() until it[1].toInt() }, actual)
        java.io.File("../data/english-alignments.tsv").bufferedReader().use { reader ->
            ReviewedEnglishAlignment.read(reader.lineSequence())
        }
    }

    private val text = "Allah helped us."
    private val arabic = listOf("نَصَرَنَا", "ٱللَّهُ")
    private val record = EnglishAlignmentRecord(ReviewedEnglishAlignment.fingerprint(text, arabic), listOf(1, 1, 1), listOf(2, 1, 1))

    @Test
    fun `heads and forward ink remain separate and include the full prefix`() {
        val alignment = ReviewedEnglishAlignment.alignment(text, arabic, record)!!
        assertTrue(alignment.usesApprovedRecord)
        assertEquals(2, alignment.positionAt(0.1f))
        assertEquals(1, alignment.positionAt(0.7f))
        assertEquals(listOf(EnglishWordSpan(0f, 1f, 1)), alignment.washSpans)
        assertEquals(0f, alignment.through(1), 0f)
        assertEquals(1f, alignment.through(2), 0f)
        val reordered = ReviewedEnglishAlignment.alignment(text, arabic, record.copy(inkAnchors = listOf(2, 1, 2)))!!
        assertEquals(1, reordered.washSpans.first().position)
        assertEquals(reordered.washSpans.first().to, reordered.washSpans.last().from, 0f)
        assertEquals(1f, reordered.washSpans.last().to, 0f)
    }

    @Test
    fun `exact binding rejects punctuation equal count surfaces and incomplete owners`() {
        assertNull(ReviewedEnglishAlignment.alignment(text + "!", arabic, record))
        assertNull(ReviewedEnglishAlignment.alignment(text, arabic.reversed(), record))
        assertNull(ReviewedEnglishAlignment.alignment(text, arabic, record.copy(heads = listOf(1))))
        assertNull(ReviewedEnglishAlignment.alignment(text, arabic, record.copy(inkAnchors = listOf(1, 0, 2))))
        assertNull(ReviewedEnglishAlignment.alignment(text, arabic, record.copy(heads = listOf(1, 3, 2))))
    }

    @Test
    fun `reader uses approved prose offline and quarantines it from word card mode`() {
        val verse = Ayah(1, 1, arabic.joinToString(" "), text,
            words = arabic.mapIndexed { index, surface -> Word(index + 1, surface, listOf("helped us", "Allah")[index], "") })
        val content = SurahContent(Surah(1, "", "", "", "", 1), listOf(verse), mapOf(1 to record))
        val prose = EnglishVerseAlignments(content)
        assertTrue(prose.alignment(1)!!.usesApprovedRecord)
        assertSame(prose.alignment(1), prose.alignment(1))
        assertFalse(EnglishVerseAlignments(content, true).alignment(1)!!.usesApprovedRecord)
        assertFalse(EnglishVerseAlignments(content.copy(ayahs = listOf(verse.copy(translation = text + "!")))).alignment(1)!!.usesApprovedRecord)
        val offline = content.copy(ayahs = listOf(verse.copy(words = verse.words.map { it.copy(translation = "") })))
        assertTrue(EnglishVerseAlignments(offline).alignment(1)!!.usesApprovedRecord)
    }

    @Test
    fun `AI maps are opt in and human records take precedence`() {
        val verse = Ayah(1, 1, arabic.joinToString(" "), text,
            words = arabic.mapIndexed { index, surface -> Word(index + 1, surface, listOf("helped us", "Allah")[index], "") })
        val content = SurahContent(Surah(1, "", "", "", "", 1), listOf(verse), aiEnglishAlignmentRecords = mapOf(1 to record))
        assertFalse(EnglishVerseAlignments(content).alignment(1)!!.usesAiReviewedRecord)
        val experiment = EnglishVerseAlignments(content, useAiReviewed = true)
        val alignment = experiment.alignment(1)!!
        assertTrue(alignment.usesAiReviewedRecord)
        assertFalse(alignment.usesApprovedRecord)
        val offline = content.copy(ayahs = listOf(verse.copy(words = verse.words.map { it.copy(translation = "") })))
        assertTrue(EnglishVerseAlignments(offline, useAiReviewed = true).alignment(1)!!.usesAiReviewedRecord)
        assertSame(alignment, experiment.alignment(1))
        assertEquals(2, alignment.positionAt(0.1f))
        assertEquals(listOf(EnglishWordSpan(0f, 1f, 1)), alignment.washSpans)
        assertFalse(EnglishVerseAlignments(content, wordByWord = true, useAiReviewed = true).alignment(1)!!.usesAiReviewedRecord)
        val stale = content.copy(aiEnglishAlignmentRecords = mapOf(1 to record.copy(fingerprint = "stale")))
        assertFalse(EnglishVerseAlignments(stale, useAiReviewed = true).alignment(1)!!.usesAiReviewedRecord)
        val human = EnglishVerseAlignments(content.copy(englishAlignmentRecords = mapOf(1 to record)), useAiReviewed = true).alignment(1)!!
        assertTrue(human.usesApprovedRecord)
        assertFalse(human.usesAiReviewedRecord)
        java.io.File("../data/english-ai-alignments.tsv").bufferedReader().use { reader ->
            assertEquals(7, ReviewedEnglishAlignment.read(reader.lineSequence()).size)
        }
    }

    @Test
    fun `tokenizer counts additions grammar apostrophes accents and UTF16 offsets`() {
        val text = "[And] Allāh’s 𐐀name"
        val tokens = ReviewedEnglishAlignment.tokens(text)
        assertEquals(listOf("And", "Allāh’s", "𐐀name"), tokens.map { text.substring(it) })
        assertEquals(14, tokens.last().first)
    }

    @Test
    fun `compiled parser supports compact heads and rejects duplicates or unknown format`() {
        val row = "1:1\t${record.fingerprint}\t1,1,1\t="
        val parsed = ReviewedEnglishAlignment.read(sequenceOf("# ${ReviewedEnglishAlignment.FORMAT}", row)).getValue("1:1")
        assertEquals(parsed.inkAnchors, parsed.heads)
        assertTrue(runCatching { ReviewedEnglishAlignment.read(sequenceOf("# invalid", row)) }.isFailure)
        assertTrue(runCatching { ReviewedEnglishAlignment.read(sequenceOf("# ${ReviewedEnglishAlignment.FORMAT}", row, row)) }.isFailure)
    }
}
