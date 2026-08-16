package com.beautifulquran.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.beautifulquran.data.model.Surah
import com.beautifulquran.ui.theme.GeneratedInkRosette
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.generatedFieldWeave
import com.beautifulquran.ui.theme.ornament.chapterOrnamentSeed
import com.beautifulquran.ui.theme.ornament.generateChapterOrnament

/** The rule itself stays a hairline; the thumb is what gives the band height. */
private val MushafThumbHeight = 3.dp
private const val MushafRuleWeightPx = 1f

/** How far each end of the panel draws in, as a share of its height. */
private const val MushafPanelTaper = 0.55f

/** The name's cartouche closes harder, so it reads as a lozenge. */
private const val MushafCartoucheTaper = 0.62f

/**
 * A capsule that tapers to a point at either end: flat above and below, with
 * each end drawn in to a vertex on the centre line and eased so the corner
 * reads as a drawn stroke rather than a mitre.
 */
private fun taperedCapsulePath(size: Size, taper: Float): Path {
    val h = size.height
    val w = size.width
    val t = (h * taper).coerceAtMost(w / 2f)
    val ease = h * 0.16f
    return Path().apply {
        moveTo(t, 0f)
        lineTo(w - t, 0f)
        quadraticTo(w - t + ease, 0f, w - t + ease * 1.4f, h * 0.30f)
        lineTo(w, h / 2f)
        lineTo(w - t + ease * 1.4f, h * 0.70f)
        quadraticTo(w - t + ease, h, w - t, h)
        lineTo(t, h)
        quadraticTo(t - ease, h, t - ease * 1.4f, h * 0.70f)
        lineTo(0f, h / 2f)
        lineTo(t - ease * 1.4f, h * 0.30f)
        quadraticTo(t - ease, 0f, t, 0f)
        close()
    }
}

private fun taperedCapsuleShape(taper: Float): Shape = GenericShape { size, _ ->
    addPath(taperedCapsulePath(size, taper))
}

/**
 * The ʿunwān panel that opens a chapter on the leaf.
 *
 * A printed mushaf does not merely name a surah — it illuminates the name in a
 * ruled panel: a horizontal cartouche holding the title, a shamsa (the sun
 * medallion) closing each end, and tooled ground between them. That is the
 * whole grammar here, drawn from the ornament kit that binds the cover, so the
 * leaf and the boards read as one book:
 *
 * - the panel's doubled gilt rule answers the cover's doubled rule;
 * - the ground is the chapter's own [generateChapterOrnament] field, tiled at a
 *   whisper so it reads as tooling rather than pattern;
 * - the two shamsas are that same chapter's rosette, the mark the scroll
 *   layout already gives it — one chapter, one hand, wherever it opens;
 * - the cartouche is paper, so the name sits on clean ground rather than on
 *   the weave.
 *
 * The kit's grammar is geometric — star rosettes and Hankin grounds — where a
 * Cairo or Istanbul manuscript would scroll islīmī vine through the panel. The
 * geometry is what this book is bound in, so the panel is built from it rather
 * than borrowing a second vocabulary for one band.
 */
