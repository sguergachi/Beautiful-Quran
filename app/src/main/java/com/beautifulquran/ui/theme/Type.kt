package com.beautifulquran.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.R
import com.beautifulquran.domain.ENGLISH_LEAF_CAPACITY_CHARS
import com.beautifulquran.domain.ENGLISH_LEAF_LEADING_EM

/** KFGQPC HAFS Uthmanic Script — the reference typeface for the Quran text. */
val HafsFontFamily = FontFamily(Font(R.font.hafs_uthmanic))

/**
 * The Scroll's Arabic: a face and the multiple that sets it at Hafs's hand.
 * [sizeScale] matches the alef's height to Hafs's (0.633 em), so a face that
 * draws large in its em does not read as a size change. The verse mark keeps
 * Hafs's cups and its own size whatever face the words are in.
 */
@androidx.compose.runtime.Immutable
data class ScrollArabicType(val fontFamily: FontFamily, val sizeScale: Float)

/** Variable, wght 400–700; its default instance is the Regular. */
private val NotoNaskhFontFamily = FontFamily(Font(R.font.noto_naskh_arabic_variable))
private val ScheherazadeFontFamily = FontFamily(Font(R.font.scheherazade_new_regular))

/** Noto Naskh Arabic (SIL OFL 1.1); alef 0.671 em. */
private val NotoNaskhScrollType = ScrollArabicType(NotoNaskhFontFamily, 0.633f / 0.671f)

/** Scheherazade New (SIL Global, SIL OFL 1.1); alef 0.700 em. */
private val ScheherazadeScrollType = ScrollArabicType(ScheherazadeFontFamily, 0.633f / 0.700f)

val HafsScrollType = ScrollArabicType(HafsFontFamily, 1f)

fun scrollArabicType(face: com.beautifulquran.data.ScrollArabicFace): ScrollArabicType = when (face) {
    com.beautifulquran.data.ScrollArabicFace.HAFS -> HafsScrollType
    com.beautifulquran.data.ScrollArabicFace.NOTO_NASKH -> NotoNaskhScrollType
    com.beautifulquran.data.ScrollArabicFace.SCHEHERAZADE -> ScheherazadeScrollType
}

/** The Scroll's Arabic face; developer mode may swap it. See [scrollArabicType]. */
val LocalScrollArabicType = staticCompositionLocalOf { HafsScrollType }

/**
 * Digital Khatt New Madina — Unicode Madinah-1420 face with real joining
 * (init/medi/fina/rlig/curs). Mushaf pages only; the scroll reader keeps
 * [HafsFontFamily]. SIL OFL 1.1.
 */
val MushafFontFamily = FontFamily(Font(R.font.digital_khatt_new_madina))

/**
 * QCF2BSML — the Madinah print's own header face, in the same hand as the 604
 * page faces.
 *
 * It carries one glyph per surah header and one for the basmalah, addressed by
 * single characters rather than by Arabic text: the page fonts have no space
 * glyph and no Unicode Arabic at all, so a basmalah written as Unicode fell
 * back to the reading face and arrived on the leaf in a different hand from
 * every other line on it. See [MUSHAF_BASMALAH_GLYPH].
 */
val MushafBasmalahFontFamily = FontFamily(Font(R.font.qcf2_bsml))

/** The basmalah, as one glyph of [MushafBasmalahFontFamily]. */
const val MUSHAF_BASMALAH_GLYPH = "\u00F3"

/**
 * What the basmalah's type size must be multiplied by to be written in the same
 * hand as the page beneath it.
 *
 * The header face carries the whole phrase as a single glyph, and that glyph's
 * ink fills only part of its em: measured, 0.673 em against the 1.161 em a page
 * face's word glyph inks at the median. Set at the leaf's own size it therefore
 * came out visibly smaller than the verse under it. At this multiple its
 * letters stand as tall as the page's, and the phrase spans about two thirds of
 * the measure — which is where the Madinah page sets it.
 */
const val MUSHAF_BASMALAH_HAND_SCALE = 1.73f

/**
 * Where the glyph's ink sits about its own baseline, in em — the midpoint of
 * its bounds, 0.310 em above it.
 *
 * The glyph's em box is nearly two ems tall while its ink fills two thirds of
 * one, and the ink sits high in the box. Centring the box therefore does not
 * centre the phrase: it dropped the basmalah to the foot of its line, where its
 * tail ran into the first verse. The line is placed by this instead.
 */
