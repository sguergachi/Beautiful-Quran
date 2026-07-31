package com.beautifulquran.ui.rootviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class RootViewerReferencesTest {

    @Test
    fun `root link uses QAC Buckwalter spelling`() {
        assertEquals("ktb", arabicToBuckwalter("كتب"))
        assertEquals("qwl", arabicToBuckwalter("قول"))
        assertEquals("\$mE", arabicToBuckwalter("شمع"))
    }

    @Test
    fun `references target the exact word root and verse`() {
        val links = rootViewerReferences(2, 282, 10, "كتب")

        assertEquals(3, links.size)
        assertEquals(
            "https://corpus.quran.com/wordmorphology.jsp?location=%282%3A282%3A10%29",
            links[0].url,
        )
        assertEquals("https://corpus.quran.com/qurandictionary.jsp?q=ktb", links[1].url)
        assertEquals("https://quran.com/2/282", links.last().url)
    }

    @Test
    fun `Lane web entry lives under the classical lexicon section`() {
        assertEquals(
            "https://arabiclexicon.hawramani.com/search/%D9%83%D8%AA%D8%A8?cat=50",
            laneLexiconUrl("كتب"),
        )
    }

    @Test
    fun `rootless words omit root dictionaries`() {
        assertEquals(2, rootViewerReferences(1, 1, 1, "").size)
    }
}