@Composable
internal fun MushafSurahTitleBand(
    surah: Surah?,
    fontSize: TextUnit,
    bandHeight: Dp,
    modifier: Modifier = Modifier,
) {
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
    val groundAlpha = if (paper.luminance() > 0.5f) 0.20f else 0.07f
    val density = LocalDensity.current

    Box(
        modifier = modifier.fillMaxWidth().height(bandHeight),
        contentAlignment = Alignment.Center,
    ) {
        // The panel is a capsule that tapers to a point at either end, the way
        // a ribbon cartouche is drawn — a rectangle reads as a UI card however
        // finely it is ruled.
        val capsule = remember { taperedCapsuleShape(MushafPanelTaper) }
        // The ground is laid once and mirrored at the fold, so the panel is
        // symmetrical about its own centre — a tiling cut by the two ends at
        // whatever phase it happened to reach is not.
        Row(
            Modifier
                .fillMaxSize()
                .clip(capsule)
                .padding(2.5.dp),
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
        Canvas(Modifier.fillMaxSize()) {
            // Doubled rule, both following the taper: the panel's edge, then a
            // hairline just inside it.
            drawPath(
                taperedCapsulePath(size, MushafPanelTaper),
                color = rule,
                style = Stroke(width = 1.2.dp.toPx()),
            )
            inset(3.dp.toPx()) {
                drawPath(
                    taperedCapsulePath(this.size, MushafPanelTaper),
                    color = hair,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = bandHeight * 0.14f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MushafShamsa(ornament, bandHeight, groundAlpha)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MushafTitleCartouche(
                    name = surah?.nameArabic.orEmpty(),
                    fontSize = fontSize,
                    height = bandHeight * 0.54f,
                    paper = paper,
                    rule = rule,
                    ink = accents.gold,
                )
            }
            MushafShamsa(ornament, bandHeight, groundAlpha)
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
    groundAlpha: Float,
) {
    GeneratedInkRosette(
        spec = ornament.rosette,
        size = bandHeight * 0.60f,
        // The same gold ink the ground is tooled in, struck a little firmer —
        // drawn, never gilded: leaf and emboss at this size close the star up
        // into a stud pressed into the page.
        ink = LocalQuranAccents.current.gold.copy(alpha = groundAlpha * 2.4f),
        strokeWidth = 0.7.dp,
    )
}

/** Paper cartouche carrying the chapter's name, so the title never sits on the ground. */
@Composable
private fun MushafTitleCartouche(
    name: String,
    fontSize: TextUnit,
    height: Dp,
    paper: Color,
    rule: Color,
    ink: Color,
) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            // The cartouche tapers like the panel around it, so the two read as
            // one drawing rather than a box inside a box.
            val path = taperedCapsulePath(size, MushafCartoucheTaper)
            drawPath(path, color = paper)
            drawPath(path, color = rule, style = Stroke(width = 0.8.dp.toPx()))
        }
        Text(
            text = name,
            fontFamily = HafsFontFamily,
            fontSize = fontSize,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            // Room for the taper to close either side of the name.
            modifier = Modifier.padding(horizontal = height * 0.85f, vertical = height * 0.10f),
        )
    }
}

/**
 * The hairline under the leaf: a rule that separates the page from the
 * transport, carrying a single thumb where the reader has got to in the book.
 *
 * A ribbon marks a place; it does not colour in the pages behind it. So the
 * rule stays one weight end to end and the thumb alone moves along it — it
 * reads the *page*, not the playback, so it answers while leaves are turned as
 * well as while they are recited. Furniture, so ink: gold on the leaf means
 * illumination.
 */
@Composable
internal fun MushafProgressRule(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val at by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "mushafProgress",
    )
    Canvas(modifier.fillMaxWidth().height(MushafThumbHeight)) {
        val rule = MushafRuleWeightPx
        val midY = size.height / 2f
        drawRoundRect(
            color = ink.copy(alpha = 0.10f),
            topLeft = Offset(0f, midY - rule / 2f),
            size = Size(size.width, rule),
            cornerRadius = CornerRadius(rule, rule),
        )
        // The thumb: where in the book this leaf sits. A little thicker than
        // the rule and rounded, so it reads as a marker laid on the line
        // rather than a control fixed to it. Right to left, the book's own
        // direction of travel.
        val thumbW = size.height * 3.2f
        val thumbX = (size.width * (1f - at) - thumbW / 2f)
            .coerceIn(0f, size.width - thumbW)
        drawRoundRect(
            color = ink.copy(alpha = 0.62f),
            topLeft = Offset(thumbX, 0f),
            size = Size(thumbW, size.height),
            cornerRadius = CornerRadius(size.height, size.height),
        )
    }
}