const val MUSHAF_BASMALAH_INK_MID_EM = 0.3101f



/**
 * Junicode 2 (Peter S. Baker, SIL OFL 1.1), set as Garamond: the app's English
 * face. Everything English that is *read* is set in it — translations, glosses,
 * lists, names, titles, notes. A Renaissance roman in Garamond's line, tuned so
 * a page of it measures as a page of EB Garamond (docs/DESIGN.md, "Type"):
 *
 * * **Size.** Its x-height is 0.418 em to Garamond's 0.405, so it is set at
 *   [JUNICODE_SIZE] of Garamond's sizes and its lowercase stands as tall.
 * * **Measure.** Its letters set 2 % narrower than Garamond's design and its
 *   word space 18 % wider, so lines came out the same length but loose. The
 *   bundled cut (tools/build_junicode_fonts.py) pins the width axis and the
 *   space to Garamond *as Android draws it*: the classic profile draws with
 *   hinted whole-pixel advances, which set Garamond about 1.5 % narrower than
 *   its design, and Junicode, drawn at exact advances, matched to the design
 *   still fitted a leaf 1.7 % shorter (764 leaves to 751). Width 102 (the
 *   italic 113) and a 0.199 em space give 753 leaves to Garamond's 751 on a
 *   Pixel 10, with the same lines broken in the same places.
 * * **Colour.** Weight is matched by the ink a sentence lays down per unit of
 *   advance and x-height, unhinted as Android draws it: Regular 400 (0.358 to
 *   0.356), Medium 520, SemiBold 680, the italic 420.
 *
 * The bundled files are subset to Latin, its extensions and the
 * transliteration marks, with the decorative enlargement axis pinned off; only
 * weight still varies. Glyphs it lacks fall back to EB Garamond (see
 * [bookFont]). Its figures and alternates: [JUNICODE_PROSE].
 */
private fun junicode(display: Boolean = false, ease: Int = 0): FontFamily =
    FontFamily(listOf(400, 500, 600, 700).flatMap { requested ->
        listOf(FontStyle.Normal, FontStyle.Italic).map { style ->
            val italic = style == FontStyle.Italic
            // Titles take the cut one step lighter than asked: at display
            // sizes the matched SemiBold reads heavy beside Garamond's.
            val step = if (display && requested > 400) requested - 100 else requested
            bookFont(
                if (italic) R.font.junicode_italic_variable else R.font.junicode_variable,
                if (italic) R.font.eb_garamond_italic else when (requested) {
                    500 -> R.font.eb_garamond_medium
                    600, 700 -> R.font.eb_garamond_semibold
                    else -> R.font.eb_garamond_regular
                },
                FontWeight(requested), style,
                "'wght' ${junicodeWeight(step, italic) - ease}",
            )
        }
    })

/** The Junicode weight whose colour matches EB Garamond's [requested] weight. */
internal fun junicodeWeight(requested: Int, italic: Boolean = false): Int = when {
    requested <= 400 -> if (italic) 420 else 400
    requested <= 500 -> 520
    requested <= 600 -> 680
    else -> 700
}

/** Junicode's size against Garamond's: their x-heights, 0.405 / 0.418 em. */
internal const val JUNICODE_SIZE = 0.969f

/**
 * Light ink on dark paper spreads into the dark around it and reads heavier
 * than the same weight in dark ink on cream, so on dark paper every role but
 * the leaf sets this much lighter. The leaf is not eased: weight moves
 * Junicode's advances, and the leaf is paginated — easing it gave each theme
 * its own book, so a System theme turning dark mid-reading moved verses onto
 * different leaves.
 */
internal const val DARK_PAPER_WEIGHT_EASE = 30

