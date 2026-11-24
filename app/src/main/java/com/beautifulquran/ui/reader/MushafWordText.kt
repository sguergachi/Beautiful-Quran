package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLocaleList
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * Laid-out QCF word and verse-mark cells, shared by every leaf in the process.
 *
 * A leaf sets ~150 cells, each its own node, and laying each one out — a
 * paragraph, its intrinsics and a StaticLayout — is a large part of what
 * bringing a neighbouring leaf in costs: ~55 ms of the three slowest
 * page-turn frames (145 ms together) measured on the emulator. The layout of a cell
 * depends only on its glyphs and its exact style (the page's own face, size,
 * narrowing, colour) at one density and direction, so it is kept here,
 * keyed by all of those, and a leaf that comes back — the one just turned
 * from, a dial landing on a visited page — reuses it.
 *
 * Entries are the same [TextLayoutResult] a text node would have produced;
 * the cell draws it exactly as foundation's text node draws its own.
 */
internal object MushafWordLayouts {
    private const val MAX_ENTRIES = 2048

    private data class Key(
        val text: String,
        val style: TextStyle,
        val density: Float,
        val fontScale: Float,
        val direction: LayoutDirection,
        val resolver: FontFamily.Resolver,
        val locales: LocaleList,
        val constraints: Constraints,
    )

    private val entries = object : LinkedHashMap<Key, TextLayoutResult>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TextLayoutResult>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    private fun cached(key: Key): TextLayoutResult? = entries[key]

    /**
     * Drops every cell set in [family]. [MushafQcfFonts] calls it when a page
     * face leaves its resident window: the cells' styles and paragraphs hold
     * that face's native font, and a rebuilt face is a new family that would
     * never hit them again, so keeping them only pinned the font past the window.
     */
    @Synchronized
    fun forget(family: FontFamily) {
        entries.keys.removeAll { it.style.fontFamily == family }
    }

    @Synchronized
    private fun store(key: Key, result: TextLayoutResult): TextLayoutResult =
        entries.getOrPut(key) { result }

    fun layout(
        text: String,
        style: TextStyle,
        density: Density,
        direction: LayoutDirection,
        resolver: FontFamily.Resolver,
        locales: LocaleList,
        constraints: Constraints = Constraints(),
    ): TextLayoutResult {
        val key = Key(text, style, density.density, density.fontScale, direction, resolver, locales, constraints)
        cached(key)?.let { return it }
        // Laid out exactly as BasicText(maxLines = 1, softWrap = false,
        // overflow = Visible) lays out its text.
        val result = TextMeasurer(
            defaultFontFamilyResolver = resolver,
            defaultLocaleList = locales,
            defaultDensity = density,
            defaultLayoutDirection = direction,
            cacheSize = 0,
        ).measure(
            text = AnnotatedString(text),
            style = style,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = constraints,
            layoutDirection = direction,
            density = density,
            fontFamilyResolver = resolver,
        )
        return store(key, result)
    }
}

/**
 * One QCF cell: a word or a verse mark, set on one line that never wraps or
 * clips (BasicText's `maxLines = 1, softWrap = false, overflow = Visible`),
 * laid out through [MushafWordLayouts]. Draws as foundation's text node does:
 * the paragraph painted in the style's colour, unclipped.
 */
@Composable
internal fun MushafWordText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    Spacer(modifier.then(MushafWordTextElement(text, style, onTextLayout)))
}

private class MushafWordTextElement(
    val text: String,
    val style: TextStyle,
    val onTextLayout: ((TextLayoutResult) -> Unit)?,
) : ModifierNodeElement<MushafWordTextNode>() {
    override fun create() = MushafWordTextNode(text, style, onTextLayout)

    override fun update(node: MushafWordTextNode) = node.update(text, style, onTextLayout)

    override fun equals(other: Any?): Boolean = other is MushafWordTextElement &&
        other.text == text && other.style == style && other.onTextLayout === onTextLayout

    override fun hashCode(): Int =
        (text.hashCode() * 31 + style.hashCode()) * 31 + (onTextLayout?.hashCode() ?: 0)

    override fun InspectorInfo.inspectableProperties() {
        name = "mushafWordText"
        properties["text"] = text
    }
}

