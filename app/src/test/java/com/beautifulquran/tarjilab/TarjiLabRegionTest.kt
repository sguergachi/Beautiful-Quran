package com.beautifulquran.tarjilab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TarjiLabRegionTest {

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
    fun `loop frames stay inside the capture`() {
        val frames = loopFrames(TarjiHoldWindow(250f, 750f), 1_000f, hopCount = 10, hopSamples = 100)
        assertEquals(250, frames.first)
        assertEquals(749, frames.last)
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
