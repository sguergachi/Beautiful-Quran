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
