package com.beautifulquran.data

import android.content.Context
import com.beautifulquran.domain.EnglishBook
import com.beautifulquran.domain.EnglishVerseRun
import com.beautifulquran.domain.englishBookOf
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

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
            val leaves = ArrayList<List<EnglishVerseRun>>(leafCount)
            repeat(leafCount) {
                val runCount = input.readInt()
                val runs = ArrayList<EnglishVerseRun>(runCount)
                repeat(runCount) {
                    runs += EnglishVerseRun(
                        surahId = input.readInt(),
                        ayah = input.readInt(),
                        from = input.readInt(),
                        to = input.readInt(),
                    )
                }
                leaves += runs
            }
            englishBookOf(leaves, pageOf, text)
        }
    }.getOrNull()

    /** Writes [book] down under [key], and forgets any book written before it. */
    fun write(key: String, book: EnglishBook) {
        runCatching {
            dir.mkdirs()
            // One book at a time: a leaf's size changes when the phone is
            // folded or the type is resized, and yesterday's leaves are of no
            // use to anybody once it has.
            dir.listFiles()?.forEach { if (it.name != key) it.delete() }
            val tmp = File(dir, "$key.writing")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(book.leafCount)
                for (index in 0 until book.leafCount) {
                    val leaf = book.leaf(index) ?: continue
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
        const val FORMAT = 5
    }
}
