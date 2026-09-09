package com.beautifulquran.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.beautifulquran.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/** A page-turn recording split into the three phases of a physical flip. */
data class PageTurnFlip(
    val name: String,
    val liftRes: Int,
    val sweepRes: Int,
    val dropRes: Int,
)

/**
 * Drives paper page-turn audio from the live position of the paper stack.
 *
 * A flip is cut into three stems — lift (the sheet peeling up), sweep (the arc
 * through the air) and drop (landing on the stack). Instead of firing a fixed
 * one-shot, [onPosition] is fed the stack's fractional position every frame and
 * releases each stem as the swipe crosses that phase. The sound therefore spans
 * however long the swipe actually takes, and a gesture abandoned before the
 * slap never plays the drop — you can start a turn and set it back down.
 *
 * Stems are cut from CC0 "Book Flip Sounds" by Voltiment555 (opengameart.org),
 * flips 2/8/9. The original tone is kept; a downward expander dries the decay
 * tail so each stem reads close and dry (a closet, not a room) instead of
 * hissing. Loudness lives in the samples (6 dB below the peak-normalized
 * stems) — SoundPool's play() gain is not a reliable cut on device.
 */
class PageTurnSounds(context: Context) {

    // Media usage keeps the flip on the listener's media volume; SoundPool never
    // takes audio focus, so a page turn never ducks or pauses the recitation.
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    // SoundPool.load() hands back an opaque sample id per file; play() and the
    // loaded-check both need that id, not the R.raw.* resource id, so keep a map
    // from resource id to sample id and track which samples have finished loading.
    private val sampleIds = mutableMapOf<Int, Int>()
    private val loadedSamples = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSamples += sampleId
        }
        FLIPS.flatMap { listOf(it.liftRes, it.sweepRes, it.dropRes) }
            .forEach { res -> sampleIds[res] = soundPool.load(context, res, 1) }
    }

    // --- Scrub state for the turn currently under way -----------------------
    private var activeFrom: Int? = null
    private var activeFlip: PageTurnFlip? = null
    private var phase = 0 // 0 none, 1 lift, 2 sweep, 3 drop
    private var maxProgress = 0f
    private var lastFlipIndex = -1
    private var lastPos = Float.NaN
    private var lastTimeNs = 0L
    private var turnRate = 1f

    /**
     * Feed the paper stack's current fractional position (-1 = bookmarks,
     * 0 = cover, 1 = reader, 2 = settings). Safe to call every frame from both
     * drags and animations.
     */
    fun onPosition(pos: Float) {
        val now = System.nanoTime()
        if (lastPos.isNaN()) {
            lastPos = pos
            lastTimeNs = now
            return
        }
        val dtSec = ((now - lastTimeNs).coerceAtLeast(1_000_000)) / 1_000_000_000f
        val velocity = (pos - lastPos) / dtSec // layers per second
        lastPos = pos
        lastTimeNs = now

        val from = activeFrom
        if (from == null) {
            // Settled until the sheet is pulled clear of an integer layer.
            val nearest = pos.roundToInt()
            if (abs(pos - nearest) > START_EPS) {
                beginTurn(origin = if (velocity >= 0f) floor(pos).toInt() else floor(pos).toInt() + 1)
                turnRate = rateFor(velocity)
            }
            return
        }

        if (abs(pos - from) > 1f + START_EPS) {
            // Ran straight into the next layer; close this turn and open another.
            finishTurn()
            beginTurn(origin = if (velocity >= 0f) floor(pos).toInt() else floor(pos).toInt() + 1)
            return
        }
        val progress = abs(pos - from).coerceIn(0f, 1f)
        if (progress > maxProgress) {
            maxProgress = progress
            turnRate = rateFor(velocity)
        }

        if (maxProgress >= SWEEP_AT) advanceTo(2, activeFlip?.sweepRes)
        if (maxProgress >= DROP_AT) advanceTo(3, activeFlip?.dropRes)

        // Landed on a new layer, or set back down on the one we started from.
        val nearest = pos.roundToInt()
        if (abs(pos - nearest) < SETTLE_EPS) {
            if (nearest != from) finishTurn() else abandonTurn()
        }
    }

    private fun beginTurn(origin: Int) {
        var index = Random.nextInt(FLIPS.size)
        if (index == lastFlipIndex) index = (index + 1) % FLIPS.size
        lastFlipIndex = index
        activeFlip = FLIPS[index]
        activeFrom = origin
        phase = 0
        maxProgress = 0f
        advanceTo(1, activeFlip?.liftRes) // the lift starts the instant the sheet moves
    }

    /** Ensure the drop has sounded, then settle on the new layer. */
    private fun finishTurn() {
        advanceTo(3, activeFlip?.dropRes)
        clearTurn()
    }

    /** Reset back onto the origin layer without ever landing the page. */
    private fun abandonTurn() = clearTurn()

    private fun clearTurn() {
        activeFrom = null
        activeFlip = null
        phase = 0
        maxProgress = 0f
    }

    private fun advanceTo(target: Int, res: Int?) {
        if (phase >= target || res == null) return
        phase = target
        playStem(res, turnRate)
    }

    // A gentle rate spread gives fast swipes a touch more urgency without
    // pitching the stem up into the thin, harsh register that erases its body.
    private fun rateFor(velocity: Float): Float =
        (0.95f + abs(velocity) * 0.05f).coerceIn(0.92f, 1.12f)

    private fun playStem(res: Int, rate: Float) {
        val sampleId = sampleIds[res] ?: return
        if (sampleId !in loadedSamples) return // async load; earliest turns stay quiet
        soundPool.play(sampleId, VOLUME, VOLUME, 1, 0, rate)
    }

    /**
     * One whole flip paced for the entrance cover's slow hinge open (~1.1 s):
     * lift as the board leaves the page block, sweep through the arc, drop as
     * it settles flat. Fired once by the entrance, not scrubbed like the stack.
     */
    fun playCoverOpen() {
        val flip = FLIPS[Random.nextInt(FLIPS.size)]
        playStem(flip.liftRes, COVER_OPEN_RATE)
        handler.postDelayed({ playStem(flip.sweepRes, COVER_OPEN_RATE) }, 260)
        handler.postDelayed({ playStem(flip.dropRes, COVER_OPEN_RATE) }, 820)
    }

    /** Play a whole flip at natural pace — for auditioning in developer settings. */
    fun auditionFlip(index: Int) {
        val flip = FLIPS.getOrNull(index) ?: return
        playStem(flip.liftRes, 1f)
        handler.postDelayed({ playStem(flip.sweepRes, 1f) }, 110)
        handler.postDelayed({ playStem(flip.dropRes, 1f) }, 300)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        soundPool.release()
    }

    companion object {
        // Second, device-dependent cut. The samples already carry the 6 dB drop.
        private const val VOLUME = 0.045f
        // A touch under natural pitch so the cover reads heavier than a page.
        private const val COVER_OPEN_RATE = 0.92f
        private const val START_EPS = 0.03f
        private const val SETTLE_EPS = 0.03f
        private const val SWEEP_AT = 0.42f
        private const val DROP_AT = 0.88f

        val FLIPS = listOf(
            PageTurnFlip("Flip 2 (crisp)", R.raw.flip2_lift, R.raw.flip2_sweep, R.raw.flip2_drop),
            PageTurnFlip("Flip 8 (busy)", R.raw.flip8_lift, R.raw.flip8_sweep, R.raw.flip8_drop),
            PageTurnFlip("Flip 9 (soft sweep)", R.raw.flip9_lift, R.raw.flip9_sweep, R.raw.flip9_drop),
        )
    }
}
