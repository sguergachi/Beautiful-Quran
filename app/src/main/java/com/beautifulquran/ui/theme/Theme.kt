package com.beautifulquran.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.beautifulquran.data.ColorSystem
import com.beautifulquran.data.ThemeMode

// "Paper" light palette
private val PaperBackground = Color(0xFFFAF3E8)
private val PaperSurface = Color(0xFFFFFBF2)
private val PaperSurfaceHigh = Color(0xFFF2E7D5)
private val Ink = Color(0xFF1C1B18)
private val InkMuted = Color(0xFF6E6858)
private val DeepGreen = Color(0xFF0E5C4A)
private val DeepGreenDim = Color(0xFF9BB8AF)
private val DeepGreenPale = Color(0xFFD7E8E1)
private val DeepGreenFaint = Color(0xFFE8F3EE)

// "Night prayer" dark palette.
// The sheet is a warm charcoal, not a blue-grey: it carries the same parchment
// ink the paper theme does, and a sheet that reads cool under warm ink is two
// materials rather than one. Lightness is untouched from the blue-cast values
// these replace — only the cast changed.
private val NightBackground = Color(0xFF0C0B09)
private val NightSurface = Color(0xFF141310)
private val NightSurfaceHigh = Color(0xFF1E1C18)
private val NightInk = Color(0xFFF3ECDF)
private val NightInkMuted = Color(0xFFCBC4B3)
private val NightGreen = Color(0xFF88C0AC)

// "Royal green" dark palette
private val RoyalGreenBackground = Color(0xFF062C24)
private val RoyalGreenSurface = Color(0xFF0A382E)
private val RoyalGreenSurfaceHigh = Color(0xFF10483B)
private val RoyalInk = Color(0xFFF8F2E4)
private val RoyalInkMuted = Color(0xFFD0CAB8)
private val RoyalGreenAccent = Color(0xFF8CC6B1)

// The entrance cover's parchment is not a theme ink: it is set on fixed leather
// and keeps the value it has always had.
private val Parchment = Color(0xFFE8E2D5)

/**
 * How dark a mark sits on the sheet — the app's one ladder of ink weights.
 *
 * Weight is hierarchy here, so a rung has to mean the same thing on cream as it
 * does on charcoal. Writing that as an alpha does not survive the change of
 * polarity: the same `onSurface.copy(alpha = 0.55f)` that reads as quiet
 * metadata on paper all but disappears on Nightfall, because contrast against a
 * near-black sheet falls away far faster than against a bright one. So each
 * rung is pinned to a **perceptual** target instead — APCA lightness contrast,
 * which unlike a WCAG ratio scores light-on-dark and dark-on-light differently
 * — and the alpha that reaches it is solved separately for every theme. Those
 * solved alphas are the numbers below; `ColorSystemTest` re-derives them and
 * fails if a rung drifts off its target.
 *
 * Rungs down to [quiet] carry words. [furniture] and below never do — they are
 * rules, grounds and icon strokes, and they sit under the floor where type
 * stays readable. Putting a label in [furniture] is the mistake this ladder
 * exists to make visible.
 *
 * The ladder fades warm: the upper rungs are cut from `onSurface`, the lower
 * ones from the warmer `onSurfaceVariant`, so ink going quiet goes brown the
 * way ink on paper does rather than simply going grey.
 */
@androidx.compose.runtime.Immutable
data class QuranInk(
    /** The revelation. The darkest thing on the sheet, full strength. */
    val scripture: Color,
    /** Titles, chapter names, the one word a screen is about. */
    val strong: Color,
    /** Translation and prose set to be read at length. */
    val body: Color,
    /** Supporting text, and controls the listener actually reaches for. */
    val secondary: Color,
    /** Subtitles, and gold's text weight. */
    val tertiary: Color,
    /** Metadata: a chapter's gloss and ayah count, a citation. */
    val muted: Color,
    /** Running head, folio, controls that choose rather than do. The quietest
     *  rung that may still carry a word. */
    val quiet: Color,
    /** Icon strokes and rules that carry meaning. Not for type. */
    val furniture: Color,
    /** Hairlines, tooled grounds, ornament. Not for type. */
    val hairline: Color,
    /** Tints and washes barely off the paper. Not for type. */
    val wash: Color,
)

