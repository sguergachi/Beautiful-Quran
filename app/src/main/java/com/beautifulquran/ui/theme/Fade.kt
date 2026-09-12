package com.beautifulquran.ui.theme

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
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
    // The ramp never changes shape while a word washes — only its head moves.
    // See [WashBrushCache] for why a fresh Brush each frame is expensive.
    var cachedBrush: Brush? = null
    var cachedEdge = Float.NaN
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
            val brush = cachedBrush?.takeIf { cachedEdge == edge }
                ?: Brush.horizontalGradient(
                    colors = washColors,
                    startX = 0f,
                    endX = edge,
                ).also {
                    cachedBrush = it
                    cachedEdge = edge
                }
            // Built at the origin, so the canvas moves under it rather than the
            // gradient moving across the canvas. TileMode.Clamp extends the end
            // colours over the rest of the rect exactly as the absolute form did.
            val headX = if (rtl) w - head else head - edge
        translate(left = headX, top = 0f) {
            drawRect(
                brush = brush,
                topLeft = Offset(-bleed - headX, -bleed),
                size = Size(size.width + bleed * 2f, size.height + bleed * 2f),
                blendMode = BlendMode.DstIn,
            )
        }
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
 * - [UpcomingDim]: punches the glyph layer over the word's line box
 *   (padded horizontally, confined to its line) so unread ink reveals
 *   the paper already there — the page, or a gather stain.
 * - [InkReveal]: same punch, coverage `1 − glyphAlpha` along the wash curve.
 * - [ColorReveal]: re-draw shaped glyphs, [BlendMode.SrcIn]-tint orange,
 *   then apply the [letterFadeIn] DstIn wash.
 */
sealed class ShapedWordBloom {
    abstract val range: IntRange

    /** Upcoming words: punch unread glyphs from the first Upcoming frame —
     * never animate the cover up from 0 (that briefly showed the whole
     * unread ayah at full ink). */
    data class UpcomingDim(
        override val range: IntRange,
        val paper: Color,
        val coverAlpha: Float,
        /**
         * Overrides the modifier's `coverPad` for this cover alone.
         *
         * The pad exists so a *word's* mask reaches the glyph overhang its box
         * does not contain, and its neighbours are words that redraw over
         * anything it laps. The ﴿N﴾ mark is the one cover with nothing to
         * redraw after it: it fades on its own clock while the ink either side
         * of it holds still, so the reach pulled 4 dp of a held word's tail
         * down and back up with the number. Zero here keeps the mark's fade to
         * the mark.
         */
        val pad: Dp? = null,
    ) : ShapedWordBloom()

