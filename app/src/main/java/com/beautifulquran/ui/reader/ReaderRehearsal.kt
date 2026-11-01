package com.beautifulquran.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Nothing is interactive and nothing reaches a view model.
 *
 * It must never cost a frame the reader can see. The caller only mounts it
 * while Home lies settled on top and nobody is touching the screen, and takes
 * it away the moment that stops. Here it is set one block per frame — the
 * opening, then the basmalah, then each verse — so no single frame carries the
 * whole page's composition and first draw; before every block it asks
 * [shouldYield], and on a yes it stops and calls [onYield] without [onDone]:
 * the caller tries again at the next quiet moment. [onDone] comes a few frames
 * after the last block has been drawn.
 */
@Composable
internal fun ReaderRehearsal(
    content: SurahContent,
    settings: Settings,
    shouldYield: () -> Boolean,
    onYield: () -> Unit,
    onDone: () -> Unit,
) {
    val surah = content.surah
    val verses = content.ayahs.take(REHEARSED_AYAHS)
    // 1 = opening, 2 = + basmalah, 3.. = + each verse.
    var blocks by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (blocks >= 1) ChapterOpening(
            chapterNumber = surah.id,
            nameArabic = surah.nameArabic,
            nameTransliteration = surah.nameTransliteration,
            nameTranslation = surah.nameTranslation,
            revelationPlace = surah.revelationPlace,
            ayahCount = surah.ayahCount,
            sheen = LocalGildingTilt.current,
        )
        if (blocks >= 2) BasmalahBlock(
            active = false,
            dimmed = false,
            fontScale = settings.fontScale,
        )
        verses.forEachIndexed { index, ayah ->
            if (blocks >= 3 + index) AyahBlock(
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
        val total = 2 + verses.size
        // A block per frame, then a few frames so the last recorded block has
        // actually reached the GPU.
        repeat(total + REHEARSAL_SETTLE_FRAMES) { frame ->
            withFrameNanos { }
            if (shouldYield()) {
                onYield()
                return@LaunchedEffect
            }
            if (frame < total) blocks = frame + 1
        }
        onDone()
    }
}

/** Enough verses to fill a phone's first screen in every reading mode. */
private const val REHEARSED_AYAHS = 3
private const val REHEARSAL_SETTLE_FRAMES = 4

/** Once per process: the caches it warms live as long as the process does. */
internal object ReaderRehearsalState {
    @Volatile
    var done: Boolean = false
}
