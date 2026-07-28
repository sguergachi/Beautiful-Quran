package com.beautifulquran.domain

import com.beautifulquran.data.model.SubwordKeyframe
import com.beautifulquran.domain.TajweedPacing.Hold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec for [TajweedPacing]'s gate / cruise / hold model.
 *
 * The gate is the headline behaviour: a word with nothing dramatic in it
 * returns null and takes the plain sweep untouched, so these tests are as much
 * about what is *not* paced as what is.
 *
 * The golden words are the exact Hafs Uthmani orthography shipped in quran.db.
 * **Keep the literals byte-identical** — NFC normalization fuses `ا + ٓ` into a
 * precomposed U+0622 and silently rewrites the weights. (The parser unfuses
 * U+0622 defensively, but the DB itself is always decomposed.)
 */
class TajweedPacingTest {

    // 1:7 — hamzat wasl + assimilated lam are silent, then a madd lazim.
    private val dallin = "ٱلضَّآلِّينَ"

    // 1:7 — dagger alef on the ra is a 2-count madd: nothing dramatic.
    private val sirat = "صِرَٰطَ"

    // 1:7 — marked sukūn noon is iẓhār, so no hold anywhere.
    private val anamta = "أَنۡعَمۡتَ"

    // 2:11 — madd munfasil on the waw, then the silent plural alif.
    private val qalu = "قَالُوٓاْ"

    // Mushaddad noon: the 2-count nasal hum, off unless ghunnah is enabled.
    private val nas = "ٱلنَّاسِ"

    // Two letters: too short to pace at all.
    private val qul = "قُلۡ"

    private fun curveOf(
        word: String,
        spoken: Float = 1f,
        hold: Hold = Hold(),
        prevArabic: String? = null,
    ) = requireNotNull(TajweedPacing.curve(word, spoken, hold, prevArabic))

    @Test
    fun `words with nothing dramatic take the plain sweep`() {
        assertNull(TajweedPacing.curve(sirat))
        assertNull(TajweedPacing.curve(anamta))
        assertNull(TajweedPacing.curve(qul))
        assertNull(TajweedPacing.curve(""))
        assertNull(TajweedPacing.curve("hello"))
    }

    @Test
    fun `a madd lazim sustains the wash`() {
        val curve = curveOf(dallin)
        assertEquals(5, curve.letterCount)
        // The madd owns 20% of the word (cruiseCap 1.25) and barely moves
        // through it, while an equal slice of cruising covers a real distance.
        val held = curve.at(0.60f) - curve.at(0.42f)
        val cruising = curve.at(0.30f) - curve.at(0.12f)
        assertTrue("the wash should all but stop on the madd, moved $held", held < 0.03f)
        assertTrue("cruising should keep moving, moved $cruising", cruising > 0.15f)
        assertTrue("the hold must read as a hold", cruising > held * 5f)
    }

    @Test
    fun `turning off the madd rule takes the gate with it`() {
        assertNull(TajweedPacing.curve(dallin, 1f, Hold(madd = false)))
    }

    @Test
    fun `ghunnah holds only when it is asked for`() {
        assertNull(TajweedPacing.curve(nas))
        assertNotNull(TajweedPacing.curve(nas, 1f, Hold(ghunnah = true)))
    }

    @Test
    fun `no segment outruns the cruise cap`() {
        for (cap in listOf(1.1f, 1.25f, 1.6f)) {
            val curve = curveOf(dallin, hold = Hold(cruiseCap = cap))
            val dt = 0.001f
            var fastest = 0f
            var t = 0f
            while (t < 1f - dt) {
                fastest = maxOf(fastest, (curve.at(t + dt) - curve.at(t)) / dt)
                t += dt
            }
            assertTrue("cap $cap exceeded by $fastest", fastest <= cap + 0.01f)
        }
    }

