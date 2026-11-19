package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReciterChoiceTest {

    @Test
    fun `choosing a voice that is not a favourite stars it`() {
        val before = Settings(reciterId = 1, favoriteReciterIds = setOf(1, 7))
        val after = before.withReciter(6)
        assertEquals(6, after.reciterId)
        assertEquals(setOf(1, 7, 6), after.favoriteReciterIds)
    }

    @Test
    fun `choosing a favourite leaves the favourites as they were`() {
        val before = Settings(reciterId = 1, favoriteReciterIds = setOf(1, 7))
        val after = before.withReciter(7)
        assertEquals(7, after.reciterId)
        assertEquals(setOf(1, 7), after.favoriteReciterIds)
        assertTrue(1 in after.favoriteReciterIds)
    }
}
