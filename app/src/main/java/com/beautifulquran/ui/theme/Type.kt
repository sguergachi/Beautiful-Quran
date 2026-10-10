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
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.R
import com.beautifulquran.domain.ENGLISH_LEAF_LEADING_EM

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
private val TimelessSerifFontFamily = timelessSerif(TypefaceTuning(), TypographyTuning().bookItalic)

/**
 * Timeless Serif's display master — surah titles and headlines; its finer
 * contrast is cut for large sizes. It sets darker than the Cormorant it
 * replaced, so each weight the app asks for is served by the cut one step
 * lighter: Medium by Regular, SemiBold by Medium. The web maps the same.
 */
private val TimelessDisplayFontFamily = timelessSerif(TypefaceTuning(style = 0f), display = true)

/**
 * Timeless Sans — the controls' face: Material's sans slots ([QuranTypography]
 * keeps `labelLarge` & co. sans on purpose; see docs/DESIGN.md, "UI text").
 */
private val TimelessSansFontFamily = timelessSans(TypographyTuning().ui)

private fun timelessSerif(
    face: TypefaceTuning,
    italicFace: TypefaceTuning = face,
    display: Boolean = false,
): FontFamily {
    val weights = listOf(400, 500, 600, 700)
    return FontFamily(weights.flatMap { requested ->
        listOf(FontStyle.Normal, FontStyle.Italic).map { style ->
            val selected = if (style == FontStyle.Italic) italicFace else face
            val italic = style == FontStyle.Italic || selected.italic >= 50f
            val weight = (selected.weight + if (display && requested == 400) 0 else
                requested - if (display) 500 else 400)
                .coerceIn(if (italic) 300 else 200, 700)
            bookFont(
                if (italic) R.font.timeless_serif_italic_variable else R.font.timeless_serif_variable,
                if (italic) R.font.eb_garamond_italic else when (requested) {
                    500 -> R.font.eb_garamond_medium
                    600, 700 -> R.font.eb_garamond_semibold
                    else -> R.font.eb_garamond_regular
                },
                FontWeight(requested), style,
                "'wght' $weight" + if (italic) "" else ", 'STYL' ${selected.style}",
                italic,
            )
        }
    })
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun timelessSans(face: TypefaceTuning): FontFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { requested ->
        Font(
            R.font.timeless_sans_variable, FontWeight(requested),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(
                    (face.weight + if (requested == 400) 0 else requested - 420).coerceIn(300, 800),
                ),
                FontVariation.Setting("STYL", face.style),
                FontVariation.Setting("ital", face.italic),
            ),
        )
    },
)

