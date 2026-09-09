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
    fun `verse body never gathers`() {
        assertEquals(ShareUxAction.None, ShareUx.onVerseTap(gathering = false, ref = a))
        assertEquals(ShareUxAction.None, ShareUx.onVerseTap(gathering = true, ref = b))
    }

    @Test
    fun `leaving the reader sheet exits gather`() {
        assertEquals(ShareUxAction.ExitShare, ShareUx.onLeaveReaderSheet(gathering = true))
        assertEquals(ShareUxAction.None, ShareUx.onLeaveReaderSheet(gathering = false))
    }
}
