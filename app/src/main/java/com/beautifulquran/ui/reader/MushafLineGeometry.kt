package com.beautifulquran.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import com.beautifulquran.domain.MUSHAF_LINE_EM
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafPage
import com.beautifulquran.domain.mushafLineJustifies
import com.beautifulquran.domain.MUSHAF_WORD_GAP_EM
import com.beautifulquran.domain.MUSHAF_MAX_LINE_SCALE
import com.beautifulquran.domain.MUSHAF_MIN_LINE_SCALE
import com.beautifulquran.domain.MushafLineFit
import com.beautifulquran.domain.mushafLineFit
import com.beautifulquran.domain.qcfTrailingMark
import com.beautifulquran.domain.qcfWordGlyphs

// How a Madinah line is measured and fitted, apart from the composables that
// draw it (MushafHafsLine.kt): pure, cached and safe off the UI thread, so the
// leaf and the warm-up that sets leaves ahead of it share every measurement.

/**
 * What one display row's words measure, before any fit: each cell's advance
 * and ink at the size it is set, unnarrowed, and what each join between two
 * cells offers. It depends on the words, the face and the size — not on the
 * measure — so a row's fit to any width ([mushafLineGeometry]) and its own set
 * width on an opening leaf ([mushafLineSetWidthPx]) share one measurement.
 */
internal class MushafLineMetrics(
    val cells: List<MushafCell>,
    val joinsEm: List<MushafInkJoin>,
)

private data class MushafLineMetricsKey(
    val page: Int,
    val line: Int,
    val fontPxBits: Int,
    val typefaceId: Int,
    val contentKey: Int,
)

private val lineMetricsCache =
    object : LinkedHashMap<MushafLineMetricsKey, MushafLineMetrics>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<MushafLineMetricsKey, MushafLineMetrics>,
        ) = size > 512
    }

/**
 * [MushafLineMetrics] from the process-wide cache, measured on a miss: ink
 * profiles render each word to a bitmap and the joins derive from them. Safe
 * off the UI thread; the lock covers the map only.
 */
internal fun mushafLineMetrics(
    page: Int,
    line: MushafLine,
    typeface: android.graphics.Typeface?,
    fontPx: Float,
): MushafLineMetrics {
    val key = MushafLineMetricsKey(
        page = page,
        line = line.number,
        fontPxBits = fontPx.toRawBits(),
        typefaceId = System.identityHashCode(typeface),
        contentKey = mushafLineContentKey(line),
    )
    synchronized(lineMetricsCache) { lineMetricsCache[key] }?.let { return it }
    val texts = mushafLineTexts(line)
    val metrics = MushafLineMetrics(
        cells = mushafLineCells(texts.map { it.text }, typeface, fontPx, condense = 1f),
        joinsEm = mushafLineJoins(texts, typeface),
    )
    return synchronized(lineMetricsCache) { lineMetricsCache.getOrPut(key) { metrics } }
}

/**
 * Drops every line measured in [typeface]: [MushafQcfFonts] calls it when a
 * page face leaves its window, since a rebuilt face is a new typeface whose
 * keys these entries would never match again.
 */
internal fun forgetMushafLineMeasures(typeface: android.graphics.Typeface) {
    val id = System.identityHashCode(typeface)
    synchronized(lineMetricsCache) { lineMetricsCache.keys.removeAll { it.typefaceId == id } }
    synchronized(lineGeometryCache) { lineGeometryCache.keys.removeAll { it.typefaceId == id } }
}

/**
 * Measured geometry for one mushaf line, cacheable across leaf compositions.
 *
 * Swiping back to a visited leaf re-composes its lines, and the measure is
 * not cheap: ink profiles render each word to a bitmap, the joins derive
 * from them, and the fit runs a bisection per line — all on the UI thread,
 * mid-swipe. The face LRU evicts typefaces (and with them the profile
 * cache), so this cache holds the geometry strongly, keyed by what the
 * geometry actually depends on: the page's own face, the line's words,
 * the size, and the measure. Line number alone is not the line — display
 * reflow rebuilds row N from a different token list once the face lands,
 * and reusing the previous row's flush fit stretched three words across
 * the measure (a river down the leaf).
 */
