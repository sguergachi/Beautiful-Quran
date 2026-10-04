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
import org.junit.Assert.assertNull
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
    fun `old profiles keep shipped brightness and new profiles restore the scale`() {
        val old = ReciterTarjiProfileBook.decode("""{"profiles":{"7":{"holdMinMs":600}}}""")
        assertEquals(1f, old.profiles.getValue("7").glintBrightness, 0f)
        val bright = ReciterTarjiProfileBook(mapOf("7" to old.profiles.getValue("7").copy(glintBrightness = 1.6f)))
        assertEquals(bright, ReciterTarjiProfileBook.decode(ReciterTarjiProfileBook.encode(bright)))
    }

    @Test
    fun `export freezes an imported capture and live tuning before the picker opens`() {
        val audio = captureOf(note(1f))
        val live = TarjiLabKnobs(holdMinMs = 720f)
        val ui = TarjiLabViewModel.TarjiLabUiState(
            capture = audio, firstHopMediaMs = 1234.0,
            sampleReciterId = 7, sampleReciterName = "Imported reciter",
            surahId = 1, ayah = 7, wordPosition = 1, wordArabic = "نَعْبُدُ",
            knobs = live, sampleNotes = "Listen to the ending",
            expectation = TarjiLabExpectation(startMs = 200f, endMs = 800f),
            showingReference = false,
            reference = TarjiLabReference(TarjiLabKnobs(), analyzeTarjiCapture(audio, TarjiLabKnobs())),
        )
        val frozen = ui.sampleForExport()!!
        val changed = ui.copy(capture = captureOf(note(0.5f)), knobs = TarjiLabKnobs(), sampleReciterId = 99)
        val restored = TarjiLabCodec.decode(TarjiLabCodec.encode(frozen))
        assertEquals(7, restored.reciterId)
        assertEquals("Imported reciter", restored.reciterName)
        assertEquals(live, restored.knobs)
        assertEquals(ui.expectation, restored.expectation)
        assertEquals("Listen to the ending", restored.notes)
        assertEquals(1234.0, restored.firstHopMediaMs, 0.0)
        assertEquals(audio.hopCount, TarjiLabCodec.toCapture(restored).hopCount)
        assertEquals(99, changed.sampleForExport()!!.reciterId)
    }

    @Test
    fun `export in compare mode matches the graph without changing live settings`() {
        val audio = captureOf(note(1f))
        val checkpoint = TarjiLabKnobs(holdMinMs = 900f)
        val live = TarjiLabKnobs(holdMinMs = 300f)
        val ui = TarjiLabViewModel.TarjiLabUiState(
            capture = audio, sampleReciterId = 7, sampleReciterName = "Reciter",
            knobs = live, showingReference = true,
            reference = TarjiLabReference(checkpoint, analyzeTarjiCapture(audio, checkpoint)),
        )
        val restored = TarjiLabCodec.decode(TarjiLabCodec.encode(ui.sampleForExport()!!))
        assertEquals(checkpoint, restored.knobs)
        assertEquals(live, ui.knobs)
    }

    @Test
    fun `export requires both captured audio and identifiable reciter`() {
        val ui = TarjiLabViewModel.TarjiLabUiState(sampleReciterId = 7, sampleReciterName = "Reciter")
        assertNull(ui.sampleForExport())
        assertNull(ui.copy(capture = captureOf(note(1f)), sampleReciterId = null).sampleForExport())
    }

    @Test
    fun `sample round-trips through JSON`() {
        val capture = captureOf(note(1.0f))
        val knobs = TarjiLabKnobs(maxTremoloHz = 4f, minTremoloDepth = 0.05f, holdMinMs = 450f, glintBrightness = 1.4f)
        val expectation = TarjiLabExpectation(
            kind = TarjiExpectationKind.PULSES,
            startMs = 320f,
            endMs = 860f,
            envelope = listOf(0.1f, 0.6f, 0.4f),
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
        assertEquals(3, decoded.schema)
        assertEquals(listOf(0.1f, 0.6f, 0.4f), decoded.expectation.envelope)
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
    fun `sample carries the detector lead-in and older samples import without one`() {
        val audio = captureOf(note(0.4f))
        val capture = audio.sliceWithLeadIn(8 until audio.hopCount, maxLeadInHops = 5)
        val sample = TarjiLabCodec.buildSample(
            capture = capture, firstHopMediaMs = 160.0, label = "test", reciterId = 7,
            reciterName = "Hani", surahId = 2, ayah = 14, wordPosition = 16, wordArabic = "",
            knobs = TarjiLabKnobs(),
        )
        val restored = TarjiLabCodec.toCapture(TarjiLabCodec.decode(TarjiLabCodec.encode(sample)))
        assertEquals(5, restored.leadInHopCount)
        assertEquals(capture.hopCount, restored.hopCount)
        for (i in capture.leadInPcm.indices) {
            assertEquals(capture.leadInPcm[i], restored.leadInPcm[i], 1.5f / 32767f)
        }

        val legacyFields = Json.parseToJsonElement(TarjiLabCodec.encode(sample)).jsonObject.toMutableMap()
        legacyFields.remove("leadInPcmB64")
        val legacy = TarjiLabCodec.toCapture(TarjiLabCodec.decode(JsonObject(legacyFields).toString()))
        assertEquals(0, legacy.leadInHopCount)
        assertEquals(capture.hopCount, legacy.hopCount)

        val partial = java.util.Base64.getEncoder().encodeToString(ByteArray(321))
        assertTrue(runCatching { TarjiLabCodec.toCapture(sample.copy(leadInPcmB64 = partial)) }.isFailure)
    }

    @Test
    fun `import rejects partial hops and invalid clocks before playback`() {
        val valid = TarjiLabSample(
            label = "test", reciterId = 7, reciterName = "Alafasy", surahId = 1,
            ayah = 1, wordPosition = 1, wordArabic = "", sampleRate = 8000,
            hopSamples = 160, firstHopMediaMs = 0.0,
            pcmB64 = TarjiLabCodec.pcmToBase64(captureOf(note(0.1f))),
            knobs = TarjiLabKnobs(),
        )
        val partial = java.util.Base64.getEncoder().encodeToString(ByteArray(321))
        for (invalid in listOf(
            valid.copy(hopSamples = 0),
            valid.copy(hopContentDurationMs = 0f),
            valid.copy(hopContentDurationMs = Float.NaN),
            valid.copy(pcmB64 = partial),
            valid.copy(pcmB64 = ""),
        )) {
            assertTrue(runCatching { TarjiLabCodec.toCapture(invalid) }.isFailure)
        }
        assertEquals(5, TarjiLabCodec.toCapture(valid).hopCount)
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