val LocalQuranInk = staticCompositionLocalOf { LightInk }

/**
 * The app's own half of the theme, reached the way [MaterialTheme] is.
 *
 * `QuranTheme.ink.muted` inside any composable; hoist it into a local first if
 * the colour is needed inside a draw or measure lambda, which is not composable.
 */
object QuranTheme {
    val ink: QuranInk
        @Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalQuranInk.current

    val accents: QuranAccents
        @Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalQuranAccents.current
}

/**
 * Accent colors that sit outside the Material scheme.
 * Gold is never flat: [goldBright]/[goldDeep] are the gilding gradient stops,
 * and [embossDark]/[embossLight] are the relief shadows for ornament pressed
 * into the paper (light from the upper-left).
 */
data class QuranAccents(
    val gold: Color,
    /** Gold that has to carry a word rather than illuminate one — chapter
     * numbers, result counts, a corrected query. [gold] is set for illumination
     * and at label sizes it falls under the ink ladder's text floor on cream;
     * this is the same hue solved to the [QuranInk.tertiary] weight instead, so
     * gold can label something without becoming unreadable on paper. */
    val goldInk: Color,
    val goldBright: Color,
    val goldDeep: Color,
    /** The app's green at the ink ladder's [QuranInk.secondary] weight — an
     * Arabic chapter name, a link. The scheme's `primary` is the green's
     * identity and is set for fills, where it may be as strong as its sheet
     * allows; these two are the green set to be *read*, and like every rung
     * they are solved per theme so the same label does not read boldly on cream
     * and faintly at night. */
    val greenInk: Color,
    /** The green at [QuranInk.muted] weight — a section label over a band. */
    val greenQuiet: Color,
    /** The green barely off the paper: the ground a band is tinted with. */
    val greenWash: Color,
    val embossDark: Color,
    val embossLight: Color,
    /** Warm ink for words the reciter is repeating — a second, orange fade that
     * dissolves back to normal ink once the recitation moves past the repeat. */
    val repeatInk: Color,
    /** Ruby ribbon of a saved verse — the bookmark's ink, drawn inside each
     * ayah block. Deliberately a
     * third hue, distinct from [gold] (selection/ornament) and [repeatInk]
     * (recitation), so "my marks" never reads as navigation or playback. */
    val bookmarkRibbon: Color,
    /** Dark maroon inky red for the reader's annotation prose. */
    val annotationInk: Color,
    /** Freshly laid ink's white-gold sheen — the first-gloss glint a newly
     * read word wears for a breath before drying to plain recited ink
     * (InkEngine.glinting). Null on themes without the effect; Nightfall and
     * Royal Green share the accent. */
    val glintInk: Color? = null,
)

val LocalQuranAccents = staticCompositionLocalOf { LightAccents }

