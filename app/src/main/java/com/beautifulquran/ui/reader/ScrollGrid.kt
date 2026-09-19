package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 *  vertical rules (x)               vertical rhythm (paper between ink)
 *  38  left rule  — Western text,   56  verse to verse (Arabic)
 *      back arrow, Western folio    48  verse to verse (English)
 *  ½   the axis — everything        40  basmalah to first verse
 *      centred: opening, basmalah,  32  opening to basmalah
 *      NEXT, pill, title, player    12  voices within one verse
 *  38  right rule — Arabic, the
 *      settings mark, Arabic folio
 * ```
 *
 * Three vertical rules and nothing else. The margins are equal because the
 * sheet has one axis: the opening, the basmalah, the top title and the player
 * are centred on the screen, so the text block must be too. With a 28 dp rail
 * side against the ribbon's 38 the verses were centred 5 dp left of everything
 * above and below them.
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

    /**
     * Both margins. The ribbon's strip is the authority on its side (its cloth
     * and the gather ordinal live in it), and the rail side matches it so the
     * text block is centred on the sheet's one axis. The rail and the ribbon
     * sit in these margins; text never does.
     */
    val MARGIN: Dp get() = BookmarkStripWidth

    /**
     * The verse column: everything that reads as text — verses, translation,
     * notes, page folios — begins and ends on the two rules.
     */
    fun column(top: Dp = 0.dp, bottom: Dp = 0.dp) =
        PaddingValues(start = MARGIN, end = MARGIN, top = top, bottom = bottom)

    /**
     * Where the top bar's icons put their ink, measured on device: the back
     * arrow from the left edge (4 dp bar inset + 12 dp of its 48 dp button +
     * the glyph's side bearing), the settings mark from the right (its button
     * already nudged 4 dp outward). The Scroll reader shifts each group by the
     * difference so its ink stands on the text rules.
     */
    val TOP_BAR_START_INK: Dp = 20.6.dp
    val TOP_BAR_END_INK: Dp = 22.1.dp

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
    /**
     * Nothing: the Hafs title's 1.6 em line box already leaves ~15 dp of paper
     * under its ink, which is the lockup's gap. Adding more set the two
     * scripts of one name as far apart as the rosette from the title.
     */
    val TITLE_TO_SUBTITLE: Dp = 0.dp
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

    /**
     * "NEXT" band above the next chapter's opening. The label names what
     * follows, so it sits on the band's foot — 46 dp of ink from the rosette
     * and twice that from the verse it leaves. Centred in the band it floated
     * nearly midway (69 / 54) and belonged to neither.
     */
    val INVITE_HEAD: Dp = UNIT * 16
    val INVITE_LABEL_TOP: Dp = INVITE_HEAD - UNIT * 4

    /** Continue pill band beneath it. */
    val INVITE_FOOT: Dp = UNIT * 20
    val INVITE_PILL_TOP: Dp = UNIT * 6

    val PILL_INSET: Dp = UNIT * 6
    val PULL_HEAD: Dp = UNIT * 3
    val PULL_FOOT: Dp = UNIT * 4
    val PULL_LABEL_GAP: Dp = UNIT * 2
    val PULL_PILL_GAP: Dp = UNIT * 3
}
