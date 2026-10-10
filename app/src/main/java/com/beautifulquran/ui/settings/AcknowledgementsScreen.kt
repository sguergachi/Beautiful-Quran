package com.beautifulquran.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.QuranTheme
import com.beautifulquran.ui.theme.verticalFadingEdges

/** One credit line: what it is, and who it belongs to. */
internal data class Acknowledgement(
    val title: String,
    val body: String,
)

/** The sources this app stands on — same list as README's Data & attribution. */
internal val ACKNOWLEDGEMENTS = listOf(
    Acknowledgement(
        title = "Quran text and translation",
        body = "Uthmani script and Saheeh International translation via the " +
            "quran-json project, from Tanzil and Al Quran Cloud. Free with attribution.",
    ),
    Acknowledgement(
        title = "Word-by-word gloss",
        body = "Word-by-word translation, transliteration, and QCF layout from the " +
            "Quran Foundation authenticated Content API, held in a seven-day local cache. " +
            "Governed by the QF Developer Terms.",
    ),
    Acknowledgement(
        title = "Roots and morphology",
        body = "Root, lemma, and morphological annotation from the Quranic Arabic Corpus " +
            "(corpus.quran.com), © Kais Dukes, University of Leeds. Free with attribution and link.",
    ),
    Acknowledgement(
        title = "Word timings",
        body = "Word-level audio timing data © the quran-align project contributors, CC-BY 4.0.",
    ),
    Acknowledgement(
        title = "Yasser Al-Dosari timings",
        body = "Word timings from Qur’anic Universal Audio, CC-BY 4.0.",
    ),
    Acknowledgement(
        title = "Repeat-aware timings",
        body = "Bundled repeat topology from the quran.com legacy qdc audio API, normalized offline. " +
            "Written QF permission requested before release.",
    ),
    Acknowledgement(
        title = "Recitation audio",
        body = "Streamed from everyayah.com. Free; all rights to the recitations " +
            "belong to the respective reciters.",
    ),
    Acknowledgement(
        title = "English typeface",
        body = "Timeless Serif and Timeless Sans © Timeless Ventures Private Limited, Chennai " +
            "(timeless.co), used under the Timeless Free Font License. EB Garamond and " +
            "Cormorant Garamond (SIL OFL 1.1) set the few transliteration letters Timeless lacks.",
    ),
    Acknowledgement(
        title = "Arabic typeface",
        body = "KFGQPC HAFS Uthmanic Script © King Fahd Glorious Quran Printing Complex, Madinah. " +
            "Redistribution permission / official license confirmation pending.",
    ),
)

/** The credits leaf: every source the app stands on, on its own sheet of paper. */
@Composable
internal fun AcknowledgementsPage(onBack: () -> Unit) {
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
                text = "Acknowledgements",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Caption("Every source this app stands on.")
            ACKNOWLEDGEMENTS.forEach { acknowledgement ->
                Spacer(Modifier.height(28.dp))
                Text(
                    text = acknowledgement.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = acknowledgement.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = QuranTheme.ink.quiet,
                )
            }
            Spacer(Modifier.height(32.dp))
            Caption("This app is free, ad-free, and collects no data.")
            Spacer(Modifier.height(48.dp))
        }
    }
}
