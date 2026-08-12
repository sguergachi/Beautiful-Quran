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
                if (ui.capture != null) {
                    PreviewAction(
                        playing = ui.previewPlaying,
                        onClick = viewModel::togglePreview,
                    )
                }
                EarTruthPanel(ui, viewModel)
                if (ui.capturing) {
                    CaptureProgress(ui.captureProgress)
                }
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

/**
 * Listener-authored ground truth. Unlike detector knobs, these marks say
 * what the shimmer should do and therefore make an export useful as a
 * positive or negative regression sample.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EarTruthPanel(
    ui: TarjiLabViewModel.TarjiLabUiState,
    viewModel: TarjiLabViewModel,
) {
    val expectation = ui.expectation
    val comparison = ui.trace?.let { compareTarjiExpectation(expectation, it) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(
            text = "Ear truth",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = expectationSummary(expectation),
            style = MaterialTheme.typography.labelSmall,
            color = if (expectation.kind == TarjiExpectationKind.UNLABELED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        comparisonSummary(expectation, comparison)?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.capture != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                WordAction("Mark start", viewModel::markExpectedStart, MaterialTheme.colorScheme.primary)
                WordAction("+ Bright crest", viewModel::addExpectedCrest, MaterialTheme.colorScheme.primary)
                WordAction("Mark end", viewModel::markExpectedEnd, MaterialTheme.colorScheme.primary)
                WordAction("No shimmer", viewModel::expectNoShimmer, MaterialTheme.colorScheme.onSurfaceVariant)
                if (expectation.crestMs.isNotEmpty()) {
                    WordAction(
                        "Remove latest crest",
                        viewModel::removeLastExpectedCrest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expectation.kind != TarjiExpectationKind.UNLABELED) {
                    WordAction(
                        "Clear labels",
                        viewModel::clearExpectation,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expectation.canPreview) {
                    WordAction(
                        if (ui.previewingTarget) "Preview detector" else "Preview my target",
                        viewModel::toggleTargetPreview,
                        MaterialTheme.colorScheme.primary,
                    )
                }
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
                                text = "Listening note (optional)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            )
                        }
                        field()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (
                expectation.kind == TarjiExpectationKind.PULSES &&
                expectation.startMs != null && expectation.endMs != null &&
                expectation.crestMs.isNotEmpty()
            ) {
                LabSlider(
                    "Target rate Hz",
                    expectation.rateHz ?: ui.trace?.meanRateHz?.takeIf { it > 0f } ?: 5f,
                    1.5f..10f,
                    decimals = 2,
                    onChange = viewModel::setExpectedRate,
                )
            }
            if (expectation.kind == TarjiExpectationKind.PULSES) {
                Text(
                    text = if (expectation.canPreview) {
                        "Target appearance · ${if (ui.previewingTarget) "showing mine" else "showing detector"}"
                    } else {
                        "Mark start, at least two crests, and end to preview your shimmer."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabSlider("Target depth", expectation.style.depth, 0f..1f) { value ->
                    viewModel.updateTargetStyle { it.copy(depth = value) }
                }
                LabSlider("Trough light", expectation.style.troughFloor, 0f..1f) { value ->
                    viewModel.updateTargetStyle { it.copy(troughFloor = value) }
                }
                LabSlider("Build ms", expectation.style.buildMs, 0f..1_500f) { value ->
                    viewModel.updateTargetStyle { it.copy(buildMs = value) }
                }
                LabSlider("Dry ms", expectation.style.dryMs, 0f..500f) { value ->
                    viewModel.updateTargetStyle { it.copy(dryMs = value) }
                }
            }
        } else {
            Text(
                text = "Capture a word, then mark onset, each brightness crest, and the end.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

private fun expectationSummary(expectation: TarjiLabExpectation): String = when (expectation.kind) {
    TarjiExpectationKind.UNLABELED -> "Unlabeled — the detector has no ear reference yet."
    TarjiExpectationKind.NO_SHIMMER -> "Expected: no shimmer on this word."
    TarjiExpectationKind.PULSES -> buildList {
        expectation.startMs?.let { add("start ${formatScrubTime(it)}") }
        add("${expectation.crestMs.size} bright crests")
        expectation.endMs?.let { add("end ${formatScrubTime(it)}") }
        expectation.rateHz?.let { add("${"%.2f".format(it)} Hz") }
    }.joinToString(" · ")
}

private fun comparisonSummary(
    expectation: TarjiLabExpectation,
    comparison: TarjiExpectationComparison?,
): String? {
    if (comparison == null || expectation.kind == TarjiExpectationKind.UNLABELED) return null
    if (expectation.kind == TarjiExpectationKind.NO_SHIMMER) {
        return if (comparison.detectedStartMs == null) {
            "Detector agrees: still gold."
        } else {
            "Detector disagrees: shimmer from ${formatScrubTime(comparison.detectedStartMs)}."
        }
    }
    val parts = buildList {
        comparison.detectedRateHz?.let { add("detector ${"%.2f".format(it)} Hz") }
        comparison.startErrorMs?.let { add("onset ${formatSignedMs(it)}") }
        comparison.endErrorMs?.let { add("end ${formatSignedMs(it)}") }
        comparison.meanCrestErrorMs?.let { add("crest ±${it.roundToInt()} ms") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: "Detector finds no shimmer yet."
}

private fun formatSignedMs(ms: Float): String =
    "${if (ms >= 0f) "+" else "−"}${abs(ms).roundToInt()} ms"

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

/** Quiet sample utilities live by the header, away from loop transport. */
@Composable
private fun LabUtilities(
    ui: TarjiLabViewModel.TarjiLabUiState,
    viewModel: TarjiLabViewModel,
    context: android.content.Context,
    onImport: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp, bottom = 2.dp),
    ) {
        WordAction(
            label = "Import",
            onClick = onImport,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.capture != null) {
            WordAction(
                label = "Export",
                onClick = { viewModel.exportSample(context) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WordAction("Reset", viewModel::resetKnobs, MaterialTheme.colorScheme.onSurfaceVariant)
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
                text = "Muted capture ${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = active,
            )
            Text(
                text = "automatic",
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
            val resonance = if (ui.previewingTarget) {
                val point = targetTarjiPointAt(ui.expectation, playheadMs)
                InkEngine.glintResonance(
                    holding = point.holding,
                    tremolo = point.tremolo,
                    tremoloGain = point.gain,
                    depth = ui.expectation.style.depth,
                    troughFloor = ui.expectation.style.troughFloor,
                )
            } else {
                val point = tracePointAt(trace, playheadMs)
                InkEngine.glintResonance(
                    holding = point.reverberating,
                    tremolo = point.tremolo,
                    tremoloGain = point.gain,
                )
            }
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
    val expectedColor = MaterialTheme.colorScheme.primary
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
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        // Scrub owns the playhead only after a real press.
                        // Claiming it before awaitFirstDown pins the cursor
                        // throughout idle time, even after Play is pressed.
                        onScrubStart()
                        try {
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

            // Ear-truth overlay: a quiet span with exact onset/end lines and
            // a short stroke at every desired brightness crest.
            val expectation = ui.expectation
            if (expectation.kind == TarjiExpectationKind.PULSES) {
                val start = expectation.startMs
                val end = expectation.endMs
                if (start != null && end != null && end > start) {
                    val left = start / durationMs * size.width
                    val right = end / durationMs * size.width
                    drawRect(
                        expectedColor.copy(alpha = 0.08f),
                        topLeft = Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(right - left, size.height),
                    )
                }
                for (edge in listOfNotNull(start, end)) {
                    val x = edge / durationMs * size.width
                    drawLine(
                        expectedColor.copy(alpha = 0.75f),
                        Offset(x, 0f),
                        Offset(x, size.height),
                        strokeWidth = 1.5f,
                    )
                }
                for (crest in expectation.crestMs) {
                    val x = crest / durationMs * size.width
                    drawLine(
                        expectedColor,
                        Offset(x, 0f),
                        Offset(x, 14f),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }

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
            drawTargetSine(ui.expectation, expectedColor, durationMs)

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
        EvidenceReadout(ui, trace, playheadMs, durationMs)
    }
}

@Composable
private fun EvidenceReadout(
    ui: TarjiLabViewModel.TarjiLabUiState,
    trace: TarjiLabTrace?,
    playheadMs: Float,
    durationMs: Float,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    val point = trace?.let { tracePointAt(it, playheadMs.coerceAtLeast(0f)) }
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
                trace != null -> {
                    val fit = ui.sineFit?.let { "%.1f Hz".format(it.rateHz) } ?: "—"
                    "FIT $fit  ·  HOP ${trace.hopDurationMs.roundToInt()} ms"
                }
                else -> "WAVEFORM · TARJĪʿ"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (ui.analyzing) MaterialTheme.colorScheme.primary else quiet,
        )
    }
    if (trace != null) {
        EvidenceHeader()
        EvidenceLane(
            label = "LOUDNESS",
            rateHz = point?.amplitudeRateHz ?: 0f,
            depth = point?.amplitudeDepth ?: 0f,
            coherence = point?.amplitudePeriodicity ?: 0f,
            active = point?.reverberating == true && point.visualUsesAmplitude,
        )
        EvidenceLane(
            label = "PITCH",
            rateHz = point?.pitchModulationRateHz ?: 0f,
            depth = point?.pitchModulationDepth ?: 0f,
            coherence = point?.pitchModulationPeriodicity ?: 0f,
            active = point?.reverberating == true && !point.visualUsesAmplitude,
        )
    }
}

@Composable
private fun EvidenceHeader() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text("", modifier = Modifier.weight(1.45f))
        Text("RATE", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
        Text("DEPTH", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
        Text("COHERENCE", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1.25f))
    }
}

@Composable
private fun EvidenceLane(
    label: String,
    rateHz: Float,
    depth: Float,
    coherence: Float,
    active: Boolean,
) {
    val color = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    fun value(value: Float, suffix: String = ""): String =
        if (value > 0f) "%.2f%s".format(value, suffix) else "—"
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1.45f))
        Text(if (rateHz > 0f) "%.1f Hz".format(rateHz) else "—", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
        Text(value(depth), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
        Text(value(coherence), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1.25f))
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

/** Listener-authored target pulse, including build and dry-down. */
private fun DrawScope.drawTargetSine(
    expectation: TarjiLabExpectation,
    color: Color,
    durationMs: Float,
) {
    if (!expectation.canPreview || expectation.kind != TarjiExpectationKind.PULSES) return
    val mid = size.height * 0.5f
    val amp = size.height * 0.30f
    var previous: Offset? = null
    var x = 0f
    while (x <= size.width) {
        val point = targetTarjiPointAt(expectation, x / size.width * durationMs)
        if (point.holding) {
            val next = Offset(x, mid - point.tremolo * point.gain * amp)
            previous?.let {
                drawLine(color.copy(alpha = 0.9f), it, next, strokeWidth = 2f)
            }
            previous = next
        } else {
            previous = null
        }
        x += 2f
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
