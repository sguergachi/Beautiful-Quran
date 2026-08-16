package com.beautifulquran.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.beautifulquran.data.model.Surah
import com.beautifulquran.ui.theme.GeneratedChapterRosette
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.generatedFieldWeave
import com.beautifulquran.ui.theme.ornament.chapterOrnamentSeed
import com.beautifulquran.ui.theme.ornament.generateChapterOrnament

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
    // The panel is tooled metal, not a ceremony: no build wash, so it is whole
    // from the first frame the leaf is drawn.
    val sheen: State<Float> = remember { mutableFloatStateOf(1f) }
    val rule = accents.gold.copy(alpha = 0.50f)
    val hair = accents.gold.copy(alpha = 0.28f)

    Box(
        modifier = modifier.fillMaxWidth().height(bandHeight),
        contentAlignment = Alignment.Center,
    ) {
        val corner = bandHeight * 0.22f
        Box(
            Modifier
                .fillMaxSize()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
                .padding(2.5.dp)
                .generatedFieldWeave(
                    // Tooling, not pattern: the chapter's own ground tiled
                    // small enough that it reads as texture inside the rules
                    // rather than as a lattice drawn across them.
                    field = ornament.field.copy(
                        cellWidthDp = ornament.field.cellWidthDp * 0.42,
                    ),
                    ink = accents.gold.copy(alpha = 0.07f),
                    embossLight = accents.embossLight.copy(alpha = 0.04f),
                ),
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = corner.toPx()
            // Doubled rule: the panel's edge, then a hairline just inside it.
            drawRoundRect(
                color = rule,
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.2.dp.toPx()),
            )
            inset(3.dp.toPx()) {
                drawRoundRect(
                    color = hair,
                    cornerRadius = CornerRadius(r * 0.7f, r * 0.7f),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = bandHeight * 0.14f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MushafShamsa(ornament, bandHeight, sheen)
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
            MushafShamsa(ornament, bandHeight, sheen)
        }
    }
}

/** The sun medallion closing one end of the panel. */
@Composable
private fun MushafShamsa(
    ornament: com.beautifulquran.ui.theme.ornament.ChapterOrnament,
    bandHeight: Dp,
    sheen: State<Float>,
) {
    val accents = LocalQuranAccents.current
    GeneratedChapterRosette(
        spec = ornament.rosette,
        size = bandHeight * 0.62f,
        brightGold = accents.goldBright,
        deepGold = accents.goldDeep,
        embossDark = accents.embossDark,
        embossLight = accents.embossLight,
        sheen = sheen,
        modifier = Modifier.size(bandHeight * 0.62f),
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
            val r = size.height / 2f
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
            modifier = Modifier.padding(horizontal = height * 0.5f, vertical = height * 0.12f),
        )
    }
}

/**
 * The hairline under the leaf: a rule that separates the page from the
 * transport and, filled from the fore-edge inward, shows how far into the
 * chapter the recitation has come. It is the one progress indicator a book can
 * carry without becoming an app — a thread of gold laid along the paper's
 * edge, not a bar with a handle.
 */
@Composable
internal fun MushafProgressRule(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val accents = LocalQuranAccents.current
    val ink = MaterialTheme.colorScheme.onBackground
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        val h = size.height
        drawRect(color = ink.copy(alpha = 0.10f), size = Size(size.width, h))
        val done = (size.width * progress.coerceIn(0f, 1f))
        if (done > 0f) {
            // Right to left: the book's own direction of travel.
            drawRect(
                color = accents.gold.copy(alpha = 0.55f),
                topLeft = Offset(size.width - done, 0f),
                size = Size(done, h),
            )
        }
    }
}
