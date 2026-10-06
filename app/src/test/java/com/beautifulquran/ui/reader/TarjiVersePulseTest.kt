package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.Word
import com.beautifulquran.playback.Hani214
import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiLabTrim
import com.beautifulquran.tarjilab.HANI_TUNING
import com.beautifulquran.tarjilab.TarjiLabCodec
import com.beautifulquran.tarjilab.TarjiLabKnobs
import com.beautifulquran.tarjilab.analyzeTarjiCapture
import com.beautifulquran.tarjilab.tarjiAcceptedPulseWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class TarjiVersePulseTest {

    private val audio = TarjiVersePulse.Decoded(Hani214.pcm(), Hani214.HOP_SAMPLES, Hani214.HOP_MS)
    private val audioMs = audio.pcm.size / audio.hopSamples * audio.hopMs
    /** The fixture starts 12 270 ms into the verse; its final word at 17 330. */
    private val finalStart = Hani214.FINAL_WORD_START_MS - Hani214.START_MS
    /** The lab's view of that word: 300 ms before its first mark. */
    private val labStart = finalStart - TarjiLabTrim.WORD_LEAD_MS

    private fun sample(name: String) =
        TarjiLabCodec.decode(javaClass.getResourceAsStream("/tarji/$name")!!.bufferedReader().use { it.readText() })

    @Test
    fun `the line under a word is the graph the Tarji Lab draws for it`() {
        // The lab's own capture of 2:14's closing word, analysed as the lab
        // does, against the reader's line for the same word from the verse's
        // audio — same settings, same span.
        val exported = sample("hani_2_14_w16_tuned.json")
        val lab = tarjiAcceptedPulseWave(analyzeTarjiCapture(TarjiLabCodec.toCapture(exported), exported.knobs))
        val line = TarjiVersePulse.lines(
            audio, exported.knobs, listOf(TarjiVersePulse.Window(labStart, audioMs)),
        ).single()
        // The lab's capture starts on the same instant, to within a hop or two.
        val labStartMs = exported.firstHopMediaMs - Hani214.START_MS
        assertEquals(labStart, labStartMs, 2 * audio.hopMs)
        // Quiet through the first half, as the lab shows it…
        assertTrue(line.take(line.size * 2 / 5).all { abs(it) < 0.15f })
        // …then the same wave: crests and troughs to the edges of the graph.
        assertTrue("crest ${line.max()} trough ${line.min()}", line.max() > 0.9f && line.min() < -0.9f)
        // Hop for hop it is the lab's trace. The capture places itself on the
        // media clock from the tap's estimate of the playback head, which sits
        // some 160 ms off the file's own time — a twentieth of the graph.
        val n = minOf(line.size, lab.size)
        val best = (-10..10).maxOf { shift ->
            var dot = 0.0; var a = 0.0; var b = 0.0
            for (i in 0 until n) {
                val j = i + shift
                if (j !in 0 until n) continue
                dot += line[i] * lab[j]; a += line[i] * line[i]; b += lab[j] * lab[j]
            }
            dot / sqrt(a * b)
        }
        assertTrue("the line follows the lab's graph ($best)", best > 0.85)
    }

    @Test
    fun `Hani's closing word of the Fatihah draws a wave under the shipped tuning`() {
        val exported = sample("hani_1_7_w9_tuned.json")
        val capture = TarjiLabCodec.toCapture(exported)
        val heard = TarjiVersePulse.Decoded(capture.pcm, capture.hopSamples, exported.hopContentDurationMs.toDouble())
        val line = TarjiVersePulse.lines(
            heard, HANI_TUNING, listOf(TarjiVersePulse.Window(0.0, capture.hopCount * heard.hopMs)),
        ).single()
        assertTrue("crest ${line.max()} trough ${line.min()}", line.max() > 0.3f && line.min() < -0.3f)
    }

    @Test
    fun `a volume threshold keeps the pulse to a full voice`() {
        val window = listOf(TarjiVersePulse.Window(labStart, audioMs))
        fun swing(minVolume: Float) = TarjiVersePulse.lines(
            audio, Hani214.knobs.copy(minVolume = minVolume), windows = window,
        ).single().maxOf { abs(it) }
        val open = swing(0f)
        assertTrue("no threshold $open", open > 0.3f)
        // Hani's closing hold is sung at about a tenth of full scale: a
        // threshold under it changes nothing…
        assertEquals(open, swing(0.02f), 1e-6f)
        // …and one above anything he sings leaves the word still.
        assertEquals(0f, swing(0.6f), 0f)
        // Off is exactly the detector as it was.
        assertEquals(Tarji.MIN_VOLUME, TarjiLabKnobs().minVolume, 0f)
        assertEquals(0f, InkEngine.Tuning().tarjiMinVolume, 0f)
    }

    @Test
    fun `work no longer wanted is abandoned, and paint dials do not ask for it again`() {
        var asked = 0
        val abandoned = runCatching {
            TarjiVersePulse.lines(audio, Hani214.knobs, listOf(TarjiVersePulse.Window(0.0, audioMs))) {
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
    fun `only words that may pulse get a line, each across the span the lab shows`() {
        val words = listOf(
            Word(1, "قَالُوٓا۟", "", ""),
            Word(2, "نَحۡنُ", "", ""),
            Word(3, "مُسۡتَهۡزِءُونَ", "", ""),
        )
        val ayah = Ayah(2, 14, "", "", words = words)
        val segments = listOf(Segment(1, 0, 900), Segment(2, 1_000, 1_400), Segment(3, 1_500, 4_000))
        val windows = TarjiVersePulse.windows(ayah, segments, audioMs = 4_600.0)
        val eligible = words.filter { InkEngine.tarjiEligible(it.arabic, it.position == 3) }.map { it.position }
        assertEquals(eligible, windows.keys.toList())
        assertTrue(3 in windows.keys)
        // 300 ms before the word's first mark, a second after its last — and
        // never past either end of the audio.
        assertEquals(1_200.0, windows.getValue(3).startMs, 0.0)
        assertEquals(4_600.0, windows.getValue(3).endMs, 0.0)
        windows[1]?.let {
            assertEquals(0.0, it.startMs, 0.0)
            assertEquals(1_900.0, it.endMs, 0.0)
        }
        // A word the timings do not cover has no line.
        assertTrue(TarjiVersePulse.windows(ayah, segments.take(1), 4_600.0).keys.all { it == 1 })
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
