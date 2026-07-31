package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LemmaGlossTest {

    @Test
    fun `pools renderings that differ only by article or aside`() {
        // كِتَٰب — "the Book" wins once its variants vote together.
        val gloss = LemmaGloss.pick(
            listOf(
                GlossVote("(of) the Book", 43),
                GlossVote("the Book", 93),
                GlossVote("the Scripture", 14),
                GlossVote("a Book", 9),
                GlossVote("(the) Book", 7),
            ),
        )

        assertEquals("Book", gloss)
    }

    @Test
    fun `strips a leading conjunction and a trailing object`() {
        assertEquals("mercy", LemmaGloss.pick(listOf(GlossVote("and mercy", 3))))
        assertEquals("show mercy", LemmaGloss.pick(listOf(GlossVote("show mercy upon them", 3))))
    }

    @Test
    fun `keeps a rendering that is nothing but framing words`() {
        // كان: trimming "is" to nothing would hand the lemma to a rare form.
        val gloss = LemmaGloss.pick(
            listOf(
                GlossVote("is", 146),
                GlossVote("And is", 37),
                GlossVote("they used (to)", 29),
            ),
        )

        assertEquals("is", gloss)
    }

    @Test
    fun `prefers the most used rendering inside the winning meaning`() {
        val gloss = LemmaGloss.pick(
            listOf(
                GlossVote("the All-Knower", 17),
                GlossVote("All-Knowing", 26),
                GlossVote("(is) All-Knower", 26),
                GlossVote("learned", 7),
            ),
        )

        assertEquals("All-Knower", gloss)
    }

    @Test
    fun `ties resolve alphabetically so the gloss never flickers`() {
        val votes = listOf(GlossVote("wombs", 4), GlossVote("kinship", 4))

        assertEquals("kinship", LemmaGloss.pick(votes))
        assertEquals("kinship", LemmaGloss.pick(votes.reversed()))
    }

    @Test
    fun `returns nothing when no rendering survives`() {
        assertEquals("", LemmaGloss.pick(emptyList()))
        assertEquals("", LemmaGloss.pick(listOf(GlossVote("(unused)", 2), GlossVote("  ", 1))))
    }
}
