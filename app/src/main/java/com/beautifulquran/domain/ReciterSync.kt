package com.beautifulquran.domain

/** Visual clock calibration for a reciter whose source-aligned ink lands late. */
object ReciterSync {
    internal const val YASSER_AL_DOSARI_ID = 9
    internal const val YASSER_ADVANCE_MS = 200L

    /** Source-clock calibration only; output latency belongs to the presentation clock. */
    fun highlightAdvanceMs(reciterId: Int): Long =
        if (reciterId == YASSER_AL_DOSARI_ID) YASSER_ADVANCE_MS else 0L
}
