package com.beautifulquran.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.ui.theme.PerceptualColor.apca
import com.beautifulquran.ui.theme.PerceptualColor.contrastOn
import com.beautifulquran.ui.theme.PerceptualColor.hueDistance
import com.beautifulquran.ui.theme.PerceptualColor.oklch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Holds the palette to the perceptual targets it was solved against.
 *
 * The app says what it means with the weight of its ink, so a rung of the ink
 * ladder has to land on the same perceived weight in all three themes. Alpha
 * alone cannot do that — contrast falls away much faster against a near-black
 * sheet than a cream one — so each rung's alpha was solved per theme, and these
 * tests are what stop those solved numbers rotting the next time a colour is
 * nudged by eye.
 *
 * If a number here has to change, change it deliberately: re-solve the alpha
 * against the new target rather than loosening the tolerance.
 */
class ColorSystemTest {

    private val themes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.ROYAL_GREEN)

    private fun parts(mode: ThemeMode) = quranThemeParts(mode)
    private fun scheme(mode: ThemeMode): ColorScheme = parts(mode).first
    private fun ink(mode: ThemeMode): QuranInk = parts(mode).second
    private fun accents(mode: ThemeMode): QuranAccents = parts(mode).third
    private fun paper(mode: ThemeMode): Color = scheme(mode).background

    /** The ladder, and the perceived weight each rung is solved to. */
    private val ladder: List<Pair<String, Double>> = listOf(
        "strong" to 90.0,
        "body" to 80.0,
        "secondary" to 72.0,
        "tertiary" to 62.0,
        "muted" to 52.0,
        "quiet" to 45.0,
        "furniture" to 33.0,
        "hairline" to 20.0,
        "wash" to 8.0,
    )

    private fun rung(ink: QuranInk, name: String): Color = when (name) {
        "scripture" -> ink.scripture
        "strong" -> ink.strong
        "body" -> ink.body
        "secondary" -> ink.secondary
        "tertiary" -> ink.tertiary
        "muted" -> ink.muted
        "quiet" -> ink.quiet
        "furniture" -> ink.furniture
        "hairline" -> ink.hairline
        "wash" -> ink.wash
        else -> error("unknown rung $name")
    }

    /** The lowest rung that may still carry a word. */
    private val textFloor = 45.0

    @Test
    fun `every rung lands on its target weight in every theme`() {
        for (mode in themes) {
            val ink = ink(mode)
            val bg = paper(mode)
            for ((name, target) in ladder) {
                val got = contrastOn(rung(ink, name), bg)
                assertTrue(
                    "$mode ink.$name is Lc %.1f but the ladder puts it at %.1f".format(got, target),
                    abs(got - target) <= 1.5,
                )
            }
        }
    }

    @Test
    fun `scripture is the strongest thing on every sheet`() {
        for (mode in themes) {
            val ink = ink(mode)
            val bg = paper(mode)
            val scripture = contrastOn(ink.scripture, bg)
            assertTrue(
                "$mode scripture is only Lc %.1f — the revelation must be the darkest mark"
                    .format(scripture),
                scripture >= 95.0,
            )
            assertTrue(
                "$mode scripture (Lc %.1f) does not stand clear of strong (Lc %.1f)"
                    .format(scripture, contrastOn(ink.strong, bg)),
                scripture - contrastOn(ink.strong, bg) >= 4.0,
            )
        }
    }

    @Test
    fun `the ladder descends in steps the eye can separate`() {
        for (mode in themes) {
            val ink = ink(mode)
            val bg = paper(mode)
            val rungs = (listOf("scripture" to 0.0) + ladder).map { (n, _) ->
                n to contrastOn(rung(ink, n), bg)
            }
            for ((a, b) in rungs.zipWithNext()) {
                assertTrue(
                    "$mode ${a.first} (Lc %.1f) and ${b.first} (Lc %.1f) are not distinct steps"
                        .format(a.second, b.second),
                    a.second - b.second >= 4.0,
                )
            }
        }
    }

    @Test
    fun `a rung weighs the same whichever theme it is read in`() {
        for ((name, _) in ladder) {
            val across = themes.map { contrastOn(rung(ink(it), name), paper(it)) }
            val spread = across.max() - across.min()
            assertTrue(
                "ink.$name reads at ${across.map { "%.0f".format(it) }} across " +
                    "$themes — a spread of %.0f Lc".format(spread),
                spread <= 3.0,
            )
        }
    }

    @Test
    fun `rungs that carry words stay above the readable floor`() {
        val carryWords = listOf("scripture", "strong", "body", "secondary", "tertiary", "muted", "quiet")
        for (mode in themes) {
            for (name in carryWords) {
                val got = contrastOn(rung(ink(mode), name), paper(mode))
                assertTrue(
                    "$mode ink.$name is Lc %.1f, below the floor where type stays readable"
                        .format(got, textFloor),
                    got >= textFloor - 1.0,
                )
            }
            assertTrue(
                "$mode ink.furniture must sit below the text floor, or the ladder " +
                    "stops warning anyone off using it for type",
                contrastOn(ink(mode).furniture, paper(mode)) < textFloor,
            )
        }
    }

    @Test
    fun `the ink and its rules carry the same weight in every theme`() {
        for (role in listOf<Pair<String, (ColorScheme) -> Color>>(
            "onSurface" to { it.onSurface },
            "onSurfaceVariant" to { it.onSurfaceVariant },
            "outline" to { it.outline },
            "outlineVariant" to { it.outlineVariant },
        )) {
            val (name, pick) = role
            val across = themes.map { apca(pick(scheme(it)), paper(it)) }
            assertTrue(
                "$name reads at ${across.map { "%.0f".format(it) }} across $themes",
                across.max() - across.min() <= 3.0,
            )
        }
    }

    @Test
    fun `gold has one presence, and a second weight for when it carries a word`() {
        val illumination = themes.map { apca(accents(it).gold, paper(it)) }
        assertTrue(
            "gold reads at ${illumination.map { "%.0f".format(it) }} across $themes — " +
                "it was 50 on cream and 64 at night before the ladder",
            illumination.max() - illumination.min() <= 3.0,
        )
        for (mode in themes) {
            val asInk = apca(accents(mode).goldInk, paper(mode))
            assertTrue(
                "$mode goldInk is Lc %.1f — gold that labels something has to stay readable"
                    .format(asInk),
                asInk >= textFloor,
            )
            assertTrue(
                "$mode goldInk should outweigh plain gold, or there is no reason for it",
                asInk > apca(accents(mode).gold, paper(mode)),
            )
        }
    }

    @Test
    fun `identity accents always read, whichever sheet they are on`() {
        // These carry a hue the reader learns, so they are not flattened to one
        // weight — a deep ruby cannot be as strong on near-black as on cream
        // without turning pink. They are held to a floor instead.
        val floors = mapOf<String, Pair<Double, (QuranAccents) -> Color>>(
            "repeatInk" to (52.0 to { it.repeatInk }),
            "bookmarkRibbon" to (45.0 to { it.bookmarkRibbon }),
            "annotationInk" to (58.0 to { it.annotationInk }),
        )
        for (mode in themes) {
            for ((name, spec) in floors) {
                val (floor, pick) = spec
                val got = apca(pick(accents(mode)), paper(mode))
                assertTrue(
                    "$mode $name is Lc %.1f, under its floor of %.1f".format(got, floor),
                    got >= floor,
                )
            }
            val green = apca(scheme(mode).primary, paper(mode))
            assertTrue(
                "$mode primary is Lc %.1f — it names chapters, so it has to read".format(green),
                green >= 60.0,
            )
        }
    }

    @Test
    fun `the green reads at one weight in every theme`() {
        // `primary` is the green's identity and may be as strong as its sheet
        // allows; these are the green set to be read, so they are rungs.
        for ((name, target, pick) in listOf(
            Triple("greenInk", 72.0, { a: QuranAccents -> a.greenInk }),
            Triple("greenQuiet", 52.0, { a: QuranAccents -> a.greenQuiet }),
            Triple("greenWash", 8.0, { a: QuranAccents -> a.greenWash }),
        )) {
            val across = themes.map { apca(pick(accents(it)), paper(it)) }
            for ((mode, got) in themes.zip(across)) {
                assertTrue(
                    "$mode $name is Lc %.1f, not the %.1f it is solved to".format(got, target),
                    abs(got - target) <= 1.5,
                )
            }
            assertTrue(
                "$name reads at ${across.map { "%.0f".format(it) }} across $themes",
                across.max() - across.min() <= 3.0,
            )
        }
        for (mode in themes) {
            val a = accents(mode)
            assertTrue(
                "$mode greenInk must outweigh greenQuiet",
                apca(a.greenInk, paper(mode)) > apca(a.greenQuiet, paper(mode)),
            )
            assertTrue(
                "$mode the green rungs must stay green",
                listOf(a.greenInk, a.greenQuiet, a.greenWash).all {
                    hueDistance(oklch(it).hue, 172.0) <= 25.0
                },
            )
        }
    }

    @Test
    fun `identity accents keep the hue and saturation that identify them`() {
        val identity = mapOf(
            "repeatInk" to (47.5 to 0.13),
            "bookmarkRibbon" to (18.5 to 0.16),
            "annotationInk" to (8.5 to 0.08),
        )
        for (mode in themes) {
            val a = accents(mode)
            for ((name, spec) in identity) {
                val (hue, minChroma) = spec
                val c = oklch(
                    when (name) {
                        "repeatInk" -> a.repeatInk
                        "bookmarkRibbon" -> a.bookmarkRibbon
                        else -> a.annotationInk
                    },
                )
                assertTrue(
                    "$mode $name drifted to hue %.0f, away from %.0f".format(c.hue, hue),
                    hueDistance(c.hue, hue) <= 8.0,
                )
                assertTrue(
                    "$mode $name washed out to chroma %.3f — it has to stay its own colour"
                        .format(c.chroma),
                    c.chroma >= minChroma,
                )
            }
        }
    }

    @Test
    fun `the gilding has the same depth in every theme`() {
        // goldBright and goldDeep are the two ends of the gilding gradient, not
        // colours anything is set in, so what has to match across themes is the
        // relief between them — their own contrast against the paper is not a
        // meaningful number and will look wildly different by design.
        val reliefs = themes.map { mode ->
            val a = accents(mode)
            val bright = oklch(a.goldBright).lightness
            val deep = oklch(a.goldDeep).lightness
            val gold = oklch(a.gold).lightness
            assertTrue(
                "$mode gold must sit between its own gilding stops",
                gold in deep..bright,
            )
            mode to Pair(bright - deep, (gold - deep) / (bright - deep))
        }
        for ((mode, r) in reliefs) {
            assertTrue(
                "$mode gilding is only %.3f deep — too flat to read as metal".format(r.first),
                r.first >= 0.20,
            )
        }
        val ranges = reliefs.map { it.second.first }
        val seats = reliefs.map { it.second.second }
        assertTrue(
            "the gilding is ${ranges.map { "%.3f".format(it) }} deep across $themes",
            ranges.max() - ranges.min() <= 0.02,
        )
        assertTrue(
            "gold sits at ${seats.map { "%.0f%%".format(it * 100) }} of the gradient " +
                "across $themes — it should be seated the same way in each",
            seats.max() - seats.min() <= 0.06,
        )
    }

    @Test
    fun `fresh ink glints brighter than the ink it dries into`() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.ROYAL_GREEN)) {
            val glint = requireNotNull(accents(mode).glintInk) { "$mode has no glint" }
            assertTrue(
                "$mode glint (Lc %.1f) must out-shine scripture (Lc %.1f)".format(
                    apca(glint, paper(mode)), apca(ink(mode).scripture, paper(mode)),
                ),
                apca(glint, paper(mode)) > apca(ink(mode).scripture, paper(mode)),
            )
        }
    }

    @Test
    fun `each theme is one hue family, sheet and rules together`() {
        // A sheet that reads cool under warm ink is two materials, not one.
        for ((mode, family) in mapOf(
            ThemeMode.LIGHT to 82.0,
            ThemeMode.DARK to 85.0,
            ThemeMode.ROYAL_GREEN to 174.5,
        )) {
            val s = scheme(mode)
            val surfaces = listOf(
                "background" to s.background,
                "surface" to s.surface,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
                "outline" to s.outline,
                "outlineVariant" to s.outlineVariant,
            )
            for ((name, c) in surfaces) {
                val h = oklch(c)
                if (h.chroma < 0.004) continue // too neutral for hue to mean anything
                assertTrue(
                    "$mode $name sits at hue %.0f, outside the theme's family at %.0f"
                        .format(h.hue, family),
                    hueDistance(h.hue, family) <= 20.0,
                )
            }
        }
    }

    @Test
    fun `the elevation ramp climbs in one direction`() {
        for (mode in themes) {
            val s = scheme(mode)
            val ramp = listOf(
                "surfaceContainerLowest" to s.surfaceContainerLowest,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
            ).map { it.first to oklch(it.second).lightness }
            val lightSheet = oklch(s.background).lightness > 0.5
            for ((a, b) in ramp.zipWithNext()) {
                val climbs = if (lightSheet) b.second <= a.second else b.second >= a.second
                assertTrue("$mode ${a.first} -> ${b.first} reverses the elevation ramp", climbs)
            }
        }
    }

    @Test
    fun `the system theme follows the device into the dark`() {
        assertEquals(quranThemeParts(ThemeMode.SYSTEM, systemDark = false), parts(ThemeMode.LIGHT))
        assertEquals(quranThemeParts(ThemeMode.SYSTEM, systemDark = true), parts(ThemeMode.DARK))
    }
}
