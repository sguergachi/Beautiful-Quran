package com.beautifulquran.data

import android.content.Context
import com.beautifulquran.domain.EnglishBook
import com.beautifulquran.domain.EnglishVerseRun
import com.beautifulquran.domain.englishBookOf
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The English book's leaves, kept on disk.
 *
 * Paginating by measurement costs a text layout a leaf — 4.8 seconds for the
 * Qur'an on a device, after the cost of it was cut by two thirds. That is a
 * price worth paying once and not worth paying twice, and it never needs paying
 * twice: the pagination is a pure function of the leaf's size, the hand, the
 * text and two settings. Given the same answers it breaks in the same places
 * every time.
 *
 * So it is written down. This is what every ebook reader does — a pagination
 * cached against the layout it was computed for, thrown away and redone when
 * the layout moves — and it is why they open instantly and repaginate visibly
 * when you change the type size.
 *
 * [key] is everything the pagination depends on. Anything not in it that can
 * change the leaves is a bug that shows as a book breaking in the wrong places,
 * so it carries a format version too: bump [FORMAT] when the leaves' meaning
 * changes, and the next launch measures instead of reading.
 */
class EnglishBookCache(context: Context) {

    private val dir = File(context.cacheDir, "english-book")

    /**
     * Everything the leaves depend on. The database's own file name is in here
     * because the translation is in it, and a leaf is a length of translation.
     */
    fun key(
        wellPx: Float,
        measurePx: Float,
        verseNumberScript: Int,
        hideParentheticals: Boolean,
        leafText: Int,
        database: String,
    ): String = listOf(
        FORMAT,
        wellPx.toRawBits(),
        measurePx.toRawBits(),
        verseNumberScript,
        if (hideParentheticals) 1 else 0,
        leafText,
        database,
    ).joinToString("-")

    /** The book written down under [key], or null when there is none to read. */
    fun read(
        key: String,
        pageOf: (surahId: Int, ayah: Int) -> Int,
        text: (surahId: Int, ayah: Int) -> String,
    ): EnglishBook? = runCatching {
        val file = File(dir, key)
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val leafCount = input.readInt()
            // An empty book is never worth keeping: it was measured from an
            // empty catalog, and reading it back installs a valid-looking
            // measured book with no leaves — blank pager, held cover, and no
            // remeasure ever, until app data is wiped. Treat as a miss and
            // remove the poison so the book rebuilds from live data.
            if (leafCount <= 0) {
                file.delete()
                return null
            }
            check(leafCount <= MAX_LEAVES) { "English book cache has too many leaves" }
            val leaves = ArrayList<List<EnglishVerseRun>>(leafCount)
            var totalRuns = 0
            repeat(leafCount) {
                val runCount = input.readInt()
                check(runCount in 1..MAX_RUNS && totalRuns + runCount <= MAX_RUNS) {
                    "English book cache has an invalid run count"
                }
                totalRuns += runCount
                val runs = ArrayList<EnglishVerseRun>(runCount)
                repeat(runCount) {
                    val run = EnglishVerseRun(input.readInt(), input.readInt(), input.readInt(), input.readInt())
                    check(run.surahId in 1..114 && run.ayah > 0 && run.from >= 0 && run.to > run.from) {
                        "English book cache has an invalid verse run"
                    }
                    check(run.to <= text(run.surahId, run.ayah).length && pageOf(run.surahId, run.ayah) in 1..604) {
                        "English book cache names text outside the Quran"
                    }
                    runs += run
                }
                leaves += runs
            }
            check(input.read() == -1) { "English book cache has trailing bytes" }
            englishBookOf(leaves, pageOf, text)
        }
    }.getOrElse {
        runCatching { File(dir, key).delete() }
        null
    }

    /** Writes [book] down under [key], and forgets any book written before it. */
    fun write(key: String, book: EnglishBook) {
        // Never persist an empty book: see read. Drop any poison already
        // stored under this key so it cannot be picked up between here and
        // the next successful measure.
        if (book.leafCount == 0) {
            runCatching { File(dir, key).delete() }
            return
        }
        runCatching {
            dir.mkdirs()
            // One book at a time: a leaf's size changes when the phone is
            // folded or the type is resized, and yesterday's leaves are of no
            // use to anybody once it has.
            dir.listFiles()?.forEach { if (it.name != key) it.delete() }
            val tmp = File(dir, "$key.writing")
            // Take the leaves first: a count written in the header that the
            // body then disagrees with is a file that reads back short every
            // launch, which looks exactly like having no cache at all.
            val leaves = (0 until book.leafCount).mapNotNull { book.leaf(it) }
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(leaves.size)
                leaves.forEach { leaf ->
                    out.writeInt(leaf.runs.size)
                    leaf.runs.forEach { run ->
                        out.writeInt(run.surahId)
                        out.writeInt(run.ayah)
                        out.writeInt(run.from)
                        out.writeInt(run.to)
                    }
                }
            }
            // Rename last, so a book half written is a book that never existed.
            tmp.renameTo(File(dir, key))
        }
    }

    private companion object {
        /** Bump when the meaning of a written leaf changes. */
        const val FORMAT = 15
        const val MAX_LEAVES = 10_000
        const val MAX_RUNS = 20_000
    }
}

/** Adds the exact prose to the pagination identity without putting QF text in a file name. */
internal fun englishBookContentKey(base: String, verses: Map<Long, String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    verses.toSortedMap().forEach { (key, text) ->
        digest.update(key.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(text.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
    }
    return "$base-${digest.digest().joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }}"
}
