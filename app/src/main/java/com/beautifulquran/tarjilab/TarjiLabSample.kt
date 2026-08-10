package com.beautifulquran.tarjilab

import com.beautifulquran.playback.TarjiLabCapture
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Tarjīʿ Lab sample: one word's captured PCM plus the detector knobs it
 * was analyzed under and where in the text it lives — the exchange format
 * for reproducing a reciter's reverberation off-device. Export it from the
 * lab, drop it in `tools/tarji_samples/`, and the waveform + tarjīʿ sine
 * can be reproduced anywhere (and used to derive a better detector).
 *
 * [pcmB64] is the decimated mono stream as 16-bit little-endian PCM
 * (Base64). [firstHopMediaMs] is the media-clock position of the first
 * captured hop, so the word span is recoverable against the ayah's marks.
 */
@Serializable
data class TarjiLabSample(
    val schema: Int = 1,
    val label: String,
    val reciterId: Int,
    val reciterName: String,
    val surahId: Int,
    val ayah: Int,
    val wordPosition: Int,
    val wordArabic: String,
    val sampleRate: Int,
    val hopSamples: Int,
    /** True content duration of one hop (ms) — the decimated stream is not
     * always exactly [Tarji.HOP_MS] (44.1 kHz ÷ 6 = 7.35 kHz, 20 ms). */
    val hopContentDurationMs: Float = 20f,
    val firstHopMediaMs: Double,
    val pcmB64: String,
    val knobs: TarjiLabKnobs,
    val notes: String = "",
)

object TarjiLabCodec {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun buildSample(
        capture: TarjiLabCapture,
        firstHopMediaMs: Double,
        label: String,
        reciterId: Int,
        reciterName: String,
        surahId: Int,
        ayah: Int,
        wordPosition: Int,
        wordArabic: String,
        knobs: TarjiLabKnobs,
        notes: String = "",
    ): TarjiLabSample = TarjiLabSample(
        label = label,
        reciterId = reciterId,
        reciterName = reciterName,
        surahId = surahId,
        ayah = ayah,
        wordPosition = wordPosition,
        wordArabic = wordArabic,
        sampleRate = capture.sampleRate,
        hopSamples = capture.hopSamples,
        hopContentDurationMs = capture.hopContentDurationMs(),
        firstHopMediaMs = firstHopMediaMs,
        pcmB64 = pcmToBase64(capture),
        knobs = knobs,
        notes = notes,
    )

    fun encode(sample: TarjiLabSample): String =
        json.encodeToString(TarjiLabSample.serializer(), sample)

    fun decode(text: String): TarjiLabSample =
        json.decodeFromString(TarjiLabSample.serializer(), text)

    fun toCapture(sample: TarjiLabSample): TarjiLabCapture {
        val bytes = Base64.getDecoder().decode(sample.pcmB64)
        val n = bytes.size / 2
        val hop = sample.hopSamples
        val floats = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            floats[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        val hopDur = sample.hopContentDurationMs
        val content = FloatArray(n / hop) { it * hopDur }
        return TarjiLabCapture(sample.sampleRate, hop, content, floats)
    }

    /** Frame rate the loop preview must play the PCM at so hop content time
     * stays true (7350 Hz for a 44.1 kHz source, 8000 for 48 kHz). */
    fun playbackSampleRate(sample: TarjiLabSample): Int =
        if (sample.hopContentDurationMs > 0f) {
            (sample.hopSamples * 1000f / sample.hopContentDurationMs).roundToInt()
        } else {
            sample.sampleRate
        }

    /** Frame rate for a live capture (its hop timestamps are real). */
    fun playbackSampleRate(capture: TarjiLabCapture): Int =
        if (capture.hopContentDurationMs() > 0f) {
            (capture.hopSamples * 1000f / capture.hopContentDurationMs()).roundToInt()
        } else {
            capture.sampleRate
        }

    fun pcmToBase64(capture: TarjiLabCapture): String {
        val n = capture.pcm.size
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (capture.pcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            bytes[2 * i] = (s.toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Stable file name for exporting a sample to the lab's download slot. */
    fun fileName(sample: TarjiLabSample): String =
        "tarji_${sample.reciterId}_${sample.surahId}_${sample.ayah}_w${sample.wordPosition}.json"

    /** The exported sample's human title. */
    fun label(reciterName: String, surahId: Int, ayah: Int, word: Int): String =
        "$reciterName $surahId:$ayah w$word"
}
