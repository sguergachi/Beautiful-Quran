package com.beautifulquran.ornamentslab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.ui.theme.GeneratedChapterRosette
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.generatedFieldWeave
import com.beautifulquran.ui.theme.ornament.generateCoverOrnament
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.QuranTheme

/**
 * Ornaments Lab — a developer tool for exploring the procedural ornament
 * generator, reachable from Settings → Developer while developer mode is on
 * (see [com.beautifulquran.ui.settings.SettingsScreen]). Rises as the same
 * contrasting ink-bleed workbench as the Timings Lab.
 *
 * Explore a seed → live previews of its field pattern, chapter header,
 * medallion, and corner seal; read the seed's decoded traits; "design" an
 * ornament by choosing traits and searching for a seed that matches; and
 * save named seeds to reuse later. Every preview uses the very same
 * generator + renderers the app ships, so what you see is what you get.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrnamentsLabScreen(
    viewModel: OrnamentsLabViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val accents = LocalQuranAccents.current
    val sheen = remember { mutableStateOf(0.5f) }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    var seedText by remember(ui.seed) { mutableStateOf(ui.seed.toString()) }
    var saveName by remember { mutableStateOf("") }
    var fFold by remember { mutableStateOf<Int?>(null) }
    var fStar by remember { mutableStateOf<String?>(null) }
    var fKnot by remember { mutableStateOf<String?>(null) }
    var fCentre by remember { mutableStateOf<Boolean?>(null) }

    // Dark workbench: force light status-bar icons so the clock/battery don't
    // read as black-on-black over royal green. Restored when the Lab closes.
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

    fun copySeed() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Ornament seed", ui.seed.toString()))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .navigationBarsPadding(),
    ) {
        // ── Header ─────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Close",
                tint = colors.onBackground,
                modifier = Modifier
                    .quietClickable(onClick = onBack)
                    .padding(12.dp)
                    .size(24.dp),
            )
            Text(
                text = "Ornaments Lab",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "Copy seed",
                tint = QuranTheme.ink.body,
                modifier = Modifier
                    .quietClickable(onClick = ::copySeed)
                    .padding(12.dp)
                    .size(22.dp),
            )
        }

        // ── Seed stepper ───────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Stepper("‹", onClick = { viewModel.step(-1) })
            BasicTextField(
                value = seedText,
                onValueChange = { text ->
                    // Allow typing a minus and digits only; commit on a parseable int.
                    if (text.isEmpty() || text == "-" || text.toIntOrNull() != null) {
                        seedText = text
                        text.toIntOrNull()?.let(viewModel::setSeed)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(
                    color = colors.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                ),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .widthIn(min = 140.dp, max = 200.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        inner()
                    }
                },
            )
            Stepper("›", onClick = { viewModel.step(1) })
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Random",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.primary,
                modifier = Modifier
                    .quietClickable(onClick = viewModel::randomize)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }

        // ── Scrollable stages ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            PreviewStage(title = "Field pattern") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(colors.surface)
                        .generatedFieldWeave(
                            field = ui.cover.field,
                            ink = accents.gold,
                            embossLight = accents.embossLight,
                        ),
                )
            }

            // Chapter header is judged on paper — the whisper field is tuned
            // for a light page, so stage it on inverseSurface (parchment).
            PreviewStage(title = "Chapter header") {
                val paper = colors.inverseSurface
                val paperInk = colors.inverseOnSurface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                        .background(paper)
                        .generatedFieldWeave(
                            field = ui.chapter.field,
                            ink = paperInk.copy(alpha = 0.10f),
                            embossLight = accents.embossLight.copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    GeneratedChapterRosette(
                        spec = ui.chapter.rosette,
                        size = 64.dp,
                        brightGold = accents.goldBright,
                        deepGold = accents.goldDeep,
                        embossDark = accents.embossDark,
                        embossLight = accents.embossLight,
                        sheen = sheen,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewStage(title = "Medallion", modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(colors.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        GeneratedChapterRosette(
                            spec = ui.cover.medallion,
                            size = 148.dp,
                            brightGold = accents.goldBright,
                            deepGold = accents.goldDeep,
                            embossDark = accents.embossDark,
                            embossLight = accents.embossLight,
                            sheen = sheen,
                        )
                    }
                }
                PreviewStage(title = "Corner seal", modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(colors.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        GeneratedChapterRosette(
                            spec = ui.cover.cornerSeal,
                            size = 100.dp,
                            brightGold = accents.goldBright,
                            deepGold = accents.goldDeep,
                            embossDark = accents.embossDark,
                            embossLight = accents.embossLight,
                            sheen = sheen,
                        )
                    }
                }
            }

            PreviewStage(title = "Traits") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    TraitRow("Medallion fold", ui.traits.medallionFold.toString())
                    TraitRow("Seal fold", ui.traits.sealFold.toString())
                    TraitRow("Field star", ui.traits.fieldStar)
                    TraitRow("Field knot", ui.traits.fieldKnot)
                    TraitRow("Field centre", if (ui.traits.fieldCentre) "yes" else "no")
                    TraitRow("Border", ui.traits.borderSignature)
                }
            }

            PreviewStage(title = "Design by trait") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "Pick what you want, then find a seed that makes it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ChipGroup(
                        label = "Medallion fold",
                        options = listOf("8", "10", "12", "16"),
                        selected = fFold?.toString(),
                    ) { fFold = it?.toInt() }
                    ChipGroup(
                        label = "Field star",
                        options = listOf("khatam", "octagram"),
                        selected = fStar,
                    ) { fStar = it }
                    ChipGroup(
                        label = "Field knot",
                        options = listOf("square", "octagon"),
                        selected = fKnot,
                    ) { fKnot = it }
                    ChipGroup(
                        label = "Field centre",
                        options = listOf("yes", "no"),
                        selected = fCentre?.let { if (it) "yes" else "no" },
                    ) { fCentre = it?.let { v -> v == "yes" } }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Find a seed",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.primary,
                        modifier = Modifier
                            .quietClickable(onClick = {
                                viewModel.findSeed(fFold, fStar, fKnot, fCentre)
                            })
                            .padding(vertical = 6.dp),
                    )
                    if (ui.searchNote.isNotEmpty()) {
                        Text(
                            text = ui.searchNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            PreviewStage(title = "Saved seeds") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BasicTextField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = colors.onSurface,
                                fontSize = 16.sp,
                                fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (saveName.isEmpty()) {
                                        Text(
                                            text = "name this seed…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = QuranTheme.ink.quiet,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.primary,
                            modifier = Modifier
                                .quietClickable(onClick = {
                                    viewModel.save(saveName)
                                    saveName = ""
                                })
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                    if (saved.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Nothing saved yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        saved.forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .quietClickable(onClick = { viewModel.setSeed(entry.seed) })
                                    .padding(vertical = 8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(colors.background)
                                        .generatedFieldWeave(
                                            field = generateCoverOrnament(entry.seed).field,
                                            ink = accents.gold,
                                            embossLight = accents.embossLight,
                                        ),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = entry.seed.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Delete ${entry.name}",
                                    tint = QuranTheme.ink.quiet,
                                    modifier = Modifier
                                        .quietClickable(onClick = { viewModel.remove(entry.seed) })
                                        .padding(8.dp)
                                        .size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** A titled stage on the workbench — label above a distinct surface panel. */
@Composable
private fun PreviewStage(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun TraitRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Ink-only option chips — selected word takes the accent, unselected stays muted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    ) {
        options.forEach { option ->
            val isOn = selected == option
            Text(
                text = option,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isOn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    QuranTheme.ink.muted
                },
                modifier = Modifier
                    .quietClickable(onClick = {
                        onSelect(if (isOn) null else option)
                    })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
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
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
