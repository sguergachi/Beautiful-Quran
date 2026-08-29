package com.beautifulquran.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.BuildConfig
import com.beautifulquran.R
import com.beautifulquran.data.BrushCircleStyle
import com.beautifulquran.data.HomeBookmarkStyle
import com.beautifulquran.data.Settings
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.share.ShareUxVariant
import com.beautifulquran.playback.RecitationCache
import com.beautifulquran.playback.RecitationUsage
import com.beautifulquran.playback.formatUsage
import com.beautifulquran.ui.PageTurnSounds
import com.beautifulquran.ui.theme.AlphaTag
import com.beautifulquran.ui.theme.BrushCheckParams
import com.beautifulquran.ui.theme.BrushCircleParams
import com.beautifulquran.ui.theme.DisclosureChevron
import com.beautifulquran.ui.theme.InkCheck
import com.beautifulquran.ui.theme.InkDisc
import com.beautifulquran.ui.theme.SHIPPED_BRUSH_REVISION
import com.beautifulquran.ui.theme.SHIPPED_CHECK_REVISION
import com.beautifulquran.ui.theme.brushCircleParams
import com.beautifulquran.ui.theme.inkBrushCheckPath
import com.beautifulquran.ui.theme.inkBrushCirclePath
import com.beautifulquran.ui.theme.paperSelectHaptic
import com.beautifulquran.ui.theme.paperToggleHaptic
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.shippedCheckParams
import com.beautifulquran.ui.theme.themePreviewColors
import com.beautifulquran.ui.theme.verticalFadingEdges
import kotlin.math.roundToInt

private val ATTRIBUTIONS = """
Quran text (Uthmani script) and Saheeh International translation via the
quran-json project, from Tanzil and Al Quran Cloud.

Word-by-word translation and transliteration from the Quran.com dataset.

Root, lemma, and morphological annotation from the Quranic Arabic Corpus (corpus.quran.com), © Kais Dukes.

Word-level audio timing data © the quran-align project contributors, CC-BY 4.0.

Recitation audio streamed from everyayah.com. All rights to the recitations belong to the respective reciters.

Arabic typeface: KFGQPC HAFS Uthmanic Script © King Fahd Glorious Quran Printing Complex, Madinah.

This app is free, ad-free, and collects no data.
""".trimIndent()

// Text size runs the same discrete stops the reader honours: 0.8× … 1.6×.
private const val FONT_SCALE_MIN = 0.8f
private const val FONT_SCALE_MAX = 1.6f
private const val FONT_SCALE_STOPS = 8 // intervals; nine tappable stops
private val FONT_SCALE_STEP = (FONT_SCALE_MAX - FONT_SCALE_MIN) / FONT_SCALE_STOPS

internal enum class SettingsDetail { CUSTOMIZE, DOWNLOADS }

/** Session-only brush lab state shared by Settings and its Customize leaf. */
internal class SettingsInkPreviewState(initialStyle: BrushCircleStyle) {
    var brushParams by mutableStateOf(brushCircleParams(initialStyle))
    var checkParams by mutableStateOf(shippedCheckParams())
    var paintToken by mutableIntStateOf(0)
    var checkPaintToken by mutableIntStateOf(0)
}

/**
 * Snap [scale] to the nearest stop, then move [deltaStops] (±1 for the A glyphs).
 * Clamped to [FONT_SCALE_MIN]…[FONT_SCALE_MAX].
 */
internal fun nudgeFontScale(scale: Float, deltaStops: Int): Float {
    val current = ((scale - FONT_SCALE_MIN) / FONT_SCALE_STEP)
        .roundToInt()
        .coerceIn(0, FONT_SCALE_STOPS)
    val next = (current + deltaStops).coerceIn(0, FONT_SCALE_STOPS)
    return FONT_SCALE_MIN + next * FONT_SCALE_STEP
}

/** Settings as its own sheet of paper — a full page, nothing floating, no
 * cards, no dividers. Hierarchy is spacing, size, and ink alone (docs/DESIGN.md). */
