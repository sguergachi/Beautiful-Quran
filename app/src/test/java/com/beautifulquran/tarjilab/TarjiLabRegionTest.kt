package com.beautifulquran.tarjilab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TarjiLabRegionTest {

    @Test
    fun `slow playhead wraps the loop at content speed`() {
        val first = loopPlayheadMs(400f, 200f, 0.5f, 400f, 900f)
        assertEquals(500f, first, 0.1f)
        val wrapped = loopPlayheadMs(800f, 400f, 0.5f, 400f, 900f)
        assertEquals(500f, wrapped, 0.1f)
        val unity = loopPlayheadMs(400f, 100f, 1f, 400f, 900f)
        assertEquals(500f, unity, 0.1f)
    }

    @Test
    fun `hold life is a wave until stamped still`() {
        assertTrue(holdLifeAlive(TarjiExpectationKind.UNLABELED))
        assertTrue(holdLifeAlive(TarjiExpectationKind.PULSES))
        assertTrue(!holdLifeAlive(TarjiExpectationKind.NO_SHIMMER))
        assertEquals(0.5f, holdLifeY(TarjiExpectationKind.NO_SHIMMER, 0.25f, 800f), 0f)
        val crest = holdLifeY(TarjiExpectationKind.PULSES, 0.1f, 800f)
        assertTrue(crest != 0.5f)
        assertEquals(TarjiExpectationKind.NO_SHIMMER, nextHoldLife(TarjiExpectationKind.PULSES))
        assertEquals(TarjiExpectationKind.PULSES, nextHoldLife(TarjiExpectationKind.NO_SHIMMER))
    }

    @Test
    fun `pcm slice follows the view window`() {
        val full = pcmSlice(TarjiViewWindow.fit(1_000f), 1_000f, pcmSize = 100)
        assertEquals(0, full.first)
        assertEquals(99, full.last)
        val zoomed = pcmSlice(TarjiViewWindow(250f, 500f), 1_000f, pcmSize = 100)
        assertEquals(25, zoomed.first)
        assertEquals(49, zoomed.last)
    }

    @Test
    fun `zoom keeps the focus and pan stays inside the capture`() {
        val full = TarjiViewWindow.fit(1_000f)
        val inOn = zoomView(full, 1_000f, focusMs = 400f, scale = 0.5f)
        assertEquals(500f, inOn.spanMs, 0.1f)
        assertTrue(inOn.startMs <= 400f && 400f <= inOn.endMs)
        val panned = panView(inOn, 1_000f, 400f)
        assertEquals(1_000f, panned.endMs, 0.1f)
        assertEquals(500f, panned.startMs, 0.1f)
        assertEquals(1_000f, zoomView(full, 1_000f, 400f, 4f).spanMs, 0.1f)
    }

    @Test
    fun `hold window clamps and keeps a minimum width`() {
        val moved = TarjiHoldWindow(200f, 800f).moveStart(790f, 1_000f)
        assertEquals(720f, moved.startMs, 0.01f)
        assertEquals(800f, moved.endMs, 0.01f)

        val translated = TarjiHoldWindow(100f, 300f).translate(900f, 1_000f)
        assertEquals(800f, translated.startMs, 0.01f)
        assertEquals(1_000f, translated.endMs, 0.01f)
    }

    @Test
    fun `seed prefers the detector span then the whole capture`() {
        val seeded = seedHoldWindow(2_000f, 400f, 1_100f)
        assertEquals(400f, seeded.startMs, 0f)
        assertEquals(1_100f, seeded.endMs, 0f)
        val whole = seedHoldWindow(2_000f, null, null)
        assertEquals(0f, whole.startMs, 0f)
        assertEquals(2_000f, whole.endMs, 0f)
    }

    @Test
    fun `handle hit test prefers the nearer edge`() {
        val window = TarjiHoldWindow(200f, 800f)
        assertEquals(TarjiCanvasHit.START, hitHoldWindow(40f, 200f, window, 1_000f, 12f))
        assertEquals(TarjiCanvasHit.END, hitHoldWindow(160f, 200f, window, 1_000f, 12f))
        assertEquals(TarjiCanvasHit.BODY, hitHoldWindow(100f, 200f, window, 1_000f, 12f))
        assertNull(hitHoldWindow(10f, 200f, window, 1_000f, 8f))
    }

    @Test
    fun `envelope paint writes the hop under the finger`() {
        val painted = paintEnvelope(
            current = emptyList(),
            hopCount = 10,
            captureMs = 200f,
            x = 100f,
            y = 0f,
            width = 200f,
            height = 100f,
        )
        assertEquals(10, painted.size)
        assertEquals(1f, painted[5], 0f)
        assertTrue(painted[4] > 0f)
        assertTrue(painted[6] > 0f)
    }

    @Test
    fun `hold drag outside creates a range only after the finger moves`() {
        val current = TarjiHoldWindow(200f, 800f)
        val dragged = holdDrag(
            hit = null,
            originMs = 100f,
            lastMs = 100f,
            atMs = 450f,
            current = current,
            captureMs = 1_000f,
        )
        assertEquals(100f, dragged.startMs, 0f)
        assertEquals(450f, dragged.endMs, 0f)
    }

    @Test
    fun `hold start and end handles stay independent`() {
        val current = TarjiHoldWindow(200f, 800f)
        val start = holdDrag(TarjiCanvasHit.START, 200f, 200f, 350f, current, 1_000f)
        assertEquals(350f, start.startMs, 0f)
        assertEquals(800f, start.endMs, 0f)
        val end = holdDrag(TarjiCanvasHit.END, 800f, 800f, 600f, current, 1_000f)
        assertEquals(200f, end.startMs, 0f)
        assertEquals(600f, end.endMs, 0f)
    }

    @Test
    fun `word preview loops the capture even when a hold is set`() {
        val hold = TarjiHoldWindow(400f, 900f)
        val word = previewLoopWindow(TarjiPreviewScope.WORD, hold, 2_000f)
        assertEquals(0f, word.startMs, 0f)
        assertEquals(2_000f, word.endMs, 0f)
        val band = previewLoopWindow(TarjiPreviewScope.HOLD, hold, 2_000f)
        assertEquals(400f, band.startMs, 0f)
        assertEquals(900f, band.endMs, 0f)
        val missing = previewLoopWindow(TarjiPreviewScope.HOLD, null, 2_000f)
        assertEquals(0f, missing.startMs, 0f)
        assertEquals(2_000f, missing.endMs, 0f)
    }

    @Test
    fun `play starts inside the hold or at its start`() {
        val window = TarjiHoldWindow(400f, 900f)
        assertEquals(400f, playheadForPlay(50f, window), 0f)
        assertEquals(500f, playheadForPlay(500f, window), 0f)
        assertEquals(400f, playheadForPlay(900f, window), 0f)
        assertEquals(400f, playheadForHoldDrag(TarjiCanvasHit.START, window), 0f)
        assertEquals(899f, playheadForHoldDrag(TarjiCanvasHit.END, window), 0f)
    }

    @Test
    fun `hold edit always plays from the new start`() {
        val window = TarjiHoldWindow(620f, 1880f)
        assertEquals(620f, playheadAfterHoldEdit(window), 0f)
        assertTrue(isInsideHold(620f, window))
        assertTrue(isInsideHold(1879f, window))
        assertTrue(!isInsideHold(1880f, window))
        assertTrue(!isInsideHold(100f, window))
    }

    @Test
    fun `loop frames stay inside the capture`() {
        val frames = loopFrames(TarjiHoldWindow(250f, 750f), 1_000f, hopCount = 10, hopSamples = 100)
        assertEquals(250, frames.first)
        assertEquals(749, frames.last)
    }

    @Test
    fun `still kills the word glow and a shape owns it`() {
        assertTrue(!labWordGlow(TarjiExpectationKind.NO_SHIMMER, listOf(1f), null, 40f, 20f).holding)
        val shaped = labWordGlow(
            TarjiExpectationKind.PULSES,
            listOf(0f, 0f, 0.8f, 0.2f),
            trace = null,
            ms = 50f,
            hopDurationMs = 20f,
        )
        assertTrue(shaped.holding)
        assertEquals(1f, shaped.gain, 0f)
        assertEquals(0.6f, shaped.tremolo, 0.001f)
        val idle = labWordGlow(TarjiExpectationKind.UNLABELED, emptyList(), null, 0f, 20f)
        assertTrue(!idle.holding)
        val trough = labWordGlow(
            TarjiExpectationKind.PULSES,
            listOf(0f, 0.9f, 0f),
            trace = null,
            ms = 0f,
            hopDurationMs = 20f,
        )
        assertTrue(!trough.holding)
        assertEquals(-1f, trough.tremolo, 0f)
    }

    @Test
    fun `profile book round-trips knobs per reciter`() {
        val book = ReciterTarjiProfileBook(
            mapOf("7" to TarjiLabKnobs(holdMinMs = 420f, minTremoloDepth = 0.08f)),
        )
        val decoded = ReciterTarjiProfileBook.decode(ReciterTarjiProfileBook.encode(book))
        assertEquals(420f, decoded.profiles["7"]!!.holdMinMs, 0f)
        assertEquals(0.08f, decoded.profiles["7"]!!.minTremoloDepth, 0f)
    }
}
