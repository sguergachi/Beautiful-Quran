package com.beautifulquran.domain

/** Typographic punctuation policy for the punctuation-free English gloss. */
object EnglishTypography {
    private val terminalPunctuation = Regex("[.!?…][\\\"'’”)]*$")

    /** Closes the ayah without guessing sentence boundaries from capitalization. */
    fun punctuate(glosses: List<String>): List<String> {
        val lastGloss = glosses.indexOfLast(String::isNotEmpty)
        return glosses.mapIndexed { index, gloss ->
            if (index == lastGloss && !terminalPunctuation.containsMatchIn(gloss)) {
                "$gloss."
            } else {
                gloss
            }
        }
    }

    /**
     * Turns word-card glosses into continuous English prose. Quran.com's data
     * repeats one shared phrase on each Arabic word it spans; keep that phrase
     * once, while preserving genuine repetitions of the same Arabic word.
     */
    fun lyricize(glosses: List<String>, arabicWords: List<String>): List<String> {
        require(glosses.size == arabicWords.size) { "glosses and Arabic words must align" }
        val prose = glosses.mapIndexed { index, gloss ->
            if (
                index > 0 &&
                gloss == glosses[index - 1] &&
                normalizeArabicForSearch(arabicWords[index]) !=
                normalizeArabicForSearch(arabicWords[index - 1])
            ) {
                ""
            } else {
                gloss
            }
        }
        return punctuate(prose)
    }
}
