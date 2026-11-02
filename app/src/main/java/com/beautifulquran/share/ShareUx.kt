package com.beautifulquran.share

/**
 * Verse-share entry is Mark UX: hold `﴿N﴾` (its shamsa fills and pops) to
 * gather that verse; [onMarkTap] is what the pop calls.
 *
 * Word long-press stays Root Viewer. Notes stay on the bookmark ribbon.
 *
 * The Icon / Reveal / Hold A/B options (removed in 0eeaef2b5) are still gone.
 * That Hold put a Share glyph on a held verse — a confirm step behind a
 * hidden gesture. This hold is different: it is on the mark only (word hold
 * stays the Root Viewer), the pop *is* the gather, and a tap on the mark
 * teaches it. See docs/VERSE_ACTIONS.md.
 */
sealed class ShareUxAction {
    data class EnterShare(val ref: AyahRef) : ShareUxAction()
    data class ToggleVerse(val ref: AyahRef) : ShareUxAction()
    data object ExitShare : ShareUxAction()
    data object None : ShareUxAction()
}

/** Pure entry/toggle policy. Compose and the ViewModel must not invent rules. */
object ShareUx {

    fun onMarkTap(gathering: Boolean, ref: AyahRef): ShareUxAction =
        if (gathering) ShareUxAction.ToggleVerse(ref) else ShareUxAction.EnterShare(ref)

    /** Idle verse body does not enter gather. Once gathering, a tap anywhere toggles. */
    fun onVerseTap(gathering: Boolean, ref: AyahRef): ShareUxAction =
        if (gathering) ShareUxAction.ToggleVerse(ref) else ShareUxAction.None

    /**
     * Leaving the reader paper sheet (Cover, Bookmarks, Settings) drops
     * gather. Mushaf page turns and chapter turns stay on that sheet.
     */
    fun onLeaveReaderSheet(gathering: Boolean): ShareUxAction =
        if (gathering) ShareUxAction.ExitShare else ShareUxAction.None
}
