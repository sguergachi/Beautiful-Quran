package com.beautifulquran.ui.reader

/** A word-play intent, provisional until its own playback generation acknowledges it. */
internal data class WordTapInk(
    val seed: ActiveWord? = null,
    val pending: Boolean = seed != null,
) {
    /** Stale polls cannot release a tap; later words retain its prefix within this performance. */
    fun onPoll(polled: ActiveWord?): WordTapInk {
        val target = seed ?: return this
        if (polled == null) return this
        val sameVerse = polled.ayah == target.ayah && polled.reciterId == target.reciterId
        if (pending) {
            return if (sameVerse && polled.wordPosition == target.wordPosition &&
                polled.playbackActivation >= target.playbackActivation
            ) {
                copy(seed = target.copy(playbackActivation = polled.playbackActivation), pending = false)
            } else this
        }
        return if (sameVerse && polled.playbackActivation == target.playbackActivation) this
            else WordTapInk()
    }

    /** Pin only the tapped word's visual activation; the actual media generation stays authoritative. */
    fun resolve(polled: ActiveWord?): ActiveWord? {
        val current = onPoll(polled)
        val target = current.seed ?: return polled
        if (current.pending) return target
        // A temporarily missing timing anchor cannot erase the requested prefix.
        if (polled == null) return target
        return polled.copy(
            activation = if (polled.wordPosition == target.wordPosition) target.activation else polled.activation,
            tapStartPosition = target.wordPosition,
        )
    }
}

/** A deliberate word start keeps its continuous prefix while the stream waits. */
internal fun streamWaitActiveWord(word: ActiveWord?, streamWait: Boolean): ActiveWord? =
    word?.takeIf { !streamWait || it.tapStartPosition != null }

/** A download cannot spend the grace period reserved for confirming a word seek. */
internal class WordTapConfirmationTimeout(private val readyMs: Long) {
    private var readyAt: Long? = null

    fun expired(buffering: Boolean, nowMs: Long): Boolean {
        if (buffering) {
            readyAt = null
            return false
        }
        val start = readyAt ?: nowMs.also { readyAt = it }
        return nowMs - start >= readyMs
    }
}

/**
 * A word tap's own wait, from the tap until the sought word is heard.
 *
 * The stream-wait cover (the whole page under Upcoming paper while the
 * download spins) is for a wait the reader did not choose: play pressed on an
 * uncached item, or a stall mid-recitation. A tapped word is a place the
 * reader asked for, so while its own seek is still loading the page keeps the
 * ordinary recitation look — the tapped word lit, its prefix kept — rather
 * than going dark. The wait ends the first time playback carries the voice
 * after the seek has landed: [activation] reached, or a buffering spell seen
 * (a cold chapter start lands without a seek). Stalls after that are ordinary
 * stream waits again.
 */
internal class WordTapVoiceWait(private val activation: Long) {
    private var landed = false

    /** True once the tapped performance is audibly playing. */
    fun heard(inkId: Long, isPlaying: Boolean, isBuffering: Boolean): Boolean {
        if (inkId >= activation || isBuffering) landed = true
        return landed && isPlaying && !isBuffering
    }
}

/** Whether buffering puts the page under the stream-wait cover. */
internal fun streamWaitEngages(isBuffering: Boolean, wordTapAwaitingVoice: Boolean): Boolean =
    isBuffering && !wordTapAwaitingVoice
