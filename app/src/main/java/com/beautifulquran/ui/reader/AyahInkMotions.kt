package com.beautifulquran.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.beautifulquran.data.model.Ayah
import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.TajweedPacing

/** Shared ink clocks for one ayah — used by [AyahBlock] and mushaf lines. */
internal data class AyahInkPack(
    val motions: List<InkMotion>,
    val recessCover: State<Float>,
    val markAlpha: State<Float>,
    val searchHitWash: RepeatWash,
    val searchHitWordPositions: Set<Int> = emptySet(),
    val searchFocusPositions: Set<Int>? = null,
    val searchBackgroundAlpha: State<Float>,
    /** A motionless mushaf ayah that still waits beneath the page recess. */
    val wholeAyahRecess: Boolean = false,
)

internal fun ayahRecessCoverTarget(dimmed: Boolean, enteringFromRecess: Boolean): Float =
    if (dimmed || enteringFromRecess) 1f - InkEngine.State.Upcoming.inkAlpha() else 0f

/**
 * The active word's wash for one ayah, before [rememberInkMotions] turns it
 * into per-word clocks: its sweep, tajweed pacing, and what it carries in from
 * the word before and lends to the word after (wasl bloom, short-word lead).
 * [AyahBlock] and [rememberAyahInkPack] both build it with [rememberAyahWordWash],
 * so the scrolling reader and the mushaf can never drift apart.
 */
internal class AyahWordWash(
    val sweepMs: Int?,
    val repeatDwellMs: Int?,
    val activation: Long,
    val activeWordStartMs: Long,
    val pacing: TajweedPacing.Curve?,
    val inks: List<InkEngine.Word>,
    val activeIndex: Int,
    val outgoingLead: InkEngine.ShortWordLead?,
    val incomingLeadReach: Float,
    val activeRevealStart: Float,
    val waslPrefixes: List<WaslPrefix?>,
)

/**
 * Letter-level tajweed pacing of the active word's sweep (Ink Lab toggle,
 * docs/TAJWEED_PACING.md): null keeps the plain constant-rate wash. Gated on
 * this ayah owning the active word — not the fade-led isActiveAyah — so a
 * waqf hold is not dropped 500 ms before the audio boundary (see
 * InkEngine.wordState). The verse-closing word is flagged so its waqf can be
 * sustained.
 */
internal fun activeWordPacing(words: List<Word>, activeWord: ActiveWord?): TajweedPacing.Curve? =
    activeWord?.let { aw ->
        val idx = words.indexOfFirst { it.position == aw.wordPosition }
        words.getOrNull(idx)?.let { word ->
            InkEngine.pacing(
                arabic = word.arabic,
                activeWord = aw,
                isAyahFinal = word.position == words.lastOrNull()?.position,
                prevArabic = words.getOrNull(idx - 1)?.arabic,
            )
        }
    }

/**
 * The lead a short next word takes from the active word's tail
 * ([InkEngine.shortWordLead]), or null: no adjacent next word in this ayah, a
 * tap-seeded word, a next owner that is not the following word (a repeat
 * backtrack), or [enabled] false (English Scroll's words are not Arabic).
 */
internal fun outgoingShortWordLead(
    words: List<Word>,
    activeIndex: Int,
    activeWord: ActiveWord?,
    sweepMs: Int?,
    activePaced: Boolean,
    connected: Boolean,
    playbackSpeed: Float,
    enabled: Boolean,
): InkEngine.ShortWordLead? =
    words.getOrNull(activeIndex + 1)?.takeIf {
        enabled && activeIndex >= 0 && activeWord?.fromTap != true &&
            activeWord?.nextWordPosition == it.position
    }?.let { next ->
        InkEngine.shortWordLead(
            activeSweepMs = sweepMs,
            activePaced = activePaced,
            activeRepeat = activeWord?.isRepeat == true,
            nextArabic = next.arabic,
            nextSweepMs = InkEngine.nextSweepMs(activeWord, playbackSpeed),
            nextPaced = InkEngine.paces(
                next.arabic,
                isAyahFinal = next.position == words.lastOrNull()?.position,
                prevArabic = words[activeIndex].arabic,
            ),
            connected = connected,
        )
    }

