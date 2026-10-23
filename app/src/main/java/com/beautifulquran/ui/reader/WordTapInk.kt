package com.beautifulquran.ui.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A word-play intent, provisional until its own playback generation acknowledges it. */
internal data class WordTapInk(
    val seed: ActiveWord? = null,
    val pending: Boolean = seed != null,
    val positionIntentId: Long? = null,
) {
    /** Stale polls cannot release a tap; later words retain its prefix within this request. */
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
        if (!sameVerse) return WordTapInk()
        if (polled.playbackActivation == target.playbackActivation) return this
        // MediaController can report both its immediate seek and the session's
        // acknowledgement. They share one requested place, but each advances
        // the media generation. Only the same landing may rebase this origin.
        return if (positionIntentId != null &&
            polled.playbackActivation > target.playbackActivation &&
            polled.wordPosition == target.wordPosition && polled.startMs == target.startMs
        ) copy(seed = target.copy(playbackActivation = polled.playbackActivation))
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

/**
 * Publishes a word tap synchronously, before its player command can enable page dimming.
 * Polls and taps share this publication so an asynchronous combine cannot expose a stale
 * word between the user's intent and the seek. Calls belong to the ViewModel's main thread.
 */
internal class WordInkPublication {
    private var tap = WordTapInk()
    private var polled: ActiveWord? = null
    private val shown = MutableStateFlow<ActiveWord?>(null)
    val activeWord: StateFlow<ActiveWord?> = shown.asStateFlow()

    fun seed(word: ActiveWord, positionIntentId: Long? = null) {
        tap = WordTapInk(word, positionIntentId = positionIntentId)
        shown.value = tap.resolve(polled)
    }

    fun onPoll(word: ActiveWord?) {
        polled = word
        tap = tap.onPoll(word)
        shown.value = tap.resolve(word)
    }

    /** A new command cancels the exception; repeated callbacks from its own seek do not. */
    fun onPositionIntent(id: Long) {
        val origin = tap.positionIntentId ?: return
        if (id <= origin) return
        tap = WordTapInk()
        shown.value = polled
    }

    fun clear() {
        tap = WordTapInk()
        polled = null
        shown.value = null
    }

    fun awaiting(word: ActiveWord): Boolean = tap.pending && tap.seed === word

    fun expire(word: ActiveWord) {
        if (!awaiting(word)) return
        tap = WordTapInk()
        shown.value = polled
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