internal class MushafLineGeometry(
    val cells: List<MushafCell>,
    val joinsEm: List<MushafInkJoin>,
    val fit: MushafLineFit,
    /** [cells] as drawn: measured again at the fit's narrowing. */
    val drawnCells: List<MushafCell>,
)

internal data class MushafLineGeometryKey(
    val page: Int,
    val line: Int,
    val fontPxBits: Int,
    val measureWidthPxBits: Int,
    val typefaceId: Int,
    val contentKey: Int,
    val allowShort: Boolean,
)

private val lineGeometryCache =
    object : LinkedHashMap<MushafLineGeometryKey, MushafLineGeometry>(64, 0.75f, true) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<MushafLineGeometryKey, MushafLineGeometry>,
    ) =
        size > 512
}

/** Identity of the words a display row is actually setting. */
internal fun mushafLineContentKey(line: MushafLine): Int {
    var h = line.tokens.size
    line.tokens.forEach { token ->
        h = 31 * h + token.surahId
        h = 31 * h + token.ayah
        h = 31 * h + token.word.position
    }
    return h
}

internal fun lineGeometryKey(
    page: Int,
    line: MushafLine,
    pageTypeface: android.graphics.Typeface?,
    fontPx: Float,
    measureWidthPx: Float,
    allowShort: Boolean,
): MushafLineGeometryKey = MushafLineGeometryKey(
    page = page,
    line = line.number,
    fontPxBits = fontPx.toRawBits(),
    measureWidthPxBits = measureWidthPx.toRawBits(),
    typefaceId = System.identityHashCode(pageTypeface),
    contentKey = mushafLineContentKey(line),
    allowShort = allowShort,
)

/**
 * [MushafLineGeometry] from the process-wide cache, measured on a miss. Safe
 * off the UI thread, which is where [MushafLeafWarmup] calls it from: the lock
 * covers the map only, and two threads measuring one line both get the same
 * answer.
 */
internal fun mushafLineGeometry(
    page: Int,
    line: MushafLine,
    pageTypeface: android.graphics.Typeface?,
    linePx: Float,
    measureWidthPx: Float,
    justify: Boolean,
    allowShort: Boolean,
): MushafLineGeometry {
    val key = lineGeometryKey(page, line, pageTypeface, linePx, measureWidthPx, allowShort)
    synchronized(lineGeometryCache) { lineGeometryCache[key] }?.let { return it }
    val geometry = com.beautifulquran.DevProfiling.trace("lineGeometryMiss") {
        val metrics = mushafLineMetrics(page, line, pageTypeface, linePx)
        val rawCells = metrics.cells
        val joinsEm = metrics.joinsEm
        val fit = if (!justify) {
            MushafLineFit(
                scale = 1f,
                gapPx = MUSHAF_WORD_GAP_EM * linePx,
                flush = false,
            )
        } else if (joinsEm.size == (rawCells.size - 1).coerceAtLeast(0) && joinsEm.isNotEmpty()) {
            mushafInkLineFit(
                inkWidthPx = rawCells.sumOf { it.inkWidth.toDouble() }.toFloat(),
                joins = joinsEm,
                measureWidthPx = measureWidthPx,
                fontPx = linePx,
                allowShort = allowShort,
            )
        } else {
            mushafLineFit(
                inkWidthPx = rawCells.sumOf { it.inkWidth.toDouble() }.toFloat(),
                gapCount = (rawCells.size - 1).coerceAtLeast(0),
                measureWidthPx = measureWidthPx,
                fontPx = linePx,
                allowShort = allowShort,
            )
        }
        // Measured again at the size they are drawn, not scaled arithmetically
        // from the unscaled measurement: see [MushafQcfPageLine].
        val drawnCells = if (fit.scale == 1f) {
            rawCells
        } else {
            mushafLineCells(mushafLineTexts(line).map { it.text }, pageTypeface, linePx, fit.scale)
        }
        MushafLineGeometry(rawCells, joinsEm, fit, drawnCells)
    }
    return synchronized(lineGeometryCache) { lineGeometryCache.getOrPut(key) { geometry } }
}

/**
 * The style a QCF line sets its cells in. Shared with [MushafLeafWarmup], whose
 * laid-out cells are only found again if the style is equal to this one.
 */
