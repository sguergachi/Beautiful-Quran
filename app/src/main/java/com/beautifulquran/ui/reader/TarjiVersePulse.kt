package com.beautifulquran.ui.reader

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Segment
import com.beautifulquran.playback.RecitationCache
import com.beautifulquran.playback.Tarji
import com.beautifulquran.playback.TarjiEarSample
import com.beautifulquran.playback.TarjiEarTrack
import com.beautifulquran.playback.TarjiLabTrim
import com.beautifulquran.playback.TarjiDetectorMode
import com.beautifulquran.playback.TarjiExperimentalDetector
import com.beautifulquran.playback.TarjiRecordingDetector
import com.beautifulquran.playback.TarjiRecordingResult
import com.beautifulquran.playback.TarjiRecordingAlignment
import com.beautifulquran.playback.VoiceEnergy
import com.beautifulquran.playback.analysisHopContentMs
import com.beautifulquran.tarjilab.TarjiLabKnobs
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The light a verse's words will be given, worked out before it is played:
 * the verse's own audio run through the reader's detector, ear track and word
 * gate ([rememberTarjiGate] does the same, live). The Ink Lab draws each
 * candidate's line under it as a sparkline, so where the pulse falls can be
 * seen ahead and compared with the light when it comes.
 */
internal object TarjiVersePulse {

    /** One word's share of the verse on the media-item clock. */
    class Window(val startMs: Double, val endMs: Double)

    /** A verse's audio as the tap would hand it to the detector. */
    class Decoded(val pcm: FloatArray, val hopSamples: Int, val hopMs: Double)

    /** The frame loop reads the ear this far behind the tap (a sink buffer). */
    private const val EAR_BEHIND_HOPS = 12

    /**
     * The pulse across each of [windows], one value per analysis hop (−1..1):
     * the detector's pulse at the ear times its gain — the very quantity the
     * Tarjīʿ Lab draws in green as the reader's output
     * (`tarjiAcceptedPulseWave`), so a word's line and its lab graph are one
     * picture. Windows may overlap. Pure.
     */
    fun lines(
        audio: Decoded,
        knobs: TarjiLabKnobs,
        windows: List<Window>,
        mode: TarjiDetectorMode = TarjiDetectorMode.Current,
        reciterId: Int = 0,
        /** Asked every few hops; false abandons the work (the answer is no longer wanted). */
        wanted: () -> Boolean = { true },
    ): List<FloatArray> = prepare(audio, knobs, windows, mode, reciterId, wanted).lines

    private class Prepared(val lines: List<FloatArray>, val recording: TarjiRecordingResult? = null)

