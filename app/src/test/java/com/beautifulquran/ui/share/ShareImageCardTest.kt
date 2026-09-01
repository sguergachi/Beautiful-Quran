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
    fun `single verse footer is full reference`() {
        assertEquals(
            "al-Baqarah 2:255",
            footerReference(listOf(line(2, 255))),
        )
    }

    @Test
    fun `same-surah range uses en-dash`() {
        assertEquals(
            "al-Baqarah 2:1–3",
            footerReference(listOf(line(2, 1), line(2, 2), line(2, 3))),
        )
    }

    @Test
    fun `chapter footer rides the last verse strip`() {
        assertEquals(true, shareImageFooterOnStrip(0, 1))
        assertEquals(false, shareImageFooterOnStrip(0, 3))
        assertEquals(false, shareImageFooterOnStrip(1, 3))
        assertEquals(true, shareImageFooterOnStrip(2, 3))
        assertEquals(false, shareImageFooterOnStrip(0, 0))
    }

    @Test
    fun `cross-surah footer joins ends`() {
        assertEquals(
            "al-Baqarah 2:255 · al-Ikhlas 112:1",
            footerReference(
                listOf(
                    line(2, 255, "al-Baqarah"),
                    line(112, 1, "al-Ikhlas"),
                ),
            ),
        )
    }
}
