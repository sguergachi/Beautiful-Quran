package com.beautifulquran.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

// "Night prayer" dark palette
private val NightBackground = Color(0xFF0A0B0C)
private val NightSurface = Color(0xFF111315)
private val NightSurfaceHigh = Color(0xFF1A1D20)
private val Parchment = Color(0xFFE8E2D5)
private val ParchmentMuted = Color(0xFF97917F)
private val SoftGreen = Color(0xFF7FB8A4)

// "Royal green" dark palette
private val RoyalGreenBackground = Color(0xFF062C24)
private val RoyalGreenSurface = Color(0xFF0A382E)
private val RoyalGreenSurfaceHigh = Color(0xFF10483B)

/**
 * Accent colors that sit outside the Material scheme.
 * Gold is never flat: [goldBright]/[goldDeep] are the gilding gradient stops,
 * and [embossDark]/[embossLight] are the relief shadows for ornament pressed
 * into the paper (light from the upper-left).
 */
data class QuranAccents(
    val gold: Color,
    val goldBright: Color,
    val goldDeep: Color,
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

val LocalQuranAccents = staticCompositionLocalOf {
    QuranAccents(
        gold = Color(0xFFC9A227),
        goldBright = Color(0xFFE9CD7A),
        goldDeep = Color(0xFF8A6B1E),
        embossDark = Color(0x24000000),
        embossLight = Color(0x59FFFFFF),
        repeatInk = Color(0xFFC2622A),
        bookmarkRibbon = Color(0xFFB3122F),
        annotationInk = Color(0xFF6B2838),
    )
}

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
    primary = SoftGreen,
    onPrimary = Color(0xFF06382D),
    primaryContainer = Color(0xFF282D30),
    onPrimaryContainer = Color(0xFFE4E8E5),
    inversePrimary = DeepGreen,
    secondary = SoftGreen,
    onSecondary = NightBackground,
    secondaryContainer = Color(0xFF24282B),
    onSecondaryContainer = Color(0xFFE0E4E1),
    tertiary = Color(0xFFC1C7C4),
    onTertiary = Color(0xFF202426),
    tertiaryContainer = Color(0xFF2E3336),
    onTertiaryContainer = Color(0xFFE6E9E7),
    background = NightBackground,
    onBackground = Parchment,
    surface = NightSurface,
    onSurface = Parchment,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = ParchmentMuted,
    surfaceTint = SoftGreen,
    inverseSurface = Color(0xFFE8E2D5),
    inverseOnSurface = Color(0xFF2F332B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF686E72),
    outlineVariant = Color(0xFF303538),
    scrim = Color.Black,
    surfaceBright = Color(0xFF303438),
    surfaceDim = NightBackground,
    surfaceContainerLowest = Color(0xFF050607),
    surfaceContainerLow = Color(0xFF0E1012),
    surfaceContainer = NightSurfaceHigh,
    surfaceContainerHigh = Color(0xFF24282B),
    surfaceContainerHighest = Color(0xFF303438),
)

private val RoyalGreenColors: ColorScheme = darkColorScheme(
    primary = SoftGreen,
    onPrimary = Color(0xFF06382D),
    primaryContainer = Color(0xFF0E5C4A),
    onPrimaryContainer = Color(0xFFD7E8E1),
    inversePrimary = DeepGreen,
    secondary = SoftGreen,
    onSecondary = RoyalGreenBackground,
    secondaryContainer = Color(0xFF244E43),
    onSecondaryContainer = Color(0xFFD7E8E1),
    tertiary = Color(0xFF9BC9B8),
    onTertiary = Color(0xFF0B332A),
    tertiaryContainer = Color(0xFF21493E),
    onTertiaryContainer = Color(0xFFE0EFE9),
    background = RoyalGreenBackground,
    onBackground = Parchment,
    surface = RoyalGreenSurface,
    onSurface = Parchment,
    surfaceVariant = RoyalGreenSurfaceHigh,
    onSurfaceVariant = ParchmentMuted,
    surfaceTint = SoftGreen,
    inverseSurface = Color(0xFFE8E2D5),
    inverseOnSurface = Color(0xFF2F332B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF568276),
    outlineVariant = Color(0xFF2C5E51),
    scrim = Color.Black,
    surfaceBright = Color(0xFF1C5E4E),
    surfaceDim = RoyalGreenBackground,
    surfaceContainerLowest = Color(0xFF04221C),
    surfaceContainerLow = Color(0xFF083229),
    surfaceContainer = RoyalGreenSurfaceHigh,
    surfaceContainerHigh = Color(0xFF155242),
    surfaceContainerHighest = Color(0xFF1A5D4B),
)

