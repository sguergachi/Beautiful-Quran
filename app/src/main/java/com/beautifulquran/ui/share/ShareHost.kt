package com.beautifulquran.ui.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.ui.theme.InkRevealOverlay
import com.beautifulquran.ui.theme.absorbPointerEvents
import com.beautifulquran.ui.theme.contrastingOverlayColorScheme

/**
 * Owns gather/send back handling and the Send ink-bleed so MainActivity does
 * not grow another cluster of overlay booleans.
 *
 * Always composed while the reader sheet exists so system-back can exit
 * gather mode even before the Send page opens.
 */
@Composable
fun ShareHost(
    viewModel: ShareViewModel,
    themeMode: ThemeMode,
    onOverlayRenderedChange: (Boolean) -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var sendRendered by remember { mutableStateOf(false) }

    BackHandler(enabled = ui.sendOpen) { viewModel.closeSend() }
    BackHandler(enabled = ui.gathering && !ui.sendOpen) { viewModel.exitGather() }
    BackHandler(enabled = ui.prompt != null && !ui.gathering) { viewModel.hidePrompt() }

    LaunchedEffect(ui.pendingShareText) {
        val text = ui.pendingShareText ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, null))
        viewModel.consumePendingShareText()
        if (!viewModel.ui.value.sendOpen) viewModel.exitGather()
    }

    LaunchedEffect(ui.pendingShareImageUri) {
        val uriString = ui.pendingShareImageUri ?: return@LaunchedEffect
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Some targets (Messages, Drive) also look at clip data.
            clipData = android.content.ClipData.newUri(
                context.contentResolver,
                "Beautiful Quran",
                uri,
            )
        }
        context.startActivity(Intent.createChooser(intent, null))
        viewModel.consumePendingShareImage()
        if (!viewModel.ui.value.sendOpen) viewModel.exitGather()
    }

    LaunchedEffect(sendRendered) {
        onOverlayRenderedChange(sendRendered)
    }

    val overlayColors = contrastingOverlayColorScheme(themeMode)
    InkRevealOverlay(
        visible = ui.sendOpen,
        backgroundColor = overlayColors.background,
        // Bloom from the lower page (near the former gather control).
        originX = 0.5f,
        originY = 0.92f,
        modifier = Modifier.zIndex(4.5f),
        onRenderedChange = { sendRendered = it },
    ) {
        MaterialTheme(colorScheme = overlayColors, typography = MaterialTheme.typography) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize().absorbPointerEvents())
                ShareComposeSheet(
                    verseLines = ui.verseLines,
                    preparingText = ui.preparingText,
                    preparingImage = ui.preparingImage,
                    error = ui.error,
                    onBack = viewModel::closeSend,
                    onShareText = { viewModel.shareAsText() },
                    onShareImage = {
                        if (activity != null) {
                            viewModel.shareAsImage(activity)
                        } else {
                            // Should not happen — ShareHost lives in MainActivity.
                            viewModel.shareAsText()
                        }
                    },
                    onRemove = viewModel::remove,
                )
            }
        }
    }
}
