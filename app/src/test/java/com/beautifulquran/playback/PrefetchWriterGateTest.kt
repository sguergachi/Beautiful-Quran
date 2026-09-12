package com.beautifulquran.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchWriterGateTest {

    @Test
    fun cancelBeforeRunSkipsWork() {
        val gate = PrefetchWriterGate()
        val ran = AtomicInteger()
        gate.cancel()
        assertFalse(gate.run({ }) { ran.incrementAndGet() })
        assertEquals(0, ran.get())
    }

    @Test
    fun cancelDuringRunInvokesTheWriterCancel() {
        val gate = PrefetchWriterGate()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        Thread {
            gate.run(
                cancelWork = { cancelled.countDown() },
                work = {
                    started.countDown()
                    check(cancelled.await(2, TimeUnit.SECONDS))
                },
            )
            finished.countDown()
        }.start()
        check(started.await(2, TimeUnit.SECONDS))
        gate.cancel()
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(gate.run({ }) { error("superseded generation must not start") })
    }
}
