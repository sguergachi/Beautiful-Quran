package com.beautifulquran.ui.reader

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.times
import com.beautifulquran.data.MushafBannerStyle
import com.beautifulquran.data.model.Surah
import com.beautifulquran.ui.theme.GeneratedInkRosette
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.SerifFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.generatedFieldWeave
import com.beautifulquran.ui.theme.ornament.chapterOrnamentSeed
import com.beautifulquran.ui.theme.ornament.generateChapterOrnament
import kotlin.math.roundToInt

/**
 * Which illumination opens a chapter. Provided once at the root from the
 * developer setting, so the Arabic leaf, the English leaf and any preview
 * draw the same band without the choice being handed down through them.
 */
internal val LocalMushafBannerStyle = staticCompositionLocalOf { MushafBannerStyle.CARTOUCHE }

/** Corner easing anywhere on the leaf: a hairline, never a curve. */
private const val MushafPanelCornerPx = 3f

/**
 * The Latin name against the hand it is handed. The window around the name is
 * sized to the name's own advance, so a long transliteration widens the
 * cartouche rather than overrunning it; the only limit is the band's height,
 * which the shared tail guard already keeps. At 0.62 the name came out a
 * little over half the prose under it and read as a caption, not a title.
 */
private const val MushafLatinTitleScale = 0.95f

/**
 * The Arabic name against the page's hand, set to read as large as the English
 * leaf's name. Matched by eye, not by em: at the same size an alef stands as
 * tall as a Latin capital, but the letters' bodies sit about half as high, so
 * the name read a size smaller than its English counterpart. The writing line
 * the band centres (alef head to baseline) still fits well inside the band, and
 * the shared tail guard keeps the deepest tails in its leading.
 */
private const val MushafArabicTitleScale = 1.35f

/** Shamsa inset from the band's ends and its diameter, in band heights. */
private const val MushafShamsaInset = 0.14f
private const val MushafShamsaSize = 0.60f

/**
 * How far below the band's centre a tail may reach, in band heights: the band's
 * own half plus most of the slot's leading under it. No variant rules the band
 * across the name, so a tail hangs into open paper there, as on a manuscript.
 */
private const val MushafTailRoom = 0.62f

/**
 * The ʿunwān that opens a chapter on the leaf.
 *
 * A printed mushaf does not merely name a surah — it illuminates the name: a
 * shamsa (the sun medallion) closing each end and tooled ground between them,
 * drawn from the ornament kit that binds the cover, so the leaf and the boards
 * read as one book. The ground is the chapter's own [generateChapterOrnament]
 * field tiled at a whisper; the shamsas are that same chapter's rosette.
 *
 * Every [MushafBannerStyle] leaves the name on open paper — no rule runs
 * over its ascenders or under its tails:
 *
 * - [MushafBannerStyle.CARTOUCHE]: the Mamluk and Ottoman ruled panel, its
 *   doubled rule broken by a central window with pointed ends;
 * - [MushafBannerStyle.CLOUD]: the Ilkhanid "writing within clouds" — ground
 *   across the band, a lobed collar knocked out of it around the name.
 *
 * Each name hangs from one book-wide writing line ([titleBaselineDrop]), not
 * from the middle of its own ink: a final ميم sweeping under the line must not
 * lift the word it ends.
 */