internal fun mushafQcfLineStyle(fontSize: TextUnit, pageFont: FontFamily, condense: Float): TextStyle =
    TextStyle(
        fontFamily = pageFont,
        fontSize = fontSize,
        lineHeight = MUSHAF_LINE_EM.em,
        textDirection = TextDirection.Rtl,
        textGeometricTransform = TextGeometricTransform(scaleX = condense),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        // AB: linear metrics + subpixel positioning (no grid fitting)
        textMotion = TextMotion.Animated,
    )

/** What each QCF cell of [line] sets: a word's glyphs, then its verse mark if it closes one. */
internal fun mushafQcfCellTexts(line: MushafLine): List<Pair<String, Boolean>> = buildList {
    line.tokens.forEach { token ->
        val raw = token.word.qcfV2
        add((if (raw.isNotEmpty()) qcfWordGlyphs(raw, token.endsAyah) else token.word.arabic) to false)
        val mark = raw.takeIf { it.isNotEmpty() }?.let { qcfTrailingMark(it, token.endsAyah) }.orEmpty()
        if (mark.isNotEmpty()) add(mark to true)
    }
}

/**
 * Whether the GPU draws this cell's glyph as an outline rather than from its
 * glyph atlas: Skia sends a glyph whose mask would run past 256 px either way
 * to the path renderer, while a software canvas still rasterises it as a mask,
 * and the two differ visibly at the edges. Such a word — a few on a leaf at
 * most, like the wide سَتُغۡلَبُونَ on page 51 — is kept out of the leaf's turn
 * picture and drawn live over it. Erring wide only draws a word live.
 */
internal fun mushafCellDrawnAsPath(cell: MushafCell): Boolean =
    cell.inkWidth >= MushafPathGlyphPx || cell.inkHeight >= MushafPathGlyphPx

/** Under Skia's 256 px atlas limit, with room for a mask's padding and rounding. */
private const val MushafPathGlyphPx = 232f

/** A cell's advance and where its ink sits inside it, in px. */
internal class MushafCell(
    val advance: Float,
    val inkLeft: Float,
    val inkRight: Float,
    /** The ink's height, px. */
    val inkHeight: Float = 0f,
) {
    val inkWidth: Float get() = (inkRight - inkLeft).coerceAtLeast(0f)

    /** The same cell with its letterforms narrowed — scaleX scales ink and
     * advance alike, so the measurements scale with them. */
    fun scaled(k: Float) = MushafCell(advance * k, inkLeft * k, inkRight * k, inkHeight)
}

/**
 * What the line draws, one string per node.
 *
 * One entry per drawn child, empty or not: the layout emits a node for every
 * word whether or not it carries glyphs, and a list that skipped the empty ones
 * put every following word on the wrong origin — the line then ended short of
 * its own margin by about a word.
 */
internal fun mushafLineTexts(line: MushafLine): List<MushafLineText> {
    val out = ArrayList<MushafLineText>(line.tokens.size + 4)
    line.tokens.forEach { token ->
        val raw = token.word.qcfV2
        out += MushafLineText(
            text = if (raw.isNotEmpty()) {
                qcfWordGlyphs(raw, token.endsAyah)
            } else {
                token.word.arabic
            },
            mark = false,
        )
        val mark = if (raw.isNotEmpty()) qcfTrailingMark(raw, token.endsAyah) else ""
        if (mark.isNotEmpty()) {
            out += MushafLineText(text = mark, mark = true)
        }
    }
    return out
}

/**
 * One cell of a line, and whether it is a verse mark rather than a word.
 *
 * The mark is a cell of its own — see docs/QURAN_TYPOGRAPHY.md rule 8 — but it
 * is not a word, and the joins either side of it are not word joins: see
 * MUSHAF_MARK_WHITE_K.
 */
internal class MushafLineText(val text: String, val mark: Boolean)

internal fun mushafLineCells(
    texts: List<String>,
    typeface: android.graphics.Typeface?,
    fontPx: Float,
    condense: Float,
): List<MushafCell> {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = fontPx
        textScaleX = condense
    }
    val bounds = android.graphics.Rect()
    return texts.map { text ->
        if (text.isEmpty()) {
            MushafCell(0f, 0f, 0f)
        } else {
            val advance = paint.measureText(text)
            paint.getTextBounds(text, 0, text.length, bounds)
            MushafCell(advance, bounds.left.toFloat(), bounds.right.toFloat(), bounds.height().toFloat())
        }
    }
}

