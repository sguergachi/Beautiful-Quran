package com.beautifulquran.timingslab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.data.model.Segment
import com.beautifulquran.ui.reader.AyahBlock
import com.beautifulquran.ui.theme.ArabicWordStyle
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.QuranAccents
import com.beautifulquran.ui.theme.quietClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Milliseconds the slide bar covers across its full width while adjusting —
 * fixed, so control feels consistent; small moves give fine (~ms) control and
 * a full swipe still travels a few seconds. */
private const val DRAG_SPAN_MS = 3_500f
/** Visible timeline window at deepest zoom (zoom = 1), centred on the mark. */
private const val MIN_ZOOM_SPAN_MS = 2_200f
/** How long the zoom lingers after the slide bar is released before easing back
 * out — the Apple-timeline "stay zoomed while I work" beat. */
private const val ZOOM_HOLD_MS = 320L

private fun fmtTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L)
    val minutes = total / 60_000L
    val seconds = (total % 60_000L) / 1_000L
    val hundredths = (total % 1_000L) / 10L
    return if (minutes > 0) "%d:%02d.%02d".format(minutes, seconds, hundredths)
    else "%d.%02ds".format(seconds, hundredths)
}

/** Which marks re-recite an earlier word (position ≤ running max before it) —
 * the same rule HighlightEngine and the reader use for the orange wash. */
private fun repeatFlags(passes: List<Segment>): List<Boolean> {
    var runningMax = 0
    return passes.map { seg ->
        val repeat = runningMax > 0 && seg.position <= runningMax
        runningMax = maxOf(runningMax, seg.position)
        repeat
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingsLabScreen(
    viewModel: TimingsLabViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activeWord by viewModel.activeWord.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accents = LocalQuranAccents.current
    val haptics = LocalHapticFeedback.current

    // The Lab is always a dark workbench, so the system status-bar icons (clock,
    // battery) must be light while it's up — otherwise the reader's dark icons
    // carry over and read as black-on-black. Restored to the reader's setting
    // when the Lab closes.
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
            // Ignoring visibility: if the sheet rises while the reader still
            // has the status bar hidden (mid-recitation), the header must not
            // slide under the notch for the frames before the bar comes back.
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .navigationBarsPadding(),
    ) {
        LabHeader(ui, viewModel, accents, onBack)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                ui.isLoading -> CircularProgressIndicator()
                ui.ayahData != null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // The stage IS the reader: same block, same fade, same
                    // repeat wash, driven by the edited marks.
                    AyahBlock(
                        ayah = ui.ayahData!!,
                        readingMode = settings.readingMode,
                        activeWord = activeWord,
                        playbackSpeed = ui.speed,
                        isActiveAyah = ui.isPlaying || activeWord != null,
                        dimmed = false,
                        obscuredBySelector = false,
                        fontScale = settings.fontScale,
                        showGloss = settings.showWordGloss,
                        showTransliteration = settings.showTransliteration,
                        showTranslation = settings.showTranslation,
                        verseNumberScript = settings.verseNumberScript,
                        hideEnglishParentheticals = settings.hideEnglishParentheticals,
                        keepActiveWordInView = ui.isPlaying,
                        onWordClick = { word ->
                            if (ui.mode == LabMode.RECORD) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            viewModel.tapWord(word.position)
                        },
                        onAyahClick = viewModel::closeTune,
                    )
                }
            }
        }

        // Editing surface: a zoomable timeline sitting above a slide bar.
        // Selecting a word (tap it, or its marker) reveals the bar; dragging
        // it nudges only that marker while the timeline zooms in around it for
        // millisecond precision, then eases back out on release.
        var zoom by remember { mutableFloatStateOf(0f) }
        val zoomScope = rememberCoroutineScope()
        var zoomJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        // Apple-timeline zoom: grabbing the slide bar zooms in and *holds* there
        // through pauses; releasing waits a beat, then eases back out. No longer
        // tied to drag speed, so it never flickers in and out as you move.
        fun zoomIn() {
            zoomJob?.cancel()
            zoomJob = zoomScope.launch {
                animate(zoom, 1f, animationSpec = tween(200)) { v, _ -> zoom = v }
            }
        }
        fun zoomOut() {
            zoomJob?.cancel()
            zoomJob = zoomScope.launch {
                delay(ZOOM_HOLD_MS)
                animate(zoom, 0f, animationSpec = tween(360)) { v, _ -> zoom = v }
            }
        }

        EditTimeline(ui, viewModel, accents, zoom = zoom)

        AnimatedVisibility(
            visible = ui.mode == LabMode.LISTEN && ui.selectedPass != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            AdjustPanel(
                ui = ui,
                viewModel = viewModel,
                accents = accents,
                onGrab = { zoomIn() },
                onRelease = { zoomOut() },
            )
        }

        TransportBar(ui, viewModel, accents)
        HintLine(ui, accents)
        if (ui.error != null) {
            Text(
                text = ui.error!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        SubmitRibbon(ui, viewModel, accents)
    }
}

