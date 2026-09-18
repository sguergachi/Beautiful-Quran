package com.beautifulquran.ui.settings

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.Modifier
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
                Caption("Choose a voice. Star favorites to keep them on the main Settings leaf.")
                Spacer(Modifier.height(32.dp))
                SectionLabel("All reciters")
                Spacer(Modifier.height(4.dp))
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
    val textAlpha by animateFloatAsState(if (selected) 1f else 0.55f, label = "reciterInk")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .quietClickable {
                if (!selected) view.paperSelectHaptic()
                onSelect()
            }
            .padding(vertical = 8.dp),
    ) {
        InkNuqta(selected = selected)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = reciter.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
            )
            if (reciter.style != "Murattal") {
                Text(
                    text = reciter.style,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
        }
        if (favorite != null && onToggleFavorite != null) {
            val gold = LocalQuranAccents.current.gold
            Box(
                modifier = Modifier
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
    }
}
