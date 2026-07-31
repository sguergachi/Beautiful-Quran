package com.beautifulquran.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentsParserTest {

    @Test
    fun `parses the pipeline segment format`() {
        val parsed = QuranRepository.parseSegments("[[1,0,960],[2,970,1420],[3,1430,2670]]")
        assertEquals(3, parsed.size)
        assertEquals(1, parsed[0].position)
        assertEquals(0L, parsed[0].startMs)
        assertEquals(960L, parsed[0].endMs)
    }

    @Test
    fun `result is sorted by start time`() {
        val parsed = QuranRepository.parseSegments("[[2,970,1420],[1,0,960]]")
        assertTrue(parsed[0].startMs <= parsed[1].startMs)
        assertEquals(1, parsed[0].position)
    }

    @Test
    fun `ignores malformed short entries`() {
        val parsed = QuranRepository.parseSegments("[[1,0,960],[2]]")
        assertEquals(1, parsed.size)
    }

    @Test
    fun `empty array parses to empty list`() {
        assertEquals(0, QuranRepository.parseSegments("[]").size)
    }

    @Test
    fun `malformed json degrades to no highlighting instead of throwing`() {
        assertEquals(0, QuranRepository.parseSegments("not json").size)
        assertEquals(0, QuranRepository.parseSegments("{\"a\":1}").size)
        assertEquals(0, QuranRepository.parseSegments("[[1,0,\"end\"]]").size)
        assertEquals(0, QuranRepository.parseSegments("").size)
    }

    @Test
    fun `parses monotone V2 acoustic keyframes`() {
        val parsed = QuranRepository.parseV2Segments(
            """
            [{
              "position":1,"startMs":100,"endMs":500,
              "keyframes":[
                {"offsetMs":80,"progress":0.5},
                {"offsetMs":300,"progress":1.0}
              ]
            }]
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals(80L, parsed.single().subwordKeyframes.first().offsetMs)
        assertEquals(1f, parsed.single().subwordKeyframes.last().progress)
    }

    @Test
    fun `parses V2 acoustic plateaus and complete repeat grammar`() {
        val parsed = QuranRepository.parseV2Segments(
            """
            [
              {"position":1,"startMs":100,"endMs":300,"keyframes":[
                {"offsetMs":80,"progress":0.5},{"offsetMs":160,"progress":0.5},
                {"offsetMs":200,"progress":1.0}
              ]},
              {"position":2,"startMs":300,"endMs":500,"keyframes":[
                {"offsetMs":200,"progress":1.0}
              ]},
              {"position":1,"startMs":500,"endMs":700,"keyframes":[
                {"offsetMs":200,"progress":1.0}
              ]}
            ]
            """.trimIndent(),
            expectedWordCount = 2,
        )

        assertEquals(listOf(1, 2, 1), parsed.map { it.position })
        assertEquals(0.5f, parsed.first().subwordKeyframes[1].progress)
    }

    @Test
    fun `rejects a malformed V2 row so repository can fall back`() {
        val backwards = """
            [{"position":1,"startMs":100,"endMs":500,"keyframes":[
              {"offsetMs":300,"progress":0.5},{"offsetMs":200,"progress":1.0}
            ]}]
        """.trimIndent()
        val zeroOffset = """
            [{"position":1,"startMs":100,"endMs":500,"keyframes":[
              {"offsetMs":0,"progress":0.5},{"offsetMs":200,"progress":1.0}
            ]}]
        """.trimIndent()
        val duplicate = """
            [{"position":1,"startMs":100,"endMs":500,"keyframes":[
              {"offsetMs":200,"progress":0.5},{"offsetMs":200,"progress":1.0}
            ]}]
        """.trimIndent()
        assertTrue(QuranRepository.parseV2Segments(backwards).isEmpty())
        assertTrue(QuranRepository.parseV2Segments(zeroOffset).isEmpty())
        assertTrue(QuranRepository.parseV2Segments(duplicate).isEmpty())
        assertTrue(
            QuranRepository.parseV2Segments(
                """[{"position":1,"startMs":100,"endMs":500,"keyframes":[
                  {"offsetMs":200,"progress":1.0}
                ]}]""",
                expectedWordCount = 2,
            ).isEmpty(),
        )
    }
}
