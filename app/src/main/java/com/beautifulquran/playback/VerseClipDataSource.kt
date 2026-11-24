package com.beautifulquran.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.beautifulquran.data.VerseClipIndex
import com.beautifulquran.data.parseVerseClipUrl
import java.io.IOException

/**
 * Serves one verse of a whole-surah recording as if it were its own MP3 file.
 *
 * A reciter who is not on EveryAyah exists only as one MP3 per surah. His verses
 * are addressed like everyone else's (`https://verse-clips.invalid/data/<slug>/
 * <SSS><AAA>.mp3`), so the cache, prefetcher and download manager key on a
 * per-verse URL and need no other idea. This sits below all of them, at the
 * network edge: it swaps the URL for the surah file's and the verse's byte range
 * for the caller's, and nothing else crosses the wire. The range logic itself
 * (lead silencing, the guard that the file is the one the ranges were measured
 * on) is [VerseClipStream].
 *
 * Anything that is not a verse-clip URL passes straight through.
 */
@UnstableApi
internal class VerseClipDataSource(
    private val upstream: DataSource,
    private val index: VerseClipIndex,
) : DataSource {
    private var uri: Uri? = null
    private var stream: VerseClipStream? = null
    private var passthrough = false
    private var passthroughOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val ref = parseVerseClipUrl(dataSpec.uri.toString())
        if (ref == null) {
            passthrough = true
            val length = upstream.open(dataSpec)
            passthroughOpen = true
            return length
        }
        passthrough = false
        val clip = index.lookup(ref) ?: throw IOException("No audio range for ${dataSpec.uri}")
        val source = object : RangeSource {
            var open = false
            override fun open(position: Long, length: Long) {
                upstream.open(
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(clip.sourceUrl))
                        .setPosition(position)
                        .setLength(length)
                        .build(),
                )
                open = true
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                upstream.read(buffer, offset, length).let { if (it == C.RESULT_END_OF_INPUT) -1 else it }

            override fun totalBytes(): Long? = parseContentRangeTotal(
                upstream.responseHeaders.entries
                    .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                    ?.value?.firstOrNull(),
            )

            override fun close() {
                if (open) upstream.close()
                open = false
            }
        }
        val verse = VerseClipStream(source, clip)
        stream = verse
        val position = dataSpec.position
        if (position < 0 || position > clip.length) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        return verse.open(position, if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1 else dataSpec.length)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        val read = stream?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        return if (read < 0) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (passthrough && passthroughOpen) upstream.responseHeaders else emptyMap()

    override fun close() {
        try {
            if (passthrough) {
                if (passthroughOpen) upstream.close()
            } else {
                stream?.close()
            }
        } finally {
            passthroughOpen = false
            stream = null
            uri = null
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val index: VerseClipIndex,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = VerseClipDataSource(upstream.createDataSource(), index)
    }
}
