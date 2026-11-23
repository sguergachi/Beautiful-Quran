package com.beautifulquran.ui.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.beautifulquran.domain.MushafCatalog
import com.beautifulquran.domain.mushafFontPreloadPages

/**
 * Per-page QCF V2 typefaces, loaded from assets and held for the leaves around
 * the one being read.
 *
 * Each page of the mushaf is its own font — 604 of them, ~145 MB in the APK —
 * and a [Typeface] holds a native font object that no amount of Java heap
 * pressure will reclaim. Cached for the life of the process, reading the book
 * end to end would retain every one of them, so the cache is a window: the
 * pager holds five leaves composed at rest, and a run of flips sets up to a
 * dozen ahead of it — the turn's target and three past it either way, and the
 * resting leaf's two either side ([mushafWarmOrder]) — so [MAX_RESIDENT]
 * covers both while the rest are dropped in least-recently-used order. A face
 * evicted mid-run took its warmed cell layouts with it, and its leaf composed
 * cold.
 */
/** Both representations of one resident page face, kept and evicted together. */
internal class MushafQcfFace(
    val family: FontFamily,
    val typeface: Typeface,
)

internal object MushafQcfFonts {
    /** The composed leaves plus everything a run of flips warms, with margin. */
    private const val MAX_RESIDENT = 20

    /**
     * Both forms of one face. A line is drawn as [FontFamily] text, but it is
     * *spaced* from the [Typeface]'s own ink bounds (see [MushafQcfPageLine]),
     * and building a second copy would mean a second native font per page.
     */
    private val families = object : LinkedHashMap<Int, MushafQcfFace>(
        MAX_RESIDENT,
        0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, MushafQcfFace>,
        ): Boolean {
            if (size <= MAX_RESIDENT) return false
            // Its laid-out cells hold the same native font; let them go with it.
            MushafWordLayouts.forget(eldest.value.family)
            return true
        }
    }

    @Synchronized
    fun cached(page: Int): MushafQcfFace? = families[page]

    fun face(context: Context, page: Int): MushafQcfFace? {
        cached(page)?.let { return it }
        // Built outside the lock: createFromAsset reads the file, and the UI
        // thread asks for the settled page while a warm-up is still running.
        val typeface = typeface(context, page) ?: return null
        return put(page, MushafQcfFace(FontFamily(typeface), typeface))
    }

    @Synchronized
    private fun put(page: Int, face: MushafQcfFace): MushafQcfFace =
        families.getOrPut(page) { face }

    fun preload(context: Context, pages: Iterable<Int>) {
        pages.forEach { face(context, it) }
    }

    private fun typeface(context: Context, page: Int): Typeface? {
        if (page !in 1..MushafCatalog.MUSHAF_PAGE_COUNT) return null
        val path = "qcf-v2-fonts/QCF2${page.toString().padStart(3, '0')}.qcf"
        return try {
            Typeface.createFromAsset(context.assets, path)
        } catch (_: Exception) {
            null
        }
    }
}
