package com.beautifulquran.ui.reader

import com.beautifulquran.domain.MUSHAF_MEDALLION_DIAMETER_EM
import com.beautifulquran.domain.MushafGrid
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.mushafDisplayFontPx
import com.beautifulquran.domain.mushafDisplayGridSlots
import com.beautifulquran.domain.mushafGridSlots
import com.beautifulquran.domain.mushafIsOpeningLeaf
import com.beautifulquran.domain.mushafMedallionChords
import com.beautifulquran.domain.mushafMedallionPitchPx
import com.beautifulquran.domain.mushafUniformFontPx
import com.beautifulquran.domain.surahOpensWithBasmalahPreface

/**
 * The paper every Madinah leaf of one reader is set on, px, and every size
 * derived from it: the one place a leaf ([MushafPageSheet]) and the warm-up
 * that sets leaves ahead of it ([MushafLeafWarmup]) take their measures from,
 * so the warm-up's cache keys are the composition's by construction.
 */
internal data class MushafLeafFrame(
    /** The text block's box: the leaf inside its page margins. */
    val widthPx: Float,
    val heightPx: Float,
    /**
     * The only inset left inside the text block: enough for a circled ayah
     * mark's overhang at the line end, and nothing more. It is an inset of the
     * *measure* — taking it off the height as well made the leading a fortieth
     * shorter than MUSHAF_LINE_INK_EM claims and charged the type for the
     * difference.
     */
    val edgeGutterPx: Float,
    /** Rounding slack the fit keeps off the measure and the height. */
    val fitSlackPx: Float,
    /** One unit of the leaf's grid (see MushafGrid). */
    val unitPx: Float,
) {
    /** The well's height: the height gives up nothing but rounding slack. */
    val wellHeightPx: Float get() = (heightPx - fitSlackPx).coerceAtLeast(1f)

    /** The measure the type size is fitted to. */
    val fitWidthPx: Float get() = (widthPx - (edgeGutterPx + fitSlackPx) * 2).coerceAtLeast(1f)

    /**
     * The measure every ordinary line is set to — the leaf inside its
     * gutters, once for the leaf rather than by a BoxWithConstraints per line.
     */
    val lineMeasurePx: Float get() = (widthPx - edgeGutterPx * 2).coerceAtLeast(1f)

    /**
     * The one type size [page] is set at. One size for the whole book: the
     * measure, not the page's own longest line — fitting each leaf to itself
     * made the hand grow and shrink as the pages turned. The well is the
     * grid's fifteen units, whatever the page holds: a leaf carrying a
     * chapter's opening asks for more slots than that and packs them into the
     * same paper. Both arguments used to be scaled by the slot count, which
     * cancelled and left the guard unable to bite — a chapter opening kept type
     * cut for a full slot while its slot had shrunk to about 0.88 of one.
     */
    fun fontPx(page: MushafPage): Float =
        mushafDisplayFontPx(
            mushafUniformFontPx(
                measureWidthPx = fitWidthPx,
                wellHeightPx = unitPx * MushafGrid.TEXT_LINES,
                slots = mushafGridSlots(mushafLeafSlotCount(page)),
            ),
        )

    /** One slot of [displayPage]'s grid: one unit of it, whatever it holds. */
    fun lineSlotPx(displayPage: MushafPage): Float =
        wellHeightPx / mushafDisplayGridSlots(mushafLeafSlotCount(displayPage))
}

/**
 * A page's slots: its lines, plus a title band for every chapter opening on
 * it and a basmalah row under each that takes one.
 */
internal fun mushafLeafSlotCount(page: MushafPage): Int =
    (page.lines.size + page.surahStarts.size +
        page.surahStarts.count { surahOpensWithBasmalahPreface(it.surahId) })
        .coerceAtLeast(1)

/** An opening leaf's medallion: each line's width and the one pitch every row stands on. */
internal class MushafMedallion(val rowWidthsPx: FloatArray, val pitchPx: Float)

/**
 * The two opening leaves are the print's medallion: seven rows in a circle —
 * al-Fātiḥah's seven lines, al-Baqarah's basmalah and six — under a title
 * band. Each line is as wide as it comes to set in the book's own hand and
 * word spacing ([mushafLineSetWidthPx]), so the print's short and long lines
 * draw the circle, and every row, the band's included, stands on one pitch:
 * the circle's diameter over its rows ([mushafMedallionPitchPx]), one circle
 * for the facing pair. All of it follows the type size, so the shape is the
 * same on every screen. Null on every other leaf.
 */
internal fun mushafMedallion(
    displayPage: MushafPage,
    typeface: android.graphics.Typeface?,
    fontPx: Float,
    frame: MushafLeafFrame,
): MushafMedallion? {
    if (!mushafIsOpeningLeaf(displayPage.page) || displayPage.lines.isEmpty()) return null
    val basmalahRows = displayPage.surahStarts.count { surahOpensWithBasmalahPreface(it.surahId) }
    val rows = displayPage.lines.size + basmalahRows
    val chords = mushafMedallionChords(rows)
    val widths = FloatArray(displayPage.lines.size) { i ->
        // The Hafs stand-in, before the page face lands, has no set widths: it
        // takes the circle's chords.
        (mushafLineSetWidthPx(displayPage.lines[i], typeface, fontPx)
            ?: (MUSHAF_MEDALLION_DIAMETER_EM * fontPx * chords[i + basmalahRows]))
            .coerceAtMost(frame.lineMeasurePx)
    }
    val pitch = mushafMedallionPitchPx(
        fontPx = fontPx,
        widestRowPx = widths.max(),
        rows = rows,
        wellHeightPx = frame.wellHeightPx,
        slotsOutside = displayPage.surahStarts.size,
    )
    return MushafMedallion(widths, pitch)
}
