package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings enums are persisted by ordinal; a stale ordinal (an entry removed
 * in an app update) must degrade to the default, never crash on startup.
 */
class EnumForOrdinalTest {

    @Test
    fun `valid ordinal maps to its entry`() {
        assertEquals(
            ThemeMode.DARK,
            enumForOrdinal(ThemeMode.entries, ThemeMode.DARK.ordinal, ThemeMode.SYSTEM),
        )
    }

    @Test
    fun `stale ordinal falls back to the default`() {
        assertEquals(
            ThemeMode.SYSTEM,
            enumForOrdinal(ThemeMode.entries, ThemeMode.entries.size, ThemeMode.SYSTEM),
        )
        assertEquals(
            ReadingMode.ARABIC_ENGLISH,
            enumForOrdinal(ReadingMode.entries, 99, ReadingMode.ARABIC_ENGLISH),
        )
        assertEquals(
            ReadingLayout.MUSHAF,
            enumForOrdinal(ReadingLayout.entries, 99, ReadingLayout.MUSHAF),
        )
    }

    @Test
    fun `negative ordinal falls back to the default`() {
        assertEquals(
            AyahSelectorSide.LEFT,
            enumForOrdinal(AyahSelectorSide.entries, -1, AyahSelectorSide.LEFT),
        )
    }

    @Test
    fun `home bookmark styles map by ordinal and stale values use top bound`() {
        HomeBookmarkStyle.entries.forEach { style ->
            assertEquals(
                style,
                enumForOrdinal(
                    HomeBookmarkStyle.entries,
                    style.ordinal,
                    HomeBookmarkStyle.TOP_BOUND,
                ),
            )
        }
        assertEquals(
            HomeBookmarkStyle.TOP_BOUND,
            enumForOrdinal(
                HomeBookmarkStyle.entries,
                99,
                HomeBookmarkStyle.TOP_BOUND,
            ),
        )
        assertEquals(
            HomeBookmarkStyle.TOP_BOUND,
            enumForOrdinal(
                HomeBookmarkStyle.entries,
                -1,
                HomeBookmarkStyle.TOP_BOUND,
            ),
        )
    }

    @Test
    fun `brush circle styles map by ordinal and stale values use baseline`() {
        BrushCircleStyle.entries.forEach { style ->
            assertEquals(
                style,
                enumForOrdinal(
                    BrushCircleStyle.entries,
                    style.ordinal,
                    BrushCircleStyle.BASELINE,
                ),
            )
        }
        assertEquals(
            BrushCircleStyle.BASELINE,
            enumForOrdinal(BrushCircleStyle.entries, 99, BrushCircleStyle.BASELINE),
        )
    }
}
