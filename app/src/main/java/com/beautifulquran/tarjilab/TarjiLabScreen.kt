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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.ui.reader.InkEngine
import com.beautifulquran.ui.theme.ArabicWordStyle
import com.beautifulquran.ui.theme.InkSpotChoiceRow
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
        LabHeader(ui = ui, onBack = onBack)

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
            var tuningOpen by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f).fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            WordRow(ui, viewModel, playheadMs, expanded = !tuningOpen)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (tuningOpen) Modifier.height(168.dp)
                        else Modifier.weight(1f).heightIn(min = 220.dp),
                    )
                    .systemGestureExclusion(),
            ) {
                WaveformPanel(
                    ui = ui,
                    playheadMs = playheadMs,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
                if (ui.capturing) {
                    CaptureProgress(
                        ui.captureProgress,
                        Modifier.align(Alignment.TopStart).padding(top = 4.dp),
                    )
                }
                if (ui.holdEditing) {
                    ui.expectation.window?.let { hold ->
                        Text(
                            text = formatLabRange(hold.startMs, hold.endMs),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFeatureSettings = "'kern' 1, 'tnum' 1, 'lnum' 1",
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        )
                    }
                }
                val captureMs = ui.capture?.let { it.hopCount * it.hopContentDurationMs() } ?: 0f
                if (captureMs > 0f && ui.view.spanMs + 1f < captureMs) {
                    Text(
                        text = "Fit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .quietClickable(onClick = viewModel::fitView)
                            .padding(8.dp),
                    )
                }
            }
            StatusSlot(
                error = ui.captureError,
                note = ui.note,
                onRetry = viewModel::retryCapture,
            )
            ResetSlot(
                visible = ui.tool == TarjiLabTool.SHAPE && ui.trace != null,
                onReset = viewModel::resetEnvelopeToKnobs,
            )
            TransportRow(ui, viewModel)
            if (!tuningOpen) {
                WaveformLegend(
                    tool = ui.tool,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 4.dp),
                )
            }
            Text(
                text = if (tuningOpen) "Hide tuning" else "Tune",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .quietClickable { tuningOpen = !tuningOpen }
                    .padding(vertical = 8.dp),
            )
            if (tuningOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    KnobsPanel(
                        ui = ui,
                        onKnob = viewModel::updateKnobs,
                        onDepth = { depth ->
                            InkEngine.tuning = InkEngine.tuning.copy(glintResonanceDepth = depth)
                        },
                        onReset = viewModel::resetKnobs,
                        onExport = { viewModel.exportSample(context) },
                        onImport = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    BasicTextField(
                        value = ui.sampleNotes,
                        onValueChange = viewModel::updateSampleNotes,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines = 2,
                        decorationBox = { field ->
                            Box(Modifier.padding(vertical = 2.dp)) {
                                if (ui.sampleNotes.isEmpty()) {
                                    Text(
                                        text = "Note",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    )
                                }
                                field()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val reciter = ui.sampleReciterName ?: ui.reciter?.name
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(
                text = if (!ui.isLoading && ui.surahName.isNotEmpty()) {
                    "${ui.surahName} ${ui.ayah}"
                } else {
                    "Tarjīʿ Lab"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = reciter ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
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
    expanded: Boolean,
) {
    val type = if (expanded) 48.sp else 28.sp
    val box = if (expanded) 96.dp else 48.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "‹",
            fontSize = if (expanded) 32.sp else 28.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = viewModel::prevWord)
                .padding(horizontal = 18.dp, vertical = 4.dp),
        )
        PreviewWord(
            ui,
            playheadMs,
            type,
            Modifier.weight(1f).height(box),
        )
        Text(
            text = "›",
            fontSize = if (expanded) 32.sp else 28.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = viewModel::nextWord)
                .padding(horizontal = 18.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CaptureProgress(progress: Float, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Column(modifier.fillMaxWidth()) {
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
private fun TransportRow(
    ui: TarjiLabViewModel.TarjiLabUiState,
    viewModel: TarjiLabViewModel,
) {
    val holdPlaying = ui.previewPlaying && ui.previewScope == TarjiPreviewScope.HOLD
    val wordPlaying = ui.previewPlaying && ui.previewScope == TarjiPreviewScope.WORD
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkSpotChoiceRow(
                entries = TarjiPreviewSpeed.entries,
                selected = ui.previewSpeed,
                onSelect = viewModel::setPreviewSpeed,
                spacing = 0.dp,
                contentPadding = 8.dp,
            ) { speed, _, ink ->
                Text(
                    text = speed.mark,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    modifier = Modifier.semantics { contentDescription = speed.mark },
                )
            }
            PreviewButton(
                playing = holdPlaying,
                enabled = ui.capture != null,
                contentDescription = if (holdPlaying) "Pause hold" else "Play hold",
                onClick = viewModel::togglePreview,
            )
            PreviewButton(
                playing = wordPlaying,
                enabled = ui.capture != null,
                contentDescription = if (wordPlaying) "Pause word" else "Play whole word",
                onClick = viewModel::toggleWordPreview,
                wholeWord = true,
            )
        }
        InkSpotChoiceRow(
            entries = TarjiLabTool.entries,
            selected = ui.tool,
            onSelect = viewModel::setTool,
            spacing = 0.dp,
            contentPadding = 8.dp,
        ) { item, _, ink ->
            ModeIcon(
                item,
                ink,
                Modifier
                    .size(22.dp)
                    .semantics { contentDescription = item.label },
            )
        }
    }
}

@Composable
private fun ResetSlot(visible: Boolean, onReset: () -> Unit) {
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier.fillMaxWidth().height(36.dp),
    ) {
        if (visible) {
            Text(
                text = "Reset",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .quietClickable(onClick = onReset)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PreviewButton(
    playing: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    wholeWord: Boolean = false,
) {
    val color = if (playing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val tap = Modifier
        .size(44.dp)
        .quietClickable(enabled = enabled, onClick = onClick)
        .padding(8.dp)
        .semantics { this.contentDescription = contentDescription }
    if (playing) {
        Icon(
            imageVector = Icons.Rounded.Pause,
            contentDescription = contentDescription,
            tint = color,
            modifier = tap,
        )
    } else if (wholeWord) {
        Box(modifier = tap, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawPlayWordBars(color) }
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = contentDescription,
                tint = color,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = contentDescription,
            tint = color,
            modifier = tap,
        )
    }
}

private val TarjiLabTool.label: String
    get() = when (this) {
        TarjiLabTool.LISTEN -> "Listen"
        TarjiLabTool.HOLD -> "Hold"
        TarjiLabTool.SHAPE -> "Shape"
    }

@Composable
private fun ModeIcon(tool: TarjiLabTool, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        when (tool) {
            TarjiLabTool.LISTEN -> drawListenIcon(color)
            TarjiLabTool.HOLD -> drawHoldIcon(color)
            TarjiLabTool.SHAPE -> drawShapeIcon(color)
        }
    }
}

/** Quiet word-bars behind the same-size Play glyph. */
private fun DrawScope.drawPlayWordBars(color: Color) {
    val mid = size.height * 0.50f
    val stroke = minOf(size.width, size.height) * 0.10f
    val bars = floatArrayOf(0.36f, 0.72f, 0.50f, 0.28f)
    bars.forEachIndexed { i, amp ->
        val x = size.width * (0.10f + i * 0.26f)
        val half = size.height * amp * 0.42f
        drawLine(
            color.copy(alpha = color.alpha * 0.42f),
            Offset(x, mid - half),
            Offset(x, mid + half),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Mini scope: waveform bars with a playhead through them. */
private fun DrawScope.drawListenIcon(color: Color) {
    val mid = size.height * 0.52f
    val bars = floatArrayOf(0.28f, 0.62f, 0.44f, 0.78f, 0.36f)
    val stroke = size.minDimension * 0.08f
    bars.forEachIndexed { i, amp ->
        val x = size.width * (0.16f + i * 0.15f)
        val half = size.height * amp * 0.38f
        drawLine(
            color, Offset(x, mid - half), Offset(x, mid + half),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
    }
    val playX = size.width * 0.52f
    drawLine(
        color, Offset(playX, size.height * 0.08f), Offset(playX, size.height * 0.92f),
        strokeWidth = stroke * 0.85f, cap = StrokeCap.Round,
    )
}

/** The two gold handles that mark a hold. */
private fun DrawScope.drawHoldIcon(color: Color) {
    val stroke = size.minDimension * 0.09f
    val top = size.height * 0.12f
    val bot = size.height * 0.88f
    val band = color.copy(alpha = color.alpha * 0.22f)
    drawRect(
        band,
        topLeft = Offset(size.width * 0.28f, top),
        size = Size(size.width * 0.44f, bot - top),
    )
    for (x in floatArrayOf(size.width * 0.28f, size.width * 0.72f)) {
        drawLine(color, Offset(x, top), Offset(x, bot), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(color, radius = stroke * 1.15f, center = Offset(x, top))
        drawCircle(color, radius = stroke * 1.15f, center = Offset(x, bot))
    }
}

/** A hand-shaped envelope — the sculpted signature. */
private fun DrawScope.drawShapeIcon(color: Color) {
    val path = Path().apply {
        moveTo(size.width * 0.08f, size.height * 0.72f)
        cubicTo(
            size.width * 0.28f, size.height * 0.72f,
            size.width * 0.32f, size.height * 0.22f,
            size.width * 0.50f, size.height * 0.22f,
        )
        cubicTo(
            size.width * 0.68f, size.height * 0.22f,
            size.width * 0.72f, size.height * 0.62f,
            size.width * 0.92f, size.height * 0.58f,
        )
    }
    drawPath(
        path,
        color,
        style = Stroke(
            width = size.minDimension * 0.11f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

@Composable
private fun WordAction(
    label: String,
    onClick: () -> Unit,
    color: Color,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .quietClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun PreviewWord(
    ui: TarjiLabViewModel.TarjiLabUiState,
    playheadMs: Float,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        val hopMs = ui.capture?.hopContentDurationMs() ?: ui.trace?.hopDurationMs ?: 0f
        val head = if (playheadMs >= 0f) playheadMs else ui.previewPositionMs
        val glow = labWordGlow(
            kind = ui.expectation.kind,
            envelope = ui.expectation.envelope,
            trace = ui.trace,
            ms = head,
            hopDurationMs = hopMs,
        )
        val resonance = InkEngine.glintResonance(
            holding = glow.holding,
            tremolo = glow.tremolo,
            tremoloGain = glow.gain,
            enabled = true,
        )
        Canvas(Modifier.fillMaxSize()) {
            val amount = 0.22f * resonance.layerMult + 0.9f * resonance.peak
            if (amount > 0.01f) {
                drawCircle(
                    color = GlintGold.copy(alpha = (amount * 0.55f).coerceIn(0f, 0.75f)),
                    radius = size.minDimension * 0.42f,
                    center = center,
                )
                drawCircle(
                    color = GlintGold.copy(alpha = (amount * 0.3f).coerceIn(0f, 0.45f)),
                    radius = size.minDimension * 0.62f,
                    center = center,
                )
            }
        }
        Text(
            text = ui.wordArabic,
            style = ArabicWordStyle,
            fontSize = fontSize,
            color = GlintGold.copy(alpha = if (glow.holding) 0.96f else 0.72f),
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
    val shapeColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val durationMs = capture?.let { it.hopCount * it.hopContentDurationMs() } ?: 0f
    val view = if (ui.view.spanMs > 1f) ui.view else TarjiViewWindow.fit(durationMs)
    val window = ui.expectation.window
    val peak = remember(capture) {
        capture?.pcm?.let { p ->
            var m = 0f
            for (v in p) m = max(m, abs(v))
            m
        } ?: 0f
    }
    Canvas(
        modifier
            .semantics {
                contentDescription = if (holdLifeAlive(ui.expectation.kind)) "Vibrato" else "Still"
            }
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
                        fun liveView(): TarjiViewWindow {
                            val v = viewModel.ui.value.view
                            return if (v.spanMs > 1f) v else TarjiViewWindow.fit(durationMs)
                        }
                        fun at(x: Float) = canvasMs(x, canvasW, durationMs, liveView())
                        var lastSpan = -1f
                        var lastMidX = down.position.x
                        val opening = currentEvent.changes.filter { it.pressed }
                        if (opening.size >= 2) {
                            lastSpan = (opening[0].position - opening[1].position).getDistance()
                            lastMidX = (opening[0].position.x + opening[1].position.x) / 2f
                        }
                        var pinching = opening.size >= 2
                        var tooling = false
                        var travel = 0f
                        var holdHit: TarjiCanvasHit? = null
                        var holdOrigin = 0f
                        var holdLast = 0f
                        var holdLive: TarjiHoldWindow? = null
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            pressed.forEach { it.consume() }
                            if (pressed.size >= 2) {
                                pinching = true
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val span = (a - b).getDistance()
                                val midX = (a.x + b.x) / 2f
                                if (lastSpan > 1f && span > 1f) {
                                    val zoom = lastSpan / span
                                    if (abs(zoom - 1f) > 0.012f) {
                                        viewModel.zoomViewAt(at(midX), zoom)
                                    } else {
                                        viewModel.panViewBy(at(lastMidX) - at(midX))
                                    }
                                }
                                lastSpan = span
                                lastMidX = midX
                                continue
                            }
                            if (pinching) break
                            val pos = pressed.first().position
                            travel = max(travel, (pos - down.position).getDistance())
                            if (travel <= slop) continue
                            val x = pos.x
                            val y = pos.y
                            if (!tooling) {
                                tooling = true
                                when (ui.tool) {
                                    TarjiLabTool.LISTEN -> {
                                        viewModel.beginPreviewScrub()
                                        viewModel.seekPreviewTo(at(x))
                                    }
                                    TarjiLabTool.HOLD -> {
                                        viewModel.beginHoldEdit()
                                        val current = viewModel.ui.value.expectation.window
                                            ?: TarjiHoldWindow(0f, durationMs)
                                        holdHit = hitHoldWindow(
                                            down.position.x, canvasW, current, durationMs, slop, liveView(),
                                        )
                                        holdOrigin = at(down.position.x)
                                        holdLast = at(x)
                                        holdLive = current
                                    }
                                    TarjiLabTool.SHAPE ->
                                        viewModel.paintEnvelopeAt(x, y, canvasW, canvasH)
                                }
                            } else {
                                when (ui.tool) {
                                    TarjiLabTool.LISTEN -> viewModel.seekPreviewTo(at(x))
                                    TarjiLabTool.HOLD -> {
                                        val live = holdLive ?: return@awaitEachGesture
                                        val next = holdDrag(
                                            holdHit, holdOrigin, holdLast, at(x), live, durationMs,
                                        )
                                        holdLast = at(x)
                                        holdLive = next
                                        viewModel.setHoldWindow(
                                            next,
                                            playheadForHoldDrag(holdHit, next),
                                        )
                                    }
                                    TarjiLabTool.SHAPE ->
                                        viewModel.paintEnvelopeAt(x, y, canvasW, canvasH)
                                }
                            }
                        }
                        if (!pinching && !tooling) {
                            val hold = viewModel.ui.value.expectation.window
                            val hit = hold?.let {
                                hitHoldWindow(
                                    down.position.x, canvasW, it, durationMs, slop, liveView(),
                                )
                            }
                            if (hit == TarjiCanvasHit.BODY) viewModel.toggleHoldLife()
                            else if (ui.tool == TarjiLabTool.LISTEN) {
                                viewModel.beginPreviewScrub()
                                viewModel.seekPreviewTo(at(down.position.x))
                                viewModel.endPreviewScrub()
                            }
                        }
                        if (ui.tool == TarjiLabTool.LISTEN && tooling) viewModel.endPreviewScrub()
                        if (ui.tool == TarjiLabTool.HOLD && tooling) viewModel.endHoldEdit()
                    }
                },
        ) {
            if (capture == null || peak <= 0f) {
                drawGuide("Capture a word to see its waveform.", guideColor)
                return@Canvas
            }

            if (ui.tool == TarjiLabTool.HOLD) {
                trace?.reverberatingSpan?.let { span ->
                    val left = viewX(span.first * trace.hopDurationMs, size.width, view)
                    val right = viewX((span.last + 1) * trace.hopDurationMs, size.width, view)
                    drawRect(
                        GlintGold.copy(alpha = 0.06f),
                        topLeft = Offset(left, 0f),
                        size = Size((right - left).coerceAtLeast(0f), size.height),
                    )
                }
            }

            window?.let { hold ->
                val left = viewX(hold.startMs, size.width, view)
                val right = viewX(hold.endMs, size.width, view)
                val alive = holdLifeAlive(ui.expectation.kind)
                drawRect(
                    GlintGold.copy(alpha = if (alive) 0.18f else 0.05f),
                    topLeft = Offset(left, 0f),
                    size = Size((right - left).coerceAtLeast(0f), size.height),
                )
                drawHandle(left, GlintGold.copy(alpha = if (alive) 0.85f else 0.4f))
                drawHandle(right, GlintGold.copy(alpha = if (alive) 0.85f else 0.4f))
            }

            val slice = pcmSlice(view, durationMs, capture.pcm.size)
            val mid = size.height * 0.5f
            val amp = size.height * 0.42f
            val stride = max(1, slice.count() / (size.width.toInt() * 2).coerceAtLeast(1))
            var last: Offset? = null
            var i = slice.first
            while (i <= slice.last) {
                val t = (i + 0.5f) / capture.pcm.size * durationMs
                val x = viewX(t, size.width, view)
                val y = mid - (capture.pcm[i] / peak) * amp
                last?.let {
                    drawLine(waveColor, it, Offset(x, y), strokeWidth = 1.6f, cap = StrokeCap.Round)
                }
                last = Offset(x, y)
                i += stride
            }

            val envelope = ui.expectation.envelope
            if (
                ui.tool == TarjiLabTool.SHAPE &&
                envelope.isNotEmpty() &&
                durationMs > 0f &&
                holdLifeAlive(ui.expectation.kind)
            ) {
                var last: Offset? = null
                for (i in envelope.indices) {
                    val x = viewX((i + 0.5f) / envelope.size * durationMs, size.width, view)
                    if (x < -2f || x > size.width + 2f) {
                        last = null
                        continue
                    }
                    val y = size.height * (1f - envelope[i].coerceIn(0f, 1f) * 0.84f - 0.08f)
                    last?.let {
                        drawLine(shapeColor, it, Offset(x, y), strokeWidth = 2f, cap = StrokeCap.Round)
                    }
                    last = Offset(x, y)
                }
                val head = if (playheadMs >= 0f) playheadMs else ui.previewPositionMs
                val hx = viewX(head, size.width, view)
                val hop = (head / durationMs * envelope.size).toInt()
                    .coerceIn(0, envelope.lastIndex)
                val hy = size.height * (1f - envelope[hop].coerceIn(0f, 1f) * 0.84f - 0.08f)
                drawCircle(GlintGold, radius = 5f, center = Offset(hx, hy))
            }

            if (playheadMs >= view.startMs && playheadMs <= view.endMs) {
                val x = viewX(playheadMs, size.width, view)
                drawLine(
                    Color.White.copy(alpha = 0.85f),
                    Offset(x, 4f),
                    Offset(x, size.height - 8f),
                    strokeWidth = 2f,
                )
            }
        }
}

private fun DrawScope.drawHandle(x: Float, color: Color) {
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    drawCircle(color, radius = 6f, center = Offset(x, 10f))
    drawCircle(color, radius = 6f, center = Offset(x, size.height - 10f))
}

/** Always the same height so error, note, or silence never move the scope. */
@Composable
private fun StatusSlot(
    error: String?,
    note: String?,
    onRetry: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(28.dp),
    ) {
        val message = error ?: note
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (error != null) {
            WordAction("Retry", onRetry, MaterialTheme.colorScheme.primary)
        }
    }
}

/** Faded key — only the marks the current tool uses. */
@Composable
private fun WaveformLegend(tool: TarjiLabTool, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val voice = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val env = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    val now = Color.White.copy(alpha = 0.38f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(voice, "voice") { drawLegendVoice(it) }
        LegendItem(ink, "hold") { drawLegendHold(it) }
        if (tool == TarjiLabTool.HOLD) {
            LegendItem(ink, "hears") { drawLegendHears(it) }
        }
        if (tool == TarjiLabTool.SHAPE) {
            LegendItem(env, "shape") { drawLegendShape(it) }
        }
        LegendItem(now, "now") { drawLegendNow(it) }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    glyph: DrawScope.(Color) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(22.dp)) { glyph(color) }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private fun DrawScope.drawLegendHold(color: Color) {
    drawRect(
        color.copy(alpha = color.alpha * 0.45f),
        topLeft = Offset(size.width * 0.22f, 0f),
        size = Size(size.width * 0.56f, size.height),
    )
    for (x in floatArrayOf(size.width * 0.22f, size.width * 0.78f)) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawLegendHears(color: Color) {
    drawRect(
        color.copy(alpha = color.alpha * 0.22f),
        topLeft = Offset(size.width * 0.16f, 0f),
        size = Size(size.width * 0.68f, size.height),
    )
}

private fun DrawScope.drawLegendVoice(color: Color) {
    val mid = size.height * 0.5f
    val bars = floatArrayOf(0.28f, 0.7f, 0.42f, 0.82f, 0.34f)
    bars.forEachIndexed { i, amp ->
        val x = size.width * (0.12f + i * 0.18f)
        val half = size.height * amp * 0.4f
        drawLine(color, Offset(x, mid - half), Offset(x, mid + half), strokeWidth = 2f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawLegendShape(color: Color) {
    val path = Path().apply {
        moveTo(size.width * 0.08f, size.height * 0.72f)
        cubicTo(
            size.width * 0.32f, size.height * 0.72f,
            size.width * 0.36f, size.height * 0.22f,
            size.width * 0.55f, size.height * 0.22f,
        )
        cubicTo(
            size.width * 0.74f, size.height * 0.22f,
            size.width * 0.78f, size.height * 0.62f,
            size.width * 0.94f, size.height * 0.56f,
        )
    }
    drawPath(path, color, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawLegendNow(color: Color) {
    drawLine(
        color,
        Offset(size.width * 0.5f, size.height * 0.08f),
        Offset(size.width * 0.5f, size.height * 0.92f),
        strokeWidth = 2f,
        cap = StrokeCap.Round,
    )
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
    onReset: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobs = ui.knobs
    Column(modifier = modifier) {
        Text(
            text = "These change what the detector hears — the faint gold band — not the stroke you draw.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            WordAction("Export", onExport, MaterialTheme.colorScheme.onSurfaceVariant)
            WordAction("Import", onImport, MaterialTheme.colorScheme.onSurfaceVariant)
            WordAction("Reset", onReset, MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
