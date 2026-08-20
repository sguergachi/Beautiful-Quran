package com.beautifulquran.ui.reader

import com.beautifulquran.data.PageNumberScript

internal fun Int.toArabicIndicDigits(): String =
    toString().map { '٠' + (it - '0') }.joinToString("")

/** Which folio figures a page break paints, and whether they sit as one centred number. */
data class PageFolioLayout(
    val leading: String,
    val trailing: String?,
    val centered: Boolean,
)

fun pageFolioLayout(page: Int, script: PageNumberScript): PageFolioLayout {
    val western = page.toString()
    val arabic = page.toArabicIndicDigits()
    return when (script) {
        PageNumberScript.BOTH -> PageFolioLayout(western, arabic, centered = false)
        PageNumberScript.ENGLISH -> PageFolioLayout(western, null, centered = true)
        PageNumberScript.ARABIC -> PageFolioLayout(arabic, null, centered = true)
    }
}

/**
 * Folio figures on a mushaf leaf. Always centred: a single script is the
 * number itself, both scripts sit either side of a diamond on the spine.
 */
data class MushafFolioLayout(
    val western: String?,
    val arabic: String?,
    val diamond: Boolean,
)

fun mushafFolioLayout(page: Int, script: PageNumberScript): MushafFolioLayout {
    val western = page.toString()
    val arabic = page.toArabicIndicDigits()
    return when (script) {
        PageNumberScript.BOTH -> MushafFolioLayout(western, arabic, diamond = true)
        PageNumberScript.ENGLISH -> MushafFolioLayout(western, null, diamond = false)
        PageNumberScript.ARABIC -> MushafFolioLayout(null, arabic, diamond = false)
    }
}
