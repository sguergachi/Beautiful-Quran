package com.beautifulquran.data

import com.beautifulquran.domain.RelatedSearchTerm
import com.beautifulquran.domain.SearchConcept
import com.beautifulquran.domain.conceptRelevance
import com.beautifulquran.domain.parseSearchQuery
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConceptRepositoryTest {

    @Test
    fun `packaged concept index covers the Quran and keeps source vocabulary`() {
        val vocabulary = decodeSearchVocabulary(repoFile("data/search_concepts.json").readText())
        val concepts = vocabulary.concepts
        assertEquals(338, concepts.size)
        assertEquals(6_236, concepts.flatMap { it.ayahKeys.asIterable() }.toSet().size)
        assertEquals(16_309, concepts.sumOf { it.ayahKeys.size })

        val mercy = concepts.single { it.name == "Divine Mercy" }
        assertTrue("clemency" in mercy.secondaryTerms)
        assertTrue(1_001 in mercy.ayahKeys)
        val wealth = concepts.single { it.name == "Wealth Management" }
        assertTrue("saving money" in wealth.secondaryTerms)
        assertTrue(conceptRelevance(wealth, parseSearchQuery("saving money")) > 0)
        assertEquals(10_426, vocabulary.thesaurus.size)
        assertTrue(RelatedSearchTerm("tranquility", 2) in vocabulary.thesaurus.getValue("calm"))
        assertTrue(RelatedSearchTerm("peace", 2) in vocabulary.thesaurus.getValue("calm"))
        assertTrue(RelatedSearchTerm("settled", 1) !in vocabulary.thesaurus.getValue("calm"))

        val candidateConcepts = decodeSearchConcepts(
            repoFile("data/search_concept_candidates.json").readText(),
        )
        fun signatures(items: List<SearchConcept>) = items.map {
            listOf(
                it.name,
                it.primaryTerms,
                it.secondaryTerms,
                it.category,
                it.domain,
                it.ayahKeys.toList(),
            )
        }
        assertEquals(signatures(concepts), signatures(candidateConcepts))
    }

    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).takeIf(File::isFile)?.let { return it }
            dir = dir.parentFile
        }
        error("Could not find $path")
    }
}
