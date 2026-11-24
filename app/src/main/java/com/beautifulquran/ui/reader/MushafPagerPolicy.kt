package com.beautifulquran.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beautifulquran.domain.MushafCatalog
import com.beautifulquran.domain.EnglishBook
import com.beautifulquran.domain.EnglishAlignment
import kotlin.math.abs

// The pager's rules, kept free of Compose so they are unit-tested on their
// own: which leaf the voice is on, when a turn may lead it, which leaf owns
// live ink, which leaves stay composed. The pager itself is MushafPager.kt.

/**
 * How far ahead of the voice the leaf turns, in milliseconds.
 *
 * A page turned exactly when the first word of the next leaf is spoken is
 * always late: the reader is looking at the word being recited, the paper
 * starts moving only once the voice has already left the leaf, and the first
 * word of the new page is half-said by the time it arrives. A person turning
 * a page for someone else starts before the line runs out. So the turn is
 * begun inside the last word of the leaf instead, while there is still voice
 * on the page it is leaving.
 */
internal const val MushafTurnLeadMs = 500L

/**
 * How long to wait, from the moment the leaf's last word begins, before
 * starting the turn: that word's own dwell at [speed], less the lead.
 *
 * Clamped at zero — a short final word simply turns at once, which is the
 * same thing the lead is asking for.
 */
internal fun mushafTurnLeadDelayMs(durationMs: Long, speed: Float): Long =
    ((durationMs / speed.coerceAtLeast(0.1f)).toLong() - MushafTurnLeadMs)
        .coerceAtLeast(0L)

/**
 * The leaf the reciter is on, or null when the voice is not on this reading.
 *
 * This is what decides ink ownership — which verse waits under paper, which
 * holds its ink, which is being washed — so its one hard requirement is that
 * it must not answer "nowhere" while the voice is plainly here.
 *
 * It used to. Between two words there is no active word, and for a beat after
 * a verse ends there is no ink frontier either, so both of the first two
 * answers were null and the page fell to null with them. Every verse on the
 * leaf was then classified as already-read for a frame or two — which is the
 * whole page snapping to full ink and back at each verse boundary. Media3
 * knows which verse is playing throughout, and that is the answer for those
 * frames.
 */
internal fun mushafVoicePage(
    catalog: MushafCatalog,
    surahId: Int,
    voice: MushafPlayback,
    word: ActiveWord?,
    wholeVerses: Boolean,
): Int? {
    if (!voice.playingHere) return null
    return when {
        voice.basmalahActive -> catalog.firstPageOf(surahId)
        word != null -> catalog.readingPageOf(
            surahId,
            word.ayah,
            word.wordPosition,
            wholeVerses = wholeVerses,
        )
        voice.activeAyah != null -> catalog.pageOf(surahId, voice.activeAyah, 1)
        voice.playingAyah != null -> catalog.pageOf(surahId, voice.playingAyah, 1)
        else -> null
    }
}

/**
 * The 1-based leaf a verse is set on — the same thing as its page number until
 * a Madinah page needs two leaves to hold its English.
 *
 * One-based so that 0 can go on meaning "no leaf", which is what every hold,
 * wait and lead-turn helper below reads it as.
 */
internal fun mushafLeafNumber(
    book: EnglishBook?,
    surahId: Int,
    ayah: Int?,
    page: Int,
    /**
     * How far into the verse the reciter has come. A verse the book carried
     * over stands on two leaves, and past the cut the reciter is on the second
     * one — left at 0 the tail leaf never owned the voice, so it sat recessed
     * and washless for as long as the first half took to say.
     */
    through: Float = 0f,
): Int = when {
    book == null -> page
    // No verse to place: the leaf the page opens on. Returning [page] here
    // handed a Madinah page number back as if it were a leaf number - the two
    // are the same only on the Arabic leaf, and on the English book 604 and
    // 1,041 are different scales, so the voice landed on some unrelated leaf
    // and the leaf it was really on never lit.
    ayah == null -> book.firstLeafOf(page) + 1
    else -> book.leafOfVerse(surahId, ayah, page, through) + 1
}

/** Transport restarts open a verse; midpoint skips use the word's Arabic or English leaf. */
internal fun mushafTransportLeaf(
    catalog: MushafCatalog,
    book: EnglishBook?,
    target: ReaderInteraction.MushafPlayTarget,
    wordCount: Int = 0,
    alignment: EnglishAlignment? = null,
): Int {
    val page = catalog.readingPageOf(
        target.surahId, target.ayah, target.word ?: 1, wholeVerses = book != null,
    )
    return mushafLeafNumber(
        book, target.surahId, target.ayah, page,
        through = mushafVerseThrough(target.word, wordCount, alignment = alignment),
    ) - 1
}

