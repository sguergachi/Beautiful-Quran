package com.beautifulquran.ui.rootviewer

/**
 * Lane writes English prose with Arabic set inline ("inf. n. كِتَابٌ and
 * كِتَابَةٌ"), so an entry has to be drawn in two scripts at once: the Latin
 * runs in the reading face, the Arabic runs in the mushaf face at its own
 * size. Splitting is pure and lives here so it can be tested without Compose.
 *
 * Entries also arrive as one dense paragraph per form. [lexiconReflow] and
 * [lexiconBlocks] turn Lane's own markers (Form N., sense breaks, the
 * morphology→gloss pivot) into a page the eye can scan; they do not invent
 * sense boundaries the source never marked.
 */
internal data class LexiconRun(
    val text: String,
    val isArabic: Boolean,
    /** Lane's source marks — `(S, K)`, `(Msb,)` — drawn quieter than the gloss. */
    val isCitation: Boolean = false,
)

/** One spaced unit of the article: an optional Form label, then body prose. */
internal data class LexiconBlock(val form: String?, val text: String)

/** Characters of Lane shown before the reader asks for the whole article. */
internal const val LEXICON_PREVIEW_CHARS = 1_400

/**
 * After Form N., the English gloss typically opens with one of these — either
 * after Lane's punctuation, or right after the Arabic headword.
 */
private val GLOSS_OPEN = Regex(
    """(?<=[;:.,)\]]|[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]) """ +
        """(?=\[?(?:He|It|I|She|They|A|An|To|The|One)\b)""",
)

/** Lane often sets the primary English sense in square brackets after the morph. */
private val BRACKET_GLOSS = Regex("""\[([^\[\]]{12,500})\]""")

private val FORM_HEAD = Regex("""^Form (\d+)\.\s*""")
private val FORM_SPLIT = Regex("""(?=Form \d+\.)""")
private val BLOCK_SPLIT = Regex("""\n\n+|\n(?=•)""")
private val PAREN = Regex("""\([^()\n]*\)""")
/**
 * Bare Latin "see …" cross-refs — not the ones already inside `(see …)`.
 *
 * Stops before Arabic so the target keeps the mushaf face; swallowing the
 * Arabic into a Latin citation span lets bidi wrap `see` / `ظَلَعَ.` apart.
 */
private val SEE_REF = Regex(
    """(?<!\()\b[Ss]ee\b(?:(?![\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF])[^.!\n])*""" +
        """(?:[.!])?""",
)
private val LATIN_WORD = Regex("""[A-Za-z]+""")

/**
 * The opening of an article, cut at one of Lane's own divisions.
 *
 * Articles run from a paragraph to ~99,000 characters, so the section shows
 * its head and lets the reader unfold the rest. The cut prefers the last
 * sense break inside the budget, then a sentence end, so the preview never
 * stops mid-clause; a short article is returned whole.
 */
internal fun lexiconPreview(text: String, budget: Int = LEXICON_PREVIEW_CHARS): String {
    if (text.length <= budget) return text
    val window = text.take(budget)
    val cut = listOf(
        window.lastIndexOf("\n•"),
        window.lastIndexOf("\n\n"),
        window.lastIndexOf(". "),
    ).firstOrNull { it > budget / 3 } ?: budget
    return window.take(cut).trimEnd().trimEnd(',', ';', '—', '(') + " …"
}

/**
 * Gives Lane's article a readable shape without rewriting his words.
 *
 * Each `Form N.` label gets its own line, and the first morphology→gloss
 * pivot under that form (`) He wrote`, `) It (a thing)`) becomes a paragraph
 * break so the preview is not one unbroken wall of citations.
 */
internal fun lexiconReflow(text: String): String {
    if (text.isEmpty()) return text
    return text.split(FORM_SPLIT).joinToString("") { section ->
        val head = FORM_HEAD.find(section) ?: return@joinToString section
        val label = "Form ${head.groupValues[1]}."
        var body = section.substring(head.range.last + 1)
        val gloss = GLOSS_OPEN.find(body)
        if (gloss != null && gloss.range.first < 500) {
            body = body.replaceRange(gloss.range.first, gloss.range.last + 1, "\n\n")
        }
        "$label\n$body"
    }.replace(Regex("\n{3,}"), "\n\n")
}

/** Reflowed article, optionally cut to the preview budget. */
internal fun lexiconArticleText(text: String, expanded: Boolean): String {
    val reflowed = lexiconReflow(text)
    return if (expanded) reflowed else lexiconPreview(reflowed)
}

/** How many `Form N.` measures Lane marks in the article. */
internal fun lexiconFormCount(text: String): Int =
    Regex("""Form \d+\.""").findAll(text).count()

/**
 * A short English sense for the Root section, taken from Lane's Form 1
 * (or the Form his opening "see N" / "see the latter" points at).
 *
 * Uses only the opening sense (before later `•` senses), prefers his
 * bracketed primary gloss, then the first English lead after the morphology.
 * Returns null when the article has no readable English lead.
 */
