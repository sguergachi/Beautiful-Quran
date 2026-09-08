package com.beautifulquran.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.beautifulquran.ui.theme.TranslationFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `Arabic-Indic mark in LTR English still isolates and swaps cups`() {
        val lri = "\u2066"
        val pdi = "\u2069"
        assertEquals(
            "$lri${wordJoiner}﴾${wordJoiner}١${wordJoiner}٢${wordJoiner}﴿${wordJoiner}$pdi",
            formatAyahNumberMark(12, useArabicIndicDigits = true, ltr = true),
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
    fun `upcoming mushaf mark receives one focus fade rather than two`() {
        assertEquals(0.22f, mushafAyahMarkInkAlpha(liveInk = true, markAlpha = 0.22f))
        assertEquals(1f, mushafAyahMarkInkAlpha(liveInk = false, markAlpha = 0.22f))
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
    fun `mark hit ignores zero-width joiners reported at the origin`() {
        val cups = Rect(120f, 8f, 148f, 28f)
        val originJoiner = Rect(0f, 0f, 0f, 0f)
        val bounds = visibleGlyphBounds(listOf(originJoiner, cups, originJoiner))
        assertEquals(cups, bounds)
        assertTrue(bounds!!.inflate(8f).contains(androidx.compose.ui.geometry.Offset(130f, 18f)))
        assertFalse(bounds.inflate(8f).contains(androidx.compose.ui.geometry.Offset(2f, 2f)))
    }

    @Test
    fun `tap on the glue space or past the last glyph still hits the mark`() {
        val mark = 10..16
        assertTrue(tapHitsRange(offset = 10, range = mark, textLength = 17))
        assertTrue(tapHitsRange(offset = 16, range = mark, textLength = 17))
        assertTrue(tapHitsRange(offset = 17, range = mark, textLength = 17))
        assertFalse(tapHitsRange(offset = 9, range = mark, textLength = 17))
        assertFalse(tapHitsRange(offset = 0, range = 0..-1, textLength = 17))
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
