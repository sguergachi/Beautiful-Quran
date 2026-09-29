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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
                        .height(156.dp)
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
                        onNotes = viewModel::updateSampleNotes,
                        onImport = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
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
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            WordAction("Rewind", viewModel::rewindPreview, QuranTheme.ink.secondary, ui.capture != null)
            WordAction(
                if (ui.previewPlaying) "Pause" else if (ui.tool == TarjiLabTool.HOLD) "Play loop" else "Play word",
                {
                    val scope = if (ui.previewPlaying) ui.previewScope
                        else if (ui.tool == TarjiLabTool.HOLD) TarjiPreviewScope.HOLD else TarjiPreviewScope.WORD
                    if (scope == TarjiPreviewScope.HOLD) viewModel.togglePreview()
                    else viewModel.toggleWordPreview()
                },
                QuranTheme.ink.secondary,
                ui.capture != null,
            )
            Spacer(Modifier.weight(1f))
            InkSpotChoiceRow(
                entries = TarjiPreviewSpeed.entries,
                selected = ui.previewSpeed,
                onSelect = viewModel::setPreviewSpeed,
                spacing = 0.dp,
                contentPadding = 8.dp,
            ) { speed, _, ink ->
                Text(speed.mark, style = MaterialTheme.typography.labelSmall, color = ink)
            }
        }
        InkSpotChoiceRow(
            entries = TarjiLabTool.entries,
            selected = ui.tool,
            onSelect = { tool ->
                viewModel.setTool(tool)
                if (ui.previewPlaying && (tool == TarjiLabTool.HOLD) != (ui.previewScope == TarjiPreviewScope.HOLD)) {
                    if (tool == TarjiLabTool.HOLD) viewModel.togglePreview() else viewModel.toggleWordPreview()
                }
            },
            spacing = 0.dp,
            contentPadding = 8.dp,
        ) { item, _, ink ->
            Text(
                if (item == TarjiLabTool.LISTEN) "Whole word" else "Choose loop",
                style = MaterialTheme.typography.labelSmall,
                color = ink,
            )
        }
        Text(
            if (ui.tool == TarjiLabTool.HOLD) "Drag the two ends to loop the part you hear."
            else "Play repeats this word. Tap the waveform to listen from there.",
            style = MaterialTheme.typography.bodySmall,
            color = QuranTheme.ink.secondary,
        )
    }
}

