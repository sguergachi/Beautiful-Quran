package com.beautifulquran.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

/**
 * [englishVerseProse] replaced `replace(Regex("\\s+"), " ").trim()` for speed
 * (see its comment). It must give the regex's answer for every input.
 */
class EnglishVerseProseTest {

    private fun viaRegex(text: String) = text.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `matches the regex on the cases that matter`() {
        listOf(
            "",
            " ",
            "plain verse with single spaces",
            "  leading and trailing  ",
            "line\nbreak",
            "carriage\r\nreturn",
            "tab\there",
            "vertical\u000Btab and\u000Cform feed",
            "a  double   space",
            "ends with newline\n",
            "\n\nstarts with newlines",
            // Not regex whitespace: kept in the text, though trim() takes them off the ends.
            "narrow no-break stays",
            "no-break stays",
            " edge narrow no-break ",
            "mixed  \n run",
        ).forEach { assertEquals("<$it>", viaRegex(it), englishVerseProse(it)) }
    }

    @Test
    fun `matches the regex on random text`() {
        val alphabet = "ab .,'\n\r\t\u000B\u000C   "
        val random = Random(7)
        repeat(20_000) {
            val text = String(CharArray(random.nextInt(0, 24)) { alphabet[random.nextInt(alphabet.length)] })
            assertEquals("<$text>", viaRegex(text), englishVerseProse(text))
        }
    }

    @Test
    fun `a verse with nothing to close up is not copied`() {
        val verse = "In the name of Allah, the Entirely Merciful"
        assertSame(verse, englishVerseProse(verse))
    }
}