private class MushafWordTextNode(
    private var text: String,
    private var style: TextStyle,
    private var onTextLayout: ((TextLayoutResult) -> Unit)?,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var result: TextLayoutResult? = null

    override val shouldAutoInvalidate: Boolean get() = false

    fun update(text: String, style: TextStyle, onTextLayout: ((TextLayoutResult) -> Unit)?) {
        val changed = text != this.text || style != this.style
        val callbackChanged = onTextLayout !== this.onTextLayout
        this.text = text
        this.style = style
        this.onTextLayout = onTextLayout
        if (changed) {
            result = null
            invalidateMeasurement()
            invalidateDraw()
        } else if (callbackChanged) {
            // A new callback still has to hear about the layout it missed.
            result?.let { onTextLayout?.invoke(it) }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val laid = MushafWordLayouts.layout(
            text = text,
            style = style,
            density = this,
            direction = layoutDirection,
            resolver = currentValueOf(LocalFontFamilyResolver),
            locales = currentValueOf(LocalLocaleList),
            constraints = constraints,
        )
        if (laid !== result) {
            result = laid
            onTextLayout?.invoke(laid)
        }
        val width = laid.size.width
        val height = laid.size.height
        val placeable = measurable.measure(Constraints.fixed(width, height))
        return layout(
            width,
            height,
            mapOf(
                FirstBaseline to laid.firstBaseline.roundToInt(),
                LastBaseline to laid.lastBaseline.roundToInt(),
            ),
        ) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        result?.let { drawText(it) }
        drawContent()
    }
}

/**
 * A whole QCF line of a leaf with no live ink, set and drawn by one node.
 *
 * Such a line is only ever its words' ink: no wash, no recess, no layer —
 * [MushafQcfPageLine] gives each word a node of its own only so the verse
 * under the voice can carry those. A leaf coming in mid-flip has none of
 * them, and composing, measuring and placing ~150 word nodes was most of
 * what bringing it in cost the UI thread. Here the line lays its cells out
 * through the same cache, places them at the same rounded origins and draws
 * them in the same order — word, then its mark — so the pixels are the ones
 * the word nodes drew.
 */
internal fun Modifier.mushafStaticQcfLine(
    texts: List<MushafLineText>,
    wordStyle: TextStyle,
    markStyle: TextStyle,
    geometry: MushafLineGeometry,
    emPx: Float,
    /** Hears the placed origins, for the line's tap handler. */
    onPlaced: (FloatArray) -> Unit,
): Modifier = this then MushafStaticQcfLineElement(texts, wordStyle, markStyle, geometry, emPx, onPlaced)

private class MushafStaticQcfLineElement(
    val texts: List<MushafLineText>,
    val wordStyle: TextStyle,
    val markStyle: TextStyle,
    val geometry: MushafLineGeometry,
    val emPx: Float,
    val onPlaced: (FloatArray) -> Unit,
) : ModifierNodeElement<MushafStaticQcfLineNode>() {
    override fun create() = MushafStaticQcfLineNode(texts, wordStyle, markStyle, geometry, emPx, onPlaced)

    override fun update(node: MushafStaticQcfLineNode) =
        node.update(texts, wordStyle, markStyle, geometry, emPx, onPlaced)

    override fun equals(other: Any?): Boolean = other is MushafStaticQcfLineElement &&
        other.texts === texts && other.wordStyle == wordStyle && other.markStyle == markStyle &&
        other.geometry === geometry && other.emPx == emPx && other.onPlaced === onPlaced

    override fun hashCode(): Int {
        var h = System.identityHashCode(texts)
        h = h * 31 + wordStyle.hashCode()
        h = h * 31 + markStyle.hashCode()
        h = h * 31 + System.identityHashCode(geometry)
        h = h * 31 + emPx.hashCode()
        return h * 31 + System.identityHashCode(onPlaced)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mushafStaticQcfLine"
        properties["cells"] = texts.size
    }
}

private class MushafStaticQcfLineNode(
    private var texts: List<MushafLineText>,
    private var wordStyle: TextStyle,
    private var markStyle: TextStyle,
    private var geometry: MushafLineGeometry,
    private var emPx: Float,
    private var onPlaced: (FloatArray) -> Unit,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var laid: Array<TextLayoutResult>? = null
    private var x = IntArray(0)
    private var y = IntArray(0)

    override val shouldAutoInvalidate: Boolean get() = false

    fun update(
        texts: List<MushafLineText>,
        wordStyle: TextStyle,
        markStyle: TextStyle,
        geometry: MushafLineGeometry,
        emPx: Float,
        onPlaced: (FloatArray) -> Unit,
    ) {
        val changed = texts !== this.texts || wordStyle != this.wordStyle || markStyle != this.markStyle ||
            geometry !== this.geometry || emPx != this.emPx
        this.texts = texts
        this.wordStyle = wordStyle
        this.markStyle = markStyle
        this.geometry = geometry
        this.emPx = emPx
        this.onPlaced = onPlaced
        if (changed) {
            laid = null
            invalidateMeasurement()
            invalidateDraw()
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val resolver = currentValueOf(LocalFontFamilyResolver)
        val locales = currentValueOf(LocalLocaleList)
        // Each cell exactly as a word node lays it out: unconstrained, which
        // is also the key its layout is cached under.
        val cells = Array(texts.size) { i ->
            val cell = texts[i]
            MushafWordLayouts.layout(
                text = cell.text,
                style = if (cell.mark) markStyle else wordStyle,
                density = this,
                direction = layoutDirection,
                resolver = resolver,
                locales = locales,
            )
        }
        var height = 0
        cells.forEach { height = maxOf(height, it.size.height) }
        val width = constraints.maxWidth
        val origins = mushafCellOrigins(
            cells = geometry.drawnCells,
            count = cells.size,
            width = width.toFloat(),
            fit = geometry.fit,
            joins = geometry.joinsEm,
            emPx = emPx,
        )
        if (x.size != cells.size) {
            x = IntArray(cells.size)
            y = IntArray(cells.size)
        }
        for (i in cells.indices) {
            x[i] = origins[i].roundToInt()
            y[i] = (height - cells[i].size.height) / 2
        }
        laid = cells
        onPlaced(origins)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        return layout(width, height) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        laid?.forEachIndexed { i, cell ->
            val dx = x[i].toFloat()
            val dy = y[i].toFloat()
            drawContext.transform.translate(dx, dy)
            drawText(cell)
            drawContext.transform.translate(-dx, -dy)
        }
        drawContent()
    }
}
