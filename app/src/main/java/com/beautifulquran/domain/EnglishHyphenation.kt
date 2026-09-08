package com.beautifulquran.domain

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The English leaf's hyphenation: the system's breaks, the book's minima.
 *
 * A ragged page needs hyphenation — a long word the rag cannot absorb pushes
 * its neighbours into a deep hole — but the breaker's own hyphenation cannot
 * be told that *de-scends* is not a break, and Compose exposes no frequency
 * or fragment control to stop it with. Self-set soft hyphens do not help
 * either: with hyphenation off the breaker ignores them entirely, and with it
 * on they are subsumed.
 *
 * So the leaf vetoes instead of proposing. Every cut the TeX US-English
 * patterns ([HYPHEN_PATTERNS]) propose for a word is kept only where each
 * fragment it leaves behind stands [HYPHEN_MIN_FRAGMENT] letters tall; the
 * rest are joined with a word joiner (U+2060), which forbids the break
 * without showing anything. The breaker, running `Hyphens.Auto`, takes what
 * is left: *right-eous-ness*, *trans-gress* and *pro-tection* carry over,
 * while *de-scends*, *Re-pelled* and *obe-di-ence*'s middle *di* stay whole.
 *
 * What the leaf deliberately does *not* do is glue short words to their
 * neighbours with no-break spaces. It was tried twice, and twice the hole a
 * keep leaves behind reads worse than the short end it removed: alone, a keep
 * removes a break the paragraph optimizer was using (*…and We made* / *an
 * appointment…*, a quarter of the measure empty); with hyphenation behind it,
 * the same hole stands unfilled, because even the hyphenated head of the
 * glued word (*an appoint-*) will not fit after an already-full line.
 *
 * Applied in [englishVerseProse] — the one choke point the ruler and the
 * drawer share, so the pagination measures exactly what the leaf draws. The
 * joiner is letters to nobody downstream: zero advance, ignored by shaping,
 * and the wash alignment tokenizes on letters only.
 *
 * Pure Kotlin over immutable data, unit-tested on the JVM. The pattern trie
 * is built once per process; per-word answers are remembered, so pagination
 * pays for each distinct word a single time.
 */
object EnglishHyphenation {
    /** A hyphen break needs this many letters standing on each side of it. */
    const val HYPHEN_MIN_FRAGMENT = 3

    /** Only words this long are ever vetoed; shorter ones break nowhere bad. */
    const val HYPHEN_MIN_WORD = 6

    /** Joins a vetoed cut: forbids the break, shows nothing, advances nothing. */
    const val WORD_JOINER = '\u2060'

    private val trie: HyphenTrie by lazy { HyphenTrie(HYPHEN_PATTERNS) }
    private val remembered = ConcurrentHashMap<String, List<Int>>(4096)

    /**
     * Where [word] may break, as offsets from its start — the odd weights of
     * the overlaid patterns, edge-anchored. Empty for anything the table does
     * not know, which the breaker then leaves whole.
     */
    fun breaks(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()
        // The table is lowercase ASCII; fold here so callers keep the word's
        // own hand for the text they set. A fold that changes length (never
        // in this translation, but never assume it) breaks nothing: offsets
        // would no longer line up, so there are no breaks.
        val key = word.lowercase(Locale.ROOT)
        if (key.length != word.length) return emptyList()
        return remembered.getOrPut(key) { trie.breaks(".$key.") }
    }

    /**
     * Which of the table's cuts the book keeps for a word of [length]: every
     * fragment they leave behind — first, middle and last — stands
     * [HYPHEN_MIN_FRAGMENT] letters tall. *de-scends* fails at the first cut,
     * *obe-di-ence* at the middle one (`di`), *per-pet-u-al* at the last
     * (`al`).
     */
    fun vetted(cuts: List<Int>, length: Int): List<Int> {
        val kept = ArrayList<Int>(cuts.size)
        cuts.sorted().forEach { cut ->
            val prev = kept.lastOrNull() ?: 0
            if (cut >= HYPHEN_MIN_FRAGMENT && cut - prev >= HYPHEN_MIN_FRAGMENT) {
                kept += cut
            }
        }
        if (kept.isNotEmpty() && length - kept.last() < HYPHEN_MIN_FRAGMENT) {
            kept.removeAt(kept.lastIndex)
        }
        return kept
    }

