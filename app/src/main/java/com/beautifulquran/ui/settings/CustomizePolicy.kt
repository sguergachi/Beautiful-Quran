package com.beautifulquran.ui.settings

import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.VerseNumberScript

/** Mushaf is a printed Arabic page — never English, never bilingual. */
fun applyReadingLayout(settings: Settings, layout: ReadingLayout): Settings =
    if (layout == ReadingLayout.MUSHAF) {
        settings.copy(readingLayout = layout, readingMode = ReadingMode.ARABIC_ONLY)
    } else {
        settings.copy(readingLayout = layout)
    }

/** View-mode changes while mushaf is on are ignored so the leaf stays Arabic. */
fun applyReadingMode(settings: Settings, mode: ReadingMode): Settings =
    if (settings.readingLayout == ReadingLayout.MUSHAF && mode != ReadingMode.ARABIC_ONLY) {
        settings
    } else {
        settings.copy(readingMode = mode)
    }

/** Printed mushaf has no annotation margin and no ayah rail. */
fun showsScrollChrome(layout: ReadingLayout): Boolean =
    layout == ReadingLayout.SCROLL

/** The faded leaf only shows a sample ḥāshiya on a scroll page with notes on. */
fun showsPreviewAnnotation(layout: ReadingLayout, annotationsEnabled: Boolean): Boolean =
    showsScrollChrome(layout) && annotationsEnabled

/** Collapsed ayah rail lives on the scroll leaf — never on a printed page. */
fun showsPreviewAyahRail(layout: ReadingLayout): Boolean =
    showsScrollChrome(layout)

/** Word gloss lives under Arabic tiles — only the bilingual scroll view. */
fun showsWordGlossChrome(layout: ReadingLayout, mode: ReadingMode): Boolean =
    showsScrollChrome(layout) && mode == ReadingMode.ARABIC_ENGLISH

fun showsPreviewWordGloss(
    layout: ReadingLayout,
    mode: ReadingMode,
    showWordGloss: Boolean,
): Boolean = showsWordGlossChrome(layout, mode) && showWordGloss

fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Paper"
    ThemeMode.DARK -> "Nightfall"
    ThemeMode.ROYAL_GREEN -> "Royal green"
}

fun customizeSummary(settings: Settings): String {
    val theme = themeLabel(settings.themeMode)
    if (settings.readingLayout == ReadingLayout.MUSHAF) return "Mushaf · $theme"
    val view = when (settings.readingMode) {
        ReadingMode.ENGLISH_ONLY -> "English"
        ReadingMode.ARABIC_ONLY -> "Arabic"
        ReadingMode.ARABIC_ENGLISH -> "Arabic & English"
    }
    val verse = if (settings.verseNumberScript == VerseNumberScript.ARABIC) {
        "Arabic verse marks"
    } else {
        "English verse marks"
    }
    return "$view · $verse · $theme"
}

fun usesArabicIndicVerseMarks(script: VerseNumberScript): Boolean =
    script == VerseNumberScript.ARABIC
