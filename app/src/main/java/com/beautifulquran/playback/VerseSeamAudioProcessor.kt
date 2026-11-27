package com.beautifulquran.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.beautifulquran.data.parseVerseClipUrl
import java.nio.ByteBuffer

/**
 * Puts [VerseSeamFade] on the playback PCM. Float output sits the processor
 * out; the verse files are 16-bit.
 *
 * The next verse is configured while this file's last few milliseconds are
 * still held. [onConfigure] must not build a new fade: that runs before
 * [onQueueEndOfStream], and the tail would be thrown away instead of ramped.
 * The fade for the new file is built in [onFlush], after the ended file has
 * been drained.
 *
 * A gapless next verse arrives as configure (next file), end of stream
 * (this file), flush. Only an end of stream that follows a configure keeps
 * the ended file's tail, and the next flush hands it to the new fade to
 * bridge the new file's opening digital silence (see [VerseSeamFade]). The
 * end of playback drains with no configure first, so a seek after it, like
 * any other seek or the first file, starts without one.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VerseSeamAudioProcessor(
    /**
     * The encoder delay ([verseClipEncoderDelay]) of the file at this URI, in
     * frames, or 0. Media3 trims it only when a file starts; a seek inside the
     * file restarts the decoder (which drops its own delay again) but not that
     * trim, so the recording would then run that far behind the clock and the
     * words. [onFlush] trims it itself after such a seek.
     */
    private val seekTrimFrames: (uri: String) -> Int = { 0 },
) : BaseAudioProcessor() {

    /**
     * True while the next gapless seam joins a verse to the one after it in
     * the same surah recording ([sampleExactSeam]). Such seams are sample-exact
     * (the player trims each verse's decoded lead), so the recording simply
     * continues across them: no fade, which would carve a dip into connected
     * recitation, and no bridge, which would replay the end of the verse
     * before, reversed, as if a word were said twice. Every other seam (the
     * basmalah into a chapter, a repeat, a pause between verses) keeps both.
     */
    @Volatile
    var sampleExactSeams: Boolean = false

    private var seam: VerseSeamFade? = null

    /** A next file was configured since the last flush: an end of stream now is a gapless seam. */
    private var nextFileConfigured = false

    /** The ended file's tail, waiting for the next file's flush. */
    private var bridge: SeamBridge? = null

    /** Media3 still owes this file its start trim: no input since the file started. */
    private var startTrimPending = false

    /** Bytes still to drop after a seek inside a file ([seekTrimFrames]). */
    private var seekDropBytes = 0

    private val period = androidx.media3.common.Timeline.Period()
    private val window = androidx.media3.common.Timeline.Window()

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        nextFileConfigured = true
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fade = seam ?: return
        if (inputBuffer.hasRemaining()) startTrimPending = false
        if (seekDropBytes > 0) {
            val dropped = minOf(seekDropBytes, inputBuffer.remaining())
            inputBuffer.position(inputBuffer.position() + dropped)
            seekDropBytes -= dropped
        }
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val pcm = ByteArray(remaining)
        inputBuffer.get(pcm)
        val ready = fade.push(pcm)
        if (ready.isEmpty()) return
        replaceOutputBuffer(ready.size).put(ready).flip()
    }

    override fun onQueueEndOfStream() {
        val exact = nextFileConfigured && sampleExactSeams
        bridge = if (nextFileConfigured && !exact) seam?.bridgeSource() else null
        val tail = seam?.endOfStream(fade = !exact) ?: return
        if (tail.isEmpty()) return
        replaceOutputBuffer(tail.size).put(tail).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        val pcm16 = isActive && format.encoding == C.ENCODING_PCM_16BIT
        // A flush with no file configured since is a seek inside the file playing.
        val seekInside = !nextFileConfigured && !startTrimPending
        seekDropBytes = if (seekInside && pcm16) {
            itemUri(streamMetadata)?.let(seekTrimFrames)?.let { it * format.bytesPerFrame } ?: 0
        } else {
            0
        }
        if (nextFileConfigured) startTrimPending = true
        seam = if (pcm16) {
            VerseSeamFade(
                format.channelCount,
                format.sampleRate,
                bridge = bridge,
            )
        } else {
            null
        }
        bridge = null
        nextFileConfigured = false
    }

    override fun onReset() {
        seam = null
        bridge = null
        nextFileConfigured = false
        startTrimPending = false
        seekDropBytes = 0
    }

    private fun itemUri(metadata: AudioProcessor.StreamMetadata): String? {
        val uid = metadata.periodUid ?: return null
        val timeline = metadata.timeline
        val index = timeline.getIndexOfPeriod(uid).takeIf { it != C.INDEX_UNSET } ?: return null
        val windowIndex = timeline.getPeriod(index, period).windowIndex
        return timeline.getWindow(windowIndex, window).mediaItem.localConfiguration?.uri?.toString()
    }
}

/**
 * Whether the seam after [current] continues the recording sample-exact: both
 * are verse clips ([com.beautifulquran.data.parseVerseClipUrl]) of one
 * reciter's surah, [next] the verse right after, and playback runs straight
 * on. A repeat of one verse or a pause between verses ends on a cut, and the
 * basmalah or a wrap joins another place or recording: those need the fade.
 */
internal fun sampleExactSeam(current: String?, next: String?, repeatOne: Boolean, pausesAtEnd: Boolean): Boolean {
    if (repeatOne || pausesAtEnd) return false
    val ended = current?.let(::parseVerseClipUrl) ?: return false
    val following = next?.let(::parseVerseClipUrl) ?: return false
    return ended.slug == following.slug && ended.surah == following.surah && following.ayah == ended.ayah + 1
}
