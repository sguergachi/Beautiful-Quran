package com.beautifulquran.ui.rootviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.theme.quietClickable

/**
 * Developer-mode ink-bleed chooser: three quiet lines, no Material dialog.
 * See docs/ROOT_VIEWER.md and docs/TARJI_LAB.md.
 */
@Composable
fun WordHoldChooser(
    onOpenRootViewer: () -> Unit,
    onOpenTimingsLab: () -> Unit,
    onOpenTarjiLab: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .quietClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                // Absorb the column's own taps so dismissing only happens on the quiet margins.
                .quietClickable(onClick = {}),
        ) {
            Text(
                text = "Open",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Root word",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .quietClickable(onClick = onOpenRootViewer)
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Timings Lab",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .quietClickable(onClick = onOpenTimingsLab)
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tarjīʿ Lab",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .quietClickable(onClick = onOpenTarjiLab)
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(36.dp))
            Text(
                text = "Not now",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .quietClickable(onClick = onDismiss)
                    .padding(vertical = 8.dp),
            )
        }
    }
}