    @Test
    fun `a cruise cap of one refuses to hurry ordinary letters`() {
        // No slack exists inside a word, so buying a mid-word hold is exactly
        // the same act as speeding its neighbours up. Forbid one, forbid both.
        assertNull(TajweedPacing.curve(dallin, 1f, Hold(cruiseCap = 1f)))
        // The waqf is the one hold that is not borrowed from the neighbours.
        assertNotNull(
            TajweedPacing.curve(sirat, 1f, Hold(cruiseCap = 1f, isAyahFinal = true)),
        )
    }

    @Test
    fun `the verse-closing word sustains its final letter`() {
        // Full share (length scale off): hold lands in the last letter's slot.
        val short = curveOf(
            sirat,
            hold = Hold(isAyahFinal = true, waqfLengthScale = 0f),
        )
        assertEquals(1f, short.at(1f), 0f)
        assertTrue("hold sits in the final slot", short.at(0.5f) > 2f / 3f)
        // Long closer, waqf-only, full share — hard park late in the sweep.
        val long = curveOf(
            dallin,
            hold = Hold(madd = false, isAyahFinal = true, waqfLengthScale = 0f),
        )
        val late = long.at(0.9f) - long.at(0.5f)
        assertTrue("long waqf should park the wash, moved $late", late < 0.08f)
    }

    @Test
    fun `the waqf share is what pays for the hold`() {
        // A bigger share buys a longer stillness — the run-up is quicker and
        // the wash then sits on the closing letter for more of the word.
        fun stillness(share: Float): Int {
            val curve = curveOf(sirat, hold = Hold(isAyahFinal = true, waqfShare = share))
            val dt = 0.005f
            return (0 until 190).count { i ->
                val t = i * dt
                (curve.at(t + dt) - curve.at(t)) / dt < 0.2f
            }
        }
        assertTrue(stillness(0.7f) > stillness(0.2f))
    }

    @Test
    fun `wasl idgham holds the next word's opening letter`() {
        // 2:8 مَن يَقُولُ — nūn sākinah + yāʾ (يرملون): sustain the yāʾ.
        val yaqulu = "يَقُولُ"
        val man = "مَن"
        assertNull(
            "without a predecessor the opening yāʾ is not dramatic",
            TajweedPacing.curve(yaqulu),
        )
        val curve = curveOf(yaqulu, hold = Hold(madd = false), prevArabic = man)
        // Mid-hold plateau barely moves; an equal late cruise covers real ground.
        val held = curve.at(0.30f) - curve.at(0.12f)
        val cruising = curve.at(0.85f) - curve.at(0.55f)
        assertTrue("wasl should park on the opening letter, moved $held", held < 0.05f)
        assertTrue("the rest of the word should still cruise, moved $cruising", cruising > 0.15f)
        assertTrue("hold sits in the first slot", curve.at(0.4f) < 0.45f)
        assertTrue("hold must read as a hold", cruising > held * 5f)
    }

    @Test
    fun `wasl connect needs nūn or tanween into a noon-rule letter`() {
        val yaqulu = "يَقُولُ"
        // Previous word ends in a plain letter — no connect.
        assertNull(TajweedPacing.curve(yaqulu, 1f, Hold(madd = false), prevArabic = "قَالَ"))
        // Iẓhār (throat) letter — no connect hold.
        assertNull(TajweedPacing.curve("عَلِيمٌ", 1f, Hold(madd = false), prevArabic = "مِن"))
        // Toggle off.
        assertNull(
            TajweedPacing.curve(yaqulu, 1f, Hold(madd = false, connect = false), prevArabic = "مَن"),
        )
    }

    @Test
    fun `wasl idgham into waw holds the waw`() {
        // 2:19 ظُلُمَٰتٞ وَرَعۡدٞ — tanwīn + wāw.
        val waraad = "وَرَعۡدٞ"
        val curve = curveOf(waraad, hold = Hold(madd = false), prevArabic = "ظُلُمَٰتٞ")
        assertTrue("opening wāw holds early", curve.at(0.35f) < 0.4f)
        assertEquals(1f, curve.at(1f), 0f)
    }

