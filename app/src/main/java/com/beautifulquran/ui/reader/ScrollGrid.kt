package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.AyahSelectorSide

/**
 * The Scroll reader's grid — the sheet's answer to [com.beautifulquran.domain.MushafGrid].
 *
 * The mushaf hangs everything on one line pitch. The scroll cannot: its Hafs,
 * glosses and translation each set their own leading, and pinch changes all of
 * them. So the sheet hangs on a smaller, fixed step instead, and every piece of
 * paper between two pieces of ink is a whole number of it. Before this object
 * the chapter carried 14, 26, 18, 30, 10, 22, 50 and 82 dp side by side — each
 * sensible alone, none related to its neighbour — and the gaps drifted a few dp
 * apart wherever two components met.
 *
 * ```
 *  horizontal                       vertical (paper between ink)
 *  28  margin: the outer lane,      56  verse to verse (Arabic)
 *      same as the Chapters index   48  verse to verse (English)
 *  38  ribbon gutter, owned by      40  basmalah to first verse
 *      [BookmarkStripWidth]         32  opening to basmalah
 *                                   12  voices within one verse
 * ```
 *
 * Two figures are anchors, not steps: the ribbon gutter belongs to the ribbon
 * component (its cloth and the gather ordinal are measured inside it), and the
 * 52 dp rosette belongs to the opening's handoff flyer. The grid places them;
 * it does not resize them.
 */
object ScrollGrid {
    /** The step. Every paper figure below is a whole number of it. */
    val UNIT: Dp = 4.dp

    // ── Horizontal ────────────────────────────────────────────────────────

    /** The outer text lane — the same 28 as the Chapters index lane. */
    val MARGIN: Dp = UNIT * 7

    /** The bookmark side's lane: the ribbon strip is the authority. */
    val RIBBON_GUTTER: Dp get() = BookmarkStripWidth

    /**
     * The verse column. Everything that reads as part of the text — verses,
     * page folios — begins and ends on these two edges, so a page rule never
     * reaches past the words it separates.
     */
    fun column(bookmarkSide: AyahSelectorSide?, top: Dp = 0.dp, bottom: Dp = 0.dp) =
        PaddingValues(
            start = if (bookmarkSide == AyahSelectorSide.LEFT) RIBBON_GUTTER else MARGIN,
            end = if (bookmarkSide == AyahSelectorSide.RIGHT) RIBBON_GUTTER else MARGIN,
            top = top,
            bottom = bottom,
        )

    // ── Verse ─────────────────────────────────────────────────────────────

    /** Paper inside a verse block, above and below its ink. */
    val VERSE_PAD: Dp = UNIT * 4

    /** Paper between one verse's pad and the next: 16 + 24 + 16 = 56. */
    val VERSE_GAP: Dp = UNIT * 6

    /** English runs on a tighter line, so its verses close to 48. */
    val VERSE_GAP_ENGLISH: Dp = UNIT * 4

    /** Arabic → translation → note: one verse, different voices. */
    val VOICE_GAP: Dp = UNIT * 3

    /**
     * The ribbon tip lines up with the verse's first ink line, which sits a
     * fixed optical distance below the pad. Moving the pad moves the tip.
     */
    val RIBBON_TIP: Dp = VERSE_PAD + 10.dp

    // ── Chapter opening ───────────────────────────────────────────────────

    val OPENING_HEAD: Dp = UNIT * 10
    val OPENING_FOOT: Dp = UNIT * 8

    /** The basmalah supplies its own head, so the opening only closes up. */
    val OPENING_FOOT_BEFORE_BASMALAH: Dp = UNIT * 2
    val ROSETTE: Dp = 52.dp
    val ROSETTE_TO_TITLE: Dp = UNIT * 4
    val TITLE_TO_SUBTITLE: Dp = UNIT * 2
    val SUBTITLE_TO_META: Dp = UNIT

    /** 8 + 24 = 32 from the opening's metadata. */
    val BASMALAH_HEAD: Dp = UNIT * 6

    /** 24 + the verse pad = 40 to the first verse. */
    val BASMALAH_FOOT: Dp = UNIT * 6

    // ── Page folio ────────────────────────────────────────────────────────

    /**
     * The folio sits in a verse gap, so the verse above already brings its
     * pad and gap (16 + 24 = 40) and the folio adds the same beneath it
     * (24 + the next verse's 16).
     */
    val FOLIO_HEAD: Dp = 0.dp
    val FOLIO_FOOT: Dp = UNIT * 6

    /** English closes its verse gap to 16, so the folio makes up the 8. */
    val FOLIO_HEAD_ENGLISH: Dp = VERSE_GAP - VERSE_GAP_ENGLISH

    /**
     * The folio's own band. Fixed in dp, not in the figures' sp line box, so
     * a larger system font cannot push everything beneath it off the step.
     */
    val FOLIO_BAND: Dp = UNIT * 4

    // ── Chapter invitations ───────────────────────────────────────────────

    /** "NEXT" band above the next chapter's opening. */
    val INVITE_HEAD: Dp = UNIT * 12
    val INVITE_LABEL_TOP: Dp = UNIT * 6

    /** Continue pill band beneath it. */
    val INVITE_FOOT: Dp = UNIT * 20
    val INVITE_PILL_TOP: Dp = UNIT * 6

    val PILL_INSET: Dp = UNIT * 6
    val PULL_HEAD: Dp = UNIT * 3
    val PULL_FOOT: Dp = UNIT * 4
    val PULL_LABEL_GAP: Dp = UNIT * 2
    val PULL_PILL_GAP: Dp = UNIT * 3
}
