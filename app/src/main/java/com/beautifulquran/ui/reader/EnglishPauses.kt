package com.beautifulquran.ui.reader

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.beautifulquran.domain.AudibleSilence
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Where the reciter actually stops, read from the verse's own audio.
 *
 * Word timings rarely mark a pause: 96% of timed word boundaries leave under
 * 20 ms between words, so a breath or a waqf sits inside the word before it,
 * and English ink paced from the timings keeps moving straight through it.
 * The Arabic shows the stop because tajweed pacing holds it; the English needs
 * to know where it is. A pause here is a stretch at least [MIN_MS] long whose
 * median-filtered loudness sits [QUIET_DB] under the verse's 95th percentile.
 * Against Hani ar-Rifai's measured quiet (assets/silence/7.txt) it finds 26
 * of 27 pauses on a 25-verse sample, starting a little earlier than the table
 * (the voice's decaying tail) — which is where the English should land.
 */
internal object EnglishPauses {
    const val QUIET_DB = -20f
    const val MIN_MS = 180L
    private const val MEDIAN_MS = 70.0
    private const val BRIDGE_MS = 80L
    private const val KEPT = 48

    /** Pauses in decoded PCM ([hopSamples] per [hopMs] hop), on the media clock. Pure. */
    fun detect(pcm: FloatArray, hopSamples: Int, hopMs: Double): List<AudibleSilence> {
        val hops = pcm.size / hopSamples.coerceAtLeast(1)
        if (hops < 4) return emptyList()
        val db = FloatArray(hops) { h ->
            var sum = 0.0
            for (i in h * hopSamples until (h + 1) * hopSamples) sum += pcm[i] * pcm[i]
            (10 * log10(sum / hopSamples + 1e-12)).toFloat()
        }
        val half = maxOf(1, (MEDIAN_MS / hopMs / 2).toInt())
        val window = FloatArray(2 * half + 1)
        val smooth = FloatArray(hops) { h ->
            for (k in window.indices) window[k] = db[(h - half + k).coerceIn(0, hops - 1)]
            window.sort()
            window[half]
        }
        val reference = db.sortedArray()[((hops - 1) * 0.95).toInt()]
        val runs = ArrayList<LongArray>()
        var h = 0
        while (h < hops) {
            if (smooth[h] < reference + QUIET_DB) {
                val start = h
                while (h < hops && smooth[h] < reference + QUIET_DB) h++
                val from = (start * hopMs).toLong()
                val to = (h * hopMs).toLong()
                val last = runs.lastOrNull()
                if (last != null && from - last[1] <= BRIDGE_MS) last[1] = to else runs += longArrayOf(from, to)
            } else h++
        }
        return runs.filter { it[1] - it[0] >= MIN_MS }.map { AudibleSilence(it[0], it[1]) }
    }

    private val ready = object : LinkedHashMap<String, List<AudibleSilence>>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AudibleSilence>>?) = size > KEPT
    }
    private val pending = HashSet<String>()

    /** Detected pauses for a verse's audio, if worked out yet. Any thread. */
    fun ready(url: String): List<AudibleSilence>? = synchronized(ready) { ready[url] }

    /** Main thread: work out [url]'s pauses in the background, once. */
    suspend fun request(context: Context, url: String) {
        synchronized(ready) { if (ready.containsKey(url) || !pending.add(url)) return }
        try {
            val audio = TarjiVersePulse.decodedAudio(context, url)
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                detect(audio.pcm, audio.hopSamples, audio.hopMs)
            }
            synchronized(ready) { ready[url] = found }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // No pauses known: the English keeps its timing-only clock.
        } finally {
            synchronized(ready) { pending.remove(url) }
        }
    }

    /**
     * The pauses a performance of one verse uses, fixed once its voice has
     * begun: a detection landing mid-verse would rebuild the clock and jump the
     * ink. Written by the reader's poll, read by the English renderers.
     */
    data class Pinned(val reciterId: Int, val surahId: Int, val ayah: Int, val generation: Long,
                      val pauses: List<AudibleSilence>, val locked: Boolean)

    val pinned = mutableStateOf<Pinned?>(null)

    /** Pauses for this poll of a verse: whatever is known until its voice begins, then fixed. */
    fun pin(reciterId: Int, surahId: Int, ayah: Int, generation: Long, url: String,
            positionMs: Long, voiceStartMs: Long): List<AudibleSilence> {
        val current = pinned.value
        val same = current != null && current.reciterId == reciterId && current.surahId == surahId &&
            current.ayah == ayah && current.generation == generation
        if (same && current!!.locked) return current.pauses
        val next = Pinned(reciterId, surahId, ayah, generation, ready(url).orEmpty(), positionMs >= voiceStartMs)
        if (next != current) pinned.value = next
        return next.pauses
    }

    /** The pinned pauses if they belong to this verse. */
    fun pinnedFor(surahId: Int, ayah: Int): List<AudibleSilence> =
        pinned.value?.takeIf { it.surahId == surahId && it.ayah == ayah }?.pauses.orEmpty()
}
