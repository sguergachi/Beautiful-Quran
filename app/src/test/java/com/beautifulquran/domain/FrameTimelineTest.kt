package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FrameTimelineTest {

    private fun FrameRing.frame(atMs: Long, totalMs: Int, uiMs: Int = 1, cmdMs: Int = 1, deadlineMs: Int = 8) =
        add(
            startNanos = atMs * 1_000_000L,
            totalUs = totalMs * 1000,
            delayUs = 0,
            inputUs = 0,
            animationUs = uiMs * 1000,
            layoutUs = 0,
            drawUs = 0,
            syncUs = 0,
            commandUs = cmdMs * 1000,
            swapUs = 0,
            gpuUs = 2000,
            deadlineUs = deadlineMs * 1000,
            firstDraw = false,
        )

    @Test
    fun ringKeepsTheFirstFramesAndCountsTheRest() {
        val ring = FrameRing(2)
        ring.frame(0, 5)
        ring.frame(8, 5)
        ring.frame(16, 5)
        val timeline = ring.snapshot()
        assertEquals(2, timeline.frames.size)
        assertEquals(1, timeline.dropped)
        assertEquals(8_000_000L, timeline.frames.last().startNanos)
    }

    @Test
    fun reportNamesTheSlowFrameAndTheEventBeforeIt() {
        val ring = FrameRing(16)
        ring.frame(1000, 6)
        ring.frame(1008, 172, uiMs = 5, cmdMs = 165)
        ring.frame(1200, 7)
        val lines = ring.snapshot().report(
            title = "launch",
            originNanos = 0L,
            refreshHz = 120f,
            marks = listOf(
                TimedMark(900_000_000L, "stackSettleStart 0.0 to 1"),
                TimedMark(2_000_000_000L, "later"),
            ),
        )
        assertTrue(lines.any { it.startsWith("frames=3 dropped=0 missed=1") })
        val turn = lines.single { it.contains("\"stackSettleStart 0.0 to 1\"") && it.contains("frames=") }
        // 900 ms start, no end mark: 1 s turn + 400 ms tail covers all three frames.
        assertTrue(turn, turn.contains("frames=3 missed=1 worst=172.0"))
        val slow = lines.single { it.contains("total=172.0") }
        assertTrue(slow, slow.contains("rt=165.0"))
        assertTrue(slow, slow.contains("after \"stackSettleStart 0.0 to 1\" (+108.0 ms)"))
        // Every frame is listed once in the table.
        assertEquals(3, lines.count { Regex("""^[+-]\d+\.\d{3},""").containsMatchIn(it) })
    }

    @Test
    fun numbersStayDotDecimalInAnyLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val ring = FrameRing(4)
            ring.frame(10, 12)
            val table = ring.snapshot().report("x", 0L, 60f).last()
            assertEquals("+0.010,12.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0,0.0,2.0,8.0", table)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun parsesRecordedEventLines() {
        val mark = FrameTimeline.parseMark("+3.460s atNanos=298350113173715  stackSettleStart 0.0 to 1")
        assertEquals(TimedMark(298350113173715L, "stackSettleStart 0.0 to 1"), mark)
    }
}
