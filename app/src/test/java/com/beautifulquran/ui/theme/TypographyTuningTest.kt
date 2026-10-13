package com.beautifulquran.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TypographyTuningTest {
    @Test
    fun catalogsMatchTheFontsBuiltInStylisticSets() {
        fun sets(sans: Boolean, italic: Boolean) = timelessFeatures(sans, italic)
            .map { it.tag }.filter { it.startsWith("ss") }
        assertEquals((1..20).map { "ss%02d".format(it) }, sets(false, false))
        assertEquals(listOf("ss01", "ss02", "ss03", "ss04", "ss05", "ss06", "ss07", "ss09", "ss12", "ss13"), sets(false, true))
        assertEquals((1..14).map { "ss%02d".format(it) }, sets(true, false))
    }

    @Test
    fun darkPaperEasesAppWeightsAndPreservesTheMeasuredLeaf() {
        val paper = TypographyTuning()
        val dark = paper.forDarkPaper()
        TypeRole.entries.forEach { role ->
            val ease = if (role == TypeRole.Leaf || role == TypeRole.BookItalic) 0 else DARK_PAPER_WEIGHT_EASE
            assertEquals(paper.face(role).weight - ease, dark.face(role).weight)
            assertEquals(paper.face(role).copy(weight = 0), dark.face(role).copy(weight = 0))
        }
        assertEquals(370, dark.book.weight)
    }

    @Test
    fun darkPaletteKeepsPaperTuningForPaginationAndSharing() {
        val paper = TypographyTuning()
        val light = quranTypePalette(QuranTypeProfile.TIMELESS, paper)
        val dark = quranTypePalette(QuranTypeProfile.TIMELESS, paper, fontTuning = paper.forDarkPaper())
        assertEquals(paper, dark.tuning)
        assertEquals(light.tuning.bookCacheKey, dark.tuning.bookCacheKey)
        assertEquals(light.leaf, dark.leaf)
        assertNotEquals(light.serif, dark.serif)
        assertEquals(quranTypePalette(QuranTypeProfile.CLASSIC), quranTypePalette(QuranTypeProfile.CLASSIC, paper, paper.forDarkPaper()))
    }

    @Test
    fun junicodeIgnoresInkLabTuningButEasesOnDarkPaperExceptTheLeaf() {
        val tuned = TypographyTuning(book = TypefaceTuning(weight = 650))
        val paper = quranTypePalette(QuranTypeProfile.JUNICODE)
        assertEquals(paper, quranTypePalette(QuranTypeProfile.JUNICODE, tuned))
        val dark = quranTypePalette(QuranTypeProfile.JUNICODE, fontTuning = TypographyTuning().forDarkPaper())
        assertNotEquals(paper.serif, dark.serif)
        assertEquals(paper.leaf, dark.leaf)
        assertEquals(paper.tuning, dark.tuning)
        assertEquals(paper.bookCacheSuffix, dark.bookCacheSuffix)
    }

    @Test
    fun junicodeNeverSetsTheOldStyleOneThatReadsAsI() {
        val t = quranTypePalette(QuranTypeProfile.JUNICODE).tuning
        listOf(TypeRole.Book, TypeRole.Leaf, TypeRole.BookItalic, TypeRole.Title, TypeRole.Note).forEach {
            assertEquals("$it carries the flagged 1", 1, t.face(it).features["ss09"])
        }
        listOf(TypeRole.Book, TypeRole.Leaf, TypeRole.BookItalic, TypeRole.Note).forEach {
            assertEquals("$it prose sets book figures", 1, t.face(it).features["onum"])
        }
        assertEquals(1, t.title.features["lnum"])
        assertEquals(3, t.title.features["cv33"])
        assertEquals(listOf(400, 420, 520, 680, 700), listOf(
            junicodeWeight(400), junicodeWeight(400, italic = true),
            junicodeWeight(500), junicodeWeight(600), junicodeWeight(700),
        ))
    }

    @Test
    fun onlyTitlesAndUiCarryStylisticSets() {
        val t = TypographyTuning()
        assertEquals(1, t.title.features["ss16"])
        assertEquals(1, t.ui.features["ss07"])
        listOf(t.book, t.leaf, t.bookItalic, t.note).forEach { face ->
            assertEquals(emptyList<String>(), face.features.keys.filter { it.startsWith("ss") })
        }
    }

    @Test
    fun eachRoleKeepsItsOwnSettings() {
        val shipped = TypographyTuning()
        TypeRole.entries.forEach { role ->
            val face = shipped.face(role).copy(weight = 650, features = mapOf("ss01" to 1))
            val changed = shipped.withFace(role, face)
            assertEquals(face, changed.face(role))
            TypeRole.entries.filter { it != role }.forEach { other ->
                assertEquals(shipped.face(other), changed.face(other))
            }
        }
    }

    @Test
    fun cacheChangesOnlyForBookMetricsAndFeatures() {
        val shipped = TypographyTuning()
        assertEquals(64, shipped.bookCacheKey.length)
        assertNotEquals(shipped.bookCacheKey, shipped.copy(book = shipped.book.copy(weight = 401)).bookCacheKey)
        assertNotEquals(shipped.bookCacheKey, shipped.copy(bookItalic = shipped.bookItalic.copy(features = mapOf("ss01" to 1))).bookCacheKey)
        assertNotEquals(shipped.bookCacheKey, shipped.copy(leaf = shipped.leaf.copy(weight = 400)).bookCacheKey)
        for (role in listOf(TypeRole.Title, TypeRole.Ui, TypeRole.Note)) {
            assertEquals(shipped.bookCacheKey, shipped.withFace(role, shipped.face(role).copy(size = 1.2f)).bookCacheKey)
        }
    }

    @Test
    fun featureSettingsIncludeExplicitOffValuesAndAlternateIndices() {
        val face = TypefaceTuning(features = mapOf("ss01" to 1, "liga" to 0, "aalt" to 6))
        assertEquals("'aalt' 6, 'liga' 0, 'ss01' 1", face.featureSettings)
    }
}
