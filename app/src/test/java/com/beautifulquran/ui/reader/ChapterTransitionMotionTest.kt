package com.beautifulquran.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTransitionMotionTest {
    @Test fun partialReleaseKeepsTheFingerPose() {
        // A tapped invitation may commit before the pull is full. The old
        // release sine curve jumped from 117px to 110px at half fill.
        assertEquals(117f, previousChapterPullOffset(0.5f, 156f), 0.001f)
        assertEquals(0f, previousChapterPullOffset(-1f, 156f), 0f)
        assertEquals(156f, previousChapterPullOffset(2f, 156f), 0f)
    }

    @Test fun arrivalInkNeverFlashesOrOvershoots() {
        assertEquals(0f, chapterVerseAlpha(0f), 0f)
        assertEquals(1f, chapterVerseAlpha(1f), 0f)
        var previous = 0f
        for (frame in 0..120) {
            val alpha = chapterVerseAlpha(frame / 120f)
            assertTrue(alpha in previous..1f)
            assertTrue(alpha - previous < 0.02f)
            previous = alpha
        }
    }
}
