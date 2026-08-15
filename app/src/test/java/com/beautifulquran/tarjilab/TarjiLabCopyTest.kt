package com.beautifulquran.tarjilab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TarjiLabCopyTest {

    @Test
    fun `times share one unit and never mix milliseconds`() {
        assertEquals("1.12 / 1.96 s", formatLabClock(1_120f, 1_960f))
        assertEquals("0.13–1.43 s", formatLabRange(130f, 1_430f))
    }

    @Test
    fun `detector is silent unless it disagrees`() {
        val late = TarjiExpectationComparison(
            detectedStartMs = 1_050f,
            detectedEndMs = 1_770f,
            detectedRateHz = 5f,
            startErrorMs = 917f,
            endErrorMs = 333f,
            rateErrorHz = null,
            meanCrestErrorMs = null,
        )
        assertEquals("Late", detectorDisagreement(TarjiExpectationKind.PULSES, late, false))
        assertNull(
            detectorDisagreement(
                TarjiExpectationKind.PULSES,
                late.copy(startErrorMs = 20f, endErrorMs = -10f),
                false,
            ),
        )
        assertNull(detectorDisagreement(TarjiExpectationKind.UNLABELED, late, false))
        assertNull(
            detectorDisagreement(
                TarjiExpectationKind.NO_SHIMMER,
                late.copy(detectedStartMs = null),
                false,
            ),
        )
        assertEquals(
            "Hears a hold",
            detectorDisagreement(TarjiExpectationKind.NO_SHIMMER, late, false),
        )
    }
}