/**
 * Junicode's OpenType features, reviewed in context (docs/DESIGN.md, "Type").
 * Most of its 20 sets and 99 variants serve medieval scholarship — runes,
 * insular letterforms, scribal abbreviations — and stay off. Three earn a place:
 *
 * * **ss09, old-style figures with a flagged 1.** Junicode's default figures are
 *   old-style, and their 1 is a small-cap I: "2:15" read "2:I5". ss09 gives
 *   the 1 its flag, so running prose can set book figures again. Figures that
 *   stand alone (labels, counts, references, verse marks) stay lining through
 *   [liningFigures]; ss09 keeps the flagged 1 there too.
 * * **cv33 = 3, the long-tailed Q, in titles** ([JUNICODE_TITLE]). Its tail
 *   sweeps under the next letter — the masthead's "Quran", Al-Qamar, Qaf.
 * * **c2sc, true small caps, for tracked capitals** ([forCaps]): a chapter line
 *   in small caps with old-style figures, as a book sets it, instead of shrunk
 *   full capitals.
 *
 * Kept off: the italic swashes (a flourish on every word end), the ct/st
 * ligatures (medieval, not Renaissance), the alternate punctuation spacing.
 */
private const val JUNICODE_PROSE = "'kern' 1, 'liga' 1, 'onum' 1, 'ss09' 1"
private const val JUNICODE_TITLE = "'kern' 1, 'liga' 1, 'lnum' 1, 'ss09' 1, 'cv33' 3"
private const val JUNICODE_SMALL_CAPS = "'kern' 1, 'c2sc' 1, 'onum' 1, 'ss09' 1"

/** How much larger Junicode's small caps are set than the capitals they replace. */
private const val JUNICODE_SMALL_CAPS_SIZE = 1.15f

private val ClassicSerifFontFamily = FontFamily(
    Font(R.font.eb_garamond_regular, FontWeight.Normal),
    Font(R.font.eb_garamond_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.eb_garamond_medium, FontWeight.Medium),
    Font(R.font.eb_garamond_semibold, FontWeight.SemiBold),
)

private val ClassicDisplayFontFamily = FontFamily(
    Font(R.font.cormorant_garamond_medium, FontWeight.Medium),
    Font(R.font.cormorant_garamond_semibold, FontWeight.SemiBold),
)

/** The controls' face: the system sans, in Material's sans slots (docs/DESIGN.md, "UI text"). */
private val SystemSansFontFamily = FontFamily.Default

/** The English type the app is set in; readers always get [QuranTypeProfile.JUNICODE]. */
internal typealias QuranTypeProfile = com.beautifulquran.data.BookTypeface

internal data class QuranTypePalette(
    val profile: QuranTypeProfile,
    val serif: FontFamily,
    val display: FontFamily,
    val sans: FontFamily,
    val typography: Typography,
    val scribe: FontFamily,
    /** The English Mushaf leaf's hand: the book face, never eased for dark paper. */
    val leaf: FontFamily,
    /** OpenType features for running serif text. */
    val bookFeatures: String?,
    /** OpenType features for serif titles set outside [typography]'s title slots. */
    val titleFeatures: String?,
)

val SerifFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.serif

val DisplayFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.display

val SansFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.sans

val TranslationFontFamily: FontFamily
    @Composable get() = SerifFontFamily

/**
 * A size given at Garamond's scale, for the face being set: Junicode stands
 * [JUNICODE_SIZE] of it. Leading needs no such call — Junicode keeps
 * Garamond's, the same x-height on the same line pitch.
 */
@Composable
internal fun typeScale(classic: TextUnit): TextUnit =
    if (LocalQuranTypePalette.current.profile == QuranTypeProfile.JUNICODE) classic * JUNICODE_SIZE else classic

/** Features for running serif text in the face being set. */
internal val BookFeatures: String?
    @Composable get() = LocalQuranTypePalette.current.bookFeatures

/** Features for a serif title set by hand (banners, lexicon headings). */
internal val TitleFeatures: String?
    @Composable get() = LocalQuranTypePalette.current.titleFeatures

/**
 * A bundled face that falls back to [fallback] — an EB Garamond of the same
 * weight — for the glyphs it lacks, then to the system serif.
 *
 * A plain resource [Font] falls back to the system *sans*, so a dictionary
 * entry's ḥ landed in Roboto mid-word. `Typeface.CustomFallbackBuilder` (API
 * 29; minSdk is 30) is the only way to chain a second bundled face, and
 * Compose reaches it through an [AndroidFont] with its own loader.
 */
