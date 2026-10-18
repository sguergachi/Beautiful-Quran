package com.beautifulquran.domain

import com.beautifulquran.data.model.Segment
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class HighlightSyncTest {
    private val item = HighlightItem(24, 1, 7)
    private val timing = timing(listOf(Segment(1, 0, 1000), Segment(2, 1100, 2000)))

    private fun timing(segments: List<Segment>, revision: Long = 1, voice: Long = 0) =
        HighlightTimingContext(revision, HighlightEngine.PreparedTimings.prepare(segments), voice)

    private fun sample(
        position: Long, at: Long, playing: Boolean = true, speed: Float = 1f,
        clock: Long = 0, ink: Long = 0, buffering: Boolean = false,
    ) = HighlightPlaybackSample(item, position, at * 1_000_000, playing, buffering, speed, clock, ink)

    private fun frame(state: HighlightSyncState, at: Long) = HighlightSync.frame(state, at * 1_000_000)

    @Test
    fun `a delayed first poll is accepted without a 250 millisecond debt`() {
        val initial = HighlightSync.sample(null, sample(0, 0), timing)
        val delayed = HighlightSync.sample(initial, sample(250, 250), timing)
        assertEquals(250L, delayed.acceptedMediaMs)
        assertEquals(250L, frame(delayed, 250).inkMs)
    }

    @Test
    fun `repeated 110 millisecond polls never wedge in settle`() {
        var state = HighlightSync.sample(null, sample(0, 0), timing)
        repeat(45) {
            val at = (it + 1) * 110L
            state = HighlightSync.sample(state, sample(at, at), timing)
            assertEquals(at, state.acceptedMediaMs)
        }
    }

    @Test
    fun `legitimate one and a half speed with 50 millisecond polls has no debt`() {
        var state = HighlightSync.sample(null, sample(0, 0, speed = 1.5f), timing)
        repeat(45) {
            val at = (it + 1) * 50L
            state = HighlightSync.sample(state, sample(at * 3 / 2, at, speed = 1.5f), timing)
            assertEquals(at * 3 / 2, state.acceptedMediaMs)
        }
    }

    @Test
    fun `post seek overshoot is rejected and its snap back never resets ink`() {
        var state = HighlightSync.sample(null, sample(0, 0), timing)
        state = HighlightSync.sample(state, sample(800, 33), timing)
        assertEquals(0L, state.acceptedMediaMs)
        val first = frame(state, 33)
        state = HighlightSync.sample(state, sample(100, 66), timing)
        val second = frame(state, 66)
        state = HighlightSync.sample(state, sample(40, 99), timing)
        assertTrue(frame(state, 99).inkMs >= second.inkMs)
        assertTrue(second.inkMs >= first.inkMs)
        assertEquals(0L, frame(state, 99).inkGeneration)
    }

    @Test
    fun `a smooth stale estimate cannot turn its late correction into a seek`() {
        var state = HighlightSync.sample(null, sample(0, 0), timing)
        repeat(60) { state = HighlightSync.sample(state, sample((it + 1) * 33L, (it + 1) * 33L), timing) }
        val before = frame(state, 2013)
        state = HighlightSync.sample(state, sample(300, 2013), timing)
        val corrected = frame(state, 2013)
        assertEquals(before.inkMs, corrected.inkMs)
        assertEquals(0L, corrected.inkGeneration)
        assertEquals(corrected.inkMs, frame(state, 2113).inkMs)
    }

    @Test
    fun `correction holds ink until the raw series catches up without adding more debt`() {
        var state = HighlightSync.sample(null, sample(1000, 1000), timing)
        state = HighlightSync.sample(state, sample(900, 1100), timing)
        assertEquals(1100L, frame(state, 1100).inkMs)
        assertEquals(1100L, frame(state, 1200).inkMs)
        assertEquals(1100L, frame(state, 1300).inkMs)
        assertEquals(1150L, frame(state, 1350).inkMs)
    }

    @Test
    fun `frame progression is continuous across the old poll bounded interpolation limit`() {
        val state = HighlightSync.sample(null, sample(0, 0), timing)
        for (hz in listOf(60, 90, 120)) {
            var prior = -1L
            for (i in 0..hz) {
                val at = i * 1_000_000_000L / hz
                val next = HighlightSync.frame(state, at).inkMs
                assertTrue("$hz Hz frame $i stalled", next > prior)
                prior = next
            }
            assertEquals(1000L, prior)
        }
    }

    @Test
    fun `pause and buffer freeze linear progress across arbitrary frame time`() {
        for (buffering in listOf(false, true)) {
            val running = HighlightSync.sample(null, sample(0, 0), timing)
            val stopped = HighlightSync.sample(running, sample(400, 400, playing = buffering, buffering = buffering), timing)
            val first = frame(stopped, 400)
            assertEquals(first.inkMs, frame(stopped, 9000).inkMs)
            assertEquals(first.linearProgress, frame(stopped, 9000).linearProgress, 0f)
            assertEquals(if (buffering) HighlightPhase.Buffering else HighlightPhase.Paused, first.phase)
        }
    }

    @Test
    fun `speed change uses old speed before the sample and new speed after it`() {
        val initial = HighlightSync.sample(null, sample(0, 0), timing)
        val fast = HighlightSync.sample(initial, sample(400, 400, speed = 2f), timing)
        assertEquals(400L, frame(fast, 400).inkMs)
        assertEquals(600L, frame(fast, 500).inkMs)
    }

    @Test
    fun `frozen paused or buffering samples cannot certify a post resume overshoot`() {
        for (buffering in listOf(false, true)) {
            var state = HighlightSync.sample(null,
                sample(0, 0, playing = buffering, buffering = buffering), timing)
            repeat(20) {
                state = HighlightSync.sample(state,
                    sample(0, (it + 1) * 250L, playing = buffering, buffering = buffering), timing)
            }
            state = HighlightSync.sample(state, sample(800, 5033), timing)
            assertEquals(0L, state.acceptedMediaMs)
            assertEquals(0L, frame(state, 5033).inkMs)
            assertEquals(33L, frame(state, 5066).inkMs)
            assertEquals(0L, state.sample.inkGeneration)
        }
    }

    @Test
    fun `a confirmed seek can move backward but a subsequent adjustment preserves laid ink`() {
        val initial = HighlightSync.sample(null, sample(1500, 0), timing)
        val seek = HighlightSync.sample(initial, sample(100, 33, clock = 1, ink = 1), timing)
        assertEquals(100L, frame(seek, 33).inkMs)
        assertEquals(1L, frame(seek, 33).inkGeneration)
        val adjustment = HighlightSync.sample(seek, sample(50, 66, clock = 2, ink = 1), timing)
        assertEquals(133L, frame(adjustment, 66).inkMs)
        assertEquals(1L, frame(adjustment, 66).inkGeneration)
    }

    @Test
    fun `stale timestamps and old acknowledgments cannot restore an earlier performance`() {
        val current = HighlightSync.sample(null, sample(500, 500, clock = 3, ink = 2), timing)
        assertSame(current, HighlightSync.sample(current, sample(900, 499, clock = 4, ink = 3), timing))
        assertSame(current, HighlightSync.sample(current, sample(0, 600, clock = 2, ink = 1), timing))
        assertSame(current, HighlightSync.sample(current,
            sample(0, 600, clock = 2, ink = 1).copy(item = item.copy(ayah = 2)), timing))
    }

    @Test
    fun `a stale timing publication cannot resurrect withdrawn marks`() {
        val empty = timing(emptyList(), revision = 3)
        val withdrawn = HighlightSync.sample(null, sample(500, 500), empty)
        assertSame(withdrawn, HighlightSync.sample(withdrawn, sample(600, 600), timing))
    }

    @Test
    fun `monotonic timestamps do not require a positive platform origin`() {
        val initial = sample(0, 0).copy(sampledAtNanos = -100_000_000)
        val next = initial.copy(positionMs = 50, sampledAtNanos = -50_000_000)
        val state = HighlightSync.sample(HighlightSync.sample(null, initial, timing), next, timing)
        assertEquals(50L, HighlightSync.frame(state, -50_000_000).inkMs)
    }

    @Test
    fun `different reciter chapter and ayah cannot inherit the previous ink floor`() {
        val current = HighlightSync.sample(null, sample(1500, 0), timing)
        for (next in listOf(item.copy(reciter = 1), item.copy(surah = 25), item.copy(ayah = 2))) {
            val state = HighlightSync.sample(current, sample(0, 33).copy(item = next), timing)
            assertEquals(0L, frame(state, 33).inkMs)
        }
    }

    @Test
    fun `empty timing publication cannot expose a previously selected occurrence`() {
        val initial = HighlightSync.sample(null, sample(400, 0), timing)
        val empty = timing(emptyList(), revision = 2)
        val removed = HighlightSync.sample(initial, sample(500, 100), empty)
        val frame = frame(removed, 100)
        assertEquals(2L, frame.timingRevision)
        assertEquals(HighlightPhase.MissingTimings, frame.phase)
        assertNull(frame.active)
        assertNull(frame.occurrence)
    }

    @Test
    fun `silence gap and completed tail are distinguishable without an active word`() {
        val table = timing(listOf(Segment(1, 100, 200), Segment(2, 300, 400)), voice = 100)
        fun at(ms: Long) = frame(HighlightSync.sample(null, sample(ms, 0), table), 0)
        assertEquals(HighlightPhase.WaitingForVoice, at(50).phase)
        assertNull(at(50).active)
        assertEquals(HighlightPhase.GapHold, at(250).phase)
        assertEquals(1, at(250).active!!.position)
        assertEquals(HighlightPhase.Completed, at(400).phase)
        assertNull(at(400).active)
    }

    @Test
    fun `consecutive same word repeats have distinct occurrence identities`() {
        val table = timing(listOf(Segment(1, 0, 100), Segment(1, 100, 200), Segment(2, 200, 300)))
        val first = frame(HighlightSync.sample(null, sample(50, 0), table), 0)
        val repeated = frame(HighlightSync.sample(null, sample(150, 0), table), 0)
        assertEquals(0, first.occurrence)
        assertEquals(1, repeated.occurrence)
        assertFalse(first.active!!.isRepeat)
        assertTrue(repeated.active!!.isRepeat)
        assertEquals(0.5f, repeated.linearProgress, 0f)
    }

    @Test
    fun `projecting before the opening ramp differs from adding elapsed to calibrated ink`() {
        val table = timing(listOf(Segment(1, 210, 1000)), voice = 210)
        val state = HighlightSync.sample(null, sample(210, 0), table, HighlightCalibration(wordLeadMs = 200))
        assertEquals(310L, frame(state, 50).inkMs)
        assertNotEquals(260L, frame(state, 50).inkMs)
    }

    @Test
    fun `lag and lead cancel before the zero clamp and lag scales exactly once`() {
        val state = HighlightSync.sample(null, sample(50, 0, speed = 2f), timing,
            HighlightCalibration(outputLagWallMs = 90, wordLeadMs = 200))
        assertEquals(0L, frame(state, 0).heardMs)
        assertEquals(70L, frame(state, 0).inkMs)
        assertEquals(170L, frame(state, 50).inkMs)
    }

    @Test
    fun `frame timestamps before the sample never project backward`() {
        val state = HighlightSync.sample(null, sample(500, 100), timing)
        assertEquals(frame(state, 100), frame(state, 50))
    }

    @Test
    fun `seeded transport sequences preserve identity and monotonic ink within a performance`() {
        for (seed in 0..19) {
            val random = Random(seed)
            var at = 0L
            var position = 0L
            var clock = 0L
            var ink = 0L
            var state: HighlightSyncState? = null
            var prior: HighlightFrame? = null
            repeat(500) {
                at += random.nextLong(1, 251)
                val seek = random.nextInt(20) == 0
                if (seek) { position = random.nextLong(0, 2000); clock++; ink++ }
                else position = (position + random.nextLong(-100, 251)).coerceAtLeast(0)
                state = HighlightSync.sample(state, sample(position, at, random.nextBoolean(),
                    listOf(0.5f, 1f, 1.5f, 2f).random(random), clock, ink), timing)
                val next = frame(state, at)
                if (prior?.inkGeneration == next.inkGeneration) {
                    assertTrue("seed=$seed event=$it", next.inkMs >= prior.inkMs)
                }
                assertEquals(item, next.item)
                assertTrue(next.linearProgress in 0f..1f)
                prior = next
            }
        }
    }
}
