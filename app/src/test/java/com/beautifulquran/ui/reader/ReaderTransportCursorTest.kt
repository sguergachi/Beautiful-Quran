package com.beautifulquran.ui.reader

import androidx.media3.common.Player
import com.beautifulquran.playback.PlaybackPositionEvents
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTransportCursorTest {
    private val cursor = ReaderTransportCursor()

    @Test
    fun `back then forward returns to the original verse before either seek lands`() {
        cursor.seek(5, 0, clockId = 0)
        val previous = cursor.current(6, 0, seek(0, 0))
        val action = FastForwardPolicy.action(previous.ayah, previous.positionMs, 286, null, 0)
        assertEquals(FastForwardPolicy.Action.SeekToAyah(6), action)
        cursor.seek(6, 0, clockId = 0)
        assertEquals(ReaderTransportCursor.Position(6, 0), cursor.current(5, 10, seek(1, 0)))
    }

    @Test
    fun `two backs restart then go to the previous verse while the clock remains stale`() {
        cursor.seek(6, 0, clockId = 2)
        assertEquals(ReaderTransportCursor.Position(6, 0), cursor.current(6, 9_000, seek(2, 0)))
        cursor.seek(5, 0, clockId = 2)
        assertEquals(ReaderTransportCursor.Position(5, 0), cursor.current(6, 9_000, seek(2, 0)))
    }

    @Test
    fun `an earlier same-verse restart cannot acknowledge the later midpoint seek`() {
        cursor.seek(6, 20_000, clockId = 0)
        assertEquals(ReaderTransportCursor.Position(6, 20_000), cursor.current(6, 0, seek(1, 0)))
        assertEquals(ReaderTransportCursor.Position(6, 20_500), cursor.current(6, 20_500, seek(2, 20_000)))
    }

    @Test
    fun `natural playlist movement retires a pending transport`() {
        cursor.seek(6, 0, clockId = 1)
        val transition = seek(2, 0).copy(reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(ReaderTransportCursor.Position(7, 10), cursor.current(7, 10, transition))
    }

    @Test
    fun `a new reader seek clears the previous transport intent`() {
        cursor.seek(6, 0, clockId = 1)
        cursor.clear()
        assertEquals(ReaderTransportCursor.Position(35, 5_000), cursor.current(35, 5_000, seek(1, 0)))
    }

    @Test
    fun `a chapter restart retains the basmalah playlist position until it arrives`() {
        cursor.seek(0, 0, clockId = 1)
        assertEquals(ReaderTransportCursor.Position(0, 0), cursor.current(1, 0, seek(1, 0)))
        assertEquals(ReaderTransportCursor.Position(0, 300), cursor.current(0, 300, seek(2, 0)))
    }

    private fun seek(clockId: Long, positionMs: Long) = PlaybackPositionEvents(
        clockId = clockId,
        reason = Player.DISCONTINUITY_REASON_SEEK,
        positionMs = positionMs,
    )
}