/**
 * How far through its verse the reciter's word stands, as a fraction — what
 * [mushafLeafNumber] needs to tell one half of a carried verse from the other.
 *
 * The book cut the verse at a *character*, so the fraction has to be in the
 * same units the wash is: [wordEnds] is where this word's English begins
 * (`EnglishWordAlignment`), and without one the words divide the sentence
 * evenly. Reading the cut with a different map than the ink would turn the page
 * while the wash was still visibly on the leaf behind it.
 */
internal fun mushafVerseThrough(
    wordPosition: Int?,
    wordCount: Int,
    wordEnds: FloatArray? = null,
    alignment: EnglishAlignment? = null,
): Float {
    if (wordPosition == null || wordCount <= 0) return 0f
    val index = (wordPosition - 1).coerceIn(0, wordCount - 1)
    if (alignment != null) return alignment.through(index + 1)
    val ends = wordEnds?.takeIf { it.size == wordCount }
        ?: return (index.toFloat() / wordCount).coerceIn(0f, 1f)
    return if (index == 0) 0f else ends[index - 1].coerceIn(0f, 1f)
}

/** The word's leaf, shared by voice ownership and release of a tapped leaf. */
internal fun mushafWordLeafNumber(
    book: EnglishBook?,
    surahId: Int,
    word: ActiveWord,
    page: Int,
    wordCount: Int,
    wordEnds: FloatArray? = null,
    alignment: EnglishAlignment? = null,
): Int = mushafLeafNumber(
    book, surahId, word.ayah, page,
    through = mushafVerseThrough(word.wordPosition, wordCount, wordEnds, alignment),
)

/**
 * The leaf to lead onto when the reciter is on the last word before the book's
 * own cut, or null when no cut is coming with this word.
 *
 * A page turned exactly when the first word overleaf is spoken is always late
 * ([MushafTurnLeadMs]). On the English leaf that lead has nowhere to stand at an
 * Arabic page boundary, because the leaf sets a straddling verse whole and its
 * last printed word is mid-sentence. At a carry cut it is the other way round:
 * the sentence really does break there, so the word before the cut is the last
 * word on the paper and the lead belongs to it.
 *
 * Answers the leaf the *next* word falls on, and only when that is further on
 * than the one the reader is holding.
 */
internal fun mushafLeadCarriedTurn(
    book: EnglishBook?,
    surahId: Int,
    ayah: Int?,
    onPage: Int,
    word: ActiveWord?,
    words: Int,
    leafNow: Int,
    wordEnds: FloatArray? = null,
    alignment: EnglishAlignment? = null,
): Int? {
    if (book == null || ayah == null || word == null || words <= 0) return null
    val index = (word.wordPosition - 1).coerceIn(0, words - 1)
    val ends = wordEnds?.takeIf { it.size == words }
    val next = mushafLeafNumber(
        book = book,
        surahId = surahId,
        ayah = ayah,
        page = onPage,
        // Where the voice will be one word from now — in the same units the
        // wash reads, so the lead fires exactly at the cut.
        through = alignment?.through(index + 2) ?: ends?.get(index)?.coerceIn(0f, 1f)
            ?: (word.wordPosition.toFloat() / words).coerceIn(0f, 1f),
    )
    return next.takeIf { it > leafNow }
}

/**
 * How long the book is, in whatever [mushafLeafNumber] is counting.
 *
 * The pager, the folio and the page dial all have to agree with it. The dial
 * did not: it was handed leaf numbers and a track 604 Madinah pages long, so
 * 1,145 leaves were scaled onto 604 stops, the chapter comb bunched into the
 * middle of the rule and the far end of the book was unreachable. A rule has to
 * be as long as the thing it measures — this is that, said once.
 */
internal fun mushafBookLength(book: EnglishBook?, pageCount: Int): Int =
    book?.leafCount ?: pageCount

/**
 * Clamp a leaf index into a book that may still be empty.
 *
 * Before the runtime QCF snapshot loads, the catalog is empty and the English
 * book built from it has no leaves — and `coerceIn(0, leafCount - 1)` throws
 * on the empty range. The sheet is up immediately with blank paper until the
 * pages arrive, so every homing effect clamps through here instead.
 */
internal fun mushafLeafIndex(index: Int, leafCount: Int): Int =
    index.coerceIn(0, (leafCount - 1).coerceAtLeast(0))

/** A second page owns clocks only while the voice is crossing onto it. */
internal fun mushafUsesLiveInk(
    isSettled: Boolean,
    isVoicePage: Boolean,
    waitingForVoice: Boolean = false,
    pageHasActiveWord: Boolean = false,
): Boolean = isSettled || isVoicePage || waitingForVoice || pageHasActiveWord

