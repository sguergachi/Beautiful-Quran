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
 * pager composes at most three leaves and warms two either side
 * ([mushafFontPreloadPages]), and [MAX_RESIDENT] keeps a comfortable margin
 * over that while the rest are dropped in least-recently-used order.
 */
internal object MushafQcfFonts {
    /** Comfortably more than the pager's composed-plus-warmed window of five. */
    private const val MAX_RESIDENT = 12

    private val families = object : LinkedHashMap<Int, FontFamily>(
        MAX_RESIDENT,
        0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FontFamily>): Boolean =
            size > MAX_RESIDENT
    }

    @Synchronized
    fun cached(page: Int): FontFamily? = families[page]

    fun family(context: Context, page: Int): FontFamily? {
        cached(page)?.let { return it }
        // Built outside the lock: createFromAsset reads the file, and the UI
        // thread asks for the settled page while a warm-up is still running.
        val family = typeface(context, page)?.let(::FontFamily) ?: return null
        return put(page, family)
    }

    @Synchronized
    private fun put(page: Int, family: FontFamily): FontFamily =
        families.getOrPut(page) { family }

    fun preload(context: Context, pages: Iterable<Int>) {
        pages.forEach { family(context, it) }
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
