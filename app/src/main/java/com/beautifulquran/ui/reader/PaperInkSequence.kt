package com.beautifulquran.ui.reader

/** The isolated study plays 1 → 2 → 2 → 3 using Media3's native item repeat. */
internal class PaperInkSequence {
    var ayah = 0
        private set
    var repeatedMiddle = false
        private set
    private var inkId = Long.MIN_VALUE

    /** Returns whether verse 2 should still be armed for its one replay. */
    fun observe(ayah: Int, inkId: Long): Boolean {
        if (ayah == 2 && this.ayah == 2 && inkId != this.inkId) repeatedMiddle = true
        this.ayah = ayah
        this.inkId = inkId
        return ayah == 2 && !repeatedMiddle
    }

    fun reset() {
        ayah = 0
        inkId = Long.MIN_VALUE
        repeatedMiddle = false
    }
}