private val LightColors: ColorScheme = lightColorScheme(
    primary = DeepGreen,
    onPrimary = Color.White,
    primaryContainer = DeepGreenPale,
    onPrimaryContainer = Color(0xFF06382D),
    inversePrimary = Color(0xFF7FB8A4),
    secondary = DeepGreen,
    onSecondary = Color.White,
    secondaryContainer = DeepGreenFaint,
    onSecondaryContainer = Color(0xFF073E32),
    tertiary = Color(0xFF2F6F5B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0EFE9),
    onTertiaryContainer = Color(0xFF0A3E32),
    background = PaperBackground,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = InkMuted,
    surfaceTint = DeepGreen,
    inverseSurface = Color(0xFF32302A),
    inverseOnSurface = Color(0xFFF5ECDC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFC0B49E),
    outlineVariant = Color(0xFFDACCB6),
    scrim = Color.Black,
    surfaceBright = PaperSurface,
    surfaceDim = Color(0xFFE8DCC5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF5E9),
    surfaceContainer = PaperSurfaceHigh,
    surfaceContainerHigh = Color(0xFFEDE1CC),
    surfaceContainerHighest = Color(0xFFE8DCC5),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = NightGreen,
    onPrimary = Color(0xFF06382D),
    primaryContainer = Color(0xFF2B2822),
    onPrimaryContainer = Color(0xFFE7E4DC),
    inversePrimary = DeepGreen,
    secondary = NightGreen,
    onSecondary = NightBackground,
    secondaryContainer = Color(0xFF272420),
    onSecondaryContainer = Color(0xFFE3E0D8),
    tertiary = Color(0xFFC6C3BA),
    onTertiary = Color(0xFF232220),
    tertiaryContainer = Color(0xFF32302A),
    onTertiaryContainer = Color(0xFFE9E7DF),
    background = NightBackground,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightInkMuted,
    surfaceTint = NightGreen,
    inverseSurface = NightInk,
    inverseOnSurface = Color(0xFF2F332B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF817D74),
    outlineVariant = Color(0xFF625E57),
    scrim = Color.Black,
    surfaceBright = Color(0xFF37332C),
    surfaceDim = NightBackground,
    surfaceContainerLowest = Color(0xFF070604),
    surfaceContainerLow = Color(0xFF11100D),
    surfaceContainer = NightSurfaceHigh,
    surfaceContainerHigh = Color(0xFF2A2721),
    surfaceContainerHighest = Color(0xFF37332C),
)

private val RoyalGreenColors: ColorScheme = darkColorScheme(
    primary = RoyalGreenAccent,
    onPrimary = Color(0xFF06382D),
    primaryContainer = Color(0xFF0E5C4A),
    onPrimaryContainer = Color(0xFFD7E8E1),
    inversePrimary = DeepGreen,
    secondary = RoyalGreenAccent,
    onSecondary = RoyalGreenBackground,
    secondaryContainer = Color(0xFF244E43),
    onSecondaryContainer = Color(0xFFD7E8E1),
    tertiary = Color(0xFF9BC9B8),
    onTertiary = Color(0xFF0B332A),
    tertiaryContainer = Color(0xFF21493E),
    onTertiaryContainer = Color(0xFFE0EFE9),
    background = RoyalGreenBackground,
    onBackground = RoyalInk,
    surface = RoyalGreenSurface,
    onSurface = RoyalInk,
    surfaceVariant = RoyalGreenSurfaceHigh,
    onSurfaceVariant = RoyalInkMuted,
    surfaceTint = RoyalGreenAccent,
    inverseSurface = RoyalInk,
    inverseOnSurface = Color(0xFF2F332B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF608C80),
    outlineVariant = Color(0xFF3C6F61),
    scrim = Color.Black,
    surfaceBright = Color(0xFF1C5E4E),
    surfaceDim = RoyalGreenBackground,
    surfaceContainerLowest = Color(0xFF04221C),
    surfaceContainerLow = Color(0xFF083229),
    surfaceContainer = RoyalGreenSurfaceHigh,
    surfaceContainerHigh = Color(0xFF155242),
    surfaceContainerHighest = Color(0xFF1A5D4B),
)

// The ladder, rung by rung. The alpha on each line is the one that lands that
// theme's ink on the rung's perceptual target; the targets themselves live in
// `ColorSystemTest.LADDER`, which is what keeps these honest.
private val LightInk = QuranInk(
    scripture = Ink,
    strong = Ink.copy(alpha = 0.863f),
    body = Ink.copy(alpha = 0.741f),
    secondary = Ink.copy(alpha = 0.655f),
    tertiary = InkMuted.copy(alpha = 0.860f),
    muted = InkMuted.copy(alpha = 0.709f),
    quiet = InkMuted.copy(alpha = 0.608f),
    furniture = InkMuted.copy(alpha = 0.446f),
    hairline = InkMuted.copy(alpha = 0.277f),
    wash = InkMuted.copy(alpha = 0.126f),
)

private val NightInkLadder = QuranInk(
    scripture = NightInk,
    strong = NightInk.copy(alpha = 0.960f),
    body = NightInk.copy(alpha = 0.890f),
    secondary = NightInk.copy(alpha = 0.829f),
    tertiary = NightInkMuted.copy(alpha = 0.916f),
    muted = NightInkMuted.copy(alpha = 0.819f),
    quiet = NightInkMuted.copy(alpha = 0.743f),
    furniture = NightInkMuted.copy(alpha = 0.614f),
    hairline = NightInkMuted.copy(alpha = 0.453f),
    wash = NightInkMuted.copy(alpha = 0.280f),
)