    @Test
    fun `wasl connection blooms the next waw during the prior word tail`() {
        // 4:163 نُوحٖ وَٱلنَّبِيِّـۧنَ — the reported tanwīn + wāw handoff.
        val connection = requireNotNull(
            TajweedPacing.connection("نُوحٖ", "وَٱلنَّبِيِّـۧنَ"),
        )
        // Default (long-word) junction: rise over the last 18%, smoothstepped.
        assertEquals(0f, connection.at(0.82f), 0f)
        assertTrue(connection.at(0.91f) in 0.49f..0.51f)
        assertEquals(1f, connection.at(1f), 0f)
        assertEquals(1f / 7f, connection.prefixFraction, 0f)
    }

    @Test
    fun `wasl connection blooms the next meem during the prior word tail`() {
        // 4:165 رُّسُلٗا مُّبَشِّرِينَ — the reported tanwīn + mīm handoff.
        val connection = requireNotNull(
            TajweedPacing.connection("رُّسُلٗا", "مُّبَشِّرِينَ"),
        )
        assertEquals(0f, connection.at(0.82f), 0f)
        assertTrue(connection.at(0.91f) in 0.49f..0.51f)
        assertEquals(1f, connection.at(1f), 0f)
        assertEquals(1f / 6f, connection.prefixFraction, 0f)
    }

    @Test
    fun `short wasl donor starts the next-letter bloom earlier`() {
        // مِن/مَن-scale (~500 ms): claim 75% of the donor so the next opening
        // gets ~375 ms of soft carry-in (speed ceiling), not a half-word pop.
        val start = TajweedPacing.waslPrefixStart(500)
        assertEquals(0.25f, start, 1e-3f)
        assertTrue(
            "short donor bloom window should be at least ~350 ms",
            (1f - start) * 500f >= 350f,
        )
        val connection = requireNotNull(
            TajweedPacing.connection("مِن", "رَّبِّكُم"),
        )
        assertEquals(0f, connection.at(start, start), 0f)
        // smoothstep(0.5) = 0.5 at the window midpoint.
        val mid = start + 0.5f * (1f - start)
        assertEquals(0.5f, connection.at(mid, start), 1e-3f)
        assertEquals(1f, connection.at(1f, start), 0f)
        // Soft onset: a little past the start is still well below linear.
        val early = start + 0.2f * (1f - start)
        assertTrue(
            "smoothstep should lag a linear ramp at the toe",
            connection.at(early, start) < 0.2f * 0.85f,
        )
    }

    @Test
    fun `wasl prefix speed ceiling targets about 480ms when the donor allows`() {
        // 800 ms donor: 480/800 = 0.60 window → start 0.40.
        val start = TajweedPacing.waslPrefixStart(800)
        assertEquals(0.40f, start, 1e-3f)
        assertEquals(
            TajweedPacing.DEFAULT_WASL_PREFIX_MS,
            (1f - start) * 800f,
            1f,
        )
        assertEquals(
            TajweedPacing.DEFAULT_WASL_HANDOFF,
            TajweedPacing.waslPrefixCompletion(800),
            0f,
        )
    }

    @Test
    fun `very short wasl donor hands off an unfinished slower bloom`() {
        // Alafasy 2:207 مَن is 220 ms; the wash floor makes it 254 ms.
        // Its capped 75% tail cannot honestly fit a 480 ms fade, so preserve
        // the initial quarter-word pause and carry partial progress forward.
        val sweepMs = 254
        val start = TajweedPacing.waslPrefixStart(sweepMs)
        val completion = TajweedPacing.waslPrefixCompletion(sweepMs)
        val connection = requireNotNull(
            TajweedPacing.connection("مَن", "يَشۡرِي"),
        )

        assertEquals(0.25f, start, 1e-3f)
        assertEquals(0.75f * sweepMs / TajweedPacing.DEFAULT_WASL_PREFIX_MS, completion, 1e-3f)
        assertEquals(0f, connection.at(start, start, completion), 0f)
        assertTrue("handoff must remain a partial fade", connection.at(1f, start, completion) < 0.5f)
    }

