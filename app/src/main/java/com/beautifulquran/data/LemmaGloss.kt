package com.beautifulquran.data

/** One word-by-word English rendering of a form, and how often it is used. */
data class GlossVote(val translation: String, val count: Int)

/**
 * Chooses the short English gloss shown beside a related form in the root
 * viewer.
 *
 * The corpus has no dictionary definitions, so the gloss is voted for by the
 * word-by-word translations of every Quran word carrying that lemma. Those
 * renderings are inflected and context-bound ("the Book", "(of) the Book",
 * "and mercy", "you know"), so each is first reduced to a comparison key —
 * bracketed asides dropped, leading articles/pronouns/auxiliaries and trailing
 * particles/objects trimmed — and the votes of every rendering sharing a key
 * are pooled. The winning key is displayed using its most common rendering.
 *
 * Trimming never empties a rendering: a bare "is" or "them" keeps its own
 * word, so copula-heavy lemmas (كان) still gloss as "is" rather than losing
 * every frequent form to the empty string.
 *
 * The mirror of `web/src/data/lemmaGloss.ts` — keep the two in step.
 */
object LemmaGloss {

    /** Function words that carry no lemma meaning when they open a rendering. */
    private val LEADING = setOf(
        "and", "then", "so", "but", "or", "the", "a", "an", "of", "to", "for",
        "in", "is", "are", "was", "were", "with", "from", "on", "at", "by", "o",
        "i", "he", "she", "it", "we", "they", "you", "him", "her", "them", "us",
        "me", "my", "your", "his", "its", "our", "their", "that", "this",
        "these", "those", "will", "has", "have", "had", "did", "do", "does",
        "surely", "indeed", "verily", "not", "who", "whom", "which", "all",
        "any", "some", "most",
    )

    /** Attached objects and dangling particles that end a rendering. */
    private val TRAILING = setOf(
        "them", "him", "her", "us", "me", "you", "it", "to", "for", "of",
        "with", "on", "upon", "from", "by", "at", "in", "and", "their", "your",
        "his", "its", "my", "our",
    )

    private val ASIDES = Regex("""\([^)]*\)|\[[^\]]*]""")
    private val WHITESPACE = Regex("""\s+""")
    private const val EDGE_PUNCTUATION = " \t.,;:!?\"'"

    /**
     * The most representative rendering of one lemma, or "" when nothing
     * survives normalisation.
     */
    fun pick(votes: List<GlossVote>): String {
        val buckets = LinkedHashMap<String, Bucket>()
        for ((translation, count) in votes) {
            val words = normalize(translation)
            if (words.isEmpty()) continue
            val display = words.joinToString(" ")
            val bucket = buckets.getOrPut(words.joinToString(" ") { it.lowercase() }) { Bucket() }
            bucket.votes += count
            if (bucket.display.isEmpty() || betterDisplay(count, display, bucket)) {
                bucket.topVotes = count
                bucket.display = display
            }
        }
        return buckets.entries
            .minWithOrNull(
                compareByDescending<Map.Entry<String, Bucket>> { it.value.votes }
                    .thenByDescending { it.value.topVotes }
                    .thenBy { it.key },
            )
            ?.value
            ?.display
            .orEmpty()
    }

    /** Drops asides and edge punctuation, then trims framing words. */
    private fun normalize(translation: String): List<String> {
        val plain = translation
            .replace(ASIDES, " ")
            .replace(WHITESPACE, " ")
            .trim(*EDGE_PUNCTUATION.toCharArray())
        if (plain.isEmpty()) return emptyList()
        val words = ArrayDeque(plain.split(" ").filter { it.isNotEmpty() })
        while (words.size > 1 && words.first().lowercase() in LEADING) words.removeFirst()
        while (words.size > 1 && words.last().lowercase() in TRAILING) words.removeLast()
        return words
    }

    /** Prefer the most used rendering, then the plainest, then alphabetical. */
    private fun betterDisplay(count: Int, display: String, bucket: Bucket): Boolean = when {
        count != bucket.topVotes -> count > bucket.topVotes
        display.length != bucket.display.length -> display.length < bucket.display.length
        else -> display < bucket.display
    }

    private class Bucket {
        var votes = 0
        var topVotes = 0
        var display = ""
    }
}
