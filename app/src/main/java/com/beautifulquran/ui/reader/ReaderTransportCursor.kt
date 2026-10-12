package com.beautifulquran.ui.reader

import androidx.media3.common.Player
import com.beautifulquran.playback.PlaybackPositionEvents

/** Chains quick transport presses from their last intent until that seek actually lands. */
internal class ReaderTransportCursor {
    data class Position(val ayah: Int, val positionMs: Long)

    private var pending: Position? = null
    private var issuedClockId = 0L

    fun current(ayah: Int, positionMs: Long, events: PlaybackPositionEvents): Position {
        val target = pending
        if (target != null && events.clockId != issuedClockId &&
            (events.reason != Player.DISCONTINUITY_REASON_SEEK ||
                (ayah == target.ayah && events.positionMs == target.positionMs))
        ) {
            pending = null
        }
        return pending ?: Position(ayah, positionMs)
    }

    fun seek(ayah: Int, positionMs: Long, clockId: Long) {
        pending = Position(ayah, positionMs)
        issuedClockId = clockId
    }

    fun clear() {
        pending = null
    }
}
