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
 * One verse as a standalone byte stream: the logic of `VerseClipDataSource`,
 * free of Android so it can be tested on the JVM.
 *
 * Positions and lengths are in the verse's own coordinates (0 is its first
 * frame). A request that starts inside the reservoir-only lead is fetched from
 * byte 0, so the lead's frames can be walked and silenced whole; it is then
 * served from memory before the stream continues.
 */
internal class VerseClipStream(
    private val source: RangeSource,
    private val clip: VerseClip,
) {
    private var opened = false
    private var remaining = 0L
    private var lead: ByteArray? = null
    private var leadPos = 0

    /**
     * Opens [length] bytes (or the rest, if -1) from [position]; returns how many
     * will be served. Throws if the position is outside the verse or the
     * recording is not the one the range was measured on.
     */
    fun open(position: Long, length: Long): Long {
        if (position < 0 || position > clip.length) {
            throw IOException("Position $position is outside the ${clip.length}-byte verse")
        }
        val available = clip.length - position
        remaining = if (length < 0) available else minOf(length, available)
        lead = null
        leadPos = 0
        if (remaining == 0L) return 0L

        val inLead = position < clip.leadBytes
        val from = if (inLead) 0L else position
        val to = maxOf(position + remaining, if (inLead) clip.leadBytes.toLong() else 0L)
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
                leadPos = position.toInt()
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