private fun timelessNotes(face: TypefaceTuning): FontFamily = FontFamily(
    bookFont(
        if (face.italic >= 50f) R.font.timeless_serif_italic_variable else R.font.timeless_serif_variable,
        R.font.eb_garamond_italic, FontWeight.Medium, FontStyle.Italic,
        "'wght' ${face.weight.coerceIn(if (face.italic >= 50f) 300 else 200, 700)}" +
            if (face.italic >= 50f) "" else ", 'STYL' ${face.style}",
        face.italic >= 50f,
    ),
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
    val scribe: FontFamily,
    val tuning: TypographyTuning = TypographyTuning(),
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
internal fun typeScale(
    timeless: TextUnit,
    classic: TextUnit,
    role: TypeRole = TypeRole.Book,
): TextUnit = LocalQuranTypePalette.current.let {
    if (it.profile == QuranTypeProfile.TIMELESS) timeless * it.tuning.face(role).size else classic
}

@Composable
internal fun typeLeading(timeless: TextUnit, classic: TextUnit, role: TypeRole = TypeRole.Book): TextUnit =
    typeScale(timeless, classic, role) * LocalQuranTypePalette.current.tuning.face(role).leading

@Composable
internal fun typeFeatures(role: TypeRole): String? = LocalQuranTypePalette.current.let {
    if (it.profile == QuranTypeProfile.TIMELESS) it.tuning.face(role).featureSettings
    else if (role == TypeRole.Title || role == TypeRole.Ui) null else BOOK_FEATURES
}

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

/** Timeless Italic notes in the new profile; Cormorant in the classic profile. */
val ScribeFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.scribe

/**
 * Discretionary refinements applied to running serif text: kerning and
 * ligatures on, old-style (text) figures so ayah counts sit inside prose
 * without shouting. Fonts that lack a feature simply ignore it.
 */
private const val BOOK_FEATURES = "'kern' 1, 'liga' 1, 'onum' 1"
private const val UI_FEATURES = "'kern' 1, 'calt' 1"

/**
 * Figures that stand on their own — labels, facts, counters, verse marks,
 * folios — are lining. Old-style figures belong to running prose, where they
 * sit among lowercase; beside a capital, a rule or inside a mark cup their
 * hanging 3 4 5 7 9 read as misaligned. Timeless defaults to lining, so this
 * only has to undo the book face's `onum`.
 */
internal fun liningFigures(features: String?): String =
    listOfNotNull(features?.takeIf(String::isNotBlank), "'onum' 0, 'lnum' 1").joinToString(", ")

/** All-caps labels: lining figures, and `case` lifts punctuation to the capitals. */
internal fun TextStyle.forCaps(): TextStyle =
    copy(fontFeatureSettings = liningFigures(fontFeatureSettings) + ", 'case' 1")

/**
 * How Timeless text is positioned. The variable fonts are unhinted and carry
 * no `gasp` table, so Android's default static mode — whole-pixel advances —
 * rounds each glyph differently at every scale, and letter spacing visibly
 * jitters while a sheet stretches or a page turns. Linear metrics with
 * subpixel positioning (`TextMotion.Animated`) keep the designed spacing at
 * any size or transform. The classic profile keeps the platform default it
 * was compared at.
 */
internal val BookTextMotion: TextMotion
    @Composable get() = if (LocalQuranTypePalette.current.profile == QuranTypeProfile.TIMELESS) {
        TextMotion.Animated
    } else {
        TextMotion.Static
    }

/**
 * Letterspacing for a mixed-case Latin line, in em. Timeless Text is spaced
 * for small sizes already, and tracked lowercase falls apart into letters, so
 * it is set all but solid (DESIGN.md: lowercase never past 0.02 em). Only
 * ALL-CAPS labels are tracked. The classic profile keeps its [classicEm].
 */
@Composable
internal fun mixedCaseTrackingEm(classicEm: Float): Float =
    if (LocalQuranTypePalette.current.profile == QuranTypeProfile.TIMELESS) {
        minOf(classicEm, MIXED_CASE_TRACKING_EM)
    } else {
        classicEm
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

/**
 * Small titles (≤ 18 sp) are set in the Text master, not the display one:
 * the display master's hairlines are drawn for large sizes, and unhinted they
 * break up at reading size. Classic keeps Cormorant.
 */
val SmallTitleFontFamily: FontFamily
    @Composable get() = LocalQuranTypePalette.current.let {
        if (it.profile == QuranTypeProfile.TIMELESS) it.serif else it.display
    }

private val MaterialSans = Typography()

/**
 * The shipped scale: serif sizes are ~0.88 of the previous Garamond values,
 * while Material's sans slots take Timeless Sans.
 */
private val TimelessQuranTypography = Typography(
    displayLarge = MaterialSans.displayLarge.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    displayMedium = MaterialSans.displayMedium.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    displaySmall = MaterialSans.displaySmall.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    headlineLarge = MaterialSans.headlineLarge.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    headlineMedium = TextStyle(
        fontFamily = TimelessDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = BOOK_FEATURES,
        textMotion = TextMotion.Animated,
    ),
    headlineSmall = MaterialSans.headlineSmall.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    titleLarge = TextStyle(
        fontFamily = TimelessDisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.5.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = BOOK_FEATURES,
        textMotion = TextMotion.Animated,
    ),
    titleMedium = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = BOOK_FEATURES,
        textMotion = TextMotion.Animated,
    ),
    titleSmall = MaterialSans.titleSmall.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    bodyLarge = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = BOOK_FEATURES,
        textMotion = TextMotion.Animated,
    ),
    bodyMedium = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = BOOK_FEATURES,
        textMotion = TextMotion.Animated,
    ),
    bodySmall = MaterialSans.bodySmall.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    labelLarge = MaterialSans.labelLarge.copy(fontFamily = TimelessSansFontFamily, fontFeatureSettings = UI_FEATURES, textMotion = TextMotion.Animated),
    labelMedium = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = liningFigures(BOOK_FEATURES),
        textMotion = TextMotion.Animated,
    ),
    labelSmall = TextStyle(
        fontFamily = TimelessSerifFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = liningFigures(BOOK_FEATURES),
        textMotion = TextMotion.Animated,
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
    scribe = timelessNotes(TypographyTuning().note),
)

