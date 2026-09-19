package com.beautifulquran.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Nothing in the app is sans" (docs/DESIGN.md, Type). A Material slot that
 * [QuranTypography] leaves out is Roboto, and components pick slots on their
 * own, so every slot must name one of the book's faces.
 */
class TypographyTest {
    private val bookFaces = setOf(SerifFontFamily, DisplayFontFamily)

    @Test
    fun everyTypographySlotIsSetInABookFace() {
        val t = QuranTypography
        val slots = mapOf(
            "displayLarge" to t.displayLarge,
            "displayMedium" to t.displayMedium,
            "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge,
            "headlineMedium" to t.headlineMedium,
            "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge,
            "titleMedium" to t.titleMedium,
            "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge,
            "bodyMedium" to t.bodyMedium,
            "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge,
            "labelMedium" to t.labelMedium,
            "labelSmall" to t.labelSmall,
        )
        slots.forEach { (name, style) ->
            assertTrue("$name falls back to ${style.fontFamily}", style.fontFamily in bookFaces)
        }
    }
}