/**
 * Where the active word's main wash starts: the edge its wasl bloom reached
 * during the previous word's tail when it took over by playing on, else the
 * short-word lead's reach ([incomingLeadReach], 0 when nothing led it in).
 */
internal fun activeWashRevealStart(
    carriedIncoming: Boolean,
    incomingConnection: TajweedPacing.Connection?,
    incomingHandoff: Float,
    mainFeather: Float,
    incomingLeadReach: Float,
): Float = if (carriedIncoming && incomingConnection != null) {
    waslWashProgress(
        windowProgress = incomingHandoff,
        endProgress = waslContinuationStart(
            prefixFraction = incomingConnection.prefixFraction,
            mainFeather = mainFeather,
        ),
    )
} else {
    // A short word led in during its predecessor's tail continues from there.
    incomingLeadReach
}

/**
 * Each word's carry-in for this frame's wash, aligned with the ayah's words.
 * [fullWindow] is a latched window (1) that redraws exactly the partial edge
 * carried across handoff while the active word's ordinary sweep continues
 * from it; [outgoingWindow] and [leadWindow] are the live 0→1 windows of the
 * active word's wasl bloom and short-word lead on the next word.
 */
internal fun ayahWaslPrefixes(
    wordCount: Int,
    activeIndex: Int,
    carriedIncoming: Boolean,
    incomingConnection: TajweedPacing.Connection?,
    outgoingConnection: TajweedPacing.Connection?,
    incomingHandoff: Float,
    activeRevealStart: Float,
    incomingLeadReach: Float,
    outgoingLead: InkEngine.ShortWordLead?,
    mainFeather: Float,
    fullWindow: State<Float>,
    outgoingWindow: State<Float>,
    leadWindow: State<Float>,
): List<WaslPrefix?> = (0 until wordCount).map { index ->
    when {
        // Preserve the outgoing bloom's reached edge only across a natural
        // adjacent handoff. A seek bumps activation and starts clean.
        index == activeIndex && carriedIncoming && incomingConnection != null ->
            WaslPrefix(
                windowProgress = fullWindow,
                endProgress = activeRevealStart,
                feather = mainFeather,
                letterEndProgress = waslWashProgress(incomingHandoff, incomingConnection.prefixFraction),
            )
        index == activeIndex + 1 && outgoingConnection != null ->
            WaslPrefix(
                windowProgress = outgoingWindow,
                endProgress = waslContinuationStart(
                    prefixFraction = outgoingConnection.prefixFraction,
                    mainFeather = mainFeather,
                ),
                feather = mainFeather,
                letterEndProgress = outgoingConnection.prefixFraction,
            )
        index == activeIndex && incomingLeadReach > 0f ->
            WaslPrefix(
                windowProgress = fullWindow,
                endProgress = incomingLeadReach,
                feather = mainFeather,
                letterEndProgress = incomingLeadReach,
            )
        index == activeIndex + 1 && outgoingLead != null ->
            WaslPrefix(
                windowProgress = leadWindow,
                endProgress = outgoingLead.reach,
                feather = InkEngine.tuning.washFeather,
                letterEndProgress = outgoingLead.reach,
            )
        else -> null
    }
}

/**
 * The one owner of the active word's wash clocks for an ayah ([AyahWordWash]).
 * Not restartable (it returns a value), so its reads stay in the caller's
 * recompose scope, and its remembers and SideEffect keep their order.
 * [shortWordLeads] false skips the short-word lead (English Scroll).
 */
