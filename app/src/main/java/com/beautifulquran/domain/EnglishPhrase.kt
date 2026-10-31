package com.beautifulquran.domain

/** A continuous presentation range; semantic tap and repeat owners remain separate. */
data class EnglishPhrase(
    val from: Float,
    val to: Float,
    val position: Int,
    val lastPosition: Int = position,
)

/** Matching evidence before adjacent words with the same semantic owner are coalesced. */
internal data class EnglishPhraseWord(
    val from: Float,
    val to: Float,
    val position: Int,
    val clause: Int,
    val anchored: Boolean,
)

/** Smallest adjacent source intervals that contain each inversion, fenced by clauses. */
internal fun englishPhraseSpans(words: List<EnglishPhraseWord>): List<EnglishPhrase> {
    data class Group(val first: Int, val last: Int, val low: Int, val high: Int, val reordered: Boolean)
    val stack = ArrayList<Group>()
    for (clause in words.indices.groupBy { words[it].clause }.values) {
        val anchors = clause.filter { words[it].anchored }
        anchors.forEachIndexed { index, at ->
            var group = Group(
                if (index == 0) clause.first() else anchors[index - 1] + 1,
                if (index == anchors.lastIndex) clause.last() else at,
                words[at].position, words[at].position, false,
            )
            while (stack.isNotEmpty() && stack.last().high >= group.low) {
                val previous = stack.removeAt(stack.lastIndex)
                group = Group(previous.first, group.last, minOf(previous.low, group.low),
                    maxOf(previous.high, group.high), previous.reordered || group.reordered || previous.high > group.low)
            }
            stack += group
        }
    }
    for (index in stack.indices) {
        val group = stack[index]
        if (!group.reordered) continue
        var first = (group.first..group.last).first { words[it].anchored }
        while (first > group.first && words[first - 1].position in group.low..group.high &&
            words[first - 1].clause == words[first].clause) first--
        stack[index] = group.copy(first = first)
    }
    // A weak remainder whose owner is already inside a closed inversion is
    // part of that phrase, not a new clock overlapping the phrase's tail.
    for (index in 0 until stack.lastIndex) {
        var group = stack[index]
        var next = stack[index + 1]
        if (!group.reordered) continue
        // Trimming a following inversion can leave weak words before its
        // first anchor; they still need attachment to this closed phrase.
        var at = group.last + 1
        while (at <= next.last && !words[at].anchored &&
            words[at].clause == words[group.last].clause && words[at].position in group.low..group.high) {
            group = group.copy(last = at)
            next = next.copy(first = maxOf(next.first, at + 1))
            at++
        }
        stack[index] = group
        stack[index + 1] = next
    }
    val reordered = stack.filter { it.reordered }
    val positions = words.map { it.position }.toIntArray()
    reordered.forEach { group -> for (at in group.first..group.last) positions[at] = group.low }
    val out = ArrayList<EnglishPhrase>()
    var owner = Int.MAX_VALUE
    for (at in words.indices.reversed()) {
        owner = minOf(owner, positions[at])
        val previous = out.lastOrNull()
        if (previous?.position == owner) out[out.lastIndex] = previous.copy(from = words[at].from)
        else out += EnglishPhrase(words[at].from, words[at].to, owner)
    }
    return out.asReversed().map { span ->
        span.copy(lastPosition = reordered.filter {
            words[it.first].from < span.to && words[it.last].to > span.from
        }.maxOfOrNull { it.high } ?: span.position)
    }
}
