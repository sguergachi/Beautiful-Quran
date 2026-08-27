package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ActiveWordResolveTest {

    private fun word(
        ayah: Int = 2,
        position: Int = 5,
        activation: Long = 6,
        fromTap: Boolean = false,
        durationMs: Long = 400,
    ) = ActiveWord(
        ayah = ayah,
        wordPosition = position,
        durationMs = durationMs,
        activation = activation,
        fromTap = fromTap,
    )

    @Test
    fun `a tap seed wins over a stale poll`() {
        val seed = word(fromTap = true)
        val stale = word(ayah = 1, position = 3, activation = 5)
        assertSame(seed, resolveActiveWord(stale, seed, seed))
    }

    @Test
    fun `poll replacing a tap seed keeps the seed activation`() {
        val seed = word(activation = 6, fromTap = true)
        val polled = word(activation = 7, durationMs = 420)
        val shown = resolveActiveWord(polled, tap = null, seed = seed)!!
        assertEquals(6L, shown.activation)
        assertFalse(shown.fromTap)
        assertEquals(420L, shown.durationMs)
    }

    @Test
    fun `matching poll while the seed is held still pins activation`() {
        val seed = word(activation = 6, fromTap = true)
        val polled = word(activation = 8)
        val shown = resolveActiveWord(polled, tap = seed, seed = seed)!!
        assertEquals(6L, shown.activation)
        assertFalse(shown.fromTap)
    }

    @Test
    fun `the next word uses the poll activation`() {
        val seed = word(position = 5, activation = 6, fromTap = true)
        val next = word(position = 6, activation = 7)
        assertSame(next, resolveActiveWord(next, tap = null, seed = seed))
    }
}
