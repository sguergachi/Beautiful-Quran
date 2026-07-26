package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.beautifulquran.ui.home.SearchDialWheel
import com.beautifulquran.ui.theme.InkCircledChoiceColumn
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
 * picking "from this ayah" reveals a wheel of *counts* beside the ayah that
 * count lands on — the reader knows how far on they want to go, not which ayah
 * that is. Neither is captioned: the joining words sit in the gutter, so each
 * dial reads straight across. Everything applies on Done, the sheet's one
 * action; the quiet margins are the only way out. A second "Not now" line used to sit 4 dp under
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
    // "From this ayah" is held as a *count* of ayahs, because that is what the
    // reader knows from where they are sitting — the ayah it lands on is the
    // dial's answer, not its input.
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
                (safeCurrentAyah + nextNCount - 1).coerceAtMost(safeAyahCount),
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
            // the Settings sheet paints around its inline choices. A choice that
            // needs numbers unfolds them directly under itself, so the wheels
            // belong to the line that asked for them rather than trailing the
            // whole list.
            InkCircledChoiceColumn(
                entries = RepeatChoice.entries,
                selected = choice,
                label = { it.label },
                onSelect = { choice = it },
            ) { entry ->
                // Neither dial is captioned: each spells its own range out with
                // the "to" standing between the two figures.
                when (entry) {
                    RepeatChoice.AYAH_RANGE -> RepeatWheelBlock {
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
                    }

                    RepeatChoice.NEXT_N_AYAHS -> RepeatWheelBlock {
                        RepeatFromHereDial(
                            startAyah = safeCurrentAyah,
                            ayahCount = safeAyahCount,
                            count = nextNCount,
                            onCountChange = { nextNCount = it },
                        )
                    }

                    else -> Unit
                }
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

/** One item's height on every wheel this sheet shows. */
private val WheelItemHeight = 42.dp

/**
 * How much of the surrounding ayahs the wheel keeps in view — four rows.
 *
 * Taller than this and a dial parked at either end of a surah (ayah 1, or the
 * last ayah) is mostly empty paper, which read as an unfinished layout rather
 * than a wheel that has run out of numbers.
 */
private val WheelHeight = 168.dp

/**
 * A single dial's measure. The wheels are a compact block in the middle of the
 * sheet, not two columns pushed to opposite margins: a range is one phrase —
 * "1 to 75" — and the two figures have to sit close enough to read as a pair.
 */
private val WheelColumnWidth = 110.dp

/**
 * The paper between the two range wheels — wide enough to hold the word "to" on
 * the reading line, since that word *is* the range's caption.
 */
private val WheelGutter = 34.dp

/** Both dials plus the gutter, so the pair can be centred as one block. */
private val WheelPairWidth = WheelColumnWidth * 2 + WheelGutter

/**
 * "From this ayah" needs a wider gutter than the range's bare "to": it names
 * the wheel's unit as well as joining the two figures ("4 · ayahs, to · 234").
 */
private val FromHereGutterWidth = 78.dp

/** That dial's three slots, so it too can be centred as one block. */
private val FromHereBlockWidth = WheelColumnWidth * 2 + FromHereGutterWidth

/**
 * A choice's numbers, unfolding under the line that asked for them.
 *
 * No captions anywhere: both dials are written as *two figures with "to"
 * between them*, which is the sentence a caption would have spelled out. What
 * separates them is which figures move — the range offers two wheels, "from
 * this ayah" pins the left figure to where the reader already is.
 *
 * Nothing is drawn under the wheel — **no band, no plate, not even a wash.**
 * The reading line is the row the numbers fade *towards*: rows dissolve into the
 * sheet as they leave the centre (`SearchDialWheel`'s own edge fade), and the
 * centred figure holds full ink in the accent while its neighbours sit faint.
 * Ink strength and the fade are the whole affordance, which is what keeps this
 * one sheet of paper rather than a control resting on it.
 */
@Composable
private fun RepeatWheelBlock(dial: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        dial()
        Spacer(Modifier.height(18.dp))
    }
}