private val LightAccents = QuranAccents(
    gold = Color(0xFFB8901C),
    goldBright = Color(0xFFE9CD7A),
    goldDeep = Color(0xFF8A6B1E),
    embossDark = Color(0x24000000),
    embossLight = Color(0x59FFFFFF),
    repeatInk = Color(0xFFB4551E),
    // Deep ruby: saturated enough to sit as ink on warm paper without glowing.
    bookmarkRibbon = Color(0xFFB3122F),
    // Dark maroon inky red — dried red ink on warm paper, not pale rose.
    annotationInk = Color(0xFF6B2838),
)

private val DarkAccents = QuranAccents(
    gold = Color(0xFFD9B44A),
    goldBright = Color(0xFFEDD188),
    goldDeep = Color(0xFF9A7B2A),
    embossDark = Color(0x66000000),
    embossLight = Color(0x1FFFFFFF),
    // Deep saturated orange — reads as warm ink on charcoal/green, not a pale
    // peach wash. Strength is further tunable via InkEngine.Tuning.repeatInkAlpha.
    repeatInk = Color(0xFFE06A18),
    // Lighter, less-saturated ruby so it lifts off near-black and deep green
    // instead of muddying into the surface. Shared by Nightfall and Royal Green.
    bookmarkRibbon = Color(0xFFD64358),
    annotationInk = Color(0xFFF0A5B1),
    // White gold: a breath brighter and warmer than Parchment, so fresh ink
    // glints on the night page without shouting.
    glintInk = Color(0xFFF8E9BE),
)

/** Royal Green shares Nightfall's accent inks, including the fresh-ink glint. */
private val RoyalGreenAccents = DarkAccents

@Composable
fun themePreviewColors(themeMode: ThemeMode): List<Color> {
    val systemDark = isSystemInDarkTheme()
    return when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) {
            listOf(NightBackground, NightSurfaceHigh, SoftGreen, Parchment)
        } else {
            listOf(PaperBackground, PaperSurfaceHigh, DeepGreen, Ink)
        }
        ThemeMode.LIGHT -> listOf(PaperBackground, PaperSurfaceHigh, DeepGreen, Ink)
        ThemeMode.DARK -> listOf(NightBackground, NightSurfaceHigh, SoftGreen, Parchment)
        ThemeMode.ROYAL_GREEN -> listOf(RoyalGreenBackground, RoyalGreenSurfaceHigh, SoftGreen, Parchment)
    }
}

/** Contrasting overlay palette for ink-bleed surfaces (repeat, lab, root viewer
 * overlays on the reader): always Royal Green so they read as a distinct
 * surface — except under the Royal Green theme itself, where Nightfall
 * provides the contrast instead. */
fun contrastingOverlayColorScheme(themeMode: ThemeMode): ColorScheme = when (themeMode) {
    ThemeMode.ROYAL_GREEN -> DarkColors
    else -> RoyalGreenColors
}

/** Fixed royal-green ink surface for contextual teaching blooms. */
fun royalGreenOverlayColorScheme(): ColorScheme = RoyalGreenColors

/** These contrasting overlays are always a dark surface, so their gold/orange
 * accents use the dark set regardless of the user's theme — the reader's own
 * accents are tuned for a light page and wash out on this green/night surface. */
val TimingsLabAccents: QuranAccents = DarkAccents

/** The entrance cover is always deep-green leather regardless of theme, so
 * its gilding always uses the dark set (same reasoning as [TimingsLabAccents]). */
val CoverAccents: QuranAccents = DarkAccents

/** The entrance cover's leather — fixed, not themed: a bound mushaf keeps its
 * own boards whatever paper the reader prefers inside. Center/edge stops of
 * the leather's radial shading, and the parchment its title is inked in. */
val CoverLeatherCenter = Color(0xFF0A382E)
val CoverLeatherEdge = Color(0xFF031C15)
val CoverParchment = Parchment

@Composable
fun BeautifulQuranTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val accents = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkAccents else LightAccents
        ThemeMode.LIGHT -> LightAccents
        ThemeMode.DARK -> DarkAccents
        ThemeMode.ROYAL_GREEN -> RoyalGreenAccents
    }
    val colors = when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.ROYAL_GREEN -> RoyalGreenColors
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalQuranAccents provides accents,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = QuranTypography,
            content = content,
        )
    }
}
