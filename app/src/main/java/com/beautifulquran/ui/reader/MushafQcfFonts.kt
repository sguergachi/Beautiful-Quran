package com.beautifulquran.ui.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.util.concurrent.ConcurrentHashMap

/** Per-page QCF V2 typefaces, loaded from assets and cached for the process. */
internal object MushafQcfFonts {
    private val typefaces = ConcurrentHashMap<Int, Typeface>()
    private val families = ConcurrentHashMap<Int, FontFamily>()

    fun cached(page: Int): FontFamily? = families[page]

    fun family(context: Context, page: Int): FontFamily? {
        families[page]?.let { return it }
        val tf = typeface(context, page) ?: return null
        return FontFamily(tf).also { families[page] = it }
    }

    fun preload(context: Context, pages: Iterable<Int>) {
        pages.forEach { family(context, it) }
    }

    private fun typeface(context: Context, page: Int): Typeface? {
        typefaces[page]?.let { return it }
        if (page !in 1..604) return null
        val path = "qcf-v2-fonts/QCF2${page.toString().padStart(3, '0')}.qcf"
        return try {
            Typeface.createFromAsset(context.assets, path).also { typefaces[page] = it }
        } catch (_: Exception) {
            null
        }
    }
}
