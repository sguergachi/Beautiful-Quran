package com.beautifulquran.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.NuqtaCurve
import com.beautifulquran.ui.theme.NuqtaLayerParams
import com.beautifulquran.ui.theme.NuqtaParams
import com.beautifulquran.ui.theme.ShippedNuqtaParams
import com.beautifulquran.ui.theme.quietClickable
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * One tunable number of the nuqta. The same list drives the lab's sliders, its
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

private fun layerKnobs(
    prefix: String,
    get: (NuqtaParams) -> NuqtaLayerParams,
    set: (NuqtaParams, NuqtaLayerParams) -> NuqtaParams,
): List<NuqtaKnob> = listOf(
    NuqtaKnob("${prefix}Start", "Starts at", 0f..0.9f, 2, { get(it).start }) { p, v -> set(p, get(p).copy(start = v)) },
    NuqtaKnob("${prefix}End", "Arrives at", 0.1f..1f, 2, { get(it).end }) { p, v -> set(p, get(p).copy(end = v)) },
) + curveKnobs(
    prefix,
    get = { get(it).curve },
    set = { p, c -> set(p, get(p).copy(curve = c)) },
) + listOf(
    NuqtaKnob("${prefix}Radius", "Reach", 0.2f..1.6f, 2, { get(it).radius }) { p, v -> set(p, get(p).copy(radius = v)) },
    NuqtaKnob("${prefix}Alpha", "Alpha", 0f..1f, 2, { get(it).alpha }) { p, v -> set(p, get(p).copy(alpha = v)) },
    NuqtaKnob("${prefix}Dx", "Pool x (dp)", -4f..4f, 1, { get(it).dxDp }) { p, v -> set(p, get(p).copy(dxDp = v)) },
    NuqtaKnob("${prefix}Dy", "Pool y (dp)", -4f..4f, 1, { get(it).dyDp }) { p, v -> set(p, get(p).copy(dyDp = v)) },
)

internal val NuqtaKnobGroups: List<NuqtaKnobGroup> = listOf(
    NuqtaKnobGroup(
        "Cut",
        listOf(
            NuqtaKnob("sizeDp", "Size (dp)", 14f..32f, 0, { it.sizeDp }) { p, v -> p.copy(sizeDp = v) },
            NuqtaKnob("bow", "Side bow", 0f..2.5f, 2, { it.bow }) { p, v -> p.copy(bow = v) },
            NuqtaKnob("strokeDp", "Outline (dp)", 0.4f..2.5f, 2, { it.strokeDp }) { p, v -> p.copy(strokeDp = v) },
            NuqtaKnob("restingOutlineAlpha", "Resting ink", 0f..1f, 2, { it.restingOutlineAlpha }) { p, v ->
                p.copy(restingOutlineAlpha = v)
            },
            NuqtaKnob("selectedOutlineAlpha", "Chosen ink", 0f..1f, 2, { it.selectedOutlineAlpha }) { p, v ->
                p.copy(selectedOutlineAlpha = v)
            },
        ),
    ),
    NuqtaKnobGroup(
        "Spread & lift",
        listOf(
            NuqtaKnob("spreadMs", "Spread ms", 120f..2000f, 0, { it.spreadMs.toFloat() }) { p, v ->
                p.copy(spreadMs = v.roundToInt())
            },
            NuqtaKnob("wetDryBack", "Wet dry-back", 0f..1f, 2, { it.wetDryBack }) { p, v -> p.copy(wetDryBack = v) },
            NuqtaKnob("liftMs", "Lift ms", 0f..800f, 0, { it.liftMs.toFloat() }) { p, v ->
                p.copy(liftMs = v.roundToInt())
            },
        ) + curveKnobs("lift", get = { it.lift }, set = { p, c -> p.copy(lift = c) }),
    ),
    NuqtaKnobGroup("Wet edge", layerKnobs("wet", get = { it.wet }, set = { p, l -> p.copy(wet = l) })),
    NuqtaKnobGroup("Body", layerKnobs("body", get = { it.body }, set = { p, l -> p.copy(body = l) })),
    NuqtaKnobGroup("Pool", layerKnobs("pool", get = { it.pool }, set = { p, l -> p.copy(pool = l) })),
)

private val NuqtaKnobsByKey: Map<String, NuqtaKnob> =
    NuqtaKnobGroups.flatMap { it.knobs }.associateBy { it.key }

private fun formatKnob(knob: NuqtaKnob, value: Float): String {
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
        group.knobs.forEach { knob -> appendLine("  ${knob.key}: ${formatKnob(knob, knob.get(p))},") }
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

/**
 * Developer lab for the nuqta radio dot. Edits are session-only and reach every
 * nuqta on the Settings and Customize sheets live, so the real lists are the
 * preview; the two rows here just give a place to flick back and forth.
 */
@Composable
internal fun NuqtaLab(
    params: NuqtaParams,
    onChange: (NuqtaParams) -> Unit,
) {
    val context = LocalContext.current
    var demoChoice by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    if (note != null) {
        LaunchedEffect(note) {
            delay(2000L)
            note = null
        }
    }
    val onNote: (String) -> Unit = { note = it }

    Text(
        "Nuqta radio dot",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Caption("Every single-choice row on these sheets follows these knobs live.")
    Spacer(Modifier.height(6.dp))
    listOf("First choice", "Second choice").forEachIndexed { index, label ->
        SelectRow(label = label, selected = demoChoice == index, onClick = { demoChoice = index })
    }

    NuqtaKnobGroups.forEach { group ->
        Spacer(Modifier.height(10.dp))
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        group.knobs.forEach { knob ->
            BrushTuningSlider(
                label = knob.label,
                value = knob.get(params),
                range = knob.range,
                integer = knob.digits == 0,
                formatValue = { formatKnob(knob, it) },
                onChange = { onChange(knob.set(params, it)) },
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LabAction("Reset nuqta") { onChange(ShippedNuqtaParams) }
        LabAction("Replay") { demoChoice = 1 - demoChoice }
        LabAction("Copy nuqta") {
            val text = formatNuqtaCopy(params)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nuqta params", text))
            Log.d("NuqtaLab", text)
            onNote("Copied nuqta params")
        }
        LabAction("Paste nuqta") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val raw = cm.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
            val parsed = parseNuqtaFromText(raw, params)
            if (parsed == null) {
                onNote("No nuqta knobs found in clipboard")
            } else {
                onChange(parsed)
                onNote("Applied nuqta params")
            }
        }
    }
    note?.let { Caption(it) }
}

@Composable
private fun LabAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .quietClickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}
