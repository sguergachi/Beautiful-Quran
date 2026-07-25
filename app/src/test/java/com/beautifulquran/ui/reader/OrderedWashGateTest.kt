package com.beautifulquran.ui.reader

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderedWashGateTest {

    @Test
    fun `drains by word position not enqueue order`() = runBlocking {
        val gate = OrderedWashGate()
        val pump = launch { gate.pump() }
        val order = mutableListOf<Int>()

        // Enqueue high positions first — must still run 5 → 7 → 9.
        val high = async {
            gate.run(9) {
                order += 9
                delay(5)
            }
        }
        val mid = async {
            gate.run(7) {
                order += 7
                delay(5)
            }
        }
        val low = async {
            gate.run(5) {
                order += 5
                delay(5)
            }
        }

        high.await()
        mid.await()
        low.await()
        pump.cancel()
        assertEquals(listOf(5, 7, 9), order)
    }

    @Test
    fun `same position runs fifo`() = runBlocking {
        val gate = OrderedWashGate()
        val pump = launch { gate.pump() }
        val order = mutableListOf<String>()

        val first = async {
            gate.run(3) {
                order += "a"
                delay(10)
            }
        }
        val second = async {
            gate.run(3) {
                order += "b"
            }
        }
        first.await()
        second.await()
        pump.cancel()
        assertEquals(listOf("a", "b"), order)
    }
}
