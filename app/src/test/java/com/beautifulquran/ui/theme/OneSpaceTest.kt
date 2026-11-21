package com.beautifulquran.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.beautifulquran.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gradient stops must share one colour space: the framework throws on mixed
 * spaces, which crashed every chapter open on a wide-gamut Pixel once the P3
 * accents met their sRGB gradient partners (see OneSpace.kt).
 */
class OneSpaceTest {

    private val p3Gold = Color(0.471f, 0.358f, 0.030f, colorSpace = ColorSpaces.DisplayP3)
    private val srgbGold = Color(0xFFC9AE67)

    @Test
    fun stopsInOneSpaceComeBackUntouched() {
        val srgb = listOf(srgbGold, Color.Transparent, Color.Black)
        assertSame(srgb, oneSpace(srgb))
        val p3 = listOf(p3Gold, p3Gold.copy(alpha = 0f))
        assertSame(p3, oneSpace(p3))
        // A spread copies the array, so compare contents: nothing converted.
        val stops = arrayOf(0f to srgbGold, 1f to Color.Transparent)
        assertTrue(oneSpace(*stops).contentEquals(stops))
    }

    @Test
    fun mixedStopsMoveTogetherIntoDisplayP3() {
        val mixed = oneSpace(listOf(p3Gold, srgbGold, Color.Transparent))
        assertTrue(mixed.all { it.colorSpace == ColorSpaces.DisplayP3 })
        // Same colours, only re-expressed: back in sRGB the partner is unchanged.
        val c = mixed[1].convert(ColorSpaces.Srgb)
        assertEquals(srgbGold.red, c.red, 1e-3f)
        assertEquals(srgbGold.green, c.green, 1e-3f)
        assertEquals(srgbGold.blue, c.blue, 1e-3f)
        assertEquals(p3Gold, mixed[0])

        val pairs = oneSpace(0f to Color.Transparent, 0.5f to p3Gold, 1f to srgbGold)
        assertTrue(pairs.all { it.second.colorSpace == ColorSpaces.DisplayP3 })
        assertEquals(listOf(0f, 0.5f, 1f), pairs.map { it.first })
    }

    @Test
    fun everyWideAccentGradientResolvesToOneSpace() {
        // The real pairings from Theme.kt on a wide-gamut window.
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.ROYAL_GREEN)) {
            val a = quranAccents(mode, systemDark = false, wideGamut = true)
            val gilt = oneSpace(listOf(a.goldDeep, a.goldBright, a.goldDeep))
            assertEquals(mode.name, 1, gilt.map { it.colorSpace }.toSet().size)
            val shield = oneSpace(listOf(a.goldBright, a.gold, a.goldDeep))
            assertEquals(mode.name, 1, shield.map { it.colorSpace }.toSet().size)
            val ruby = oneSpace(0f to a.bookmarkRibbon, 1f to Color.Transparent)
            assertEquals(mode.name, 1, ruby.map { it.second.colorSpace }.toSet().size)
        }
    }

    @Test
    fun everyAppGradientPassesItsStopsThroughOneSpace() {
        val call = Regex("""Brush\.(linear|vertical|horizontal|radial|sweep)Gradient\(""")
        val offenders = mutableListOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            for (m in call.findAll(text)) {
                val args = argumentsOf(text, m.range.last + 1)
                if ("oneSpace(" !in args) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "${file.path}:$line"
                }
            }
        }
        assertTrue("Gradients without oneSpace(...): $offenders", offenders.isEmpty())
    }

    /** The text between the '(' before [start] and its matching ')'. */
    private fun argumentsOf(text: String, start: Int): String {
        var depth = 1
        var i = start
        while (depth > 0 && i < text.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            i++
        }
        return text.substring(start, i - 1)
    }
}
