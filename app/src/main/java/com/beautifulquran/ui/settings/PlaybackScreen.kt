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
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.ui.theme.BrushCheckParams
import com.beautifulquran.ui.theme.verticalFadingEdges

/** The Settings row's one-line account of this page: the voice, and whether
 * a finished chapter hands on to the next. */
internal fun playbackSummary(reciterName: String?, autoplayNextChapter: Boolean): String =
    when {
        reciterName == null -> "Reciter and chapter flow"
        autoplayNextChapter -> "$reciterName · plays on to the next chapter"
        else -> "$reciterName · stops at the chapter's end"
    }

/**
 * Playback as its own sheet of paper: the voice, and what happens when a
 * chapter ends. Settings keeps only the row that opens this page.
 */
@Composable
internal fun PlaybackPage(
    settings: Settings,
    reciters: List<Reciter>,
    onSelectReciter: (Reciter) -> Unit,
    onOpenReciters: () -> Unit,
    onToggleAutoplay: (Boolean) -> Unit,
    checkParams: BrushCheckParams,
    checkPaintToken: Int,
    onBack: () -> Unit,
) {
    val favoriteReciters = reciters.filter { it.id in settings.favoriteReciterIds }
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
            LeafIntro("The voice, and what happens when a chapter ends.")

            Spacer(Modifier.height(36.dp))
            SectionLabel("Reciter")
            Spacer(Modifier.height(10.dp))
            favoriteReciters.forEach { reciter ->
                ReciterChoiceRow(
                    reciter = reciter,
                    selected = reciter.id == settings.reciterId,
                    onSelect = { onSelectReciter(reciter) },
                )
            }
            if (favoriteReciters.isEmpty()) {
                Caption("No favorites yet — keep them from the full list.")
            }
            ShowAllRecitersLink(total = reciters.size, onClick = onOpenReciters)

            Spacer(Modifier.height(48.dp))
            ToggleRow(
                label = "Play on to the next chapter",
                checked = settings.autoplayNextChapter,
                onChange = onToggleAutoplay,
                checkParams = checkParams,
                checkPaintToken = checkPaintToken,
            )
            Caption("Pins play in your order, then the book carries on.")

            Spacer(Modifier.height(48.dp))
        }
    }
}
