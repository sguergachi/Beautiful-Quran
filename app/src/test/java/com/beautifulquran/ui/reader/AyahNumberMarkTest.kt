package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AyahNumberMarkTest {
    private val wordJoiner = "\u2060"

    @Test
    fun `Arabic mark uses RTL bracket order and Arabic-Indic digits`() {
        assertEquals(
            "﴿${wordJoiner}١${wordJoiner}٢${wordJoiner}﴾",
            formatAyahNumberMark(12, useArabicIndicDigits = true),
        )
    }

    @Test
    fun `English mark uses LTR bracket order and Western digits`() {
        assertEquals(
            "﴾${wordJoiner}1${wordJoiner}2${wordJoiner}﴿",
            formatAyahNumberMark(12, useArabicIndicDigits = false),
        )
    }

    @Test
    fun `mark characters are glued so they cannot line-break mid unit`() {
        val mark = formatAyahNumberMark(3, useArabicIndicDigits = false)
        assertEquals("﴾${wordJoiner}3${wordJoiner}﴿", mark)
        // No adjacent mark graphemes without a joiner between them.
        assertFalse(mark.contains("﴾3"))
        assertFalse(mark.contains("3﴿"))
    }
}