private val RoyalInkLadder = QuranInk(
    scripture = RoyalInk,
    strong = RoyalInk.copy(alpha = 0.952f),
    body = RoyalInk.copy(alpha = 0.876f),
    secondary = RoyalInk.copy(alpha = 0.811f),
    tertiary = RoyalInkMuted.copy(alpha = 0.903f),
    muted = RoyalInkMuted.copy(alpha = 0.800f),
    quiet = RoyalInkMuted.copy(alpha = 0.718f),
    furniture = RoyalInkMuted.copy(alpha = 0.573f),
    hairline = RoyalInkMuted.copy(alpha = 0.402f),
    wash = RoyalInkMuted.copy(alpha = 0.212f),
)

private val LightAccents = QuranAccents(
    gold = Color(0xFFA8831D),
    goldInk = Color(0xFF9F7B0B),
    goldBright = Color(0xFFC9AE67),
    goldDeep = Color(0xFF7A5B05),
    greenInk = Color(0xFF2B725F),
    greenQuiet = Color(0xFF5F9C8A),
    greenWash = Color(0xFFB8EDDB),
    embossDark = Color(0x24000000),
    embossLight = Color(0x59FFFFFF),
    repeatInk = Color(0xFFB4551E),
    // Deep ruby: saturated enough to sit as ink on warm paper without glowing.
    bookmarkRibbon = Color(0xFFB3122F),
    // Dark maroon inky red — dried red ink on warm paper, not pale rose.
    annotationInk = Color(0xFF6B2838),
)

private val DarkAccents = QuranAccents(
    gold = Color(0xFFCAA547),
    goldInk = Color(0xFFD2AD4F),
    goldBright = Color(0xFFECD189),
    goldDeep = Color(0xFF9A7B32),
    greenInk = Color(0xFF8FD3BC),
    greenQuiet = Color(0xFF72AF9A),
    greenWash = Color(0xFF134738),
    embossDark = Color(0x66000000),
    embossLight = Color(0x1FFFFFFF),
    // Deep saturated orange — reads as warm ink on charcoal/green, not a pale
    // peach wash. Strength is further tunable via InkEngine.Tuning.repeatInkAlpha.
    // Lifted to the ink ladder's tertiary weight: at the old value this warning
    // that the reciter is repeating a word was a third weaker at night than on
    // paper, which is backwards for a signal the reader has to catch.
    repeatInk = Color(0xFFFF8948),
    // Lighter, less-saturated ruby so it lifts off near-black and deep green
    // instead of muddying into the surface.
    bookmarkRibbon = Color(0xFFFF6672),
    annotationInk = Color(0xFFF0A5B1),
    // White gold: a breath brighter than the page's own ink, so fresh ink
    // glints on the night page without shouting.
    glintInk = Color(0xFFFFF0C7),
)

/** Royal Green's paper is a full step lighter than Nightfall's, so it cannot
 * share Nightfall's inks and still read at the same weight — each accent is
 * solved against its own sheet. */
private val RoyalGreenAccents = QuranAccents(
    gold = Color(0xFFD0AA4D),
    goldInk = Color(0xFFD7B255),
    goldBright = Color(0xFFF2D68F),
    goldDeep = Color(0xFFA07F38),
    greenInk = Color(0xFF95D9C1),
    greenQuiet = Color(0xFF78B49F),
    greenWash = Color(0xFF1F5141),
    embossDark = Color(0x66000000),
    embossLight = Color(0x1FFFFFFF),
    repeatInk = Color(0xFFFF9359),
    bookmarkRibbon = Color(0xFFFF727B),
    annotationInk = Color(0xFFF4A4B2),
    glintInk = Color(0xFFFFF7DE),
)

