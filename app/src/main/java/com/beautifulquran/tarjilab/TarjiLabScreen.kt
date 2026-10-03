package com.beautifulquran.tarjilab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
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
    var showHelp by remember { mutableStateOf(false) }
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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> viewModel.exportSample(context, uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importSample(context, uri)
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
                        .height(164.dp)
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
                    if (ui.tool == TarjiLabTool.HOLD) {
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
                }
                StatusSlot(
                    error = ui.captureError,
                    note = ui.note,
                    onRetry = viewModel::retryCapture,
                )
                TransportRow(ui, viewModel, showHelp) { showHelp = !showHelp }
                ComparisonRow(ui, viewModel, playheadMs)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    KnobsPanel(
                        ui = ui,
                        showHelp = showHelp,
                        onKnob = viewModel::updateKnobs,
                        onReset = viewModel::resetKnobs,
                        onMatch = viewModel::matchPulse,
                        onExport = { viewModel.prepareSampleExport()?.let { exportLauncher.launch(it) } },
                        onNotes = viewModel::updateSampleNotes,
                        onFinished = viewModel::finishKnobEdit,
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
        LabIconAction(Icons.Rounded.Close, "Close lab", onBack)

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
        LabIconAction(Icons.Rounded.ChevronLeft, "Previous word", viewModel::prevWord,
            enabled = ui.wordCount > 0)
        PreviewWord(
            ui,
            playheadMs,
            36.sp,
            Modifier.weight(1f).height(56.dp),
        )
        LabIconAction(Icons.Rounded.ChevronRight, "Next word", viewModel::nextWord,
            enabled = ui.wordCount > 0)

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
    showHelp: Boolean,
    onHelp: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        LabIconAction(Icons.Rounded.Replay, "Rewind", viewModel::rewindPreview,
            enabled = ui.capture != null)
        LabIconAction(
            if (ui.previewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (ui.previewPlaying) "Pause" else "Play", {
                val scope = if (ui.previewPlaying) ui.previewScope
                    else if (ui.tool == TarjiLabTool.HOLD) TarjiPreviewScope.HOLD else TarjiPreviewScope.WORD
                if (scope == TarjiPreviewScope.HOLD) viewModel.togglePreview() else viewModel.toggleWordPreview()
            }, enabled = ui.capture != null, prominent = true,
        )
        LabIconAction(Icons.Rounded.Repeat, "Loop selection", {
            val tool = if (ui.tool == TarjiLabTool.HOLD) TarjiLabTool.LISTEN else TarjiLabTool.HOLD
            viewModel.setTool(tool)
        }, enabled = ui.capture != null, selected = ui.tool == TarjiLabTool.HOLD)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp)
                .quietClickable(enabled = ui.capture != null, role = Role.Button) {
                    val speeds = TarjiPreviewSpeed.entries
                    viewModel.setPreviewSpeed(speeds[(speeds.indexOf(ui.previewSpeed) + 1) % speeds.size])
                }.semantics { contentDescription = "Playback speed ${ui.previewSpeed.mark}. Tap to change." },
        ) {
            Text(ui.previewSpeed.mark, style = MaterialTheme.typography.labelLarge, color = QuranTheme.ink.secondary)
        }
        LabIconAction(Icons.Rounded.ZoomOutMap, "Fit waveform", viewModel::fitView,
            enabled = ui.capture != null)
        LabIconAction(Icons.AutoMirrored.Rounded.HelpOutline, "Show tuning help", onHelp, selected = showHelp)
    }
}

/** Comparison affects only the graph and glow; the recorded voice keeps its place. */
@Composable
private fun ComparisonRow(
    ui: TarjiLabViewModel.TarjiLabUiState,
    viewModel: TarjiLabViewModel,
    playheadMs: Float,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DetectorReadout(ui, playheadMs, Modifier.weight(1f))
        LabIconAction(Icons.AutoMirrored.Rounded.CompareArrows, "Compare reference", viewModel::toggleReference,
            enabled = ui.reference != null, selected = ui.showingReference)
        LabIconAction(Icons.Rounded.BookmarkAdd, "Set comparison reference", viewModel::setReference,
            enabled = ui.trace != null && !ui.analyzing && !ui.showingReference,
            selected = ui.reference?.knobs == ui.knobs)
        LabIconAction(Icons.AutoMirrored.Rounded.Undo, "Undo tuning", viewModel::undoKnobs, enabled = ui.canUndo)
        LabIconAction(Icons.AutoMirrored.Rounded.Redo, "Redo tuning", viewModel::redoKnobs, enabled = ui.canRedo)
    }
}