    private fun prepare(audio: Decoded, knobs: TarjiLabKnobs, windows: List<Window>,
                        mode: TarjiDetectorMode, reciterId: Int, wanted: () -> Boolean): Prepared {
        val detector = Tarji()
        detector.hopSamples = audio.hopSamples
        detector.hopContentDurationMs = audio.hopMs
        knobs.applyTo(detector)
        detector.dramaWeights = com.beautifulquran.playback.TarjiDramaWeights.forReciter(reciterId)
        val track = TarjiEarTrack()
        val ear = TarjiEarSample()
        val out = windows.map {
            FloatArray(((it.endMs - it.startMs) / audio.hopMs).toInt().coerceAtLeast(2))
        }
        val hops = audio.pcm.size / audio.hopSamples
        val scratch = FloatArray(audio.hopSamples)
        val experiment = if (mode == TarjiDetectorMode.Current) null else TarjiExperimentalDetector()
        if (mode == TarjiDetectorMode.Recording) {
            val frames = ArrayList<com.beautifulquran.playback.TarjiFrame>(hops)
            val pitch = FloatArray(hops)
            val lead = FloatArray(hops)
            for (hop in 0 until hops) {
                if (hop and 63 == 0 && !wanted()) throw kotlinx.coroutines.CancellationException("retuned")
                audio.pcm.copyInto(scratch, 0, hop * scratch.size, (hop + 1) * scratch.size)
                detector.onSamples8k(scratch)
                frames += detector.measurements.copy()
                pitch[hop] = detector.lastFoldedPitchHz
                lead[hop] = detector.lastPitchLeadHops
            }
            val recording = TarjiRecordingDetector.analyze(frames, detector, wanted)
            // Decision timestamps are hop ends; raw RMS lives at hop centres.
            for (hop in 0 until hops + EAR_BEHIND_HOPS) {
                if (hop and 63 == 0 && !wanted()) throw kotlinx.coroutines.CancellationException("retuned")
                if (hop < hops) track.publish(hop, recording.rms[hop], pitch[hop], lead[hop],
                    recording.rate[hop], recording.amplitude[hop], recording.gain[hop], recording.eventStart[hop])
                val earHop = hop - EAR_BEHIND_HOPS
                if (earHop < 0) continue
                val mediaMs = (earHop + 1) * audio.hopMs
                track.read(mediaMs, audio.hopMs, ear)
                val pulse = (ear.tremolo * ear.gain).coerceIn(-1f, 1f)
                for (index in windows.indices) {
                    val window = windows[index]
                    if (mediaMs >= window.startMs && mediaMs < window.endMs) {
                        val slot = ((mediaMs - window.startMs) / audio.hopMs).toInt().coerceIn(0, out[index].lastIndex)
                        out[index][slot] = pulse
                    }
                }
            }
            return Prepared(out, recording)
        }
        for (hop in 0 until hops + EAR_BEHIND_HOPS) {
            if (hop and 63 == 0 && !wanted()) throw kotlinx.coroutines.CancellationException("retuned")
            if (hop < hops) {
                audio.pcm.copyInto(scratch, 0, hop * scratch.size, (hop + 1) * scratch.size)
                detector.onSamples8k(scratch)
                experiment?.next(detector.measurements, mode, detector)
                val decision = experiment?.decision
                track.publish(
                    hop = detector.hopCount - 1,
                    hopRms = detector.lastHopRms,
                    pitchHz = detector.lastFoldedPitchHz,
                    pitchLeadHops = detector.lastPitchLeadHops,
                    rateHz = decision?.rateHz ?: detector.lastRateHz,
                    usesAmplitude = decision?.usesAmplitude ?: detector.lastVisualUsesAmplitude,
                    gain = decision?.gain ?: detector.tremoloGain,
                    eventStartHop = decision?.eventStartHop ?: if (detector.reverberating) detector.eventStartHop else -1,
                )
            }
            val earHop = hop - EAR_BEHIND_HOPS
            if (earHop < 0) continue
            val mediaMs = (earHop + 1) * audio.hopMs
            var read = false
            var pulse = 0f
            for (index in windows.indices) {
                val window = windows[index]
                if (mediaMs < window.startMs || mediaMs >= window.endMs) continue
                if (!read) {
                    track.read(mediaMs, audio.hopMs, ear)
                    pulse = (ear.tremolo * ear.gain).coerceIn(-1f, 1f)
                    read = true
                }
                val line = out[index]
                line[((mediaMs - window.startMs) / audio.hopMs).toInt().coerceIn(0, line.lastIndex)] = pulse
            }
        }
        return Prepared(out)
    }

    /**
     * Each eligible word's window: the span the Tarjīʿ Lab shows for the word
     * ([TarjiLabTrim.wordSpanMs] with the lab's lead and tail), kept inside
     * the audio.
     */
    fun windows(ayah: Ayah, segments: List<Segment>, audioMs: Double): Map<Int, Window> {
        val last = ayah.words.lastOrNull()?.position
        val out = LinkedHashMap<Int, Window>()
        for (word in ayah.words) {
            if (!InkEngine.tarjiEligible(word.arabic, word.position == last)) continue
            if (segments.none { it.position == word.position }) continue
            val span = TarjiLabTrim.wordSpanMs(
                segments, word.position, TarjiLabTrim.WORD_LEAD_MS, TarjiLabTrim.WORD_TAIL_MS,
            ) ?: continue
            val start = span.first.toDouble().coerceAtLeast(0.0)
            val end = span.last.toDouble().coerceAtMost(audioMs)
            if (end - start >= 40.0) out[word.position] = Window(start, end)
        }
        return out
    }

    // ── On the device ────────────────────────────────────────────────────

    /** For the lab's readout: verses being worked out now, and what the last failure said. */
    var working by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var lastFailure by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    /** The last verse worked out, in a line: enough to tell a flat line from a wrong one. */
    var lastReport by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    enum class Outcome { Done, Failed }