@Composable
internal fun rememberAyahWordWash(
    ayah: Ayah,
    activeWord: ActiveWord?,
    playbackSpeed: Float,
    isActiveAyah: Boolean,
    dimmed: Boolean,
    shortWordLeads: Boolean = true,
): AyahWordWash {
    // The letter fade paces itself to how long the reciter dwells on the
    // word, corrected for the chosen playback speed.
    val sweepMs = InkEngine.sweepMs(activeWord, playbackSpeed)
    // Repeat washes share the same audio handoff but must not inherit the
    // ordinary sweep's visual minimum and continue past the spoken word.
    val repeatDwellMs = InkEngine.repeatDwellMs(activeWord, playbackSpeed)
    val activation = activeWord?.activation ?: 0L
    val activeWordStartMs = activeWord?.startMs ?: Long.MIN_VALUE
    val pacing = activeWordPacing(ayah.words, activeWord)
    // Each word's ink behaviour, derived once for the whole ayah. All the
    // policy (upcoming/active/recited/high-water, repeat chain) lives in
    // InkEngine; the render branches only draw what it decided.
    val inks = ayah.words.map { word ->
        InkEngine.word(
            position = word.position,
            activeWord = activeWord,
            isActiveAyah = isActiveAyah,
            dimmed = dimmed,
        )
    }
    val activeIndex = inks.indexOfFirst { it.state == InkEngine.State.Active }
    val incomingConnection = if (activeIndex > 0) {
        InkEngine.connection(
            prevArabic = ayah.words[activeIndex - 1].arabic,
            arabic = ayah.words[activeIndex].arabic,
        )
    } else {
        null
    }
    val outgoingConnection = if (activeIndex in 0 until ayah.words.lastIndex) {
        InkEngine.connection(
            prevArabic = ayah.words[activeIndex].arabic,
            arabic = ayah.words[activeIndex + 1].arabic,
        )
    } else {
        null
    }
    val outgoingProgress = rememberWaslProgress(
        connection = outgoingConnection,
        sweepMs = sweepMs,
        identity = activeWord?.wordPosition,
        activation = activation,
        activeWord = activeWord,
        playbackSpeed = playbackSpeed,
        verse = ayah,
    )
    // A short next word borrows its bloom time from this word's tail rather
    // than trailing past its own end (InkEngine.shortWordLead).
    val outgoingLead = remember(activeIndex, activation, activeWord?.startMs, activeWord?.reciterId, activeWord?.fromTap, playbackSpeed) {
        outgoingShortWordLead(
            words = ayah.words,
            activeIndex = activeIndex,
            activeWord = activeWord,
            sweepMs = sweepMs,
            activePaced = pacing != null,
            connected = outgoingConnection != null,
            playbackSpeed = playbackSpeed,
            enabled = shortWordLeads,
        )
    }
    val leadWindow = rememberShortWordLead(
        lead = outgoingLead,
        sweepMs = sweepMs,
        activation = activation,
        activeWord = activeWord,
        playbackSpeed = playbackSpeed,
        verse = ayah,
    )
    val previousActive = remember { ActiveWordEntry(activeIndex, activation) }
    val carriedIncoming = remember(activeIndex, activation) {
        carriedFromPrevious(previousActive, activeIndex, activation, activeWord)
    }
    val incomingHandoff = if (carriedIncoming) previousActive.outgoingHandoff else 0f
    // Captured at entry: the SideEffect below overwrites the entry with this
    // word's own outgoing lead after the first frame.
    val incomingLeadReach = remember(activeIndex, activation) {
        if (carriedIncoming && incomingConnection == null) previousActive.outgoingLeadReach else 0f
    }
    SideEffect {
        previousActive.index = activeIndex
        previousActive.activation = activation
        previousActive.generation = activeWord?.playbackActivation ?: 0L
        previousActive.outgoingHandoff = outgoingProgress.atHandoff
        previousActive.outgoingLeadReach = outgoingLead?.reach ?: 0f
    }
    val waslMainFeather = if (pacing != null) {
        InkEngine.pacedFeather()
    } else {
        InkEngine.tuning.washFeather
    }
    val activeRevealStart = activeWashRevealStart(
        carriedIncoming = carriedIncoming,
        incomingConnection = incomingConnection,
        incomingHandoff = incomingHandoff,
        mainFeather = waslMainFeather,
        incomingLeadReach = incomingLeadReach,
    )
    val fullWaslWindow = remember { mutableStateOf(1f) }
    val waslPrefixes = ayahWaslPrefixes(
        wordCount = ayah.words.size,
        activeIndex = activeIndex,
        carriedIncoming = carriedIncoming,
        incomingConnection = incomingConnection,
        outgoingConnection = outgoingConnection,
        incomingHandoff = incomingHandoff,
        activeRevealStart = activeRevealStart,
        incomingLeadReach = incomingLeadReach,
        outgoingLead = outgoingLead,
        mainFeather = waslMainFeather,
        fullWindow = fullWaslWindow,
        outgoingWindow = outgoingProgress.value,
        leadWindow = leadWindow,
    )
    return AyahWordWash(
        sweepMs = sweepMs,
        repeatDwellMs = repeatDwellMs,
        activation = activation,
        activeWordStartMs = activeWordStartMs,
        pacing = pacing,
        inks = inks,
        activeIndex = activeIndex,
        outgoingLead = outgoingLead,
        incomingLeadReach = incomingLeadReach,
        activeRevealStart = activeRevealStart,
        waslPrefixes = waslPrefixes,
    )
}