/** A ripple-free 48 dp target; press motion belongs to the ink, not a floating surface. */
@Composable
private fun LabIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    prominent: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.9f else 1f, label = "control press")
    val ink = if (!enabled) QuranTheme.ink.quiet else if (selected || prominent) GlintGold else QuranTheme.ink.secondary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp)
            .quietClickable(enabled = enabled, role = Role.Button, interactionSource = interaction, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
    ) {
        Icon(icon, contentDescription = null, tint = ink,
            modifier = Modifier.size(if (prominent) 36.dp else 24.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value })
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
        color = if (enabled) color else QuranTheme.ink.quiet,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .quietClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
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
        val glow = ui.displayTrace?.let { tracePointAt(it, head) }
        val resonance = InkEngine.glintResonance(
            holding = glow?.reverberating == true,
            tremolo = glow?.tremolo ?: 0f,
            tremoloGain = glow?.gain ?: 0f,
            depth = InkEngine.GLINT_RESONANCE_DEPTH,
            enabled = true,
        )
        val pulseColor = com.beautifulquran.ui.theme.glintPulseColor(
            GlintGold, resonance.peak, ui.displayKnobs.glintBrightness,
        )
        Canvas(Modifier.fillMaxSize()) {
            val amount = (0.22f * resonance.layerMult + 0.9f * resonance.peak) *
                ui.displayKnobs.glintBrightness.coerceIn(0f, 2f)
            if (amount > 0.01f) {
                drawCircle(
                    color = pulseColor.copy(alpha = (amount * 0.55f).coerceIn(0f, 0.75f)),
                    radius = size.minDimension * 0.42f,
                    center = center,
                )
                drawCircle(
                    color = pulseColor.copy(alpha = (amount * 0.3f).coerceIn(0f, 0.45f)),
                    radius = size.minDimension * 0.62f,
                    center = center,
                )
            }
        }
        Text(
            text = ui.wordArabic,
            style = ArabicWordStyle,
            fontSize = fontSize,
            color = pulseColor,
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
    val trace = ui.displayTrace
    val waveColor = QuranTheme.ink.quiet
    val guideColor = QuranTheme.ink.muted
    val pulseColor = QuranTheme.accents.greenInk
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

            drawLine(guideColor.copy(alpha = 0.2f), Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2), strokeWidth = 1f)
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
                    drawLine(pulseColor, it, point, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
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
    val visibleX = x.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx())
    drawLine(color, Offset(visibleX, 0f), Offset(visibleX, size.height), strokeWidth = 1.dp.toPx())
    drawLine(color, Offset(visibleX, size.height / 2 - 14.dp.toPx()),
        Offset(visibleX, size.height / 2 + 14.dp.toPx()), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
}

/** Messages use space only when there is something to report. */
@Composable
private fun StatusSlot(
    error: String?,
    note: String?,
    onRetry: () -> Unit,
) {
    val message = error ?: note ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (error != null) 48.dp else 24.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = if (error != null) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (error != null) {
            LabIconAction(Icons.Rounded.Replay, "Retry capture", onRetry)
        }
    }
}