@Composable
internal fun MushafSurahTitleBand(
    surah: Surah?,
    fontSize: TextUnit,
    bandHeight: Dp,
    modifier: Modifier = Modifier,
    /**
     * The English leaf names the chapter in the book's own hand, not in Hafs.
     * The panel around it is unchanged — one chapter, one ornament, whichever
     * language the leaf is set in — because the illumination is the book's and
     * only the writing is the reader's.
     */
    latin: Boolean = false,
) {
    val style = LocalMushafBannerStyle.current
    val accents = LocalQuranAccents.current
    val paper = MaterialTheme.colorScheme.background
    val ornament = remember(surah?.id, surah?.ayahCount) {
        generateChapterOrnament(
            chapterOrnamentSeed(
                chapterNumber = surah?.id ?: 1,
                ayahCount = surah?.ayahCount ?: 7,
            ),
        )
    }
    val rule = accents.gold.copy(alpha = 0.50f)
    val hair = accents.gold.copy(alpha = 0.28f)
    // Gold gains contrast on a dark leaf and loses it on cream, so the tooled
    // ground cannot carry one alpha across both: at the weight that reads as a
    // whisper on Nightfall it disappears into paper. Weigh it against the leaf
    // it is tooled into.
    val baseGround = if (paper.luminance() > 0.5f) 0.20f else 0.07f
    // The cloud has no rule carrying the band, so its ground has to.
    val groundAlpha = if (style == MushafBannerStyle.CLOUD) baseGround * 1.2f else baseGround

    val name = if (latin) surah?.nameTransliteration.orEmpty() else surah?.nameArabic.orEmpty()
    val family = if (latin) SerifFontFamily else HafsFontFamily
    val titleSize = fontSize * if (latin) MushafLatinTitleScale else MushafArabicTitleScale
    val resolvedTypeface by LocalFontFamilyResolver.current.resolve(family)
    val density = LocalDensity.current
    val naturalPx = with(density) { titleSize.toPx() }
    val bandPx = with(density) { bandHeight.toPx() }
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(bandHeight),
        contentAlignment = Alignment.Center,
    ) {
    // The window around the name grows with it only so far: past the room
    // between the band's ends it would run over the rules and the shamsas. A
    // name too long for that (a long transliteration on a narrow phone or at a
    // large font scale) is set down until it fits, never let through.
    val roomPx = bannerNameRoomPx(style, constraints.maxWidth.toFloat(), bandPx)
    val fontPx = remember(resolvedTypeface, naturalPx, latin, name, roomPx) {
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = resolvedTypeface as Typeface
            textSize = naturalPx
            if (latin) letterSpacing = TitleLetterSpacingEm
        }
        val natural = probe.measureText(name)
        if (natural <= 0f || natural <= roomPx) naturalPx else naturalPx * (roomPx / natural).coerceAtLeast(0.5f)
    }
    val titleSizeFit = with(density) { fontPx.toSp() }
    val paint = remember(resolvedTypeface, fontPx, latin) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = resolvedTypeface as Typeface
            textSize = fontPx
            if (latin) letterSpacing = TitleLetterSpacingEm
        }
    }
    val drop = remember(paint, latin, bandPx) { titleBaselineDrop(paint, latin, bandPx) }
    val advance = remember(paint, name) { paint.measureText(name) }

        // The ground is laid once and mirrored at the fold, so the panel is
        // symmetrical about its own centre — a tiling cut by the two ends at
        // whatever phase it happened to reach is not. Each style then decides
        // where it shows and what rules it, in one cached pass.
        Row(
            Modifier
                .fillMaxSize()
                .bannerIllumination(style, advance, rule, hair),
        ) {
            val ground = Modifier
                .weight(1f)
                .fillMaxHeight()
                .generatedFieldWeave(
                    // Tooling, not pattern: the chapter's own ground tiled
                    // small enough that it reads as texture inside the rules
                    // rather than as a lattice drawn across them.
                    field = ornament.field.copy(
                        cellWidthDp = ornament.field.cellWidthDp * 0.42,
                    ),
                    ink = accents.gold.copy(alpha = groundAlpha),
                    embossLight = accents.embossLight.copy(alpha = groundAlpha * 0.55f),
                )
            Box(ground)
            Box(ground.graphicsLayer { scaleX = -1f })
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = bandHeight * MushafShamsaInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shamsaAlpha = groundAlpha * 2.4f
            MushafShamsa(ornament, bandHeight, shamsaAlpha)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                MushafTitle(
                    name = name,
                    latin = latin,
                    family = family,
                    fontSize = titleSizeFit,
                    drop = drop,
                    ink = accents.gold,
                    modifier = Modifier.padding(horizontal = bandHeight * 0.10f),
                )
            }
            MushafShamsa(ornament, bandHeight, shamsaAlpha)
        }
    }
}

private const val TitleLetterSpacingEm = 0.06f

/**
 * The widest name the band's window can hold without reaching its rules — the
 * inverse of each style's window width in [bannerIllumination]: the cartouche
 * caps its window at `w - 2.2h` around `advance + 1.1h`, the cloud at `w - 2h`
 * around `advance + 0.7h`.
 */