/**
 * What each join between two words offers, in em — the white it already
 * carries, and the closest the two come.
 *
 * Held in em because it is a property of the two letterforms and nothing else:
 * a horizontal narrowing scales it exactly as it scales the ink either side,
 * and the type size scales it too, so the raster work is done once per glyph
 * for the whole book. See [MushafInkProfiles].
 */
private fun mushafLineJoins(
    texts: List<MushafLineText>,
    typeface: android.graphics.Typeface?,
): List<MushafInkJoin> {
    if (texts.size < 2 || typeface == null) return emptyList()
    val profiles = texts.map { MushafInkProfiles.of(typeface, it.text) }
    return List(texts.size - 1) { i ->
        mushafInkJoin(profiles[i], profiles[i + 1], texts[i].mark || texts[i + 1].mark)
    }
}

/**
 * How wide [line] comes to, px at [fontPx], set exactly as a full line of
 * the book is: its white levelled to [MUSHAF_MAX_WHITE_LEVEL_EM] and its
 * letters widened to [MUSHAF_MAX_LINE_SCALE] — where [mushafInkLineFit]
 * leaves the reflowed rows of an ordinary leaf. Given that width as its
 * measure, the line is set in the same hand and the same word spacing as
 * every other page.
 *
 * The two opening leaves set their lines to it, so their medallion is drawn
 * by the words themselves rather than by widths the words are forced into: an
 * earlier measure, the print's own extent from the page face's advances,
 * came in under the fit's floor for the joins, and the fit narrowed the
 * letters to ~0.9 — the opening leaves read a size smaller than the book.
 * A line the fit does not justify (one word, or joins that do not line up
 * with its cells) is measured the way it is set instead. Null until the page
 * face has loaded.
 */
internal fun mushafLineSetWidthPx(
    page: Int,
    line: MushafLine,
    typeface: android.graphics.Typeface?,
    fontPx: Float,
): Float? {
    if (typeface == null || fontPx <= 0f) return null
    val metrics = mushafLineMetrics(page, line, typeface, fontPx)
    val cells = metrics.cells
    if (cells.isEmpty()) return null
    val joins = metrics.joinsEm
    if (!mushafLineJustifies(line.tokens.size) || joins.isEmpty() || joins.size != cells.size - 1) {
        // Set as [lineGeometry] sets such a line: the face's own advances,
        // a word gap between cells, unnarrowed.
        return cells.sumOf { it.advance.toDouble() }.toFloat() +
            MUSHAF_WORD_GAP_EM * fontPx * (cells.size - 1)
    }
    val ink = cells.sumOf { it.inkWidth.toDouble() }.toFloat() / fontPx
    var opened = 0f
    for (join in joins) {
        opened += maxOf(join.whiteAt(MUSHAF_MAX_WHITE_LEVEL_EM) - join.paper, join.floorEm)
    }
    return (ink + opened) * MUSHAF_MAX_LINE_SCALE * fontPx
}

/** Rasterizes a leaf's ink joins before that leaf reaches composition. */
internal fun warmMushafInkProfiles(
    page: MushafPage?,
    typeface: android.graphics.Typeface?,
) {
    if (page == null || typeface == null) return
    page.lines.forEach { line ->
        mushafLineTexts(line).forEach { MushafInkProfiles.of(typeface, it.text) }
    }
}

/**
 * Left origin for each cell, right to left across [width].
 *
 * A justified line divides the paper left over between the cells' *ink*, so
 * every join carries the same air. A short line — al-Fātiḥah, a chapter's
 * closing line — keeps the face's own advances and is centred, the way it is
 * printed.
 */