internal enum class MushafInkPackKind {
    ACTIVE_WORD,
    UPCOMING,
    SEARCH_FLASH,
    SEARCH_BACKGROUND,
    STATIC,
}

/** Which clock pack an ayah owns on the page carrying the voice. */
internal fun mushafInkPackKind(
    pageOwnsVoice: Boolean,
    ayah: Int,
    activeWordAyah: Int?,
    frontierAyah: Int?,
    basmalahActive: Boolean,
    hasSearchFlash: Boolean,
    /** The frontier is selected, but its first word timing has not arrived. */
    frontierWaitingForFirstWord: Boolean = false,
    /**
     * Follow is turning onto this leaf (or just did) and the voice is
     * still on the previous one. The whole leaf waits under paper so the
     * wash can fill it; a hand-browsed leaf stays [STATIC].
     */
    waitingForVoice: Boolean = false,
    /**
     * This leaf is where [activeWordAyah] lives, even if Media3 has not
     * set [pageOwnsVoice] yet. A dial-then-tap seed puts the word here
     * while `playingHere` is still false; dropping Active at play-start
     * disposes the wash Animatable.
     */
    pageHasActiveWord: Boolean = false,
    hasSearchFocus: Boolean = false,
    /**
     * The voice has just left this verse and its last word's glimmer is
     * still drying ([InkEngine.Tuning.glintFadeMs]). Its clocks stay, so the
     * glow recedes instead of vanishing with a pack that has no motions.
     */
    drying: Boolean = false,
): MushafInkPackKind = when {
    pageOwnsVoice && basmalahActive -> MushafInkPackKind.UPCOMING
    // Own the wash wherever the word is, not only once Media3 or the
    // waiting cover says so. pageOwnsVoice lags the seek; waitingForVoice
    // is released when play starts — both used to swap this pack to
    // STATIC and kill the letter fade mid-run.
    (pageOwnsVoice || waitingForVoice || pageHasActiveWord) &&
        activeWordAyah == ayah ->
        MushafInkPackKind.ACTIVE_WORD
    hasSearchFlash -> MushafInkPackKind.SEARCH_FLASH
    hasSearchFocus -> MushafInkPackKind.SEARCH_BACKGROUND
    (pageOwnsVoice || pageHasActiveWord) &&
        (frontierAyah == null || ayah > frontierAyah ||
            ayah == frontierAyah && frontierWaitingForFirstWord) ->
        MushafInkPackKind.UPCOMING
    waitingForVoice && (frontierAyah == null || ayah >= frontierAyah) ->
        MushafInkPackKind.UPCOMING
    drying -> MushafInkPackKind.ACTIVE_WORD
    else -> MushafInkPackKind.STATIC
}

/** A timing backtrack at a physical page tail must not turn the leaf forward. */
internal fun mushafTailTurnAllowed(
    nextTimingPage: Int?,
    followingPage: Int,
    isFinalAyah: Boolean,
): Boolean = !isFinalAyah &&
    (nextTimingPage == null || nextTimingPage == followingPage)

/**
 * Neighbour leaves stay composed for a warm turn, but they must not own the
 * tap. A hit that leaks onto the previous or next page plays that page's
 * verse with no wash on the leaf the reader is looking at.
 */
internal fun mushafLeafAcceptsTap(pageIndex: Int, currentPage: Int): Boolean =
    pageIndex == currentPage

/** Leaves held composed either side of the settled one while the pager rests. */
internal const val MushafHeldNeighbours = 2

/** Still frames after a turn before the pager counts as resting. */
internal const val MushafRestFrames = 2

/**
 * True while a leaf the pager has composed should stay blank paper: it is
 * beyond the [held] neighbours of the page the pager rests on, neither in view
 * nor beside a leaf that is, and not where a turn is going. The pager composes
 * such leaves on its own — the one that joins its window halfway through a
 * turn, and the one it prefetches past the window at rest, whose lines it then
 * measured on the next swipe's first frame. A turn from rest only ever shows
 * leaves within the held two, so it composes nothing. A second swipe begun
 * before the first settled brings the next leaf into view; the one beyond it
 * is set then, a turn ahead of being seen.
 */
internal fun mushafDefersLeaf(
    pageIndex: Int,
    restPage: Int,
    held: Int,
    targetPage: Int,
    nearView: Boolean,
): Boolean =
    abs(pageIndex - restPage) > held &&
        pageIndex != targetPage &&
        !nearView

