package com.beautifulquran.ui.settings

import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.VerseNumberScript
import com.beautifulquran.ui.reader.mushafFolioLayout
import com.beautifulquran.ui.reader.pageFolioLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizePolicyTest {

    @Test
    fun `defaults stay a bilingual scroll with Arabic verse marks and both folios`() {
        val settings = Settings()
        assertEquals(ReadingMode.ARABIC_ENGLISH, settings.readingMode)
        assertEquals(ReadingLayout.SCROLL, settings.readingLayout)
        assertEquals(VerseNumberScript.ARABIC, settings.verseNumberScript)
        assertEquals(PageNumberScript.BOTH, settings.pageNumberScript)
    }

    @Test
    fun `entering mushaf from English keeps the reader in English`() {
        val next = applyReadingLayout(
            Settings().copy(readingMode = ReadingMode.ENGLISH_ONLY),
            ReadingLayout.MUSHAF,
        )
        assertEquals(ReadingLayout.MUSHAF, next.readingLayout)
        assertEquals(ReadingMode.ENGLISH_ONLY, next.readingMode)
    }

    @Test
    fun `entering mushaf from bilingual lands on Arabic - a leaf is one language`() {
        val next = applyReadingLayout(
            Settings().copy(readingMode = ReadingMode.ARABIC_ENGLISH),
            ReadingLayout.MUSHAF,
        )
        assertEquals(ReadingLayout.MUSHAF, next.readingLayout)
        assertEquals(ReadingMode.ARABIC_ONLY, next.readingMode)
    }

    @Test
    fun `a leaf may be set in either language, and in nothing else`() {
        val mushaf = Settings().copy(
            readingLayout = ReadingLayout.MUSHAF,
            readingMode = ReadingMode.ARABIC_ONLY,
        )
        assertEquals(
            ReadingMode.ENGLISH_ONLY,
            applyReadingMode(mushaf, ReadingMode.ENGLISH_ONLY).readingMode,
        )
        assertEquals(mushaf, applyReadingMode(mushaf, ReadingMode.ARABIC_ENGLISH))
        assertEquals(
            listOf(ReadingMode.ARABIC_ONLY, ReadingMode.ENGLISH_ONLY),
            MUSHAF_VIEW_MODES,
        )
    }

    @Test
    fun `verse marks are offered wherever the reader can see Western digits`() {
        // The Arabic leaf's marks are drawn by the page face and cannot be
        // restyled; the English leaf's are set in the running prose.
        assertFalse(showsVerseNumberChrome(ReadingLayout.MUSHAF, ReadingMode.ARABIC_ONLY))
        assertTrue(showsVerseNumberChrome(ReadingLayout.MUSHAF, ReadingMode.ENGLISH_ONLY))
        assertTrue(showsVerseNumberChrome(ReadingLayout.SCROLL, ReadingMode.ARABIC_ONLY))
    }

    @Test
    fun `mushaf hides the annotation toggle and ayah-rail side`() {
        assertTrue(showsScrollChrome(ReadingLayout.SCROLL))
        assertFalse(showsScrollChrome(ReadingLayout.MUSHAF))
        assertTrue(showsPreviewAyahRail(ReadingLayout.SCROLL))
        assertFalse(showsPreviewAyahRail(ReadingLayout.MUSHAF))
    }

    @Test
    fun `word gloss is only a bilingual scroll option`() {
        assertTrue(
            showsWordGlossChrome(ReadingLayout.SCROLL, ReadingMode.ARABIC_ENGLISH),
        )
        assertFalse(
            showsWordGlossChrome(ReadingLayout.SCROLL, ReadingMode.ARABIC_ONLY),
        )
        assertFalse(
            showsWordGlossChrome(ReadingLayout.MUSHAF, ReadingMode.ARABIC_ENGLISH),
        )
        assertTrue(
            showsPreviewWordGloss(
                ReadingLayout.SCROLL,
                ReadingMode.ARABIC_ENGLISH,
                showWordGloss = true,
            ),
        )
        assertFalse(
            showsPreviewWordGloss(
                ReadingLayout.SCROLL,
                ReadingMode.ARABIC_ENGLISH,
                showWordGloss = false,
            ),
        )
    }

    @Test
    fun `preview shows a sample note only on scroll with annotations on`() {
        assertTrue(showsPreviewAnnotation(ReadingLayout.SCROLL, annotationsEnabled = true))
        assertFalse(showsPreviewAnnotation(ReadingLayout.SCROLL, annotationsEnabled = false))
        assertFalse(showsPreviewAnnotation(ReadingLayout.MUSHAF, annotationsEnabled = true))
    }

    @Test
    fun `leaving mushaf keeps the Arabic view until the reader changes it`() {
        val next = applyReadingLayout(
            Settings().copy(
                readingLayout = ReadingLayout.MUSHAF,
                readingMode = ReadingMode.ARABIC_ONLY,
            ),
            ReadingLayout.SCROLL,
        )
        assertEquals(ReadingLayout.SCROLL, next.readingLayout)
        assertEquals(ReadingMode.ARABIC_ONLY, next.readingMode)
    }

    @Test
    fun `summary names the layout and the verse-mark script`() {
        assertEquals(
            "Arabic & English · Arabic verse marks · System",
            customizeSummary(Settings()),
        )
        assertEquals(
            "Mushaf · Arabic · Nightfall",
            customizeSummary(
                Settings().copy(
                    readingLayout = ReadingLayout.MUSHAF,
                    readingMode = ReadingMode.ARABIC_ONLY,
                    verseNumberScript = VerseNumberScript.ENGLISH,
                    themeMode = ThemeMode.DARK,
                ),
            ),
        )
        assertEquals(
            "English · English verse marks · Paper",
            customizeSummary(
                Settings().copy(
                    readingMode = ReadingMode.ENGLISH_ONLY,
                    verseNumberScript = VerseNumberScript.ENGLISH,
                    themeMode = ThemeMode.LIGHT,
                ),
            ),
        )
    }

    @Test
    fun `verse marks follow the script, not the view mode`() {
        assertTrue(usesArabicIndicVerseMarks(VerseNumberScript.ARABIC))
        assertFalse(usesArabicIndicVerseMarks(VerseNumberScript.ENGLISH))
    }

    @Test
    fun `page folio layout matches each script`() {
        val both = pageFolioLayout(12, PageNumberScript.BOTH)
        assertEquals("12", both.leading)
        assertEquals("١٢", both.trailing)
        assertFalse(both.centered)

        val english = pageFolioLayout(12, PageNumberScript.ENGLISH)
        assertEquals("12", english.leading)
        assertNull(english.trailing)
        assertTrue(english.centered)

        val arabic = pageFolioLayout(12, PageNumberScript.ARABIC)
        assertEquals("١٢", arabic.leading)
        assertNull(arabic.trailing)
        assertTrue(arabic.centered)
    }

    @Test
    fun `mushaf folio is centred, with a diamond only for both scripts`() {
        val both = mushafFolioLayout(330, PageNumberScript.BOTH)
        assertEquals("330", both.western)
        assertEquals("٣٣٠", both.arabic)
        assertTrue(both.diamond)

        val english = mushafFolioLayout(330, PageNumberScript.ENGLISH)
        assertEquals("330", english.western)
        assertNull(english.arabic)
        assertFalse(english.diamond)

        val arabic = mushafFolioLayout(330, PageNumberScript.ARABIC)
        assertNull(arabic.western)
        assertEquals("٣٣٠", arabic.arabic)
        assertFalse(arabic.diamond)
    }
}
