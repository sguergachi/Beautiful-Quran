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
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
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
 * Timeless Serif Text — the book face. Everything English that is *read* is
 * set in it: translations, glosses, lists, names, facts. The Text cuts are
 * its small-size optical master. Italics use the family's real Timeless
 * Serif Italic cut; the archive has no separate Text Italic optical master.
 *
 * Timeless has no ḍ ḥ ẓ ʾ ʿ, which transliterations and the dictionary use,
 * so each cut falls back to the matching EB Garamond — the book face before
 * it — glyph by glyph rather than to the system's sans. See [bookFont].
 *
 * Its x-height is 0.52 em against EB Garamond's 0.40, so the same size reads
 * about an eighth larger: sizes across the app sit at ~0.88 of what they were
 * in Garamond, which keeps every line's apparent size and the page's density.
 */
private val TimelessSerifFontFamily = FontFamily(
    bookFont(R.font.timeless_serif_text_regular, R.font.eb_garamond_regular, FontWeight.Normal),
    bookFont(R.font.timeless_serif_italic, R.font.eb_garamond_italic, FontWeight.Normal, FontStyle.Italic),
    bookFont(R.font.timeless_serif_text_medium, R.font.eb_garamond_medium, FontWeight.Medium),
    bookFont(R.font.timeless_serif_text_semibold, R.font.eb_garamond_semibold, FontWeight.SemiBold),
    bookFont(R.font.timeless_serif_text_bold, R.font.eb_garamond_semibold, FontWeight.Bold),
)

/**
 * Timeless Serif's display master — surah titles and headlines; its finer
 * contrast is cut for large sizes. It sets darker than the Cormorant it
 * replaced, so each weight the app asks for is served by the cut one step
 * lighter: Medium by Regular, SemiBold by Medium. The web maps the same.
 */
private val TimelessDisplayFontFamily = FontFamily(
    bookFont(R.font.timeless_serif_regular, R.font.eb_garamond_regular, FontWeight.Medium),
    bookFont(R.font.timeless_serif_medium, R.font.eb_garamond_medium, FontWeight.SemiBold),
)

/**
 * Timeless Sans — the controls' face: Material's sans slots ([QuranTypography]
 * keeps `labelLarge` & co. sans on purpose; see docs/DESIGN.md, "UI text").
 */
private val TimelessSansFontFamily = FontFamily(
    Font(R.font.timeless_sans_regular, FontWeight.Normal),
    Font(R.font.timeless_sans_medium, FontWeight.Medium),
)

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

private val ClassicSansFontFamily = FontFamily.Default

internal enum class QuranTypeProfile { TIMELESS, CLASSIC }

internal data class QuranTypePalette(
    val profile: QuranTypeProfile,
    val serif: FontFamily,
    val display: FontFamily,
    val sans: FontFamily,
    val typography: Typography,
)

val SerifFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.serif

val DisplayFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.display

val SansFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.sans

val TranslationFontFamily: FontFamily
    @Composable get() = SerifFontFamily

@Composable
internal fun typeScale(timeless: TextUnit, classic: TextUnit): TextUnit =
    if (LocalQuranTypePalette.current.profile == QuranTypeProfile.TIMELESS) timeless else classic

/**
 * A Timeless cut that falls back to [fallback] — an EB Garamond of the same
 * weight — for the glyphs Timeless lacks, then to the system serif.
 *
 * A plain resource [Font] falls back to the system *sans*, so a dictionary
 * entry's ḥ landed in Roboto mid-word. `Typeface.CustomFallbackBuilder` is the
 * only way to chain a second bundled face, and Compose reaches it through an
 * [AndroidFont] with its own loader.
 */
private fun bookFont(
    @FontRes primary: Int,
    @FontRes fallback: Int,
    weight: FontWeight,
    style: FontStyle = FontStyle.Normal,
): Font = BookFont(primary, fallback, weight, style)

private data class BookFont(
    @FontRes val primary: Int,
    @FontRes val fallback: Int,
    override val weight: FontWeight,
    override val style: FontStyle,
) : AndroidFont(FontLoadingStrategy.Blocking, BookFontLoader, FontVariation.Settings())

private object BookFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        font as BookFont
        val slant = if (font.style == FontStyle.Italic) {
            android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC
        } else {
            android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT
        }
        fun family(@FontRes id: Int) = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(context.resources, id)
                .setWeight(font.weight.weight)
                .setSlant(slant)
                .build(),
        ).build()
        return Typeface.CustomFallbackBuilder(family(font.primary))
            .addCustomFallback(family(font.fallback))
            .setSystemFallback("serif")
            .setStyle(android.graphics.fonts.FontStyle(font.weight.weight, slant))
            .build()
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
        loadBlocking(context, font)
}

/**
 * **The reader's hand.** Cormorant Garamond Italic, instanced at weight 500 —
 * the chancery cursive that Renaissance scribes actually wrote marginal glosses
 * in, and the hand italic type was cut from in the first place.
 *
 * It is deliberately *not* the book face's italic. The app's prose is Timeless
 * Serif, so its own italic reads as **emphasis** — the same voice leaning — rather
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

private val MaterialSans = Typography()

/**
 * The shipped scale: serif sizes are ~0.88 of the previous Garamond values,
 * while Material's sans slots take Timeless Sans.
 */
private val TimelessQuranTypography = Typography(
    displayLarge = MaterialSans.displayLarge.copy(fontFamily = TimelessSansFontFamily),
    displayMedium = MaterialSans.displayMedium.copy(fontFamily = TimelessSansFontFamily),
    displaySmall = MaterialSans.displaySmall.copy(fontFamily = TimelessSansFontFamily),
    headlineLarge = MaterialSans.headlineLarge.copy(fontFamily = TimelessSansFontFamily),
    headlineMedium = TextStyle(
        fontFamily = TimelessDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineSmall = MaterialSans.headlineSmall.copy(fontFamily = TimelessSansFontFamily),
    titleLarge = TextStyle(
        fontFamily = TimelessDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.5.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    titleSmall = MaterialSans.titleSmall.copy(fontFamily = TimelessSansFontFamily),
    bodyLarge = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    bodyMedium = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = BOOK_FEATURES,
    ),
    bodySmall = MaterialSans.bodySmall.copy(fontFamily = TimelessSansFontFamily),
    labelLarge = MaterialSans.labelLarge.copy(fontFamily = TimelessSansFontFamily),
    labelMedium = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

/** The exact pre-Timeless Material + Garamond/Cormorant scale for comparison. */
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

private val TimelessTypePalette = QuranTypePalette(
    profile = QuranTypeProfile.TIMELESS,
    serif = TimelessSerifFontFamily,
    display = TimelessDisplayFontFamily,
    sans = TimelessSansFontFamily,
    typography = TimelessQuranTypography,
)

private val ClassicTypePalette = QuranTypePalette(
    profile = QuranTypeProfile.CLASSIC,
    serif = ClassicSerifFontFamily,
    display = ClassicDisplayFontFamily,
    sans = ClassicSansFontFamily,
    typography = ClassicQuranTypography,
)

internal val LocalQuranTypePalette = staticCompositionLocalOf { TimelessTypePalette }

internal fun quranTypePalette(timeless: Boolean): QuranTypePalette =
    if (timeless) TimelessTypePalette else ClassicTypePalette

val QuranTypography: Typography
    @Composable get() = LocalQuranTypePalette.current.typography

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
