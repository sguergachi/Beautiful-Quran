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
internal val ShareImagePadFooterTop = 40.dp
internal val ShareImagePadBottom = 56.dp

/** Chapter colophon is composed on the last verse strip so stitching cannot drop it. */
internal fun shareImageFooterOnStrip(index: Int, verseCount: Int): Boolean =
    verseCount > 0 && index == verseCount - 1

/**
 * Fixed paper sheet for image export — verses at rest in full ink.
 * Not the live [ReaderScreen]: a thin, offline-renderable card that ships the
 * same Hafs face and paper tokens without LazyColumn / playback / gestures.
 *
 * Always composed under [com.beautifulquran.ui.theme.BeautifulQuranTheme] with
 * [com.beautifulquran.data.ThemeMode.LIGHT] so shares stay readable parchment.
 *
 * Export draws this as **one strip per verse plus the footer**, then stitches
 * the bitmaps, so the GPU never rasterises the whole wrap.
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
            ShareImageExportStrip(
                verse = verse,
                includeTranslation = includeTranslation,
                padTop = if (index == 0) ShareImagePadTop else ShareImagePadBetween,
                padBottom = if (index == verses.lastIndex) 0.dp else ShareImagePadBetween,
                footerRef = if (index == verses.lastIndex) footerReference(verses) else null,
            )
        }
    }
}

/**
 * One export strip: the verse, and on the last strip the gold chapter
 * footer so stitching cannot drop the colophon.
 */
@Composable
fun ShareImageExportStrip(
    verse: ShareVerseLine,
    includeTranslation: Boolean,
    padTop: Dp,
    padBottom: Dp,
    footerRef: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ShareImageVerseStrip(
            verse = verse,
            includeTranslation = includeTranslation,
            padTop = padTop,
            padBottom = padBottom,
        )
        if (footerRef != null) {
            ShareImageFooterStrip(footerRef)
        }
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
    footerRef: String,
    modifier: Modifier = Modifier,
) {
    val gold = LocalQuranAccents.current.gold
    val ink = MaterialTheme.colorScheme.onSurface
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