internal fun mushafCellOrigins(
    cells: List<MushafCell>,
    count: Int,
    width: Float,
    fit: MushafLineFit,
    /** What each join already carries, in em, fore-edge first. */
    joins: List<MushafInkJoin> = emptyList(),
    /** How many px the line's em comes to, once narrowed. */
    emPx: Float = 0f,
): FloatArray {
    val n = minOf(cells.size, count)
    val origins = FloatArray(count)
    if (n == 0) return origins
    val inkTotal = (0 until n).sumOf { cells[it].inkWidth.toDouble() }.toFloat()
    // A flush line divides what is actually left, so it ends on the margin
    // whatever the rasteriser did with the letterforms. The fit has already
    // chosen the scale that makes this residue the space it wants.
    val spread = if (fit.flush && n > 1) {
        (width - inkTotal).coerceAtLeast(0f)
    } else {
        fit.gapPx * (n - 1)
    }
    val steps = mushafJoinSteps(joins, n, spread, emPx)
    // A flush line starts at the fore-edge; a short one is centred on the page,
    // the way a chapter's closing line is printed.
    var inkRight = if (fit.flush) {
        width
    } else {
        width - ((width - (inkTotal + spread)) / 2f).coerceAtLeast(0f)
    }
    for (i in 0 until n) {
        origins[i] = inkRight - cells[i].inkRight
        inkRight -= cells[i].inkWidth + steps.getOrElse(i) { 0f }
    }
    return origins
}

/**
 * How far apart to set each pair of ink boxes so that every join on the line
 * carries the same *white*, given [spread] px of paper to divide between them.
 *
 * Dividing the paper equally between the boxes is what a line of Latin type
 * does, and it is wrong here: Arabic words nest, and how much white a join
 * already holds at the moment its boxes touch varies threefold along one line.
 * So the paper is divided to level the white instead — every join set to the
 * same measure `level` of it — except where a join's two words run alongside
 * each other and would weld at that setting. Those are held at their own floor
 * and the rest of the line absorbs the difference.
 *
 * `level` is found by bisection because the floors make the total a piecewise
 * function of it; twenty-four halvings settle it well inside a pixel. It is
 * free to go negative, and must be: a pair that nests deeply carries a third of
 * an em of white with its boxes already touching, so levelling a line down to
 * an ordinary word space means drawing that pair's boxes *through* each other.
 * Held at zero, those joins could never give anything back, and the whole of a
 * dense line's shortfall fell on the pairs that had least to spare.
 */
private fun mushafJoinSteps(
    joins: List<MushafInkJoin>,
    n: Int,
    spread: Float,
    emPx: Float,
): FloatArray {
    val steps = FloatArray(n)
    if (n < 2) return steps
    if (joins.size < n - 1 || emPx <= 0f) {
        val even = spread / (n - 1)
        for (i in 0 until n - 1) steps[i] = even
        return steps
    }
    // In em, like the level itself: a verse mark's white bends at a knee set in
    // em, so the bisection cannot be run in pixels and read the same.
    val active = if (joins.size == n - 1) joins else joins.subList(0, n - 1)
    val floors = FloatArray(n - 1) { active[it].floorEm * emPx }
    val spreadEm = spread / emPx
    var floorTotal = 0f
    for (i in 0 until n - 1) floorTotal += floors[i]
    if (floorTotal >= spread) {
        // The line is denser than bare clearance allows — the fit condenses to
        // avoid this, but it stops at MUSHAF_MIN_LINE_SCALE and a line may
        // arrive here anyway. What is missing then comes off each join in
        // proportion to the white it holds of its own, so the joins that have
        // none keep what little the floor gave them and no two words weld.
        var slackTotal = 0f
        for (i in 0 until n - 1) slackTotal += active[i].closest
        val short = floorTotal - spread
        for (i in 0 until n - 1) {
            steps[i] = floors[i] - if (slackTotal > 0f) {
                short * active[i].closest / slackTotal
            } else {
                short / (n - 1)
            }
        }
        return steps
    }
    val level = mushafWhiteLevel(active, spreadEm)
    for (i in 0 until n - 1) {
        val join = active[i]
        steps[i] = maxOf(join.whiteAt(level) - join.paper, join.floorEm) * emPx
    }
    return steps
}

/** What [joins] take between them when the line levels at [level], in em. */
private fun mushafWhiteSum(joins: List<MushafInkJoin>, level: Float): Float {
    var sum = 0f
    for (join in joins) sum += maxOf(join.whiteAt(level) - join.paper, join.floorEm)
    return sum
}

/**
 * The level of white a line settles at when [spreadEm] em of paper is divided
 * between [joins] — the same bisection [mushafJoinSteps] runs, asked in em and
 * before anything is placed, so the fit can ask what a setting would cost.
 */