/**
 * The motion owner [AyahBlock] already uses: tajweed pacing, wasl handoff,
 * letter sweep, orange repeat. Mushaf lines must call this — never a second
 * wash clock — so the page and the scrolling reader are the same ink.
 */
@Composable
internal fun rememberAyahInkPack(
    ayah: Ayah,
    activeWord: ActiveWord?,
    playbackSpeed: Float,
    isActiveAyah: Boolean,
    dimmed: Boolean,
    flashWordPosition: Int? = null,
    flashWordPositions: Set<Int> = emptySet(),
    searchFocusPositions: Set<Int>? = null,
    searchFocusActive: Boolean = searchFocusPositions != null,
    /** True while the voice is running: the wet-ink glint dries on a pause. */
    wetInk: Boolean = true,
    /** Mushaf selection enters from the paper cover already on the ayah. */
    initiallyRecessed: Boolean = false,
): AyahInkPack {
    // The Recording detector reads pulse lines worked out for the verse under
    // the voice only; without `active` the page's glimmer never pulsed.
    RequestTarjiPulseLines(ayah, active = isActiveAyah)
    val wash = rememberAyahWordWash(
        ayah = ayah,
        activeWord = activeWord,
        playbackSpeed = playbackSpeed,
        isActiveAyah = isActiveAyah,
        dimmed = dimmed,
    )
    val motions = rememberInkMotions(
        words = ayah.words,
        inks = wash.inks,
        // Short words bloom at the reciter's letter pace, borrowing from the
        // word before (InkEngine.visualSweepMs); wasl and Paper's lead-in stay
        // on the voiced sweepMs.
        activeSweepMs = InkEngine.visualSweepMs(
            wash.sweepMs,
            ayah.words.getOrNull(wash.activeIndex)?.arabic,
            paced = wash.pacing != null,
            ledIn = wash.incomingLeadReach > 0f,
            lending = wash.outgoingLead,
        ),
        activeVoicedSweepMs = wash.sweepMs,
        activeRepeatDwellMs = wash.repeatDwellMs,
        pacing = wash.pacing,
        activeRevealStart = wash.activeRevealStart,
        waslPrefixes = wash.waslPrefixes,
        activation = wash.activation,
        activeWordStartMs = wash.activeWordStartMs,
        activeWord = activeWord,
        playbackSpeed = playbackSpeed,
        verse = ayah,
        sequentialSweeps = false,
        animateLyricInk = false,
        wetInk = wetInk,
    )
    val enteringFromRecess = remember(initiallyRecessed) {
        mutableStateOf(initiallyRecessed)
    }
    LaunchedEffect(initiallyRecessed) {
        enteringFromRecess.value = false
    }
    val recessCover = animateFloatAsState(
        targetValue = ayahRecessCoverTarget(dimmed, enteringFromRecess.value),
        animationSpec = tween(InkEngine.tuning.recessMs, easing = FastOutSlowInEasing),
        label = "mushafRecessCover",
    )
    val searchTargets = flashWordPositions + listOfNotNull(flashWordPosition?.takeIf { it > 0 })
    val searchBackgroundAlpha = animateFloatAsState(
        targetValue = if (searchFocusActive) SearchHitFlash.BACKGROUND_ALPHA else 1f,
        animationSpec = tween(SearchHitFlash.FOCUS_FADE_MS, easing = FastOutSlowInEasing),
        label = "mushafSearchBackgroundAlpha",
    )
    return AyahInkPack(
        motions = motions,
        recessCover = recessCover,
        markAlpha = rememberAyahMarkAlpha(focused = !dimmed && !enteringFromRecess.value),
        searchHitWash = rememberSearchHitWash(
            searchTargets.hashCode().takeIf { searchTargets.isNotEmpty() } ?: flashWordPosition,
        ),
        searchHitWordPositions = searchTargets,
        searchFocusPositions = searchFocusPositions,
        searchBackgroundAlpha = searchBackgroundAlpha,
    )
}