    @Test
    fun `long wasl donor still leaves visible fade after handoff`() {
        // Alafasy 2:231 بِمَعۡرُوفٖۚ → وَلَا: the 2.22 s donor has ample
        // room for 480 ms, but pre-forming the whole wāw snaps across a line.
        val sweepMs = 2_220
        val start = TajweedPacing.waslPrefixStart(sweepMs)
        val completion = TajweedPacing.waslPrefixCompletion(sweepMs)
        val connection = requireNotNull(
            TajweedPacing.connection("بِمَعۡرُوفٖۚ", "وَلَا"),
        )

        assertEquals(1f - 480f / sweepMs, start, 1e-3f)
        assertEquals(TajweedPacing.DEFAULT_WASL_HANDOFF, completion, 0f)
        assertTrue(connection.at(1f, start, completion) < 0.5f)
    }

    @Test
    fun `wasl prefix start respects a lab minPrefixMs override`() {
        // Ink Lab "Wasl prefix ms" = 600 on an 800 ms donor → 0.75 window.
        assertEquals(0.25f, TajweedPacing.waslPrefixStart(800, minPrefixMs = 600f), 1e-3f)
        // Very high ceiling still clamped by MAX_WASL_PREFIX_WINDOW (0.75).
        assertEquals(0.25f, TajweedPacing.waslPrefixStart(500, minPrefixMs = 900f), 1e-3f)
    }

    @Test
    fun `long wasl donor keeps a late junction`() {
        // 5 s: 480/5000 < min window 0.18 → still 82% junction.
        assertEquals(0.82f, TajweedPacing.waslPrefixStart(5000), 1e-3f)
        // 2 s: 480/2000 = 0.24 → slightly earlier than the bare 18% tail.
        assertEquals(0.76f, TajweedPacing.waslPrefixStart(2000), 1e-3f)
    }

    @Test
    fun `wasl connection excludes plain endings and izhar`() {
        assertNull(TajweedPacing.connection("قَالَ", "وَرَعۡدٞ"))
        assertNull(TajweedPacing.connection("مِن", "عَلِيمٌ"))
        // Fatḥatan before its carrier alif is still iẓhār before ʿayn.
        assertNull(TajweedPacing.connection("رُّسُلٗا", "عَلِيمٌ"))
    }

    @Test
    fun `wasl ikhfa holds the next word's opening letter`() {
        // 2:26 مِن قَبۡلُ — nūn + qāf (ikhfāʾ): sustain the qāf.
        val qablu = "قَبۡلُ"
        assertNull(TajweedPacing.curve(qablu, 1f, Hold(madd = false)))
        val curve = curveOf(qablu, hold = Hold(madd = false), prevArabic = "مِن")
        val held = curve.at(0.30f) - curve.at(0.12f)
        val cruising = curve.at(0.90f) - curve.at(0.55f)
        assertTrue("ikhfāʾ should park on the opening letter, moved $held", held < 0.05f)
        assertTrue("the rest should cruise, moved $cruising", cruising > 0.15f)
    }

    @Test
    fun `wasl iqlab holds the next word's opening ba`() {
        // nūn + bāʾ (iqlāb): hold the bāʾ.
        val bi = "بِسُورَةٖ"
        val curve = curveOf(bi, hold = Hold(madd = false), prevArabic = "مِن")
        assertTrue("iqlāb parks early on bāʾ", curve.at(0.35f) < 0.45f)
        assertEquals(1f, curve.at(1f), 0f)
    }