private fun bookFont(
    @FontRes primary: Int,
    @FontRes fallback: Int,
    weight: FontWeight,
    style: FontStyle = FontStyle.Normal,
    axes: String = "",
    italic: Boolean = style == FontStyle.Italic,
): Font = BookFont(primary, fallback, weight, style, axes, italic)

private data class BookFont(
    @FontRes val primary: Int,
    @FontRes val fallback: Int,
    override val weight: FontWeight,
    override val style: FontStyle,
    val axes: String,
    val italic: Boolean,
) : AndroidFont(FontLoadingStrategy.Blocking, BookFontLoader, FontVariation.Settings())

private object BookFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        font as BookFont
        val slant = if (font.italic) {
            android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC
        } else {
            android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT
        }
        fun family(@FontRes id: Int, axes: String = ""): android.graphics.fonts.FontFamily {
            val builder = android.graphics.fonts.Font.Builder(context.resources, id)
                .setWeight(font.weight.weight).setSlant(slant)
            if (axes.isNotEmpty()) builder.setFontVariationSettings(axes)
            return android.graphics.fonts.FontFamily.Builder(builder.build()).build()
        }
        return Typeface.CustomFallbackBuilder(family(font.primary, font.axes))
            .addCustomFallback(family(font.fallback))
            .setSystemFallback("serif")
            .setStyle(android.graphics.fonts.FontStyle(font.weight.weight, slant))
            .build()
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
        loadBlocking(context, font)
}

/** Previous annotation hand, retained by the classic comparison profile. */
private val ClassicScribeFontFamily = FontFamily(
    Font(R.font.cormorant_garamond_italic, FontWeight.Medium, FontStyle.Italic),
)

/** The reader's own notes: Junicode Italic; Cormorant in the classic profile. */
val ScribeFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.scribe

/** Kerning and ligatures, Garamond's book features in the classic profile. */
private const val BOOK_FEATURES = "'kern' 1, 'liga' 1"

/**
 * Figures that stand on their own — labels, facts, counters, verse marks,
 * folios — are lining, whatever the running text sets.
 */
internal fun liningFigures(features: String?): String =
    listOfNotNull(features?.takeIf(String::isNotBlank), "'onum' 0, 'lnum' 1").joinToString(", ")

/** A figure that stands on its own — a count, a number in a list, a reference like 2:7 — lining. */
internal fun TextStyle.lining(): TextStyle = copy(fontFeatureSettings = liningFigures(fontFeatureSettings))

/**
 * All-caps labels. Junicode sets them as true small caps (`c2sc`) with
 * old-style figures, a step larger so the small caps (0.457 em) keep a label
 * legible; set the size *before* calling this. Classic keeps shrunk capitals
 * with lining figures, `case` lifting punctuation to the capitals.
 */
@Composable
internal fun TextStyle.forCaps(): TextStyle =
    if (LocalQuranTypePalette.current.profile == QuranTypeProfile.JUNICODE) {
        copy(fontSize = fontSize * JUNICODE_SMALL_CAPS_SIZE, fontFeatureSettings = JUNICODE_SMALL_CAPS)
    } else {
        copy(fontFeatureSettings = liningFigures(fontFeatureSettings) + ", 'case' 1")
    }

/**
 * How book text is positioned. Junicode is a variable font drawn unhinted, and
 * Android's default static mode — whole-pixel advances — rounds each glyph
 * differently at every scale, so letter spacing visibly jitters while a sheet
 * stretches or a page turns. Linear metrics with subpixel positioning
 * (`TextMotion.Animated`) keep the designed spacing at any size or transform.
 * The classic profile keeps the platform default it was compared at.
 */
internal val BookTextMotion: TextMotion
    @Composable get() = LocalQuranTypePalette.current.bookTextMotion

internal val QuranTypePalette.bookTextMotion: TextMotion
    get() = if (profile == QuranTypeProfile.CLASSIC) TextMotion.Static else TextMotion.Animated

/**
 * Letterspacing for a mixed-case Latin line, in em. Tracked lowercase falls
 * apart into letters, so it is set all but solid (DESIGN.md: lowercase never
 * past 0.02 em); only ALL-CAPS labels are tracked. The classic profile keeps
 * its [classicEm].
 */
