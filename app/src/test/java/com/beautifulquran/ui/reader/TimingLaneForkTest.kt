package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.SubwordKeyframe
import org.junit.Assert.assertEquals
import org.junit.Test

/** V1 and V2 are parallel DB forks; this counts true acoustic V2 ayahs. */
class TimingLaneForkTest {
    @Test
    fun acousticCountIgnoresV1FallbackRows() {
        val v1 = listOf(Segment(1, 0, 100))
        val v2 = listOf(
            Segment(
                position = 1,
                startMs = 0,
                endMs = 100,
                subwordKeyframes = listOf(SubwordKeyframe(50, 1f)),
            ),
        )
        val map = mapOf(1 to v1, 2 to v2, 3 to v1)
        assertEquals(1, ReaderViewModel.acousticV2AyahCount(map))
    }
}
