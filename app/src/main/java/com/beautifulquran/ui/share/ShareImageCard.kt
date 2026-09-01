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
 * The sheet is as tall as the gather: every verse, then the gold chapter
 * footer. Do not clip to a phone-screen height. Pixel scale is the
 * gather size — see [com.beautifulquran.share.shareImageScale].
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(paper)
            .padding(horizontal = 48.dp, vertical = 56.dp),
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