@Composable
internal fun mixedCaseTrackingEm(classicEm: Float): Float =
    if (LocalQuranTypePalette.current.profile == QuranTypeProfile.CLASSIC) {
        classicEm
    } else {
        minOf(classicEm, MIXED_CASE_TRACKING_EM)
    }

private const val MIXED_CASE_TRACKING_EM = 0.02f

/**
 * [BookTextMotion]'s flags for text drawn straight onto a canvas: linear
 * metrics and subpixel positioning, so canvas widths match what Compose draws.
 */
const val TEXT_PAINT_FLAGS =
    android.graphics.Paint.ANTI_ALIAS_FLAG or
        android.graphics.Paint.SUBPIXEL_TEXT_FLAG or
        android.graphics.Paint.LINEAR_TEXT_FLAG

/** Canvas text must use the selected profile's Compose rendering mode. */
internal val BookTextPaintFlags: Int
    @Composable get() = if (BookTextMotion == TextMotion.Animated) {
        TEXT_PAINT_FLAGS
    } else {
        android.graphics.Paint.ANTI_ALIAS_FLAG
    }

/** Small titles (≤ 18 sp) are set in the book face; classic keeps Cormorant. */
val SmallTitleFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.let {
        if (it.profile == QuranTypeProfile.CLASSIC) it.display else it.serif
    }

