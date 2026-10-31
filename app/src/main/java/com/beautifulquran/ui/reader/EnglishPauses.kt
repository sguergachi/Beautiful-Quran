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

    /**
     * At a word the Mushaf marks for a stop (ۖ ۗ ۚ ۘ ۛ, or the saktah ۜ),
     * a reciter's pause can be shallow: Alafasy's after رَبِّنَاۗ in 3:7 sits
     * only 15–19 dB under his voice. Next to a marked word a quiet of
     * [MARKED_DB] for [MARKED_MIN_MS] is a pause: at 3:7's four marked
     * boundaries he holds below −12 dB for 400–850 ms, at unmarked ones never
     * past 90 ms. Within [MARKED_BEFORE_MS] before the next word's timed start
     * to [MARKED_AFTER_MS] after it.
     */
    const val MARKED_DB = -12f
    const val MARKED_MIN_MS = 150L
    private const val MARKED_BEFORE_MS = 600L
    private const val MARKED_AFTER_MS = 800L

    /** Waqf signs a reciter may stop at; ۙ (U+06D9) forbids a stop and is not one. */
    fun marksStop(arabic: String): Boolean = arabic.any { it in "\u06D6\u06D7\u06D8\u06DA\u06DB\u06DC" }

    /** A verse's loudness, median-filtered, in dB relative to its 95th percentile, per [hopMs] hop. */
    class Loudness(val hopMs: Double, private val db: FloatArray) {
        private fun runs(threshold: Float, from: Int, to: Int): List<LongArray> {
            val out = ArrayList<LongArray>()
            var h = from
            while (h < to) {
                if (db[h] < threshold) {
                    val start = h
                    while (h < to && db[h] < threshold) h++
                    val a = (start * hopMs).toLong()
                    val b = (h * hopMs).toLong()
                    val last = out.lastOrNull()
                    if (last != null && a - last[1] <= BRIDGE_MS) last[1] = b else out += longArrayOf(a, b)
                } else h++
            }
            return out
        }

        /** Deep quiet anywhere: a breath the timings call voiced. */
        val pauses: List<AudibleSilence> by lazy {
            runs(QUIET_DB, 0, db.size).filter { it[1] - it[0] >= MIN_MS }.map { AudibleSilence(it[0], it[1]) }
        }

        /** The longest shallow quiet around a marked stop whose next word starts at [boundaryMs]. */
        fun pauseNear(boundaryMs: Long): AudibleSilence? {
            val from = ((boundaryMs - MARKED_BEFORE_MS) / hopMs).toInt().coerceIn(0, db.size)
            val to = ((boundaryMs + MARKED_AFTER_MS) / hopMs).toInt().coerceIn(from, db.size)
            return runs(MARKED_DB, from, to).filter { it[1] - it[0] >= MARKED_MIN_MS }
                .maxByOrNull { it[1] - it[0] }?.let { AudibleSilence(it[0], it[1]) }
        }

        /** Every pause for a verse whose marked stops lead into words timed at [stopBoundaries]. */
        fun pausesWith(stopBoundaries: List<Long>): List<AudibleSilence> {
            val all = (pauses + stopBoundaries.mapNotNull(::pauseNear)).sortedBy { it.startMs }
            val merged = ArrayList<AudibleSilence>()
            for (p in all) {
                val last = merged.lastOrNull()
                if (last != null && p.startMs <= last.endMs) {
                    merged[merged.lastIndex] = AudibleSilence(last.startMs, maxOf(last.endMs, p.endMs))
                } else merged += p
            }
            return merged
        }
    }

    /** [pcm]'s loudness ([hopSamples] per [hopMs] hop), on the media clock. Pure. */
    fun analyze(pcm: FloatArray, hopSamples: Int, hopMs: Double): Loudness {
        val hops = pcm.size / hopSamples.coerceAtLeast(1)
        if (hops < 4) return Loudness(hopMs, FloatArray(0))
        val db = FloatArray(hops) { h ->
            var sum = 0.0
            for (i in h * hopSamples until (h + 1) * hopSamples) sum += pcm[i] * pcm[i]
            (10 * log10(sum / hopSamples + 1e-12)).toFloat()
        }
        val half = maxOf(1, (MEDIAN_MS / hopMs / 2).toInt())
        val window = FloatArray(2 * half + 1)
        val reference = db.sortedArray()[((hops - 1) * 0.95).toInt()]
        val smooth = FloatArray(hops) { h ->
            for (k in window.indices) window[k] = db[(h - half + k).coerceIn(0, hops - 1)]
            window.sort()
            window[half] - reference
        }
        return Loudness(hopMs, smooth)
    }

    /** Deep-quiet pauses in [pcm]. Pure. */
    fun detect(pcm: FloatArray, hopSamples: Int, hopMs: Double): List<AudibleSilence> =
        analyze(pcm, hopSamples, hopMs).pauses

    private val ready = object : LinkedHashMap<String, Loudness>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loudness>?) = size > KEPT
    }
    private val pending = HashSet<String>()

    /** A verse's analysed loudness, if worked out yet. Any thread. */
    fun ready(url: String): Loudness? = synchronized(ready) { ready[url] }

    /** Main thread: work out [url]'s pauses in the background, once. */
    suspend fun request(context: Context, url: String) {
        synchronized(ready) { if (ready.containsKey(url) || !pending.add(url)) return }
        try {
            val audio = TarjiVersePulse.decodedAudio(context, url)
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                analyze(audio.pcm, audio.hopSamples, audio.hopMs)
            }
            synchronized(ready) { ready[url] = found }
            log("${url.substringAfterLast('/')}: ${found.pauses.joinToString { "${it.startMs}-${it.endMs}" }}")
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
            positionMs: Long, voiceStartMs: Long, nextWordMs: Long? = null,
            /** Timed starts of words that follow a marked stop ([marksStop]). */
            stopBoundaries: List<Long> = emptyList()): List<AudibleSilence> {
        val current = pinned.value
        val same = current != null && current.reciterId == reciterId && current.surahId == surahId &&
            current.ayah == ayah && current.generation == generation
        val found = ready(url)?.pausesWith(stopBoundaries)
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
