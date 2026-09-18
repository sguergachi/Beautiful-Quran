package com.beautifulquran.ui.settings

import com.beautifulquran.ui.theme.NuqtaCurve
import com.beautifulquran.ui.theme.ShippedNuqtaParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuqtaKnobsTest {

    private val knobs = NuqtaKnobGroups.flatMap { it.knobs }

    @Test
    fun `every knob key is unique`() {
        assertEquals(knobs.size, knobs.map { it.key }.toSet().size)
    }

    @Test
    fun `shipped values sit inside every slider's range`() {
        knobs.forEach { knob ->
            val v = knob.get(ShippedNuqtaParams)
            assertTrue("${knob.key}=$v outside ${knob.range}", v in knob.range)
        }
    }

    @Test
    fun `copy then paste restores every knob`() {
        val tuned = ShippedNuqtaParams.copy(
            sizeDp = 24f,
            bow = 1.4f,
            spreadMs = 900,
            spreadSharpness = 4.5f,
            lift = NuqtaCurve(0.2f, 0.1f, 0.9f, 1f),
            originDx = -1.5f,
            fingers = 0.6f,
            seed = 12,
            wetAlpha = 0.2f,
            fringeAlpha = 0.4f,
        )
        val pasted = parseNuqtaFromText(formatNuqtaCopy(tuned), ShippedNuqtaParams)
        knobs.forEach { knob ->
            assertEquals(knob.key, knob.get(tuned), knob.get(pasted!!), 0.006f)
        }
    }

    @Test
    fun `paste without nuqta knobs is refused`() {
        assertNull(parseNuqtaFromText("{ p0x: 0.2, alpha = 0.9 }", ShippedNuqtaParams))
    }
}