private fun bannerNameRoomPx(style: MushafBannerStyle, w: Float, h: Float): Float = when (style) {
    MushafBannerStyle.CARTOUCHE -> w - 3.3f * h
    MushafBannerStyle.CLOUD -> w - 2.7f * h
}.coerceAtLeast(h)

/**
 * Where the baseline sits below the band's centre, in px — the same for every
 * chapter at a size, so a page of openings writes on one line.
 *
 * The writing line runs from an alef's head (a Latin capital's, on the English
 * leaf) down to the baseline, and that zone is what is centred. Tails are
 * left to hang below it. The only adjustment is shared: if the deepest tail the
 * script has would run out of [MushafTailRoom], every name rises together.
 */
private fun titleBaselineDrop(paint: Paint, latin: Boolean, bandPx: Float): Float {
    val bounds = android.graphics.Rect()
    fun extent(glyph: String): android.graphics.Rect =
        bounds.also { paint.getTextBounds(glyph, 0, glyph.length, it) }
    val head = -extent(if (latin) "H" else "ا").top.toFloat()
    val deepest = (if (latin) "gjpqy" else "منقعجيى")
        .map { extent(it.toString()).bottom }
        .maxOrNull()?.toFloat() ?: 0f
    return minOf(head / 2f, bandPx * MushafTailRoom - deepest).coerceAtLeast(0f)
}

/**
 * The style's ground and rules: the [content] (the tooled weave) is clipped to
 * where this style lays ground, and the rules are drawn over it. Geometry is
 * built once per size and name width.
 */
private fun Modifier.bannerIllumination(
    style: MushafBannerStyle,
    advance: Float,
    rule: Color,
    hair: Color,
): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    when (style) {
        MushafBannerStyle.CARTOUCHE -> {
            // A paper window around the name, its ends drawn to a point. The
            // band's doubled rule runs into those points and stops: above and
            // below the name there is nothing but the panel's own air.
            val tip = 0.32f * h
            val half = (advance + 1.1f * h).coerceIn(2.6f * h, maxOf(2.6f * h, w - 2.2f * h)) / 2f
            val x0 = cx - half
            val x1 = cx + half
            val sides = Path().apply {
                moveTo(x0, 0f)
                quadraticTo(x0 - 0.2f * tip, 0.55f * cy, x0 - tip, cy)
                quadraticTo(x0 - 0.2f * tip, h - 0.55f * cy, x0, h)
                moveTo(x1, h)
                quadraticTo(x1 + 0.2f * tip, h - 0.55f * cy, x1 + tip, cy)
                quadraticTo(x1 + 0.2f * tip, 0.55f * cy, x1, 0f)
            }
            // The window runs well past the band so the rules' outer half
            // strokes are cut with it.
            val window = Path().apply {
                moveTo(x0, -h)
                lineTo(x0, 0f)
                quadraticTo(x0 - 0.2f * tip, 0.55f * cy, x0 - tip, cy)
                quadraticTo(x0 - 0.2f * tip, h - 0.55f * cy, x0, h)
                lineTo(x0, 2f * h)
                lineTo(x1, 2f * h)
                lineTo(x1, h)
                quadraticTo(x1 + 0.2f * tip, h - 0.55f * cy, x1 + tip, cy)
                quadraticTo(x1 + 0.2f * tip, 0.55f * cy, x1, 0f)
                lineTo(x1, -h)
                close()
            }
            val pad = 2.5.dp.toPx()
            val ground = Path.combine(
                PathOperation.Difference,
                Path().apply { addRect(Rect(pad, pad, w - pad, h - pad)) },
                window,
            )
            val r = CornerRadius(MushafPanelCornerPx, MushafPanelCornerPx)
            val outer = Stroke(width = 1.2.dp.toPx())
            val inner = Stroke(width = 0.8.dp.toPx())
            val insetPx = 3.dp.toPx()
            onDrawWithContent {
                clipPath(ground) { this@onDrawWithContent.drawContent() }
                clipPath(window, ClipOp.Difference) {
                    drawRoundRect(color = rule, cornerRadius = r, style = outer)
                    inset(insetPx) {
                        drawRoundRect(color = hair, cornerRadius = r, style = inner)
                    }
                }
                drawPath(sides, color = rule, style = inner)
            }
        }
        MushafBannerStyle.CLOUD -> {
            // Ground the length of the band with rounded ends and no rule; a
            // collar the band's full height is knocked out of it around the
            // name, lobed only at its sides — so nothing horizontal is ever
            // drawn over or under the writing.
            val lobe = h / 6f
            val half = (advance + 0.7f * h).coerceIn(h, maxOf(h, w - 2f * h)) / 2f
            val xl = cx - half
            val xr = cx + half
            fun Path.lobesDown(x: Float) {
                for (i in 0..2) {
                    arcTo(Rect(x - lobe, 2f * lobe * i, x + lobe, 2f * lobe * (i + 1)), -90f, -180f, false)
                }
            }
            fun Path.lobesUp(x: Float) {
                for (i in 2 downTo 0) {
                    arcTo(Rect(x - lobe, 2f * lobe * i, x + lobe, 2f * lobe * (i + 1)), 90f, -180f, false)
                }
            }
            val scallops = Path().apply {
                moveTo(xl, 0f)
                lobesDown(xl)
                moveTo(xr, h)
                lobesUp(xr)
            }
            val collar = Path().apply {
                moveTo(xl, -h)
                lineTo(xl, 0f)
                lobesDown(xl)
                lineTo(xl, 2f * h)
                lineTo(xr, 2f * h)
                lineTo(xr, h)
                lobesUp(xr)
                lineTo(xr, -h)
                close()
            }
            val ground = Path.combine(
                PathOperation.Difference,
                Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(cy, cy))) },
                collar,
            )
            val stroke = Stroke(width = 0.7.dp.toPx())
            val scallop = rule.copy(alpha = rule.alpha * 0.84f)
            onDrawWithContent {
                clipPath(ground) { this@onDrawWithContent.drawContent() }
                drawPath(scallops, color = scallop, style = stroke)
            }
        }
    }
}

