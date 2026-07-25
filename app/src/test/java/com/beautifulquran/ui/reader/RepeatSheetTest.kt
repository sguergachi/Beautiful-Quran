package com.beautifulquran.ui.reader

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatSheetTest {

    @Test
    fun `retains explicit range choice when range starts at current ayah`() {
        assertEquals(
            RepeatChoice.AYAH_RANGE,
            repeatChoice(
                repeatMode = Player.REPEAT_MODE_OFF,
                repeatRange = 4..8,
                currentAyah = 4,
                retainedChoice = RepeatChoice.AYAH_RANGE,
            ),
        )
    }

    @Test
    fun `infers from-this-ayah when no explicit choice was retained`() {
        assertEquals(
            RepeatChoice.NEXT_N_AYAHS,
            repeatChoice(
                repeatMode = Player.REPEAT_MODE_OFF,
                repeatRange = 4..8,
                currentAyah = 4,
                retainedChoice = null,
            ),
        )
    }

    @Test
    fun `ignores retained range choice after repeat is turned off`() {
        assertEquals(
            RepeatChoice.OFF,
            repeatChoice(
                repeatMode = Player.REPEAT_MODE_OFF,
                repeatRange = null,
                currentAyah = 4,
                retainedChoice = RepeatChoice.AYAH_RANGE,
            ),
        )
    }
}