// ---------------------------------------------------------------------------
// Legacy palette — the colours as they stood before the ink ladder.
//
// Kept whole so the two systems can be held against each other on a device
// (Settings -> Developer -> "Legacy colours"). Everything below is the old
// value verbatim; nothing here is solved or corrected, which is the point.
//
// The ladder reconstruction is approximate by necessity: 54 hand-picked alphas
// were collapsed onto 10 rungs, so each legacy rung carries the alpha that the
// majority of that rung's call sites used to have. Sites that once differed by
// a point or two of alpha come back identical. See [ColorSystem].
// ---------------------------------------------------------------------------

private val LegacyParchment = Color(0xFFE8E2D5)
private val LegacyParchmentMuted = Color(0xFF97917F)
private val LegacySoftGreen = Color(0xFF7FB8A4)
private val LegacyNightBackground = Color(0xFF0A0B0C)
private val LegacyNightSurface = Color(0xFF111315)
private val LegacyNightSurfaceHigh = Color(0xFF1A1D20)

private val LegacyDarkColors: ColorScheme = DarkColors.copy(
    primary = LegacySoftGreen,
    primaryContainer = Color(0xFF282D30),
    onPrimaryContainer = Color(0xFFE4E8E5),
    secondary = LegacySoftGreen,
    onSecondary = LegacyNightBackground,
    secondaryContainer = Color(0xFF24282B),
    onSecondaryContainer = Color(0xFFE0E4E1),
    tertiary = Color(0xFFC1C7C4),
    onTertiary = Color(0xFF202426),
    tertiaryContainer = Color(0xFF2E3336),
    onTertiaryContainer = Color(0xFFE6E9E7),
    background = LegacyNightBackground,
    onBackground = LegacyParchment,
    surface = LegacyNightSurface,
    onSurface = LegacyParchment,
    surfaceVariant = LegacyNightSurfaceHigh,
    onSurfaceVariant = LegacyParchmentMuted,
    surfaceTint = LegacySoftGreen,
    inverseSurface = LegacyParchment,
    outline = Color(0xFF686E72),
    outlineVariant = Color(0xFF303538),
    surfaceBright = Color(0xFF303438),
    surfaceDim = LegacyNightBackground,
    surfaceContainerLowest = Color(0xFF050607),
    surfaceContainerLow = Color(0xFF0E1012),
    surfaceContainer = LegacyNightSurfaceHigh,
    surfaceContainerHigh = Color(0xFF24282B),
    surfaceContainerHighest = Color(0xFF303438),
)

private val LegacyRoyalGreenColors: ColorScheme = RoyalGreenColors.copy(
    primary = LegacySoftGreen,
    secondary = LegacySoftGreen,
    onBackground = LegacyParchment,
    onSurface = LegacyParchment,
    onSurfaceVariant = LegacyParchmentMuted,
    surfaceTint = LegacySoftGreen,
    inverseSurface = LegacyParchment,
    outline = Color(0xFF568276),
    outlineVariant = Color(0xFF2C5E51),
    surfaceBright = Color(0xFF1C5E4E),
)

/** Rung -> the alpha the majority of that rung's old call sites carried. */
private fun legacyInk(strongBase: Color, quietBase: Color) = QuranInk(
    scripture = strongBase,
    strong = strongBase.copy(alpha = 0.86f),
    body = strongBase.copy(alpha = 0.74f),
    secondary = strongBase.copy(alpha = 0.68f),
    tertiary = quietBase.copy(alpha = 0.85f),
    muted = quietBase.copy(alpha = 0.70f),
    quiet = quietBase.copy(alpha = 0.55f),
    furniture = quietBase.copy(alpha = 0.45f),
    hairline = quietBase.copy(alpha = 0.25f),
    wash = quietBase.copy(alpha = 0.12f),
)

private val LegacyLightInk = legacyInk(Ink, InkMuted)
private val LegacyDarkInk = legacyInk(LegacyParchment, LegacyParchmentMuted)

/** The old accents. [QuranAccents.goldInk] and the green rungs did not exist,
 * so they are filled with the faded gold and green their call sites used to
 * apply inline — which is exactly the thing the new values replaced. */
