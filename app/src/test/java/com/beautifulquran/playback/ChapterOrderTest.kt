package com.beautifulquran.playback

import com.beautifulquran.data.readPinnedSurahIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterOrderTest {
    private val all = (1..114).toList()

    @Test fun withoutPinsTheNextChapterIsTheNextInTheBook() {
        assertEquals(37, chapterAfter(36, all, emptyList()))
    }

    @Test fun pinnedChaptersPlayInTheOrderTheyWerePinned() {
        val pinned = listOf(67, 2, 36)
        assertEquals(2, chapterAfter(67, all, pinned))
        assertEquals(36, chapterAfter(2, all, pinned))
    }

    @Test fun theLastPinHandsOnToTheTopOfTheRest() {
        assertEquals(1, chapterAfter(36, all, listOf(67, 2, 36)))
    }

    @Test fun theRestSkipsWhatThePinsAlreadyMoved() {
        val pinned = listOf(3, 2)
        assertEquals(1, chapterAfter(2, all, pinned))
        assertEquals(4, chapterAfter(1, all, pinned))
    }

    @Test fun theFootOfThePageIsTheEnd() {
        assertNull(chapterAfter(114, all, emptyList()))
    }

    @Test fun aPinnedLastChapterDoesNotEndThePlaylist() {
        assertEquals(1, chapterAfter(114, all, listOf(114)))
        assertEquals(113, chapterAfter(112, all, listOf(114)))
        assertNull(chapterAfter(113, all, listOf(114)))
    }

    @Test fun anUnknownChapterHasNoNext() {
        assertNull(chapterAfter(0, all, emptyList()))
    }

    @Test fun legacyUnorderedPinsKeepTheBookOrderTheBandShowed() {
        assertEquals(listOf(2, 36, 67), readPinnedSurahIds(null, setOf("67", "2", "36")))
        assertEquals(listOf(67, 2), readPinnedSurahIds("67,2,x,67", setOf("1")))
    }
}