// ── Header ────────────────────────────────────────────────────────────────

@Composable
private fun LabHeader(
    ui: TimingsLabUiState,
    viewModel: TimingsLabViewModel,
    accents: QuranAccents,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // The Lab is a sheet that rose over the reader; the chevron lowers it
        // back onto the exact page it came from. onExit runs in the closer.
        IconButton(onClick = onBack) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("‹", onClick = viewModel::prevAyah)
                Text(
                    text = "${ui.surahName} · Ayah ${ui.ayah}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Stepper("›", onClick = viewModel::nextAyah)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = ui.reciter?.name.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.isOverridden) {
                    Text(
                        text = "· edited",
                        style = MaterialTheme.typography.labelSmall,
                        color = accents.gold,
                    )
                }
            }
        }
        OverflowMenu(ui, viewModel)
    }
}

@Composable
private fun Stepper(glyph: String, onClick: () -> Unit) {
    Text(
        text = glyph,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .quietClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 2.dp),
    )
}

@Composable
private fun OverflowMenu(
    ui: TimingsLabUiState,
    viewModel: TimingsLabViewModel,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true; confirmClear = false }) {
            Icon(
                Icons.Rounded.MoreHoriz,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Submit this ayah") },
                enabled = ui.isOverridden,
                onClick = { expanded = false; viewModel.submit(context, currentOnly = true) },
            )
            DropdownMenuItem(
                text = { Text("Submit all corrections (${ui.overrideCount})") },
                enabled = ui.overrideCount > 0,
                onClick = { expanded = false; viewModel.submit(context, currentOnly = false) },
            )
            DropdownMenuItem(
                text = { Text("Copy this ayah patch") },
                enabled = ui.isOverridden,
                onClick = { expanded = false; viewModel.copyPatch(context, currentOnly = true) },
            )
            DropdownMenuItem(
                text = { Text("Copy all patch JSON") },
                enabled = ui.overrideCount > 0,
                onClick = { expanded = false; viewModel.copyPatch(context, currentOnly = false) },
            )
            DropdownMenuItem(
                text = { Text("Reset ayah to bundled") },
                enabled = ui.isOverridden,
                onClick = { expanded = false; viewModel.resetOverride() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (confirmClear) "Tap again to confirm" else "Clear all corrections",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                enabled = ui.overrideCount > 0,
                onClick = {
                    if (confirmClear) {
                        expanded = false
                        viewModel.clearAllOverrides()
                    } else {
                        confirmClear = true
                    }
                },
            )
        }
    }
}

// ── Adjust panel ──────────────────────────────────────────────────────────

/** Shown when a word (or its marker) is selected. Names the word, offers
 * Add-repeat / Delete, and hosts the slide bar that nudges only this marker
 * while zooming the timeline above. No modes, no numeric steppers. */
