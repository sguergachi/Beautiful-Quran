package com.beautifulquran.ui.reader

import androidx.compose.runtime.mutableStateOf
import com.beautifulquran.data.model.Word
import com.beautifulquran.domain.MushafLine
import com.beautifulquran.domain.MushafToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a Madinah line may be drawn by one node ([mushafStaticQcfLine]) on a
 * leaf with live ink: only while [MushafQcfWord] would draw nothing on it but
 * the words themselves. Anything else is ink the one node cannot draw.
 */
class MushafLineInkAtRestTest {

    private val line = MushafLine(
        number = 3,
        tokens = listOf(token(ayah = 5, position = 4, endsAyah = true), token(ayah = 6, position = 1)),
    )

    @Test
    fun `a line no clock has reached yet is at rest`() {
        assertTrue(mushafLineInkAtRest(line, emptyMap()))
    }

    @Test
    fun `a leaf at rest with no voice keeps its lines one node`() {
        assertTrue(mushafLineInkAtRest(line, mapOf((1 to 5) to pack(), (1 to 6) to pack())))
    }

    @Test
    fun `a verse waiting under the recess takes its words back`() {
        assertFalse(mushafLineInkAtRest(line, mapOf((1 to 6) to pack(recess = true))))
    }

    @Test
    fun `a verse with ink moving takes its words back`() {
        assertFalse(mushafLineInkAtRest(line, mapOf((1 to 6) to pack(motions = listOf(motion())))))
    }

    @Test
    fun `a closing mark still coming up keeps the words until it is full`() {
        assertFalse(mushafLineInkAtRest(line, mapOf((1 to 5) to pack(markAlpha = 0.6f))))
        // A mark on another line does not hold this one.
        assertTrue(mushafLineInkAtRest(line, mapOf((1 to 6) to pack(markAlpha = 0.6f))))
    }

    private fun pack(
        motions: List<InkMotion> = emptyList(),
        recess: Boolean = false,
        markAlpha: Float = 1f,
    ) = AyahInkPack(
        motions = motions,
        recessCover = mutableStateOf(0f),
        markAlpha = mutableStateOf(markAlpha),
        searchHitWash = RepeatWash(
            progress = mutableStateOf(1f),
            alpha = mutableStateOf(0f),
            feather = mutableStateOf(null),
        ),
        searchBackgroundAlpha = mutableStateOf(1f),
        wholeAyahRecess = recess,
    )

    private fun motion() = InkMotion(
        ink = InkEngine.Word(state = InkEngine.State.Active, repeat = false),
        lyricInk = mutableStateOf(1f),
        sweep = LetterSweep(
            progress = mutableStateOf(0.5f),
            feather = mutableStateOf(null),
            pacing = mutableStateOf(null),
        ),
        repeatWash = RepeatWash(
            progress = mutableStateOf(0f),
            alpha = mutableStateOf(0f),
            feather = mutableStateOf(null),
        ),
        glintAlpha = mutableStateOf(0f),
        glintIsRepeat = false,
        glintReplacedByRepeat = false,
        waslPrefix = null,
        tarji = mutableStateOf(InkEngine.GlintResonance.Idle),
    )
}

private fun token(ayah: Int, position: Int, endsAyah: Boolean = false) = MushafToken(
    surahId = 1,
    ayah = ayah,
    word = Word(
        position = position,
        arabic = "و",
        translation = "",
        transliteration = "",
        qcfPage = 1,
        qcfLine = 3,
    ),
    endsAyah = endsAyah,
)
