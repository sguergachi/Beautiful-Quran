package com.beautifulquran.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.ContextualGuideTuning
import com.beautifulquran.ui.theme.DisclosureChevron
import com.beautifulquran.ui.theme.quietClickable
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Developer-mode overlay for tuning the highlight feel live: sliders bound
 * straight to [InkEngine.tuning], so every change is visible on the page
 * behind it while a recitation plays. Lab numbers (Tuning + Highlight knobs)
 * persist across process restarts via [InkLabStore] until **Reset**;
 * "Copy values" puts a paste-ready [InkEngine.Tuning] constructor on the
 * clipboard (and Logcat tag `InkLab`) so tuned numbers can land in the
 * shipped defaults in InkEngine.kt.
 *
 * The action row also hosts a session-only [InkEngine.focusEngineEnabled]
 * freeze (next to Copy values) so auto-home can be parked while panning.
 * Reset does not touch it, and Focus is never persisted.
 *
 * Enabled from Settings → Developer → "Ink Lab overlay" (developer mode
 * itself unlocks by tapping the Settings logo). See docs/INK_ENGINE.md.
 */
/**
 * The panel's sections. There are far too many knobs to scroll as one list,
 * and they cluster naturally by what you are listening for: the resting page,
 * the wash that reveals a word, the orange repeat chain, the experimental
 * tajweed hold, and the karaoke clock (Bluetooth lag + next-ayah lead).
 */
private enum class InkLabTab(val label: String) {
    Ink("Ink"),
    Sweep("Sweep"),
    Repeat("Repeat"),
    Tajweed("Tajweed"),
    Guide("Guide"),
    /** Karaoke clock: word lead + output lag + ayah fade-lead — not wash feel. */
    Highlight("Sync"),
}

