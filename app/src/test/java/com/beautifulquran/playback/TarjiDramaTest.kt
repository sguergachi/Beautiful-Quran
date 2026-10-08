package com.beautifulquran.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarjiDramaTest {

    @Test
    fun `a long lifted hold is dramatic and a syllable's ripple is not`() {
        // A verse's closing hold: a second and a half of pulse on a three
        // second note, a little louder and higher than the verse.
        val closing = TarjiDrama.score(eventMs = 1_500.0, holdMs = 3_000.0, loudness = 1.5f, pitchRatio = 1.12f)
        assertEquals(1f, closing, 1e-4f)
        // An ordinary ripple: under half a second on a short note, at the
        // verse's own level.
        val ripple = TarjiDrama.score(eventMs = 400.0, holdMs = 700.0, loudness = 1f, pitchRatio = 1f)
        assertTrue("ripple $ripple", ripple < 0.2f)
        assertTrue(ripple < Tarji.MIN_DRAMA && closing >= Tarji.MIN_DRAMA)
    }

    @Test
    fun `lift is the voice raised in loudness or pitch, whichever is more`() {
        fun lift(loudness: Float, pitch: Float) =
            TarjiDrama.score(eventMs = 0.0, holdMs = 0.0, loudness = loudness, pitchRatio = pitch) / 0.25f
        assertEquals(1f, lift(1.42f, 1f), 0.02f) // +3 dB
        assertEquals(1f, lift(0.5f, 1.19f), 0.02f) // +3 semitones while quieter
        assertEquals(0f, lift(0.79f, 0.94f), 0.02f) // −2 dB and −1 semitone
        // An octave error of the pitch tracker is not a lift.
        assertEquals(lift(1f, 1f), lift(1f, 2f), 1e-4f)
    }

    @Test
    fun `the recording method keeps Hani's closing hold and drops the ripples`() {
        val stream = Hani214.pcm()
        fun events(minDrama: Float): Pair<Int, Float> {
            val detector = Tarji()
            detector.hopSamples = Hani214.HOP_SAMPLES
            detector.hopContentDurationMs = Hani214.HOP_MS
            Hani214.knobs.applyTo(detector)
            detector.minDrama = minDrama
            val frames = ArrayList<TarjiFrame>()
            val scratch = FloatArray(Hani214.HOP_SAMPLES)
            for (hop in 0 until stream.size / Hani214.HOP_SAMPLES) {
                stream.copyInto(scratch, 0, hop * scratch.size, (hop + 1) * scratch.size)
                detector.onSamples8k(scratch)
                frames += detector.measurements.copy()
            }
            val result = TarjiRecordingDetector.analyze(frames, detector)
            val closingFrom = ((Hani214.FINAL_WORD_START_MS - Hani214.START_MS) / Hani214.HOP_MS).toInt()
            val closing = (closingFrom until result.gain.size).maxOf { result.gain[it] }
            return result.eventStart.filter { it >= 0 }.toSet().size to closing
        }
        val (all, closingAll) = events(0f)
        val (dramatic, closingKept) = events(Tarji.MIN_DRAMA)
        assertTrue("the closing word lights with every event kept ($closingAll)", closingAll > 0.5f)
        assertTrue("and stays lit when only drama is kept ($closingKept)", closingKept > 0.8f)
        assertTrue("fewer events survive ($dramatic of $all)", dramatic < all)
    }
}