@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel,
    inkPreview: SettingsInkPreviewState,
    onBack: () -> Unit,
    onOpenCustomize: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenTimingsLab: () -> Unit = {},
    onOpenTarjiLab: () -> Unit = {},
    onOpenOrnamentsLab: () -> Unit = {},
    onRecordSystemTrace: () -> Unit = {},
    downloadsRefreshKey: Int = 0,
) {
    val settings by viewModel.settings.settings.collectAsStateWithLifecycle()
    val reciters by viewModel.reciters.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var usage by remember { mutableStateOf<RecitationUsage?>(null) }
    LaunchedEffect(downloadsRefreshKey) {
        usage = withContext(Dispatchers.IO) {
            RecitationCache.usage(context)
        }
    }

    var developerTapCount by remember { mutableStateOf(0) }
    val brushParams = inkPreview.brushParams
    val checkParams = inkPreview.checkParams
    val checkPaintToken = inkPreview.checkPaintToken
    var copyNote by remember { mutableStateOf<String?>(null) }
    // Only reseed when the preset or shipped BASE revision actually changes —
    // never wipe a live paste / slider edit on unrelated recomposition.
    // Ship bumps always load baseline (not Hairline's bodyAmp 0.12, etc.).
    var lastBrushStyle by remember { mutableStateOf(settings.brushCircleStyle) }
    var lastShipRev by remember { mutableIntStateOf(SHIPPED_BRUSH_REVISION) }
    var lastCheckShipRev by remember { mutableIntStateOf(SHIPPED_CHECK_REVISION) }

    LaunchedEffect(settings.brushCircleStyle, SHIPPED_BRUSH_REVISION) {
        val styleChanged = lastBrushStyle != settings.brushCircleStyle
        val shipChanged = lastShipRev != SHIPPED_BRUSH_REVISION
        lastBrushStyle = settings.brushCircleStyle
        lastShipRev = SHIPPED_BRUSH_REVISION
        if (shipChanged) {
            if (settings.brushCircleStyle != BrushCircleStyle.BASELINE) {
                viewModel.settings.update { it.copy(brushCircleStyle = BrushCircleStyle.BASELINE) }
            }
            lastBrushStyle = BrushCircleStyle.BASELINE
            inkPreview.brushParams = brushCircleParams(BrushCircleStyle.BASELINE)
            inkPreview.paintToken++
        } else if (styleChanged) {
            inkPreview.brushParams = brushCircleParams(settings.brushCircleStyle)
            inkPreview.paintToken++
        }
    }

    LaunchedEffect(SHIPPED_CHECK_REVISION) {
        if (lastCheckShipRev == SHIPPED_CHECK_REVISION) return@LaunchedEffect
        lastCheckShipRev = SHIPPED_CHECK_REVISION
        inkPreview.checkParams = shippedCheckParams()
        inkPreview.checkPaintToken++
    }

    if (developerTapCount > 0) {
        LaunchedEffect(developerTapCount) {
            delay(1500L)
            developerTapCount = 0
        }
    }
    if (copyNote != null) {
        LaunchedEffect(copyNote) {
            delay(2000L)
            copyNote = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalFadingEdges(
                    color = MaterialTheme.colorScheme.background,
                    top = 20.dp,
                    bottom = 40.dp,
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            // Match the top dissolve so the chevron/title sit clear at rest.
            Spacer(Modifier.height(20.dp))
            BackChevron(onBack)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(36.dp))

            SectionLabel("Reciter")
            Spacer(Modifier.height(4.dp))
            reciters.forEach { reciter ->
                SelectRow(
                    label = reciter.name,
                    note = if (!reciter.hasTimings) "No word highlighting" else null,
                    selected = reciter.id == settings.reciterId,
                    onClick = { viewModel.selectReciter(reciter) },
                )
            }

            Spacer(Modifier.height(28.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .quietClickable { onOpenDownloads() }
                    .padding(vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Download manager",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = usage?.let(::formatUsage) ?: "…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
                DisclosureChevron(expanded = false)
            }

            Spacer(Modifier.height(20.dp))
            NavigateRow(
                label = "Customize",
                note = customizeSummary(settings),
                onClick = { onOpenCustomize() },
            )

            if (settings.developerModeEnabled) {
                Spacer(Modifier.height(44.dp))
                DeveloperSection(
                    viewModel = viewModel,
                    settings = settings,
                    brushParams = brushParams,
                    onBrushParams = {
                        inkPreview.brushParams = it
                        inkPreview.paintToken++
                    },
                    checkParams = checkParams,
                    checkPaintToken = checkPaintToken,
                    onCheckParams = {
                        inkPreview.checkParams = it
                        inkPreview.checkPaintToken++
                    },
                    onReplayPaint = { inkPreview.paintToken++ },
                    onReplayCheckPaint = { inkPreview.checkPaintToken++ },
                    copyNote = copyNote,
                    onCopyValues = {
                        val text = formatBrushParamsCopy(brushParams)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("brush circle params", text))
                        Log.d("BrushLab", text)
                        copyNote = "Copied TS + Kotlin params"
                    },
                    onPasteValues = { raw ->
                        val parsed = parseBrushParamsFromText(raw, brushParams)
                        if (parsed == null) {
                            copyNote = "No brush knobs found in paste"
                        } else {
                            inkPreview.brushParams = parsed
                            inkPreview.paintToken++
                            copyNote = "Applied pasted params"
                        }
                    },
                    onPasteFromClipboard = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val raw = cm.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                        val parsed = parseBrushParamsFromText(raw, brushParams)
                        if (parsed == null) {
                            copyNote = "No brush knobs found in clipboard"
                        } else {
                            inkPreview.brushParams = parsed
                            inkPreview.paintToken++
                            copyNote = "Applied pasted params"
                        }
                    },
                    onCopyCheckValues = {
                        val text = formatBrushCheckCopy(checkParams)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("brush check params", text))
                        Log.d("BrushLab", text)
                        copyNote = "Copied check params"
                    },
                    onPasteCheckValues = { raw ->
                        val parsed = parseBrushCheckFromText(raw, checkParams)
                        if (parsed == null) {
                            copyNote = "No check knobs found in paste"
                        } else {
                            inkPreview.checkParams = parsed
                            inkPreview.checkPaintToken++
                            copyNote = "Applied check params"
                        }
                    },
                    onPasteCheckFromClipboard = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val raw = cm.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                        val parsed = parseBrushCheckFromText(raw, checkParams)
                        if (parsed == null) {
                            copyNote = "No check knobs found in clipboard"
                        } else {
                            inkPreview.checkParams = parsed
                            inkPreview.checkPaintToken++
                            copyNote = "Applied check params"
                        }
                    },
                    onOpenTimingsLab = onOpenTimingsLab,
                    onOpenTarjiLab = onOpenTarjiLab,
                    onOpenOrnamentsLab = onOpenOrnamentsLab,
                    onRecordSystemTrace = onRecordSystemTrace,
                )
            }

            Spacer(Modifier.height(56.dp))
            Colophon(
                developerModeEnabled = settings.developerModeEnabled,
                onLogoClick = {
                    developerTapCount++
                    if (developerTapCount >= 3) {
                        viewModel.settings.update {
                            it.copy(developerModeEnabled = !it.developerModeEnabled)
                        }
                        developerTapCount = 0
                    }
                },
                onLogoLongClick = {
                    if (settings.developerModeEnabled) onOpenTimingsLab()
                },
            )

            Spacer(Modifier.height(18.dp))
            Text(
                text = ATTRIBUTIONS,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Testing tools for development builds; controls here may change or vanish. */
@Composable
private fun DeveloperSection(
    viewModel: SettingsViewModel,
    settings: Settings,
    brushParams: BrushCircleParams,
    onBrushParams: (BrushCircleParams) -> Unit,
    checkParams: BrushCheckParams,
    checkPaintToken: Int,
    onCheckParams: (BrushCheckParams) -> Unit,
    onReplayPaint: () -> Unit,
    onReplayCheckPaint: () -> Unit,
    copyNote: String?,
    onCopyValues: () -> Unit,
    onPasteValues: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onCopyCheckValues: () -> Unit,
    onPasteCheckValues: (String) -> Unit,
    onPasteCheckFromClipboard: () -> Unit,
    onOpenTimingsLab: () -> Unit,
    onOpenTarjiLab: () -> Unit,
    onOpenOrnamentsLab: () -> Unit,
    onRecordSystemTrace: () -> Unit,
) {
    val context = LocalContext.current
    // Created on first audition tap: a SoundPool with nine loaded samples is
    // too heavy to spin up just because the settings sheet composed.
    var sounds by remember { mutableStateOf<PageTurnSounds?>(null) }
    var presetsOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var educationRearmed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { sounds?.release() }
    }

    SectionLabel("Developer")
    Spacer(Modifier.height(2.dp))
    Caption("Tools for testing work in progress.")

    Spacer(Modifier.height(20.dp))
    ToggleRow(
        label = "Contextual feature guides",
        checked = settings.educationGuidesEnabled,
        onChange = { enabled ->
            if (enabled) {
                viewModel.settings.rearmEducation()
                educationRearmed = true
            } else {
                educationRearmed = false
            }
            viewModel.settings.update { it.copy(educationGuidesEnabled = enabled) }
        },
        checkParams = checkParams,
        checkPaintToken = checkPaintToken,
    )
    Caption("Off by default. Guides appear only after their matching gesture.")

    Spacer(Modifier.height(12.dp))
    Text(
        text = "Replay feature guides",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable {
                viewModel.settings.rearmEducation()
                viewModel.settings.update { it.copy(educationGuidesEnabled = true) }
                educationRearmed = true
            }
            .padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.primary,
    )
    Caption(
        if (educationRearmed) {
            "Ready — open a chapter or add a new bookmark."
        } else {
            "Rearms each lesson for its next matching moment."
        },
    )

    if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 35) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Record & send performance profile",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .quietClickable(onClick = onRecordSystemTrace)
                .padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Caption(
            if (Build.VERSION.SDK_INT >= 37) {
                "Records ten seconds — use the app while it runs — then opens " +
                    "the share sheet. Also registers cold-start triggers."
            } else {
                "Records ten seconds — use the app while it runs — then opens " +
                    "the share sheet. Cold-start triggers need API 37."
            },
        )
    }

    Spacer(Modifier.height(20.dp))
    Text(
        text = "Timings Lab",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onOpenTimingsLab)
            .padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.primary,
    )
    Caption("Edit word-level timing marks; also opens from a word long-press.")

    Spacer(Modifier.height(20.dp))
    Text(
        text = "Tarjīʿ Lab",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onOpenTarjiLab)
            .padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.primary,
    )
    Caption(
        "Capture a word, see its waveform and tarjīʿ sine on a loop, and tune " +
            "the detector. Also opens from a word long-press.",
    )

    Spacer(Modifier.height(20.dp))
    Text(
        text = "Ornaments Lab",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onOpenOrnamentsLab)
            .padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.primary,
    )
    Caption("Explore, design, and save seeds for the procedural star-and-cross ornament generator.")

    Spacer(Modifier.height(18.dp))
    ToggleRow(
        label = "Ink Lab overlay",
        checked = settings.inkLabEnabled,
        onChange = { on -> viewModel.settings.update { it.copy(inkLabEnabled = on) } },
        checkParams = checkParams,
        checkPaintToken = checkPaintToken,
    )
    Caption("Live sliders over the reader's highlight tuning. Numbers persist until Reset.")

    Spacer(Modifier.height(20.dp))
    ToggleRow(
        label = "Hide bracketed English",
        checked = settings.hideEnglishParentheticals,
        onChange = { on -> viewModel.settings.update { it.copy(hideEnglishParentheticals = on) } },
        checkParams = checkParams,
        checkPaintToken = checkPaintToken,
    )
    Caption("English-only reading hides text in parentheses or square brackets, including the brackets.")

    Spacer(Modifier.height(28.dp))
    Text(
        "Verse share",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(2.dp))
    Caption("Four entry designs. Turning one on turns the others off.")
    ShareUxVariant.entries.filter { it != ShareUxVariant.OFF }.forEach { variant ->
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            label = variant.label,
            checked = settings.shareUxVariant == variant,
            onChange = { on ->
                viewModel.settings.update {
                    it.copy(shareUxVariant = if (on) variant else ShareUxVariant.OFF)
                }
            },
            checkParams = checkParams,
            checkPaintToken = checkPaintToken,
        )
        Caption(variant.note)
    }

    Spacer(Modifier.height(20.dp))
    Text(
        "Selector brush circle",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable { presetsOpen = !presetsOpen }
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = "Presets",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        DisclosureChevron(expanded = presetsOpen)
        Spacer(Modifier.size(12.dp))
        Text(
            text = brushCircleParams(settings.brushCircleStyle).label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
    if (presetsOpen) {
        BrushCircleStyle.entries.forEach { style ->
            SelectRow(
                label = brushCircleParams(style).label,
                selected = settings.brushCircleStyle == style,
                onClick = {
                    viewModel.settings.update { it.copy(brushCircleStyle = style) }
                    onBrushParams(brushCircleParams(style))
                },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    BrushLabSliders(params = brushParams, onChange = onBrushParams)

    Spacer(Modifier.height(22.dp))
    Text(
        "Ink check mark",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable {
                // Toggle preview by flipping a local... use paint replay via onReplayCheckPaint
                onReplayCheckPaint()
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Preview — see toggles above",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        InkCheck(checked = true, params = checkParams, paintToken = checkPaintToken)
    }
    CheckLabSliders(params = checkParams, onChange = onCheckParams)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = "Reset check",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable { onCheckParams(shippedCheckParams()) }
                .padding(vertical = 6.dp),
        )
        Text(
            text = "Replay paint",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = onReplayCheckPaint)
                .padding(vertical = 6.dp),
        )
        Text(
            text = "Copy check",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = onCopyCheckValues)
                .padding(vertical = 6.dp),
        )
        Text(
            text = "Paste check",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = onPasteCheckFromClipboard)
                .padding(vertical = 6.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = "Reset to preset",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable {
                    onBrushParams(brushCircleParams(settings.brushCircleStyle))
                }
                .padding(vertical = 6.dp),
        )
        Text(
            text = "Replay paint",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = onReplayPaint)
                .padding(vertical = 6.dp),
        )
        Text(
            text = "Copy values",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = onCopyValues)
                .padding(vertical = 6.dp),
        )
        Text(
            text = "Paste values",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .quietClickable(onClick = onPasteFromClipboard)
                .padding(vertical = 6.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    BasicTextField(
        value = pasteText,
        onValueChange = { pasteText = it },
        textStyle = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontFamily = MaterialTheme.typography.bodySmall.fontFamily,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        decorationBox = { inner ->
            Column {
                if (pasteText.isEmpty()) {
                    Text(
                        text = "Paste saved brush params here (TS or Kotlin)…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                inner()
            }
        },
    )
    Text(
        text = "Apply paste",
        style = MaterialTheme.typography.labelLarge,
        color = if (pasteText.isBlank()) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier
            .quietClickable(enabled = pasteText.isNotBlank()) {
                onPasteValues(pasteText)
                pasteText = ""
            }
            .padding(vertical = 6.dp),
    )
    if (copyNote != null) {
        Caption(copyNote)
    }

    Spacer(Modifier.height(20.dp))
    Text(
        "Home bookmark",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Caption("Changes the Chapters shortcut; bookmark ribbons inside verses are unchanged.")
    Spacer(Modifier.height(4.dp))
    HomeBookmarkStyle.entries.forEach { style ->
        SelectRow(
            label = when (style) {
                HomeBookmarkStyle.TOP_BOUND -> "Top-bound ribbon"
                HomeBookmarkStyle.SAVED_PASSAGES -> "Saved passages line"
            },
            selected = settings.homeBookmarkStyle == style,
            onClick = {
                viewModel.settings.update { it.copy(homeBookmarkStyle = style) }
            },
        )
    }

    Spacer(Modifier.height(18.dp))
    Text(
        "Page turn sounds",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Caption("Tap to hear the whole flip (lift → sweep → drop).")
    Spacer(Modifier.height(4.dp))
    PageTurnSounds.FLIPS.forEachIndexed { index, flip ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .quietClickable {
                    (sounds ?: PageTurnSounds(context).also { sounds = it })
                        .auditionFlip(index)
                }
                .padding(vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play ${flip.name}",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(14.dp))
            Text(
                flip.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── Header / footer ────────────────────────────────────────────────────────

@Composable
internal fun BackChevron(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .quietClickable(onClick = onBack),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The book's colophon: the app's own mark at the foot of the sheet. The
 * quiet triple-tap on the mark toggles developer mode. */
@Composable
private fun Colophon(
    developerModeEnabled: Boolean,
    onLogoClick: () -> Unit,
    onLogoLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .quietClickable(onClick = onLogoClick, onLongClick = onLogoLongClick),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
            AlphaTag()
        }
        Text(
            text = buildString {
                append("Version ${BuildConfig.VERSION_NAME}")
                if (developerModeEnabled) append(" · developer mode")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ── Selection vocabulary ───────────────────────────────────────────────────

/** Opens a sub-page on this same sheet — label, quiet summary, chevron. */
@Composable
private fun NavigateRow(
    label: String,
    note: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        DisclosureChevron(expanded = false)
    }
}

/** A single-choice row: a green ink disc leads the label, ink strength carries
 * the selection, and an optional trailing ornament (theme swatches) sits at the
 * edge. No radio, no ripple. */
@Composable
internal fun SelectRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    note: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val view = LocalView.current
    val textAlpha by animateFloatAsState(if (selected) 1f else 0.55f, label = "selectInk")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable {
                if (!selected) view.paperSelectHaptic()
                onClick()
            }
            .padding(vertical = 8.dp),
    ) {
        InkDisc(selected = selected)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(12.dp))
            trailing()
        }
    }
}

/** On/off row: the label carries the weight; a green tick inks itself in at the
 * trailing edge when on, and settles to a faint empty ring when off. */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    checkParams: BrushCheckParams = shippedCheckParams(),
    checkPaintToken: Int = 0,
) {
    val view = LocalView.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .quietClickable {
                val next = !checked
                view.paperToggleHaptic(turningOn = next)
                onChange(next)
            }
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        InkCheck(checked = checked, params = checkParams, paintToken = checkPaintToken)
    }
}




@Composable
private fun CheckLabSliders(
    params: BrushCheckParams,
    onChange: (BrushCheckParams) -> Unit,
) {
    data class Spec(
        val label: String,
        val value: Float,
        val range: ClosedFloatingPointRange<Float>,
        val integer: Boolean = false,
        val formatValue: ((Float) -> String)? = null,
        val set: (Float) -> BrushCheckParams,
    )
    val specs = listOf(
        Spec("Stem X", params.p0x, 0.05f..0.45f, formatValue = { "%.2f".format(it) }) {
            params.copy(p0x = it)
        },
        Spec("Stem Y", params.p0y, 0.2f..0.8f, formatValue = { "%.2f".format(it) }) {
            params.copy(p0y = it)
        },
        Spec("Valley X", params.p1x, 0.2f..0.6f, formatValue = { "%.2f".format(it) }) {
            params.copy(p1x = it)
        },
        Spec("Valley Y", params.p1y, 0.5f..0.95f, formatValue = { "%.2f".format(it) }) {
            params.copy(p1y = it)
        },
        Spec("Tip X", params.p2x, 0.55f..0.98f, formatValue = { "%.2f".format(it) }) {
            params.copy(p2x = it)
        },
        Spec("Tip Y", params.p2y, 0.05f..0.5f, formatValue = { "%.2f".format(it) }) {
            params.copy(p2y = it)
        },
        Spec("Size", params.sizeDp, 14f..36f, integer = true) {
            params.copy(sizeDp = it)
        },
        Spec("Stroke half", params.peakHalfDp, 0.6f..4.5f, formatValue = { "%.2f".format(it) }) {
            params.copy(peakHalfDp = it)
        },
        Spec("Nib bias", params.nibBias, 0f..0.8f, formatValue = { "%.2f".format(it) }) {
            params.copy(nibBias = it)
        },
        Spec("Attack", params.attack, 0.02f..0.4f, formatValue = { "%.3f".format(it) }) {
            params.copy(attack = it)
        },
        Spec("Release start", params.releaseStart, 0.4f..0.98f, formatValue = { "%.2f".format(it) }) {
            params.copy(releaseStart = it)
        },
        Spec(
            "Body amp",
            params.bodyAmp,
            0f..0.6f,
            formatValue = { "%.2f".format(it).trimEnd('0').trimEnd('.') },
        ) {
            params.copy(bodyAmp = it)
        },
        Spec("Body freq", params.bodyFreq, 0.5f..12f, formatValue = { "%.1f".format(it) }) {
            params.copy(bodyFreq = it)
        },
        Spec("Paint ms", params.paintMs.toFloat(), 200f..1200f, integer = true) {
            params.copy(paintMs = it.roundToInt())
        },
        Spec(
            "Alpha",
            params.alpha,
            0.3f..1f,
            formatValue = { "%.2f".format(it).trimEnd('0').trimEnd('.') },
        ) {
            params.copy(alpha = it)
        },
    )
    specs.forEach { spec ->
        BrushTuningSlider(
            label = spec.label,
            value = spec.value,
            range = spec.range,
            integer = spec.integer,
            formatValue = spec.formatValue,
            onChange = { onChange(spec.set(it)) },
        )
    }
}

@Composable
private fun BrushLabSliders(
    params: BrushCircleParams,
    onChange: (BrushCircleParams) -> Unit,
) {
    data class Spec(
        val label: String,
        val value: Float,
        val range: ClosedFloatingPointRange<Float>,
        val integer: Boolean = false,
        val formatValue: ((Float) -> String)? = null,
        val set: (Float) -> BrushCircleParams,
    )
    // Readout precision must match the real knob (not blanket %.2f) so paste
    // of 0.025 / 0.195 does not look like it became 0.03 / 0.20.
    val specs = listOf(
        Spec("Pad X", params.padXDp, 2f..24f, formatValue = { "%.1f".format(it) }) {
            params.copy(label = "Custom", padXDp = it)
        },
        Spec("Pad Y", params.padYDp, 0f..12f, formatValue = { "%.1f".format(it) }) {
            params.copy(label = "Custom", padYDp = it)
        },
        Spec("Stroke half", params.peakHalfDp, 0.6f..4.5f, formatValue = { "%.2f".format(it) }) {
            params.copy(label = "Custom", peakHalfDp = it)
        },
        Spec("Join °", params.startDeg, 0f..360f, integer = true) {
            params.copy(label = "Custom", startDeg = it)
        },
        Spec(
            label = "Start overshoot",
            value = params.startOvershoot,
            range = 0f..80f,
            integer = true,
            formatValue = { v ->
                val o = v.roundToInt()
                if (o > 0) "+$o°" else "$o°"
            },
            set = { params.copy(label = "Custom", startOvershoot = it) },
        ),
        Spec(
            label = "End overshoot",
            value = params.endOvershoot,
            range = 0f..80f,
            integer = true,
            formatValue = { v ->
                val o = v.roundToInt()
                if (o > 0) "+$o°" else "$o°"
            },
            set = { params.copy(label = "Custom", endOvershoot = it) },
        ),
        Spec("Bow / cross", params.bow, 0f..14f, formatValue = { "%.2f".format(it) }) {
            params.copy(label = "Custom", bow = it)
        },
        Spec("Bow span", params.bowSpan, 0.06f..0.4f, formatValue = { "%.2f".format(it) }) {
            params.copy(label = "Custom", bowSpan = it)
        },
        Spec("Breath", params.breath, 0f..0.08f, formatValue = { "%.3f".format(it) }) {
            params.copy(label = "Custom", breath = it)
        },
        Spec("Nib bias", params.nibBias, 0f..0.6f, formatValue = { "%.2f".format(it) }) {
            params.copy(label = "Custom", nibBias = it)
        },
        Spec("Attack", params.attack, 0.02f..0.3f, formatValue = { "%.3f".format(it) }) {
            params.copy(label = "Custom", attack = it)
        },
        Spec("Release start", params.releaseStart, 0.6f..0.98f, formatValue = { "%.2f".format(it) }) {
            params.copy(label = "Custom", releaseStart = it)
        },
        // Format without forced trailing zeros so 0.34 / 0.9 match paste text.
        Spec(
            "Body amp",
            params.bodyAmp,
            0f..0.6f,
            formatValue = { "%.2f".format(it).trimEnd('0').trimEnd('.') },
        ) {
            params.copy(label = "Custom", bodyAmp = it)
        },
        Spec("Body freq", params.bodyFreq, 0.5f..12f, formatValue = { "%.1f".format(it) }) {
            params.copy(label = "Custom", bodyFreq = it)
        },
        Spec("Paint ms", params.paintMs.toFloat(), 200f..1200f, integer = true) {
            params.copy(label = "Custom", paintMs = it.roundToInt())
        },
        Spec(
            "Alpha",
            params.alpha,
            0.3f..1f,
            formatValue = { "%.2f".format(it).trimEnd('0').trimEnd('.') },
        ) {
            params.copy(label = "Custom", alpha = it)
        },
    )
    specs.forEach { spec ->
        BrushTuningSlider(
            label = spec.label,
            value = spec.value,
            range = spec.range,
            integer = spec.integer,
            formatValue = spec.formatValue,
            onChange = { onChange(spec.set(it)) },
        )
    }
}

@Composable
private fun BrushTuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    integer: Boolean = false,
    formatValue: ((Float) -> String)? = null,
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
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatValue?.invoke(value)
                ?: if (integer) value.roundToInt().toString() else "%.2f".format(value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
    }
}

private fun formatBrushCheckCopy(p: BrushCheckParams): String {
    fun f(v: Float, digits: Int): String {
        val s = "%.${digits}f".format(v).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }
    return """
// Ink check — paste into the check lab or brushCheck.ts SHIPPED_CHECK_PARAMS
// TypeScript
{
  p0x: ${f(p.p0x, 2)},
  p0y: ${f(p.p0y, 2)},
  p1x: ${f(p.p1x, 2)},
  p1y: ${f(p.p1y, 2)},
  p2x: ${f(p.p2x, 2)},
  p2y: ${f(p.p2y, 2)},
  size: ${p.sizeDp.roundToInt()},
  peakHalf: ${f(p.peakHalfDp, 2)},
  nibBias: ${f(p.nibBias, 2)},
  attack: ${f(p.attack, 3)},
  releaseStart: ${f(p.releaseStart, 2)},
  bodyAmp: ${f(p.bodyAmp, 2)},
  bodyFreq: ${f(p.bodyFreq, 1)},
  paintMs: ${p.paintMs},
  alpha: ${f(p.alpha, 2)},
}

// Kotlin
BrushCheckParams(
    p0x = ${f(p.p0x, 2)}f,
    p0y = ${f(p.p0y, 2)}f,
    p1x = ${f(p.p1x, 2)}f,
    p1y = ${f(p.p1y, 2)}f,
    p2x = ${f(p.p2x, 2)}f,
    p2y = ${f(p.p2y, 2)}f,
    sizeDp = ${f(p.sizeDp, 1)}f,
    peakHalfDp = ${f(p.peakHalfDp, 2)}f,
    nibBias = ${f(p.nibBias, 2)}f,
    attack = ${f(p.attack, 3)}f,
    releaseStart = ${f(p.releaseStart, 2)}f,
    bodyAmp = ${f(p.bodyAmp, 2)}f,
    bodyFreq = ${f(p.bodyFreq, 1)}f,
    paintMs = ${p.paintMs},
    alpha = ${f(p.alpha, 2)}f,
)
""".trimIndent()
}

private fun parseBrushCheckFromText(text: String, base: BrushCheckParams): BrushCheckParams? {
    val ts = Regex("""\{[\s\S]*?\}""").find(text)?.value
    val kotlin = Regex("""BrushCheckParams\s*\([\s\S]*?\)""").find(text)?.value
    val source = ts ?: kotlin ?: text
    val re = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)f?\b""")
    var next = base
    var hits = 0
    for (m in re.findAll(source)) {
        val key = m.groupValues[1]
        val n = m.groupValues[2].toFloatOrNull() ?: continue
        val updated = when (key) {
            "p0x" -> next.copy(p0x = n)
            "p0y" -> next.copy(p0y = n)
            "p1x" -> next.copy(p1x = n)
            "p1y" -> next.copy(p1y = n)
            "p2x" -> next.copy(p2x = n)
            "p2y" -> next.copy(p2y = n)
            "size", "sizeDp" -> next.copy(sizeDp = n)
            "peakHalf", "peakHalfDp" -> next.copy(peakHalfDp = n)
            "nibBias" -> next.copy(nibBias = n)
            "attack" -> next.copy(attack = n)
            "releaseStart" -> next.copy(releaseStart = n)
            "bodyAmp" -> next.copy(bodyAmp = n)
            "bodyFreq" -> next.copy(bodyFreq = n)
            "paintMs" -> next.copy(paintMs = n.roundToInt())
            "alpha" -> next.copy(alpha = n)
            else -> null
        }
        if (updated != null) {
            next = updated
            hits++
        }
    }
    return if (hits > 0) next else null
}

private fun formatBrushParamsCopy(p: BrushCircleParams): String {
    fun f(v: Float, digits: Int): String {
        val s = "%.${digits}f".format(v).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }
    return """
// Brush circle — paste into the lab or into brushMark.ts BASE
// TypeScript  (startDeg = Join °)
{
  padX: ${f(p.padXDp, 2)},
  padY: ${f(p.padYDp, 2)},
  peakHalf: ${f(p.peakHalfDp, 2)},
  startDeg: ${f(p.startDeg, 1)}, // Join °
  startOvershoot: ${f(p.startOvershoot, 1)},
  endOvershoot: ${f(p.endOvershoot, 1)},
  bow: ${f(p.bow, 2)},
  bowSpan: ${f(p.bowSpan, 2)},
  breath: ${f(p.breath, 3)},
  nibBias: ${f(p.nibBias, 2)},
  attack: ${f(p.attack, 3)},
  releaseStart: ${f(p.releaseStart, 2)},
  bodyAmp: ${f(p.bodyAmp, 2)},
  bodyFreq: ${f(p.bodyFreq, 1)},
  paintMs: ${p.paintMs},
  alpha: ${f(p.alpha, 2)},
}

// Kotlin  (startDeg = Join °)
BrushCircleParams(
    label = "Custom",
    padXDp = ${f(p.padXDp, 2)}f,
    padYDp = ${f(p.padYDp, 2)}f,
    peakHalfDp = ${f(p.peakHalfDp, 2)}f,
    startDeg = ${f(p.startDeg, 1)}f, // Join °
    startOvershoot = ${f(p.startOvershoot, 1)}f,
    endOvershoot = ${f(p.endOvershoot, 1)}f,
    bow = ${f(p.bow, 2)}f,
    bowSpan = ${f(p.bowSpan, 2)}f,
    breath = ${f(p.breath, 3)}f,
    nibBias = ${f(p.nibBias, 2)}f,
    attack = ${f(p.attack, 3)}f,
    releaseStart = ${f(p.releaseStart, 2)}f,
    bodyAmp = ${f(p.bodyAmp, 2)}f,
    bodyFreq = ${f(p.bodyFreq, 1)}f,
    paintMs = ${p.paintMs},
    alpha = ${f(p.alpha, 2)}f,
)
""".trimIndent()
}

/**
 * Parse a copied brush-lab snippet (TS object and/or Kotlin BrushCircleParams).
 * Prefers the TypeScript `{ ... }` block when both are present. Starts from
 * shipped baseline then overlays found knobs so paste is not tainted by stale
 * lab state. Returns null if nothing numeric was found.
 */
private fun parseBrushParamsFromText(text: String, base: BrushCircleParams): BrushCircleParams? {
    // Prefer TS object; fall back to Kotlin constructor; else whole text.
    val ts = Regex("""\{[\s\S]*?\}""").find(text)?.value
    val kotlin = Regex("""BrushCircleParams\s*\([\s\S]*?\)""").find(text)?.value
    val source = ts ?: kotlin ?: text
    val re = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)f?\b""")
    // Ignore [base] — start from shipped baseline so partial pastes are clean.
    var next = brushCircleParams(BrushCircleStyle.BASELINE).copy(label = "Custom")
    var hits = 0
    for (m in re.findAll(source)) {
        val key = m.groupValues[1]
        val n = m.groupValues[2].toFloatOrNull() ?: continue
        val updated = when (key) {
            "padX", "padXDp" -> next.copy(padXDp = n)
            "padY", "padYDp" -> next.copy(padYDp = n)
            "peakHalf", "peakHalfDp" -> next.copy(peakHalfDp = n)
            "startDeg", "join", "joinDeg" -> next.copy(startDeg = n)
            "startOvershoot" -> next.copy(startOvershoot = n)
            "endOvershoot" -> next.copy(endOvershoot = n)
            "bow" -> next.copy(bow = n)
            "bowSpan" -> next.copy(bowSpan = n)
            "breath" -> next.copy(breath = n)
            "nibBias" -> next.copy(nibBias = n)
            "attack" -> next.copy(attack = n)
            "releaseStart" -> next.copy(releaseStart = n)
            "bodyAmp" -> next.copy(bodyAmp = n)
            "bodyFreq" -> next.copy(bodyFreq = n)
            "paintMs" -> next.copy(paintMs = n.roundToInt())
            "alpha" -> next.copy(alpha = n)
            else -> null
        }
        if (updated != null) {
            next = updated
            hits++
        }
    }
    return if (hits > 0) next else null
}


/** Text size as ink, not a Material slider: an "A" at each size flanks a thin
 * paper track with a green dot; tap or drag the track to choose, or tap the
 * small/large "A" to nudge one stop. The letters show the effect the setting
 * has. */
@Composable
internal fun TextSizeControl(scale: Float, onScale: (Float) -> Unit) {
    var widthPx by remember { mutableStateOf(1) }
    val fraction = ((scale - FONT_SCALE_MIN) / (FONT_SCALE_MAX - FONT_SCALE_MIN)).coerceIn(0f, 1f)
    val animFraction by animateFloatAsState(fraction, label = "sizeDot")
    val trackInk = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val accent = MaterialTheme.colorScheme.primary
    val glyphInk = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    fun setFromX(x: Float) {
        val f = (x / widthPx.coerceAtLeast(1)).coerceIn(0f, 1f)
        val stop = (f * FONT_SCALE_STOPS).roundToInt()
        onScale(FONT_SCALE_MIN + stop.toFloat() / FONT_SCALE_STOPS * (FONT_SCALE_MAX - FONT_SCALE_MIN))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "A",
            fontSize = 15.sp,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
            color = glyphInk,
            modifier = Modifier
                .quietClickable(role = Role.Button) { onScale(nudgeFontScale(scale, -1)) }
                .padding(horizontal = 6.dp, vertical = 10.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .padding(horizontal = 8.dp)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(Unit) { detectTapGestures { setFromX(it.x) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ -> setFromX(change.position.x) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                drawLine(
                    color = trackInk,
                    start = Offset(0f, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(
                    color = accent,
                    radius = 7.dp.toPx(),
                    center = Offset(size.width * animFraction, cy),
                )
            }
        }
        Text(
            text = "A",
            fontSize = 26.sp,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
            color = glyphInk,
            modifier = Modifier
                .quietClickable(role = Role.Button) { onScale(nudgeFontScale(scale, +1)) }
                .padding(horizontal = 6.dp, vertical = 10.dp),
        )
    }
}

/** Theme preview: main paper fill in a round rect with a gilded gold rim. */
@Composable
internal fun ThemeColorPreview(mode: ThemeMode) {
    val colors = themePreviewColors(mode)
    val fill = colors.first()
    // Paper gilt on light surfaces; warmer gilt on dark (matches Theme.kt accents).
    val gilt = when (mode) {
        ThemeMode.DARK, ThemeMode.ROYAL_GREEN -> Color(0xFFD9B44A)
        ThemeMode.LIGHT -> Color(0xFFC9A227)
        ThemeMode.SYSTEM -> {
            val c = colors.first()
            if (c.red + c.green + c.blue < 1.5f) Color(0xFFD9B44A) else Color(0xFFC9A227)
        }
    }
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 22.dp)
            .border(1.5.dp, gilt, RoundedCornerShape(6.dp))
            .background(fill, RoundedCornerShape(6.dp)),
    )
}

internal val ThemeMode.label: String
    get() = themeLabel(this)

// ── Quiet typographic helpers ──────────────────────────────────────────────

/** A section opening: generous air above, then the quiet label. */
@Composable
internal fun Section(text: String) {
    Spacer(Modifier.height(32.dp))
    SectionLabel(text)
    Spacer(Modifier.height(10.dp))
}

/** Letterspaced, low-ink label — a whisper that never competes with the
 * reading (docs/DESIGN.md). */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

@Composable
internal fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
    )
}
