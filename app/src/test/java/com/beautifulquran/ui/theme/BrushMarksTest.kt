package com.beautifulquran.ui.theme

import com.beautifulquran.data.BrushCircleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The ink-brush selection marks (`ui/theme/BrushMarks.kt`).
 *
 * The web port has covered `brushMark.ts` / `brushCheck.ts` since they shipped;
 * Android had no equivalent while the geometry lived inside `SettingsScreen.kt`
 * and returned an Android-backed `Path`. These tests exercise
 * [inkBrushCircleOutline] / [inkBrushCheckOutline] — the pure halves — and, most
 * importantly, **lock the shipped knobs against the values the web suite pins**,
 * which is the only thing keeping the two platforms drawing the same mark.
 */
class BrushMarksTest {

    private fun outline(
        progress: Float,
        params: BrushCircleParams = brushCircleParams(BrushCircleStyle.BASELINE),
        rx: Float = 60f,
        ry: Float = 18f,
    ) = inkBrushCircleOutline(
        cx = 70f,
        cy = 20f,
        rx = rx,
        ry = ry,
        peakHalf = params.peakHalfDp,
        bowPx = params.bow,
        progress = progress,
        params = params,
    )

    // ---- cross-platform knob lock -------------------------------------------------

    /**
     * Bit-for-bit against `brushMark.test.ts`'s "locks every shipped knob" case.
     * If this fails, Android and web have drifted and the same word gets a
     * different loop on each platform — decide which is right, then change both.
     */
    @Test
    fun `shipped circle knobs match the web baseline bit for bit`() {
        for (params in listOf(
            brushCircleParams(BrushCircleStyle.BASELINE),
            // The data-class defaults must agree with BASELINE too: every other
            // style is expressed as a partial override of them.
            BrushCircleParams(label = "defaults"),
        )) {
            assertEquals(15.5f, params.padXDp, 0f)
            assertEquals(6f, params.padYDp, 0f)
            assertEquals(2.2f, params.peakHalfDp, 0f)
            assertEquals(254f, params.startDeg, 0f)
            assertEquals(43f, params.startOvershoot, 0f)
            assertEquals(22f, params.endOvershoot, 0f)
            assertEquals(4.25f, params.bow, 0f)
            assertEquals(0.19f, params.bowSpan, 0f)
            assertEquals(0.025f, params.breath, 0f)
            assertEquals(0.58f, params.nibBias, 0f)
            assertEquals(0.195f, params.attack, 0f)
            assertEquals(0.6f, params.releaseStart, 0f)
            assertEquals(0.34f, params.bodyAmp, 0f)
            assertEquals(5f, params.bodyFreq, 0f)
            assertEquals(620, params.paintMs)
            assertEquals(0.9f, params.alpha, 0f)
        }
    }

    /** Bit-for-bit against `brushCheck.test.ts`'s SHIPPED_CHECK_PARAMS. */
    @Test
    fun `shipped check knobs match the web baseline bit for bit`() {
        val p = shippedCheckParams()
        assertEquals(0.1f, p.p0x, 0f)
        assertEquals(0.49f, p.p0y, 0f)
        assertEquals(0.39f, p.p1x, 0f)
        assertEquals(0.8f, p.p1y, 0f)
        assertEquals(0.73f, p.p2x, 0f)
        assertEquals(0.11f, p.p2y, 0f)
        assertEquals(24f, p.sizeDp, 0f)
        assertEquals(1.68f, p.peakHalfDp, 0f)
        assertEquals(0.56f, p.nibBias, 0f)
        assertEquals(0.184f, p.attack, 0f)
        assertEquals(0.74f, p.releaseStart, 0f)
        assertEquals(0.1f, p.bodyAmp, 0f)
        assertEquals(2.2f, p.bodyFreq, 0f)
        assertEquals(833, p.paintMs)
        assertEquals(0.75f, p.alpha, 0f)
    }

    /** The lab reseeds off these; web pins the same numbers. */
    @Test
    fun `shipped revisions match the web`() {
        assertEquals(9, SHIPPED_BRUSH_REVISION)
        assertEquals(2, SHIPPED_CHECK_REVISION)
    }