    /** First-pass ink: punch over full-ink glyphs, wash from
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

/** Unread covers punch the glyph layer ([BlendMode.DstOut]) so the wash
 * reveals whatever is already on the paper. Painting page colour would
 * cut a cream hole in a gather stain. */
internal fun paperCoverBlendMode(punchToBackdrop: Boolean): BlendMode =
    if (punchToBackdrop) BlendMode.DstOut else BlendMode.SrcOver

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
    /**
     * Whether the text is set `TextAlign.Justify`.
     *
     * It changes where the ink *is*, not merely how it looks: a justified line
     * is stretched to the measure after it is measured, and the selection path
     * this works from never learns of it. See [justifyShift]. Every caller that
     * sets ragged or centred text leaves this false and is unaffected.
     */
    justified: Boolean = false,
    /**
     * The advance of the hyphen the breaker draws, in the same hand as the text.
     *
     * Only a [justified] and hyphenating caller needs it; see [justifyShift].
     */
    hyphenPx: Float = 0f,
): Modifier {
    val stops = FloatArray(InkProfileStops) { i -> i / (InkProfileStops - 1f) }
    val lineBoundsCache = LineBoundsCache(justified, hyphenPx)
    val glyphHaloCache = GlyphHaloCache()
    val glyphPathCache = GlyphPathCache()
    val washBrushCache = WashBrushCache(rtl, stops)
    return drawWithContent {
        val bloomList = blooms()
        val punchLayer = bloomList.any { bloom ->
            when (bloom) {
                is ShapedWordBloom.UpcomingDim -> bloom.coverAlpha > 0f
                is ShapedWordBloom.InkReveal -> bloom.progress < 1f
                is ShapedWordBloom.ColorReveal -> false
            }
        }
        val coverBlend = paperCoverBlendMode(punchLayer)
        if (punchLayer) {
            val extra = maxOf(FadeLayerBleed.toPx(), coverPad.toPx())
            drawIntoCanvas { canvas ->
                canvas.saveLayer(
                    Rect(-extra, -extra, size.width + extra, size.height + extra),
                    Paint(),
                )
            }
        }
        try {
            drawContent()
        val textLayout = layout() ?: return@drawWithContent
        val bleed = FadeLayerBleed.toPx()
        bloomList.forEach { bloom ->
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
                    val pad = (bloom.pad ?: coverPad).toPx()
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
                                blendMode = coverBlend,
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
                    val pad = coverPad.toPx()
                    // One wash across the whole range, however many lines it
                    // is set over.
                    //
                    // A word is one line's worth of ink, so for every caller
                    // that draws words this is a single cover and the arithmetic
                    // below collapses to what it always was. A range that *is*
                    // set over several lines — a verse of English prose, which
                    // is one reveal spanning a paragraph — must be washed in
                    // reading order: the wash crosses line one, then line two.
                    // Giving each line the same progress lit the whole block at
                    // once, which is the one thing the ink is not allowed to do.
                    // Summed off the cached line boxes instead of a fresh list
                    // of padded covers: [linePaperCoverBounds] widens each box by
                    // [pad] on both sides and changes nothing else.
                    var total = 0f
                    for (i in lineBounds.indices) {
                        total += lineBounds[i].width + pad * 2f
                    }
                    total = total.coerceAtLeast(1f)
                    val edge = (total * (bloom.feather ?: feather)).coerceAtLeast(1f)
                    val brush = washBrushCache.brushFor(bloom.paper, bloom.restingAlpha, edge)
                    val head = p * (total + edge)
                    var travelled = 0f
                    for (i in lineBounds.indices) {
                        val cover = linePaperCoverBounds(lineBounds[i], pad)
                        // The clip/draw rect already includes [pad] for glyph
                        // overhang, so the wash must use that same geometry.
                        // Otherwise an end glyph such as EB Garamond's “g”
                        // sits outside the calculated width: its bowl reveals
                        // while the right-swept descender remains faint until
                        // progress snaps to 1.
                        val washLeft = cover.left
                        val w = cover.width
                        // Where the head stands within this line, measured from
                        // the line's own leading edge.
                        val local = head - travelled
                        travelled += w
                        val headX = if (rtl) {
                            washLeft + (w - local)
                        } else {
                            washLeft + local - edge
                        }
                        clipRect(
                            left = cover.left,
                            top = cover.top,
                            right = cover.right,
                            bottom = cover.bottom,
                        ) {
                            translate(left = headX, top = 0f) {
                                drawRect(
                                    brush = brush,
                                    topLeft = Offset(washLeft - headX, cover.top),
                                    size = Size(w, cover.height),
                                    blendMode = coverBlend,
                                )
                            }
                        }
                    }
                }
                is ShapedWordBloom.ColorReveal -> {
                    if (bloom.layerAlpha <= 0f) return@forEach
                    // Re-draw the same shaped glyphs, tint them orange with
                    // SrcIn (keeps harf shapes), then DstIn-wash like letterFadeIn.
                    // The glyph path is needed here and nowhere else.
                    val shaped = glyphPathCache.shapedFor(textLayout, start, endExclusive)
                    val path = shaped.path
                    val p = bloom.progress.coerceIn(0f, 1f)
                    val bounds = shaped.bounds
                    if (bounds.isEmpty || bounds.width <= 0f) return@forEach
                    val colorBleed = maxOf(
                        bleed,
                        bloom.glowRadius.dp.toPx() * 3f,
                    )
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
                            // Same fence as the tint: where the node holds one
                            // word, the selection path is not its silhouette.
                            clipPath = path.takeIf { clipTintToRange },
                            overhangPx = bleed,
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
                        // One wash across the whole range, however many lines it
                        // is set over — the same reading-order travel InkReveal
                        // uses. A hyphenated word sets its fragments on two
                        // lines, and the union bounds of that span the width of
                        // the whole line: sweeping the union lit the gap between
                        // the fragments and washed both at once. Each line takes
                        // its own share of the head instead.
                        val tintBounds = lineBoundsCache.boundsFor(
                            textLayout,
                            start,
                            endExclusive,
                        )
                        // The wash has to reach as far as the tint did, or
                        // an overhang keeps ink the sweep has not arrived at.
                        val washBleed = if (clipTintToRange) bleed else colorBleed
                        var total = 0f
                        for (i in tintBounds.indices) {
                            total += tintBounds[i].width + washBleed * 2f
                        }
                        total = total.coerceAtLeast(1f)
                        val lineEdge =
                            (total * (bloom.feather ?: feather)).coerceAtLeast(1f)
                        // The white DstIn ramp — paper null. See [WashBrushCache].
                        val brush =
                            washBrushCache.brushFor(null, bloom.restingAlpha, lineEdge)
                        val lineHead = p *
                            (total * bloom.revealFraction.coerceIn(0f, 1f) + lineEdge)
                        var travelled = 0f
                        for (i in tintBounds.indices) {
                            val lineBox = tintBounds[i]
                            val cover = Rect(
                                left = lineBox.left - washBleed,
                                top = lineBox.top,
                                right = lineBox.right + washBleed,
                                bottom = lineBox.bottom,
                            )
                            val wLine = cover.width
                            val local = lineHead - travelled
                            travelled += wLine
                            val headX = if (rtl) {
                                cover.right - local
                            } else {
                                cover.left + local - lineEdge
                            }
                            clipRect(
                                left = cover.left,
                                top = cover.top,
                                right = cover.right,
                                bottom = cover.bottom,
                            ) {
                                translate(left = headX, top = 0f) {
                                    drawRect(
                                        brush = brush,
                                        topLeft = Offset(cover.left - headX, cover.top),
                                        size = Size(cover.width, cover.height),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            }
                        }
                    }
                    drawIntoCanvas { canvas -> canvas.restore() }
                }
            }
        }
        } finally {
            if (punchLayer) {
                drawIntoCanvas { canvas -> canvas.restore() }
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
private class LineBoundsCache(private val justified: Boolean, private val hyphenPx: Float) {
    private var layout: TextLayoutResult? = null
    private val byRange = HashMap<Long, List<Rect>>()

    fun boundsFor(textLayout: TextLayoutResult, start: Int, endExclusive: Int): List<Rect> {
        if (layout !== textLayout) {
            layout = textLayout
            byRange.clear()
        }
        val key = (start.toLong() shl 32) or endExclusive.toLong()
        byRange[key]?.let { return it }
        return computeLineBounds(textLayout, start, endExclusive, justified, hyphenPx)
            .also { byRange[key] = it }
    }
}

/**
 * Shaped glyph outlines for one [TextLayoutResult], memoised across draw frames.
 *
 * [TextLayoutResult.getPathForRange] walks the range's glyph runs and builds a
 * native [Path] on every call, and [ShapedWordBloom.ColorReveal] needs one on
 * every frame of the glint — so the active word rebuilt its outline at the
 * refresh rate for a shape that only moves when the ayah re-lays out. Same
 * lifetime rule as [LineBoundsCache] (dropped whole when the layout changes
 * identity), but LRU-bounded: a path holds native memory, and a search flash
 * can tint every word of a verse at once.
 */
private class GlyphPathCache {
    class Shaped(val path: Path, val bounds: Rect)

    private var layout: TextLayoutResult? = null
    private val byRange = object : LinkedHashMap<Long, Shaped>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Shaped>?): Boolean =
            size > 16
    }

    fun shapedFor(textLayout: TextLayoutResult, start: Int, endExclusive: Int): Shaped {
        if (layout !== textLayout) {
            layout = textLayout
            byRange.clear()
        }
        val key = (start.toLong() shl 32) or endExclusive.toLong()
        byRange[key]?.let { return it }
        val path = textLayout.getPathForRange(start, endExclusive)
        return Shaped(path, path.getBounds()).also { byRange[key] = it }
    }
}

/**
 * Directional wash ramps, built once and reused across draw frames.
 *
 * A wash head moves every frame, and expressing that as the gradient's own
 * `startX`/`endX` meant a new [Brush] per line per bloom per frame. That is
 * worse than the object churn suggests: [androidx.compose.ui.graphics.ShaderBrush]
 * caches its native shader *on the brush instance*, so a fresh instance threw
 * the cache away and allocated an `android.graphics.LinearGradient` every time.
 *
 * The ramp itself is stable — the resting alpha is fixed per bloom kind and the
 * feather is locked when a word goes Active — so it is built once spanning
 * `0 → edge` at the origin, and callers [translate] the canvas to put that span
 * where the head stands. `TileMode.Clamp` extends the end colours over the rest
 * of the rect exactly as the absolute form did, so the pixels are unchanged.
 *
 * [paper] is the colour the ramp covers glyphs with ([ShapedWordBloom.InkReveal]);
 * null asks for the white `DstIn` mask ([ShapedWordBloom.ColorReveal]).
 */
private class WashBrushCache(private val rtl: Boolean, private val stops: FloatArray) {
    private data class Key(val paper: Color?, val restingAlphaBits: Int, val edgeBits: Int)

    private val byKey = object : LinkedHashMap<Key, Brush>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Brush>?): Boolean =
            size > 8
    }

    fun brushFor(paper: Color?, restingAlpha: Float, edge: Float): Brush {
        val key = Key(paper, restingAlpha.toBits(), edge.toBits())
        byKey[key]?.let { return it }
        val colors = stops.map { t ->
            val s = inkSmootherstep(t)
            val glyphAlpha = restingAlpha + (1f - restingAlpha) * (if (rtl) s else 1f - s)
            if (paper == null) {
                Color.White.copy(alpha = glyphAlpha)
            } else {
                paper.copy(alpha = (1f - glyphAlpha).coerceIn(0f, 1f))
            }
        }
        return Brush.horizontalGradient(colors = colors, startX = 0f, endX = edge)
            .also { byKey[key] = it }
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
        val clipped: Boolean,
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

    /**
     * [clipPath] is the range's selection path where words share one layout: it
     * is the only thing holding this word's light off the word beside it, and
     * the neighbour redraws over anything it laps.
     *
     * Null where the node holds a single word, as on the mushaf leaf. A QCF
     * glyph inks past its advance — a tail sweeping under the word before it, a
     * mark riding high — and the selection stops at that advance, so masking
     * the halo with it cut the tail and the mark out of the light and left a
     * straight edge down the side of the glow. `docs/GLIMMER.md` forbids
     * exactly that box edge, and it is the same reason the tint is unclipped
     * there (see [Modifier.shapedWordBloom]). Unclipped, the mask is the node's
     * own drawing widened by [overhangPx] for the ink that leaves its box.
     */
    fun haloFor(
        textLayout: TextLayoutResult,
        start: Int,
        endExclusive: Int,
        radiusPx: Float,
        clipPath: Path?,
        overhangPx: Float,
    ): Halo? {
        if (radiusPx <= 0f) return null
        if (layout !== textLayout) {
            layout = textLayout
            byRange.clear()
        }
        val key = Key(start, endExclusive, radiusPx.toBits(), clipPath != null)
        byRange[key]?.let { return it }

        val bounds = if (clipPath != null) {
            clipPath.getBounds()
        } else {
            Rect(
                -overhangPx,
                -overhangPx,
                textLayout.size.width + overhangPx,
                textLayout.size.height + overhangPx,
            )
        }
        if (bounds.isEmpty || bounds.width <= 0f || bounds.height <= 0f) return null
        val left = floor(bounds.left).toInt()
        val top = floor(bounds.top).toInt()
        val width = (ceil(bounds.right).toInt() - left).coerceAtLeast(1)
        val height = (ceil(bounds.bottom).toInt() - top).coerceAtLeast(1)
        val glyphs = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val glyphCanvas = Canvas(android.graphics.Canvas(glyphs))
        glyphCanvas.translate(-left.toFloat(), -top.toFloat())
        if (clipPath != null) glyphCanvas.clipPath(clipPath)
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
    justified: Boolean,
    hyphenPx: Float,
): List<Rect> = buildList {
    val firstLine = textLayout.getLineForOffset(start)
    val lastLine = textLayout.getLineForOffset((endExclusive - 1).coerceAtLeast(start))
    for (line in firstLine..lastLine) {
        val layoutStart = textLayout.getLineStart(line)
        val layoutEnd = textLayout.getLineEnd(line, visibleEnd = true)
        val lineStart = maxOf(start, layoutStart)
        val lineEnd = minOf(endExclusive, layoutEnd)
        if (lineEnd <= lineStart) continue
        val selectionBounds = lineSelectionBounds(textLayout, line, lineStart, lineEnd)
        if (!selectionBounds.isEmpty && selectionBounds.width > 0f) {
            val shift = if (justified) {
                justifyShift(textLayout, line, layoutStart, layoutEnd, hyphenPx)
            } else {
                null
            }
            // The hyphen the breaker draws belongs to the word it broke, and
            // to whatever is inked over it — but it is not in the text, so no
            // range reaches it. The fragment that ends the line takes it on.
            val hyphen = if (endsHyphenated(textLayout, line, lineEnd)) hyphenPx else 0f
            // Selection paths can stop above a Latin descender at a wrapped
            // range edge. Keep the word-local horizontal bounds, but cover the
            // text layout's full line height so g/j/p/q/y never escape the
            // upcoming-ink mask.
            add(
                Rect(
                    left = selectionBounds.left + (shift?.at(lineStart) ?: 0f),
                    top = textLayout.getLineTop(line),
                    right = selectionBounds.right + (shift?.at(lineEnd) ?: 0f) + hyphen,
                    bottom = textLayout.getLineBottom(line),
                ),
            )
        }
    }
}

/**
 * The part of a range's selection path that is on [line], as a rectangle.
 *
 * A hyphenated line breaks inside a word, so its last offset is also the first
 * offset of the line below — and a selection path taken up to it spills onto
 * that line, where it starts at the margin. `getBounds()` unions the two, and
 * the caller reads back a box the width of the whole measure: a paper cover for
 * the verse *after* a hyphenated break lay over the verse before it as well, so
 * a fragment of prose was dimmed twice and all but vanished. (Justification did
 * not cause that — it only hyphenates more lines, so it showed up at once.)
 *
 * Keeping only what is inside the line's own band is exact for every case: with
 * no spill the intersection is the path itself, and a bidi run's extremes stay
 * wherever the path put them.
 */
private fun lineSelectionBounds(
    textLayout: TextLayoutResult,
    line: Int,
    lineStart: Int,
    lineEnd: Int,
): Rect {
    val path = textLayout.getPathForRange(lineStart, lineEnd)
    // Nothing else can spill, and every word of every Arabic ayah comes through
    // here — so the whole of the arithmetic below is skipped for all of them.
    if (!breaksMidWord(textLayout, line, lineEnd)) return path.getBounds()
    val band = Path().apply {
        addRect(
            Rect(
                left = 0f,
                top = textLayout.getLineTop(line),
                right = textLayout.size.width.toFloat(),
                bottom = textLayout.getLineBottom(line),
            ),
        )
    }
    return Path().apply { op(path, band, PathOperation.Intersect) }.getBounds()
}

/**
 * Whether [line] ends inside a word — where the line below picks it up.
 *
 * A line broken at a space ends before it: `getLineEnd(visibleEnd = true)` takes
 * the space off, so the next line starts later. A line broken inside a word ends
 * exactly where the next one begins, and [offset] is that seam.
 */
private fun breaksMidWord(textLayout: TextLayoutResult, line: Int, offset: Int): Boolean =
    line + 1 < textLayout.lineCount && textLayout.getLineStart(line + 1) == offset

/**
 * Where a justified line's ink actually sits, against where the selection path
 * says it does.
 *
 * A selection path is measured on the run's own advances:
 * `TextLayoutResult.getPathForRange` does not know that the line was stretched
 * to the measure, so on a justified paragraph it stops short of the ink by the
 * whole of that stretch. Left uncorrected the paper masks came up short at the
 * end of every line, and an unread verse showed its last word or two at full
 * ink — a page of English prose with a bright ragged edge down its right side.
 *
 * The correction is exact rather than a fudge, because the stretch is
 * distributed evenly: Android justifies inter-word, dividing the extra width
 * over the line's stretchable spaces. So a character sits right of where the
 * path puts it by one share for every space before it.
 *
 * A hyphenated line is the one place the arithmetic needs telling twice. Its
 * hyphen is drawn, not written: the breaker adds it to the run, so it stands
 * between the last letter and the margin without being in the text at all. Read
 * naively, the paper between them is all taken for stretch, every share comes
 * out a couple of pixels too wide, and the far end of the line drifts far enough
 * that a verse beginning there showed its first letter at full ink. [hyphenPx]
 * is that glyph's advance, measured in the leaf's own hand, and taking it off
 * first leaves the stretch alone. Callers that never hyphenate pass zero.
 */
private class JustifyShift(private val perSpace: Float, private val spacesBefore: (Int) -> Int) {
    fun at(offset: Int): Float = perSpace * spacesBefore(offset)
}

private fun justifyShift(
    textLayout: TextLayoutResult,
    line: Int,
    layoutStart: Int,
    layoutEnd: Int,
    hyphenPx: Float,
): JustifyShift? {
    // A paragraph's last line is set flush left and takes no stretch at all;
    // measuring one would read the whole of its ragged edge as stretch.
    if (line >= textLayout.lineCount - 1) return null
    val text = textLayout.layoutInput.text
    val spaces = countSpaces(text, layoutStart, layoutEnd)
    if (spaces <= 0) return null
    // On this line only: a hyphenated line's own end offset also belongs to the
    // line below, and a path taken to it spills there. Unclipped, the spill made
    // `natural.right` the whole measure, the stretch came out negative, and the
    // one line the correction was skipped on was the one whose last word carried
    // the hyphen. See [lineSelectionBounds].
    val natural = lineSelectionBounds(textLayout, line, layoutStart, layoutEnd)
    if (natural.isEmpty) return null
    val drawn = if (endsHyphenated(textLayout, line, layoutEnd)) hyphenPx else 0f
    val stretch = textLayout.getLineRight(line) - drawn - natural.right
    if (stretch <= 0f) return null
    return JustifyShift(stretch / spaces) { offset ->
        countSpaces(text, layoutStart, offset)
    }
}

/**
 * Whether the breaker split a word at [offset] and drew a hyphen for it.
 *
 * A word that carries its own hyphen (*Ad-Dukhan*) breaks mid-word too and needs
 * no second one, so a text hyphen already there is not counted twice.
 */
private fun endsHyphenated(textLayout: TextLayoutResult, line: Int, offset: Int): Boolean {
    if (!breaksMidWord(textLayout, line, offset)) return false
    val text = textLayout.layoutInput.text
    return offset in 1..text.length && text[offset - 1] != '-'
}

private fun countSpaces(text: CharSequence, start: Int, endExclusive: Int): Int {
    var spaces = 0
    for (index in start until endExclusive.coerceAtMost(text.length)) {
        if (text[index] == ' ') spaces++
    }
    return spaces
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

/**
 * Paper dissolving off the bottom of a sticky band. Size the receiver to the
 * feather height; scrolling content passing under it feathers instead of
 * hitting a hard edge. Cheap gradient overlay — no offscreen layer.
 */
fun Modifier.paperBottomFeather(color: Color): Modifier = drawBehind {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
        ),
    )
}
