package com.beautifulquran.tarjilab

/** Bounded session history: a continuous slider gesture is one reversible edit. */
internal class TarjiKnobHistory {
    private val past = ArrayDeque<TarjiLabKnobs>()
    private val future = ArrayDeque<TarjiLabKnobs>()
    private var editing = false
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun record(before: TarjiLabKnobs) {
        if (editing) return
        past.addLast(before)
        if (past.size > 32) past.removeFirst()
        future.clear()
        editing = true
    }

    fun finish(current: TarjiLabKnobs) {
        if (editing && past.lastOrNull() == current) past.removeLast()
        editing = false
    }

    fun undo(current: TarjiLabKnobs): TarjiLabKnobs? {
        finish(current)
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return previous
    }

    fun redo(current: TarjiLabKnobs): TarjiLabKnobs? {
        finish(current)
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        return next
    }

    fun clear() {
        past.clear()
        future.clear()
        editing = false
    }
}

/** One capture's comparison checkpoint; switching it never changes persisted tuning. */
data class TarjiLabReference(val knobs: TarjiLabKnobs, val trace: TarjiLabTrace)
