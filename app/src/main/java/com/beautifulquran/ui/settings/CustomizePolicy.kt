package com.beautifulquran.ui.settings

import com.beautifulquran.data.ReadingLayout
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.Settings
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.VerseNumberScript

/**
 * Views the mushaf leaf can be set in.
 *
 * A printed leaf is one book in one language — the page's own hand, or the
 * English of it (`domain/EnglishLeaf.kt`). Bilingual is a scroll idea: it
 * pairs each verse with its translation under it, which is a list of verses
 * and not a page, and there is nowhere on a leaf to put the second language
 * without ceasing to be a leaf.
 *
 * Until the English leaf existed this list was Arabic alone, and both
 * functions below forced the setting back to it.
 */
val MUSHAF_VIEW_MODES = listOf(ReadingMode.ARABIC_ONLY, ReadingMode.ENGLISH_ONLY)

/** Bilingual has no printed leaf, so entering mushaf from it lands on Arabic. */
fun applyReadingLayout(settings: Settings, layout: ReadingLayout): Settings =
    if (layout == ReadingLayout.MUSHAF && settings.readingMode !in MUSHAF_VIEW_MODES) {
        settings.copy(readingLayout = layout, readingMode = ReadingMode.ARABIC_ONLY)
    } else {
        settings.copy(readingLayout = layout)
    }

/** A leaf may be set in either language, and in nothing else. */
fun applyReadingMode(settings: Settings, mode: ReadingMode): Settings =
    if (settings.readingLayout == ReadingLayout.MUSHAF && mode !in MUSHAF_VIEW_MODES) {
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

/**
 * The verse-mark script is offered wherever the reader actually sees Western
 * digits as an option: every scroll view, and the English leaf, whose marks
 * are set in the running prose the same way. The Arabic leaf's marks are drawn
 * by the page face itself and cannot be restyled.
 */
fun showsVerseNumberChrome(layout: ReadingLayout, mode: ReadingMode): Boolean =
    showsScrollChrome(layout) || mode == ReadingMode.ENGLISH_ONLY

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

fun readingModeLabel(mode: ReadingMode): String = when (mode) {
    ReadingMode.ENGLISH_ONLY -> "English"
    ReadingMode.ARABIC_ONLY -> "Arabic"
    ReadingMode.ARABIC_ENGLISH -> "Arabic & English"
}

fun customizeSummary(settings: Settings): String {
    val theme = themeLabel(settings.themeMode)
    if (settings.readingLayout == ReadingLayout.MUSHAF) {
        return "Mushaf · ${readingModeLabel(settings.readingMode)} · $theme"
    }
    val view = readingModeLabel(settings.readingMode)
    val verse = if (settings.verseNumberScript == VerseNumberScript.ARABIC) {
        "Arabic verse marks"
    } else {
        "English verse marks"
    }
    return "$view · $verse · $theme"
}

fun usesArabicIndicVerseMarks(script: VerseNumberScript): Boolean =
    script == VerseNumberScript.ARABIC
