package com.beautifulquran.ui.reader

import android.content.Context
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.beautifulquran.domain.MUSHAF_WORD_GAP_EM
import com.beautifulquran.domain.MushafGrid
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.mushafDisplayFontPx
import com.beautifulquran.domain.mushafGridSlots
import com.beautifulquran.domain.mushafIsOpeningLeaf
import com.beautifulquran.domain.mushafLineJustifies
import com.beautifulquran.domain.mushafLineMayStandShort
import com.beautifulquran.domain.mushafUniformFontPx
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs
import com.beautifulquran.domain.reflowMushafPage
import com.beautifulquran.domain.surahOpensWithBasmalahPreface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What a leaf is set to, as the last composed Arabic leaf found it. Every
 * leaf of one reader shares it, so the leaves a turn has yet to reach can be
 * set from it before they are composed.
 */
internal class MushafLeafSetting(
    /** The measure the type size is fitted to: the leaf inside its fit inset. */
    val fitWidthPx: Float,
    val unitPx: Float,
    /** The measure every ordinary line is set to. */
    val lineMeasurePx: Float,
    val density: Density,
    val resolver: FontFamily.Resolver,
    val locales: LocaleList,
    val wordInk: Color,
    val markInk: Color,
)

/**
 * Sets leaves before they are composed, off the UI thread.
 *
 * Composing a leaf nobody has opened yet measured every line on the UI thread:
 * its reflow, each word's ink profile and bounds, the fit, and then a
 * paragraph per word — ~75 ms on the emulator, which a run of quick flips paid
 * at the start of almost every swipe. All of it lands in the caches the
 * composition reads ([mushafDisplayPage], [mushafLineGeometry],
 * [MushafWordLayouts]) under the same keys, so a warmed leaf composes from
 * them. A miss — a setting not seen yet, a size this did not predict — only
 * means the composition measures it itself, as it always did.
 */
internal object MushafLeafWarmup {
    @Volatile
    var setting: MushafLeafSetting? = null

    suspend fun warm(
        context: Context,
        page: MushafPage,
        surahAyahCount: (Int) -> Int?,
    ) {
        val face = MushafQcfFonts.face(context, page.page) ?: return
        warmMushafInkProfiles(page, face.typeface)
        val setting = setting ?: return
        // The opening leaves are set in a circle, each row to its own chord.
        if (mushafIsOpeningLeaf(page.page)) return
        val display = mushafDisplayPage(page, face.typeface)
        val fontPx = mushafLeafFontPx(page, setting.fitWidthPx, setting.unitPx)
        val fontSp = with(setting.density) { fontPx.toSp() }
        val linePx = with(setting.density) { fontSp.toPx() }
        display.lines.forEach { line ->
            currentCoroutineContext().ensureActive()
            if (line.tokens.none { it.word.qcfV2.isNotEmpty() }) return@forEach
            val last = line.tokens.lastOrNull()
            val geometry = mushafLineGeometry(
                page = page.page,
                line = line,
                pageTypeface = face.typeface,
                linePx = linePx,
                measureWidthPx = setting.lineMeasurePx,
                justify = mushafLineJustifies(line.tokens.size),
                allowShort = mushafLineMayStandShort(page.page, last, last?.surahId?.let(surahAyahCount)),
            )
            val style = mushafQcfLineStyle(fontSp, face.family, geometry.fit.scale)
            val wordStyle = style.copy(color = setting.wordInk)
            val markStyle = style.copy(color = setting.markInk)
            mushafQcfCellTexts(line).forEach { (text, mark) ->
                MushafWordLayouts.layout(
                    text = text,
                    style = if (mark) markStyle else wordStyle,
                    density = setting.density,
                    direction = LayoutDirection.Rtl,
                    resolver = setting.resolver,
                    locales = setting.locales,
                )
            }
        }
    }
}

/**
 * The page as the leaf sets it — rows rebalanced by [reflowMushafPage] with
 * the page's own face — from a small process-wide cache, so the leaf and
 * [MushafLeafWarmup] reflow each page once.
 */
internal fun mushafDisplayPage(page: MushafPage, typeface: android.graphics.Typeface?): MushafPage {
    val key = DisplayPageKey(page.page, System.identityHashCode(typeface))
    synchronized(displayPages) {
        displayPages[key]?.takeIf { it.source === page && it.typeface === typeface }?.let { return it.display }
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = 100f
    }
    val display = reflowMushafPage(page) { token ->
        val qcf = token.word.qcfV2
        val word = if (qcf.isEmpty()) {
            token.word.arabic
        } else {
            qcfWordGlyphs(qcf, token.endsAyah)
        }
        val mark = if (qcf.isEmpty()) "" else qcfTrailingMark(qcf, token.endsAyah)
        paint.measureText(word) + paint.measureText(mark) + MUSHAF_WORD_GAP_EM * paint.textSize
    }
    synchronized(displayPages) { displayPages[key] = DisplayPage(page, typeface, display) }
    return display
}

private data class DisplayPageKey(val page: Int, val typefaceId: Int)

private class DisplayPage(
    val source: MushafPage,
    val typeface: android.graphics.Typeface?,
    val display: MushafPage,
)

private val displayPages = object : LinkedHashMap<DisplayPageKey, DisplayPage>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DisplayPageKey, DisplayPage>) =
        size > 32
}

/**
 * The fit's slot count: the page's lines, plus a title band for every chapter
 * opening on it and a basmalah row under each that takes one.
 */
internal fun mushafLeafFitSlotCount(page: MushafPage): Int =
    (page.lines.size + page.surahStarts.size +
        page.surahStarts.count { surahOpensWithBasmalahPreface(it.surahId) })
        .coerceAtLeast(1)

/**
 * The one type size a leaf is set at (see [MushafPageSheet]). The well is the
 * grid's fifteen units, whatever the page holds: a leaf carrying a chapter's
 * opening asks for more slots than that and packs them into the same paper.
 * Both arguments used to be scaled by the slot count, which cancelled and left
 * the guard unable to bite — a chapter opening kept type cut for a full slot
 * while its slot had shrunk to about 0.88 of one.
 */
internal fun mushafLeafFontPx(page: MushafPage, fitWidthPx: Float, unitPx: Float): Float =
    mushafDisplayFontPx(
        mushafUniformFontPx(
            measureWidthPx = fitWidthPx,
            wellHeightPx = unitPx * MushafGrid.TEXT_LINES,
            slots = mushafGridSlots(mushafLeafFitSlotCount(page)),
        ),
    )

/** How many leaves past a turn's target are set ahead of it. */
internal const val MushafWarmReach = 3

/**
 * 1-based pages to set, most urgent first: the turn's target, the leaves
 * beyond it in the direction of travel, the ones behind it, then the resting
 * leaf's neighbours. Indices are the pager's, 0-based.
 */
internal fun mushafWarmOrder(
    settledIndex: Int,
    currentIndex: Int,
    targetIndex: Int,
    pageCount: Int,
    reach: Int = MushafWarmReach,
): List<Int> {
    val heading = Integer.signum(targetIndex - settledIndex)
        .takeIf { it != 0 } ?: Integer.signum(currentIndex - settledIndex)
    val order = LinkedHashSet<Int>()
    order += targetIndex
    if (heading != 0) for (d in 1..reach) order += targetIndex + heading * d
    for (d in 1..reach) {
        order += targetIndex + d
        order += targetIndex - d
    }
    for (d in 1..2) {
        order += settledIndex + d
        order += settledIndex - d
    }
    order += settledIndex
    return order.filter { it in 0 until pageCount }.map { it + 1 }
}