@Composable
private fun AdjustPanel(
    ui: TimingsLabUiState,
    viewModel: TimingsLabViewModel,
    accents: QuranAccents,
    onGrab: () -> Unit,
    onRelease: () -> Unit,
) {
    val index = ui.selectedPass ?: return
    val pass = ui.passes.getOrNull(index) ?: return
    val word = ui.ayahData?.words?.firstOrNull { it.position == pass.position }
    val repeats = remember(ui.passes) { repeatFlags(ui.passes) }
    val isRepeat = repeats.getOrElse(index) { false }
    val markColor = if (isRepeat) accents.repeatInk else accents.gold
    val siblingPasses = ui.passes.withIndex().filter { it.value.position == pass.position }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = word?.arabic ?: "#${pass.position}",
                style = ArabicWordStyle,
                fontSize = 24.sp,
                color = if (isRepeat) accents.repeatInk else MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = buildString {
                    append(fmtTime(pass.startMs))
                    if (isRepeat) append(" · repeat")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isRepeat) accents.repeatInk else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::closeTune, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Done",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // This word was recited more than once: pick which pass to adjust.
        if (siblingPasses.size > 1) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                siblingPasses.forEach { (i, p) ->
                    val selected = i == index
                    val chipColor = if (repeats.getOrElse(i) { false }) accents.repeatInk else accents.gold
                    Text(
                        text = fmtTime(p.startMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) chipColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) chipColor.copy(alpha = 0.16f) else Color.Transparent)
                            .border(
                                width = 0.8.dp,
                                color = chipColor.copy(alpha = if (selected) 0.5f else 0.2f),
                                shape = RoundedCornerShape(50),
                            )
                            .quietClickable { viewModel.selectPass(i) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        AdjustSlider(accents = markColor, onGrab = onGrab, onRelease = onRelease) { deltaMs ->
            viewModel.nudgeSelected(deltaMs)
        }

        Spacer(Modifier.height(8.dp))
        // Has this word been moved off the shipped default? If so, offer to
        // reset just this word back to it — leaving every other word alone.
        val defForWord = ui.defaultPasses.filter { it.position == pass.position }
        val curForWord = ui.passes.filter { it.position == pass.position }
        val canReset = curForWord.map { it.startMs } != defForWord.map { it.startMs }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LabPill("＋ Add repeat", accents.repeatInk, filled = true) { viewModel.addRepeat() }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Reset",
                style = MaterialTheme.typography.labelMedium,
                color = accents.gold.copy(alpha = if (canReset) 1f else 0.3f),
                modifier = Modifier
                    .quietClickable(enabled = canReset) { viewModel.resetSelectedWordToDefault() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                text = "Delete",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .quietClickable(onClick = viewModel::deleteSelected)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The slide bar. A horizontal drag nudges only the selected marker at a fixed,
 * fine sensitivity. Grabbing it ([onGrab]) snapshots for Undo and zooms the
 * timeline in; the zoom *holds* through pauses and only eases back out after
 * release ([onRelease]) — Apple-timeline style, never flickering with speed.
 */
@Composable
private fun AdjustSlider(
    accents: Color,
    onGrab: () -> Unit,
    onRelease: () -> Unit,
    onNudge: (Long) -> Unit,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accents.copy(alpha = 0.10f))
            .border(0.8.dp, accents.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                var remainder = 0f
                detectHorizontalDragGestures(
                    onDragStart = { onGrab() },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, dragAmount ->
                    change.consume()
                    val ms = dragAmount * (DRAG_SPAN_MS / widthPx) + remainder
                    val whole = ms.roundToLong()
                    remainder = ms - whole
                    if (whole != 0L) onNudge(whole)
                }
            },
    ) {
        Text(
            text = "◀   slide to adjust   ▶",
            style = MaterialTheme.typography.labelMedium,
            color = accents.copy(alpha = 0.8f),
        )
    }
}

// ── Timeline ──────────────────────────────────────────────────────────────

/**
 * The marks-and-playhead strip. Repeats are washed orange, first-pass marks
 * gold, the selected one enlarged with a handle dot. [zoom] (0 = whole ayah,
 * 1 = deepest) windows the view around the selected marker so a fine drag on
 * the slide bar reads as a real zoom, not just a slower number.
 */
@Composable
private fun EditTimeline(
    ui: TimingsLabUiState,
    viewModel: TimingsLabViewModel,
    accents: QuranAccents,
    zoom: Float,
) {
    val duration = ui.durationMs.coerceAtLeast(1L)
    val repeats = remember(ui.passes) { repeatFlags(ui.passes) }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val playheadColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    // Window the view: full ayah at zoom 0, tightening around the selected
    // marker as zoom rises. Kept as floats; the Canvas maps time→x through it.
    val centerMs = ui.selectedPass?.let { ui.passes.getOrNull(it)?.startMs }?.toFloat()
        ?: (duration / 2f)
    val spanMs = androidx.compose.ui.util.lerp(duration.toFloat(), MIN_ZOOM_SPAN_MS, zoom)
        .coerceIn(1f, duration.toFloat())
    val windowStart = (centerMs - spanMs / 2f).coerceIn(0f, (duration - spanMs).coerceAtLeast(0f))
    fun xFor(ms: Float, w: Float): Float = ((ms - windowStart) / spanMs) * w

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 24.dp)
            // Tap a marker to select it (the word lights up); tap elsewhere to
            // scrub. Disabled while zoomed — the slide bar owns that gesture.
            .pointerInputPick(duration, ui.passes, zoom < 0.02f) { tapX, w ->
                val nearest = ui.passes.withIndex().minByOrNull {
                    kotlin.math.abs(xFor(it.value.startMs.toFloat(), w) - tapX)
                }
                val hitPx = w * 0.05f
                if (nearest != null &&
                    kotlin.math.abs(xFor(nearest.value.startMs.toFloat(), w) - tapX) < hitPx
                ) {
                    viewModel.selectPass(nearest.index)
                } else {
                    viewModel.scrubTo((windowStart + (tapX / w) * spanMs).toLong())
                }
            },
    ) {
        val w = size.width
        val midY = size.height / 2f
        drawLine(
            color = trackColor,
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Center guide while zoomed, so the selected mark's exact placement reads.
        if (zoom > 0.02f) {
            val cx = xFor(centerMs, w)
            drawLine(
                color = guideColor,
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        ui.passes.forEachIndexed { i, seg ->
            val x = xFor(seg.startMs.toFloat(), w)
            if (x < -8f || x > w + 8f) return@forEachIndexed
            val selected = i == ui.selectedPass
            val color = if (repeats.getOrElse(i) { false }) accents.repeatInk else accents.gold
            val tick = if (selected) 14.dp.toPx() else 8.dp.toPx()
            drawLine(
                color = color,
                start = Offset(x, midY - tick),
                end = Offset(x, midY + tick),
                strokeWidth = (if (selected) 3.dp else 2.dp).toPx(),
                cap = StrokeCap.Round,
            )
            if (selected) {
                drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(x, midY))
            }
        }
        val px = xFor(ui.positionMs.toFloat(), w)
        if (px in 0f..w) {
            drawLine(
                color = playheadColor,
                start = Offset(px, 2.dp.toPx()),
                end = Offset(px, size.height - 2.dp.toPx()),
                strokeWidth = 1.6.dp.toPx(),
            )
        }
    }
}

/** Tap picking / scrubbing for the timeline, active only when [enabled]
 * (i.e. not mid-zoom). Reports the tapped x and the canvas width. */
private fun Modifier.pointerInputPick(
    durationMs: Long,
    passes: List<Segment>,
    enabled: Boolean,
    onTap: (tapX: Float, width: Float) -> Unit,
): Modifier = pointerInput(durationMs, passes, enabled) {
    if (!enabled) return@pointerInput
    detectTapGestures { tap -> onTap(tap.x, size.width.toFloat()) }
}

// ── Transport ─────────────────────────────────────────────────────────────

@Composable
private fun TransportBar(
    ui: TimingsLabUiState,
    viewModel: TimingsLabViewModel,
    accents: QuranAccents,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        if (ui.mode == LabMode.RECORD) {
            LabPill("■  Done", accents.repeatInk, filled = true) { viewModel.finishRecord() }
            LabPill("↺ 4s", accents.repeatInk) { viewModel.rewind() }
            LabPill("Undo", accents.repeatInk, enabled = ui.recordedMarks > 0) { viewModel.undoTap() }
            Spacer(Modifier.weight(1f))
            RecIndicator(ui.recordedMarks, accents)
        } else {
            IconButton(onClick = viewModel::playPause, modifier = Modifier.size(48.dp)) {
                if (ui.isBuffering && !ui.isPlaying) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (ui.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = viewModel::restart, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Replay,
                    contentDescription = "Restart",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LabPill(speedLabel(ui.speed), accents.gold) { viewModel.cycleSpeed() }
            Spacer(Modifier.weight(1f))
            LabPill("●  Re-sync", accents.gold, filled = true, enabled = !ui.isLoading) {
                viewModel.startRecord()
            }
        }
    }
}

private fun speedLabel(speed: Float): String = when {
    speed < 0.6f -> "½×"
    speed < 0.9f -> "¾×"
    else -> "1×"
}

@Composable
private fun RecIndicator(marks: Int, accents: QuranAccents) {
    val pulse = rememberInfiniteTransition(label = "rec")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recPulse",
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .graphicsLayer { alpha = pulse.value }
                .clip(CircleShape)
                .background(accents.repeatInk),
        )
        Text(
            text = if (marks == 0) "REC" else "$marks mark${if (marks == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = accents.repeatInk,
        )
    }
}

