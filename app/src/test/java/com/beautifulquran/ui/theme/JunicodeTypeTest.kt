package com.beautifulquran.ui.theme

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JunicodeTypeTest {
    private fun sha16(name: String): String = MessageDigest.getInstance("SHA-256")
        .digest(File("src/main/res/font/$name").readBytes())
        .joinToString("") { "%02x".format(it) }
        .take(16)

    @Test
    fun bundledCutMatchesTheHashesTheBookCacheIsKeyedBy() {
        // Rebuilt the fonts (tools/build_junicode_fonts.py)? Update the two
        // constants in Type.kt: they key the English book's pagination cache.
        assertEquals(JUNICODE_ROMAN_SHA, sha16("junicode_variable.ttf"))
        assertEquals(JUNICODE_ITALIC_SHA, sha16("junicode_italic_variable.ttf"))
    }

    @Test
    fun eachFaceKeysItsOwnBookAndDarkPaperKeepsTheLeaf() {
        val paper = quranTypePalette(QuranTypeProfile.JUNICODE)
        val dark = quranTypePalette(QuranTypeProfile.JUNICODE, darkPaper = true)
        val classic = quranTypePalette(QuranTypeProfile.CLASSIC)
        assertNotEquals(paper.bookCacheSuffix, classic.bookCacheSuffix)
        assertEquals(paper.bookCacheSuffix, dark.bookCacheSuffix)
        assertTrue(JUNICODE_ROMAN_SHA in paper.bookCacheSuffix)
        assertEquals(paper.leaf, dark.leaf)
        assertNotEquals(paper.serif, dark.serif)
        assertEquals(classic, quranTypePalette(QuranTypeProfile.CLASSIC, darkPaper = true))
    }

    @Test
    fun proseNeverSetsTheOldStyleOneThatReadsAsI() {
        val junicode = quranTypePalette(QuranTypeProfile.JUNICODE)
        listOf(junicode.bookFeatures, junicode.titleFeatures).forEach { features ->
            assertTrue("$features carries the flagged 1", "'ss09' 1" in features.orEmpty())
        }
        assertTrue("'onum' 1" in junicode.bookFeatures.orEmpty())
        assertTrue("'cv33' 3" in junicode.titleFeatures.orEmpty())
        // Labels set lining over the prose features, flagged 1 kept.
        val label = junicode.typography.labelSmall.fontFeatureSettings.orEmpty()
        assertTrue(label.endsWith("'onum' 0, 'lnum' 1") && "'ss09' 1" in label)
    }

    @Test
    fun weightsMatchGaramondsColour() {
        assertEquals(
            listOf(400, 420, 520, 680, 700),
            listOf(
                junicodeWeight(400), junicodeWeight(400, italic = true),
                junicodeWeight(500), junicodeWeight(600), junicodeWeight(700),
            ),
        )
    }
}
