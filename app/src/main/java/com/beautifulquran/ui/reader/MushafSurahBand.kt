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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/** How far the chapter's name rides above its line box, in ems of itself. */
private const val MushafNameLift = 0.30f

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
        // The panel is a plain ruled box: a band across the leaf, squared at
        // the ends. The capsule belongs to the cartouche inside it, which is
        // the thing that holds the name.
        val capsule = remember { RoundedCornerShape(MushafPanelCornerPx.dp / 3f) }
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
            val r = CornerRadius(MushafPanelCornerPx, MushafPanelCornerPx)
            drawRoundRect(color = rule, cornerRadius = r, style = Stroke(width = 1.2.dp.toPx()))
            inset(3.dp.toPx()) {
                drawRoundRect(color = hair, cornerRadius = r, style = Stroke(width = 0.8.dp.toPx()))
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
    val nameLift = with(LocalDensity.current) { (fontSize.toPx() * MushafNameLift).toDp() }
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            // A capsule: the panel is the band, this is what carries the name.
            val r = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = paper, cornerRadius = r)
            drawRoundRect(color = rule, cornerRadius = r, style = Stroke(width = 0.8.dp.toPx()))
        }
        Text(
            text = name,
            // Centred on its ink, not on its line box: the Hafs face carries
            // more space above the baseline than below, so a name laid in a
            // default line box sits low in the cartouche however evenly it is
            // padded. Trimming the leading and centring what is left puts the
            // letters themselves on the panel's centre line.
            style = TextStyle(
                fontFamily = HafsFontFamily,
                fontSize = fontSize,
                color = ink,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            maxLines = 1,
            // Trimming the line box to the type's own metrics leaves the box
            // shorter than the ink: a bowl that dips below the baseline (ب) or
            // a tail that sweeps under it (ر) falls outside and is cut, which
            // is how a chapter's name came to be missing letters. Never clip a
            // glyph at its box — the same law the page's words are set under.
            overflow = TextOverflow.Visible,
            // The taper eats into the cartouche from both ends, so the name
            // needs its margin measured past the point where the sides start
            // drawing in — otherwise the letters sit in the closing wedge.
            //
            // The lift is the last of it: even trimmed, the Hafs em box keeps
            // headroom for marks this name does not carry, so its ink hangs
            // below the centre line. Raise it by that much and the letters sit
            // on the panel's own centre.
            modifier = Modifier
                .offset(y = -nameLift)
                .padding(horizontal = height * 1.45f, vertical = height * 0.26f),
        )
    }
}