@Composable
private fun LabPill(
    label: String,
    color: Color,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color.copy(alpha = alpha),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.16f * alpha) else Color.Transparent)
            .border(
                width = 0.8.dp,
                color = color.copy(alpha = (if (filled) 0.5f else 0.3f) * alpha),
                shape = RoundedCornerShape(50),
            )
            .quietClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// ── Hint + ribbon ─────────────────────────────────────────────────────────

@Composable
private fun HintLine(ui: TimingsLabUiState, accents: QuranAccents) {
    val text = when {
        ui.mode == LabMode.RECORD ->
            "Tap each word the moment it's recited — tap earlier words again for repeats"
        ui.passes.isEmpty() && !ui.isLoading ->
            "No timings yet for this ayah — hit Re-sync and tap along"
        ui.selectedPass != null ->
            "Slide to adjust — Reset returns this word to its default · ＋ Add repeat to mark a repetition"
        else -> "Play to follow along · tap a word (or a marker) to adjust its timing"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (ui.mode == LabMode.RECORD) {
            accents.repeatInk.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
    )
}

@Composable
private fun SubmitRibbon(
    ui: TimingsLabUiState,
    viewModel: TimingsLabViewModel,
    accents: QuranAccents,
) {
    if (ui.overrideCount == 0 || ui.mode == LabMode.RECORD) {
        Spacer(Modifier.height(6.dp))
        return
    }
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            text = when {
                ui.isOverridden && ui.overrideCount == 1 -> "This ayah corrected"
                ui.isOverridden -> "This ayah · ${ui.overrideCount} on device"
                else -> "${ui.overrideCount} ayah${if (ui.overrideCount == 1) "" else "s"} corrected on this device"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.size(10.dp))
        if (ui.isOverridden) {
            Text(
                text = "Submit this",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accents.gold,
                modifier = Modifier
                    .quietClickable { viewModel.submit(context, currentOnly = true) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (ui.overrideCount > 1 || !ui.isOverridden) {
            if (ui.isOverridden) Spacer(Modifier.size(6.dp))
            Text(
                text = if (ui.isOverridden) "Submit all" else "Submit",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accents.gold,
                modifier = Modifier
                    .quietClickable { viewModel.submit(context, currentOnly = false) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
