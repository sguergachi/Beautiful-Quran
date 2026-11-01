package com.beautifulquran.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.Settings
import com.beautifulquran.data.model.SurahContent
import com.beautifulquran.ui.theme.LocalGildingTilt

/**
 * The first page of a chapter, set once where nobody can see it, so the first
 * chapter the reader really opens is not the first one this process has drawn.
 *
 * Why: every open remounts [ReaderScreen] (`readerSession`), yet only the
 * first open after launch hitched; a second open, of *any* chapter, was smooth.
 * Release traces (emulator, 2026-11-01) put the difference on the render
 * thread's first use of the page: the largest single cost was a ~37 ms path
 * rasterisation as the turn settled (a 64 ms frame), plus first glyph uploads
 * for the verse faces. Drawing the same composables once, under the opaque
 * home sheet, removed that frame; the first open went from 68/39/64 ms frames
 * to 52/31 ms. What it does not cover: ~16 GPU shader programs the turn itself
 * compiles (a tilted copy here did not reach them). A phone keeps those on
 * disk between launches, so they cost once per install, not once per launch.
 *
 * It is the real [ChapterOpening], [BasmalahBlock] and [AyahBlock] with the
 * reader's own settings — a lookalike would warm the wrong shaders and faces.
 * Nothing is interactive and nothing reaches a view model. [onDone] removes it
 * after a few frames; it is drawn exactly where an opened reader sits under
 * the home sheet, so it is never visible.
 */
@Composable
internal fun ReaderRehearsal(
    content: SurahContent,
    settings: Settings,
    onDone: () -> Unit,
) {
    val surah = content.surah
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChapterOpening(
            chapterNumber = surah.id,
            nameArabic = surah.nameArabic,
            nameTransliteration = surah.nameTransliteration,
            nameTranslation = surah.nameTranslation,
            revelationPlace = surah.revelationPlace,
            ayahCount = surah.ayahCount,
            sheen = LocalGildingTilt.current,
        )
        BasmalahBlock(
            active = false,
            dimmed = false,
            fontScale = settings.fontScale,
        )
        content.ayahs.take(REHEARSED_AYAHS).forEach { ayah ->
            AyahBlock(
                ayah = ayah,
                readingMode = settings.readingMode,
                activeWord = null,
                playbackSpeed = 1f,
                isActiveAyah = false,
                dimmed = false,
                obscuredBySelector = false,
                fontScale = settings.fontScale,
                showGloss = settings.showWordGloss,
                showTransliteration = settings.showTransliteration,
                showTranslation = settings.showTranslation,
                verseNumberScript = settings.verseNumberScript,
                bookmarkSide = AyahSelectorSide.RIGHT,
                onWordClick = null,
                onAyahClick = {},
                reciting = false,
            )
        }
    }
    LaunchedEffect(Unit) {
        // Composition, then a draw the render thread has to execute: a few
        // frames so the recorded page has actually reached the GPU.
        repeat(REHEARSAL_FRAMES) { withFrameNanos { } }
        onDone()
    }
}

/** Enough verses to fill a phone's first screen in every reading mode. */
private const val REHEARSED_AYAHS = 3
private const val REHEARSAL_FRAMES = 4

/** Once per process: the caches it warms live as long as the process does. */
internal object ReaderRehearsalState {
    @Volatile
    var done: Boolean = false
}
