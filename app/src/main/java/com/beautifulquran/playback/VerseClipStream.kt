package com.beautifulquran.playback

import com.beautifulquran.data.VerseClip
import java.io.EOFException
import java.io.IOException

/** The surah recording's byte ranges, as `VerseClipStream` needs them. Positions are absolute in the file. */
internal interface RangeSource {
    /** Starts streaming [length] bytes of the recording from [position]. */
    fun open(position: Long, length: Long)

    /** Up to [length] bytes into [buffer], or -1 at the end of the range. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** The recording's total size as the server reported it for the open range, if it did. */
    fun totalBytes(): Long?

    fun close()
}

/**
 * Samples the platform MP3 decoder (`c2.android.mp3.decoder`, PacketVideo)
 * withholds: it drops this many from the start of every stream and appends as
 * many zeros at its end. Measured on the emulator's decoder by
 * `VerseClipSeamCaptureTest` (each verse came back shifted by exactly 529).
 */
internal const val PLATFORM_MP3_DECODER_DELAY = 529

/**
 * An ID3v2.3 tag holding only an `iTunSMPB` comment: Media3's MP3 extractor reads
 * it as the stream's encoder delay and padding, and the audio sink trims that
 * many samples off the decoded start and end, keeping its clock on the trimmed
 * audio. [delay] and [padding] are in samples of the decoder's output.
 */
internal fun gaplessId3Tag(delay: Int, padding: Int): ByteArray {
    require(delay in 0..0xFFFFFF && padding in 0..0xFFFFFF)
    val text = " 00000000 %08X %08X 0000000000000000".format(delay, padding)
    val body = byteArrayOf(0) + "eng".toByteArray(Charsets.ISO_8859_1) +
        "iTunSMPB".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
        text.toByteArray(Charsets.ISO_8859_1)
    val frame = "COMM".toByteArray(Charsets.ISO_8859_1) + bigEndian(body.size) + byteArrayOf(0, 0) + body
    val size = frame.size
    val header = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
        (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(),
        (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
    )
    return header + frame
}

private fun bigEndian(value: Int) = byteArrayOf(
    (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
)

/**
 * The tag that makes the player drop everything decoded before [clip]'s verse
 * (silenced reservoir lead and the warm-up frame) and the decoder's padding
 * after it, so verses join sample-exact instead of across ~26 ms of silence.
 */
internal fun gaplessHeaderFor(clip: VerseClip): ByteArray =
    gaplessId3Tag(delay = verseClipEncoderDelay(clip), padding = PLATFORM_MP3_DECODER_DELAY)

/**
 * Decoded samples the player trims from the front of [clip] at its start: its
 * frames before the verse, less what the platform decoder already drops.
 */
internal fun verseClipEncoderDelay(clip: VerseClip): Int =
    (clip.trimSamples - PLATFORM_MP3_DECODER_DELAY).coerceAtLeast(0)

/**
 * One verse as a standalone byte stream: the logic of `VerseClipDataSource`,
 * free of Android so it can be tested on the JVM.
 *
 * The stream is [gaplessHeaderFor] the clip, then the clip's bytes. Positions
 * and lengths are in the stream's own coordinates (0 is the tag's first byte;
 * [length] covers both). A request that starts inside the reservoir-only lead
 * is fetched from the lead's first byte, so the lead's frames can be walked and
 * silenced whole; it is then served from memory before the stream continues.
 */
internal class VerseClipStream(
    private val source: RangeSource,
    private val clip: VerseClip,
) {
    private val header = gaplessHeaderFor(clip)

    /** Bytes served for the verse: the tag, then the range. */
    val length: Long get() = header.size + clip.length

    private var opened = false
    private var remaining = 0L
    private var headerPos = 0
    private var lead: ByteArray? = null
    private var leadPos = 0

    /**
     * Opens [length] bytes (or the rest, if -1) from [position]; returns how many
     * will be served. Throws if the position is outside the verse or the
     * recording is not the one the range was measured on.
     */
    fun open(position: Long, length: Long): Long {
        if (position < 0 || position > this.length) {
            throw IOException("Position $position is outside the ${this.length}-byte verse")
        }
        val available = this.length - position
        remaining = if (length < 0) available else minOf(length, available)
        lead = null
        leadPos = 0
        headerPos = minOf(position, header.size.toLong()).toInt()
        if (remaining == 0L) return 0L

        val clipPosition = (position - header.size).coerceAtLeast(0L)
        val clipRemaining = remaining - (header.size - headerPos)
        if (clipRemaining <= 0L) return remaining
        val inLead = clipPosition < clip.leadBytes
        val from = if (inLead) 0L else clipPosition
        val to = maxOf(clipPosition + clipRemaining, if (inLead) clip.leadBytes.toLong() else 0L)
        source.open(clip.startByte + from, to - from)
        opened = true
        try {
            val total = source.totalBytes()
            if (total != clip.sourceBytes) {
                throw IOException(
                    "Surah recording is ${total ?: "of unknown size"} bytes, expected ${clip.sourceBytes}",
                )
            }
            if (inLead) {
                val bytes = ByteArray(clip.leadBytes)
                var done = 0
                while (done < bytes.size) {
                    val read = source.read(bytes, done, bytes.size - done)
                    if (read < 0) throw EOFException("Verse ended inside its lead")
                    done += read
                }
                Mp3LeadSilencer.silence(bytes, clip.leadBytes)
                lead = bytes
                leadPos = clipPosition.toInt()
            }
        } catch (e: Exception) {
            close()
            throw e
        }
        return remaining
    }

    /** Up to [length] bytes, or -1 once the opened range is complete. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val wanted = minOf(length.toLong(), remaining).toInt()
        if (headerPos < header.size) {
            val count = minOf(wanted, header.size - headerPos)
            System.arraycopy(header, headerPos, buffer, offset, count)
            headerPos += count
            remaining -= count
            return count
        }
        val head = lead
        if (head != null && leadPos < head.size) {
            val count = minOf(wanted, head.size - leadPos)
            System.arraycopy(head, leadPos, buffer, offset, count)
            leadPos += count
            remaining -= count
            return count
        }
        val read = source.read(buffer, offset, wanted)
        if (read < 0) throw EOFException("Recording ended ${remaining} bytes before the verse did")
        remaining -= read
        return read
    }

    fun close() {
        try {
            if (opened) source.close()
        } finally {
            opened = false
            lead = null
        }
    }
}

/** The total in `Content-Range: bytes 100-199/1000`, or null if absent, malformed or `*`. */
internal fun parseContentRangeTotal(header: String?): Long? =
    header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
