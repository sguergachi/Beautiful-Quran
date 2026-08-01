package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class AyahNumberMarkTest {
    @Test
    fun `Arabic mark uses RTL bracket order and Arabic-Indic digits`() {
        assertEquals("﴿١٢﴾", formatAyahNumberMark(12, useArabicIndicDigits = true))
    }

    @Test
    fun `English mark uses LTR bracket order and Western digits`() {
        assertEquals("﴾12﴿", formatAyahNumberMark(12, useArabicIndicDigits = false))
    }
}
