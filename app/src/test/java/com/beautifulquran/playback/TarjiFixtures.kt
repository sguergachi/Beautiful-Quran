package com.beautifulquran.playback

import com.beautifulquran.tarjilab.TarjiLabKnobs

/**
 * Hani Ar-Rifai 2:14 from 12 270 ms to the end of the ayah, exactly as the
 * tap decimates the 44.1 kHz stream (8820 Hz, 176-sample hops). The stream
 * starts where a reported Pixel session did; its final word, مُسْتَهْزِءُونَ
 * (17 330 ms on), showed a pulse in the Tarjīʿ Lab and none in the reader.
 */
internal object Hani214 {
    const val START_MS = 12_270.0
    const val HOP_SAMPLES = 176
    const val HOP_MS = HOP_SAMPLES * 1000.0 / 8820.0
    const val FINAL_WORD_START_MS = 17_330L

    /** The lab settings in use when the reader stayed still. */
    val knobs = TarjiLabKnobs(
        maxTremoloHz = 6f,
        minTremoloHz = 3.1f,
        holdMinMs = 598f,
        minTremoloDepth = 0.1372f,
        minPeriodicity = 0.416f,
        maxPitchDrift = 0.3f,
        attackMs = 50f,
        releaseMs = 1095.3691f,
        glintBrightness = 2f,
    )

    fun pcm(): FloatArray {
        val wav = javaClass.getResourceAsStream("/tarji/hani_2_14_from_12270_8820.wav")
            ?.use { it.readBytes() }
            ?: throw AssertionError("missing test audio resource")
        // Canonical 44-byte header: PCM16 mono at the decimated rate.
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8)
        check(rate == 8820 && wav[22].toInt() == 1 && wav[34].toInt() == 16)
        return FloatArray((wav.size - 44) / 2) {
            val i = 44 + 2 * it
            ((wav[i].toInt() and 0xFF) or (wav[i + 1].toInt() shl 8)).toShort() / 32768f
        }
    }

    /** Media-clock position at the end of [hop], [droppedSamples] into the stream. */
    fun mediaMs(hop: Int, droppedSamples: Int = 0): Long =
        (START_MS + droppedSamples * 1000.0 / 8820.0 + (hop + 1) * HOP_MS).toLong()
}