    private data class Key(val reciter: Int, val surah: Int, val ayah: Int, val url: String,
                           val mode: TarjiDetectorMode, val knobs: TarjiLabKnobs, val version: Int = 1)
    private class Result(val key: Key, val lines: Map<Int, FloatArray>, val windows: Map<Int, Window>,
                         val recording: TarjiRecordingResult?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** A device has only so many decoders; a screenful of verses takes turns. */
    private val decoders = kotlinx.coroutines.sync.Semaphore(3)

    // Main thread only. A verse's audio is decoded once and kept: retuning the
    // detector reruns only the detector, which is a fraction of the work.
    private val audio = lru<String, kotlinx.coroutines.Deferred<Decoded>>(AUDIO_KEPT)
    private val results = lru<Key, Result>(RESULTS_KEPT)
    private val recordings = lru<com.beautifulquran.playback.NowPlaying, Result>(AUDIO_KEPT)
    var recordingStatus by androidx.compose.runtime.mutableStateOf("Recording analysis pending")
        private set
    private var sampledTuning: InkEngine.Tuning? = null
    private var sampledKnobs: TarjiLabKnobs? = null
    private var alignedResult: Result? = null
    private var alignedSession = 0L
    private var alignment = TarjiRecordingAlignment()
    private val liveRms = FloatArray(96)
    private val rawEar = TarjiEarSample()
    private var lastAlignmentNanos = 0L
    private val timings = lru<String, Map<Int, List<Segment>>>(4)

    private fun <K, V> lru(limit: Int) = object : LinkedHashMap<K, V>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > limit
    }

    /**
     * Works out [ayah]'s lines and hands them to the words' traces. Main
     * thread; suspends, and is meant to be cancelled when the verse leaves the
     * screen or the detector is retuned — a dial being dragged asks for a new
     * answer every frame, and only the last one is wanted.
     */
    suspend fun compute(context: Context, ayah: Ayah): Outcome {
        // The verse's reciter and timings come from the app's own stores, the
        // same the reader loads from. They once came through whichever reader
        // screen was made last, and a second screen left every line unanswered.
        val name = "${ayah.surahId}:${ayah.number}"
        val quran = context.applicationContext as? com.beautifulquran.QuranApp
            ?: return failed("$name no app")
        val reciterId = quran.settings.settings.value.reciterId
        val reciters = quran.repository.reciters()
        val reciter = reciters.firstOrNull { it.id == reciterId } ?: reciters.firstOrNull()
            ?: return failed("$name no reciter")
        val surahTimings = timings["${reciter.id}|${ayah.surahId}"]
            ?: quran.repository.timings(reciter.id, ayah.surahId).also { timings["${reciter.id}|${ayah.surahId}"] = it }
        val segments = surahTimings[ayah.number].orEmpty()
        if (segments.isEmpty()) return failed("$name has no word timings for ${reciter.name}")
        val url = reciter.audioUrl(ayah.surahId, ayah.number)
        val tuning = InkEngine.tuning
        val knobs = detectorKnobs(tuning)
        val mode = InkEngine.tarjiDetectorMode
        val key = Key(reciter.id, ayah.surahId, ayah.number, url, mode, knobs)
        InkEngine.clearTarjiTraces(ayah.surahId, ayah.number)
        results[key]?.let { apply(ayah, it); return Outcome.Done }
        val app = context.applicationContext
        working++
        try {
            val decoded = audio.getOrPut(url) {
                scope.async { decoders.withPermit { decode(app, url) } }
            }
            val heard = try {
                decoded.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                audio.remove(url) // let a later request fetch it again
                throw failed
            }
            val result = withContext(Dispatchers.Default) {
                val job = coroutineContext[Job]
                val windows = windows(ayah, segments, heard.pcm.size / heard.hopSamples * heard.hopMs)
                val prepared = prepare(heard, knobs, windows.values.toList(), mode, reciter.id) { job?.isActive != false }
                Result(key, windows.keys.zip(prepared.lines).toMap(), windows, prepared.recording)
            }
            if (mode != InkEngine.tarjiDetectorMode || knobs != detectorKnobs(InkEngine.tuning) ||
                reciter.id != quran.settings.settings.value.reciterId) throw kotlinx.coroutines.CancellationException("retuned")
            results[key] = result
            apply(ayah, result)
            val strongest = result.lines.maxByOrNull { (_, line) -> line.maxOfOrNull { kotlin.math.abs(it) } ?: 0f }
            lastReport = "$name ${reciter.name}: " +
                "%.1f s of audio, ".format(heard.pcm.size / heard.hopSamples * heard.hopMs / 1000.0) +
                "${segments.size} timings, ${result.lines.size} lines" +
                (strongest?.let { (position, line) ->
                    ", strongest %.2f on word $position".format(line.maxOfOrNull { kotlin.math.abs(it) } ?: 0f)
                } ?: "")
            lastFailure = null
            return Outcome.Done
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            return failed("$name ${failed.javaClass.simpleName}: ${failed.message}")
        } finally {
            working--
        }
    }

