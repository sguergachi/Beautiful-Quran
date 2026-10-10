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

    /**
     * Turns QF word-card glosses into continuous English prose. The source data
     * repeats one shared phrase on each Arabic word it spans; keep that phrase
     * once, while preserving genuine repetitions of the same Arabic word.
     */
    fun lyricize(
        glosses: List<String>,
        arabicWords: List<String>,
    ): List<String> {
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

    /** Visible owner of a shared gloss when [requestedIndex] was coalesced. */
    fun coalescedGlossOwnerIndex(
        glosses: List<String>,
        arabicWords: List<String>,
        requestedIndex: Int,
    ): Int? {
        require(glosses.size == arabicWords.size) { "glosses and Arabic words must align" }
        if (requestedIndex !in glosses.indices) return null
        var owner = requestedIndex
        while (
            owner > 0 &&
            glosses[owner] == glosses[owner - 1] &&
            normalizeArabicForSearch(arabicWords[owner]) !=
            normalizeArabicForSearch(arabicWords[owner - 1])
        ) {
            owner--
        }
        return owner
    }

    /**
     * The translation as a typographer would set it: curled quotes and
     * apostrophes, and a spaced en dash where the source typed " - ".
     *
     * Display-only, and one character for one, so every range measured on the
     * stored text (search hits, karaoke word spans) lands on the same letters
     * here. The one addition is at the very end: the source dropped each
     * verse's final punctuation, closing quotes included, so 1,127 verses
     * open a quotation they never close. Those closers are restored after the
     * last character, which moves no earlier index. A quotation that runs on
     * into the next verse ([quoteContinues], see [quoteContinuesInto]) is left
     * open, as a quotation spanning paragraphs is.
     */
    fun typeset(raw: String, quoteContinues: Boolean = false): String {
        val out = StringBuilder(raw.length + 2)
        var doubles = 0
        var singles = 0
        raw.forEachIndexed { i, c ->
            val prev = raw.getOrNull(i - 1)
            val next = raw.getOrNull(i + 1)
            val opensHere = prev == null || prev.isWhitespace() || prev in OPENING_CONTEXT
            out.append(
                when {
                    c == '"' && opensHere -> '“'.also { doubles++ }
                    c == '"' -> '”'.also { doubles-- }
                    c == '\'' && prev?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true -> '’'
                    c == '\'' && opensHere -> '‘'.also { singles++ }
                    c == '\'' -> '’'.also { if (singles > 0) singles-- }
                    c == '-' && prev == ' ' && next == ' ' -> '–'
                    else -> c
                },
            )
        }
        if (!quoteContinues) {
            repeat(singles) { out.append('’') }
            repeat(doubles.coerceAtLeast(0)) { out.append('”') }
        }
        return out.toString()
    }

    /**
     * Whether a quotation open at the end of a verse carries on into [next]:
     * the next verse's first double quote closes rather than opens.
     */
    fun quoteContinuesInto(next: String?): Boolean {
        next ?: return false
        val at = next.indexOf('"')
        if (at < 0) return false
        val prev = next.getOrNull(at - 1)
        return !(prev == null || prev.isWhitespace() || prev in OPENING_CONTEXT)
    }

    /** A surah name or gloss: apostrophes curled, nothing else touched. */
    fun typesetName(raw: String): String = raw.replace('\'', '’')

    /**
     * [typeset] undone, character for character, so text matching a reader's
     * typed query (straight quotes, a hyphen) still finds the typeset text,
     * at the same indices.
     */
    fun fold(text: String): String {
        if (text.none { it in TYPESET_MARKS }) return text
        return buildString(text.length) {
            text.forEach { c ->
                append(
                    when (c) {
                        '“', '”' -> '"'
                        '‘', '’' -> '\''
                        '–' -> '-'
                        else -> c
                    },
                )
            }
        }
    }

    private const val OPENING_CONTEXT = "([{–—-“‘"
    private const val TYPESET_MARKS = "“”‘’–"
}
