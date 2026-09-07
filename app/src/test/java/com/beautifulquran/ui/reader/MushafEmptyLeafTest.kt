package com.beautifulquran.ui.reader

import com.beautifulquran.data.RuntimeCachePhase
import com.beautifulquran.data.RuntimeCacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The blank leaf always carries its reason — except for a breath on first
 * paint with a warm cache, which stays blank.
 */
class MushafEmptyLeafTest {

    private fun status(
        phase: RuntimeCachePhase,
        apiCalls: Long = 0,
        lastError: String? = null,
    ) = RuntimeCacheStatus(phase, null, null, null, apiCalls, lastError)

    @Test
    fun `fresh cache stays blank`() {
        assertNull(mushafEmptyLeafMessage(status(RuntimeCachePhase.FRESH)))
    }

    @Test
    fun `first paint says preparing`() {
        assertEquals(
            "Preparing the pages…",
            mushafEmptyLeafMessage(status(RuntimeCachePhase.EMPTY))?.line,
        )
        assertEquals(
            "Preparing the pages…",
            mushafEmptyLeafMessage(status(RuntimeCachePhase.REFRESHING))?.line,
        )
    }

    @Test
    fun `download in flight counts its requests`() {
        val message = mushafEmptyLeafMessage(status(RuntimeCachePhase.REFRESHING, apiCalls = 4))
        assertEquals("Setting down the pages…", message?.line)
        assertEquals("4 requests so far", message?.subline)
    }

    @Test
    fun `failure names its reason and offers retry`() {
        val message = mushafEmptyLeafMessage(
            status(RuntimeCachePhase.ERROR, lastError = "QF Content API returned 404"),
        )
        assertEquals("The pages could not be reached", message?.line)
        assertEquals(
            "QF Content API returned 404 — trying again — tap to try now",
            message?.subline,
        )
    }
}
