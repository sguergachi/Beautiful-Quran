package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.beautifulquran.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Display P3 accents are an addition for wide-gamut windows, never a change:
 * off one, every theme draws its sRGB accents exactly; on one, only the accents
 * that are never set as text move, and they move into Display P3.
 */
class WideGamutAccentsTest {

    private val themes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.ROYAL_GREEN)

    @Test
    fun srgbWindowsKeepTheShippedAccents() {
        for (mode in themes) {
            assertSame(mode.name, quranThemeParts(mode).third, quranAccents(mode, systemDark = false, wideGamut = false))
        }
    }

    @Test
    fun onlyDrawnAccentsGoWide() {
        val wide = mapOf(
            ThemeMode.LIGHT to setOf("goldDeep", "bookmarkRibbon"),
            ThemeMode.DARK to setOf("bookmarkRibbon"),
            ThemeMode.ROYAL_GREEN to setOf("bookmarkRibbon"),
        )
        for (mode in themes) {
            val srgb = quranThemeParts(mode).third.fields()
            val p3 = quranAccents(mode, systemDark = false, wideGamut = true).fields()
            val changed = srgb.keys.filter { srgb[it] != p3[it] }.toSet()
            assertEquals(mode.name, wide.getValue(mode), changed)
            for (name in changed) {
                assertEquals("$mode.$name", ColorSpaces.DisplayP3, p3.getValue(name)!!.colorSpace)
            }
        }
    }

    private fun QuranAccents.fields() = mapOf(
        "gold" to gold, "goldInk" to goldInk, "goldBright" to goldBright, "goldDeep" to goldDeep,
        "greenInk" to greenInk, "greenQuiet" to greenQuiet, "greenWash" to greenWash,
        "embossDark" to embossDark, "embossLight" to embossLight, "repeatInk" to repeatInk,
        "bookmarkRibbon" to bookmarkRibbon, "annotationInk" to annotationInk, "glintInk" to glintInk,
    )
}