    // ---- style catalogue ----------------------------------------------------------

    @Test
    fun `ships a baseline plus ten developer variants, each labelled`() {
        val styles = BrushCircleStyle.entries
        assertEquals(11, styles.size)
        assertEquals(BrushCircleStyle.BASELINE, styles.first())
        for (style in styles) {
            assertTrue("$style has a label", brushCircleParams(style).label.isNotEmpty())
        }
    }

    @Test
    fun `every style paints a distinct stroke`() {
        val strokes = BrushCircleStyle.entries.map { style ->
            outline(1f, brushCircleParams(style)).top.map { "${it.x},${it.y}" }
        }
        assertEquals(
            "all 11 styles must differ — distinct pressure / pad / nib knobs",
            BrushCircleStyle.entries.size,
            strokes.toSet().size,
        )
    }

    // ---- circle geometry ---------------------------------------------------------

    @Test
    fun `the ribbon has matching edges and is never empty`() {
        val o = outline(1f)
        assertEquals(o.top.size, o.bottom.size)
        assertTrue("a full loop needs many samples", o.top.size > 60)
    }

    @Test
    fun `the stroke grows as the brush paints around`() {
        val early = outline(0.2f).top.size
        val half = outline(0.5f).top.size
        val full = outline(1f).top.size
        assertTrue("$early < $half < $full", early < half && half < full)
    }

    /** Zero progress must still yield a drawable ribbon, not an empty path. */
    @Test
    fun `a barely started stroke still has a segment to draw`() {
        for (p in listOf(0f, -1f, 0.001f)) {
            val o = outline(p)
            assertTrue("progress $p", o.top.size >= 2)
            assertEquals(o.top.size, o.bottom.size)
        }
    }

    @Test
    fun `geometry is deterministic`() {
        val a = outline(0.7f)
        val b = outline(0.7f)
        assertEquals(a.top, b.top)
        assertEquals(a.bottom, b.bottom)
    }

    /**
     * Where the ribbon's centreline sits at [i] relative to the plain ellipse:
     * 1 is on the track. Averaging the two edges cancels the nib's half-width,
     * which is what the bow has to be measured against.
     */
    private fun normalisedRadius(o: BrushOutline, i: Int, rx: Float, ry: Float): Float {
        val x = (o.top[i].x + o.bottom[i].x) / 2f
        val y = (o.top[i].y + o.bottom[i].y) / 2f
        return hypot((x - 70f) / rx, (y - 20f) / ry)
    }

    /**
     * The stroke spans **more than a full turn** — it starts before its join and
     * runs past it — so it crosses its own entry angle on the way round. That
     * overlap is what makes the mark read as a hand's loop rather than a ring.
     */
    @Test
    fun `a completed loop sweeps past its own entry angle`() {
        val params = brushCircleParams(BrushCircleStyle.BASELINE)
        val expectedSweep = 360f + params.startOvershoot + params.endOvershoot
        assertTrue("overshoot must exceed a full turn", expectedSweep > 360f)

        // Geometrically: some later point sits back at the entry point's angle.
        val o = outline(1f, params)
        fun angle(p: Offset) =
            Math.toDegrees(atan2((p.y - 20.0) / 18.0, (p.x - 70.0) / 60.0))
                .let { (it + 360.0) % 360.0 }
        val entryAngle = angle(o.top.first())
        val revisited = o.top.drop(o.top.size / 2).any {
            abs(angle(it) - entryAngle) < 6.0
        }
        assertTrue("the stroke should pass back over its entry angle", revisited)
    }

