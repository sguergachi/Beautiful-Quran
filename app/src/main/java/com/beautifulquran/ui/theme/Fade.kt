package com.beautifulquran.ui.theme

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Letter reveal for the word being recited: a soft ink wash sweeps across the
 * glyphs (right-to-left for Arabic), so each letter breathes in to full ink
 * in turn. [progress] is 0..1 across the word and is read only at draw time —
 * the sweep animates every frame without a single recomposition or relayout.
 *
 * The wash is painterly rather than mechanical: across the feathered edge the
 * alpha follows a smootherstep curve (zero slope at both ends), so ink blooms
 * in with a soft toe and settles with a soft shoulder — a wash drawn onto
 * paper, not a wipe. The edge itself is wide, about half the word.
 *
 * Letters ahead of the wash rest at [restingAlpha] (the "upcoming" ink);
 * letters behind it are fully inked. The mask uses a small bleed around the
 * measured text box so Arabic glyphs that overhang their bounds are not clipped
 * while the offscreen wash is active. The layer is local to this word's draw
 * scope — it never paints onto neighbouring words.
 */
fun Modifier.letterFadeIn(
    progress: () -> Float,
    rtl: Boolean,
    restingAlpha: Float = 0.35f,
    /** Feather width relative to the word; see [InkWashFeather]. */
    feather: Float = InkWashFeather,
    /** How much of the word the wash may reveal; 1 reveals the whole word. */
    revealFraction: Float = 1f,
): Modifier {
    // Alpha profile across the feathered edge, sampled into gradient stops.
    // The seam-free smootherstep shape lives in [inkSmootherstep].
    val stops = FloatArray(InkProfileStops) { i -> i / (InkProfileStops - 1f) }
    val washColors = stops.map { t ->
        val s = inkSmootherstep(t)
        val a = restingAlpha + (1f - restingAlpha) * (if (rtl) s else 1f - s)
        Color.White.copy(alpha = a)
    }
    return drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        if (p >= 1f && revealFraction >= 1f) {
            drawContent()
            return@drawWithContent
        }
        val w = size.width
        if (w <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val bleed = FadeLayerBleed.toPx()
        drawIntoCanvas { canvas ->
            canvas.saveLayer(
                Rect(-bleed, -bleed, size.width + bleed, size.height + bleed),
                Paint(),
            )
        }
        drawContent()
            // The feather is far wider than the word itself, so every letter
            // spends most of the word's dwell time mid-bloom: the reveal is
            // closer to a whole-word breath than a moving edge, with only a
            // gentle directional lead in the reading direction. The wash
            // travels one edge-width past the end so the final letter
            // finishes exactly at p = 1.
            val edge = (w * feather).coerceAtLeast(1f)
            val head = p * (w * revealFraction.coerceIn(0f, 1f) + edge)
            val brush = if (rtl) {
                Brush.horizontalGradient(
                    colors = washColors,
                    startX = w - head,
                    endX = w - head + edge,
                )
            } else {
                Brush.horizontalGradient(
                    colors = washColors,
                    startX = head - edge,
                    endX = head,
                )
            }
        drawRect(
            brush = brush,
            topLeft = Offset(-bleed, -bleed),
            size = Size(size.width + bleed * 2f, size.height + bleed * 2f),
            blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}

/**
 * Draw-phase alpha for word-local text. Unlike a tight graphics layer, this
 * offscreen layer includes enough bleed for Latin serifs and Arabic marks that
 * paint beyond the word's advance, so animated ink never shears an overhang.
 */
fun Modifier.glyphLayerAlpha(alpha: () -> Float): Modifier = drawWithContent {
    val value = alpha().coerceIn(0f, 1f)
    if (value >= 1f) {
        drawContent()
        return@drawWithContent
    }
    if (value <= 0f) return@drawWithContent

    val bleed = FadeLayerBleed.toPx()
    drawIntoCanvas { canvas ->
        canvas.saveLayer(
            Rect(-bleed, -bleed, size.width + bleed, size.height + bleed),
            Paint().apply { this.alpha = value },
        )
    }
    drawContent()
    drawIntoCanvas { canvas -> canvas.restore() }
}

/**
 * One word-local bloom inside a larger shaped [Text] (Arabic-only / no-gloss).
 *
 * Constraints that ruled out earlier approaches:
 * - Per-glyph [SpanStyle]s flip Uthmanic Hafs joining (#133).
 * - A separate overlay [Text] of one word re-shapes in isolation (no fade).
 * - [drawText] with a [Color] argument does **not** override existing
 *   [SpanStyle] colours — painting over a transparent active span stays
 *   invisible until the word becomes recited.
 * - Inflated paper/orange rects bleed onto neighbours.
 * - Baking "upcoming" into span colours, or animating upcoming dim from 0,
 *   flashes the whole ayah full-ink when playback lands on it. Upcoming dim
 *   is a full-strength draw-phase cover from the first Upcoming frame.
 *
 * So every bloom operates on the ayah's already-shaped [TextLayoutResult],
 * clipped to [TextLayoutResult.getPathForRange]:
 * - [UpcomingDim]: static paper cover (padded horizontally beyond the
 *   selection box, but confined to its line so it cannot wash adjacent text).
 * - [InkReveal]: paper coverage of `1 − glyphAlpha` along the wash curve
 *   (same pad as UpcomingDim).
 * - [ColorReveal]: re-draw shaped glyphs, [BlendMode.SrcIn]-tint orange,
 *   then apply the [letterFadeIn] DstIn wash.
 */
sealed class ShapedWordBloom {
    abstract val range: IntRange

    /** Upcoming words: full-strength paper cover over full-ink glyphs from
     * the first frame the word is Upcoming — never animate up from 0 (that
     * briefly showed the whole unread ayah at full ink). */
    data class UpcomingDim(
        override val range: IntRange,
        val paper: Color,
        val coverAlpha: Float,
    ) : ShapedWordBloom()

    /** First-pass ink: paper cover over full-ink glyphs, wash from
     * [restingAlpha] → 1 (same curve as [letterFadeIn]). [feather] overrides
     * the modifier-level feather when set — a tajweed-paced word narrows its
     * edge so letter dwell stays visible. */
    data class InkReveal(
        override val range: IntRange,
        val progress: Float,
        val paper: Color,
        val restingAlpha: Float,
        val feather: Float? = null,
    ) : ShapedWordBloom()

    /** Tinted ink (orange repeat, white-gold glint): shaped glyphs tinted to
     * [color], wash from [restingAlpha] → 1, then dissolve via [layerAlpha].
     * [feather] overrides the modifier-level feather when set, same as
     * [InkReveal] — a tinted wash riding a paced sweep must share its edge. */
    data class ColorReveal(
        override val range: IntRange,
        val progress: Float,
        val color: Color,
        val restingAlpha: Float = 0f,
        val layerAlpha: Float = 1f,
        val feather: Float? = null,
        val colorAlpha: Float = 1f,
        /** Subtle blurred glyph-outline halo used by Nightfall's glimmer. */
        val glowAlpha: Float = 0f,
        val glowRadius: Float = 3.5f,
        /** How much of the word the directional mask may reveal. */
        val revealFraction: Float = 1f,
    ) : ShapedWordBloom()
}

/**
 * Draw-phase blooms for word(s) inside a shaped ayah [Text]. Layers are read
 * only at draw time so the sweep never recomposes or reshapes the ayah.
 */
fun Modifier.shapedWordBloom(
    blooms: () -> List<ShapedWordBloom>,
    layout: () -> TextLayoutResult?,
    rtl: Boolean,
    /** Feather width relative to the word; see [InkWashFeather]. */
    feather: Float = InkWashFeather,
    /**
     * How far a word's paper mask reaches past its box, for glyph overhang.
     *
     * Right when the words of a line share one [Text] — the mask covers its
     * own word's overhang and any neighbour ink it laps is redrawn by the same
     * text pass. Wrong when each word is its own node, as on the mushaf leaf:
     * there the mask is the last thing drawn over that word's box, so the reach
     * paints paper across the neighbouring word's ink and its ayah mark, and
     * the page looks chewed at every word boundary while it recites. Those
     * callers pass zero.
     */
    coverPad: Dp = PaperCoverPad,
    /**
     * Whether a tinted wash is clipped to its own range's selection path.
     *
     * [TextLayoutResult.getPathForRange] encloses the *selection*, not the
     * letterforms: it stops at the word's advance and its line box. Where a
     * line's words share one [Text] that clip is what keeps the orange off the
     * word beside it, and the neighbour redraws over anything it laps.
     *
     * On the mushaf leaf each word is its own node, and a QCF glyph inks past
     * its advance — a tail sweeping under the word before it, a mark riding
     * high. Clipped there, the tint stopped at that box while the letter kept
     * going, so a repeat coloured the middle of a word and left its overhang
     * black along a straight edge. Those callers pass false: the layer holds
     * only this word, the tint is [BlendMode.SrcIn] against the word's own
     * redrawn glyphs, and so it is masked by the letterform exactly as the
     * first-pass wash is ([Modifier.letterFadeIn]).
     */
    clipTintToRange: Boolean = true,
): Modifier {
    val stops = FloatArray(InkProfileStops) { i -> i / (InkProfileStops - 1f) }
    val lineBoundsCache = LineBoundsCache()
    val glyphHaloCache = GlyphHaloCache()
    return drawWithContent {
        drawContent()
        val textLayout = layout() ?: return@drawWithContent
        val bleed = FadeLayerBleed.toPx()
        blooms().forEach { bloom ->
            val range = bloom.range
            if (range.isEmpty()) return@forEach
            val length = textLayout.layoutInput.text.length
            if (length == 0) return@forEach
            val start = range.first.coerceIn(0, length)
            val endExclusive = (range.last + 1).coerceIn(start, length)
            if (endExclusive <= start) return@forEach

            // Each bloom kind derives only the geometry it paints with:
            // the paper covers work off line boxes (memoised — they change on
            // relayout, not as the wash advances), and only [ColorReveal] needs
            // the shaped glyph path. Deriving both for every bloom meant a long
            // ayah rebuilt ~2 paths per word on every animation frame.
            when (bloom) {
                is ShapedWordBloom.UpcomingDim -> {
                    val a = bloom.coverAlpha.coerceIn(0f, 1f)
                    if (a <= 0f) return@forEach
                    val lineBounds = lineBoundsCache.boundsFor(textLayout, start, endExclusive)
                    // The bounds already span the layout's full line height.
                    // Bleed only horizontally: vertical padding lets an
                    // unread line's paper mask climb into the preceding line
                    // and fade a read word's descender (g/j/p/q/y).
                    val pad = coverPad.toPx()
                    lineBounds.forEach { bounds ->
                        val cover = linePaperCoverBounds(bounds, pad)
                        clipRect(
                            left = cover.left,
                            top = cover.top,
                            right = cover.right,
                            bottom = cover.bottom,
                        ) {
                            drawRect(
                                color = bloom.paper.copy(alpha = a),
                                topLeft = Offset(cover.left, cover.top),
                                size = Size(cover.width, cover.height),
                            )
                        }
                    }
                }
                is ShapedWordBloom.InkReveal -> {
                    // Full ink is already on the page; pull paper back along the
                    // wash so glyphs breathe in. Padded clip covers mark/AA
                    // overhangs (same as UpcomingDim).
                    val p = bloom.progress.coerceIn(0f, 1f)
                    if (p >= 1f) return@forEach
                    val lineBounds = lineBoundsCache.boundsFor(textLayout, start, endExclusive)
                    val paperColors = stops.map { t ->
                        val s = inkSmootherstep(t)
                        val glyphAlpha = bloom.restingAlpha +
                            (1f - bloom.restingAlpha) * (if (rtl) s else 1f - s)
                        bloom.paper.copy(alpha = (1f - glyphAlpha).coerceIn(0f, 1f))
                    }
                    val pad = coverPad.toPx()
                    lineBounds.forEach { bounds ->
                        val cover = linePaperCoverBounds(bounds, pad)
                        // The clip/draw rect already includes [pad] for glyph
                        // overhang, so the wash must use that same geometry.
                        // Otherwise an end glyph such as EB Garamond's “g”
                        // sits outside the calculated width: its bowl reveals
                        // while the right-swept descender remains faint until
                        // progress snaps to 1.
                        val washLeft = cover.left
                        val w = cover.width
                        val edge = (w * (bloom.feather ?: feather)).coerceAtLeast(1f)
                        val head = p * (w + edge)
                        val brush = if (rtl) {
                            Brush.horizontalGradient(
                                colors = paperColors,
                                startX = washLeft + (w - head),
                                endX = washLeft + (w - head) + edge,
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = paperColors,
                                startX = washLeft + head - edge,
                                endX = washLeft + head,
                            )
                        }
                        clipRect(
                            left = cover.left,
                            top = cover.top,
                            right = cover.right,
                            bottom = cover.bottom,
                        ) {
                            drawRect(
                                brush = brush,
                                topLeft = Offset(washLeft, cover.top),
                                size = Size(w, cover.height),
                            )
                        }
                    }
                }
                is ShapedWordBloom.ColorReveal -> {
                    if (bloom.layerAlpha <= 0f) return@forEach
                    // Re-draw the same shaped glyphs, tint them orange with
                    // SrcIn (keeps harf shapes), then DstIn-wash like letterFadeIn.
                    // The glyph path is needed here and nowhere else.
                    val path = textLayout.getPathForRange(start, endExclusive)
                    val p = bloom.progress.coerceIn(0f, 1f)
                    val bounds = path.getBounds()
                    if (bounds.isEmpty || bounds.width <= 0f) return@forEach
                    val w = bounds.width
                    val edge = (w * (bloom.feather ?: feather)).coerceAtLeast(1f)
                    val head = p *
                        (w * bloom.revealFraction.coerceIn(0f, 1f) + edge)
                    val colorBleed = maxOf(
                        bleed,
                        bloom.glowRadius.dp.toPx() * 3f,
                    )
                    val washColors = stops.map { t ->
                        val s = inkSmootherstep(t)
                        val a = bloom.restingAlpha +
                            (1f - bloom.restingAlpha) * (if (rtl) s else 1f - s)
                        Color.White.copy(alpha = a)
                    }
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(
                            Rect(
                                bounds.left - colorBleed,
                                bounds.top - colorBleed,
                                bounds.right + colorBleed,
                                bounds.bottom + colorBleed,
                            ),
                            Paint(),
                        )
                    }
                    // Glow rides the same DstIn directional wash as the tint —
                    // do not also multiply by smootherstep(p): that whole-word
                    // gate kept the halo invisible until the bloom finished.
                    val glowAlpha = bloom.layerAlpha.coerceIn(0f, 1f) *
                        bloom.glowAlpha.coerceIn(0f, 1f)
                    if (glowAlpha > 0f) {
                        val halo = glyphHaloCache.haloFor(
                            textLayout = textLayout,
                            start = start,
                            endExclusive = endExclusive,
                            radiusPx = bloom.glowRadius.dp.toPx(),
                        )
                        if (halo != null) {
                            val glowPaint = android.graphics.Paint(
                                android.graphics.Paint.ANTI_ALIAS_FLAG,
                            ).apply {
                                color = bloom.color.copy(alpha = glowAlpha).toArgb()
                            }
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawBitmap(
                                    halo.bitmap,
                                    halo.left,
                                    halo.top,
                                    glowPaint,
                                )
                            }
                        }
                    }
                    val tint = bloom.color.copy(
                        alpha = bloom.layerAlpha.coerceIn(0f, 1f) *
                            bloom.colorAlpha.coerceIn(0f, 1f),
                    )
                    if (clipTintToRange) {
                        clipPath(path) {
                            drawText(textLayoutResult = textLayout)
                            drawRect(
                                color = tint,
                                topLeft = Offset(bounds.left, bounds.top),
                                size = Size(bounds.width, bounds.height),
                                blendMode = BlendMode.SrcIn,
                            )
                        }
                    } else {
                        // Unclipped, the tint reaches every pixel the word
                        // actually inks: SrcIn writes only where the redrawn
                        // glyphs are, so the letterform is the mask and the
                        // overhang colours with the stroke it belongs to.
                        drawText(textLayoutResult = textLayout)
                        drawRect(
                            color = tint,
                            topLeft = Offset(
                                bounds.left - colorBleed,
                                bounds.top - colorBleed,
                            ),
                            size = Size(
                                bounds.width + colorBleed * 2f,
                                bounds.height + colorBleed * 2f,
                            ),
                            blendMode = BlendMode.SrcIn,
                        )
                    }
                    if (p < 1f || bloom.revealFraction < 1f) {
                        val brush = if (rtl) {
                            Brush.horizontalGradient(
                                colors = washColors,
                                startX = bounds.left + (w - head),
                                endX = bounds.left + (w - head) + edge,
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = washColors,
                                startX = bounds.left + head - edge,
                                endX = bounds.left + head,
                            )
                        }
                        // The wash has to reach as far as the tint did, or
                        // an overhang keeps ink the sweep has not arrived at.
                        val washBleed = if (clipTintToRange) bleed else colorBleed
                        drawRect(
                            brush = brush,
                            topLeft = Offset(
                                bounds.left - washBleed,
                                bounds.top - washBleed,
                            ),
                            size = Size(
                                bounds.width + washBleed * 2f,
                                bounds.height + washBleed * 2f,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    drawIntoCanvas { canvas -> canvas.restore() }
                }
            }
        }
    }
}

/**
 * Per-word line boxes for one [TextLayoutResult], memoised across draw frames.
 *
 * The boxes are pure geometry of (layout, character range): they move only when
 * the ayah re-lays out, never as the wash advances. Without this, a recessed
 * 128-word ayah re-derived every word's boxes on every frame of the active
 * word's sweep — the wash itself is untouched, only the arithmetic behind it.
 * Bounded by the word count of one ayah and dropped whole when the layout
 * changes identity (font scale, width, text).
 */
private class LineBoundsCache {
    private var layout: TextLayoutResult? = null
    private val byRange = HashMap<Long, List<Rect>>()

    fun boundsFor(textLayout: TextLayoutResult, start: Int, endExclusive: Int): List<Rect> {
        if (layout !== textLayout) {
            layout = textLayout
            byRange.clear()
        }
        val key = (start.toLong() shl 32) or endExclusive.toLong()
        byRange[key]?.let { return it }
        return computeLineBounds(textLayout, start, endExclusive).also { byRange[key] = it }
    }
}

/**
 * Small LRU of blurred alpha masks made from the laid-out glyphs themselves.
 *
 * [TextLayoutResult.getPathForRange] is a selection enclosure, not a glyph
 * outline. Blurring it produces the rounded word-sized rectangle this halo is
 * specifically meant to avoid. Render the selected glyphs into an alpha mask
 * once per word/radius instead; every animation frame then draws one tiny
 * cached bitmap with only its colour and lifecycle alpha changing.
 */
private class GlyphHaloCache {
    private data class Key(
        val start: Int,
        val endExclusive: Int,
        val radiusBits: Int,
    )

    data class Halo(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
    )

    private var layout: TextLayoutResult? = null
    private val byRange = object : LinkedHashMap<Key, Halo>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Halo>?): Boolean =
            size > 8
    }

    fun haloFor(
        textLayout: TextLayoutResult,
        start: Int,
        endExclusive: Int,
        radiusPx: Float,
    ): Halo? {
        if (radiusPx <= 0f) return null
        if (layout !== textLayout) {
            layout = textLayout
            byRange.clear()
        }
        val key = Key(start, endExclusive, radiusPx.toBits())
        byRange[key]?.let { return it }

        val path = textLayout.getPathForRange(start, endExclusive)
        val bounds = path.getBounds()
        if (bounds.isEmpty || bounds.width <= 0f || bounds.height <= 0f) return null
        val left = floor(bounds.left).toInt()
        val top = floor(bounds.top).toInt()
        val width = (ceil(bounds.right).toInt() - left).coerceAtLeast(1)
        val height = (ceil(bounds.bottom).toInt() - top).coerceAtLeast(1)
        val glyphs = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val glyphCanvas = Canvas(android.graphics.Canvas(glyphs))
        glyphCanvas.translate(-left.toFloat(), -top.toFloat())
        glyphCanvas.clipPath(path)
        textLayout.multiParagraph.paint(glyphCanvas)

        val offset = IntArray(2)
        val blurred = glyphs.extractAlpha(
            android.graphics.Paint().apply {
                maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
            },
            offset,
        )
        glyphs.recycle()
        return Halo(
            bitmap = blurred,
            left = (left + offset[0]).toFloat(),
            top = (top + offset[1]).toFloat(),
        ).also { byRange[key] = it }
    }
}

/** Word-local horizontal bounds per line, at the layout's full line height. */
private fun computeLineBounds(
    textLayout: TextLayoutResult,
    start: Int,
    endExclusive: Int,
): List<Rect> = buildList {
    val firstLine = textLayout.getLineForOffset(start)
    val lastLine = textLayout.getLineForOffset((endExclusive - 1).coerceAtLeast(start))
    for (line in firstLine..lastLine) {
        val lineStart = maxOf(start, textLayout.getLineStart(line))
        val lineEnd = minOf(endExclusive, textLayout.getLineEnd(line, visibleEnd = true))
        if (lineEnd <= lineStart) continue
        val selectionBounds = textLayout.getPathForRange(lineStart, lineEnd).getBounds()
        if (!selectionBounds.isEmpty && selectionBounds.width > 0f) {
            // Selection paths can stop above a Latin descender at a wrapped
            // range edge. Keep the word-local horizontal bounds, but cover the
            // text layout's full line height so g/j/p/q/y never escape the
            // upcoming-ink mask.
            add(
                Rect(
                    left = selectionBounds.left,
                    top = textLayout.getLineTop(line),
                    right = selectionBounds.right,
                    bottom = textLayout.getLineBottom(line),
                ),
            )
        }
    }
}

private const val InkProfileStops = 9
/** Extra room around this word's measured box so Hafs marks/overhangs aren't
 * clipped by the offscreen [letterFadeIn] / [shapedWordBloom] mask. Local to
 * the word's draw scope — does not paint onto neighbours. */
private val FadeLayerBleed = 14.dp

/** Visible but still ink-like halo around Nightfall's active glimmer. */
/** Horizontal pad beyond [TextLayoutResult.getPathForRange] when painting
 * paper covers. Vertical expansion is forbidden because it masks glyphs on
 * adjacent lines. */
/** How far a word's paper mask reaches past its box, for glyph overhang. */
internal val PaperCoverPad = 4.dp

/** Expands a word mask for horizontal glyph overhang without crossing its line. */
internal fun linePaperCoverBounds(lineBounds: Rect, horizontalPad: Float): Rect =
    Rect(
        left = lineBounds.left - horizontalPad,
        top = lineBounds.top,
        right = lineBounds.right + horizontalPad,
        bottom = lineBounds.bottom,
    )

// The ink wash feathers over 1.6× the word's own width, so the reveal reads as a
// whole-word breath with a gentle directional lead rather than a hard moving
// edge. The wash head therefore travels the word plus that feather (see below).
internal const val InkWashFeather = 1.6f

/**
 * smootherstep (6t⁵−15t⁴+10t³): zero first *and* second derivative at both ends,
 * so ink blooms in with a soft toe and settles with a soft shoulder — no seam
 * where the wash meets the resting or the fully inked letters. The easing shape
 * behind the per-letter [letterFadeIn] gradient wash.
 */
internal fun inkSmootherstep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

private const val InkWashSpan = 1f + InkWashFeather

/**
 * The ink-wash alpha for a glyph at reading-direction fraction [pos] (0 at the
 * first-revealed letter, 1 at the last) while the word's wash is at [progress].
 *
 * Same bloom [letterFadeIn] paints as a moving gradient mask, sampled per
 * character. Kept for tests and for any caller that needs the curve as a
 * scalar rather than a draw-phase brush.
 */
fun inkWashAlpha(pos: Float, progress: Float, restingAlpha: Float): Float {
    val t = (InkWashSpan * progress - pos) / InkWashFeather
    return restingAlpha + (1f - restingAlpha) * inkSmootherstep(t)
}

/**
 * Softly dissolves the content at its top and bottom edges, so scrolling
 * feels like ink fading off a single sheet of paper.
 *
 * Pair with scroll `contentPadding` (or leading/trailing spacers) of at
 * least [top] / [bottom] so the first and last ink sit clear of the dissolve
 * at rest; only as the user scrolls does content pass under the soft edge.
 *
 * [topInset] / [bottomInset] paint opaque paper bands outside the soft
 * gradients — used to lift a fade above chrome that sits on the same sheet
 * (status bar on the reader, floating playback on the cover).
 *
 * Implementation note: because every sheet sits on a solid paper color, the
 * fade is drawn as a cheap gradient overlay of that color on top of the
 * content. This costs one rect per edge in the draw phase — no offscreen
 * compositing layer, no alpha mask — which keeps scrolling at the display's
 * native refresh rate even on modest GPUs.
 */
fun Modifier.verticalFadingEdges(
    color: Color,
    top: Dp = 28.dp,
    bottom: Dp = 56.dp,
    topInset: Dp = 0.dp,
    /** Opaque paper band under the bottom fade — lifts the soft edge above a
     *  bottom chrome strip (e.g. the cover sheet's floating playback bar). */
    bottomInset: Dp = 0.dp,
): Modifier = drawWithContent {
    drawContent()
    val topInsetPx = topInset.toPx()
    if (topInsetPx > 0f) {
        drawRect(
            color = color,
            topLeft = Offset.Zero,
            size = Size(size.width, topInsetPx),
        )
    }
    val topPx = top.toPx()
    if (topPx > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(color, color.copy(alpha = 0f)),
                startY = topInsetPx,
                endY = topInsetPx + topPx,
            ),
            topLeft = Offset(0f, topInsetPx),
            size = Size(size.width, topPx),
        )
    }
    val bottomInsetPx = bottomInset.toPx()
    if (bottomInsetPx > 0f) {
        drawRect(
            color = color,
            topLeft = Offset(0f, size.height - bottomInsetPx),
            size = Size(size.width, bottomInsetPx),
        )
    }
    val bottomPx = bottom.toPx()
    if (bottomPx > 0f) {
        val fadeBottom = size.height - bottomInsetPx
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0f), color),
                startY = fadeBottom - bottomPx,
                endY = fadeBottom,
            ),
            topLeft = Offset(0f, fadeBottom - bottomPx),
            size = Size(size.width, bottomPx),
        )
    }
}
