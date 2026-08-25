package com.beautifulquran.ui.reader

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.beautifulquran.ui.theme.TranslationFontFamily
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
    fun `English mark emits the opposite code points so LTR mirroring paints cups toward the digits`() {
        val lri = "\u2066"
        val pdi = "\u2069"
        assertEquals(
            "$lri${wordJoiner}﴾${wordJoiner}1${wordJoiner}2${wordJoiner}﴿${wordJoiner}$pdi",
            formatAyahNumberMark(12, useArabicIndicDigits = false),
        )
    }

    @Test
    fun `mushaf mark is end-of-ayah plus Arabic-Indic digits`() {
        assertEquals(
            "\u06DD${wordJoiner}١${wordJoiner}٢",
            formatMushafAyahMark(12),
        )
        assertFalse(formatMushafAyahMark(3).contains('﴿'))
    }

    @Test
    fun `English mark is LTR-isolated so an RTL line cannot flip its brackets`() {
        val mark = formatAyahNumberMark(1, useArabicIndicDigits = false)
        assertEquals('\u2066', mark.first())
        assertEquals('\u2069', mark.last())
    }

    @Test
    fun `mark characters are glued so they cannot line-break mid unit`() {
        val mark = formatAyahNumberMark(3, useArabicIndicDigits = false)
        assertFalse(mark.contains("﴾3"))
        assertFalse(mark.contains("3﴿"))
    }

    @Test
    fun `English mark gives Western digits an explicit Garamond span`() {
        val mark = buildAnnotatedString {
            appendAyahNumberMark(12, useArabicIndicDigits = false, style = SpanStyle())
        }

        assertEquals(
            listOf(4 to 5, 6 to 7),
            mark.spanStyles
                .filter { it.item.fontFamily == TranslationFontFamily }
                .map { it.start to it.end },
        )
    }
}
