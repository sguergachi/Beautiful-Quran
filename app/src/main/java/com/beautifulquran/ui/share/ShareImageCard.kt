package com.beautifulquran.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beautifulquran.ui.theme.HafsFontFamily
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.TranslationFontFamily

internal val ShareImagePadH = 48.dp
internal val ShareImagePadTop = 56.dp
internal val ShareImagePadBetween = 14.dp
internal val ShareImagePadFooterTop = 24.dp
internal val ShareImagePadBottom = 32.dp

/** Verses plus one gold chapter strip at the end. */
internal fun shareImageSegmentCount(verseCount: Int): Int =
    if (verseCount <= 0) 0 else verseCount + 1

/** Last index of [shareImageSegmentCount] is the chapter footer, not a verse. */
internal fun shareImageIsFooterSegment(index: Int, verseCount: Int): Boolean =
    verseCount > 0 && index == verseCount

/**
 * Fixed paper sheet for image export — verses at rest in full ink.
 * Not the live [ReaderScreen]: a thin, offline-renderable card that ships the
 * same Hafs face and paper tokens without LazyColumn / playback / gestures.
 *
 * Always composed under [com.beautifulquran.ui.theme.BeautifulQuranTheme] with
 * [com.beautifulquran.data.ThemeMode.LIGHT] so shares stay readable parchment.
 *
 * Export draws this as **one strip per verse**, then a **separate** footer
 * attach, then stitches the bitmaps, so the GPU never rasterises the whole
 * wrap and the chapter line cannot be a stale last verse.
 */
@Composable
fun ShareImageCard(
    verses: List<ShareVerseLine>,
    includeTranslation: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        verses.forEachIndexed { index, verse ->
            ShareImageVerseStrip(
                verse = verse,
                includeTranslation = includeTranslation,
                padTop = if (index == 0) ShareImagePadTop else ShareImagePadBetween,
                padBottom = if (index == verses.lastIndex) 0.dp else ShareImagePadBetween,
            )
        }
        ShareImageFooterStrip(shareFooterCopy(verses))
    }
}

@Composable
fun ShareImageVerseStrip(
    verse: ShareVerseLine,
    includeTranslation: Boolean,
    padTop: Dp,
    padBottom: Dp,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.background
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(paper)
            .padding(start = ShareImagePadH, end = ShareImagePadH, top = padTop, bottom = padBottom),
    ) {
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

@Composable
fun ShareImageFooterStrip(
    copy: ShareFooterCopy,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold
    val paper = MaterialTheme.colorScheme.background
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(paper)
            .padding(
                start = ShareImagePadH,
                end = ShareImagePadH,
                top = ShareImagePadFooterTop,
                bottom = ShareImagePadBottom,
            ),
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(gold.copy(alpha = 0.45f)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = copy.chapter,
            style = MaterialTheme.typography.titleLarge,
            color = gold,
            textAlign = TextAlign.Center,
        )
        if (copy.verses.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = copy.verses,
                style = MaterialTheme.typography.labelMedium,
                color = gold.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Chapter display name and the verse citation that sits under it. */
data class ShareFooterCopy(
    val chapter: String,
    val verses: String,
)

/** Quiet gold colophon: display name above, verse numbers below. */
internal fun shareFooterCopy(verses: List<ShareVerseLine>): ShareFooterCopy {
    if (verses.isEmpty()) return ShareFooterCopy("", "")
    val first = verses.first()
    val last = verses.last()
    fun name(line: ShareVerseLine) =
        line.surahName.ifBlank { "Surah ${line.ref.surahId}" }
    val chapter = if (first.ref.surahId == last.ref.surahId) {
        name(first)
    } else {
        "${name(first)} · ${name(last)}"
    }
    val verseLine = if (first.ref.surahId == last.ref.surahId) {
        if (first.ref.ayah == last.ref.ayah) {
            "${first.ref.surahId}:${first.ref.ayah}"
        } else {
            "${first.ref.surahId}:${first.ref.ayah}–${last.ref.ayah}"
        }
    } else {
        "${first.ref.surahId}:${first.ref.ayah} · ${last.ref.surahId}:${last.ref.ayah}"
    }
    return ShareFooterCopy(chapter, verseLine)
}
