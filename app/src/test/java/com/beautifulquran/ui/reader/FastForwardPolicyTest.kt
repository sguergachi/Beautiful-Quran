package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastForwardPolicyTest {

    private fun segs(count: Int, startAt: (Int) -> Long, endAt: (Int) -> Long): List<Segment> =
        List(count) { i -> Segment(position = i + 1, startMs = startAt(i), endMs = endAt(i)) }


    @Test
    fun `long ayah first skip goes to midpoint`() {
        val action = FastForwardPolicy.action(
            ayah = 5,
            positionMs = 0L,
            ayahCount = 20,
            midpointMs = 10_000L,
            midpointConsumedForAyah = 0,
        )
        assertEquals(FastForwardPolicy.Action.SeekToMidpoint(5, 10_000L), action)
        assertEquals(5, FastForwardPolicy.nextConsumedAyah(action))
    }

    @Test
    fun `second skip on same long ayah advances to next ayah even if position still early`() {
        // Regression: async seek leaves positionMs pre-midpoint; a second FF
        // must not re-issue the same midpoint seek forever.
        val action = FastForwardPolicy.action(
            ayah = 5,
            positionMs = 0L,
            ayahCount = 20,
            midpointMs = 10_000L,
            midpointConsumedForAyah = 5,
        )
        assertEquals(FastForwardPolicy.Action.SeekToAyah(6), action)
        assertEquals(0, FastForwardPolicy.nextConsumedAyah(action))
    }

    @Test
    fun `past midpoint goes to next ayah`() {
        val action = FastForwardPolicy.action(
            ayah = 5,
            positionMs = 12_000L,
            ayahCount = 20,
            midpointMs = 10_000L,
            midpointConsumedForAyah = 0,
        )
        assertEquals(FastForwardPolicy.Action.SeekToAyah(6), action)
    }

    @Test
    fun `short ayah has no midpoint and advances`() {
        val action = FastForwardPolicy.action(
            ayah = 2,
            positionMs = 0L,
            ayahCount = 7,
            midpointMs = null,
            midpointConsumedForAyah = 0,
        )
        assertEquals(FastForwardPolicy.Action.SeekToAyah(3), action)
    }

    @Test
    fun `last ayah past midpoint is none`() {
        val action = FastForwardPolicy.action(
            ayah = 7,
            positionMs = 50_000L,
            ayahCount = 7,
            midpointMs = 10_000L,
            midpointConsumedForAyah = 7,
        )
        assertEquals(FastForwardPolicy.Action.None, action)
    }

    @Test
    fun `within grace of midpoint treats as past midpoint`() {
        // position >= midpoint - grace → skip mid, go next
        val midpoint = 10_000L
        val action = FastForwardPolicy.action(
            ayah = 3,
            positionMs = midpoint - FastForwardPolicy.MIDPOINT_SEEK_GRACE_MS,
            ayahCount = 10,
            midpointMs = midpoint,
            midpointConsumedForAyah = 0,
        )
        assertEquals(FastForwardPolicy.Action.SeekToAyah(4), action)
    }

    @Test
    fun `new ayah after advance can mid-skip again`() {
        val first = FastForwardPolicy.action(
            ayah = 5,
            positionMs = 0L,
            ayahCount = 20,
            midpointMs = 8_000L,
            midpointConsumedForAyah = 0,
        )
        val consumed = FastForwardPolicy.nextConsumedAyah(first)
        val second = FastForwardPolicy.action(
            ayah = 5,
            positionMs = 0L,
            ayahCount = 20,
            midpointMs = 8_000L,
            midpointConsumedForAyah = consumed,
        )
        assertTrue(second is FastForwardPolicy.Action.SeekToAyah)
        val third = FastForwardPolicy.action(
            ayah = 6,
            positionMs = 0L,
            ayahCount = 20,
            midpointMs = 9_000L,
            midpointConsumedForAyah = FastForwardPolicy.nextConsumedAyah(second),
        )
        assertEquals(FastForwardPolicy.Action.SeekToMidpoint(6, 9_000L), third)
    }

    @Test
    fun `midpointMs is null for short ayahs`() {
        val segments = segs(10, startAt = { it * 1000L }, endAt = { it * 1000L + 900L })
        assertEquals(null, FastForwardPolicy.midpointMs(segments))
    }

    @Test
    fun `midpointMs picks first segment at or after time half`() {
        // 20 segs, 1s each → end 20_000, half 10_000 → first start >= 10_000 is index 10
        val segments = segs(20, startAt = { it * 1000L }, endAt = { it * 1000L + 1000L })
        assertEquals(10_000L, FastForwardPolicy.midpointMs(segments))
    }

    @Test
    fun `past time half goes to next ayah even when word-index mid would be later`() {
        // Uneven pacing: early words short, later words long. Time half is ~10s;
        // word-index mid would land later. Past time half must advance.
        val segments = segs(
            20,
            startAt = { i -> if (i < 10) i * 500L else 10_000L + (i - 10) * 1000L },
            endAt = { i ->
                val start = if (i < 10) i * 500L else 10_000L + (i - 10) * 1000L
                if (i == 19) 20_000L else start + 500L
            },
        )
        val mid = FastForwardPolicy.midpointMs(segments)!!
        val action = FastForwardPolicy.action(
            ayah = 1,
            positionMs = mid + 1_000L,
            ayahCount = 10,
            midpointMs = mid,
            midpointConsumedForAyah = 0,
        )
        assertEquals(FastForwardPolicy.Action.SeekToAyah(2), action)
    }
}
