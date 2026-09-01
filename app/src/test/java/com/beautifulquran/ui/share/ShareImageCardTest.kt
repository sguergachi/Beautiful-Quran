package com.beautifulquran.ui.share

import com.beautifulquran.share.AyahRef
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareImageCardTest {

    private fun line(surah: Int, ayah: Int, name: String = "al-Baqarah") = ShareVerseLine(
        ref = AyahRef(surah, ayah),
        surahName = name,
        arabic = "…",
        translation = "…",
    )

    @Test
    fun `single verse splits name and citation`() {
        assertEquals(
            ShareFooterCopy("al-Baqarah", "2:255"),
            shareFooterCopy(listOf(line(2, 255))),
        )
    }

    @Test
    fun `same-surah range keeps the name and dashes ayahs`() {
        assertEquals(
            ShareFooterCopy("al-Baqarah", "2:1–3"),
            shareFooterCopy(listOf(line(2, 1), line(2, 2), line(2, 3))),
        )
    }

    @Test
    fun `chapter footer is its own last segment`() {
        assertEquals(0, shareImageSegmentCount(0))
        assertEquals(2, shareImageSegmentCount(1))
        assertEquals(4, shareImageSegmentCount(3))
        assertEquals(true, shareImageIsFooterSegment(1, 1))
        assertEquals(false, shareImageIsFooterSegment(0, 1))
        assertEquals(false, shareImageIsFooterSegment(2, 3))
        assertEquals(true, shareImageIsFooterSegment(3, 3))
    }

    @Test
    fun `cross-surah footer joins both names and citations`() {
        assertEquals(
            ShareFooterCopy("al-Baqarah · al-Ikhlas", "2:255 · 112:1"),
            shareFooterCopy(
                listOf(
                    line(2, 255, "al-Baqarah"),
                    line(112, 1, "al-Ikhlas"),
                ),
            ),
        )
    }
}