private val ClassicTypePalette = QuranTypePalette(
    profile = QuranTypeProfile.CLASSIC,
    serif = ClassicSerifFontFamily,
    display = ClassicDisplayFontFamily,
    sans = ClassicSansFontFamily,
    typography = ClassicQuranTypography,
    scribe = ClassicScribeFontFamily,
)

internal val LocalQuranTypePalette = staticCompositionLocalOf { TimelessTypePalette }

/**
 * The English leaf's leading. [ENGLISH_LEAF_LEADING_EM] was set for EB
 * Garamond's 0.40 em x-height; Timeless stands at 0.52, so the same leading
 * left less white between its lines than between Garamond's. A little more
 * air costs the leaf a slightly smaller hand, which fits its page either way.
 */
internal val QuranTypePalette.leafLeadingEm: Float
    get() = if (profile == QuranTypeProfile.TIMELESS) TIMELESS_LEAF_LEADING_EM else ENGLISH_LEAF_LEADING_EM

private const val TIMELESS_LEAF_LEADING_EM = 1.46f

internal fun quranTypePalette(
    timeless: Boolean,
    tuning: TypographyTuning = TypographyTuning(),
): QuranTypePalette {
    if (!timeless) return ClassicTypePalette
    if (tuning == TypographyTuning()) return TimelessTypePalette
    val book = timelessSerif(tuning.book, tuning.bookItalic)
    val title = timelessSerif(tuning.title, display = true)
    val ui = timelessSans(tuning.ui)
    fun tune(style: TextStyle, family: FontFamily, face: TypefaceTuning, lining: Boolean = false) = style.copy(
        fontFamily = family,
        fontSize = style.fontSize * face.size,
        lineHeight = style.lineHeight * face.size * face.leading,
        letterSpacing = (if (style.letterSpacing.isEm) style.letterSpacing.value else
            style.letterSpacing.value / style.fontSize.value).let {
            ((if (it.isFinite()) it else 0f) + face.tracking).em
        },
        fontFeatureSettings = if (lining) liningFigures(face.featureSettings) else face.featureSettings,
    )
    val base = TimelessQuranTypography
    return QuranTypePalette(
        QuranTypeProfile.TIMELESS, book, title, ui,
        base.copy(
            displayLarge = tune(base.displayLarge, ui, tuning.ui),
            displayMedium = tune(base.displayMedium, ui, tuning.ui),
            displaySmall = tune(base.displaySmall, ui, tuning.ui),
            headlineLarge = tune(base.headlineLarge, ui, tuning.ui),
            headlineMedium = tune(base.headlineMedium, title, tuning.title),
            headlineSmall = tune(base.headlineSmall, ui, tuning.ui),
            titleLarge = tune(base.titleLarge, title, tuning.title),
            titleMedium = tune(base.titleMedium, book, tuning.book),
            titleSmall = tune(base.titleSmall, ui, tuning.ui),
            bodyLarge = tune(base.bodyLarge, book, tuning.book),
            bodyMedium = tune(base.bodyMedium, book, tuning.book),
            bodySmall = tune(base.bodySmall, ui, tuning.ui),
            labelLarge = tune(base.labelLarge, ui, tuning.ui),
            labelMedium = tune(base.labelMedium, book, tuning.book, lining = true),
            labelSmall = tune(base.labelSmall, book, tuning.book, lining = true),
        ),
        timelessNotes(tuning.note), tuning,
    )
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