/** The word standing between the two figures of a range, on the reading line. */
@Composable
private fun BoxScope.RangeJoint() {
    Text(
        text = "to",
        style = MaterialTheme.typography.bodyMedium,
        // Quiet ink — it is the joint between the figures, not a third figure.
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier.align(Alignment.Center),
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
    // The wheels dissolve into the ink-bleed paper this sheet is painted on,
    // not into `surface` — see SearchDialWheel's `fadeColor`.
    val paper = MaterialTheme.colorScheme.background

    BoxWithConstraints(
        modifier = Modifier
            .width(WheelPairWidth)
            .height(WheelHeight),
    ) {
        val wheelEdgePadding = ((maxHeight - WheelItemHeight) / 2).coerceAtLeast(0.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(WheelGutter),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SearchDialWheel(
                itemCount = ayahCount,
                selectedIndex = (from - 1).coerceIn(0, ayahCount - 1),
                itemHeight = WheelItemHeight,
                edgePadding = wheelEdgePadding,
                onSelectedIndexChange = { onFromChange(it + 1) },
                modifier = Modifier.weight(1f),
                fadeColor = paper,
            ) { index, selected ->
                RepeatNumberItem(index + 1, selected)
            }
            SearchDialWheel(
                itemCount = ayahCount,
                selectedIndex = (to - 1).coerceIn(0, ayahCount - 1),
                itemHeight = WheelItemHeight,
                edgePadding = wheelEdgePadding,
                onSelectedIndexChange = { onToChange(it + 1) },
                modifier = Modifier.weight(1f),
                fadeColor = paper,
            ) { index, selected ->
                RepeatNumberItem(index + 1, selected)
            }
        }
        RangeJoint()
    }
}

/**
 * "From this ayah": you turn a **count**, the sheet answers with the **ayah**.
 *
 * This is the one dial whose two figures are not the same kind of thing. What
 * the reader has is "about four ayahs on" — the number they can feel from where
 * they are sitting; what they have to hand the player is an ayah number they do
 * not know. So the wheel carries the count, the gutter names its unit, and the
 * figure on the right is *derived*: it is not a second wheel, it is the answer,
 * moving as the count turns. Both ends of the sentence hold the accent because
 * they are one fact said twice — "4 ayahs, to 234".
 *
 * (Turning the wheel through ayah numbers instead reads more tidily, but it
 * asks the reader for the one number they came here without.)
 */
@Composable
private fun RepeatFromHereDial(
    startAyah: Int,
    ayahCount: Int,
    count: Int,
    onCountChange: (Int) -> Unit,
) {
    val paper = MaterialTheme.colorScheme.background
    val first = startAyah.coerceIn(1, ayahCount)
    // The loop cannot run past the end of the surah, so neither can the count.
    val maxCount = (ayahCount - first + 1).coerceAtLeast(1)
    val safeCount = count.coerceIn(1, maxCount)

    BoxWithConstraints(
        modifier = Modifier
            .width(FromHereBlockWidth)
            .height(WheelHeight),
    ) {
        val wheelEdgePadding = ((maxHeight - WheelItemHeight) / 2).coerceAtLeast(0.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            SearchDialWheel(
                itemCount = maxCount,
                selectedIndex = safeCount - 1,
                itemHeight = WheelItemHeight,
                edgePadding = wheelEdgePadding,
                onSelectedIndexChange = { onCountChange(it + 1) },
                modifier = Modifier.weight(1f),
                fadeColor = paper,
            ) { index, selected ->
                RepeatNumberItem(index + 1, selected)
            }
            // Fixed width, so naming the unit can never shift the figures on
            // either side of it as the count crosses from "ayah" to "ayahs".
            Box(
                modifier = Modifier
                    .width(FromHereGutterWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (safeCount == 1) "ayah, to" else "ayahs, to",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (first + safeCount - 1).toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
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