private fun legacyAccents(green: Color, glint: Color?) = QuranAccents(
    gold = if (glint == null) Color(0xFFB8901C) else Color(0xFFD9B44A),
    goldInk = (if (glint == null) Color(0xFFB8901C) else Color(0xFFD9B44A)).copy(alpha = 0.75f),
    goldBright = if (glint == null) Color(0xFFE9CD7A) else Color(0xFFEDD188),
    goldDeep = if (glint == null) Color(0xFF8A6B1E) else Color(0xFF9A7B2A),
    embossDark = if (glint == null) Color(0x24000000) else Color(0x66000000),
    embossLight = if (glint == null) Color(0x59FFFFFF) else Color(0x1FFFFFFF),
    repeatInk = if (glint == null) Color(0xFFB4551E) else Color(0xFFE06A18),
    bookmarkRibbon = if (glint == null) Color(0xFFB3122F) else Color(0xFFD64358),
    annotationInk = if (glint == null) Color(0xFF6B2838) else Color(0xFFF0A5B1),
    greenInk = green.copy(alpha = 0.88f),
    greenQuiet = green.copy(alpha = 0.75f),
    greenWash = green.copy(alpha = 0.10f),
    glintInk = glint,
)

private val LegacyLightAccents = legacyAccents(DeepGreen, null)
private val LegacyDarkAccents = legacyAccents(LegacySoftGreen, Color(0xFFF8E9BE))

/** Which colour system the surrounding content is painted in. */
val LocalColorSystem = staticCompositionLocalOf { ColorSystem.LADDER }

private fun legacySchemeFor(themeMode: ThemeMode, systemDark: Boolean): ColorScheme =
    when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) LegacyDarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> LegacyDarkColors
        ThemeMode.ROYAL_GREEN -> LegacyRoyalGreenColors
    }

@Composable
fun themePreviewColors(themeMode: ThemeMode): List<Color> {
    val systemDark = isSystemInDarkTheme()
    return when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) {
            listOf(NightBackground, NightSurfaceHigh, NightGreen, NightInk)
        } else {
            listOf(PaperBackground, PaperSurfaceHigh, DeepGreen, Ink)
        }
        ThemeMode.LIGHT -> listOf(PaperBackground, PaperSurfaceHigh, DeepGreen, Ink)
        ThemeMode.DARK -> listOf(NightBackground, NightSurfaceHigh, NightGreen, NightInk)
        ThemeMode.ROYAL_GREEN ->
            listOf(RoyalGreenBackground, RoyalGreenSurfaceHigh, RoyalGreenAccent, RoyalInk)
    }
}

/** Contrasting overlay palette for ink-bleed surfaces (repeat, lab, root viewer
 * overlays on the reader): always Royal Green so they read as a distinct
 * surface — except under the Royal Green theme itself, where Nightfall
 * provides the contrast instead. */
@Composable
fun contrastingOverlayColorScheme(themeMode: ThemeMode): ColorScheme =
    if (LocalColorSystem.current == ColorSystem.LEGACY) {
        when (themeMode) {
            ThemeMode.ROYAL_GREEN -> LegacyDarkColors
            else -> LegacyRoyalGreenColors
        }
    } else {
        when (themeMode) {
            ThemeMode.ROYAL_GREEN -> DarkColors
            else -> RoyalGreenColors
        }
    }

/** The ink ladder belonging to a [contrastingOverlayColorScheme]. An overlay
 * swaps the whole sheet out from under its content, so it has to swap the
 * ladder too — the reader's own rungs are solved against the reader's paper
 * and land on the wrong weights here. */
@Composable
fun contrastingOverlayInk(themeMode: ThemeMode): QuranInk =
    if (LocalColorSystem.current == ColorSystem.LEGACY) {
        LegacyDarkInk
    } else {
        when (themeMode) {
            ThemeMode.ROYAL_GREEN -> NightInkLadder
            else -> RoyalInkLadder
        }
    }

/** The accents belonging to a [contrastingOverlayColorScheme]. */
@Composable
fun contrastingOverlayAccents(): QuranAccents =
    if (LocalColorSystem.current == ColorSystem.LEGACY) LegacyDarkAccents else TimingsLabAccents

