package com.beautifulquran.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.beautifulquran.ui.home.SearchDialWheel
import com.beautifulquran.ui.theme.InkCircledChoiceColumn
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.UnselectedChoiceInk
import com.beautifulquran.ui.theme.quietClickable

/** How playback should loop, chosen on the repeat sheet. */
enum class RepeatChoice(val label: String) {
    OFF("Off"),
    ONE_AYAH("This ayah"),
    WHOLE_SURAH("Whole surah"),
    AYAH_RANGE("A range of ayahs"),
    NEXT_N_AYAHS("From this ayah"),
}

/** Retains an explicit choice when multiple controls describe the same range. */
internal fun repeatChoice(
    repeatMode: Int,
    repeatRange: IntRange?,
    currentAyah: Int,
    retainedChoice: RepeatChoice?,
): RepeatChoice {
    val retainedRangeChoice = retainedChoice?.takeIf {
        it == RepeatChoice.AYAH_RANGE || it == RepeatChoice.NEXT_N_AYAHS
    }
    return when {
        repeatRange != null && retainedRangeChoice != null -> retainedRangeChoice
        repeatRange != null &&
            repeatRange.first == currentAyah &&
            repeatRange.first < repeatRange.last -> RepeatChoice.NEXT_N_AYAHS
        repeatRange != null && repeatRange.first == repeatRange.last -> RepeatChoice.ONE_AYAH
        repeatRange != null -> RepeatChoice.AYAH_RANGE
        repeatMode == Player.REPEAT_MODE_ONE -> RepeatChoice.ONE_AYAH
        repeatMode == Player.REPEAT_MODE_ALL -> RepeatChoice.WHOLE_SURAH
        else -> RepeatChoice.OFF
    }
}

/**
 * Choosing how the recitation repeats — **not a dialog**.
 *
 * The reader sheet itself becomes the question: `ReaderScreen` hosts this inside
 * an [com.beautifulquran.ui.theme.InkRevealOverlay], so ink bleeds out from the
 * player bar's repeat control and soaks the page, the same shared reveal the Root
 * Word Viewer and the word-hold chooser use. Nothing floats above the paper and
 * there is no scrim.
 *
 * Selection uses the app's own vocabulary rather than Material's: the chosen
 * line holds full ink while the others sit faint, and
 * [com.beautifulquran.ui.theme.InkCircledChoiceColumn] paints the shared
 * ink-brush circle around it — the same mark the Settings sheet loops around its
 * inline choices. No radio buttons, no card, no Material buttons.
 *
 * Picking "a range of ayahs" reveals two vertical wheels to bound the loop;
 * picking "from this ayah" reveals a single wheel counting forward from the
 * current ayah. Everything applies on Done, the sheet's one action; the quiet
 * margins are the only way out. A second "Not now" line used to sit 4 dp under
 * Done — same type vocabulary as the choices, so the sheet read as one long
 * list, and discarding the pick was a thumb-width from committing it.
 *
 * This replaced a stock Material `Dialog` that had been violating AGENTS.md
 * invariant 4 (recorded, and now resolved, in docs/DESIGN.md).
 */
