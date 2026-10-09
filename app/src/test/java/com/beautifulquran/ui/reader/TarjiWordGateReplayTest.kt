package com.beautifulquran.ui.reader

import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiEarSample
import com.beautifulquran.playback.TarjiEarTrack
import com.beautifulquran.playback.VoiceEnergy
import com.beautifulquran.tarjilab.TarjiLabKnobs
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The reader's word gate against a real recitation, through the reader's own
 * chain: Minshawi's closing word of the Fatihah (ٱلضَّآلِّينَ), from 2 s
 * before it, decimated as the tap does (8820 Hz). Its graph peaks at full
 * swing; the old gate let the syllable's brief, weak attack event spend the
 * word and held the light under a fifth of that, on every hop phase.
 */
class TarjiWordGateReplayTest {

    private val hopSamples = 176
    private val hopMs = hopSamples * 1000.0 / 8820.0
    /** The word's timing mark, 2 s into the clip. */
    private val wordStartMs = 2_000L

    private fun pcm(): FloatArray {
        val wav = javaClass.getResourceAsStream("/tarji/minshawi_1_7_w9_from_6240_8820.wav")!!.readBytes()
        val samples = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(samples.remaining()) { samples.get(it) / 32768f }
    }

    @Test
    fun `a closing hold's reverberation lights its word on every hop phase`() {
        val stream = pcm()
        val knobs = TarjiLabKnobs.fromTuning(InkEngine.Tuning())
        val peaks = (0 until 20).map { phase ->
            val dropped = phase * 9
            val detector = Tarji()
            detector.hopSamples = hopSamples
            knobs.applyTo(detector)
            val track = TarjiEarTrack()
            val ear = TarjiEarSample()
            val gate = TarjiWordGate(TarjiEventLedger())
            var peak = 0f
            val hops = (stream.size - dropped) / hopSamples
            val offsetMs = dropped * 1000.0 / 8820.0
            for (hop in 0 until hops + EAR_BEHIND_HOPS) {
                if (hop < hops) {
                    val at = dropped + hop * hopSamples
                    detector.onSamples8k(stream.copyOfRange(at, at + hopSamples))
                    track.publish(
                        detector.hopCount - 1, detector.lastHopRms, detector.lastFoldedPitchHz,
                        detector.lastPitchLeadHops, detector.lastRateHz, detector.lastVisualUsesAmplitude,
                        detector.tremoloGain, if (detector.reverberating) detector.eventStartHop else -1,
                    )
                }
                val earHop = hop - EAR_BEHIND_HOPS
                if (earHop < 0) continue
                track.read((earHop + 1) * hopMs, hopMs, ear)
                val mediaMs = (earHop + 1) * hopMs + offsetMs
                if (mediaMs < wordStartMs) continue
                val eventMs = if (ear.eventStartHop < 0) VoiceEnergy.NO_EVENT_MS
                    else ((ear.eventStartHop - 1) * hopMs + offsetMs).toLong()
                if (gate.allows(ear.gain, ear.reverberating, eventMs, wordStartMs)) peak = maxOf(peak, ear.gain)
            }
            peak
        }
        assertTrue("the word must light strongly on nearly every phase ($peaks)", peaks.count { it >= 0.5f } >= 18)
        assertTrue("and on every phase at all ($peaks)", peaks.all { it >= 0.3f })
    }

    private companion object {
        /** The frame loop reads the ear a sink buffer (~250 ms) behind the tap. */
        const val EAR_BEHIND_HOPS = 12
    }
}