internal fun lexiconRootSense(text: String, maxChars: Int = 180): String? {
    if (text.isBlank()) return null
    val section = resolveSenseSection(lexiconReflow(text)) ?: return null
    return senseLeadFromSection(section, maxChars)
}

/**
 * Follow Lane's Form-1 cross-refs (`see 4`, `see the latter`) so roots like
 * نور — where Form 1 only redirects to أَنَارَ — still yield a Root gloss.
 */
private fun resolveSenseSection(reflowed: String): String? {
    if (!Regex("""(?:^|\n)Form \d+\.""").containsMatchIn(reflowed)) {
        return reflowed.takeIf {
            looksLikeEnglishSense(it) || BRACKET_GLOSS.containsMatchIn(it)
        }
    }
    var n = 1
    val visited = mutableSetOf<Int>()
    while (n !in visited && visited.size < 6) {
        visited += n
        val section = formSection(reflowed, n) ?: break
        when (val redirect = openingFormRedirect(section)) {
            null -> return section
            0 -> n += 1
            else -> n = redirect
        }
    }
    return formSection(reflowed, 1)
}

/** Form N body including its label. */
private fun formSection(reflowed: String, n: Int): String? {
    val head = Regex("""(?:^|\n)(Form $n\.)""").find(reflowed) ?: return null
    val start = head.groups[1]!!.range.first
    val rest = reflowed.substring(start)
    val end = Regex("""\nForm \d+\.""")
        .find(rest, startIndex = "Form $n.".length)
        ?.range?.first
        ?: rest.length
    return rest.take(end)
}

/**
 * When Form N opens as a bare cross-ref, the Form number to follow.
 * `0` means "see the latter" (try the next Form). Null = real opening sense.
 */
private fun openingFormRedirect(formSection: String): Int? {
    val open = formSection
        .replace(Regex("""^Form \d+\.\s*"""), "")
        .substringBefore("\n•")
        .substringBefore("\n\n")
        .trim()
    if (open.isEmpty() || hasSenseLead(open)) return null
    Regex("""\bsee (\d+)\b""", RegexOption.IGNORE_CASE)
        .find(open)
        ?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it in 1..15 }
        ?.let { return it }
    if (Regex("""\bsee the latter\b""", RegexOption.IGNORE_CASE).containsMatchIn(open)) {
        return 0
    }
    return null
}

private fun hasSenseLead(text: String): Boolean {
    BRACKET_GLOSS.find(text)?.groupValues?.get(1)?.trim()
        ?.takeIf { looksLikePrimaryBracketGloss(it) }
        ?.let { return true }
    return englishSenseLead(text) != null
}

private fun senseLeadFromSection(section: String, maxChars: Int): String? {
    val lead = section.substringBefore("\n•")

    BRACKET_GLOSS.find(lead)?.groupValues?.get(1)?.trim()
        ?.takeIf { looksLikePrimaryBracketGloss(it) }
        ?.let { return shortenSense(it, maxChars) }

    for (block in lexiconBlocks(lead)) {
        englishSenseLead(block.text)?.let { return shortenSense(it, maxChars) }
    }
    return null
}

private fun englishSenseLead(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    // Prefer an already-English paragraph (after reflow). Mid-string GLOSS_OPEN
    // would otherwise steal `, A,` inside Lane citations like `(S, A, Msb)`.
    val gloss = GLOSS_OPEN.find(trimmed)
    val body = when {
        trimmed.first().let { it == '[' || (!it.isArabicScript() && it.isUpperCase()) } ->
            trimmed
        gloss != null && gloss.range.first < 500 ->
            trimmed.substring(gloss.range.last + 1).trim()
        else -> return null
    }.removePrefix("[").trimStart { it == ' ' || it == ']' }
    return body.takeIf { looksLikeEnglishSense(it) || it.firstOrNull()?.isUpperCase() == true }
}

private fun looksLikeEnglishSense(text: String): Boolean {
    var latin = 0
    var arabic = 0
    for (char in text) {
        when {
            char in 'A'..'Z' || char in 'a'..'z' -> latin++
            char.isArabicScript() -> arabic++
        }
    }
    return latin >= 12 && latin > arabic
}

/** Lane's Form-1 primary gloss, not later editorial asides in brackets. */
private fun looksLikePrimaryBracketGloss(inner: String): Boolean {
    if (!looksLikeEnglishSense(inner)) return false
    val lead = inner.trimStart()
    if (lead.startsWith("This is what", ignoreCase = true)) return false
    if (lead.startsWith("i. e.", ignoreCase = true) || lead.startsWith("i.e.", ignoreCase = true)) {
        return false
    }
    return Regex("""^(?:He|It|I|She|They|A|An|To|The|One)\b""").containsMatchIn(lead)
}

