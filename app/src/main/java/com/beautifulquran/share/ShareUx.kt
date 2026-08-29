package com.beautifulquran.share

/**
 * Four verse-share entry designs for in-app A/B (Settings → Developer).
 *
 * Discoverability is the question. Hidden taps on `﴿N﴾` are not how anyone
 * shares a thing on a phone. These four are the actually-findable options:
 *
 * - [ICON] — the Android share glyph on the play bar (current verse)
 * - [RIBBON] — the same glyph under the bookmark tip, on that verse
 * - [REVEAL] — the word Share written under the verse you are on
 * - [HOLD] — long-press the verse (not a word) and Share appears
 * - [MARK] — tap `﴿N﴾` (verse handle; least obvious, kept for comparison)
 *
 * [OFF] is production: gather/export exist, nothing on the page starts them.
 * Chrome counts are Western digits in the book face.
 */
enum class ShareUxVariant {
    OFF,
    ICON,
    RIBBON,
    REVEAL,
    HOLD,
    MARK,
    ;

    /** Share glyph on the player bar. */
    val usesBarIcon: Boolean
        get() = this == ICON

    /** Share glyph under the bookmark swallowtail, on that verse. */
    val usesMarginIcon: Boolean
        get() = this == RIBBON

    /** Share is written under the current (playing or focused) verse. */
    val revealsOnCurrent: Boolean
        get() = this == REVEAL

    /** Long-press the verse body (translation) reveals Share on that verse. */
    val usesBodyHold: Boolean
        get() = this == HOLD

    val usesMarkTap: Boolean
        get() = this == MARK

    val entersOnMarkTap: Boolean
        get() = this == MARK

    val label: String
        get() = when (this) {
            OFF -> "Off"
            ICON -> "Icon"
            RIBBON -> "Ribbon"
            REVEAL -> "Reveal"
            HOLD -> "Hold"
            MARK -> "Mark"
        }

    val note: String
        get() = when (this) {
            OFF -> "Shipped reader — no share entry"
            ICON -> "Share on the play bar — this verse"
            RIBBON -> "Share under the bookmark tip — that verse"
            REVEAL -> "Share written under the verse you are on"
            HOLD -> "Hold the verse, Share appears"
            MARK -> "Tap ﴿N﴾ to share that verse"
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

    fun onBarShare(ref: AyahRef): ShareUxAction = ShareUxAction.EnterShare(ref)

    fun onMarginShare(ref: AyahRef): ShareUxAction = ShareUxAction.EnterShare(ref)

    fun onMarkTap(
        variant: ShareUxVariant,
        gathering: Boolean,
        prompt: AyahRef?,
        ref: AyahRef,
    ): ShareUxAction {
        if (gathering) return ShareUxAction.ToggleVerse(ref)
        if (variant.entersOnMarkTap) return ShareUxAction.EnterShare(ref)
        if (variant == ShareUxVariant.HOLD && prompt == ref) {
            return ShareUxAction.HidePrompt
        }
        return ShareUxAction.None
    }

    fun onShareVerb(ref: AyahRef): ShareUxAction = ShareUxAction.EnterShare(ref)

    fun onBodyHold(
        variant: ShareUxVariant,
        gathering: Boolean,
        ref: AyahRef,
    ): ShareUxAction {
        if (!variant.usesBodyHold || gathering) return ShareUxAction.None
        return ShareUxAction.ShowPrompt(ref)
    }

    fun onVerseTap(gathering: Boolean, ref: AyahRef): ShareUxAction =
        if (gathering) ShareUxAction.ToggleVerse(ref) else ShareUxAction.None
}