@Composable
fun InkLabPanel(
    modifier: Modifier = Modifier,
    guideActive: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var tab by remember(guideActive) {
        mutableStateOf(if (guideActive) InkLabTab.Guide else InkLabTab.Ink)
    }
    var copyNote by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.widthIn(max = 340.dp),
    ) {
        // Collapsed the panel is just its name — a quiet ink label that
        // expands into the sliders, so the page stays readable while tuning.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                .quietClickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Ink Lab",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            DisclosureChevron(expanded = expanded)
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            InkLabTabs(selected = tab, onSelect = { tab = it })
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState(), reverseScrolling = false),
            ) {
                val t = InkEngine.tuning
                val guide = InkEngine.contextualGuideTuning
                when (tab) {
                    InkLabTab.Ink -> {
                        TuningSlider("Upcoming ink", t.upcomingAlpha, 0.05f..0.6f) {
                            InkEngine.tuning = t.copy(upcomingAlpha = it)
                        }
                        TuningSlider("Ink fade ms", t.inkFadeMs.toFloat(), 0f..1200f, integer = true) {
                            InkEngine.tuning = t.copy(inkFadeMs = it.roundToInt())
                        }
                        TuningSlider("Mark fade ms", t.ayahMarkFadeMs.toFloat(), 0f..1200f, integer = true) {
                            InkEngine.tuning = t.copy(ayahMarkFadeMs = it.roundToInt())
                        }
                        TuningSlider("Recess ms", t.recessMs.toFloat(), 0f..1400f, integer = true) {
                            InkEngine.tuning = t.copy(recessMs = it.roundToInt())
                        }
                    }

                    InkLabTab.Sweep -> {
                        TuningSlider("Min sweep ms", t.minSweepMs.toFloat(), 40f..600f, integer = true) {
                            InkEngine.tuning = t.copy(minSweepMs = it.roundToInt())
                        }
                        TuningSlider("Max sweep ms", t.maxSweepMs.toFloat(), 1000f..12000f, integer = true) {
                            InkEngine.tuning = t.copy(maxSweepMs = it.roundToInt())
                        }
                        TuningSlider("Wash feather", t.washFeather, 0.2f..3f) {
                            InkEngine.tuning = t.copy(washFeather = it)
                        }
                        TuningSlider("Glitter time ms", t.glintFadeMs.toFloat(), 100f..2400f, integer = true) {
                            InkEngine.tuning = t.copy(glintFadeMs = it.roundToInt())
                        }
                        TuningSlider("Glint tint", t.glintTintAlpha, 0f..1f) {
                            InkEngine.tuning = t.copy(glintTintAlpha = it)
                        }
                        TuningSlider("Halo strength", t.glintGlowAlpha, 0f..1f) {
                            InkEngine.tuning = t.copy(glintGlowAlpha = it)
                        }
                        TuningSlider("Halo blur", t.glintGlowRadius, 0f..10f) {
                            InkEngine.tuning = t.copy(glintGlowRadius = it)
                        }
                    }

                    InkLabTab.Repeat -> {
                        TuningSlider("Repeat minimum ms", t.repeatSweepMs.toFloat(), 100f..1500f, integer = true) {
                            InkEngine.tuning = t.copy(repeatSweepMs = it.roundToInt())
                        }
                        TuningSlider("Repeat fade ms", t.repeatFadeOutMs.toFloat(), 100f..2400f, integer = true) {
                            InkEngine.tuning = t.copy(repeatFadeOutMs = it.roundToInt())
                        }
                        TuningSlider("Repeat ink", t.repeatInkAlpha, 0.2f..1f) {
                            InkEngine.tuning = t.copy(repeatInkAlpha = it)
                        }
                    }

                    // Letter-level tajweed hold on the active sweep —
                    // experimental (docs/TAJWEED_PACING.md).
                    InkLabTab.Tajweed -> {
                        TuningToggle("Tajweed pacing", t.tajweedPacing) {
                            InkEngine.tuning = t.copy(tajweedPacing = it)
                        }
                        TuningToggle("Hold: madd", t.holdMadd) {
                            InkEngine.tuning = t.copy(holdMadd = it)
                        }
                        TuningToggle("Hold: ghunnah", t.holdGhunnah) {
                            InkEngine.tuning = t.copy(holdGhunnah = it)
                        }
                        TuningToggle("Hold: waqf", t.holdWaqf) {
                            InkEngine.tuning = t.copy(holdWaqf = it)
                        }
                        TuningToggle("Hold: wasl connect", t.holdConnect) {
                            InkEngine.tuning = t.copy(holdConnect = it)
                        }
                        TuningSlider(
                            "Wasl prefix ms",
                            t.waslPrefixMs.toFloat(),
                            120f..900f,
                            integer = true,
                        ) {
                            InkEngine.tuning = t.copy(waslPrefixMs = it.roundToInt())
                        }
                        LabCaption(
                            "Speed ceiling for the next-letter wasl bloom " +
                                "(مَن يَشْرِى, مِن رَّبِّكُم). Higher = slower " +
                                "fade into the next opening. Short donors may " +
                                "use up to 75% of their span, then continue " +
                                "the unfinished fade after handoff.",
                        )
                        TuningSlider("Wasl pre-ink", t.waslHandoff, 0f..1f) {
                            InkEngine.tuning = t.copy(waslHandoff = it)
                        }
                        LabCaption(
                            "Maximum share of the connected opening revealed " +
                                "before it becomes active. Lower leaves more " +
                                "wash visible after handoff; 0 disables the " +
                                "early carry.",
                        )
                        TuningSlider("Cruise cap", t.cruiseCap, 1f..2f) {
                            InkEngine.tuning = t.copy(cruiseCap = it)
                        }
                        TuningSlider("Waqf hold", t.waqfShare, 0f..0.8f) {
                            InkEngine.tuning = t.copy(waqfShare = it)
                        }
                        TuningSlider("Waqf length scale", t.waqfLengthScale, 0f..1f) {
                            InkEngine.tuning = t.copy(waqfLengthScale = it)
                        }
                        TuningSlider("Hold creep", t.holdCreep, 0f..0.3f) {
                            InkEngine.tuning = t.copy(holdCreep = it)
                        }
                        TuningSlider("Paced feather", t.pacedFeather, 0.3f..3f) {
                            InkEngine.tuning = t.copy(pacedFeather = it)
                        }
                    }

                    InkLabTab.Guide -> {
                        TuningSlider("Body edge", guide.bodyEdge, 0.28f..0.55f) {
                            InkEngine.contextualGuideTuning = guide.copy(bodyEdge = it)
                        }
                        TuningSlider("Feather width", guide.featherWidth, 0.12f..0.5f) {
                            InkEngine.contextualGuideTuning = guide.copy(featherWidth = it)
                        }
                        TuningSlider("Fade softness", guide.fadeSoftness, 0.4f..1.8f) {
                            InkEngine.contextualGuideTuning = guide.copy(fadeSoftness = it)
                        }
                        TuningSlider("Blur radius dp", guide.blurRadiusDp, 0f..24f) {
                            InkEngine.contextualGuideTuning = guide.copy(blurRadiusDp = it)
                        }
                        TuningSlider("Blur strength", guide.blurStrength, 0f..1f) {
                            InkEngine.contextualGuideTuning = guide.copy(blurStrength = it)
                        }
                        TuningSlider("Vellum grain", guide.vellumGrain, 0f..0.08f) {
                            InkEngine.contextualGuideTuning = guide.copy(vellumGrain = it)
                        }
                        TuningSlider("Vertical taper", guide.verticalTaper, 0f..0.08f) {
                            InkEngine.contextualGuideTuning = guide.copy(verticalTaper = it)
                        }
                    }

                    // Karaoke clock — word lead, BT lag, ayah prepare
                    // (docs/OUTPUT_LATENCY.md).
                    InkLabTab.Highlight -> {
                        TuningSlider(
                            "Highlight lead ms",
                            InkEngine.highlightLeadMs.toFloat(),
                            0f..1200f,
                            integer = true,
                        ) {
                            InkEngine.highlightLeadMs = it.roundToInt()
                        }
                        LabCaption(
                            "How early each word's wash starts vs the timing table. " +
                                "1200 = ink runs 1.2s ahead of HighlightEngine startMs.",
                        )
                        val override = InkEngine.outputLatencyOverrideMs
                        TuningToggle("Manual output lag", override != null) { on ->
                            InkEngine.outputLatencyOverrideMs =
                                if (on) (override ?: 180) else null
                        }
                        LabCaption(
                            if (override == null) {
                                "Auto: route preset (speaker 0 / A2DP 180 / LE 80). " +
                                    "Lag delays ink to match late audio."
                            } else {
                                "Override absolute lag subtracted from the playhead."
                            },
                        )
                        if (override != null) {
                            TuningSlider(
                                "Output lag ms",
                                override.toFloat(),
                                0f..400f,
                                integer = true,
                            ) {
                                InkEngine.outputLatencyOverrideMs = it.roundToInt()
                            }
                        }
                        TuningSlider(
                            "Ayah fade lead ms",
                            InkEngine.fadeLeadMs.toFloat(),
                            0f..1200f,
                            integer = true,
                        ) {
                            InkEngine.fadeLeadMs = it.roundToInt()
                        }
                        LabCaption(
                            "Ayah handoff only: next verse focus/recess before last word ends. " +
                                "Does not move word washes.",
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            // Actions share one row so chrome stays thin: Reset / Copy values
            // sit beside the session-only focus freeze (not part of Tuning).
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .quietClickable {
                            // Clears on-device overrides so future shipped
                            // default changes apply; Focus freeze stays put.
                            InkEngine.resetLabToShippedDefaults()
                            copyNote = null
                        }
                        .padding(vertical = 4.dp),
                )
                Text(
                    text = "Copy values",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .quietClickable {
                            val text = formatTuningCopy(InkEngine.tuning) +
                                "\n" +
                                formatContextualGuideCopy(InkEngine.contextualGuideTuning) +
                                "\n" + formatHighlightCopy()
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Ink Lab tuning", text))
                            Log.d("InkLab", text)
                            copyNote = "Copied"
                        }
                        .padding(vertical = 4.dp),
                )
                ActionToggle(
                    label = "Focus",
                    value = InkEngine.focusEngineEnabled,
                    onChange = { InkEngine.focusEngineEnabled = it },
                )
            }
            copyNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Paste-ready defaults for the progressive-vellum guide shader. */
internal fun formatContextualGuideCopy(t: ContextualGuideTuning): String {
    fun f(v: Float): String {
        val s = "%.4f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        return "${s}f"
    }
    return buildString {
        appendLine("// Contextual guide — 02 progressive vellum")
        appendLine("ContextualGuideTuning(")
        appendLine("    bodyEdge = ${f(t.bodyEdge)},")
        appendLine("    featherWidth = ${f(t.featherWidth)},")
        appendLine("    fadeSoftness = ${f(t.fadeSoftness)},")
        appendLine("    blurRadiusDp = ${f(t.blurRadiusDp)},")
        appendLine("    blurStrength = ${f(t.blurStrength)},")
        appendLine("    vellumGrain = ${f(t.vellumGrain)},")
        appendLine("    verticalTaper = ${f(t.verticalTaper)},")
        append(")")
    }
}

/**
 * Paste-ready Kotlin for the current lab values — drop into
 * [InkEngine.Tuning] defaults or a `InkEngine.tuning = …` call.
 * Sweep-easing control points are included even though the panel has no
 * sliders for them, so a full snapshot never silently drops fields.
 */
internal fun formatTuningCopy(t: InkEngine.Tuning): String {
    fun f(v: Float): String {
        val s = "%.4f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        return "${s}f"
    }
    return buildString {
        appendLine("// InkEngine.Tuning — paste into defaults or a tuning assignment")
        appendLine("InkEngine.Tuning(")
        appendLine("    upcomingAlpha = ${f(t.upcomingAlpha)},")
        appendLine("    inkFadeMs = ${t.inkFadeMs},")
        appendLine("    ayahMarkFadeMs = ${t.ayahMarkFadeMs},")
        appendLine("    recessMs = ${t.recessMs},")
        appendLine("    minSweepMs = ${t.minSweepMs},")
        appendLine("    maxSweepMs = ${t.maxSweepMs},")
        appendLine("    repeatSweepMs = ${t.repeatSweepMs},")
        appendLine("    repeatFadeOutMs = ${t.repeatFadeOutMs},")
        appendLine("    repeatInkAlpha = ${f(t.repeatInkAlpha)},")
        appendLine("    glintFadeMs = ${t.glintFadeMs},")
        appendLine("    glintTintAlpha = ${f(t.glintTintAlpha)},")
        appendLine("    glintGlowAlpha = ${f(t.glintGlowAlpha)},")
        appendLine("    glintGlowRadius = ${f(t.glintGlowRadius)},")
        appendLine("    washFeather = ${f(t.washFeather)},")
        appendLine("    sweepEaseX1 = ${f(t.sweepEaseX1)},")
        appendLine("    sweepEaseY1 = ${f(t.sweepEaseY1)},")
        appendLine("    sweepEaseX2 = ${f(t.sweepEaseX2)},")
        appendLine("    sweepEaseY2 = ${f(t.sweepEaseY2)},")
        appendLine("    tajweedPacing = ${t.tajweedPacing},")
        appendLine("    pacedFeather = ${f(t.pacedFeather)},")
        appendLine("    holdMadd = ${t.holdMadd},")
        appendLine("    holdGhunnah = ${t.holdGhunnah},")
        appendLine("    holdWaqf = ${t.holdWaqf},")
        appendLine("    holdConnect = ${t.holdConnect},")
        appendLine("    waslPrefixMs = ${t.waslPrefixMs},")
        appendLine("    waslHandoff = ${f(t.waslHandoff)},")
        appendLine("    cruiseCap = ${f(t.cruiseCap)},")
        appendLine("    waqfShare = ${f(t.waqfShare)},")
        appendLine("    waqfLengthScale = ${f(t.waqfLengthScale)},")
        appendLine("    holdCreep = ${f(t.holdCreep)},")
        append(")")
    }
}

/** Highlight-sync knobs for the clipboard snapshot (also persisted with Tuning). */
internal fun formatHighlightCopy(): String = buildString {
    appendLine("// Highlight sync (Ink Lab → Highlight) — persisted with lab numbers")
    appendLine("InkEngine.highlightLeadMs = ${InkEngine.highlightLeadMs}")
    appendLine("InkEngine.fadeLeadMs = ${InkEngine.fadeLeadMs}")
    val lag = InkEngine.outputLatencyOverrideMs
    append(
        if (lag == null) {
            "InkEngine.outputLatencyOverrideMs = null // auto route preset"
        } else {
            "InkEngine.outputLatencyOverrideMs = $lag"
        },
    )
}

/**
 * Section picker in the panel's quiet-ink idiom: names in a row, the current
 * one inked and underlined. No tab bar chrome, no ripple — the same paper
 * treatment as the rest of the lab (docs/DESIGN.md).
 */
@Composable
private fun InkLabTabs(selected: InkLabTab, onSelect: (InkLabTab) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        InkLabTab.entries.forEach { entry ->
            val active = entry == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .quietClickable { onSelect(entry) }
                        .padding(vertical = 2.dp),
                )
                // A hairline of ink under the live section, drawn at zero
                // height when idle so the row never reflows on selection.
                Spacer(
                    Modifier
                        .padding(top = 1.dp)
                        .width(if (active) 18.dp else 0.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** Compact on/off for the action row — sits beside Copy values without a
 * full-width label column. Same word idiom as [TuningToggle]. */
@Composable
private fun ActionToggle(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .quietClickable { onChange(!value) }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (value) "on" else "off",
            style = MaterialTheme.typography.labelLarge,
            color = if (value) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** On/off knob in the panel's quiet-ink idiom — a word, not a switch. The
 * whole row is the tap target: the value word alone is too small to hit
 * reliably, and with no ripple a missed tap gives no feedback at all. */
@Composable
private fun TuningToggle(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable { onChange(!value) }
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = if (value) "on" else "off",
            style = MaterialTheme.typography.labelLarge,
            color = if (value) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Quiet helper line under a toggle or slider (Ink Lab only). */
@Composable
private fun LabCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    )
}

/**
 * Decade base for the zero-including exponential map. Higher → more track
 * spent near the low end (finer small-value control, coarser large values).
 */
private const val LOG_SLIDER_BASE = 10f

/**
 * Map a real [value] in [range] to a 0..1 slider thumb position on a log
 * scale: equal thumb travel ≈ equal *ratios* when [range.start] > 0, or more
 * precision near the floor when the range includes 0.
 */
internal fun inkLabValueToPosition(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
): Float {
    val min = range.start
    val max = range.endInclusive
    if (max <= min) return 0f
    val v = value.coerceIn(min, max)
    return if (min > 0f) {
        ln(v / min) / ln(max / min)
    } else {
        val u = (v - min) / (max - min)
        ln(1f + u * (LOG_SLIDER_BASE - 1f)) / ln(LOG_SLIDER_BASE)
    }
}

/** Inverse of [inkLabValueToPosition]: 0..1 thumb → real value in [range]. */
internal fun inkLabPositionToValue(
    position: Float,
    range: ClosedFloatingPointRange<Float>,
): Float {
    val min = range.start
    val max = range.endInclusive
    if (max <= min) return min
    val t = position.coerceIn(0f, 1f)
    return if (min > 0f) {
        min * (max / min).pow(t)
    } else {
        val u = (LOG_SLIDER_BASE.pow(t) - 1f) / (LOG_SLIDER_BASE - 1f)
        min + (max - min) * u
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    integer: Boolean = false,
    onChange: (Float) -> Unit,
) {
    // Log track: fine grain near the low end, still reaches the high end.
    // Material Slider is linear in its valueRange; we feed it 0..1 positions.
    val position = inkLabValueToPosition(value, range)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Slider(
            value = position,
            onValueChange = { t ->
                val raw = inkLabPositionToValue(t, range)
                onChange(if (integer) raw.roundToInt().toFloat() else raw)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (integer) value.roundToInt().toString() else "%.2f".format(value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
    }
}
