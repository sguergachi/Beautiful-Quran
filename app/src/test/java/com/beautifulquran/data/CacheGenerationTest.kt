package com.beautifulquran.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CacheGenerationTest {
    @Test
    fun `invalidation does not block on a build and old result is neither returned nor saved`() {
        val cache = CacheGeneration()
        var saved: String? = null
        var source = "old"
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writes = mutableListOf<String>()
        try {
            val result = executor.submit<String> {
                cache.getOrBuild({ saved }, { saved = it; writes += it }) {
                    val snapshot = source
                    if (snapshot == "old") {
                        started.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                    snapshot
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            source = "new"
            cache.invalidate { saved = null }
            resume.countDown()
            assertEquals("new", result.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("new"), writes)
            assertEquals("new", cache.getOrBuild({ saved }, { saved = it }) { error("cache miss") })
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a concurrent current-generation winner is reused`() {
        val cache = CacheGeneration()
        var saved: String? = null
        val result = cache.getOrBuild({ saved }, { saved = it }) {
            cache.invalidate { saved = null }
            cache.getOrBuild({ saved }, { saved = it }) { "new" }
            "old"
        }
        assertEquals("new", result)
        assertEquals("new", saved)
    }

    @Test
    fun `failed build publishes nothing and later build can recover`() {
        val cache = CacheGeneration()
        var saved: String? = null
        assertTrue(runCatching {
            cache.getOrBuild({ saved }, { saved = it }) { error("failed") }
        }.isFailure)
        assertNull(saved)
        assertEquals("recovered", cache.getOrBuild({ saved }, { saved = it }) { "recovered" })
    }
}