    @Test
    fun `wasl connection leaves the donor on its ordinary sweep`() {
        val thulumat = "ظُلُمَٰتٞ"
        assertNotNull(TajweedPacing.connection(thulumat, "وَرَعۡدٞ"))
        assertNull(TajweedPacing.curve(thulumat, 1f, Hold(madd = false)))

        // 4:165 writes fatḥatan on lām before a silent carrier alif.
        val rusulan = "رُّسُلٗا"
        assertNotNull(TajweedPacing.connection(rusulan, "مُّبَشِّرِينَ"))
        assertNull(TajweedPacing.curve(rusulan, 1f, Hold(madd = false)))
    }

    @Test
    fun `waqf length scale protects short closers`() {
        // Same high slider: a short closer must keep more run-up (less stillness)
        // than a long closer that may spend the full share.
        fun stillness(word: String, share: Float, lengthScale: Float = 0.7f): Int {
            val curve = curveOf(
                word,
                hold = Hold(isAyahFinal = true, waqfShare = share, waqfLengthScale = lengthScale),
            )
            val dt = 0.005f
            return (0 until 190).count { i ->
                val t = i * dt
                (curve.at(t + dt) - curve.at(t)) / dt < 0.2f
            }
        }
        // صِرَٰطَ is short; ٱلضَّآلِّينَ is long — at share 0.8 the long word
        // is allowed a larger effective hold when length scale is on.
        assertTrue(
            stillness(dallin, 0.8f) > stillness(sirat, 0.8f),
        )
        // Turning the dial off equalizes effective share by length (long may
        // still look longer only if it also holds a mid-word madd — use waqf-only).
        val shortOff = stillness(sirat, 0.8f, lengthScale = 0f)
        val shortOn = stillness(sirat, 0.8f, lengthScale = 1f)
        assertTrue(
            "length scale 1 should cut short-closer stillness ($shortOn) vs off ($shortOff)",
            shortOn < shortOff,
        )
    }

    @Test
    fun `medium closer keeps more run-up when length scale is high`() {
        // عَظِيمًا — typical medium ayah-final; high waqfShare with full length
        // scale must not spend nearly the whole budget on the last letter.
        val azima = "عَظِيمًا"
        fun runUp(scale: Float): Float {
            val curve = curveOf(
                azima,
                hold = Hold(
                    madd = false,
                    isAyahFinal = true,
                    waqfShare = 0.8f,
                    waqfLengthScale = scale,
                ),
            )
            // Position reached halfway through the clock — more run-up ⇒ lower x
            // when more time is spent later on the hold (higher effective share
            // reaches the final slot earlier). Compare mid-hold stillness instead:
            return curve.at(0.5f)
        }
        // Stronger length scale → smaller effective share → later hold → lower
        // position at the midpoint of the sweep.
        assertTrue(
            "high length scale should leave more of the word for the run-up",
            runUp(1f) < runUp(0f),
        )
    }

    @Test
    fun `a trailing silent letter is crossed on the held letter's glide`() {
        // قَالُوٓاْ: the maddah waw is a 4-count hold and the silent plural alif
        // has no time of its own, so the waw's slot spans both.
        val curve = curveOf(qalu)
        assertEquals(4, curve.letterCount)
        assertEquals(1f, curve.at(1f), 0f)
        // The park lands inside the waw+alif slice (0.6..1.0), not before it.
        assertTrue("anchored in the final slice", curve.at(0.5f) > 0.6f)
    }

    @Test
    fun `curve rests at full ink after the spoken span`() {
        val curve = curveOf(dallin, spoken = 0.6f)
        assertEquals(1f, curve.at(0.6f), 1e-4f)
        assertEquals(1f, curve.at(0.8f), 0f)
    }

    @Test
    fun `degenerate spoken fractions are floored`() {
        // A near-zero voiced share must not compress the word into a blink.
        val curve = curveOf(dallin, spoken = 0.01f)
        assertTrue(curve.at(0.1f) < 1f)
        assertEquals(1f, curve.at(0.25f), 1e-4f)
    }

