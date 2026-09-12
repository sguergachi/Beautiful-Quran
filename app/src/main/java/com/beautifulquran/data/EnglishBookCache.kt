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
 * cached against the layout it was computed for. A handful of recent configs
 * stay on disk so flipping translation, gloss, or type size does not remeasure;
 * older ones are forgotten.
 *
 * [key] is everything the pagination depends on. Anything not in it that can
 * change the leaves is a bug that shows as a book breaking in the wrong places,
 * so it carries a format version too: bump [FORMAT] when the leaves' meaning
 * changes, and the next launch measures instead of reading.
 */
class EnglishBookCache internal constructor(private val dir: File) {

    constructor(context: Context) : this(File(context.cacheDir, "english-book"))

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
    @Synchronized
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
        }.also {
            // Access recency so flipping translation/gloss/size keeps this key.
            file.setLastModified(System.currentTimeMillis())
        }
    }.getOrNull()

    /**
     * Writes [book] down under [key], keeping a small recency-bounded set of
     * other configs so switching translation, gloss, or type size back and
     * forth does not remeasure. Access and pruning share the same lock so an
     * older eviction decision cannot remove a freshly replaced book.
     */
    @Synchronized
    fun write(key: String, book: EnglishBook) {
        runCatching {
            dir.mkdirs()
            val tmp = File.createTempFile("book-", ".writing", dir)
            try {
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
                val target = File(dir, key)
                check(tmp.renameTo(target)) { "Could not publish English book" }
                prune(target)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun prune(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        // A killed process cannot run finally. Leave recent temporary files
        // alone, but reclaim abandoned ones on a later successful write.
        val abandonedBefore = System.currentTimeMillis() - 86_400_000L
        files.filter { it.name.endsWith(".writing") && it.lastModified() < abandonedBefore }
            .forEach { it.delete() }
        val others = files.filter { it != keep && !it.name.endsWith(".writing") }
        // Explicitly protect this write even when filesystem timestamps tie.
        others.sortedByDescending { it.lastModified() }
            .drop(RETAINED_BOOKS - 1)
            .forEach { it.delete() }
    }

    internal companion object {
        /** Recent configs kept so Customize back-and-forth does not remeasure. */
        const val RETAINED_BOOKS = 4
        /**
         * Bump when the meaning of a written leaf changes.
         *
         * 16: the prose hyphenates (`Hyphens.Auto`, `LineBreak.Balanced`,
         * the book face's kern/liga/onum) with unbookish breaks vetoed by a
         * word joiner (`EnglishHyphenation`) — every leaf breaks somewhere new.
         *
         * 17: justification. Shipped, then taken back out; the leaf is ragged.
         *
         * 18: the verse mark is bound to the word it closes with a narrow
         * no-break space, so it can no longer open a line. Every leaf that
         * carried a stranded mark breaks somewhere new.
         *
         * 19: the prose breaks greedily (`LineBreak.Strategy.Simple`). The
         * evening strategies drew a phantom second margin; every leaf's lines
         * end somewhere new.
         *
         * 20: the leaf carries 1,400 characters instead of 940, so the hand is
         * cut smaller and the line holds 53 characters instead of 45. Every
         * leaf in the book is a different length of text.
         *
         * 21: 1,250, giving back some of the hand 20 spent — 18.2 sp and 48
         * characters to the line. Every leaf moves again.
         *
         * 22: the head gutter is one line of prose rather than one Arabic
         * unit, so the well is taller and the hand solved from it a hair
         * bigger. The leaf holds a little more.
         */
        const val FORMAT = 23
    }
}
