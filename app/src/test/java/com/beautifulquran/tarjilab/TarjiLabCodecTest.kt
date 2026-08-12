package com.beautifulquran.tarjilab

import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiLabCapture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec for the Tarjīʿ Lab sample exchange format (export → file → import). */
class TarjiLabCodecTest {

    private fun captureOf(pcm: FloatArray, hopSamples: Int = Tarji.HOP_SAMPLES): TarjiLabCapture {
        val n = pcm.size / hopSamples
        return TarjiLabCapture(
            sampleRate = Tarji.SAMPLE_RATE,
            hopSamples = hopSamples,
            hopContentMs = FloatArray(n) { it * 20f },
            pcm = FloatArray(n * hopSamples) { pcm[it] },
        )
    }

    private fun note(seconds: Float): FloatArray {
        val n = (seconds * 8000f).toInt()
        return FloatArray(n) {
            val t = it / 8000f
            0.3f * sin(2f * PI.toFloat() * 130f * t)
        }
    }

    @Test
    fun `sample round-trips through JSON`() {
        val capture = captureOf(note(1.0f))
        val knobs = TarjiLabKnobs(maxTremoloHz = 4f, minTremoloDepth = 0.05f, holdMinMs = 450f)
        val expectation = TarjiLabExpectation(
            kind = TarjiExpectationKind.PULSES,
            startMs = 320f,
            endMs = 860f,
            crestMs = listOf(400f, 600f, 800f),
            phaseAnchorMs = 600f,
            style = TarjiTargetStyle(depth = 0.8f, troughFloor = 0.15f, buildMs = 700f),
        )
        val sample = TarjiLabCodec.buildSample(
            capture = capture,
            firstHopMediaMs = 12345.6,
            label = "Mishary Rashid Alafasy 1:7 w1",
            reciterId = 7,
            reciterName = "Mishary Rashid Alafasy",
            surahId = 1,
            ayah = 7,
            wordPosition = 1,
            wordArabic = "نَعْبُدُ",
            knobs = knobs,
            expectation = expectation,
            notes = "slow swell at the waqf",
        )
        val json = TarjiLabCodec.encode(sample)
        assertTrue(json.contains("نَعْبُدُ"))
        assertTrue(json.contains("slow swell at the waqf"))
        assertTrue(json.contains("PULSES"))

        val decoded = TarjiLabCodec.decode(json)
        assertEquals(sample, decoded)
        assertNotNull(TarjiLabCodec.toCapture(decoded))
        val restored = TarjiLabCodec.toCapture(decoded)!!
        assertEquals(capture.hopCount, restored.hopCount)
        assertEquals(capture.hopSamples, restored.hopSamples)
        for (i in capture.pcm.indices) {
            assertTrue(
                "pcm $i within 16-bit quantization",
                abs(capture.pcm[i] - restored.pcm[i]) <= 1.5f / 32767f,
            )
        }
        assertEquals(20f, restored.hopContentDurationMs(), 1e-4f)

        val legacyFields = Json.parseToJsonElement(json).jsonObject.toMutableMap()
        legacyFields["schema"] = JsonPrimitive(1)
        legacyFields.remove("expectation")
        val legacy = TarjiLabCodec.decode(JsonObject(legacyFields).toString())
        assertEquals(TarjiExpectationKind.UNLABELED, legacy.expectation.kind)
    }

    @Test
    fun `playback rate preserves the true hop duration`() {
        // 44.1 kHz source decimates to 7.35 kHz: 147 samples ≈ 20 ms.
        val capture147 = TarjiLabCapture(
            sampleRate = Tarji.SAMPLE_RATE,
            hopSamples = 147,
            hopContentMs = floatArrayOf(0f, 20f, 40f),
            pcm = FloatArray(3 * 147),
        )
        assertEquals(7350, TarjiLabCodec.playbackSampleRate(capture147))
        val capture160 = captureOf(note(0.1f))
        assertEquals(8000, TarjiLabCodec.playbackSampleRate(capture160))
    }

    @Test
    fun `sample file name is stable and human readable`() {
        val sample = TarjiLabCodec.buildSample(
            capture = captureOf(note(0.1f)),
            firstHopMediaMs = 0.0,
            label = "x",
            reciterId = 7,
            reciterName = "x",
            surahId = 1,
            ayah = 7,
            wordPosition = 3,
            wordArabic = "x",
            knobs = TarjiLabKnobs(),
        )
        assertEquals("tarji_7_1_7_w3.json", TarjiLabCodec.fileName(sample))
    }
}