/**
 * The shamsa closing one end of the panel — drawn, not gilded. At this size a
 * gold-and-emboss rosette closes up into a solid disc and reads as a button
 * stuck to the paper; struck in one hairline it stays a drawing.
 */
@Composable
private fun MushafShamsa(
    ornament: com.beautifulquran.ui.theme.ornament.ChapterOrnament,
    bandHeight: Dp,
    inkAlpha: Float,
) {
    GeneratedInkRosette(
        spec = ornament.rosette,
        size = bandHeight * MushafShamsaSize,
        // The same gold ink the ground is tooled in, struck a little firmer —
        // drawn, never gilded: leaf and emboss at this size close the star up
        // into a stud pressed into the page.
        ink = LocalQuranAccents.current.gold.copy(alpha = inkAlpha),
        strokeWidth = 0.7.dp,
    )
}

/**
 * The chapter's name, hung from the shared writing line: its baseline sits
 * [drop] px below the centre of the box it is given, whatever the name.
 */
@Composable
private fun MushafTitle(
    name: String,
    latin: Boolean,
    family: androidx.compose.ui.text.font.FontFamily,
    fontSize: TextUnit,
    drop: Float,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = name,
        style = TextStyle(
            fontFamily = family,
            fontSize = fontSize,
            letterSpacing = if (latin) TitleLetterSpacingEm.em else TextUnit.Unspecified,
            color = ink,
            textAlign = TextAlign.Center,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        maxLines = 1,
        softWrap = false,
        // Never clip a glyph at its box — the same law the page's words are
        // set under. A tail that sweeps under the line (ر, م) draws past it.
        overflow = TextOverflow.Visible,
        modifier = modifier.layout { measurable, constraints ->
            // Measure the full natural line, then place its baseline on the
            // book's writing line rather than centring this name's ink.
            val text = measurable.measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                text.placeRelative(
                    (constraints.maxWidth - text.width) / 2,
                    (constraints.maxHeight / 2f + drop - text[FirstBaseline]).roundToInt(),
                )
            }
        },
    )
}
