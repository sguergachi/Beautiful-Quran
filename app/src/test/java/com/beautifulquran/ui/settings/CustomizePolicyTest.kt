package com.beautifulquran.ui.settings

import com.beautifulquran.data.PageNumberScript
import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.VerseNumberScript
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
    fun `mushaf forces Arabic-only view`() {
        val next = applyReadingLayout(
            Settings().copy(readingMode = ReadingMode.ENGLISH_ONLY),
            ReadingLayout.MUSHAF,
        )
        assertEquals(ReadingLayout.MUSHAF, next.readingLayout)
        assertEquals(ReadingMode.ARABIC_ONLY, next.readingMode)
    }

    @Test
    fun `view mode cannot leave Arabic while mushaf is on`() {
        val mushaf = Settings().copy(
            readingLayout = ReadingLayout.MUSHAF,
            readingMode = ReadingMode.ARABIC_ONLY,
        )
        assertEquals(mushaf, applyReadingMode(mushaf, ReadingMode.ENGLISH_ONLY))
        assertEquals(mushaf, applyReadingMode(mushaf, ReadingMode.ARABIC_ENGLISH))
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
            "Mushaf · Nightfall",
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
}
