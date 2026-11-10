package com.beautifulquran.ui.entrance

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverFontScaleTest {

    @Test
    fun `system scale is kept while the du'a fits`() {
        assertEquals(1.3f, coverFontScale(1.3f, availableWidthDp = 331f, duaWidthAtUnitScaleDp = 208f), 0f)
        assertEquals(0.85f, coverFontScale(0.85f, availableWidthDp = 240f, duaWidthAtUnitScaleDp = 208f), 0f)
    }

    @Test
    fun `large text stops where the du'a fills the measure`() {
        // 360 dp phone at 2x text: the du'a is held to one line.
        val scale = coverFontScale(2f, availableWidthDp = 280f, duaWidthAtUnitScaleDp = 208f)
        assertEquals(280f / 208f, scale, 1e-4f)
        assertEquals(280f, 208f * scale, 1e-3f)
    }

    @Test
    fun `a very narrow screen shrinks the type no further than the floor`() {
        assertEquals(COVER_MIN_FONT_SCALE, coverFontScale(1f, availableWidthDp = 100f, duaWidthAtUnitScaleDp = 208f), 0f)
    }

    @Test
    fun `unmeasured input leaves the system scale alone`() {
        assertEquals(1.5f, coverFontScale(1.5f, availableWidthDp = 280f, duaWidthAtUnitScaleDp = 0f), 0f)
    }
}
