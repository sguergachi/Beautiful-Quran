package com.beautifulquran.share

/**
 * Four verse-share entry designs for in-app A/B (Settings → Developer).
 *
 * [OFF] is the shipped reader: gather/export exist, but nothing on the page
 * enters the mode (player-bar Gather was removed in #519). The other four are
 * the interaction experiments from [docs/VERSE_ACTIONS.md]. Export (text /
 * image) is shared; only entry chrome differs.
 */
enum class ShareUxVariant {
    OFF,
    /** Tap ﴿N﴾ → quiet "Share" line under that verse. */
    COLOPHON,
    /** Long-press the verse body → enter share with that verse selected. */
    LIFT,
    /** Tap ﴿N﴾ → "Share" inks beside the ayah mark. */
    SEAL,
    /** Tap ﴿N﴾ → player bar rewrites to Share (verse already current). */
    ACTION_LINE,
    ;

    val usesMarkTap: Boolean
        get() = this == COLOPHON || this == SEAL || this == ACTION_LINE

    val usesLift: Boolean
        get() = this == LIFT

    val label: String
        get() = when (this) {
            OFF -> "Off"
            COLOPHON -> "Colophon"
            LIFT -> "Lift"
            SEAL -> "Seal"
            ACTION_LINE -> "Action line"
        }

    val note: String
        get() = when (this) {
            OFF -> "Shipped reader — no share entry"
            COLOPHON -> "Tap ﴿N﴾, then Share under the verse"
            LIFT -> "Hold the verse; gold wash, already selected"
            SEAL -> "Tap ﴿N﴾, then Share beside the mark"
            ACTION_LINE -> "Tap ﴿N﴾, Share rewrites the player bar"
        }
}

sealed class ShareUxAction {
    data class ShowPrompt(val ref: AyahRef) : ShareUxAction()
    data object HidePrompt : ShareUxAction()
    data class EnterShare(val ref: AyahRef) : ShareUxAction()
    data class ToggleVerse(val ref: AyahRef) : ShareUxAction()
    data object None : ShareUxAction()
}

/** Pure entry/toggle policy. Compose and the ViewModel must not invent rules. */
object ShareUx {

    fun onMarkTap(
        variant: ShareUxVariant,
        gathering: Boolean,
        prompt: AyahRef?,
        ref: AyahRef,
    ): ShareUxAction {
        if (gathering) return ShareUxAction.ToggleVerse(ref)
        if (!variant.usesMarkTap) return ShareUxAction.None
        return if (prompt == ref) ShareUxAction.HidePrompt else ShareUxAction.ShowPrompt(ref)
    }

    fun onShareVerb(ref: AyahRef): ShareUxAction = ShareUxAction.EnterShare(ref)

    fun onLift(
        variant: ShareUxVariant,
        gathering: Boolean,
        ref: AyahRef,
    ): ShareUxAction {
        if (!variant.usesLift || gathering) return ShareUxAction.None
        return ShareUxAction.EnterShare(ref)
    }

    fun onVerseTap(gathering: Boolean, ref: AyahRef): ShareUxAction =
        if (gathering) ShareUxAction.ToggleVerse(ref) else ShareUxAction.None
}
