package com.beautifulquran.domain

import com.beautifulquran.data.model.EnglishAlignmentRecord
import java.security.MessageDigest

/** Approved prose mappings; review evidence stays in the offline ledger. */
object ReviewedEnglishAlignment {
    const val FORMAT = "english-alignments-v1"
    private val word = Regex("\\p{L}[\\p{L}\\p{M}]*(?:['’][\\p{L}\\p{M}]+)*")

    /** Printed word bounds in Android's UTF-16 coordinates, without phrase merging. */
    fun tokens(text: String): List<IntRange> = word.findAll(text).map { it.range }.toList()

    /** Exact text and ordered surfaces detect even equal-count segmentation changes. */
    fun fingerprint(text: String, arabic: List<String>): String {
        require('\u0000' !in text && arabic.none { '\u0000' in it })
        val bytes = (listOf(FORMAT, text) + arabic).joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Invalid or stale rows keep the existing mapper as an explicit fallback. */
    fun alignment(text: String, arabic: List<String>, record: EnglishAlignmentRecord): EnglishAlignment? {
        val tokens = tokens(text)
        if (tokens.isEmpty() || '\u0000' in text || arabic.any { '\u0000' in it } || record.fingerprint != fingerprint(text, arabic) ||
            record.inkAnchors.size != tokens.size || record.heads.size != tokens.size ||
            (record.inkAnchors + record.heads).any { it !in 1..arabic.size }) return null
        fun spans(owners: List<Int>): List<EnglishWordSpan> = buildList {
            tokens.forEachIndexed { index, token ->
                val span = EnglishWordSpan(
                    if (index == 0) 0f else (tokens[index - 1].last + 1f) / text.length,
                    if (index == tokens.lastIndex) 1f else (token.last + 1f) / text.length,
                    owners[index],
                )
                val previous = lastOrNull()
                if (previous?.position == span.position) this[lastIndex] = previous.copy(to = span.to)
                else add(span)
            }
        }
        val ink = spans(record.inkAnchors)
        val ends = FloatArray(arabic.size) { index ->
            ink.filter { it.position <= index + 1 }.maxOfOrNull { it.to } ?: 0f
        }
        return EnglishAlignment(ends, spans(record.heads), continuousEnglishWash(ink), usesApprovedRecord = true)
    }

    /** Parse the compiled, text-free artifact once on the repository's IO path. */
    fun read(lines: Sequence<String>): Map<String, EnglishAlignmentRecord> {
        val iterator = lines.iterator()
        require(iterator.hasNext() && iterator.next() == "# $FORMAT") { "Unsupported English alignment format" }
        return buildMap {
            iterator.forEach { line ->
                if (line.isBlank() || line.startsWith('#')) return@forEach
                val columns = line.split('\t')
                require(columns.size == 4 && columns[0].matches(Regex("[1-9][0-9]*:[1-9][0-9]*")))
                require(columns[1].matches(Regex("[0-9a-f]{64}")))
                val ink = columns[2].split(',').map(String::toInt)
                val heads = if (columns[3] == "=") ink else columns[3].split(',').map(String::toInt)
                require(ink.isNotEmpty() && ink.size == heads.size && (ink + heads).all { it > 0 })
                require(put(columns[0], EnglishAlignmentRecord(columns[1], ink, heads)) == null) { "Duplicate alignment verse" }
            }
        }
    }
}
