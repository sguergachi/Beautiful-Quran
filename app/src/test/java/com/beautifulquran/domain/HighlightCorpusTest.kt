package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in exhaustive runtime snapshot audit. Restricted timing packs stay outside the checkout. */
class HighlightCorpusTest {
    @Test
    fun `V2 preserves every delivered occurrence boundary and seek ordering`() {
        val path = System.getProperty("bq.highlightCorpus")
        assumeNotNull(path)
        var rows = 0
        var queries = 0L
        var repeats = 0L
        val reciters = mutableSetOf<Int>()
        File(path!!).forEachLine { line ->
            val (reciter, surah, ayah, encoded) = line.split('\t')
            reciters += reciter.toInt()
            val segments = encoded.split(';').map {
                val (position, start, end) = it.split(',')
                Segment(position.toInt(), start.toLong(), end.toLong())
            }
            val prepared = HighlightEngine.PreparedTimings.prepare(segments)
            val context = HighlightTimingContext(1, prepared, segments.first().startMs)
            val item = HighlightItem(surah.toInt(), ayah.toInt(), reciter.toInt())
            val clocks = segments.flatMap { listOf(it.startMs - 1, it.startMs,
                (it.startMs + it.endMs) / 2, it.endMs - 1, it.endMs) }.filter { it >= 0 }.distinct()
            var state: HighlightSyncState? = null
            var generation = 0L
            for (order in listOf(clocks, clocks.reversed(), clocks.sortedBy { it * 48271 % 2147483647 })) {
                for (clock in order) {
                    generation++
                    val input = HighlightPlaybackSample(item, clock, generation * 1_000_000, true,
                        clockRevision = generation, inkGeneration = generation)
                    state = HighlightSync.sample(state, input, context)
                    val frame = HighlightSync.frame(state, input.sampledAtNanos)
                    val index = if (clock >= segments.last().endMs) -1 else segments.indexOfLast { it.startMs <= clock }
                    assertEquals("$item/$clock occurrence", index.takeIf { it >= 0 }, frame.occurrence)
                    if (index >= 0) {
                        val segment = segments[index]
                        val actual = frame.active!!
                        assertEquals(segment.position, actual.position)
                        assertEquals(segment.startMs, actual.startMs)
                        val priorMax = segments.take(index).maxOfOrNull { it.position } ?: -1
                        assertEquals(segment.position <= priorMax, actual.isRepeat)
                        assertEquals(maxOf(priorMax, segment.position), actual.highWater)
                        assertTrue(frame.linearProgress in 0f..1f)
                        val window = HighlightWashWindow(item.surah, item.ayah, generation,
                            segment.startMs, actual.holdEndMs - segment.startMs)
                        assertEquals(frame.linearProgress, window.progress(frame), 0.000001f)
                    } else assertNull(frame.active)
                    queries++
                }
            }
            repeats += segments.indices.count { i -> segments.take(i).any { it.position >= segments[i].position } }
            rows++
        }
        assertEquals(13, reciters.size)
        assertTrue(rows > 80_000)
        println("V2 corpus: rows=$rows reciters=${reciters.sorted()} queries=$queries repeatOccurrences=$repeats")
    }
}
