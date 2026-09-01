package com.beautifulquran.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareUxTest {

    private val a = AyahRef(2, 255)
    private val b = AyahRef(2, 256)

    @Test
    fun `mark tap enters with that verse`() {
        assertEquals(
            ShareUxAction.EnterShare(a),
            ShareUx.onMarkTap(gathering = false, ref = a),
        )
    }

    @Test
    fun `while gathering every mark tap toggles membership`() {
        assertEquals(
            ShareUxAction.ToggleVerse(a),
            ShareUx.onMarkTap(gathering = true, ref = a),
        )
    }

    @Test
    fun `verse tap only toggles while gathering`() {
        assertEquals(ShareUxAction.None, ShareUx.onVerseTap(gathering = false, ref = a))
        assertEquals(
            ShareUxAction.ToggleVerse(b),
            ShareUx.onVerseTap(gathering = true, ref = b),
        )
    }
}
