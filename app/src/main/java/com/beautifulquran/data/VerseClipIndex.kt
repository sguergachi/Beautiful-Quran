package com.beautifulquran.data

import android.database.sqlite.SQLiteDatabase
import com.beautifulquran.data.model.VERSE_CLIP_HOST

/**
 * One verse of a reciter who exists only as whole-surah recordings: a byte
 * range of that file, starting on an MP3 frame header.
 *
 * The first [leadBytes] are whole frames kept only so the bit reservoir the
 * frames after them read from is present; `VerseClipDataSource` silences them.
 * The verse itself is preceded by one more frame, decoded for real so the
 * verse's first frame decodes as it does in the whole recording. Decoded,
 * [trimSamples] samples come before the verse; the player drops exactly those
 * (gapless trim), so back to back verses continue the recording sample for sample.
 * [sourceBytes] pins the size of the recording the range was measured on.
 */
data class VerseClip(
    val sourceUrl: String,
    val sourceBytes: Long,
    val startByte: Long,
    val endByte: Long,
    val leadBytes: Int,
    val trimSamples: Int,
) {
    val length: Long get() = endByte - startByte
}

/** A per-verse URL on [VERSE_CLIP_HOST]: `https://verse-clips.invalid/data/<slug>/<SSS><AAA>.mp3`. */
internal data class VerseClipRef(val slug: String, val surah: Int, val ayah: Int)

private val VERSE_CLIP_URL = Regex("""^https://${Regex.escape(VERSE_CLIP_HOST)}/data/([^/]+)/(\d{3})(\d{3})\.mp3$""")

internal fun parseVerseClipUrl(url: String): VerseClipRef? {
    val match = VERSE_CLIP_URL.matchEntire(url) ?: return null
    return VerseClipRef(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt())
}

/**
 * Lazily loaded `audio_clips` table. Loading touches SQLite, so the first
 * [lookup] must come from a loader thread, never the main thread.
 */
class VerseClipIndex(private val load: () -> Map<String, VerseClip>) {
    private val clips: Map<String, VerseClip> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }

    internal fun lookup(ref: VerseClipRef): VerseClip? = clips[key(ref.slug, ref.surah, ref.ayah)]

    companion object {
        internal fun key(slug: String, surah: Int, ayah: Int) = "$slug/$surah:$ayah"
    }
}

internal fun readVerseClips(db: SQLiteDatabase): Map<String, VerseClip> {
    val out = HashMap<String, VerseClip>(8192)
    db.rawQuery(
        """
        SELECT r.slug, c.surah_id, c.ayah_number, c.source_url, c.source_bytes,
               c.start_byte, c.end_byte, c.lead_bytes, c.trim_samples
        FROM audio_clips c JOIN reciters r ON r.id = c.reciter_id
        """.trimIndent(),
        null,
    ).use { cursor ->
        while (cursor.moveToNext()) {
            out[VerseClipIndex.key(cursor.getString(0), cursor.getInt(1), cursor.getInt(2))] =
                VerseClip(
                    sourceUrl = cursor.getString(3),
                    sourceBytes = cursor.getLong(4),
                    startByte = cursor.getLong(5),
                    endByte = cursor.getLong(6),
                    leadBytes = cursor.getInt(7),
                    trimSamples = cursor.getInt(8),
                )
        }
    }
    return out
}

/** Bumped whenever the bytes `VerseClipStream` serves for the same range change. */
internal const val VERSE_CLIP_FORMAT = 2

/** One range-sourced reciter's slicing, reduced to what decides whether a cached verse is still the right audio. */
data class VerseClipStamp(val slug: String, val reciterId: Int, val fingerprint: String)

/**
 * A fingerprint per range-sourced reciter over every verse's byte range, lead,
 * trim and source (URL and size). The playback cache keys a verse by its URL alone, so audio
 * cut by an earlier build (other seams, other ranges) would keep playing after
 * the ranges changed. It must be dropped whenever this changes.
 */
internal fun readVerseClipStamps(db: SQLiteDatabase): List<VerseClipStamp> {
    val out = ArrayList<VerseClipStamp>()
    db.rawQuery(
        """
        SELECT r.slug, r.id, c.surah_id, c.ayah_number, c.source_bytes, c.start_byte, c.end_byte, c.lead_bytes,
               c.trim_samples, c.source_url
        FROM audio_clips c JOIN reciters r ON r.id = c.reciter_id
        ORDER BY r.id, c.surah_id, c.ayah_number
        """.trimIndent(),
        null,
    ).use { cursor ->
        var slug: String? = null
        var id = 0
        var digest = java.security.MessageDigest.getInstance("SHA-256")
        fun finish() {
            slug?.let { out += VerseClipStamp(it, id, digest.digest().joinToString("") { b -> "%02x".format(b) }) }
        }
        while (cursor.moveToNext()) {
            if (cursor.getString(0) != slug) {
                finish()
                slug = cursor.getString(0)
                id = cursor.getInt(1)
                digest = java.security.MessageDigest.getInstance("SHA-256")
            }
            // Prefixed with how the stream is served (gapless header), so a change there drops old copies too.
            val row = "$VERSE_CLIP_FORMAT:" + (2..8).joinToString(":") { cursor.getLong(it).toString() } +
                ":" + cursor.getString(9)
            digest.update("$row\n".toByteArray())
        }
        finish()
    }
    return out
}

/** The cache keys of [slug]'s verses: every per-verse URL under its folder. */
internal fun verseClipKeys(keys: Collection<String>, slug: String): List<String> {
    val prefix = "https://$VERSE_CLIP_HOST/data/$slug/"
    return keys.filter { it.startsWith(prefix) }
}
