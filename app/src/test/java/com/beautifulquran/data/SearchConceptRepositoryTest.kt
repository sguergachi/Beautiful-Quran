package com.beautifulquran.data

import com.beautifulquran.domain.RelatedSearchTerm
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
        assertEquals(10_426, vocabulary.thesaurus.size)
        assertTrue(RelatedSearchTerm("tranquility", 2) in vocabulary.thesaurus.getValue("calm"))
        assertTrue(RelatedSearchTerm("peace", 2) in vocabulary.thesaurus.getValue("calm"))
        assertTrue(RelatedSearchTerm("settled", 1) !in vocabulary.thesaurus.getValue("calm"))
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
