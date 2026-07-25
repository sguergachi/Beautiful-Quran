package com.beautifulquran.ui.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Per-ayah gate that runs orange wash jobs **one at a time in word-position
 * order**. A plain [Mutex] is only FIFO by *acquire* time, which can invert
 * when every chain member enters on the same frame (seek into a chain).
 *
 * Senders enqueue by [position]; a single consumer always drains the lowest
 * position first so sequential residual wash stays position-ordered.
 * Same-turn enqueues are batched with [yield] after wake before the first
 * drain so seek-into-chain lands in the sorted map first.
 */
class OrderedWashGate {
    private data class Job(
        val position: Int,
        val block: suspend () -> Unit,
        val done: CompletableDeferred<Unit>,
    )

    private val mutex = Mutex()
    /** position → FIFO of jobs at that position (seek re-washes stack). */
    private val pending = sortedMapOf<Int, ArrayDeque<Job>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pumping = false

    /**
     * Run [block] when this [position] is the next in line. Suspends until
     * [block] completes. Concurrent enqueues at higher positions wait.
     */
    suspend fun run(position: Int, block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        mutex.withLock {
            pending.getOrPut(position) { ArrayDeque() }.addLast(Job(position, block, done))
            wake.trySend(Unit)
        }
        done.await()
    }

    /**
     * Single consumer — call once from a long-lived [kotlinx.coroutines.CoroutineScope]
     * (e.g. `LaunchedEffect(gate) { gate.pump() }`).
     */
    suspend fun pump() {
        mutex.withLock {
            if (pumping) return
            pumping = true
        }
        try {
            while (true) {
                wake.receive()
                // Let sibling enqueues from the same Compose/frame turn join
                // before we pick the lowest position (seek-into-chain).
                yield()
                while (true) {
                    val job = mutex.withLock {
                        val firstKey = pending.keys.firstOrNull() ?: return@withLock null
                        val q = pending.getValue(firstKey)
                        val j = q.removeFirst()
                        if (q.isEmpty()) pending.remove(firstKey)
                        j
                    } ?: break
                    try {
                        job.block()
                        job.done.complete(Unit)
                    } catch (ce: CancellationException) {
                        // Completing the waiter with cancel (not completeExceptionally)
                        // so a foreign CancellationException does not kill the
                        // word's LaunchedEffect collector via done.await().
                        job.done.cancel(ce)
                        throw ce
                    } catch (t: Throwable) {
                        job.done.completeExceptionally(t)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { pumping = false }
            }
        }
    }
}
