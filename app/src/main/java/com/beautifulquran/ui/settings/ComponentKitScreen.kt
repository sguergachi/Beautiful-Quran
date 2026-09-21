package com.beautifulquran.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.data.BrushCircleStyle
import com.beautifulquran.ui.theme.BrushCheckParams
import com.beautifulquran.ui.theme.BrushCircleParams
import com.beautifulquran.ui.theme.InkCircledChoiceRow
import com.beautifulquran.ui.theme.LocalNuqtaParams
import com.beautifulquran.ui.theme.ShippedNuqtaParams
import com.beautifulquran.ui.theme.brushCircleParams
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.shippedCheckParams
import com.beautifulquran.ui.theme.verticalFadingEdges
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.beautifulquran.ui.theme.QuranTheme

/** The components the kit tunes, in swipe order. */
private enum class KitComponent(val title: String, val blurb: String) {
    NUQTA("Nuqta radio dot", "Every single-choice row."),
    CHECK("Ink check mark", "Every on/off row."),
    BRUSH("Brush circle", "Side-by-side and stacked choice words."),
}

/**
 * Settings → Developer → Component kit: one of the app's own marks per page,
 * held in a preview that stays put while its dials scroll beneath it. Swipe
 * sideways for the next or previous component.
 *
 * Edits live in [SettingsInkPreviewState] for the session, so every real
 * control on the Settings and Customize sheets follows them too. Ship a tuning
 * by copying it into the component's defaults (docs/DESIGN.md).
 */