    private fun failed(why: String): Outcome {
        lastFailure = why
        return Outcome.Failed
    }

    /** The detector's own settings: what a verse's lines depend on, and nothing the paint does. */
    fun detectorKnobs(tuning: InkEngine.Tuning): TarjiLabKnobs =
        TarjiLabKnobs.fromTuning(tuning).copy(glintBrightness = 1f)

    private fun apply(ayah: Ayah, result: Result) {
        if (result.recording != null) recordings[com.beautifulquran.playback.NowPlaying(
            ayah.surahId, ayah.number, result.key.reciter,
        )] = result
        for (word in ayah.words) {
            val line = result.lines[word.position] ?: continue
            val window = result.windows.getValue(word.position)
            InkEngine.tarjiTrace(ayah.surahId, ayah.number, word.position).set(line, window.startMs.toFloat(), (window.endMs - window.startMs).toFloat())
        }
    }

    /** Live decisions and measured phase share one audible clock; decoded PCM never supplies live phase. */
    fun sampleAtEar(context: Context, voice: VoiceEnergy, now: Long, out: TarjiEarSample,
                    leadMs: Float = 0f, displayLeadMs: Float = 8f): Boolean {
        val heard = voice.sampleAtEar(now, out, leadMs, displayLeadMs)
        if (InkEngine.tarjiDetectorMode != TarjiDetectorMode.Recording) return heard
        val app = context.applicationContext as? com.beautifulquran.QuranApp
        val playing = app?.player?.state?.value?.nowPlaying
        val result = recordings[playing]
        if (sampledTuning !== InkEngine.tuning) {
            sampledTuning = InkEngine.tuning
            sampledKnobs = detectorKnobs(InkEngine.tuning)
        }
        val recording = result?.recording
        if (!heard || !voice.isPlaying || recording == null || result.key.knobs != sampledKnobs ||
            out.mediaMs == Long.MIN_VALUE) {
            recordingStatus = if (working > 0) "Analyzing recording…" else "Recording analysis pending"
            return false
        }
        if (alignedResult !== result || alignedSession != voice.sessionStartWall) {
            alignedResult = result
            alignedSession = voice.sessionStartWall
            alignment = TarjiRecordingAlignment()
            lastAlignmentNanos = 0L
        }
        if (alignment.offsetMs.isNaN() && now - lastAlignmentNanos >= 200_000_000L) {
            lastAlignmentNanos = now
            val count = voice.copyRecentRms(liveRms)
            alignment.match(liveRms, count, voice.recentRmsStartMediaMs, voice.analysisHopMs,
                recording.rms, recording.hopMs)
            recordingStatus = if (alignment.offsetMs.isFinite()) {
                "Recording aligned " + alignment.offsetMs.toInt() + " ms"
            } else "Aligning recording · needs 1.5 s of distinct audio"
        }
        if (alignment.offsetMs.isNaN()) return false
        val mediaMs = out.mediaMs.toDouble()
        if (!recording.sample(mediaMs + alignment.offsetMs, out)) {
            recordingStatus = "Outside analyzed recording"
            return false
        }
        val position = ((mediaMs + alignment.offsetMs) / recording.hopMs - 1.0)
            .roundToInt().coerceIn(0, recording.amplitude.lastIndex)
        out.eventStartMediaMs = if (out.eventStartHop >= 0)
            (out.eventStartHop * recording.hopMs - alignment.offsetMs).toLong() else Long.MIN_VALUE
        if (out.eventStartMediaMs != Long.MIN_VALUE && out.eventStartMediaMs < voice.detectorSwitchMediaMs) {
            out.gain = 0f
            out.reverberating = false
            out.eventStartMediaMs = Long.MIN_VALUE
            recordingStatus = "Recording aligned · replay the hold after switching"
        }
        if (!voice.sampleRawPulseAtEar(now, rawEar, out.rateHz, recording.amplitude[position], leadMs, displayLeadMs)) {
            out.gain = 0f
            out.reverberating = false
            return false
        }
        out.tremolo = rawEar.tremolo
        out.mediaMs = rawEar.mediaMs
        return true
    }

    private const val AUDIO_KEPT = 24
    private const val RESULTS_KEPT = 160

