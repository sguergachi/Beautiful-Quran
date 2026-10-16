package com.beautifulquran.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishContractionsTest {
    @Test
    fun `shared contraction cases rank and highlight in both directions`() {
        val fixture = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/testdata/search_contractions.tsv") }.first(File::isFile)
        for (line in fixture.readLines().filter(String::isNotBlank)) {
            val (contracted, expanded) = line.split('\t')
            for ((source, query) in listOf(contracted to expanded, expanded to contracted)) {
                for (apostrophe in listOf("'", "’")) {
                    val text = "Before ${source.replace("'", apostrophe)} after"
                    val typed = query.replace("'", apostrophe)
                    assertTrue("$typed -> $text", searchTextRelevance(text, parseSearchQuery(typed), false) > 0)
                    val spans = englishTranslationHighlightSpans(text, typed, "")
                    assertEquals("$typed -> $text", source.replace("'", apostrophe),
                        spans.filter { it.highlighted }.joinToString("") { it.text })
                    assertEquals(text, spans.joinToString("") { it.text })
                    val partial = "Before ${source.replace("'", apostrophe)} love after"
                    assertEquals(2_200, searchTextRelevance(partial, parseSearchQuery("$typed lov"), false))
                    assertEquals("${source.replace("'", apostrophe)} love",
                        englishTranslationHighlightSpans(partial, "$typed lov", "")
                            .filter { it.highlighted }.joinToString("") { it.text })
                }
            }
        }
    }

    @Test
    fun `negation spans brackets and repeated occurrences without losing positions`() {
        val text = "Allah (does) not love corruption and does [not] love wrongdoing"
        val spans = englishTranslationHighlightSpans(text, "doesn’t love", "")
        assertEquals(listOf("(does) not love", "does [not] love"),
            spans.filter { it.highlighted }.map { it.text })
        assertEquals(text, spans.joinToString("") { it.text })
        assertEquals("does", quickSearchFallbackTerm("doesn't"))
        assertEquals("will", quickSearchFallbackTerm("won't"))
    }

    @Test
    fun `final prefixes work through expansions while complete negations stay complete`() {
        for ((source, query) in listOf("(does) not love" to "doesn't", "doesn't love" to "does not")) {
            for (prefix in listOf("l", "lo", "lov")) {
                val typed = "$query $prefix"
                assertEquals(2_200, searchTextRelevance(source, parseSearchQuery(typed), false))
                assertEquals(source, englishTranslationHighlightSpans(source, typed, "")
                    .filter { it.highlighted }.joinToString("") { it.text })
                assertEquals(0, searchTextRelevance(source, parseSearchQuery("\"$typed\""), false))
            }
        }
        for ((source, query) in listOf("does nothing" to "doesn't", "can nothing" to "can't",
            "he island" to "he's", "would nothing have" to "wouldn't've")) {
            assertEquals(0, searchTextRelevance(source, parseSearchQuery(query), false))
        }
    }

    @Test
    fun `surrounding quotation marks do not become contraction letters`() {
        for ((source, query) in listOf("doesn't love" to "does not love", "he's merciful" to "he is merciful",
            "'tis good" to "it is good")) {
            for ((open, close) in listOf("'" to "'", "‘" to "’")) {
                val text = "He said $open$source$close"
                assertEquals(3_000, searchTextRelevance(text, parseSearchQuery(query), false))
                assertEquals(source, englishTranslationHighlightSpans(text, query, "")
                    .filter { it.highlighted }.joinToString("") { it.text })
            }
        }
    }

    @Test
    fun `possessives quotes word order and incomplete negations stay distinct`() {
        for ((source, query) in listOf(
            "Allah's mercy" to "Allah is mercy", "his mercy" to "he's mercy",
            "does love" to "doesn't love",
            "undoes not love" to "doesn't love", "he has mercy" to "he is mercy",
            "does not love" to "\"doesn't love\"", "doesn't love" to "\"does not love\"",
        )) {
            assertEquals("$query -> $source", 0, searchTextRelevance(source, parseSearchQuery(query), false))
            assertFalse(englishTranslationHighlightSpans(source, query, "").any { it.highlighted })
        }
    }
}
