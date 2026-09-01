package com.beautifulquran.share

/**
 * Verse-share entry is Mark UX: tap `﴿N﴾` to gather that verse.
 *
 * Word long-press stays Root Viewer. Notes stay on the bookmark ribbon.
 * Icon / Reveal / Hold were A/B options and are gone.
 */
sealed class ShareUxAction {
    data class EnterShare(val ref: AyahRef) : ShareUxAction()
    data class ToggleVerse(val ref: AyahRef) : ShareUxAction()
    data object None : ShareUxAction()
}

/** Pure entry/toggle policy. Compose and the ViewModel must not invent rules. */
object ShareUx {

    fun onMarkTap(gathering: Boolean, ref: AyahRef): ShareUxAction =
        if (gathering) ShareUxAction.ToggleVerse(ref) else ShareUxAction.EnterShare(ref)

    fun onVerseTap(gathering: Boolean, ref: AyahRef): ShareUxAction =
        if (gathering) ShareUxAction.ToggleVerse(ref) else ShareUxAction.None
}
