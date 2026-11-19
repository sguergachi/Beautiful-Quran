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

    @Test fun theLastPinHandsOnToTheBookWhereItSits() {
        assertEquals(37, chapterAfter(36, all, listOf(67, 2, 36)))
    }

    @Test fun theRestSkipsWhatThePinsAlreadyMoved() {
        val pinned = listOf(3, 2)
        assertEquals(4, chapterAfter(2, all, pinned))
        assertEquals(4, chapterAfter(1, all, pinned))
    }

    @Test fun theBookResumedAfterThePinsSkipsPinsAlreadyPlayed() {
        val pinned = listOf(67, 2, 36)
        assertEquals(68, chapterAfter(66, all, pinned))
        assertEquals(37, chapterAfter(35, all, pinned))
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

    @Test fun theReaderNextForAPinnedChapterIsTheNextPin() {
        // Chapter 107 pinned with 109 after it: NEXT is the pin, not 108.
        assertEquals(109, readerNextChapter(107, all, listOf(107, 109)))
        assertEquals(1, readerNextChapter(2, all, listOf(2, 1)))
    }

    @Test fun theReaderNextForTheLastPinResumesTheBook() {
        assertEquals(3, readerNextChapter(1, all, listOf(2, 1)))
    }

    @Test fun theReaderNextForAnUnpinnedChapterStaysTheBooksNext() {
        // Even when the book's next chapter is itself pinned.
        assertEquals(36, readerNextChapter(35, all, listOf(36)))
        assertNull(readerNextChapter(114, all, emptyList()))
    }

    @Test fun legacyUnorderedPinsKeepTheBookOrderTheBandShowed() {
        assertEquals(listOf(2, 36, 67), readPinnedSurahIds(null, setOf("67", "2", "36")))
        assertEquals(listOf(67, 2), readPinnedSurahIds("67,2,x,67", setOf("1")))
    }
}
