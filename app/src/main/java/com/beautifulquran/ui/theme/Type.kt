package com.beautifulquran.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.R

/** KFGQPC HAFS Uthmanic Script — the reference typeface for the Quran text. */
val HafsFontFamily = FontFamily(Font(R.font.hafs_uthmanic))

/**
 * Digital Khatt New Madina — Unicode Madinah-1420 face with real joining
 * (init/medi/fina/rlig/curs). Mushaf pages only; the scroll reader keeps
 * [HafsFontFamily]. SIL OFL 1.1.
 */
val MushafFontFamily = FontFamily(Font(R.font.digital_khatt_new_madina))

/**
 * EB Garamond — the book face. Everything English is set in it: translations,
 * glosses, lists, labels, even the speed chip. Bundled with true italics and
 * optical weights so emphasis never falls back to a synthetic slant.
 */
val SerifFontFamily = FontFamily(
    Font(R.font.eb_garamond_regular, FontWeight.Normal),
    Font(R.font.eb_garamond_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.eb_garamond_medium, FontWeight.Medium),
    Font(R.font.eb_garamond_semibold, FontWeight.SemiBold),
)

/**
 * Cormorant Garamond — the display face for surah titles and headlines,
 * where its tall, fine-stroked capitals can breathe at large sizes.
 */
val DisplayFontFamily = FontFamily(
    Font(R.font.cormorant_garamond_medium, FontWeight.Medium),
    Font(R.font.cormorant_garamond_semibold, FontWeight.SemiBold),
)

val TranslationFontFamily = SerifFontFamily

/**
 * **The reader's hand.** Cormorant Garamond Italic, instanced at weight 500 —
 * the chancery cursive that Renaissance scribes actually wrote marginal glosses
 * in, and the hand italic type was cut from in the first place.
 *
 * It is deliberately *not* EB Garamond Italic. The app's prose is EB Garamond,
 * so its own italic reads as **emphasis** — the same voice leaning — rather
 * than as a second person writing on the page. Cormorant's italic is a
 * different, more pen-driven hand: looser 'a' and 'e', calligraphic 'f' and
 * 'y', a wider stroke contrast. At note size the reader sees someone else's
 * writing, not the app raising its voice.
 *
 * This is the one narrow exception to Cormorant's display-only rule
 * (docs/DESIGN.md, "Break a rule narrowly and record why"): the 500 weight and
 * the note's 62 % ink keep the fine strokes from going wispy at 15 sp. Do not
 * generalise it into body copy — the roman Cormorant stays display-only.
 */
val ScribeFontFamily = FontFamily(
    Font(R.font.cormorant_garamond_italic, FontWeight.Medium, FontStyle.Italic),
)

/**
 * Discretionary refinements applied to running serif text: kerning and
 * ligatures on, old-style (text) figures so ayah counts sit inside prose
 * without shouting. Fonts that lack a feature simply ignore it.
 */
private const val BOOK_FEATURES = "'kern' 1, 'liga' 1, 'onum' 1"

/**
 * Full serif scale. EB Garamond runs a small x-height, so sizes sit ~1sp
 * above the Material defaults to keep the same apparent size.
 */
val QuranTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    bodyLarge = TextStyle(
        fontFamily = SerifFontFamily,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    bodyMedium = TextStyle(
        fontFamily = SerifFontFamily,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    labelMedium = TextStyle(
        fontFamily = SerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
)

/** Base style for a single Arabic word in the follow-along view. */
val ArabicWordStyle = TextStyle(
    fontFamily = HafsFontFamily,
    fontSize = 30.sp,
    lineHeight = 1.9.em,
)

/** Arabic surah name in lists and headers. */
val ArabicTitleStyle = TextStyle(
    fontFamily = HafsFontFamily,
    fontSize = 24.sp,
    lineHeight = 1.6.em,
)