/** The exact pre-Junicode Material + Garamond/Cormorant scale, for comparison. */
private val ClassicQuranTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = ClassicDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = ClassicDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = ClassicSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    bodyLarge = TextStyle(
        fontFamily = ClassicSerifFontFamily,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    bodyMedium = TextStyle(
        fontFamily = ClassicSerifFontFamily,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    labelMedium = TextStyle(
        fontFamily = ClassicSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = ClassicSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
)

/**
 * Garamond's scale — [ClassicQuranTypography]'s serif slots — set in Junicode
 * at [JUNICODE_SIZE], keeping Garamond's leading. Material's sans slots stay in
 * the system sans.
 */
private fun junicodeTypography(serif: FontFamily, display: FontFamily, sans: FontFamily): Typography {
    fun set(style: TextStyle, family: FontFamily, features: String = JUNICODE_PROSE) = style.copy(
        fontFamily = family,
        fontSize = style.fontSize * JUNICODE_SIZE,
        fontFeatureSettings = features,
        textMotion = TextMotion.Animated,
    )
    val garamond = ClassicQuranTypography
    fun ui(style: TextStyle) = style.copy(fontFamily = sans)
    return garamond.copy(
        displayLarge = ui(garamond.displayLarge),
        displayMedium = ui(garamond.displayMedium),
        displaySmall = ui(garamond.displaySmall),
        headlineLarge = ui(garamond.headlineLarge),
        headlineMedium = set(garamond.headlineMedium, display, JUNICODE_TITLE),
        headlineSmall = ui(garamond.headlineSmall),
        titleLarge = set(garamond.titleLarge, display, JUNICODE_TITLE),
        titleMedium = set(garamond.titleMedium, serif),
        titleSmall = ui(garamond.titleSmall),
        bodyLarge = set(garamond.bodyLarge, serif),
        bodyMedium = set(garamond.bodyMedium, serif),
        bodySmall = ui(garamond.bodySmall),
        labelLarge = ui(garamond.labelLarge),
        // Garamond's labels were tracked 0.6–0.8 sp; lowercase is never tracked
        // past 0.02 em (docs/DESIGN.md), so these are set solid.
        labelMedium = set(garamond.labelMedium, serif, liningFigures(JUNICODE_PROSE)).copy(letterSpacing = 0.sp),
        labelSmall = set(garamond.labelSmall, serif, liningFigures(JUNICODE_PROSE)).copy(letterSpacing = 0.sp),
    )
}

/** Junicode, its every role but the leaf lightened by [ease] (see [DARK_PAPER_WEIGHT_EASE]). */
private fun junicodePalette(ease: Int): QuranTypePalette {
    val serif = junicode(ease = ease)
    val display = junicode(display = true, ease = ease)
    return QuranTypePalette(
        profile = QuranTypeProfile.JUNICODE,
        serif = serif,
        display = display,
        sans = SystemSansFontFamily,
        typography = junicodeTypography(serif, display, SystemSansFontFamily),
        scribe = FontFamily(
            bookFont(
                R.font.junicode_italic_variable, R.font.eb_garamond_italic, FontWeight.Medium,
                FontStyle.Italic, "'wght' ${junicodeWeight(500, italic = true) - ease}",
            ),
        ),
        leaf = JunicodeLeafFontFamily,
        bookFeatures = JUNICODE_PROSE,
        titleFeatures = JUNICODE_TITLE,
    )
}

private val JunicodeLeafFontFamily = junicode()
private val JunicodeTypePalette = junicodePalette(ease = 0)
private val JunicodeDarkTypePalette = junicodePalette(ease = DARK_PAPER_WEIGHT_EASE)

private val ClassicTypePalette = QuranTypePalette(
    profile = QuranTypeProfile.CLASSIC,
    serif = ClassicSerifFontFamily,
    display = ClassicDisplayFontFamily,
    sans = SystemSansFontFamily,
    typography = ClassicQuranTypography,
    scribe = ClassicScribeFontFamily,
    leaf = ClassicSerifFontFamily,
    bookFeatures = BOOK_FEATURES,
    titleFeatures = null,
)

internal val LocalQuranTypePalette = staticCompositionLocalOf { JunicodeTypePalette }

/**
 * The English leaf's leading. Junicode's hand comes out at [JUNICODE_SIZE] of
 * Garamond's (see `englishBookHandPx`), so its leading in em is Garamond's
 * divided by that: the lines keep Garamond's pitch.
 */
internal val QuranTypePalette.leafLeadingEm: Float
    get() = when (profile) {
        QuranTypeProfile.CLASSIC -> ENGLISH_LEAF_LEADING_EM
        QuranTypeProfile.JUNICODE -> ENGLISH_LEAF_LEADING_EM / JUNICODE_SIZE
    }

/** What the leaf's hand is solved against: how many characters a full leaf holds. */
internal val QuranTypePalette.leafCapacityChars: Int
    get() = ENGLISH_LEAF_CAPACITY_CHARS

/**
 * The bundled Junicode cut, by the SHA-256 of each file — the first 16 hex
 * digits. A font rebuilt without updating these fails `JunicodeTypeTest`, and
 * they are part of [bookCacheSuffix], so a new cut can never be paginated from
 * the last one's cache.
 */
internal const val JUNICODE_ROMAN_SHA = "f6af8d5939e92e8a"
internal const val JUNICODE_ITALIC_SHA = "e30d8716a75abeae"

/**
 * What the English book's pagination cache is keyed by beyond the window and
 * the text: the face and everything about it that moves a line — the font
 * files, size, leading, capacity and weights.
 */
internal val QuranTypePalette.bookCacheSuffix: String
    get() = when (profile) {
        QuranTypeProfile.CLASSIC -> "|classic|$leafCapacityChars|$leafLeadingEm"
        QuranTypeProfile.JUNICODE -> listOf(
            "|junicode", JUNICODE_ROMAN_SHA, JUNICODE_ITALIC_SHA, JUNICODE_SIZE, leafLeadingEm,
            leafCapacityChars, junicodeWeight(400), junicodeWeight(400, italic = true), bookFeatures,
        ).joinToString("|")
    }

/** The palette for [profile]; Junicode lightened on dark paper ([DARK_PAPER_WEIGHT_EASE]). */
internal fun quranTypePalette(profile: QuranTypeProfile, darkPaper: Boolean = false): QuranTypePalette =
    when (profile) {
        QuranTypeProfile.CLASSIC -> ClassicTypePalette
        QuranTypeProfile.JUNICODE -> if (darkPaper) JunicodeDarkTypePalette else JunicodeTypePalette
    }

val QuranTypography: Typography
    @Composable get() = LocalQuranTypePalette.current.typography

/** Base style for a single Arabic word in the follow-along view. */
val ArabicWordStyle = TextStyle(
    fontFamily = HafsFontFamily,
    fontSize = 30.sp,
    lineHeight = 1.9.em,
    textMotion = TextMotion.Static,
)

/** Arabic surah name in lists and headers. */
val ArabicTitleStyle = TextStyle(
    fontFamily = HafsFontFamily,
    fontSize = 24.sp,
    lineHeight = 1.6.em,
    textMotion = TextMotion.Static,
)