    /**
     * The tips **bow**: the entry eases outward off the track and the exit eases
     * inward, so the two ends cross instead of riding the same curve. Measured
     * against the plain ellipse — 1.0 is on the track.
     */
    @Test
    fun `the tips bow out on entry and in on exit`() {
        val rx = 60f
        val ry = 18f
        val o = outline(1f, rx = rx, ry = ry)
        val entry = normalisedRadius(o, 0, rx, ry)
        val exit = normalisedRadius(o, o.top.lastIndex, rx, ry)
        val body = (o.top.size * 4 / 10 until o.top.size * 6 / 10)
            .map { normalisedRadius(o, it, rx, ry) }

        assertTrue("entry tip $entry should ride outside the track", entry > 1.05f)
        assertTrue("exit tip $exit should cut inside the track", exit < 0.95f)
        for (r in body) {
            assertEquals("body should hug the track", 1f, r, 0.05f)
        }
    }

    /** A nearly-closed ring bows far less than the long-overshoot variant. */
    @Test
    fun `bow span differs between styles`() {
        val rx = 60f
        val ry = 18f
        fun entryBow(style: BrushCircleStyle): Float =
            normalisedRadius(outline(1f, brushCircleParams(style), rx, ry), 0, rx, ry)
        assertTrue(
            entryBow(BrushCircleStyle.CLOSED_RING) < entryBow(BrushCircleStyle.LONG_OVERSHOOT),
        )
    }

    /** Pressure shapes the ribbon: thin at the tips, full through the body. */
    @Test
    fun `pressure attacks from the tip and releases at the end`() {
        val params = brushCircleParams(BrushCircleStyle.BASELINE)
        val atTip = brushPressure(0f, params)
        val body = brushPressure(0.4f, params)
        val atExit = brushPressure(1f, params)
        assertTrue("tip $atTip should be lighter than body $body", atTip < body)
        assertTrue("exit $atExit should be lighter than body $body", atExit < body)
        // Never fully lifts — an ink stroke keeps a trace of width throughout.
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue("t=$t", brushPressure(t, params) >= 0.1f)
        }
    }

    @Test
    fun `a heavier nib widens the ribbon`() {
        val hairline = brushCircleParams(BrushCircleStyle.HAIRLINE)
        val heavy = brushCircleParams(BrushCircleStyle.HEAVY)
        fun width(params: BrushCircleParams): Float {
            val o = outline(1f, params)
            val i = o.top.size / 2
            return hypot(o.top[i].x - o.bottom[i].x, o.top[i].y - o.bottom[i].y)
        }
        assertTrue(width(hairline) < width(heavy))
    }

    // ---- check geometry ----------------------------------------------------------

    @Test
    fun `the check ribbon has matching edges and grows as it paints`() {
        val params = shippedCheckParams()
        val early = inkBrushCheckOutline(24f, 0.15f, params)
        val full = inkBrushCheckOutline(24f, 1f, params)
        assertEquals(early.top.size, early.bottom.size)
        assertEquals(full.top.size, full.bottom.size)
        assertTrue("${early.top.size} < ${full.top.size}", early.top.size < full.top.size)
    }

    /**
     * The degenerate case the drawing code has an explicit fallback for: at a
     * progress too small to accumulate two samples, it synthesises a short stub
     * so the tick is never an unclosable one-point path.
     */
    @Test
    fun `a barely started check falls back to a drawable stub`() {
        val o = inkBrushCheckOutline(24f, 0f, shippedCheckParams())
        assertTrue(o.top.size >= 2)
        assertEquals(o.top.size, o.bottom.size)
    }

    @Test
    fun `the check scales with its box`() {
        val params = shippedCheckParams()
        val small = inkBrushCheckOutline(24f, 1f, params)
        val large = inkBrushCheckOutline(48f, 1f, params)
        val smallSpan = small.top.maxOf { it.x } - small.top.minOf { it.x }
        val largeSpan = large.top.maxOf { it.x } - large.top.minOf { it.x }
        assertEquals(2f, largeSpan / smallSpan, 0.05f)
    }

    @Test
    fun `check geometry is deterministic`() {
        val a = inkBrushCheckOutline(24f, 0.6f, shippedCheckParams())
        val b = inkBrushCheckOutline(24f, 0.6f, shippedCheckParams())
        assertEquals(a.top, b.top)
        assertEquals(a.bottom, b.bottom)
    }
}
