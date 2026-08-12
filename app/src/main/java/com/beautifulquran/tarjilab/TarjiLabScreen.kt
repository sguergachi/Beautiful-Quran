package com.beautifulquran.tarjilab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TarjiLabScreen(
    viewModel: TarjiLabViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var toolsExpanded by remember { mutableStateOf(false) }

    // Frame-driven playhead for the canvas + preview word: the loop is
    // hardware-looped, so the wall clock modulo the capture duration is
    // exact to the sample — no polling, no drift.
    var playheadMs by remember { mutableFloatStateOf(-1f) }
    androidx.compose.runtime.LaunchedEffect(
        ui.previewPlaying,
        ui.previewDurationMs,
        ui.previewPositionMs,
    ) {
        while (ui.previewPlaying) {
            withFrameNanos {
                playheadMs = viewModel.previewPlayheadMs()
            }
        }
        playheadMs = viewModel.previewPlayheadMs()
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

    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (previous != null) controller.isAppearanceLightStatusBars = previous
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        LabHeader(ui, onBack)

        if (ui.isLoading) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 14.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                WordRow(ui, viewModel, playheadMs)

                Spacer(Modifier.height(10.dp))
                WaveformPanel(
                    ui = ui,
                    playheadMs = playheadMs,
                    onScrubStart = viewModel::beginPreviewScrub,
                    onScrub = viewModel::seekPreviewTo,
                    onScrubEnd = viewModel::endPreviewScrub,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                ActionRow(
                    ui = ui,
                    viewModel = viewModel,
                    toolsExpanded = toolsExpanded,
                    onToggleTools = { toolsExpanded = !toolsExpanded },
                )
                if (toolsExpanded) {
                    ToolsRow(
                        viewModel = viewModel,
                        context = context,
                        onImport = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                    )
                }
                if (ui.capturing) {
                    CaptureProgress(ui.captureProgress)
                }
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

                Spacer(Modifier.height(8.dp))
                KnobsPanel(
                    ui = ui,
                    onKnob = viewModel::updateKnobs,
                    onDepth = { depth ->
                        InkEngine.tuning = InkEngine.tuning.copy(glintResonanceDepth = depth)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LabHeader(
    ui: TarjiLabViewModel.TarjiLabUiState,
    onBack: () -> Unit,
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
    playheadMs: Float,
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            PreviewWord(
                ui = ui,
                playheadMs = playheadMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp),
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
    toolsExpanded: Boolean,
    onToggleTools: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        maxItemsInEachRow = 3,
        modifier = Modifier.fillMaxWidth(),
    ) {
        WordAction(
            label = if (ui.capturing) "Cancel capture" else "Capture word",
            onClick = viewModel::captureWord,
            color = if (ui.capturing) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        PreviewAction(
            playing = ui.previewPlaying,
            onClick = viewModel::togglePreview,
        )
        WordAction(
            label = "Tools",
            onClick = onToggleTools,
            color = if (toolsExpanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ToolsRow(
    viewModel: TarjiLabViewModel,
    context: android.content.Context,
    onImport: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
    ) {
        WordAction(
            label = "Reset knobs",
            onClick = viewModel::resetKnobs,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun CaptureProgress(progress: Float) {
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Capturing ${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = active,
            )
            Text(
                text = "Tap Cancel capture to stop",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .padding(top = 2.dp),
        ) {
            drawLine(
                color = track,
                start = Offset.Zero.copy(y = size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = active,
                start = Offset.Zero.copy(y = size.height / 2f),
                end = Offset(size.width * progress.coerceIn(0f, 1f), size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Explicit transport control for the captured word loop. */
@Composable
private fun PreviewAction(
    playing: Boolean,
    onClick: () -> Unit,
) {
    val color = if (playing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .quietClickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Pause loop" else "Play loop",
            tint = color,
            modifier = Modifier.padding(end = 4.dp).width(24.dp),
        )
        Text(
            text = if (playing) "Pause loop" else "Play loop",
            style = MaterialTheme.typography.labelLarge,
            color = color,
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
        modifier = modifier,
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
            fontSize = 46.sp,
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
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
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
    val durationMs = trace?.let { it.hopCount * it.hopDurationMs } ?: 0f
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
                .height(if (capture == null) 156.dp else 190.dp)
                .pointerInput(durationMs) {
                    if (durationMs <= 0f) return@pointerInput
                    awaitEachGesture {
                        onScrubStart()
                        try {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            fun seek(x: Float) {
                                onScrub((x / size.width * durationMs).coerceIn(0f, durationMs))
                            }
                            seek(down.position.x)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                seek(change.position.x)
                            }
                        } finally {
                            onScrubEnd()
                        }
                    }
                },
        ) {
            if (capture == null || trace == null || peak <= 0f) {
                drawGuide(guideText, guideColor)
                return@Canvas
            }
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
            if (capture != null && durationMs > 0f) {
                Text(
                    text = "${formatScrubTime(playheadMs.coerceIn(0f, durationMs))} / " +
                        formatScrubTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
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

private fun formatScrubTime(ms: Float): String =
    "%.2fs".format(ms.coerceAtLeast(0f) / 1000f)

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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
