package com.beautifulquran.domain

/** Removes parenthetical asides while preserving one entry for every source word. */
fun hideParentheticalText(parts: List<String>): List<String> {
    var depth = 0
    return parts.map { part ->
        buildString {
            part.forEach { character ->
                when (character) {
                    '(' -> depth++
                    ')' -> if (depth > 0) depth-- else append(character)
                    else -> if (depth == 0) append(character)
                }
            }
        }.trim()
    }
}
