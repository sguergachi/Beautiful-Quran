package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderAyahInkPolicyTest {
    @Test
    fun `a pending word start fades verses on both sides even with a stale focus`() {
        for (target in 2..12) for (oldFocus in listOf(target - 1, target, target + 1)) {
            val active = (1..13).filter {
                readerAyahInkPolicyActive(it, target, oldFocus, awaitingWordStart = true)
            }
            assertEquals(listOf(target), active)
        }
    }

    @Test
    fun `a natural handoff prepares the next verse while the held word keeps its ink`() {
        assertEquals(listOf(7, 8), (6..9).filter {
            readerAyahInkPolicyActive(it, 7, 8, awaitingWordStart = false)
        })
    }

    @Test
    fun `fading surrounding verses leaves a tapped prefix fully inked`() {
        val tapped = ActiveWord(ayah = 7, wordPosition = 5, durationMs = 1_000,
            fromTap = true, tapStartPosition = 5)
        for (ayah in 6..8) for (position in 1..6) {
            val focused = readerAyahInkPolicyActive(ayah, tapped.ayah, 6, true)
            val state = InkEngine.wordState(position, tapped.takeIf { it.ayah == ayah },
                isActiveAyah = focused, dimmed = !focused)
            val expected = when {
                ayah != 7 || position > 5 -> InkEngine.State.Upcoming
                position == 5 -> InkEngine.State.Active
                else -> InkEngine.State.Recited
            }
            assertEquals("ayah=$ayah position=$position", expected, state)
        }
    }
}
