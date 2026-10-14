package com.beautifulquran.ui.reader

import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Segment
import com.beautifulquran.data.model.Word
import com.beautifulquran.playback.Hani214
import com.beautifulquran.playback.NowPlaying
import com.beautifulquran.playback.TarjiDetectorMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class TarjiVersePulseTimingTest {
    private val reciter = Reciter(7, "Hani_Rifai_192kbps", "Hani", "Murattal", true)
    private val peer = Reciter(2, "Husary_64kbps", "Husary", "Murattal", true)
    private val ayah = Ayah(2, 14, "", "", words = listOf(Word(1, "مُسۡتَهۡزِءُونَ", "", "")))
    private val audio = TarjiVersePulse.Decoded(Hani214.pcm(), Hani214.HOP_SAMPLES, Hani214.HOP_MS)
    private val generations = mutableMapOf<Int, MutableStateFlow<Long>>()
    private var rows = mapOf(14 to listOf(Segment(1, 500, 2_200)))
    private val reads = mutableMapOf<Int, Int>()
    private val decodes = mutableMapOf<Int, Int>()
    private var originalMode = TarjiDetectorMode.Current

    @Before
    fun prepare() {
        originalMode = InkEngine.tarjiDetectorMode
        InkEngine.tarjiDetectorMode = TarjiDetectorMode.Recording
        val generation = sequence.addAndGet(10)
        listOf(reciter, peer).forEach { generations[it.id] = MutableStateFlow(generation) }
        TarjiVersePulse.timingsChanged(generations.mapValues { it.value.value })
    }

    @After
    fun restore() {
        TarjiVersePulse.timingsChanged(generations.mapValues { it.value.value + 1 })
        InkEngine.tarjiDetectorMode = originalMode
    }

    private fun playing(voice: Reciter = reciter) = NowPlaying(ayah.surahId, ayah.number, voice.id)
    private fun trace() = InkEngine.tarjiTrace(ayah.surahId, ayah.number, 1)
    private suspend fun compute(voice: Reciter = reciter, decode: suspend () -> TarjiVersePulse.Decoded = { audio }) =
        TarjiVersePulse.compute(ayah, voice, { generations.getValue(voice.id).value }, {
            reads[voice.id] = (reads[voice.id] ?: 0) + 1
            rows
        }, {
            decodes[voice.id] = (decodes[voice.id] ?: 0) + 1
            decode()
        })

    @Test
    fun `replacement rebuilds word windows and recordings once then reuses the accepted result`() = runTest {
        assertEquals(TarjiVersePulse.Outcome.Done, compute())
        val firstLine = trace().line
        val firstRecording = TarjiVersePulse.recordingFor(playing(), generations.getValue(reciter.id).value)
        assertNotNull(firstLine)
        assertNotNull(firstRecording)
        assertEquals(200f, trace().startMs, 0f)
        compute()
        assertSame(firstLine, trace().line)
        assertEquals(1, reads[reciter.id])
        assertEquals(1, decodes[reciter.id])

        rows = mapOf(14 to listOf(Segment(1, 850, 2_800)))
        generations.getValue(reciter.id).value++
        TarjiVersePulse.timingsChanged(generations.mapValues { it.value.value })
        assertNull(trace().line)
        assertNull(TarjiVersePulse.recordingFor(playing(), generations.getValue(reciter.id).value - 1))
        assertEquals(TarjiVersePulse.Outcome.Done, compute())
        assertEquals(550f, trace().startMs, 0f)
        assertNotSame(firstLine, trace().line)
        assertNotSame(firstRecording, TarjiVersePulse.recordingFor(playing(), generations.getValue(reciter.id).value))
        compute()
        assertEquals(2, reads[reciter.id])
        assertEquals(2, decodes[reciter.id])
    }

    @Test
    fun `peer withdrawal leaves the displayed voice alone and global revocation clears both recordings`() = runTest {
        compute()
        compute(peer)
        val peerLine = trace().line
        val peerRecording = TarjiVersePulse.recordingFor(playing(peer), generations.getValue(peer.id).value)
        assertNotNull(peerLine)
        assertNotNull(peerRecording)
        generations.getValue(reciter.id).value++
        TarjiVersePulse.timingsChanged(mapOf(reciter.id to generations.getValue(reciter.id).value))
        assertSame(peerLine, trace().line)
        assertSame(peerRecording, TarjiVersePulse.recordingFor(playing(peer), generations.getValue(peer.id).value))
        assertNull(TarjiVersePulse.recordingFor(playing(), generations.getValue(reciter.id).value - 1))
        compute(peer)
        assertEquals(1, reads[peer.id])
        assertEquals(1, decodes[peer.id])

        generations.values.forEach { it.value++ }
        TarjiVersePulse.timingsChanged(generations.mapValues { it.value.value })
        assertNull(trace().line)
        assertNull(TarjiVersePulse.recordingFor(playing(peer), generations.getValue(peer.id).value - 1))
        rows = emptyMap()
        assertEquals(TarjiVersePulse.Outcome.Failed, compute())
        assertEquals(TarjiVersePulse.Outcome.Failed, compute(peer))
        assertNull(trace().line)
        assertEquals(1, decodes[reciter.id])
        assertEquals(1, decodes[peer.id])
    }

    @Test
    fun `an unavailable current row clears visible traces before returning failure`() = runTest {
        compute()
        assertNotNull(trace().line)
        rows = emptyMap()
        generations.getValue(reciter.id).value++
        // The request must clear the old graph even before the app's purge collector runs.
        assertEquals(TarjiVersePulse.Outcome.Failed, compute())
        assertNull(trace().line)
        assertNull(TarjiVersePulse.recordingFor(playing(), generations.getValue(reciter.id).value - 1))
        assertEquals(1, decodes[reciter.id])
    }

    @Test
    fun `a pending decode cannot restore traces or recording decisions after withdrawal`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            compute {
                started.complete(Unit)
                release.await()
                audio
            }
        }
        started.await()
        rows = emptyMap()
        generations.getValue(reciter.id).value++
        TarjiVersePulse.timingsChanged(generations.mapValues { it.value.value })
        release.complete(Unit)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(trace().line)
        assertNull(TarjiVersePulse.recordingFor(playing(), generations.getValue(reciter.id).value - 1))
        assertEquals(0, TarjiVersePulse.working)
        assertEquals(TarjiVersePulse.Outcome.Failed, compute())
        assertEquals(1, decodes[reciter.id])
    }

    @Test
    fun `a timing read overtaken by revocation cannot cache or analyze the old chapter`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        var decoded = false
        val pending = async {
            TarjiVersePulse.compute(ayah, reciter, { generations.getValue(reciter.id).value }, {
                reads++
                val captured = rows
                if (reads == 1) {
                    started.complete(Unit)
                    release.await()
                }
                captured
            }, { decoded = true; audio })
        }
        started.await()
        rows = emptyMap()
        generations.values.forEach { it.value++ }
        TarjiVersePulse.timingsChanged(generations.mapValues { it.value.value })
        release.complete(Unit)
        assertEquals(TarjiVersePulse.Outcome.Failed, pending.await())
        assertEquals(2, reads)
        assertTrue(!decoded)
        assertNull(trace().line)
        assertNull(TarjiVersePulse.recordingFor(playing(), generations.getValue(reciter.id).value - 1))
    }

    private companion object {
        val sequence = AtomicLong(1_000)
    }
}
