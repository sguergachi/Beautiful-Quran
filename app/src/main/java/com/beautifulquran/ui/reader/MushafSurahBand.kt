package com.beautifulquran.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

/** Corner easing anywhere on the leaf: a hairline, never a curve. */
private const val MushafPanelCornerPx = 3f

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
        // A printed panel is ruled, not rounded. Nothing on the leaf carries
        // more than a hairline's easing at its corners.
        val corner = with(density) { MushafPanelCornerPx.toDp() }
        // The ground is laid once and mirrored at the fold, so the panel is
        // symmetrical about its own centre — a tiling cut by the two ends at
        // whatever phase it happened to reach is not.
        Row(
            Modifier
                .fillMaxSize()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
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
            val r = MushafPanelCornerPx
            // Doubled rule: the panel's edge, then a hairline just inside it.
            drawRoundRect(
                color = rule,
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.2.dp.toPx()),
            )
            inset(3.dp.toPx()) {
                drawRoundRect(
                    color = hair,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = bandHeight * 0.14f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MushafShamsa(ornament, bandHeight)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MushafTitleCartouche(
                    name = surah?.nameArabic.orEmpty(),
                    fontSize = fontSize,
                    height = bandHeight * 0.66f,
                    paper = paper,
                    rule = rule,
                    ink = accents.gold,
                )
            }
            MushafShamsa(ornament, bandHeight)
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
) {
    GeneratedInkRosette(
        spec = ornament.rosette,
        size = bandHeight * 0.60f,
        // Ink, not gold: gilt at this size reads as a stud pressed into the
        // page. The star is a drawing the panel encloses, and the gold in the
        // band belongs to its rules and the chapter's name.
        ink = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
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
            val r = MushafPanelCornerPx
            drawRoundRect(color = paper, cornerRadius = CornerRadius(r, r))
            drawRoundRect(
                color = rule,
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 0.8.dp.toPx()),
            )
        }
        Text(
            text = name,
            fontFamily = HafsFontFamily,
            fontSize = fontSize,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = height * 0.42f, vertical = height * 0.14f),
        )
    }
}

/**
 * The hairline under the leaf: a rule that separates the page from the
 * transport and, filled from the fore-edge inward, shows how far into the
 * chapter the recitation has come. It is the one progress indicator a book can
 * carry without becoming an app — a line drawn along the paper's edge, not a
 * bar with a handle.
 *
 * The fill is the same ink as the rule, only denser: this is furniture, and
 * gold on the leaf means illumination. Read as a pencil drawn along the edge,
 * darkening the part of the chapter already recited.
 */
@Composable
internal fun MushafProgressRule(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        val h = size.height
        drawRect(color = ink.copy(alpha = 0.10f), size = Size(size.width, h))
        val done = (size.width * progress.coerceIn(0f, 1f))
        if (done > 0f) {
            // Right to left: the book's own direction of travel.
            drawRect(
                color = ink.copy(alpha = 0.46f),
                topLeft = Offset(size.width - done, 0f),
                size = Size(done, h),
            )
        }
    }
}
