package com.beautifulquran.ui.settings

import com.beautifulquran.ui.theme.NuqtaCurve
import com.beautifulquran.ui.theme.NuqtaParams
import kotlin.math.roundToInt

/**
 * One tunable number of the nuqta. The same list drives the kit's sliders, its
 * copy output and its paste parser, so the three can never disagree — and the
 * keys match web `nuqta.ts`, so a snippet copied on one platform pastes on the
 * other.
 */
internal class NuqtaKnob(
    val key: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val digits: Int,
    val get: (NuqtaParams) -> Float,
    val set: (NuqtaParams, Float) -> NuqtaParams,
)

/** A titled run of knobs in the lab. */
internal class NuqtaKnobGroup(val title: String, val knobs: List<NuqtaKnob>)

private fun curveKnobs(
    prefix: String,
    get: (NuqtaParams) -> NuqtaCurve,
    set: (NuqtaParams, NuqtaCurve) -> NuqtaParams,
): List<NuqtaKnob> = listOf(
    NuqtaKnob("${prefix}X1", "Curve x1", 0f..1f, 2, { get(it).x1 }) { p, v -> set(p, get(p).copy(x1 = v)) },
    NuqtaKnob("${prefix}Y1", "Curve y1", -0.5f..1.5f, 2, { get(it).y1 }) { p, v -> set(p, get(p).copy(y1 = v)) },
    NuqtaKnob("${prefix}X2", "Curve x2", 0f..1f, 2, { get(it).x2 }) { p, v -> set(p, get(p).copy(x2 = v)) },
    NuqtaKnob("${prefix}Y2", "Curve y2", -0.5f..1.5f, 2, { get(it).y2 }) { p, v -> set(p, get(p).copy(y2 = v)) },
)

private fun knob(
    key: String,
    label: String,
    range: ClosedFloatingPointRange<Float>,
    digits: Int,
    get: (NuqtaParams) -> Float,
    set: (NuqtaParams, Float) -> NuqtaParams,
) = NuqtaKnob(key, label, range, digits, get, set)

internal val NuqtaKnobGroups: List<NuqtaKnobGroup> = listOf(
    NuqtaKnobGroup(
        "Cut",
        listOf(
            knob("sizeDp", "Size (dp)", 14f..32f, 0, { it.sizeDp }) { p, v -> p.copy(sizeDp = v) },
            knob("bow", "Side bow", 0f..2.5f, 2, { it.bow }) { p, v -> p.copy(bow = v) },
            knob("strokeDp", "Outline (dp)", 0.4f..2.5f, 2, { it.strokeDp }) { p, v -> p.copy(strokeDp = v) },
            knob("restingOutlineAlpha", "Resting ink", 0f..1f, 2, { it.restingOutlineAlpha }) { p, v ->
                p.copy(restingOutlineAlpha = v)
            },
            knob("selectedOutlineAlpha", "Chosen ink", 0f..1f, 2, { it.selectedOutlineAlpha }) { p, v ->
                p.copy(selectedOutlineAlpha = v)
            },
        ),
    ),
    NuqtaKnobGroup(
        "Clock",
        listOf(
            knob("spreadMs", "Spread ms", 120f..2000f, 0, { it.spreadMs.toFloat() }) { p, v ->
                p.copy(spreadMs = v.roundToInt())
            },
            knob("spreadSharpness", "Run-out", 1f..8f, 1, { it.spreadSharpness }) { p, v ->
                p.copy(spreadSharpness = v)
            },
            knob("liftMs", "Lift ms", 0f..800f, 0, { it.liftMs.toFloat() }) { p, v ->
                p.copy(liftMs = v.roundToInt())
            },
        ) + curveKnobs("lift", get = { it.lift }, set = { p, c -> p.copy(lift = c) }),
    ),
    NuqtaKnobGroup(
        "Drop",
        listOf(
            knob("originDx", "Lands x (dp)", -4f..4f, 1, { it.originDx }) { p, v -> p.copy(originDx = v) },
            knob("originDy", "Lands y (dp)", -4f..4f, 1, { it.originDy }) { p, v -> p.copy(originDy = v) },
            knob("reach", "Reach", 0.6f..1.8f, 2, { it.reach }) { p, v -> p.copy(reach = v) },
            knob("fingers", "Fibre runs", 0f..1.2f, 2, { it.fingers }) { p, v -> p.copy(fingers = v) },
            knob("grain", "Edge grain", 0f..0.3f, 2, { it.grain }) { p, v -> p.copy(grain = v) },
            knob("seed", "Paper seed", 0f..64f, 0, { it.seed.toFloat() }) { p, v -> p.copy(seed = v.roundToInt()) },
        ),
    ),
    NuqtaKnobGroup(
        "Ink",
        listOf(
            knob("wetAlpha", "Lands at", 0f..1f, 2, { it.wetAlpha }) { p, v -> p.copy(wetAlpha = v) },
            knob("inkAlpha", "Soaks to", 0f..1f, 2, { it.inkAlpha }) { p, v -> p.copy(inkAlpha = v) },
            knob("soakDelay", "Soak delay", 0f..0.9f, 2, { it.soakDelay }) { p, v -> p.copy(soakDelay = v) },
            knob("feather", "Wet fringe", 0f..0.9f, 2, { it.feather }) { p, v -> p.copy(feather = v) },
            knob("fringeAlpha", "Fringe ink", 0f..1f, 2, { it.fringeAlpha }) { p, v -> p.copy(fringeAlpha = v) },
        ),
    ),
)

private val NuqtaKnobsByKey: Map<String, NuqtaKnob> =
    NuqtaKnobGroups.flatMap { it.knobs }.associateBy { it.key }

internal fun formatNuqtaKnob(knob: NuqtaKnob, value: Float): String {
    if (knob.digits == 0) return value.roundToInt().toString()
    val s = "%.${knob.digits}f".format(value).trimEnd('0').trimEnd('.')
    return if (s.isEmpty() || s == "-0") "0" else s
}

/** Copy text for the lab: a flat object web `nuqta.ts` pastes as-is. */
internal fun formatNuqtaCopy(p: NuqtaParams): String = buildString {
    appendLine("// Nuqta radio dot — paste into either platform's nuqta lab,")
    appendLine("// then ship by editing NuqtaParams defaults / SHIPPED_NUQTA.")
    appendLine("{")
    NuqtaKnobGroups.forEach { group ->
        appendLine("  // ${group.title}")
        group.knobs.forEach { knob -> appendLine("  ${knob.key}: ${formatNuqtaKnob(knob, knob.get(p))},") }
    }
    append("}")
}

/**
 * Reads any `key: value` / `key = value` pairs named in the knob list out of
 * [text], on top of [base]. Null when nothing recognisable was found.
 */
internal fun parseNuqtaFromText(text: String, base: NuqtaParams): NuqtaParams? {
    val re = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)f?\b""")
    var next = base
    var hits = 0
    for (m in re.findAll(text)) {
        val knob = NuqtaKnobsByKey[m.groupValues[1]] ?: continue
        val n = m.groupValues[2].toFloatOrNull() ?: continue
        next = knob.set(next, n)
        hits++
    }
    return if (hits > 0) next else null
}
