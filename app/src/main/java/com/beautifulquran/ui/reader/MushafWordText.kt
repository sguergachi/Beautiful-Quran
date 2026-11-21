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
