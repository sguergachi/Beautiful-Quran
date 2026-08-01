package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ParentheticalTextTest {

    @Test
    fun `hides parenthetical English including parentheses across word glosses`() {
        assertEquals(
            listOf("Say", "", "to", "them"),
            hideParentheticalText(listOf("Say", "(O Messenger)", "to", "them")),
        )
    }

    @Test
    fun `hides nested and word-spanning parenthetical English`() {
        assertEquals(
            listOf("He", "", "", "knows"),
            hideParentheticalText(listOf("He", "(alone", "the (unseen))", "knows")),
        )
    }

    @Test
    fun `hides square-bracketed English across word glosses`() {
        assertEquals(
            listOf("He", "", "knows"),
            hideParentheticalText(listOf("He", "[alone]", "knows")),
        )
        assertEquals(
            listOf("He", "", "", "knows"),
            hideParentheticalText(listOf("He", "[alone", "the (unseen)]", "knows")),
        )
    }

    @Test
    fun `keeps an unmatched closing parenthesis as text`() {
        assertEquals(listOf("word)"), hideParentheticalText(listOf("word)")))
        assertEquals(listOf("word]"), hideParentheticalText(listOf("word]")))
    }

    @Test
    fun `punctuation closes the last remaining English word`() {
        assertEquals(
            listOf("It.", ""),
            EnglishTypography.punctuate(hideParentheticalText(listOf("It", "(is)"))),
        )
    }

    @Test
    fun `lyric prose coalesces before it hides parenthetical English`() {
        assertEquals(
            listOf("Say", "", "", "it."),
            EnglishTypography.lyricize(
                glosses = listOf("Say", "(O Messenger)", "(O Messenger)", "it"),
                arabicWords = listOf("قُلْ", "يَا", "أَيُّهَا", "لَهُ"),
                hideParentheticals = true,
            ),
        )
    }
}
