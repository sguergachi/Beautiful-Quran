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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

private val GlintGold = Color(0xFFF8E9BE)

/**
 * Reciter-signature workbench: capture a word, mark the held-note window,
 * sculpt its envelope, and tune this reciter's detector against the same
 * PCM. The waveform is the scope; the knobs are live.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TarjiLabScreen(
    viewModel: TarjiLabViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
        LabUtilities(
            ui = ui,
            viewModel = viewModel,
            context = context,
            onImport = {
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            },
        )

        if (ui.isLoading) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
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
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                if (ui.capture != null) {
                    PreviewAction(
                        playing = ui.previewPlaying,
                        onClick = viewModel::togglePreview,
                    )
                    Spacer(Modifier.height(6.dp))
                    ToolRow(ui.tool, viewModel::setTool)
                    LabelRow(ui.expectation.kind, viewModel)
                    if (ui.expectation.envelope.isNotEmpty()) {
                        WordAction(
                            "Clear shape",
                            viewModel::clearEnvelope,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = ui.sampleNotes,
                        onValueChange = viewModel::updateSampleNotes,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines = 3,
                        decorationBox = { field ->
                            Box(Modifier.padding(vertical = 4.dp)) {
                                if (ui.sampleNotes.isEmpty()) {
                                    Text(
                                        text = "Note this reciter's room, mic, or why this hold matters",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    )
                                }
                                field()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.capturing) CaptureProgress(ui.captureProgress)
                ui.captureError?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        WordAction(
                            label = "Retry muted",
                            onClick = viewModel::retryCapture,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                ui.note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
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
                val reciter = ui.sampleReciterName ?: ui.reciter.name
                Text(
                    text = "$reciter · ${ui.surahName} ${ui.ayah}  w${ui.wordPosition}",
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
            PreviewWord(ui, playheadMs, Modifier.fillMaxWidth().height(86.dp))
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
private fun LabUtilities(
    ui: TarjiLabViewModel.TarjiLabUiState,
    viewModel: TarjiLabViewModel,
    context: Context,
    onImport: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 2.dp),
    ) {
        WordAction("Import", onImport, MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.capture != null) {
            WordAction(
                "Export",
                { viewModel.exportSample(context) },
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WordAction("Reset reciter", viewModel::resetKnobs, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CaptureProgress(progress: Float) {
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(
            text = "Muted capture ${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = active,
        )
        Canvas(Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp)) {
            drawLine(
                color = track,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = active,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width * progress.coerceIn(0f, 1f), size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun PreviewAction(playing: Boolean, onClick: () -> Unit) {
    val color = if (playing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.quietClickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Pause hold" else "Play hold",
            tint = color,
            modifier = Modifier.padding(end = 4.dp).width(24.dp),
        )
        Text(
            text = if (playing) "Pause hold" else "Play hold",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun ToolRow(tool: TarjiLabTool, onTool: (TarjiLabTool) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TarjiLabTool.entries.forEach { item ->
            val selected = item == tool
            Text(
                text = when (item) {
                    TarjiLabTool.LISTEN -> "Listen"
                    TarjiLabTool.HOLD -> "Hold"
                    TarjiLabTool.SHAPE -> "Shape"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .quietClickable { onTool(item) }
                    .padding(vertical = 4.dp),
            )
        }
    }
    Text(
        text = when (tool) {
            TarjiLabTool.LISTEN -> "Scrub the waveform. Play loops the hold."
            TarjiLabTool.HOLD -> "Drag the gold edges — or the band — to mark the held note."
            TarjiLabTool.SHAPE -> "Draw over the hold to sculpt this reciter's envelope."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabelRow(
    kind: TarjiExpectationKind,
    viewModel: TarjiLabViewModel,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        WordAction(
            "Has vibrato",
            viewModel::labelHold,
            if (kind == TarjiExpectationKind.PULSES) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        WordAction(
            "Still",
            viewModel::labelStill,
            if (kind == TarjiExpectationKind.NO_SHIMMER) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun WordAction(label: String, onClick: () -> Unit, color: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.quietClickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun PreviewWord(
    ui: TarjiLabViewModel.TarjiLabUiState,
    playheadMs: Float,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
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

@Composable
private fun WaveformPanel(
    ui: TarjiLabViewModel.TarjiLabUiState,
    playheadMs: Float,
    viewModel: TarjiLabViewModel,
    modifier: Modifier = Modifier,
) {
    val capture = ui.capture
    val trace = ui.trace
    val waveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val envColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val shapeColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val durationMs = capture?.let { it.hopCount * it.hopContentDurationMs() } ?: 0f
    val window = ui.expectation.window
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
                .height(if (capture == null) 156.dp else 200.dp)
                .pointerInput(durationMs, ui.tool) {
                    if (durationMs <= 0f) return@pointerInput
                    val slop = 28.dp.toPx()
                    awaitEachGesture {
                        val canvasW = size.width.toFloat()
                        val canvasH = size.height.toFloat()
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        down.consume()
                        when (ui.tool) {
                            TarjiLabTool.LISTEN -> {
                                viewModel.beginPreviewScrub()
                                try {
                                    fun seek(x: Float) {
                                        viewModel.seekPreviewTo(canvasMs(x, canvasW, durationMs))
                                    }
                                    seek(down.position.x)
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        seek(change.position.x)
                                    }
                                } finally {
                                    viewModel.endPreviewScrub()
                                }
                            }
                            TarjiLabTool.HOLD -> {
                                val current = viewModel.ui.value.expectation.window
                                    ?: TarjiHoldWindow(0f, durationMs)
                                val hit = hitHoldWindow(
                                    down.position.x, canvasW, current, durationMs, slop,
                                )
                                val origin = canvasMs(down.position.x, canvasW, durationMs)
                                if (hit == null) {
                                    viewModel.setHoldWindow(TarjiHoldWindow.of(origin, origin, durationMs))
                                }
                                var last = origin
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    val at = canvasMs(change.position.x, canvasW, durationMs)
                                    val live = viewModel.ui.value.expectation.window ?: current
                                    val next = when (hit) {
                                        TarjiCanvasHit.START -> live.moveStart(at, durationMs)
                                        TarjiCanvasHit.END -> live.moveEnd(at, durationMs)
                                        TarjiCanvasHit.BODY -> live.translate(at - last, durationMs)
                                        null -> TarjiHoldWindow.of(origin, at, durationMs)
                                    }
                                    last = at
                                    viewModel.setHoldWindow(next)
                                }
                            }
                            TarjiLabTool.SHAPE -> {
                                viewModel.paintEnvelopeAt(
                                    down.position.x, down.position.y, canvasW, canvasH,
                                )
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    viewModel.paintEnvelopeAt(
                                        change.position.x, change.position.y, canvasW, canvasH,
                                    )
                                }
                            }
                        }
                    }
                },
        ) {
            if (capture == null || peak <= 0f) {
                drawGuide("Capture a word to see its waveform.", guideColor)
                return@Canvas
            }

            window?.let { hold ->
                val left = hold.startMs / durationMs * size.width
                val right = hold.endMs / durationMs * size.width
                drawRect(
                    GlintGold.copy(alpha = 0.12f),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
                drawHandle(left, GlintGold)
                drawHandle(right, GlintGold)
            }

            trace?.reverberatingSpan?.let { span ->
                val left = hopX(span.first.toFloat(), trace, size.width)
                val right = hopX((span.last + 1).toFloat(), trace, size.width)
                drawRect(
                    GlintGold.copy(alpha = 0.08f),
                    topLeft = Offset(left, size.height * 0.08f),
                    size = Size(right - left, size.height * 0.08f),
                )
            }

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

            if (trace != null) {
                var lastEnv: Offset? = null
                for (i in trace.firstAnalysisHop until trace.hopCount) {
                    val x = hopX(i + 0.5f, trace, size.width)
                    val y = size.height * 0.5f - (trace.envRms[i] / peak) * size.height * 0.42f
                    lastEnv?.let { drawLine(envColor, it, Offset(x, y), strokeWidth = 1.2f) }
                    lastEnv = Offset(x, y)
                }
            }

            val envelope = ui.expectation.envelope
            if (envelope.isNotEmpty() && durationMs > 0f) {
                var last: Offset? = null
                for (i in envelope.indices) {
                    val x = (i + 0.5f) / envelope.size * size.width
                    val y = size.height * (1f - envelope[i].coerceIn(0f, 1f) * 0.84f - 0.08f)
                    last?.let {
                        drawLine(shapeColor, it, Offset(x, y), strokeWidth = 2f, cap = StrokeCap.Round)
                    }
                    last = Offset(x, y)
                }
            }

            if (playheadMs >= 0f) {
                val x = (playheadMs / durationMs) * size.width
                drawLine(
                    Color.White.copy(alpha = 0.85f),
                    Offset(x, 4f),
                    Offset(x, size.height - 8f),
                    strokeWidth = 2f,
                )
            }
        }
        ScopeReadout(ui, playheadMs, durationMs)
    }
}

private fun DrawScope.drawHandle(x: Float, color: Color) {
    drawLine(color.copy(alpha = 0.85f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    drawCircle(color, radius = 6f, center = Offset(x, 10f))
    drawCircle(color, radius = 6f, center = Offset(x, size.height - 10f))
}

@Composable
private fun ScopeReadout(
    ui: TarjiLabViewModel.TarjiLabUiState,
    playheadMs: Float,
    durationMs: Float,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    val window = ui.expectation.window
    val comparison = ui.trace?.let { compareTarjiExpectation(ui.expectation, it) }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
    ) {
        Text(
            text = if (durationMs > 0f) {
                "${formatScrubTime(playheadMs.coerceIn(0f, durationMs))} / ${formatScrubTime(durationMs)}"
            } else {
                "AUTOMATIC MUTED CAPTURE"
            },
            style = MaterialTheme.typography.labelSmall,
            color = quiet,
        )
        Text(
            text = when {
                ui.analyzing -> "ANALYZING…"
                window != null ->
                    "HOLD ${formatScrubTime(window.startMs)}–${formatScrubTime(window.endMs)}"
                else -> "HOLD —"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (ui.analyzing) MaterialTheme.colorScheme.primary else quiet,
        )
    }
    comparisonLine(ui, comparison)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = quiet,
        )
    }
}

private fun comparisonLine(
    ui: TarjiLabViewModel.TarjiLabUiState,
    comparison: TarjiExpectationComparison?,
): String? {
    val kind = ui.expectation.kind
    if (comparison == null || kind == TarjiExpectationKind.UNLABELED) {
        return ui.trace?.reverberatingSpan?.let {
            "Detector hears a hold · label it Has vibrato or Still"
        }
    }
    if (kind == TarjiExpectationKind.NO_SHIMMER) {
        return if (comparison.detectedStartMs == null) {
            "Detector agrees: still."
        } else {
            "Detector disagrees: hears a hold at ${formatScrubTime(comparison.detectedStartMs)}."
        }
    }
    val parts = buildList {
        comparison.detectedStartMs?.let { start ->
            val end = comparison.detectedEndMs
            add(
                if (end != null) {
                    "detector ${formatScrubTime(start)}–${formatScrubTime(end)}"
                } else {
                    "detector ${formatScrubTime(start)}"
                },
            )
        }
        comparison.startErrorMs?.let { add("start ${formatSignedMs(it)}") }
        comparison.endErrorMs?.let { add("end ${formatSignedMs(it)}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
        ?: "Detector finds no hold yet."
}

private fun formatSignedMs(ms: Float): String =
    "${if (ms >= 0f) "+" else "−"}${abs(ms).roundToInt()} ms"

private fun formatScrubTime(ms: Float): String =
    "%.2fs".format(ms.coerceAtLeast(0f) / 1000f)

private fun DrawScope.hopX(hop: Float, trace: TarjiLabTrace, width: Float): Float {
    val duration = trace.hopCount * trace.hopDurationMs
    if (duration <= 0f) return 0f
    return (hop * trace.hopDurationMs / duration) * width
}

private fun DrawScope.drawGuide(text: String, color: Color) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 30f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f, size.height / 2f, paint)
}

@Composable
private fun KnobsPanel(
    ui: TarjiLabViewModel.TarjiLabUiState,
    onKnob: ((TarjiLabKnobs) -> TarjiLabKnobs) -> Unit,
    onDepth: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobs = ui.knobs
    val reciter = ui.sampleReciterName ?: ui.reciter?.name ?: "this reciter"
    Column(modifier = modifier) {
        Text(
            text = reciter,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Signature knobs — they persist for this reciter.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LabSlider("Glint depth", InkEngine.tuning.glintResonanceDepth, 0f..1f, onChange = onDepth)
        LabSlider("Hold min ms", knobs.holdMinMs, 100f..1_200f) { v ->
            onKnob { k -> k.copy(holdMinMs = v) }
        }
        LabSlider("Wobble min Hz", knobs.minTremoloHz, 1.5f..5f) { v ->
            onKnob { k -> k.copy(minTremoloHz = v) }
        }
        LabSlider("Wobble max Hz", knobs.maxTremoloHz, 1.5f..10f) { v ->
            onKnob { k -> k.copy(maxTremoloHz = v) }
        }
        LabSlider("Min depth", knobs.minTremoloDepth, 0.01f..0.25f) { v ->
            onKnob { k -> k.copy(minTremoloDepth = v) }
        }
        LabSlider("Regularity", knobs.minPeriodicity, 0.15f..0.85f) { v ->
            onKnob { k -> k.copy(minPeriodicity = v) }
        }
        LabSlider("Pitch wander", knobs.maxPitchDrift, 0.04f..0.30f) { v ->
            onKnob { k -> k.copy(maxPitchDrift = v) }
        }
        LabSlider("Attack ms", knobs.attackMs, 50f..600f) { v ->
            onKnob { k -> k.copy(attackMs = v) }
        }
        LabSlider("Release ms", knobs.releaseMs, 100f..2_000f) { v ->
            onKnob { k -> k.copy(releaseMs = v) }
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int? = null,
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
            modifier = Modifier.width(108.dp),
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
            text = when (decimals ?: if (range.endInclusive - range.start <= 1f) 2 else 0) {
                0 -> value.roundToInt().toString()
                1 -> "%.1f".format(value)
                else -> "%.2f".format(value)
            },
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
