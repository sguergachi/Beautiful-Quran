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
                    // Never below zero: a verse that opens by closing the last
                    // one's quotation must still close its own (3:73).
                    c == '"' -> '”'.also { if (doubles > 0) doubles-- }
                    c == '\'' && prev?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true -> '’'
                    c == '\'' && isAynApostrophe(raw, i) -> '’'
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
     * Whether the `'` at [i] opening a word is the transliterated letter ʿayn
     * rather than an opening quote: al-'Azeez, 'Imran, 'Aad, 'umrah, and the
     * source's "Allah 's". Read as quotes, each opened a quotation nothing
     * closed, and the verse ended on a stray ’ (3:33 "…over the worlds’").
     *
     * A quote opens a quotation that closes later in the verse, or is
     * introduced by speech — after a comma, colon or another quote mark — and
     * runs to the verse end, where the source dropped its closer. An ʿayn sits
     * after a hyphen, starts a lowercase syllable, or follows an ordinary word.
     * Two names are introduced by commas ("Unquestionably, 'Aad"; "Ya, 'Ayn,
     * Sad") and are known by name.
     */
    private fun isAynApostrophe(raw: String, i: Int): Boolean {
        val prev = raw.getOrNull(i - 1)
        val next = raw.getOrNull(i + 1) ?: return false
        if (!next.isLetter()) return false
        if (prev == '-') return true
        var end = i + 1
        while (end < raw.length && raw[end].isLetter()) end++
        if (raw.substring(i + 1, end) in AYN_NAMES) return true
        if (closesLater(raw, end)) return false          // 'partners' is a quotation
        if (next.isLowerCase()) return true
        var k = i - 1
        while (k >= 0 && raw[k] == ' ') k--
        return k >= 0 && raw[k] !in SPEECH_CONTEXT
    }

    /** A later `'` in [raw] that closes a quotation: after a word or stop, before a non-letter. */
    private fun closesLater(raw: String, from: Int): Boolean {
        for (j in from until raw.length) {
            if (raw[j] != '\'') continue
            val p = raw[j - 1]
            val n = raw.getOrNull(j + 1)
            if ((p.isLetterOrDigit() || p in ".,!?;:") && (n == null || !n.isLetterOrDigit())) return true
        }
        return false
    }

    private val AYN_NAMES = setOf("Aad", "Ayn")
    private const val SPEECH_CONTEXT = ",:\"“([{"

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
