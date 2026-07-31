package com.beautifulquran.data

import com.beautifulquran.data.model.DictionarySenseGroup

/**
 * Parses the compact JSON payload written by `tools/build_dictionary_db.py`:
 * `[{"pos":"verb","glosses":["to say",…]},…]`.
 *
 * Hand-scanned rather than regex: Android's ICU engine rejects the escaped
 * character-class form that JVM `java.util.regex` accepts, which silently
 * dropped every dictionary hit in the Root Viewer.
 */
internal fun parseDictionaryPayload(json: String): List<DictionarySenseGroup> {
    if (json.isBlank()) return emptyList()
    val groups = ArrayList<DictionarySenseGroup>()
    var i = 0
    while (i < json.length) {
        val posKey = json.indexOf("\"pos\"", i)
        if (posKey < 0) break
        val posColon = json.indexOf(':', posKey + 5)
        if (posColon < 0) break
        val posStart = json.indexOf('"', posColon + 1)
        if (posStart < 0) break
        val pos = readJsonString(json, posStart) ?: break
        i = pos.end

        val glossKey = json.indexOf("\"glosses\"", i)
        if (glossKey < 0) break
        val arrayOpen = json.indexOf('[', glossKey + 9)
        if (arrayOpen < 0) break
        val glosses = ArrayList<String>()
        var cursor = arrayOpen + 1
        while (cursor < json.length) {
            when (json[cursor]) {
                ']' -> {
                    cursor++
                    break
                }
                '"' -> {
                    val gloss = readJsonString(json, cursor) ?: return groups
                    val text = gloss.value.trim()
                    if (text.isNotEmpty()) glosses += text
                    cursor = gloss.end
                }
                else -> cursor++
            }
        }
        i = cursor
        if (glosses.isNotEmpty()) {
            groups += DictionarySenseGroup(pos = pos.value, glosses = glosses)
        }
    }
    return groups
}

private data class JsonString(val value: String, val end: Int)

/** [start] points at the opening quote; [end] is past the closing quote. */
private fun readJsonString(json: String, start: Int): JsonString? {
    if (start >= json.length || json[start] != '"') return null
    var i = start + 1
    val out = StringBuilder()
    while (i < json.length) {
        when (val c = json[i]) {
            '"' -> return JsonString(out.toString(), i + 1)
            '\\' -> {
                if (i + 1 >= json.length) return null
                when (val next = json[i + 1]) {
                    '"', '\\', '/' -> out.append(next)
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> {
                        if (i + 5 >= json.length) return null
                        val hex = json.substring(i + 2, i + 6)
                        out.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(next)
                }
                i += 2
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return null
}