    @Test
    fun `curves are monotone and bounded with exact endpoints`() {
        val holds = listOf(
            Hold(),
            Hold(ghunnah = true),
            Hold(cruiseCap = 2f),
            Hold(creep = 0f),
            Hold(creep = 0.3f),
            Hold(isAyahFinal = true),
            Hold(isAyahFinal = true, waqfShare = 0.8f),
            Hold(isAyahFinal = true, cruiseCap = 1f),
        )
        for (word in listOf(dallin, sirat, anamta, qalu, nas)) {
            for (spoken in listOf(0.4f, 0.75f, 1f)) {
                for (hold in holds) {
                    val curve = TajweedPacing.curve(word, spoken, hold) ?: continue
                    var last = -1f
                    for (i in 0..200) {
                        val v = curve.at(i / 200f)
                        assertTrue("monotone at $i for $word", v >= last)
                        assertTrue("bounded at $i for $word", v in 0f..1f)
                        last = v
                    }
                    assertEquals(1f, curve.at(1f), 0f)
                    assertEquals(1f, curve.at(2f), 0f)
                }
            }
        }
    }

    @Test
    fun `acoustic curve lands on letter arrivals and finishes full ink`() {
        val curve = requireNotNull(
            TajweedPacing.acousticCurve(
                keyframes = listOf(
                    SubwordKeyframe(200, 0.4f),
                    SubwordKeyframe(600, 1f),
                ),
                durationMs = 1_000,
            ),
        )

        assertTrue(curve.softWash)
        assertEquals(0f, curve.at(0f), 0f)
        assertEquals(0.4f, curve.at(0.2f), 1e-3f)
        assertEquals(1f, curve.at(0.6f), 1e-3f)
        assertEquals(1f, curve.at(1f), 0f)
    }

    @Test
    fun `acoustic curve parks on a long letter hold then peels`() {
        // 1:5-style madd: ~3s on penultimate letter, then final peel.
        val curve = requireNotNull(
            TajweedPacing.acousticCurve(
                keyframes = listOf(
                    SubwordKeyframe(20, 0.166667f),
                    SubwordKeyframe(201, 0.166667f),
                    SubwordKeyframe(221, 0.333333f),
                    SubwordKeyframe(742, 0.833333f),
                    SubwordKeyframe(3727, 0.833333f),
                    SubwordKeyframe(3747, 1f),
                ),
                durationMs = 3_747,
            ),
        )

        assertEquals(0.833333f, curve.at(742f / 3747f), 1e-3f)
        // Deep in the hold — parked on that letter for the full dwell.
        assertEquals(0.833333f, curve.at(2000f / 3747f), 1e-3f)
        assertEquals(0.833333f, curve.at(3600f / 3747f), 1e-3f)
        assertEquals(1f, curve.at(1f), 0f)
    }

    @Test
    fun `acoustic curve rejects an initial progress jump at time zero`() {
        assertNull(
            TajweedPacing.acousticCurve(
                keyframes = listOf(
                    SubwordKeyframe(0, 0.25f),
                    SubwordKeyframe(400, 1f),
                ),
                durationMs = 500,
            ),
        )
    }

    @Test
    fun `acoustic curve parks holds and eases letter peels`() {
        val curve = requireNotNull(
            TajweedPacing.acousticCurve(
                keyframes = listOf(
                    SubwordKeyframe(75, 0f),
                    SubwordKeyframe(95, 0.333f),
                    SubwordKeyframe(296, 0.333f),
                    SubwordKeyframe(316, 0.666f),
                    SubwordKeyframe(416, 0.666f),
                    SubwordKeyframe(436, 1f),
                ),
                durationMs = 536,
            ),
        )
        // Mid-hold parks at first letter progress.
        assertEquals(0.333f, curve.at(200f / 536f), 1e-3f)
        var prev = curve.at(0f)
        for (i in 1..64) {
            val p = curve.at(i.toFloat() / 64f)
            assertTrue("rewind at step $i: $p < $prev", p + 1e-5f >= prev)
            prev = p
        }
        assertEquals(1f, prev, 1e-4f)
    }
}