private fun shortenSense(text: String, maxChars: Int): String {
    var sense = text.trim().trimStart('[').trimEnd(']', ' ')
    // `: (` / `; (` are Lane citations after a gloss; `; syn.` / `; or` and
    // sentence ends need a longer lead so we don't chop ordinary English.
    val earlyStop = listOf(
        Regex(""":\s*\(""").find(sense)?.range?.first?.takeIf { it >= 8 },
        Regex(""";\s*\(""").find(sense)?.range?.first?.takeIf { it >= 16 },
        Regex(""";\s*(?:syn\.|or\b|and ↓|see\b)""", RegexOption.IGNORE_CASE)
            .find(sense)?.range?.first?.takeIf { it >= 16 },
        Regex("""[.!?](?:\s|$)""").find(sense)?.range?.first?.takeIf { it >= 24 },
    ).filterNotNull().minOrNull()
    if (earlyStop != null) {
        sense = sense.take(earlyStop + if (sense.getOrNull(earlyStop) in setOf('.', '!', '?')) 1 else 0)
    }
    sense = sense.replace(Regex("""\s*\([^()]*\)\s*$"""), "").trimEnd(' ', ':', ';', ',')
    if (sense.length <= maxChars) return sense
    val window = sense.take(maxChars)
    val cut = window.lastIndexOf(' ').takeIf { it > maxChars / 2 } ?: maxChars
    return window.take(cut).trimEnd(' ', ',', ';', ':') + "…"
}

/**
 * Spaced units for the page: a Form heading when Lane opens a measure, then
 * the prose beneath it. Sense / paragraph breaks become quiet air between
 * blocks — no bullet marks. [text] should already be [lexiconReflow]'d (or
 * come from [lexiconArticleText]).
 */
internal fun lexiconBlocks(text: String): List<LexiconBlock> =
    BLOCK_SPLIT.split(text).mapNotNull { chunk ->
        val trimmed = chunk.trim().removePrefix("•").trimStart()
        if (trimmed.isEmpty()) return@mapNotNull null
        val head = FORM_HEAD.find(trimmed)
        if (head != null) {
            LexiconBlock(
                form = "Form ${head.groupValues[1]}.",
                text = trimmed.substring(head.range.last + 1).trim(),
            )
        } else {
            LexiconBlock(form = null, text = trimmed)
        }
    }

/** Arabic block, Arabic Supplement/Extended-A, and the presentation forms. */
private fun Char.isArabicScript(): Boolean = when (this) {
    in '؀'..'ۿ', in 'ݐ'..'ݿ',
    in 'ࢠ'..'ࣿ', in 'ﭐ'..'﷿',
    in 'ﹰ'..'﻿' -> true
    else -> false
}

/**
 * Parentheses that are Lane's source marks rather than English asides.
 *
 * `(S, K)` and `(Msb,)` cite lexicographers; `(tropical:)` is an editorial
 * mark; `(a thing)` glosses the subject and stays at body ink.
 */
internal fun isLaneCitation(inner: String): Boolean {
    if (inner.any { it.isArabicScript() }) return false
    if (inner.matches(Regex("""[a-z]+:"""))) return true
    return LATIN_WORD.findAll(inner).none { word ->
        word.value.length >= 4 && word.value.first().isLowerCase()
    }
}

/**
 * Splits [text] into alternating Latin, Arabic, and quiet runs.
 *
 * Source marks and "see …" cross-references are peeled from the whole string
 * first so they can recede; neutral characters otherwise stay in the run they
 * follow — the split only chooses a typeface; bidi is resolved over the
 * paragraph.
 */
internal fun lexiconRuns(text: String): List<LexiconRun> {
    if (text.isEmpty()) return emptyList()
    data class Quiet(val start: Int, val endInclusive: Int, val value: String)

    val quiets = mutableListOf<Quiet>()
    for (match in PAREN.findAll(text)) {
        val inner = match.value.substring(1, match.value.lastIndex)
        if (isLaneCitation(inner)) {
            quiets += Quiet(match.range.first, match.range.last, match.value)
        }
    }
    for (match in SEE_REF.findAll(text)) {
        if (quiets.any { match.range.first in it.start..it.endInclusive }) continue
        quiets += Quiet(match.range.first, match.range.last, match.value)
    }
    quiets.sortBy { it.start }

    val runs = mutableListOf<LexiconRun>()
    var i = 0
    for (quiet in quiets) {
        if (quiet.start < i) continue
        if (quiet.start > i) runs += splitScripts(text.substring(i, quiet.start))
        runs += LexiconRun(quiet.value, isArabic = false, isCitation = true)
        i = quiet.endInclusive + 1
    }
    if (i < text.length) runs += splitScripts(text.substring(i))
    return runs
}

private fun splitScripts(text: String): List<LexiconRun> {
    if (text.isEmpty()) return emptyList()
    val runs = mutableListOf<LexiconRun>()
    val current = StringBuilder()
    var arabic: Boolean? = null

    fun flush() {
        if (current.isEmpty() || arabic == null) return
        runs += LexiconRun(current.toString(), isArabic = arabic == true)
        current.clear()
    }

    for (char in text) {
        val script = when {
            char.isArabicScript() -> true
            char.isLetterOrDigit() -> false
            else -> null
        }
        if (script != null && arabic != null && script != arabic) flush()
        if (script != null) arabic = script
        current.append(char)
    }
    flush()
    return runs
}