@Composable
fun RepeatSheet(
    ayahCount: Int,
    repeatMode: Int,
    repeatRange: IntRange?,
    currentAyah: Int?,
    retainedChoice: RepeatChoice?,
    onDismiss: () -> Unit,
    onRepeatMode: (Int) -> Unit,
    onRepeatRange: (Int, Int) -> Unit,
    onChoiceApplied: (RepeatChoice) -> Unit,
) {
    val safeAyahCount = ayahCount.coerceAtLeast(1)
    val safeCurrentAyah = (currentAyah ?: 1).coerceIn(1, safeAyahCount)
    val isNextNRange = repeatRange != null &&
        repeatRange.first == safeCurrentAyah &&
        repeatRange.first < repeatRange.last
    var choice by remember {
        mutableStateOf(
            repeatChoice(repeatMode, repeatRange, safeCurrentAyah, retainedChoice),
        )
    }
    var from by remember {
        mutableIntStateOf((repeatRange?.first ?: safeCurrentAyah).coerceIn(1, safeAyahCount))
    }
    var to by remember {
        mutableIntStateOf((repeatRange?.last ?: safeAyahCount).coerceIn(1, safeAyahCount))
    }
    val maxNextNCount = (safeAyahCount - safeCurrentAyah + 1).coerceAtLeast(1)
    var nextNCount by remember {
        val opening = if (isNextNRange) repeatRange.count() else 2
        mutableIntStateOf(opening.coerceIn(1, maxNextNCount))
    }

    fun commit() {
        when (choice) {
            RepeatChoice.OFF -> onRepeatMode(Player.REPEAT_MODE_OFF)
            RepeatChoice.ONE_AYAH -> onRepeatMode(Player.REPEAT_MODE_ONE)
            RepeatChoice.WHOLE_SURAH -> onRepeatMode(Player.REPEAT_MODE_ALL)
            RepeatChoice.AYAH_RANGE -> onRepeatRange(from, to)
            RepeatChoice.NEXT_N_AYAHS -> onRepeatRange(
                safeCurrentAyah,
                safeCurrentAyah + nextNCount - 1,
            )
        }
        // Remember the *choice*, not just the resulting range: "a range of
        // ayahs" and "from this ayah" can describe the same IntRange, so
        // reopening must show the one the reader actually picked (#548).
        onChoiceApplied(choice)
        onDismiss()
    }

    // The margins are quiet paper: tapping them puts the question away.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .quietClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                // Centred while it fits, scrollable once a wheel opens on a
                // short screen — the sheet never clips its own Done line.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 40.dp)
                // Absorb the column's own taps so only the margins dismiss.
                .quietClickable(onClick = {}),
        ) {
            Text(
                text = "Repeat",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(24.dp))

            // The shared ink-brush circle loops the chosen line — the same mark
            // the Settings sheet paints around its inline choices.
            InkCircledChoiceColumn(
                entries = RepeatChoice.entries,
                selected = choice,
                label = { it.label },
                onSelect = { choice = it },
            )

            if (choice == RepeatChoice.AYAH_RANGE) {
                Spacer(Modifier.height(20.dp))
                RepeatWheelCaption("Ayah $from to $to")
                Spacer(Modifier.height(10.dp))
                RepeatRangeDials(
                    ayahCount = safeAyahCount,
                    from = from,
                    to = to,
                    onFromChange = {
                        from = it
                        if (to < it) to = it
                    },
                    onToChange = {
                        to = it
                        if (from > it) from = it
                    },
                )
            } else if (choice == RepeatChoice.NEXT_N_AYAHS) {
                Spacer(Modifier.height(20.dp))
                RepeatWheelCaption(
                    "Repeat $nextNCount ayah${if (nextNCount == 1) "" else "s"} " +
                        "from ayah $safeCurrentAyah",
                )
                Spacer(Modifier.height(10.dp))
                RepeatCountDial(
                    count = nextNCount,
                    maxCount = maxNextNCount,
                    onCountChange = { nextNCount = it },
                )
            }

            // Done sits well clear of the last choice — a 20 dp gap would read
            // as one more line in the list — and carries the sheet's strongest
            // ink, so the action is never quieter than the options above it.
            Spacer(Modifier.height(48.dp))
            Text(
                text = "Done",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .quietClickable(onClick = ::commit)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun RepeatWheelCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RepeatRangeDials(
    ayahCount: Int,
    from: Int,
    to: Int,
    onFromChange: (Int) -> Unit,
    onToChange: (Int) -> Unit,
) {
    val accents = LocalQuranAccents.current
    val itemHeight = 42.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            RepeatWheelLabel("Start", Modifier.weight(1f))
            RepeatWheelLabel("End", Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(208.dp),
        ) {
            val wheelEdgePadding = ((maxHeight - itemHeight) / 2).coerceAtLeast(0.dp)
            // A soft gilt band marks the reading line of the wheel — an ink
            // wash on the page, not a selected-item container.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accents.gold.copy(alpha = 0.12f)),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SearchDialWheel(
                    itemCount = ayahCount,
                    selectedIndex = (from - 1).coerceIn(0, ayahCount - 1),
                    itemHeight = itemHeight,
                    edgePadding = wheelEdgePadding,
                    onSelectedIndexChange = { onFromChange(it + 1) },
                    modifier = Modifier.weight(1f),
                ) { index, selected ->
                    RepeatNumberItem(index + 1, selected)
                }
                SearchDialWheel(
                    itemCount = ayahCount,
                    selectedIndex = (to - 1).coerceIn(0, ayahCount - 1),
                    itemHeight = itemHeight,
                    edgePadding = wheelEdgePadding,
                    onSelectedIndexChange = { onToChange(it + 1) },
                    modifier = Modifier.weight(1f),
                ) { index, selected ->
                    RepeatNumberItem(index + 1, selected)
                }
            }
        }
    }
}

@Composable
private fun RepeatCountDial(
    count: Int,
    maxCount: Int,
    onCountChange: (Int) -> Unit,
) {
    val accents = LocalQuranAccents.current
    val itemHeight = 42.dp
    val safeMaxCount = maxCount.coerceAtLeast(1)

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        RepeatWheelLabel("Count", Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(208.dp),
        ) {
            val wheelEdgePadding = ((maxHeight - itemHeight) / 2).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.5f)
                    .height(itemHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accents.gold.copy(alpha = 0.12f)),
            )
            SearchDialWheel(
                itemCount = safeMaxCount,
                selectedIndex = (count - 1).coerceIn(0, safeMaxCount - 1),
                itemHeight = itemHeight,
                edgePadding = wheelEdgePadding,
                onSelectedIndexChange = { onCountChange(it + 1) },
                modifier = Modifier.fillMaxWidth(),
            ) { index, selected ->
                RepeatNumberItem(index + 1, selected)
            }
        }
    }
}

@Composable
private fun RepeatWheelLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun RepeatNumberItem(value: Int, selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = UnselectedChoiceInk)
            },
        )
    }
}
