package com.beautifulquran.playback

import com.beautifulquran.tarjilab.HANI_TUNING
import com.beautifulquran.tarjilab.TarjiLabKnobs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Captured before adding the experimental feature seam, from ee4e1d88. */
class TarjiBaselineTest {
    @Test
    fun `default decisions and phase remain exact across recordings and hop alignments`() {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        fun run(pcm: FloatArray, hop: Int, drop: Int, knobs: TarjiLabKnobs? = null) {
            val detector = Tarji().also { it.hopSamples = hop; knobs?.applyTo(it) }
            val scratch = FloatArray(hop)
            var offset = drop
            while (offset + hop <= pcm.size) {
                pcm.copyInto(scratch, 0, offset, offset + hop)
                detector.onSamples8k(scratch)
                out.clear()
                out.putFloat(detector.tremoloGain).putInt(detector.eventStartHop)
                    .putFloat(detector.lastRateHz).put(if (detector.lastVisualUsesAmplitude) 1 else 0)
                    .putFloat(detector.tremolo)
                digest.update(out.array())
                offset += hop
            }
        }
        fun wav(name: String): FloatArray {
            val bytes = AudioSystem.getAudioInputStream(javaClass.getResource("/tarji/$name")!!)
                .use { it.readAllBytes() }
            val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 2) { pcm.short / 32768f }
        }
        run(wav("alfasy_1_7_8k.wav"), 160, 0)
        run(wav("hani_1_7_8k.wav"), 160, 0, HANI_TUNING)
        val hani = Hani214.pcm()
        run(hani, 176, 0, HANI_TUNING)
        for (phase in 0 until 40) run(hani, 176, phase * 4, Hani214.knobs)
        assertEquals("3cb902f58467e67a04c322d4dad101d5449595ddc48ec0bf5b3433655870b1af",
            digest.digest().joinToString("") { "%02x".format(it) })
    }
}