/** Quiet text action with a separate enabled hit target. */
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
    val guideColor = QuranTheme.ink.muted
    val durationMs = capture?.let { it.hopCount * it.hopContentDurationMs() } ?: 0f
    val view = if (ui.view.spanMs > 1f) ui.view else TarjiViewWindow.fit(durationMs)
    val window = ui.expectation.window.takeIf { ui.tool == TarjiLabTool.HOLD }
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

            window?.let { hold ->
                val left = viewX(hold.startMs, size.width, view)
                val right = viewX(hold.endMs, size.width, view)
                drawRect(
                    GlintGold.copy(alpha = 0.07f),
                    topLeft = Offset(left, 0f),
                    size = Size((right - left).coerceAtLeast(0f), size.height),
                )
                drawHandle(left, guideColor)
                drawHandle(right, guideColor)
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

            var previousPulse: Offset? = null
            for (i in acceptedEnvelope.indices) {
                val x = viewX((i + 0.5f) / acceptedEnvelope.size * durationMs, size.width, view)
                if (x < -2f || x > size.width + 2f || trace == null || trace.gain[i] <= 0.001f) {
                    previousPulse = null
                    continue
                }
                val point = Offset(x, mid - acceptedEnvelope[i] * amp)
                previousPulse?.let {
                    drawLine(GlintGold, it, point, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
                previousPulse = point
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
        ui.analyzing -> "Updating pulse…"
        point == null -> "Waiting for audio"
        point.gain > 0.001f -> "Pulse here"
        else -> "No pulse here"
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
    onNotes: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobs = ui.knobs
    var more by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Gold = the pulse used by the word’s glow", style = MaterialTheme.typography.bodySmall,
                color = GlintGold, modifier = Modifier.weight(1f))
            WordAction("Reset", onReset, QuranTheme.ink.secondary)
        }
        Text(
            "Missing a pulse you hear? Increase Sensitivity or shorten the note. Too much pulsing? Do the reverse. Changes save for this reciter.",
            style = MaterialTheme.typography.bodySmall,
            color = QuranTheme.ink.secondary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LabSlider("Sensitivity", (0.25f - knobs.minTremoloDepth) / 0.24f, 0f..1f,
            valueLabel = "${((0.25f - knobs.minTremoloDepth) / 0.24f * 100).roundToInt()}%",
            help = "More catches faint wavering. Less stops noise from pulsing.", ends = "Less" to "More") { v ->
            onKnob { k -> k.copy(minTremoloDepth = 0.25f - v * 0.24f) }
        }
        LabSlider("Shortest note", knobs.holdMinMs, 100f..1_200f,
            valueLabel = "${knobs.holdMinMs.roundToInt()} ms",
            help = "Shorter catches brief holds sooner. Longer ignores ordinary syllables.", ends = "Shorter" to "Longer") { v ->
            onKnob { k -> k.copy(holdMinMs = v) }
        }
        LabSlider("Rhythm tolerance", (0.85f - knobs.minPeriodicity) / 0.70f, 0f..1f,
            valueLabel = "${((0.85f - knobs.minPeriodicity) / 0.70f * 100).roundToInt()}%",
            help = "More accepts uneven wavering. Less requires a steady repeating pulse.", ends = "Steady only" to "Uneven too") { v ->
            onKnob { k -> k.copy(minPeriodicity = 0.85f - v * 0.70f) }
        }
        WordAction(if (more) "Fewer controls ▴" else "More controls ▾", { more = !more }, QuranTheme.ink.secondary)
        if (more) {
            Spacer(Modifier.height(12.dp))
            LabSlider("Slowest pulse", knobs.minTremoloHz, 1.5f..5f, decimals = 1,
                help = "Lower to catch slow wavering; raise to ignore slow volume swells.") { v ->
                onKnob { k -> k.copy(minTremoloHz = v, maxTremoloHz = maxOf(v, k.maxTremoloHz)) }
            }
            LabSlider("Fastest pulse", knobs.maxTremoloHz, 1.5f..10f, decimals = 1,
                help = "Raise to catch fast wavering; lower to ignore rapid roughness.") { v ->
                onKnob { k -> k.copy(maxTremoloHz = v, minTremoloHz = minOf(v, k.minTremoloHz)) }
            }
            LabSlider("Allow note slides", knobs.maxPitchDrift, 0.04f..0.30f,
                help = "Raise for a sliding held note. Lower if changing notes trigger a pulse.") { v ->
                onKnob { k -> k.copy(maxPitchDrift = v) }
            }
            LabSlider("Fade in", knobs.attackMs, 50f..600f,
                help = "Lower for a quicker entrance. Raise for a gentler glow.") { v ->
                onKnob { k -> k.copy(attackMs = v) }
            }
            LabSlider("Bridge gaps", knobs.releaseMs, 100f..2_000f,
                help = "Raise to smooth brief interruptions. Lower to clear the pulse sooner.") { v ->
                onKnob { k -> k.copy(releaseMs = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                WordAction("Export sample", onExport, QuranTheme.ink.secondary)
                WordAction("Import sample", onImport, QuranTheme.ink.secondary)
            }
            BasicTextField(
                value = ui.sampleNotes,
                onValueChange = onNotes,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = QuranTheme.ink.secondary),
                maxLines = 2,
                decorationBox = { field ->
                    Box(Modifier.padding(vertical = 12.dp)) {
                        if (ui.sampleNotes.isEmpty()) Text("Sample note (optional)",
                            style = MaterialTheme.typography.bodySmall, color = QuranTheme.ink.quiet)
                        field()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int? = null,
    valueLabel: String? = null,
    help: String,
    ends: Pair<String, String>? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = QuranTheme.ink.secondary,
                modifier = Modifier.weight(1f))
            Text(valueLabel ?: when (decimals ?: if (range.endInclusive - range.start <= 1f) 2 else 0) {
                0 -> "${value.roundToInt()} ms"
                1 -> "%.1f Hz".format(value)
                else -> "%.2f".format(value)
            }, style = MaterialTheme.typography.labelSmall, color = QuranTheme.ink.secondary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
        ends?.let { (left, right) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(left, style = MaterialTheme.typography.labelSmall, color = QuranTheme.ink.secondary)
                Text(right, style = MaterialTheme.typography.labelSmall, color = QuranTheme.ink.secondary)
            }
        }
        Text(help, style = MaterialTheme.typography.bodySmall, color = QuranTheme.ink.secondary)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
