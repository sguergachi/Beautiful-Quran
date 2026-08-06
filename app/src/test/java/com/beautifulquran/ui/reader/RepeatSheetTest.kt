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

    @Test
    fun `from-this distance becomes range end when switching to range dials`() {
        // On ayah 5, "4 ayahs on" is 5..8 — flipping to range should land there,
        // not leave the end dial on a stale default (e.g. surah last).
        assertEquals(
            RangeDraft(from = 5, to = 8, nextNCount = 4),
            carryRangeDraft(
                fromChoice = RepeatChoice.NEXT_N_AYAHS,
                toChoice = RepeatChoice.AYAH_RANGE,
                currentAyah = 5,
                ayahCount = 120,
                from = 5,
                to = 120,
                nextNCount = 4,
            ),
        )
    }

    @Test
    fun `range end becomes from-this distance when switching to distance dial`() {
        assertEquals(
            RangeDraft(from = 10, to = 14, nextNCount = 5),
            carryRangeDraft(
                fromChoice = RepeatChoice.AYAH_RANGE,
                toChoice = RepeatChoice.NEXT_N_AYAHS,
                currentAyah = 10,
                ayahCount = 120,
                from = 10,
                to = 14,
                nextNCount = 2,
            ),
        )
    }

    @Test
    fun `carry clamps distance at surah end`() {
        assertEquals(
            RangeDraft(from = 5, to = 7, nextNCount = 3),
            carryRangeDraft(
                fromChoice = RepeatChoice.NEXT_N_AYAHS,
                toChoice = RepeatChoice.AYAH_RANGE,
                currentAyah = 5,
                ayahCount = 7,
                from = 1,
                to = 7,
                nextNCount = 3,
            ),
        )
    }

    @Test
    fun `carry leaves draft alone when choice is not a range instrument flip`() {
        assertEquals(
            RangeDraft(from = 3, to = 9, nextNCount = 2),
            carryRangeDraft(
                fromChoice = RepeatChoice.OFF,
                toChoice = RepeatChoice.AYAH_RANGE,
                currentAyah = 5,
                ayahCount = 120,
                from = 3,
                to = 9,
                nextNCount = 2,
            ),
        )
    }
}
