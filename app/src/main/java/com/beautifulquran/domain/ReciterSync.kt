package com.beautifulquran.domain

/** Visual clock calibration for a reciter whose source-aligned ink lands late. */
object ReciterSync {
    internal const val ALAFASY_ID = 1
    internal const val ALAFASY_BLUETOOTH_ADVANCE_MS = 200L
    internal const val YASSER_AL_DOSARI_ID = 9
    internal const val YASSER_ADVANCE_MS = 200L

    /**
     * Extra word-highlight lead; timing rows and non-word playback stay untouched.
     * Alafasy's reported Bluetooth residual is a wall-time visual trim, so it
     * scales with playback speed. Yasser's existing source-clock trim does not.
     * [bluetoothOutput] describes the actual routed track, never paired devices.
     */
    fun highlightAdvanceMs(
        reciterId: Int,
        bluetoothOutput: Boolean = false,
        playbackSpeed: Float = 1f,
    ): Long = when {
        reciterId == ALAFASY_ID && bluetoothOutput ->
            (ALAFASY_BLUETOOTH_ADVANCE_MS * playbackSpeed.coerceAtLeast(0f)).toLong()
        reciterId == YASSER_AL_DOSARI_ID -> YASSER_ADVANCE_MS
        else -> 0L
    }
}
