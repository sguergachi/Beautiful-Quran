package com.beautifulquran.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScrollDismissTest {
    @Test
    fun stillListNeverDismisses() {
        assertFalse(shouldDismissSearchOnScroll(false, 0, 0))
        assertFalse(shouldDismissSearchOnScroll(false, 3, 500))
    }

    @Test
    fun parkedListNeverDismisses() {
        assertFalse(shouldDismissSearchOnScroll(true, 0, 0))
        assertFalse(shouldDismissSearchOnScroll(true, 0, DISMISS_SCROLL_THRESHOLD_PX))
    }

    @Test
    fun deliberateScrollDismisses() {
        assertTrue(shouldDismissSearchOnScroll(true, 0, DISMISS_SCROLL_THRESHOLD_PX + 1))
        assertTrue(shouldDismissSearchOnScroll(true, 1, 0))
    }
}
