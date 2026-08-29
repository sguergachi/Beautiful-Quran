package com.beautifulquran.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUxTest {

    private val a = AyahRef(2, 255)
    private val b = AyahRef(2, 256)

    @Test
    fun `off and lift do not open a mark prompt`() {
        assertEquals(
            ShareUxAction.None,
            ShareUx.onMarkTap(ShareUxVariant.OFF, gathering = false, prompt = null, ref = a),
        )
        assertEquals(
            ShareUxAction.None,
            ShareUx.onMarkTap(ShareUxVariant.LIFT, gathering = false, prompt = null, ref = a),
        )
    }

    @Test
    fun `colophon seal and action line prompt on first mark tap`() {
        for (variant in listOf(
            ShareUxVariant.COLOPHON,
            ShareUxVariant.SEAL,
            ShareUxVariant.ACTION_LINE,
        )) {
            assertEquals(
                ShareUxAction.ShowPrompt(a),
                ShareUx.onMarkTap(variant, gathering = false, prompt = null, ref = a),
            )
        }
    }

    @Test
    fun `second tap on the same mark hides the prompt`() {
        assertEquals(
            ShareUxAction.HidePrompt,
            ShareUx.onMarkTap(
                ShareUxVariant.COLOPHON,
                gathering = false,
                prompt = a,
                ref = a,
            ),
        )
    }

    @Test
    fun `tapping another mark moves the prompt`() {
        assertEquals(
            ShareUxAction.ShowPrompt(b),
            ShareUx.onMarkTap(
                ShareUxVariant.SEAL,
                gathering = false,
                prompt = a,
                ref = b,
            ),
        )
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
    fun `share verb always enters with that verse`() {
        assertEquals(ShareUxAction.EnterShare(a), ShareUx.onShareVerb(a))
    }

    @Test
    fun `lift enters from a body hold only for that variant`() {
        assertEquals(
            ShareUxAction.EnterShare(a),
            ShareUx.onLift(ShareUxVariant.LIFT, gathering = false, ref = a),
        )
        assertEquals(
            ShareUxAction.None,
            ShareUx.onLift(ShareUxVariant.COLOPHON, gathering = false, ref = a),
        )
        assertEquals(
            ShareUxAction.None,
            ShareUx.onLift(ShareUxVariant.LIFT, gathering = true, ref = a),
        )
    }

    @Test
    fun `verse tap only toggles while gathering`() {
        assertEquals(ShareUxAction.None, ShareUx.onVerseTap(gathering = false, ref = a))
        assertEquals(
            ShareUxAction.ToggleVerse(a),
            ShareUx.onVerseTap(gathering = true, ref = a),
        )
    }

    @Test
    fun `variant flags match the four entry designs`() {
        assertFalse(ShareUxVariant.OFF.usesMarkTap)
        assertFalse(ShareUxVariant.OFF.usesLift)
        assertTrue(ShareUxVariant.COLOPHON.usesMarkTap)
        assertTrue(ShareUxVariant.SEAL.usesMarkTap)
        assertTrue(ShareUxVariant.ACTION_LINE.usesMarkTap)
        assertTrue(ShareUxVariant.LIFT.usesLift)
        assertFalse(ShareUxVariant.LIFT.usesMarkTap)
        assertEquals(5, ShareUxVariant.entries.size)
    }
}
