package com.beautifulquran.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WordSearchRowKeyTest {

    @Test
    fun everyRowOfAResultSetHasItsOwnKey() {
        // Two sections on the same chapter and two hits on one verse must
        // still give distinct keys: a duplicate key crashes the lazy list.
        val keys = buildList {
            listOf(2, 2, 3).forEachIndexed { section, surah ->
                add(wordSearchRowKey(WordSearchRow.Header, section, surah))
                repeat(3) { hit -> add(wordSearchRowKey(WordSearchRow.Hit, section, surah, hit)) }
                add(wordSearchRowKey(WordSearchRow.Expand, section, surah))
                add(wordSearchRowKey(WordSearchRow.Collapse, section, surah))
            }
        }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun resultKeysNeverMeetTheSheetsOtherRows() {
        val others = (1..114).map(::chapterKey) +
            listOf("word-results", "surahs-label", "pinned-air", "pinned-flag", "empty")
        val results = (0 until 114).flatMap { section ->
            WordSearchRow.entries.map { row -> wordSearchRowKey(row, section, section + 1) }
        }
        assertFalse(results.any { it in others })
    }
}