/**
 * A tap pins the leaf until the seek's word arrives on it. Auto-follow —
 * including the last-word lead turn — must not steal that leaf while the
 * poll still names the word from before the seek.
 *
 * [heldPage] and [voicePage] are 1-based. A null voice has not arrived.
 */
internal fun mushafHoldBlocksFollow(heldPage: Int?, voicePage: Int?): Boolean =
    heldPage != null && voicePage != heldPage

/**
 * The leaf follow is turning onto, or the leaf a tap just pinned, waits
 * under paper so the wash has something to fill. A hand-browsed neighbour
 * stays fully readable.
 *
 * [pageNumber], [waitingPage] and [heldPage] are 1-based; 0 / null means none.
 */
internal fun mushafLeafWaitingForVoice(
    pageNumber: Int,
    waitingPage: Int,
    heldPage: Int?,
): Boolean = pageNumber == waitingPage || heldPage == pageNumber

/**
 * The 33 ms poll has named the waiting leaf. A tap seed must not count:
 * treating it as arrival drops the cover and disposes the wash mid-run.
 */
internal fun mushafWaitingLeafReleased(
    waitingPage: Int,
    voicePage: Int,
    fromTap: Boolean,
): Boolean = waitingPage != 0 && voicePage == waitingPage && !fromTap

/** A font wait cannot finish a turn inherited by another verse, seek, or repeat. */
internal fun mushafSamePlayback(ayah: Int?, inkId: Long, current: MushafPlayback): Boolean =
    current.playingAyah == ayah && current.inkId == inkId

/** Delayed turns retain ownership only while the exact activation survives. */
internal fun mushafSameActivation(expected: ActiveWord, current: ActiveWord?): Boolean =
    current != null &&
        expected.ayah == current.ayah &&
        expected.wordPosition == current.wordPosition &&
        expected.startMs == current.startMs &&
        expected.activation == current.activation

/** A null-word turn is still valid only for the same playback snapshot. */
internal fun mushafNullWordFollowStillCurrent(
    activeAyah: Int?,
    basmalahActive: Boolean,
    playingAyah: Int?,
    inkId: Long,
    currentVoice: MushafPlayback,
    currentWord: ActiveWord?,
): Boolean =
    currentWord == null &&
        currentVoice.activeAyah == activeAyah &&
        currentVoice.basmalahActive == basmalahActive &&
        mushafSamePlayback(playingAyah, inkId, currentVoice)

/**
 * A last-word lead turn puts the paper on [waitingPage] while the voice is
 * still on the previous leaf. Catch-up that rewinds to the spoken leaf is the
 * bounce: next, previous, next again. Hold until the voice arrives.
 *
 * [voicePage], [visiblePage] and [waitingPage] are 1-based leaf numbers;
 * [waitingPage] is 0 when no lead turn is in flight.
 */
internal fun mushafLeadTurnHoldsPager(
    voicePage: Int,
    visiblePage: Int,
    waitingPage: Int,
): Boolean = waitingPage != 0 &&
    visiblePage == waitingPage &&
    voicePage == waitingPage - 1

/**
 * Follow may pull the paper only while the leaf in view is the one it last
 * aimed at. A swipe lands on a different leaf; catching up to the voice from
 * there rewinds the turn the hand just made.
 */
internal fun mushafFollowOwnsVisiblePage(
    currentPageIndex: Int,
    followPage: Int,
): Boolean = currentPageIndex == followPage

/**
 * The playing leaf sits to the left (later pages) or the right (earlier
 * pages) of the leaf under the reader. The mushaf pager is reversed: page
 * one is on the right, so a higher page number is a turn to the left.
 */
internal enum class MushafReturnWay { Left, Right }

/**
 * Which way the reader would travel to reach the page the voice is on.
 *
 * A page number is not a direction: a later leaf lies leftward in a book bound
 * on the right and rightward in one bound on the left, so the arrow has to be
 * told which book this is. It was not, and pointed backwards on the English
 * leaf for as long as that leaf has turned the other way — the last thing on
 * this page still reading the mushaf's binding out of a page number.
 *
 * [rightToLeft] comes from `mushafTurnsRightToLeft`, the one figure the leaf,
 * the rule and the folio already take it from.
 */
internal fun mushafReturnWay(
    currentPage: Int,
    playbackPage: Int,
    rightToLeft: Boolean = true,
): MushafReturnWay? {
    val later = if (rightToLeft) MushafReturnWay.Left else MushafReturnWay.Right
    val earlier = if (rightToLeft) MushafReturnWay.Right else MushafReturnWay.Left
    return when {
        playbackPage > currentPage -> later
        playbackPage < currentPage -> earlier
        else -> null
    }
}
