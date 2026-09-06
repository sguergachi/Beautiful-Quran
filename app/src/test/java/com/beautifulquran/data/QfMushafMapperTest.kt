package com.beautifulquran.data

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QfMushafMapperTest {
    @Test
    fun `provider fused word spans both canonical positions without shifting later words`() {
        val mapped = map(
            verseKey = "2:181",
            canonicalCount = 14,
            qfCount = 13,
        )

        val third = payload(mapped[2])
        val fourth = payload(mapped[3])
        val fifth = payload(mapped[4])
        assertEquals(4, third.qcfSpanEnd)
        assertEquals("t3", third.translation)
        assertEquals("", fourth.qcfV2)
        assertEquals("t4", fifth.translation)
        assertTrue(payload(mapped.last()).qcfV2.contains(codePoint(14)))
    }

    @Test
    fun `canonical fused word joins two QF glyphs and can prefer live supplement`() {
        val mapped = map(
            verseKey = "15:7",
            canonicalCount = 7,
            qfCount = 8,
            duplicateFirstTransliteration = true,
        )

        val first = payload(mapped.first())
        assertEquals("${codePoint(1)} ${codePoint(2)}", first.qcfV2)
        assertEquals("t1 t2", first.translation)
        assertEquals("live tr2", first.transliteration)
        assertEquals("t3", payload(mapped[1]).translation)
    }

    private fun map(
        verseKey: String,
        canonicalCount: Int,
        qfCount: Int,
        duplicateFirstTransliteration: Boolean = false,
    ): List<QfCacheRow> {
        val (surah, ayah) = verseKey.split(':').map(String::toInt)
        val mushaf = mutableListOf(
            row(QF_MUSHAF_RESOURCE, "mushaf", "1", """{"id":1,"record_type":"mushaf","pages_count":1,"lines_per_page":15}"""),
            row(QF_MUSHAF_RESOURCE, "mushaf_page", "2", """{"id":2,"record_type":"mushaf_page","page_number":1}"""),
        )
        repeat(qfCount) { index ->
            val position = index + 1
            mushaf += row(
                QF_MUSHAF_RESOURCE,
                "mushaf_word",
                position.toString(),
                """{"id":$position,"record_type":"mushaf_word","verse_id":1,"word_id":${100 + position},"text":"${codePoint(position)}","char_type_name":"word","page_number":1,"line_number":1,"position_in_verse":$position}""",
            )
        }
        mushaf += row(
            QF_MUSHAF_RESOURCE,
            "mushaf_word",
            "end",
            """{"id":999,"record_type":"mushaf_word","verse_id":1,"word_id":999,"text":"${codePoint(qfCount + 1)}","char_type_name":"end","page_number":1,"line_number":1,"position_in_verse":${qfCount + 1}}""",
        )
        val translations = (1..qfCount).map { position ->
            wordText(WORD_TRANSLATION_RESOURCE, "word_translation", position, "t$position")
        }
        val transliterations = (1..qfCount).flatMap { position ->
            val values = if (position == 1 && duplicateFirstTransliteration) listOf("stale", "wrong") else listOf("tr$position")
            values.mapIndexed { index, text ->
                wordText(WORD_TRANSLITERATION_RESOURCE, "word_transliteration", position, text, index)
            }
        }
        val supplements = if (duplicateFirstTransliteration) {
            listOf(wordText(WORD_SUPPLEMENT_RESOURCE, "word_transliteration", 1, "live"))
        } else {
            emptyList()
        }
        return mapQfMushaf(
            mapOf(surah to mapOf(ayah to List(canonicalCount) { "word" })),
            mushaf,
            translations,
            transliterations,
            supplements,
            1..1,
        )
    }

    private fun wordText(
        resource: QfResource,
        type: String,
        position: Int,
        text: String,
        suffix: Int = 0,
    ) = row(
        resource,
        type,
        "$position-$suffix",
        """{"id":${position * 10 + suffix},"word_id":${100 + position},"text":"$text"}""",
    )

    private fun row(resource: QfResource, type: String, key: String, payload: String) =
        QfCacheRow(resource, type, key, payload, "")

    private fun payload(row: QfCacheRow) =
        parseMushafWord(kotlinx.serialization.json.Json.parseToJsonElement(row.payload).jsonObject)

    private fun codePoint(position: Int) = String(Character.toChars(0xFC40 + position))
}
