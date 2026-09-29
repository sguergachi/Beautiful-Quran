package com.beautifulquran.tarjilab

import org.junit.Assert.*
import org.junit.Test

class TarjiKnobHistoryTest {
    private val original = TarjiLabKnobs()

    @Test fun `a long drag undoes to its start and redoes to its end`() {
        val history = TarjiKnobHistory()
        var current = original
        repeat(100) {
            history.record(current)
            current = current.copy(holdMinMs = current.holdMinMs + 1)
        }
        history.finish(current)
        assertEquals(original, history.undo(current))
        assertFalse(history.canUndo)
        assertEquals(current, history.redo(original))
    }

    @Test fun `editing after undo discards the abandoned redo branch`() {
        val history = TarjiKnobHistory()
        val first = original.copy(attackMs = 200f)
        history.record(original)
        history.finish(first)
        assertEquals(original, history.undo(first))
        history.record(original)
        history.finish(original.copy(releaseMs = 500f))
        assertFalse(history.canRedo)
    }

    @Test fun `drag returning to original does not create an undo step`() {
        val history = TarjiKnobHistory()
        history.record(original)
        history.finish(original)
        assertFalse(history.canUndo)
    }

    @Test fun `new word clears both directions of history`() {
        val history = TarjiKnobHistory()
        val changed = original.copy(attackMs = 250f)
        history.record(original)
        history.finish(changed)
        history.undo(changed)
        history.clear()
        assertNull(history.undo(original))
        assertNull(history.redo(original))
    }

    @Test fun `history retains only the most recent 32 edits`() {
        val history = TarjiKnobHistory()
        var current = original
        repeat(40) {
            history.record(current)
            current = current.copy(holdMinMs = current.holdMinMs + 10)
            history.finish(current)
        }
        repeat(32) { current = history.undo(current)!! }
        assertEquals(original.holdMinMs + 80, current.holdMinMs, 0f)
        assertNull(history.undo(current))
    }
}