@Composable
internal fun ComponentKitScreen(
    viewModel: SettingsViewModel,
    inkPreview: SettingsInkPreviewState,
    onBack: () -> Unit,
) {
    val pages = KitComponent.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    if (note != null) {
        LaunchedEffect(note) {
            delay(2000L)
            note = null
        }
    }

    CompositionLocalProvider(LocalNuqtaParams provides inkPreview.nuqtaParams) {
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
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                Spacer(Modifier.height(20.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                ) {
                    BackChevron(onBack)
                    Spacer(Modifier.weight(1f))
                    KitPageDots(
                        count = pages.size,
                        current = pager.currentPage,
                        onPick = { scope.launch { pager.animateScrollToPage(it) } },
                    )
                }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { index ->
                    val component = pages[index]
                    KitPage(
                        component = component,
                        index = index,
                        count = pages.size,
                        note = note.takeIf { pager.currentPage == index },
                        preview = {
                            when (component) {
                                KitComponent.NUQTA -> NuqtaPreview()
                                KitComponent.CHECK -> CheckPreview(inkPreview)
                                KitComponent.BRUSH -> BrushPreview(inkPreview)
                            }
                        },
                        dials = {
                            when (component) {
                                KitComponent.NUQTA -> NuqtaDials(inkPreview) { note = it }
                                KitComponent.CHECK -> CheckDials(inkPreview) { note = it }
                                KitComponent.BRUSH -> BrushDials(viewModel, inkPreview) { note = it }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One component: its name, a preview that holds still, and dials that scroll. */
@Composable
private fun KitPage(
    component: KitComponent,
    index: Int,
    count: Int,
    note: String?,
    preview: @Composable () -> Unit,
    dials: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = component.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${index + 1} of $count · ${component.blurb}",
            style = MaterialTheme.typography.labelSmall,
            color = QuranTheme.ink.muted,
        )
        Spacer(Modifier.height(16.dp))
        // The stage: stays put while the dials below scroll, so every change
        // is seen on the mark itself.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp),
        ) {
            preview()
        }
        if (note != null) Caption(note)
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalFadingEdges(
                    color = MaterialTheme.colorScheme.background,
                    top = 16.dp,
                    bottom = 40.dp,
                )
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            dials()
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Which component of how many — also a way to jump straight to one. */
@Composable
private fun KitPageDots(count: Int, current: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { i ->
            val color by animateColorAsState(
                if (i == current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                },
                label = "kitDot",
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .quietClickable { onPick(i) },
            ) {
                Canvas(Modifier.size(6.dp)) { drawCircle(color) }
            }
        }
    }
}

// ------------------------------------------------------------------ nuqta

@Composable
private fun NuqtaPreview() {
    var choice by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        listOf("First choice", "Second choice").forEachIndexed { index, label ->
            SelectRow(label = label, selected = choice == index, onClick = { choice = index })
        }
    }
}

@Composable
private fun NuqtaDials(inkPreview: SettingsInkPreviewState, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val params = inkPreview.nuqtaParams
    NuqtaKnobGroups.forEach { group ->
        DialGroupLabel(group.title)
        group.knobs.forEach { knob ->
            BrushTuningSlider(
                label = knob.label,
                value = knob.get(params),
                range = knob.range,
                integer = knob.digits == 0,
                formatValue = { formatNuqtaKnob(knob, it) },
                onChange = { inkPreview.nuqtaParams = knob.set(params, it) },
            )
        }
    }
    KitActions(
        "Reset" to { inkPreview.nuqtaParams = ShippedNuqtaParams },
        "Copy" to {
            copyToClipboard(context, "nuqta params", formatNuqtaCopy(params))
            onNote("Copied nuqta params")
        },
        "Paste" to {
            val parsed = parseNuqtaFromText(readClipboard(context), params)
            if (parsed == null) {
                onNote("No nuqta knobs found in clipboard")
            } else {
                inkPreview.nuqtaParams = parsed
                onNote("Applied nuqta params")
            }
        },
    )
}

// ------------------------------------------------------------------ check

@Composable
private fun CheckPreview(inkPreview: SettingsInkPreviewState) {
    var checked by remember { mutableStateOf(true) }
    ToggleRow(
        label = "Preview",
        checked = checked,
        onChange = { checked = it },
        checkParams = inkPreview.checkParams,
        checkPaintToken = inkPreview.checkPaintToken,
    )
}

@Composable
private fun CheckDials(inkPreview: SettingsInkPreviewState, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val params = inkPreview.checkParams
    fun apply(next: BrushCheckParams) {
        inkPreview.checkParams = next
        inkPreview.checkPaintToken++
    }
    DialGroupLabel("Stroke")
    CheckLabSliders(params = params, onChange = ::apply)
    KitActions(
        "Reset" to { apply(shippedCheckParams()) },
        "Replay" to { inkPreview.checkPaintToken++ },
        "Copy" to {
            copyToClipboard(context, "brush check params", formatBrushCheckCopy(params))
            onNote("Copied check params")
        },
        "Paste" to {
            val parsed = parseBrushCheckFromText(readClipboard(context), params)
            if (parsed == null) {
                onNote("No check knobs found in clipboard")
            } else {
                apply(parsed)
                onNote("Applied check params")
            }
        },
    )
}

// ------------------------------------------------------------------ brush

@Composable
private fun BrushPreview(inkPreview: SettingsInkPreviewState) {
    var choice by remember { mutableStateOf("Scroll") }
    InkCircledChoiceRow(
        entries = listOf("Scroll", "Mushaf", "Both"),
        selected = choice,
        label = { it },
        onSelect = { choice = it },
        params = inkPreview.brushParams,
        paintToken = inkPreview.paintToken,
    )
}

@Composable
private fun BrushDials(
    viewModel: SettingsViewModel,
    inkPreview: SettingsInkPreviewState,
    onNote: (String) -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.settings.collectAsStateWithLifecycle()
    val params = inkPreview.brushParams
    fun apply(next: BrushCircleParams) {
        inkPreview.brushParams = next
        inkPreview.paintToken++
    }
    DialGroupLabel("Preset")
    BrushCircleStyle.entries.forEach { style ->
        SelectRow(
            label = brushCircleParams(style).label,
            selected = settings.brushCircleStyle == style,
            onClick = {
                viewModel.settings.update { it.copy(brushCircleStyle = style) }
                apply(brushCircleParams(style))
            },
        )
    }
    DialGroupLabel("Stroke")
    BrushLabSliders(params = params, onChange = ::apply)
    KitActions(
        "Reset" to { apply(brushCircleParams(settings.brushCircleStyle)) },
        "Replay" to { inkPreview.paintToken++ },
        "Copy" to {
            copyToClipboard(context, "brush circle params", formatBrushParamsCopy(params))
            onNote("Copied TS + Kotlin params")
        },
        "Paste" to {
            val parsed = parseBrushParamsFromText(readClipboard(context), params)
            if (parsed == null) {
                onNote("No brush knobs found in clipboard")
            } else {
                apply(parsed)
                onNote("Applied pasted params")
            }
        },
    )
}

// ------------------------------------------------------------------ shared

@Composable
private fun DialGroupLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun KitActions(vararg actions: Pair<String, () -> Unit>) {
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        actions.forEach { (label, onClick) ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .quietClickable(onClick = onClick)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Log.d("ComponentKit", text)
}

private fun readClipboard(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        .orEmpty()
}