/** Fixed royal-green ink surface for contextual teaching blooms. */
@Composable
fun royalGreenOverlayColorScheme(): ColorScheme =
    if (LocalColorSystem.current == ColorSystem.LEGACY) LegacyRoyalGreenColors else RoyalGreenColors

/** The ladder that goes with [royalGreenOverlayColorScheme]. */
@Composable
fun royalGreenOverlayInk(): QuranInk =
    if (LocalColorSystem.current == ColorSystem.LEGACY) LegacyDarkInk else RoyalInkLadder

/** These contrasting overlays are always a dark surface, so their gold/orange
 * accents use the dark set regardless of the user's theme — the reader's own
 * accents are tuned for a light page and wash out on this green/night surface. */
val TimingsLabAccents: QuranAccents = RoyalGreenAccents

/** The entrance cover is always deep-green leather regardless of theme, so
 * its gilding always uses the Royal Green set (same reasoning as
 * [TimingsLabAccents]): [CoverLeatherCenter] is that theme's own surface, and
 * since the two dark sets were split apart Nightfall's gold is solved against
 * a near-black sheet this leather is nothing like. */
val CoverAccents: QuranAccents = RoyalGreenAccents

/** The entrance cover's leather — fixed, not themed: a bound mushaf keeps its
 * own boards whatever paper the reader prefers inside. Center/edge stops of
 * the leather's radial shading, and the parchment its title is inked in. */
val CoverLeatherCenter = Color(0xFF0A382E)
val CoverLeatherEdge = Color(0xFF031C15)
val CoverParchment = Parchment

@Composable
fun BeautifulQuranTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorSystem: ColorSystem = ColorSystem.LADDER,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val legacy = colorSystem == ColorSystem.LEGACY
    val darkish = themeMode == ThemeMode.DARK ||
        themeMode == ThemeMode.ROYAL_GREEN ||
        (themeMode == ThemeMode.SYSTEM && systemDark)
    val accents = if (legacy) {
        if (darkish) LegacyDarkAccents else LegacyLightAccents
    } else when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkAccents else LightAccents
        ThemeMode.LIGHT -> LightAccents
        ThemeMode.DARK -> DarkAccents
        ThemeMode.ROYAL_GREEN -> RoyalGreenAccents
    }
    val ink = if (legacy) {
        if (darkish) LegacyDarkInk else LegacyLightInk
    } else when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) NightInkLadder else LightInk
        ThemeMode.LIGHT -> LightInk
        ThemeMode.DARK -> NightInkLadder
        ThemeMode.ROYAL_GREEN -> RoyalInkLadder
    }
    val colors = if (legacy) legacySchemeFor(themeMode, systemDark) else when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.ROYAL_GREEN -> RoyalGreenColors
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalQuranAccents provides accents,
        LocalQuranInk provides ink,
        LocalColorSystem provides colorSystem,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = QuranTypography,
            content = content,
        )
    }
}

/** Every theme's three parts in one place, for `ColorSystemTest`, which holds
 * the palette to the perceptual targets it was solved against. */
internal fun legacyThemeParts(themeMode: ThemeMode): Triple<ColorScheme, QuranInk, QuranAccents> {
    val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.ROYAL_GREEN
    return Triple(
        legacySchemeFor(themeMode, systemDark = false),
        if (dark) LegacyDarkInk else LegacyLightInk,
        if (dark) LegacyDarkAccents else LegacyLightAccents,
    )
}

internal fun quranThemeParts(
    themeMode: ThemeMode,
    systemDark: Boolean = false,
): Triple<ColorScheme, QuranInk, QuranAccents> = when (themeMode) {
    ThemeMode.SYSTEM ->
        if (systemDark) Triple(DarkColors, NightInkLadder, DarkAccents)
        else Triple(LightColors, LightInk, LightAccents)
    ThemeMode.LIGHT -> Triple(LightColors, LightInk, LightAccents)
    ThemeMode.DARK -> Triple(DarkColors, NightInkLadder, DarkAccents)
    ThemeMode.ROYAL_GREEN -> Triple(RoyalGreenColors, RoyalInkLadder, RoyalGreenAccents)
}