    /** The verse's audio through the playback cache, decoded and decimated as the tap does. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun decode(context: Context, url: String): Decoded {
        val bytes = java.io.ByteArrayOutputStream(512 * 1024)
        val data = RecitationCache.playbackDataSourceFactory(
            context,
            DefaultHttpDataSource.Factory().setUserAgent("BeautifulQuran/1.0"),
        ).createDataSource()
        try {
            data.open(DataSpec(Uri.parse(url)))
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = data.read(buffer, 0, buffer.size)
                if (read < 0) break
                bytes.write(buffer, 0, read)
            }
        } finally {
            data.close()
        }
        return decodeBytes(bytes.toByteArray()) ?: error("no audio track in ${url.substringAfterLast('/')}")
    }

    private class Bytes(private val bytes: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }

    private fun decodeBytes(bytes: ByteArray): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(Bytes(bytes))
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decimator = Decimator()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                var progressed = false
                // Never wait on one side while the other has work: a wait per
                // frame made a verse take seconds that decodes in a blink.
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                while (true) {
                    val outIndex = decoder.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        continue
                    }
                    if (outIndex < 0) break
                    val pcm = decoder.getOutputBuffer(outIndex)!!
                    pcm.position(info.offset).limit(info.offset + info.size)
                    pcm.order(ByteOrder.LITTLE_ENDIAN)
                    decimator.configure(sampleRate)
                    while (pcm.remaining() >= 2 * channels) {
                        decimator.add(pcm.short / 32768f)
                        pcm.position(pcm.position() + 2 * (channels - 1))
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    progressed = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }
                if (progressed) {
                    idle = 0
                } else {
                    // Both sides busy: the decoder is working. Yield briefly.
                    Thread.sleep(1)
                    if (++idle > 5_000) break // a decoder that never ends: keep what it gave
                }
            }
            return decimator.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** The tap's own decimation ([VoiceEnergy.onPcm16]): first channel, block mean. */
    internal class Decimator {
        private var step = 0
        private var sourceRate = 0
        private var sum = 0f
        private var n = 0
        private var out = FloatArray(1 shl 16)
        private var size = 0

        fun configure(sampleRate: Int) {
            if (sourceRate != 0) return
            sourceRate = sampleRate
            step = maxOf(1, sampleRate / Tarji.SAMPLE_RATE)
        }

        fun add(sample: Float) {
            sum += sample
            if (++n < step) return
            if (size == out.size) out = out.copyOf(size * 2)
            out[size++] = sum / step
            sum = 0f
            n = 0
        }

        fun finish(): Decoded? {
            if (sourceRate == 0 || size == 0) return null
            val hopSamples = ((sourceRate / step) * (Tarji.HOP_MS / 1000f)).roundToInt().coerceAtLeast(1)
            return Decoded(out.copyOf(size), hopSamples, analysisHopContentMs(sourceRate, step, hopSamples))
        }
    }
}

/**
 * Ink Lab: while candidates are marked, has this verse's pulse lines worked
 * out ahead of the voice, and again when the detector is retuned. Every
 * reader that builds ink motions for a verse calls it.
 */
@androidx.compose.runtime.Composable
internal fun RequestTarjiPulseLines(ayah: Ayah, active: Boolean = false) {
    val mode = InkEngine.tarjiDetectorMode
    if (!InkEngine.tarjiMarkCandidates && !(active && mode == TarjiDetectorMode.Recording)) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val tuning = InkEngine.tuning
    // Only what the detector reads: a paint dial must not redo the verse.
    val knobs = TarjiVersePulse.detectorKnobs(tuning)
    val reciterId = (context.applicationContext as? com.beautifulquran.QuranApp)
        ?.settings?.settings?.collectAsState()?.value?.reciterId
    androidx.compose.runtime.LaunchedEffect(ayah, knobs, reciterId, mode) {
        InkEngine.clearTarjiTraces(ayah.surahId, ayah.number)
        // A dial being dragged changes the key every frame; wait for it to
        // rest before doing anything, and let the restart cancel the rest.
        kotlinx.coroutines.delay(150)
        var failures = 0
        while (true) {
            when (TarjiVersePulse.compute(context, ayah)) {
                TarjiVersePulse.Outcome.Done -> return@LaunchedEffect
                TarjiVersePulse.Outcome.Failed -> if (++failures >= 3) return@LaunchedEffect
            }
            kotlinx.coroutines.delay(750)
        }
    }
}
