package com.beautifulquran.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUxTest {

    private val a = AyahRef(2, 255)
    private val b = AyahRef(2, 256)

    @Test
    fun `bar share always enters with the current verse`() {
        assertEquals(ShareUxAction.EnterShare(a), ShareUx.onBarShare(a))
    }

    @Test
    fun `only mark enters on a short tap of ﴿N﴾`() {
        assertEquals(
            ShareUxAction.EnterShare(a),
            ShareUx.onMarkTap(ShareUxVariant.MARK, gathering = false, prompt = null, ref = a),
        )
        for (variant in listOf(
            ShareUxVariant.OFF,
            ShareUxVariant.ICON,
            ShareUxVariant.REVEAL,
            ShareUxVariant.HOLD,
            ShareUxVariant.RIBBON,
        )) {
            assertEquals(
                ShareUxAction.None,
                ShareUx.onMarkTap(variant, gathering = false, prompt = null, ref = a),
            )
        }
    }

    @Test
    fun `hold on the verse body reveals Share, it does not fire share`() {
        assertEquals(
            ShareUxAction.ShowPrompt(a),
            ShareUx.onBodyHold(ShareUxVariant.HOLD, gathering = false, ref = a),
        )
        assertEquals(
            ShareUxAction.None,
            ShareUx.onBodyHold(ShareUxVariant.REVEAL, gathering = false, ref = a),
        )
        assertEquals(
            ShareUxAction.None,
            ShareUx.onBodyHold(ShareUxVariant.HOLD, gathering = true, ref = a),
        )
    }

    @Test
    fun `second tap on the held verse hides the revealed Share`() {
        assertEquals(
            ShareUxAction.HidePrompt,
            ShareUx.onMarkTap(
                ShareUxVariant.HOLD,
                gathering = false,
                prompt = a,
                ref = a,
            ),
        )
    }

    @Test
    fun `share verb always enters with that verse`() {
        assertEquals(ShareUxAction.EnterShare(a), ShareUx.onShareVerb(a))
    }

    @Test
    fun `while gathering every mark tap toggles membership`() {
        for (variant in ShareUxVariant.entries) {
            assertEquals(
                ShareUxAction.ToggleVerse(a),
                ShareUx.onMarkTap(variant, gathering = true, prompt = null, ref = a),
            )
        }
    }

    @Test
    fun `verse tap only toggles while gathering`() {
        assertEquals(ShareUxAction.None, ShareUx.onVerseTap(gathering = false, ref = a))
        assertEquals(
            ShareUxAction.ToggleVerse(b),
            ShareUx.onVerseTap(gathering = true, ref = b),
        )
    }

    @Test
    fun `margin share enters with that verse`() {
        assertEquals(ShareUxAction.EnterShare(a), ShareUx.onMarginShare(a))
    }

    @Test
    fun `variant flags match the discoverable designs`() {
        assertTrue(ShareUxVariant.ICON.usesBarIcon)
        assertTrue(ShareUxVariant.RIBBON.usesMarginIcon)
        assertTrue(ShareUxVariant.REVEAL.revealsOnCurrent)
        assertTrue(ShareUxVariant.HOLD.usesBodyHold)
        assertTrue(ShareUxVariant.MARK.usesMarkTap)
        assertTrue(ShareUxVariant.MARK.entersOnMarkTap)
        assertFalse(ShareUxVariant.ICON.usesMarkTap)
        assertFalse(ShareUxVariant.RIBBON.usesBarIcon)
        assertFalse(ShareUxVariant.REVEAL.usesBarIcon)
        assertEquals(6, ShareUxVariant.entries.size)
    }
}
