package com.beautifulquran.ui.settings

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import com.beautifulquran.ui.theme.QuranTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.ui.theme.InkNuqta
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.paperSelectHaptic
import com.beautifulquran.ui.theme.paperToggleHaptic
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges

/** The complete voice catalog; Settings itself keeps only the reader's favorites. */
@Composable
internal fun RecitersPage(
    reciters: List<Reciter>,
    selectedReciterId: Int,
    favoriteReciterIds: Set<Int>,
    onSelect: (Reciter) -> Unit,
    onToggleFavorite: (Reciter) -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
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
                ),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            item(key = "reciters-header") {
                Spacer(Modifier.height(20.dp))
                BackChevron(onBack)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Reciters",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                LeafIntro("Choose a voice. Star favorites to keep them on the main Settings leaf.")
                // No "All reciters" label: the title already says it.
                Spacer(Modifier.height(24.dp))
            }
            items(reciters.sortedBy(Reciter::name), key = Reciter::id) { reciter ->
                ReciterChoiceRow(
                    reciter = reciter,
                    selected = reciter.id == selectedReciterId,
                    favorite = reciter.id in favoriteReciterIds,
                    onSelect = { onSelect(reciter) },
                    onToggleFavorite = { onToggleFavorite(reciter) },
                )
            }
            item(key = "reciters-foot") { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/** One voice line: a calligraphic nuqta selects; optional gilding keeps it close. */
@Composable
internal fun ReciterChoiceRow(
    reciter: Reciter,
    selected: Boolean,
    onSelect: () -> Unit,
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val view = LocalView.current
    // Unselected voices sit on the secondary rung, not a 55 % fade: they are
    // live choices and must not read as disabled (#897).
    val nameInk by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else QuranTheme.ink.secondary,
        label = "reciterInk",
    )
    // Nuqta and star centre on the name as it is actually measured; a
    // Mujawwad / Muallim subtitle hangs directly under the name instead of
    // pulling the marks off the name's line (#897). A Row centred the marks on
    // name + subtitle; a fixed leading guessed the name box and missed.
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .quietClickable {
                if (!selected) view.paperSelectHaptic()
                onSelect()
            }
            .padding(vertical = 8.dp),
        content = {
            Box(Modifier.layoutId(NuqtaSlot)) { InkNuqta(selected = selected) }
            Text(
                text = reciter.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = nameInk,
                modifier = Modifier.layoutId(NameSlot),
            )
            if (reciter.style != "Murattal") {
                Text(
                    text = reciter.style,
                    style = MaterialTheme.typography.labelSmall,
                    // A fact under a name: the same rung as Settings' row notes.
                    color = QuranTheme.ink.muted,
                    modifier = Modifier.layoutId(StyleSlot),
                )
            }
            if (favorite != null && onToggleFavorite != null) {
                val gold = LocalQuranAccents.current.gold
                Box(
                    modifier = Modifier
                        .layoutId(StarSlot)
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (favorite) {
                                "Remove ${reciter.name} from favorites"
                            } else {
                                "Add ${reciter.name} to favorites"
                            }
                            toggleableState = if (favorite) {
                                ToggleableState.On
                            } else {
                                ToggleableState.Off
                            }
                        }
                        .quietClickable(role = Role.Checkbox) {
                            view.paperToggleHaptic(!favorite)
                            onToggleFavorite()
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        tint = if (favorite) {
                            gold
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = 16.dp.roundToPx()
        val nuqta = measurables.first { it.layoutId == NuqtaSlot }.measure(loose)
        val star = measurables.firstOrNull { it.layoutId == StarSlot }?.measure(loose)
        val textWidth = (constraints.maxWidth - nuqta.width - gap - (star?.width ?: 0)).coerceAtLeast(0)
        val textConstraints = Constraints(maxWidth = textWidth)
        val name = measurables.first { it.layoutId == NameSlot }.measure(textConstraints)
        val style = measurables.firstOrNull { it.layoutId == StyleSlot }?.measure(textConstraints)
        val slot = maxOf(name.height, nuqta.height, star?.height ?: 0)
        val nameY = (slot - name.height) / 2
        val styleY = nameY + name.height
        val height = maxOf(slot, styleY + (style?.height ?: 0))
        layout(constraints.maxWidth, height) {
            nuqta.place(0, (slot - nuqta.height) / 2)
            name.place(nuqta.width + gap, nameY)
            style?.place(nuqta.width + gap, styleY)
            star?.place(constraints.maxWidth - star.width, (slot - star.height) / 2)
        }
    }
}

private const val NuqtaSlot = "nuqta"
private const val NameSlot = "name"
private const val StyleSlot = "style"
private const val StarSlot = "star"
