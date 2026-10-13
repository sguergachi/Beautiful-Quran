package com.beautifulquran.data

import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTimingHttpApiTest {
    @Test
    fun `access rejection applies globally and a withdrawn resource applies only to its reciter`() {
        assertTrue(runCatching { throwRuntimeTimingHttpError(403, "qf_access_revoked") }.exceptionOrNull() is QfAccessRevokedException)
        assertTrue(runCatching { throwRuntimeTimingHttpError(410, "qf_timing_resource_deleted") }.exceptionOrNull() is QfTimingResourceDeletedException)
    }

    @Test
    fun `review holds outages and unrelated rejection codes preserve the prior generation`() {
        listOf(503 to "qf_timing_review_required", 410 to "resync_required", 403 to "origin_not_allowed").forEach { (status, code) ->
            assertTrue(runCatching { throwRuntimeTimingHttpError(status, code) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun `insecure provider origins are rejected before making a request`() {
        assertTrue(runCatching { RuntimeTimingHttpApi("http://example.com") }.isFailure)
    }
}
