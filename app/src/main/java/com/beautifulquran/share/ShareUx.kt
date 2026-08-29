package com.beautifulquran.share

/**
 * Four verse-share entry designs for in-app A/B (Settings → Developer).
 *
 * [OFF] is the shipped reader: gather/export exist, but nothing on the page
 * enters the mode (player-bar Gather was removed in #519). Export (text /
 * image) is shared; only entry chrome differs.
 *
 * Chrome (count, margin ordinals) is Western digits in the book face — never
 * Arabic-Indic. Those digits belong to scripture marks, not furniture.
 */
enum class ShareUxVariant {
    OFF,
    /** Tap ﴿N﴾ → quiet "Share" line under that verse. Two taps. */
    COLOPHON,
    /** Long-press the ayah mark → enter share. Word hold stays Root Viewer. */
    LIFT,
    /** Tap ﴿N﴾ → enter share with that verse already selected. One tap. */
    SEAL,
    /** Tap ﴿N﴾ → Share sits above the player bar; transport stays. */
    ACTION_LINE,
    ;

    val usesMarkTap: Boolean
        get() = this == COLOPHON || this == SEAL || this == ACTION_LINE

    /** Long-press the ﴿N﴾ mark, not the verse body. */
    val usesMarkHold: Boolean
        get() = this == LIFT

    val entersOnMarkTap: Boolean
        get() = this == SEAL

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
            LIFT -> "Hold ﴿N﴾; gold wash, already selected"
            SEAL -> "Tap ﴿N﴾ to share that verse"
            ACTION_LINE -> "Tap ﴿N﴾, Share sits above play"
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
        if (variant.entersOnMarkTap) return ShareUxAction.EnterShare(ref)
        if (!variant.usesMarkTap) return ShareUxAction.None
        return if (prompt == ref) ShareUxAction.HidePrompt else ShareUxAction.ShowPrompt(ref)
    }

    fun onShareVerb(ref: AyahRef): ShareUxAction = ShareUxAction.EnterShare(ref)

    fun onMarkHold(
        variant: ShareUxVariant,
        gathering: Boolean,
        ref: AyahRef,
    ): ShareUxAction {
        if (!variant.usesMarkHold || gathering) return ShareUxAction.None
        return ShareUxAction.EnterShare(ref)
    }

    fun onVerseTap(gathering: Boolean, ref: AyahRef): ShareUxAction =
        if (gathering) ShareUxAction.ToggleVerse(ref) else ShareUxAction.None
}