    /**
     * The whole setting: vetoed hyphenation. Idempotent — a text already set
     * passes through unchanged, so composing a leaf twice (the ruler draws
     * every leaf it measures) never doubles a veto.
     */
    fun setProse(text: String): String {
        if (text.isEmpty()) return text
        return vetoedText(text)
    }

    private fun vetoedText(text: String): String {
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isLetter() && (i == 0 || (!text[i - 1].isLetter() && text[i - 1] != WORD_JOINER))) {
                var j = i
                while (j < text.length && (text[j].isLetter() || text[j] == '\'')) j++
                out.append(vetoWord(text.substring(i, j)))
                i = j
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun vetoWord(word: String): String {
        if (word.length < HYPHEN_MIN_WORD || word.any { !it.isLetter() }) return word
        val cuts = breaks(word)
        if (cuts.isEmpty()) return word
        val bad = cuts.toSet() - vetted(cuts, word.length).toSet()
        if (bad.isEmpty()) return word
        val out = StringBuilder(word.length + bad.size)
        for (index in word.indices) {
            if (index in bad) out.append(WORD_JOINER)
            out.append(word[index])
        }
        return out.toString()
    }
}

/**
 * Liang's patterns as a trie, so a word pays for its own length rather than
 * for the eleven thousand patterns.
 *
 * Each node holds the weights a pattern ending here puts in the gaps *after*
 * its letters: matching walks every suffix of the word through the trie and
 * keeps the maximum weight at each gap, exactly as overlaying every pattern
 * would. A gap breaks the word when its maximum is odd.
 */
internal class HyphenTrie(patterns: Array<String>) {
    private class Node {
        val next = HashMap<Char, Node>()
        var weights: IntArray? = null
    }

    private val root = Node()

    init {
        patterns.forEach { insert(it) }
    }

    private fun insert(pattern: String) {
        // Split into letters and the weight standing in each gap: `ab1d`
        // carries weight 1 between b and d, and 0 everywhere else.
        val letters = StringBuilder()
        val gaps = ArrayList<Int>()
        gaps += 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c.isDigit()) {
                gaps[gaps.lastIndex] = maxOf(gaps[gaps.lastIndex], c.digitToInt())
            } else {
                letters.append(c)
                gaps += 0
            }
            i++
        }
        var node = root
        letters.forEach { node = node.next.getOrPut(it) { Node() } }
        val old = node.weights
        node.weights = if (old == null) {
            gaps.toIntArray()
        } else {
            IntArray(maxOf(old.size, gaps.size)) { k ->
                maxOf(old.getOrElse(k) { 0 }, gaps.getOrElse(k) { 0 })
            }
        }
    }

    /** Break offsets for a `.`-anchored word, before fragment filtering. */
    fun breaks(word: String): List<Int> {
        val values = IntArray(word.length + 1)
        for (start in word.indices) {
            var node = root
            var k = start
            while (k < word.length) {
                node = node.next[word[k]] ?: break
                node.weights?.forEachIndexed { gap, weight ->
                    val at = start + gap
                    if (at <= word.length && weight > values[at]) values[at] = weight
                }
                k++
            }
        }
        // Gaps 1..n-1 of the anchored word are offsets 0..n-3 of the bare one;
        // the anchor dots themselves never break.
        val out = ArrayList<Int>(4)
        for (at in 2 until word.length - 1) {
            if (values[at] % 2 == 1) out += at - 1
        }
        return out
    }
}