private fun mushafWhiteLevel(joins: List<MushafInkJoin>, spreadEm: Float): Float {
    if (joins.isEmpty()) return 0f
    // Every join sits on its floor at `low`, so the level the line wants is at
    // or above it. It is not bounded from above by anything as simple: a mark's
    // white climbs more slowly than the level does, and past its knee slower
    // still, so the bracket is opened until it holds rather than solved for.
    var paperTotal = 0f
    var low = Float.MAX_VALUE
    for (join in joins) {
        paperTotal += join.paper
        low = minOf(low, join.levelFor(join.paper + join.floorEm))
    }
    if (mushafWhiteSum(joins, low) >= spreadEm) return low
    var high = maxOf(low + 1f, spreadEm + paperTotal)
    var opens = 0
    while (mushafWhiteSum(joins, high) < spreadEm && opens < 32) {
        high = low + (high - low) * 2f
        opens++
    }
    repeat(24) {
        val mid = (low + high) / 2f
        if (mushafWhiteSum(joins, mid) < spreadEm) low = mid else high = mid
    }
    return (low + high) / 2f
}

/**
 * How far the line's letters are narrowed, and whether it reaches its margins.
 *
 * The fit this replaces reserved a fixed word space per *gap between ink
 * boxes*, which is not a quantity the eye can see: on a join where the next
 * word tucks under an open ن, a fifth of an em between the boxes is half an em
 * of visible white, and on a join where two flat ends meet it is a fifth. So
 * lines were condensed to buy space they did not need, and every line paid for
 * it in letterform — measured over forty pages, only about half a page's lines
 * came through at their own width.
 *
 * Reserving the white each join actually needs instead, the same forty pages
 * leave seven lines in ten untouched, and the ones that do give way give up
 * less. The order is still a compositor's: the white is guaranteed first and
 * the letters yield to it, never the reverse.
 */
internal fun mushafInkLineFit(
    inkWidthPx: Float,
    joins: List<MushafInkJoin>,
    measureWidthPx: Float,
    fontPx: Float,
    allowShort: Boolean = false,
): MushafLineFit {
    if (inkWidthPx <= 0f || measureWidthPx <= 0f || fontPx <= 0f || joins.isEmpty()) {
        return MushafLineFit(scale = 1f, gapPx = MUSHAF_WORD_GAP_EM * fontPx, flush = false)
    }
    val ink = inkWidthPx / fontPx
    val measure = measureWidthPx / fontPx
    var tight = 0f
    for (join in joins) tight += join.fitFloorEm
    if (ink + tight > measure) {
        // Denser than the line's own white will allow: narrow the letters until
        // it is not, and stop at the point where narrowing itself starts to
        // read as a fault.
        val scale = (measure / (ink + tight)).coerceAtLeast(MUSHAF_MIN_LINE_SCALE)
        return MushafLineFit(scale = scale, gapPx = 0f, flush = true)
    }
    // Room to spare. Level the white as far as a line is set, and see what the
    // letters would have to do to take up whatever is still left.
    var opened = 0f
    for (join in joins) {
        opened += maxOf(join.whiteAt(MUSHAF_MAX_WHITE_LEVEL_EM) - join.paper, join.floorEm)
    }
    val needed = measure / (ink + opened)
    if (needed <= 1f) return MushafLineFit(scale = 1f, gapPx = 0f, flush = true)
    if (needed <= MUSHAF_MAX_LINE_SCALE) {
        return MushafLineFit(scale = needed, gapPx = 0f, flush = true)
    }
    // Width cannot tell a full row from a chapter's last. Only a known ending
    // may stand short; every other row keeps its margins after the letters
    // have reached their stretch limit.
    val stretched = mushafWhiteLevel(joins, measure / MUSHAF_MAX_LINE_SCALE - ink)
    if (!allowShort || stretched <= MUSHAF_STRETCH_WHITE_LEVEL_EM) {
        return MushafLineFit(scale = MUSHAF_MAX_LINE_SCALE, gapPx = 0f, flush = true)
    }
    return MushafLineFit(scale = 1f, gapPx = MUSHAF_WORD_GAP_EM * fontPx, flush = false)
}
