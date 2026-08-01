package com.beautifulquran.domain

/** Typographic punctuation policy for the punctuation-free English gloss. */
object EnglishTypography {
    private val terminalPunctuation = Regex("[.!?…][\\\"'’”)]*$")

    /** Closes the ayah without guessing sentence boundaries from capitalization. */
    fun punctuate(glosses: List<String>): List<String> {
        val lastVisible = glosses.indexOfLast { it.isNotBlank() }
        return glosses.mapIndexed { index, gloss ->
            if (
                index == lastVisible &&
                !terminalPunctuation.containsMatchIn(gloss)
            ) {
                "$gloss."
            } else {
                gloss
            }
        }
    }
}
