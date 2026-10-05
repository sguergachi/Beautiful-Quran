package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.Word
import com.beautifulquran.playback.Hani214
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TarjiVersePulseTest {

    private val audio = TarjiVersePulse.Decoded(Hani214.pcm(), Hani214.HOP_SAMPLES, Hani214.HOP_MS)
    private val audioMs = audio.pcm.size / audio.hopSamples * audio.hopMs
    /** The fixture starts 12 270 ms into the verse; its final word at 17 330. */
    private val finalStart = Hani214.FINAL_WORD_START_MS - Hani214.START_MS

    @Test
    fun `the line drawn under a word ahead of time is the pulse the reader gives it`() {
        val windows = listOf(
            TarjiVersePulse.Window(0.0, finalStart),
            TarjiVersePulse.Window(finalStart, audioMs),
        )
        val (before, closing) = TarjiVersePulse.lines(audio, Hani214.knobs, depth = 1f, windows = windows)
        // One value per analysis hop across the word.
        assertEquals(((audioMs - finalStart) / audio.hopMs).toInt(), closing.size)
        // Hani's closing hold reverberates: the line swings both ways…
        assertTrue("crest ${closing.max()}", closing.max() > 0.3f)
        assertTrue("trough ${closing.min()}", closing.min() < -0.3f)
        // …several times, not once: it is a wave, at a rate tarji lives at.
        val crossings = closing.toList().zipWithNext().count { (a, b) -> a < 0f && b >= 0f }
        assertTrue("$crossings crests", crossings in 4..40)
        // and it is quiet before the voice has held anything long enough.
        assertTrue(closing.take(10).all { abs(it) < 0.05f })
        // The words before it carry a false start the reader keeps dark.
        assertTrue("before ${before.maxOf { abs(it) }}", before.maxOf { abs(it) } < closing.maxOf { abs(it) })
    }

    @Test
    fun `Hani's closing word of the Fatihah draws a wave under the shipped tuning`() {
        val sample = com.beautifulquran.tarjilab.TarjiLabCodec.decode(
            javaClass.getResourceAsStream("/tarji/hani_1_7_w9_tuned.json")!!.bufferedReader().use { it.readText() })
        val capture = com.beautifulquran.tarjilab.TarjiLabCodec.toCapture(sample)
        val heard = TarjiVersePulse.Decoded(capture.pcm, capture.hopSamples, sample.hopContentDurationMs.toDouble())
        val wordStart = 7_610.0 - sample.firstHopMediaMs
        val end = capture.hopCount * heard.hopMs
        val line = TarjiVersePulse.lines(
            heard, com.beautifulquran.tarjilab.HANI_TUNING, depth = 1f,
            windows = listOf(TarjiVersePulse.Window(wordStart.coerceAtLeast(0.0), end)),
        ).single()
        assertTrue("crest ${line.max()} trough ${line.min()}", line.max() > 0.3f && line.min() < -0.3f)
    }

    @Test
    fun `a volume threshold keeps the pulse to a full voice`() {
        val window = listOf(TarjiVersePulse.Window(finalStart, audioMs))
        fun swing(minVolume: Float) = TarjiVersePulse.lines(
            audio, Hani214.knobs.copy(minVolume = minVolume), depth = 1f, windows = window,
        ).single().maxOf { abs(it) }
        val open = swing(0f)
        assertTrue("no threshold $open", open > 0.3f)
        // Hani's closing hold is sung at about a tenth of full scale: a
        // threshold under it changes nothing…
        assertEquals(open, swing(0.02f), 1e-6f)
        // …and one above anything he sings leaves the word still.
        assertEquals(0f, swing(0.6f), 0f)
        // Off is exactly the detector as it was.
        assertEquals(com.beautifulquran.playback.Tarji.MIN_VOLUME, com.beautifulquran.tarjilab.TarjiLabKnobs().minVolume, 0f)
        assertEquals(0f, InkEngine.Tuning().tarjiMinVolume, 0f)
    }

    @Test
    fun `work no longer wanted is abandoned, and paint dials do not ask for it again`() {
        var asked = 0
        val abandoned = runCatching {
            TarjiVersePulse.lines(audio, Hani214.knobs, 1f, listOf(TarjiVersePulse.Window(0.0, audioMs))) {
                ++asked < 3
            }
        }
        assertTrue(abandoned.exceptionOrNull() is java.util.concurrent.CancellationException)
        // Given up within a couple of seconds of audio, not at the verse's end.
        assertEquals(3, asked)
        // A verse's lines depend on the detector alone: brightness, glow and
        // smoothing can be dragged without one verse being redone.
        val shipped = InkEngine.Tuning()
        val painted = shipped.copy(glintBrightness = 2f, tarjiLightRise = 0.1f, tarjiGlowGain = 2f, tarjiLightSmoothMs = 30f)
        assertEquals(TarjiVersePulse.detectorKnobs(shipped), TarjiVersePulse.detectorKnobs(painted))
        assertTrue(TarjiVersePulse.detectorKnobs(shipped) != TarjiVersePulse.detectorKnobs(shipped.copy(tarjiMinVolume = 0.1f)))
    }

    @Test
    fun `only words that may pulse get a line, each across the span it is active`() {
        val words = listOf(
            Word(1, "قَالُوٓا۟", "", ""),
            Word(2, "نَحۡنُ", "", ""),
            Word(3, "مُسۡتَهۡزِءُونَ", "", ""),
        )
        val ayah = Ayah(2, 14, "", "", words = words)
        val segments = listOf(Segment(1, 0, 900), Segment(2, 1_000, 1_400), Segment(3, 1_500, 4_000))
        val windows = TarjiVersePulse.windows(ayah, segments, audioMs = 6_000.0)
        val eligible = words.filter { InkEngine.tarjiEligible(it.arabic, it.position == 3) }.map { it.position }
        assertEquals(eligible, windows.keys.toList())
        assertTrue(3 in windows.keys)
        // The closing word is held to the end of the audio, not its segment's end.
        assertEquals(1_500.0, windows.getValue(3).startMs, 0.0)
        assertEquals(6_000.0, windows.getValue(3).endMs, 0.0)
        // An earlier one runs to the next word's start.
        windows[1]?.let { assertEquals(1_000.0, it.endMs, 0.0) }
        // A word the timings do not cover has no line.
        assertTrue(TarjiVersePulse.windows(ayah, segments.take(1), 6_000.0).keys.all { it == 1 })
    }

    @Test
    fun `the decimator is the tap's own`() {
        val d = TarjiVersePulse.Decimator()
        d.configure(44_100)
        repeat(44_100) { d.add(if (it % 5 == 0) 1f else 0f) }
        val out = d.finish()!!
        assertEquals(176, out.hopSamples)
        assertEquals(8_820, out.pcm.size)
        assertEquals(0.2f, out.pcm[100], 1e-6f)
        assertEquals(Hani214.HOP_MS, out.hopMs, 1e-9)
    }
}