/** Values at the same content position as the audible loop. */
@Composable
private fun DetectorReadout(ui: TarjiLabViewModel.TarjiLabUiState, playheadMs: Float, modifier: Modifier) {
    val point = ui.displayTrace?.let { tracePointAt(it, playheadMs.coerceAtLeast(0f)) }
    val status = when {
        ui.matchingPulse -> "Matching…"
        ui.analyzing && !ui.showingReference -> "Updating…"
        point == null -> "Waiting for audio"
        point.gain > 0.001f && point.rateHz > 0f -> "${if (point.visualUsesAmplitude) "Volume" else "Pitch"} · %.1f Hz".format(point.rateHz)
        point.gain > 0.001f -> "Pulse fading"
        else -> "No pulse here"
    }
    Column(modifier) {
        Text(formatLabClock(playheadMs, ui.previewDurationMs),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "'tnum' 1, 'lnum' 1"),
            color = QuranTheme.ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (ui.showingReference) "Reference" else status,
            style = MaterialTheme.typography.labelSmall,
            color = if (ui.showingReference) GlintGold else if ((point?.gain ?: 0f) > 0.001f) QuranTheme.accents.greenInk else QuranTheme.ink.secondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
    showHelp: Boolean,
    onKnob: ((TarjiLabKnobs) -> TarjiLabKnobs) -> Unit,
    onReset: () -> Unit,
    onMatch: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onNotes: (String) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobs = ui.displayKnobs
    var more by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        LabSlider("Glint brightness", knobs.glintBrightness / 2f, 0f..1f,
            valueLabel = "${(knobs.glintBrightness * 100).roundToInt()}%",
            help = "Left dims the glint; right brightens it. 100% is the original look. This changes the sheen and halo, not which pulses are detected.",
            enabled = !ui.showingReference, showHelp = showHelp, onFinished = onFinished) { v ->
            onKnob { it.copy(glintBrightness = v * 2f) }
        }
        LabSlider("Sensitivity", (0.25f - knobs.minTremoloDepth) / 0.24f, 0f..1f,
            valueLabel = "${((0.25f - knobs.minTremoloDepth) / 0.24f * 100).roundToInt()}%",
            help = "Missing a pulse? Move right. Speech pulsing? Move left.",
            enabled = !ui.showingReference, showHelp = showHelp, onFinished = onFinished) { v ->
            onKnob { k -> k.copy(minTremoloDepth = 0.25f - v * 0.24f) }
        }
        LabSlider("Shortest note", knobs.holdMinMs, 100f..1_200f,
            valueLabel = "${knobs.holdMinMs.roundToInt()} ms",
            help = "Move left for brief holds; right to ignore short syllables.",
            enabled = !ui.showingReference, showHelp = showHelp, onFinished = onFinished) { v ->
            onKnob { k -> k.copy(holdMinMs = v) }
        }
        LabSlider("Rhythm tolerance", (0.85f - knobs.minPeriodicity) / 0.70f, 0f..1f,
            valueLabel = "${((0.85f - knobs.minPeriodicity) / 0.70f * 100).roundToInt()}%",
            help = "Move right for uneven wavering; left for a steady rhythm.",
            enabled = !ui.showingReference, showHelp = showHelp, onFinished = onFinished) { v ->
            onKnob { k -> k.copy(minPeriodicity = 0.85f - v * 0.70f) }
        }
        PulseSpeedControl(knobs, !ui.showingReference, showHelp, onFinished, onMatch,
            canMatch = ui.capture != null && !ui.showingReference && !ui.matchingPulse && !ui.holdEditing && !ui.capturing) { band ->
            onKnob { it.copy(minTremoloHz = band.start, maxTremoloHz = band.endInclusive) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabIconAction(Icons.Rounded.Tune, "More tuning controls", { more = !more }, selected = more)
            Text(if (more) "More controls" else "Fine tuning", style = MaterialTheme.typography.labelLarge,
                color = QuranTheme.ink.secondary, modifier = Modifier.weight(1f).heightIn(min = 48.dp).quietClickable(role = Role.Button) { more = !more }
                    .padding(vertical = 12.dp))
            LabIconAction(Icons.Rounded.RestartAlt, "Reset reciter tuning", onReset)
        }
        if (showHelp) Text(
            "The green pulse drives the word’s glow. Tap the graph to seek; pinch to zoom. Loop lets you isolate a note. Compare shows your reference; the bookmark saves a new one.",
            style = MaterialTheme.typography.bodySmall, color = QuranTheme.ink.secondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (more) {
            LabSlider("Allow note slides", knobs.maxPitchDrift, 0.04f..0.30f, enabled = !ui.showingReference, showHelp = showHelp, onFinished = onFinished,
                help = "Raise for a sliding held note. Lower if changing notes trigger a pulse.") { v ->
                onKnob { k -> k.copy(maxPitchDrift = v) }
            }
            LabSlider("Fade in", knobs.attackMs, 50f..600f, enabled = !ui.showingReference, showHelp = showHelp, onFinished = onFinished,
                help = "Lower for a quicker entrance. Raise for a gentler glow.") { v ->
                onKnob { k -> k.copy(attackMs = v) }
            }
            LabSlider("Bridge gaps", knobs.releaseMs, 100f..2_000f, enabled = !ui.showingReference, showHelp = showHelp, onFinished = onFinished,
                help = "Raise to smooth brief interruptions. Lower to clear the pulse sooner.") { v ->
                onKnob { k -> k.copy(releaseMs = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                WordAction("Export sample", onExport, QuranTheme.ink.secondary, enabled = ui.capture != null && !ui.capturing)
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

/** One band control replaces separate rate endpoints, keeping both on the same scale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PulseSpeedControl(
    knobs: TarjiLabKnobs,
    enabled: Boolean,
    showHelp: Boolean,
    onFinished: () -> Unit,
    onMatch: () -> Unit,
    canMatch: Boolean,
    onChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    val ink = QuranTheme.ink
    val pulseColor = if (enabled) QuranTheme.accents.greenInk else ink.quiet
    val low = knobs.minTremoloHz.coerceIn(1.5f, 10f)
    val high = knobs.maxTremoloHz.coerceIn(low, 10f)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pulse speed", style = MaterialTheme.typography.labelLarge, color = ink.secondary,
                modifier = Modifier.weight(1f))
            Text("%.1f–%.1f Hz".format(low, high),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "'tnum' 1, 'lnum' 1"),
                color = ink.secondary)
            LabIconAction(Icons.Rounded.AutoFixHigh, "Match this section", onMatch, enabled = canMatch)
        }
        RangeSlider(
            value = low..high, onValueChange = onChange, onValueChangeFinished = onFinished,
            valueRange = 1.5f..10f, steps = 84, enabled = enabled,
            startThumb = { PulseThumb(pulseColor) }, endThumb = { PulseThumb(pulseColor) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val y = size.height / 2
                    drawLine(ink.hairline, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(pulseColor, Offset((state.activeRangeStart - 1.5f) / 8.5f * size.width, y),
                        Offset((state.activeRangeEnd - 1.5f) / 8.5f * size.width, y), 3.dp.toPx(), StrokeCap.Round)
                }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Slowest and fastest pulse speed" },
        )
        if (showHelp) Text(
            "Drag the two ends to include the wavering you hear: left is slower, right is faster. Hz means pulses per second, not the voice’s pitch. A wider range catches more kinds of wavering in volume or pitch. The wand matches the selected loop, or the whole word. Use Loop to isolate the wavering you hear.",
            style = MaterialTheme.typography.bodySmall, color = ink.secondary,
        )
    }
}

@Composable
private fun PulseThumb(color: Color) {
    Canvas(Modifier.size(20.dp)) { drawCircle(color, radius = 7.dp.toPx()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int? = null,
    valueLabel: String? = null,
    help: String,
    showHelp: Boolean = false,
    enabled: Boolean = true,
    onFinished: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val ink = QuranTheme.ink
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = QuranTheme.ink.secondary,
                modifier = Modifier.weight(1f))
            Text(valueLabel ?: when (decimals ?: if (range.endInclusive - range.start <= 1f) 2 else 0) {
                0 -> "${value.roundToInt()} ms"
                1 -> "%.1f Hz".format(value)
                else -> "%.2f".format(value)
            }, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "'tnum' 1, 'lnum' 1"),
                color = QuranTheme.ink.secondary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val step = if (range.endInclusive <= 1f) 0.01f else if (range.endInclusive <= 10f) 0.1f else 10f
            LabIconAction(Icons.Rounded.Remove, "Decrease $label", {
                onChange((value - step).coerceIn(range)); onFinished()
            }, enabled = enabled && value > range.start)
            Slider(
                value = value.coerceIn(range),
                onValueChange = onChange,
                onValueChangeFinished = onFinished,
                enabled = enabled,
                valueRange = range,
                thumb = {
                    PulseThumb(if (enabled) GlintGold else ink.quiet)
                },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                        val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                        val start = Offset(0f, size.height / 2)
                        drawLine(ink.hairline, start, Offset(size.width, start.y), 3.dp.toPx(), StrokeCap.Round)
                        drawLine(if (enabled) GlintGold else ink.quiet, start,
                            Offset(size.width * fraction, start.y), 3.dp.toPx(), StrokeCap.Round)
                    }
                },
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
            )
            LabIconAction(Icons.Rounded.Add, "Increase $label", {
                onChange((value + step).coerceIn(range)); onFinished()
            }, enabled = enabled && value < range.endInclusive)
        }
        if (showHelp) Text(help, style = MaterialTheme.typography.bodySmall, color = QuranTheme.ink.secondary)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
