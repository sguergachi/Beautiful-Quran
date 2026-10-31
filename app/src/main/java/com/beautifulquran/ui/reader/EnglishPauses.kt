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
    private const val TAG = "EnglishPauses"

    /** Logcat breadcrumbs (`adb logcat -s EnglishPauses`); absent on the JVM. */
    private fun log(message: String) {
        runCatching { android.util.Log.d(TAG, message) }
    }

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
            log("${url.substringAfterLast('/')}: ${found.joinToString { "${it.startMs}-${it.endMs}" }}")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            // No pauses known: the English keeps its timing-only clock.
            log("${url.substringAfterLast('/')}: ${failed.javaClass.simpleName} ${failed.message}")
        } finally {
            synchronized(ready) { pending.remove(url) }
        }
    }

    /**
     * The pauses a performance of one verse uses, fixed once its voice has
     * begun: a detection landing mid-verse would rebuild the clock and jump the
     * ink. Written by the reader's poll, read by the English renderers.
     */
    /**
     * [pauses] are fixed once the verse's voice begins: the Mushaf leaf's clock
     * is one audible-time run, so any later change would move its edge.
     * [ahead] adds pauses detected late that start after the word now being
     * said: Scroll's clock times each word on its own, so they reshape only
     * words still to come. (Starting play on a long verse must not lose all of
     * its pauses to a second of decoding.)
     */
    data class Pinned(val reciterId: Int, val surahId: Int, val ayah: Int, val generation: Long,
                      val pauses: List<AudibleSilence>, val locked: Boolean,
                      val ahead: List<AudibleSilence> = pauses)

    val pinned = mutableStateOf<Pinned?>(null)

    /**
     * Pauses for this poll of a verse. [nextWordMs] is where the next timed word
     * starts after [positionMs] (null past the last).
     */
    fun pin(reciterId: Int, surahId: Int, ayah: Int, generation: Long, url: String,
            positionMs: Long, voiceStartMs: Long, nextWordMs: Long? = null): List<AudibleSilence> {
        val current = pinned.value
        val same = current != null && current.reciterId == reciterId && current.surahId == surahId &&
            current.ayah == ayah && current.generation == generation
        val found = ready(url)
        val next = if (same && current.locked) {
            val from = maxOf(nextWordMs ?: Long.MAX_VALUE, positionMs + LATE_MARGIN_MS)
            val late = found.orEmpty().filter { it.startMs >= from && it !in current.ahead }
            if (late.isEmpty()) return current.pauses
            current.copy(ahead = (current.ahead + late).sortedBy { it.startMs })
        } else Pinned(reciterId, surahId, ayah, generation, found.orEmpty(), positionMs >= voiceStartMs)
        if (next != current) {
            pinned.value = next
            log("pin $surahId:$ayah gen=$generation locked=${next.locked} " +
                "pauses=${next.pauses.size} ahead=${next.ahead.size} at=$positionMs voice=$voiceStartMs")
        }
        return next.pauses
    }

    /** The pauses fixed at this verse's first voice (Mushaf leaf). */
    fun pinnedFor(surahId: Int, ayah: Int): List<AudibleSilence> =
        pinned.value?.takeIf { it.surahId == surahId && it.ayah == ayah }?.pauses.orEmpty()

    /** Those plus any found late for words still to come (Scroll). */
    fun aheadFor(surahId: Int, ayah: Int): List<AudibleSilence> =
        pinned.value?.takeIf { it.surahId == surahId && it.ayah == ayah }?.ahead.orEmpty()

    /** Keeps the smoothing around the word being said untouched by a late rebuild. */
    private const val LATE_MARGIN_MS = 400L
}
