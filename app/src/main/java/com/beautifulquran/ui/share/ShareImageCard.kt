package com.beautifulquran.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.TranslationFontFamily

/**
 * Fixed paper sheet for image export — verses at rest in full ink.
 * Not the live [ReaderScreen]: a thin, offline-renderable card that ships the
 * same Hafs face and paper tokens without LazyColumn / playback / gestures.
 *
 * Always composed under [com.beautifulquran.ui.theme.BeautifulQuranTheme] with
 * [com.beautifulquran.data.ThemeMode.LIGHT] so shares stay readable parchment.
 *
 * Given a bounded height (the renderer caps long gathers at 1920px), the gold
 * chapter footer stays in the last rows and verses fade into the paper above
 * it. Unbounded, the sheet wraps the full gather.
 */
@Composable
fun ShareImageCard(
    verses: List<ShareVerseLine>,
    includeTranslation: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.background
    val footerRef = footerReference(verses)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(paper),
    ) {
        val pinFooter = maxHeight != Dp.Infinity
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (pinFooter) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = 48.dp, vertical = 56.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (pinFooter) {
                            Modifier
                                .weight(1f)
                                .clipToBounds()
                                .fadeShareBodyIntoPaper()
                        } else {
                            Modifier
                        },
                    ),
            ) {
                verses.forEachIndexed { index, verse ->
                    if (index > 0) Spacer(Modifier.height(28.dp))
                    Text(
                        text = verse.arabic,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = HafsFontFamily,
                            fontSize = 28.sp,
                            lineHeight = 46.sp,
                            textDirection = TextDirection.Rtl,
                        ),
                        color = ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (includeTranslation && verse.translation.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = verse.translation,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = TranslationFontFamily,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            ),
                            color = ink.copy(alpha = 0.66f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
            Text(
                text = footerRef,
                style = MaterialTheme.typography.labelLarge,
                color = gold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Beautiful Quran",
                style = MaterialTheme.typography.labelMedium,
                color = ink.copy(alpha = 0.38f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Soft paper dissolve so a height cap does not slice a glyph in half. */
private fun Modifier.fadeShareBodyIntoPaper(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = 56.dp.toPx()
            if (size.height <= 0f) return@drawWithContent
            drawRect(
                brush = Brush.verticalGradient(
                    startY = (size.height - fade).coerceAtLeast(0f),
                    endY = size.height,
                    colors = listOf(Color.Black, Color.Transparent),
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/** Quiet gold footer: single ref, or first…last when several. */
internal fun footerReference(verses: List<ShareVerseLine>): String {
    if (verses.isEmpty()) return ""
    if (verses.size == 1) return verses.first().reference
    val first = verses.first()
    val last = verses.last()
    return if (first.ref.surahId == last.ref.surahId) {
        val name = first.surahName.ifBlank { "Surah ${first.ref.surahId}" }
        "$name ${first.ref.surahId}:${first.ref.ayah}–${last.ref.ayah}"
    } else {
        "${first.reference} · ${last.reference}"
    }
}
