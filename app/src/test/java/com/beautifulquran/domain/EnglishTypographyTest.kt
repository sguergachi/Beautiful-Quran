package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTypographyTest {
    @Test
    fun `adds a stop only at the ayah end`() {
        assertEquals(
            listOf("and killed", "Dawood", "Jalut", "And if not."),
            EnglishTypography.punctuate(
                listOf("and killed", "Dawood", "Jalut", "And if not"),
            ),
        )
    }

    @Test
    fun `does not infer internal sentence boundaries from capitals`() {
        assertEquals(
            listOf("that which", "He willed", "And if not", "the worlds."),
            EnglishTypography.punctuate(
                listOf("that which", "He willed", "And if not", "the worlds"),
            ),
        )
    }

    @Test
    fun `preserves existing punctuation`() {
        assertEquals(
            listOf("Why?", "Then", "listen!"),
            EnglishTypography.punctuate(
                listOf("Why?", "Then", "listen!"),
            ),
        )
    }
}
