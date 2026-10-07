package com.beautifulquran.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class TarjiRecordingAlignmentTest {
    @Test fun decoderPrimingIsMeasuredOnHopCentresWithGainAndSeekIndependence() {
        val random = Random(17)
        val decoded = FloatArray(500) { 0.03f + random.nextFloat() * 0.3f }
        for (hopMs in listOf(20.0, 176_000.0 / 8820.0)) for (offset in listOf(-4, 0, 3)) {
            val firstMs = 5010.0
            val live = FloatArray(96) { i ->
                val p = (firstMs + i * hopMs) / 20.0 - 0.5 + offset
                val a = p.toInt()
                (2.0 * (decoded[a] + (p - a) * (decoded[a + 1] - decoded[a]))).toFloat()
            }
            val alignment = TarjiRecordingAlignment()
            assertTrue("offset $offset hop $hopMs", alignment.match(live, live.size, firstMs, hopMs, decoded, 20.0))
            assertEquals(offset * 20.0, alignment.offsetMs, 0.0)
            assertEquals(1.0, alignment.correlation, 1e-6)
        }
    }

    @Test fun shortSteadyAmbiguousOrUnrelatedEvidenceCannotAuthorizeAlignment() {
        val random = Random(19)
        val unrelated = FloatArray(500) { 0.1f + random.nextFloat() }
        val flat = FloatArray(500) { 0.2f }
        val periodic = FloatArray(500) { (0.2 + 0.1 * sin(it * Math.PI * 2 / 5)).toFloat() }
        for ((decoded, live) in listOf(flat to flat.copyOf(96), periodic to periodic.copyOf(96),
            unrelated to FloatArray(96) { 0.1f + random.nextFloat() })) {
            val alignment = TarjiRecordingAlignment()
            assertFalse(alignment.match(live, live.size, 10.0, 20.0, decoded, 20.0))
            assertTrue(alignment.offsetMs.isNaN())
        }
        assertFalse(TarjiRecordingAlignment().match(unrelated, 64, 10.0, 20.0, unrelated, 20.0))
        assertFalse(TarjiRecordingAlignment().match(unrelated, 96, Double.NaN, 20.0, unrelated, 20.0))
    }
}
