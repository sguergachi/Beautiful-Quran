package com.beautifulquran.domain

/** Removes parenthetical and square-bracketed asides while preserving source-word entries. */
fun hideParentheticalText(parts: List<String>): List<String> {
    var parenthesisDepth = 0
    var squareBracketDepth = 0
    return parts.map { part ->
        buildString {
            part.forEach { character ->
                when (character) {
                    '(' -> parenthesisDepth++
                    ')' -> if (parenthesisDepth > 0) {
                        parenthesisDepth--
                    } else if (squareBracketDepth == 0) {
                        append(character)
                    }
                    '[' -> squareBracketDepth++
                    ']' -> if (squareBracketDepth > 0) {
                        squareBracketDepth--
                    } else if (parenthesisDepth == 0) {
                        append(character)
                    }
                    else -> if (parenthesisDepth == 0 && squareBracketDepth == 0) append(character)
                }
            }
        }.trim()
    }
}
