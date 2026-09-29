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
import androidx.compose.material.icons.rounded.Replay
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.beautifulquran.ui.theme.QuranTheme

private val GlintGold = Color(0xFFF8E9BE)

/**
 * Live detector workbench: loop real PCM and tune the algorithm while its
 * measured output drives both the scope and the word.
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

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.onExit()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onExit()
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
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                WordRow(ui, viewModel, playheadMs)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(196.dp)
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
                TransportRow(ui, viewModel)
                DetectorReadout(ui, playheadMs)
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
                                        color = QuranTheme.ink.quiet,
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
        PreviewWord(
            ui,
            playheadMs,
            36.sp,
            Modifier.weight(1f).height(56.dp),
        )
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
private fun CaptureProgress(progress: Float, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val track = QuranTheme.ink.hairline
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
    Column(Modifier.fillMaxWidth()) {
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
            Icon(
                imageVector = Icons.Rounded.Replay,
                contentDescription = "Rewind to loop start",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(44.dp)
                    .quietClickable(enabled = ui.capture != null, onClick = viewModel::rewindPreview)
                    .padding(10.dp),
            )
            PreviewButton(
                playing = holdPlaying,
                enabled = ui.capture != null,
                contentDescription = if (holdPlaying) "Pause loop" else "Play loop range",
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
            Text(item.label, style = MaterialTheme.typography.labelSmall, color = ink)
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
        TarjiLabTool.LISTEN -> "Seek"
        TarjiLabTool.HOLD -> "Loop range"
    }

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
        val head = if (playheadMs >= 0f) playheadMs else ui.previewPositionMs
        val glow = ui.trace?.let { tracePointAt(it, head) }
        val resonance = InkEngine.glintResonance(
            holding = glow?.reverberating == true,
            tremolo = glow?.tremolo ?: 0f,
            tremoloGain = glow?.gain ?: 0f,
            depth = InkEngine.GLINT_RESONANCE_DEPTH,
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
            color = GlintGold,
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
    val waveColor = QuranTheme.ink.quiet
    val shapeColor = MaterialTheme.colorScheme.primary
    val guideColor = QuranTheme.ink.muted
    val durationMs = capture?.let { it.hopCount * it.hopContentDurationMs() } ?: 0f
    val view = if (ui.view.spanMs > 1f) ui.view else TarjiViewWindow.fit(durationMs)
    val window = ui.expectation.window
    val detectorEnvelope = remember(trace) { trace?.let(::tarjiPulseWave).orEmpty() }
    val acceptedEnvelope = remember(trace) { trace?.let(::tarjiAcceptedPulseWave).orEmpty() }
    val peak = remember(capture) {
        capture?.pcm?.let { p ->
            var m = 0f
            for (v in p) m = max(m, abs(v))
            m
        } ?: 0f
    }
    Canvas(
        modifier.clipToBounds()
            .semantics {
                contentDescription = "Audio waveform and detector output"
            }
            .pointerInput(capture, ui.tool) {
                    if (durationMs <= 0f) return@pointerInput
                    val slop = 28.dp.toPx()
                    awaitEachGesture {
                        val canvasW = size.width.toFloat()
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
                                }
                            }
                        }
                        if (!pinching && !tooling) {
                            if (ui.tool == TarjiLabTool.LISTEN) {
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

            trace?.let {
                for (i in it.reverberating.indices) {
                    if (!it.reverberating[i]) continue
                    val left = viewX(i * it.hopDurationMs, size.width, view)
                    val right = viewX((i + 1) * it.hopDurationMs, size.width, view)
                    drawRect(GlintGold.copy(alpha = 0.55f), Offset(left, size.height - 6f),
                        Size((right - left).coerceAtLeast(0f), 4f))
                }
            }

            window?.let { hold ->
                val left = viewX(hold.startMs, size.width, view)
                val right = viewX(hold.endMs, size.width, view)
                drawRect(
                    GlintGold.copy(alpha = 0.07f),
                    topLeft = Offset(left, 0f),
                    size = Size((right - left).coerceAtLeast(0f), size.height),
                )
                drawHandle(left, GlintGold.copy(alpha = 0.6f))
                drawHandle(right, GlintGold.copy(alpha = 0.6f))
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

            // Both curves use the voice's time axis. The candidate remains
            // visible when a threshold rejects it; gold is the reader output.
            fun drawPulse(envelope: List<Float>, color: Color, width: Float, acceptedOnly: Boolean) {
                var last: Offset? = null
                for (i in envelope.indices) {
                    val x = viewX((i + 0.5f) / envelope.size * durationMs, size.width, view)
                    if (x < -2f || x > size.width + 2f ||
                        (acceptedOnly && (trace == null || trace.gain[i] <= 0.001f))) {
                        last = null
                        continue
                    }
                    val point = Offset(x, mid - envelope[i] * amp)
                    last?.let { drawLine(color, it, point, strokeWidth = width, cap = StrokeCap.Round) }
                    last = point
                }
            }
            drawPulse(detectorEnvelope, shapeColor, 1.5.dp.toPx(), acceptedOnly = false)
            drawPulse(acceptedEnvelope, GlintGold, 2.5.dp.toPx(), acceptedOnly = true)
            if (detectorEnvelope.isNotEmpty()) {
                val head = if (playheadMs >= 0f) playheadMs else ui.previewPositionMs
                val hop = (head / durationMs * detectorEnvelope.size).toInt()
                    .coerceIn(0, detectorEnvelope.lastIndex)
                drawCircle(shapeColor, radius = 3.dp.toPx(), center = Offset(
                    viewX(head, size.width, view), mid - detectorEnvelope[hop] * amp,
                ))
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

/** Values at the same content position as the audible loop. */
@Composable
private fun DetectorReadout(ui: TarjiLabViewModel.TarjiLabUiState, playheadMs: Float) {
    val point = ui.trace?.let { tracePointAt(it, playheadMs.coerceAtLeast(0f)) }
    val status = when {
        ui.analyzing -> "Updating detector…"
        point == null -> "Waiting for audio"
        point.reverberating -> "Hold · %.1f Hz · gain %.2f".format(point.rateHz, point.gain)
        else -> "Measured pulse · no accepted hold"
    }
    Text(
        text = "${formatLabClock(playheadMs, ui.previewDurationMs)} · $status",
        style = MaterialTheme.typography.labelSmall,
        color = QuranTheme.ink.secondary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
    onReset: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobs = ui.knobs
    Column(modifier = modifier) {
        Text(
            text = "Start with a hold you can hear. If the pulse stays flat, lower Min depth or Regularity. If speech triggers it, raise those or Hold min. Then adjust Attack and Release. The pulse overlays the voice. The fine curve is measured modulation; thick gold shows what these settings let through. The dot follows the audible position.",
            style = MaterialTheme.typography.labelSmall,
            color = QuranTheme.ink.muted,
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
        LabSlider("Hold min ms", knobs.holdMinMs, 100f..1_200f, help = "How long a note must stay steady. Lower catches shorter holds sooner; higher rejects brief syllables.") { v ->
            onKnob { k -> k.copy(holdMinMs = v) }
        }
        LabSlider("Wobble min Hz", knobs.minTremoloHz, 1.5f..5f, help = "Slowest accepted pulse rate (cycles per second). Lower to include slower vibrato; raise to reject slow volume swells.", decimals = 1) { v ->
            onKnob { k -> k.copy(minTremoloHz = v, maxTremoloHz = maxOf(v, k.maxTremoloHz)) }
        }
        LabSlider("Wobble max Hz", knobs.maxTremoloHz, 1.5f..10f, help = "Fastest accepted pulse rate. Raise if a fast vibrato is missed; lower if rapid roughness triggers it. The band filters the voice; it does not invent a pulse rate.", decimals = 1) { v ->
            onKnob { k -> k.copy(maxTremoloHz = v, minTremoloHz = minOf(v, k.minTremoloHz)) }
        }
        LabSlider("Min depth", knobs.minTremoloDepth, 0.01f..0.25f, help = "Minimum strength of the wobble. Lower reveals subtle vibrato; higher rejects weak fluctuations and noise.") { v ->
            onKnob { k -> k.copy(minTremoloDepth = v) }
        }
        LabSlider("Regularity", knobs.minPeriodicity, 0.15f..0.85f, help = "How evenly the wobble must repeat. Lower accepts uneven vibrato; higher requires a cleaner, steadier rhythm.") { v ->
            onKnob { k -> k.copy(minPeriodicity = v) }
        }
        LabSlider("Pitch wander", knobs.maxPitchDrift, 0.04f..0.30f, help = "How far the note may drift while still counting as a hold. Raise for sliding notes; lower if changing syllables are mistaken for one held note.") { v ->
            onKnob { k -> k.copy(maxPitchDrift = v) }
        }
        LabSlider("Attack ms", knobs.attackMs, 50f..600f, help = "How slowly a detected pulse gains strength. Lower makes it appear sooner; higher softens its entrance.") { v ->
            onKnob { k -> k.copy(attackMs = v) }
        }
        LabSlider("Release ms", knobs.releaseMs, 100f..2_000f, help = "How long pulse strength bridges a brief detection gap. Higher smooths flicker; lower clears it sooner. The actual end of a hold still fades quickly.") { v ->
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
    help: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
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
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall,
            color = QuranTheme.ink.quiet,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
