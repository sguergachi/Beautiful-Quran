package com.beautifulquran.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.Settings
import com.beautifulquran.ui.theme.BrushCheckParams
import com.beautifulquran.ui.theme.BrushCircleParams
import com.beautifulquran.ui.theme.InkCircledChoiceRow
import com.beautifulquran.ui.theme.verticalFadingEdges

/** The pauses Playback offers between verses, in seconds; 0 is none. */
internal val VERSE_GAP_CHOICES = listOf(0, 1, 2, 3, 5)

internal fun verseGapLabel(seconds: Int): String = if (seconds == 0) "None" else "$seconds s"

/** The Settings row's one-line account of this page: whether a finished
 * chapter hands on to the next, and any pause between verses. */
internal fun playbackSummary(autoplayNextChapter: Boolean, verseGapSeconds: Int = 0): String {
    val chapters = if (autoplayNextChapter) "Plays on to the next chapter" else "Stops at the chapter's end"
    return if (verseGapSeconds > 0) "$chapters · $verseGapSeconds s between verses" else chapters
}

/**
 * How recitation carries on, as its own sheet of paper. The voice itself is
 * chosen on Settings, under Reciter; this page holds what happens around it.
 */
@Composable
internal fun PlaybackPage(
    settings: Settings,
    onToggleAutoplay: (Boolean) -> Unit,
    onSetVerseGap: (Int) -> Unit,
    checkParams: BrushCheckParams,
    checkPaintToken: Int,
    brushParams: BrushCircleParams,
    paintToken: Int,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalFadingEdges(
                    color = MaterialTheme.colorScheme.background,
                    top = 20.dp,
                    bottom = 40.dp,
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            BackChevron(onBack)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Playback",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            LeafIntro("How recitation carries on between verses and chapters.")

            Spacer(Modifier.height(36.dp))
            ToggleRow(
                label = "Play on to the next chapter",
                checked = settings.autoplayNextChapter,
                onChange = onToggleAutoplay,
                checkParams = checkParams,
                checkPaintToken = checkPaintToken,
            )
            Caption("Pins play in your order, then the book carries on.")

            Spacer(Modifier.height(36.dp))
            SectionLabel("Pause between verses")
            Spacer(Modifier.height(10.dp))
            InkCircledChoiceRow(
                entries = VERSE_GAP_CHOICES,
                selected = settings.verseGapSeconds,
                params = brushParams,
                paintToken = paintToken,
                label = ::verseGapLabel,
                onSelect = onSetVerseGap,
            )
            Caption("Silence after each verse, to repeat it or reflect.")

            Spacer(Modifier.height(48.dp))
        }
    }
}
