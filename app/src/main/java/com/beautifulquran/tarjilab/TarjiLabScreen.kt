package com.beautifulquran.tarjilab

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.playback.TarjiLabCapture
import com.beautifulquran.ui.reader.InkEngine
import com.beautifulquran.ui.theme.ArabicWordStyle
import com.beautifulquran.ui.theme.quietClickable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The lab's white-gold — the same sheen the wet glint renders with. */
private val GlintGold = Color(0xFFF8E9BE)

/**
 * The Tarjīʿ Lab: capture a word's PCM from the tap, loop it, and see —
 * on one canvas — the audio waveform, the detector's amplitude envelope,
 * the measured tarjīʿ sine, and the pure sine fitted to it, while the word
 * behind pulses with the exact shimmer the trace would drive.
 *
 * Knob edits re-run the pure detector offline over the captured stream, so
 * every change is judged instantly against the same audio, on a loop, with
 * the ear. Exported samples ([TarjiLabCodec]) reproduce any capture
 * off-device for deriving a better detector.
 */
@Composable
fun TarjiLabScreen(
    viewModel: TarjiLabViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Frame-driven playhead for the canvas + preview word: the loop is
    // hardware-looped, so the wall clock modulo the capture duration is
    // exact to the sample — no polling, no drift.
    var playheadMs by remember { mutableFloatStateOf(-1f) }
    androidx.compose.runtime.LaunchedEffect(ui.previewPlaying, ui.previewDurationMs) {
        while (ui.previewPlaying) {
            withFrameNanos {
                playheadMs = viewModel.previewPlayheadMs()
            }
        }
        playheadMs = -1f
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                Log.e("TarjiLab", "could not read $uri")
            } else {
                viewModel.importSample(text)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 14.dp),
    ) {
        LabHeader(ui, onBack, viewModel)

        if (ui.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        WordRow(ui, viewModel)

        Spacer(Modifier.height(8.dp))
        ActionRow(
            ui = ui,
            viewModel = viewModel,
            onImport = {
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            },
        )

        ui.captureError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ui.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        WaveformPanel(
            ui = ui,
            playheadMs = playheadMs,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Spacer(Modifier.height(10.dp))
        PreviewWord(
            ui = ui,
            playheadMs = playheadMs,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        KnobsPanel(
            ui = ui,
            onKnob = viewModel::updateKnobs,
            onDepth = { depth ->
                InkEngine.tuning = InkEngine.tuning.copy(glintResonanceDepth = depth)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 250.dp),
        )
    }
}

@Composable
private fun LabHeader(
    ui: TarjiLabViewModel.TarjiLabUiState,
    onBack: () -> Unit,
    viewModel: TarjiLabViewModel,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Tarjīʿ Lab",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            if (!ui.isLoading && ui.reciter != null) {
                Text(
                    text = "${ui.surahName} ${ui.ayah} · " +
                        (ui.sampleReciterName ?: ui.reciter.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close lab",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The word under study with ‹ › stepping between the ayah's words. */
@Composable
private fun WordRow(
    ui: TarjiLabViewModel.TarjiLabUiState,
    viewModel: TarjiLabViewModel,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "‹",
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = viewModel::prevWord)
                .padding(horizontal = 18.dp, vertical = 4.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(
                text = ui.wordArabic,
                style = ArabicWordStyle,
                fontSize = 34.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = ui.wordTranslation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = "›",
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = viewModel::nextWord)
                .padding(horizontal = 18.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ActionRow(
    ui: TarjiLabViewModel.TarjiLabUiState,
    viewModel: TarjiLabViewModel,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        WordAction(
            label = if (ui.capturing) "Capturing…" else "Capture word",
            onClick = viewModel::captureWord,
            color = MaterialTheme.colorScheme.primary,
        )
        WordAction(
            label = if (ui.previewPlaying) "Loop: on" else "Loop: off",
            onClick = { if (ui.previewPlaying) viewModel.stopPreview() else viewModel.startPreview() },
            color = if (ui.previewPlaying) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        WordAction(
            label = "Reset knobs",
            onClick = viewModel::resetKnobs,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        WordAction(
            label = "Export",
            onClick = { viewModel.exportSample(context) },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WordAction(
            label = "Import",
            onClick = onImport,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WordAction(
    label: String,
    onClick: () -> Unit,
    color: Color,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .quietClickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** The word wearing the shimmer: its glow pulses exactly as the trace's
 * tarjīʿ does — crests brighten the white-gold, troughs extinguish it —
 * driven by the same [InkEngine.glintResonance] mapping the reader renders. */
@Composable
private fun PreviewWord(
    ui: TarjiLabViewModel.TarjiLabUiState,
    playheadMs: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(112.dp),
    ) {
        val trace = ui.trace
        if (trace != null && playheadMs >= 0f) {
            val point = tracePointAt(trace, playheadMs)
            val resonance = InkEngine.glintResonance(
                holding = point.reverberating,
                tremolo = point.tremolo,
                tremoloGain = point.gain,
            )
            Canvas(Modifier.fillMaxSize()) {
                val glow = 0.22f * resonance.layerMult + 0.9f * resonance.peak
                if (glow > 0.01f) {
                    drawCircle(
                        color = GlintGold.copy(alpha = (glow * 0.55f).coerceIn(0f, 0.75f)),
                        radius = size.minDimension * 0.42f,
                        center = center,
                    )
                    drawCircle(
                        color = GlintGold.copy(alpha = (glow * 0.3f).coerceIn(0f, 0.45f)),
                        radius = size.minDimension * 0.62f,
                        center = center,
                    )
                }
            }
        }
        Text(
            text = ui.wordArabic,
            style = ArabicWordStyle,
            fontSize = 56.sp,
            color = GlintGold.copy(alpha = 0.96f),
        )
    }
}

/** Waveform + detector trace canvas: the raw audio, its 80 ms envelope, the
 * measured tarjīʿ sine, the fitted ideal sine, and the playhead. */
@Composable
private fun WaveformPanel(
    ui: TarjiLabViewModel.TarjiLabUiState,
    playheadMs: Float,
    modifier: Modifier = Modifier,
) {
    val capture = ui.capture
    val trace = ui.trace
    val fit = ui.sineFit
    val waveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val envColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val guideText = if (capture == null) {
        "No capture yet — Capture word plays it once, then loops."
    } else {
        "No audio in this capture."
    }
    Column(modifier = modifier) {
        val peak = remember(capture) {
            capture?.pcm?.let { p ->
                var m = 0f
                for (v in p) m = max(m, abs(v))
                m
            } ?: 0f
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (capture == null || trace == null || peak <= 0f) {
                drawGuide(guideText, guideColor)
                return@Canvas
            }
            val durationMs = trace.hopCount * trace.hopDurationMs
            val sineColor = GlintGold.copy(alpha = 0.95f)
            val fitColor = Color.White.copy(alpha = 0.55f)
            val revColor = GlintGold.copy(alpha = 0.10f)

            // Reverberating band behind everything.
            trace.reverberatingSpan?.let { span ->
                val left = hopX(span.first.toFloat(), trace, size.width)
                val right = hopX((span.last + 1).toFloat(), trace, size.width)
                drawRect(revColor, topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(right - left, size.height))
            }

            // Waveform: min/max per pixel column.
            var column = 0f
            while (column < size.width) {
                val start = (column / size.width * capture.pcm.size).toInt()
                val end = ((column + 1f) / size.width * capture.pcm.size).toInt()
                    .coerceAtLeast(start + 1)
                var lo = 1f
                var hi = -1f
                var j = start
                while (j < end && j < capture.pcm.size) {
                    val v = capture.pcm[j] / peak
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                    j++
                }
                val mid = size.height * 0.5f
                drawLine(
                    waveColor,
                    Offset(column, mid - hi * size.height * 0.42f),
                    Offset(column, mid - lo * size.height * 0.42f),
                    strokeWidth = 1f,
                )
                column++
            }

            // Envelope (normalized to the same peak as the waveform).
            var lastEnv: Offset? = null
            for (i in trace.firstAnalysisHop until trace.hopCount) {
                val x = hopX(i + 0.5f, trace, size.width)
                val y = size.height * 0.5f - (trace.envRms[i] / peak) * size.height * 0.42f
                lastEnv?.let { drawLine(envColor, it, Offset(x, y), strokeWidth = 1f) }
                lastEnv = Offset(x, y)
            }

            // Measured tarjīʿ sine (alpha rides the gain, so detection edges
            // dry out instead of popping on/off) and the fitted ideal sine.
            drawSine(trace.tremolo, trace.gain, sineColor, trace)
            fit?.let { f ->
                drawFittedSine(f, fitColor, trace)
            }

            // Word span bracket (capture lead → lead + word duration). Only
            // for live captures whose first hop has a media anchor.
            if (ui.wordEndMs > ui.wordStartMs && ui.firstHopMediaMs > 0.0) {
                val lead = (ui.wordStartMs.toDouble() - ui.firstHopMediaMs)
                    .coerceAtLeast(0.0)
                    .toFloat()
                val left = (lead / durationMs) * size.width
                val right = ((ui.wordEndMs - ui.firstHopMediaMs).toFloat() / durationMs) * size.width
                val y = size.height - 8f
                drawLine(GlintGold.copy(alpha = 0.5f), Offset(left, y), Offset(right, y), strokeWidth = 2f, cap = StrokeCap.Round)
                drawLine(GlintGold.copy(alpha = 0.35f), Offset(left, y - 4f), Offset(left, y + 4f), strokeWidth = 2f)
                drawLine(GlintGold.copy(alpha = 0.35f), Offset(right, y - 4f), Offset(right, y + 4f), strokeWidth = 2f)
            }

            // Playhead.
            if (playheadMs >= 0f) {
                val x = (playheadMs / durationMs) * size.width
                drawLine(
                    Color.White.copy(alpha = 0.85f),
                    Offset(x, 4f),
                    Offset(x, size.height - 16f),
                    strokeWidth = 2f,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            val durationMs = if (trace != null) trace.hopCount * trace.hopDurationMs else 0f
            val label = if (capture != null) {
                val rate = ui.sineFit?.let { "${"%.1f".format(it.rateHz)} Hz" } ?: "—"
                "captured ${"%.1f".format(durationMs / 1000f)}s · hop ${"%.1f".format(trace?.hopDurationMs)}ms · fit $rate"
            } else {
                "waveform · tarjīʿ sine"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            if (ui.analyzing) {
                Text(
                    text = "analyzing…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun DrawScope.hopX(hop: Float, trace: TarjiLabTrace, width: Float): Float {
    val duration = trace.hopCount * trace.hopDurationMs
    if (duration <= 0f) return 0f
    return (hop * trace.hopDurationMs / duration) * width
}

private fun DrawScope.drawSine(
    values: FloatArray,
    gains: FloatArray,
    color: Color,
    trace: TarjiLabTrace,
) {
    val mid = size.height * 0.5f
    val amp = size.height * 0.30f
    var last: Offset? = null
    for (i in trace.firstAnalysisHop until trace.hopCount) {
        val x = hopX(i + 0.5f, trace, size.width)
        val y = mid - values[i] * amp * gains[i].coerceIn(0f, 1f)
        val alpha = gains[i].coerceIn(0f, 1f) * 0.95f
        last?.let {
            drawLine(color.copy(alpha = alpha), it, Offset(x, y), strokeWidth = 2f, cap = StrokeCap.Round)
        }
        last = Offset(x, y)
    }
}

private fun DrawScope.drawFittedSine(
    fit: TarjiSineFit,
    color: Color,
    trace: TarjiLabTrace,
) {
    val mid = size.height * 0.5f
    val amp = size.height * 0.30f
    val hopDur = trace.hopDurationMs
    var dash = true
    var last: Offset? = null
    var x = hopX(fit.startHop.toFloat(), trace, size.width)
    while (x <= hopX(fit.endHop.toFloat(), trace, size.width)) {
        val ms = (x / size.width) * trace.hopCount * hopDur
        val y = mid - fit.valueAt(ms, hopDur) * amp
        val point = Offset(x, y)
        if (!dash) last?.let { drawLine(color, it, point, strokeWidth = 1.5f) }
        last = point
        dash = !dash
        x += 3f
    }
}

private fun DrawScope.drawGuide(text: String, color: Color) {
    // Centered quiet guidance — the lab's empty state.
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 30f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f, size.height / 2f, paint)
}

/** The detector knobs (Ink Lab's Tarjīʿ set) with the effect's pulse depth. */
@Composable
private fun KnobsPanel(
    ui: TarjiLabViewModel.TarjiLabUiState,
    onKnob: ((TarjiLabKnobs) -> TarjiLabKnobs) -> Unit,
    onDepth: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobs = ui.knobs
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        LabSlider(
            "Pulse depth",
            InkEngine.tuning.glintResonanceDepth,
            0f..1f,
        ) { v -> onDepth(v) }
        LabSlider("Max rate Hz", knobs.maxTremoloHz, 1.5f..10f) { v ->
            onKnob { k -> k.copy(maxTremoloHz = v) }
        }
        LabSlider("Min rate Hz", knobs.minTremoloHz, 1.5f..5f) { v ->
            onKnob { k -> k.copy(minTremoloHz = v) }
        }
        LabSlider("Hold min ms", knobs.holdMinMs, 100f..1200f) { v ->
            onKnob { k -> k.copy(holdMinMs = v) }
        }
        LabSlider("Min depth", knobs.minTremoloDepth, 0.01f..0.25f) { v ->
            onKnob { k -> k.copy(minTremoloDepth = v) }
        }
        LabSlider("Min periodicity", knobs.minPeriodicity, 0.15f..0.85f) { v ->
            onKnob { k -> k.copy(minPeriodicity = v) }
        }
        LabSlider("Pitch drift", knobs.maxPitchDrift, 0.04f..0.30f) { v ->
            onKnob { k -> k.copy(maxPitchDrift = v) }
        }
        LabSlider("Attack ms", knobs.attackMs, 50f..600f) { v ->
            onKnob { k -> k.copy(attackMs = v) }
        }
        LabSlider("Release ms", knobs.releaseMs, 100f..2000f) { v ->
            onKnob { k -> k.copy(releaseMs = v) }
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (range.endInclusive - range.start <= 1f) "%.2f".format(value)
            else value.roundToInt().toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
    }
}
