package com.beautifulquran.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beautifulquran.data.model.Surah
import com.beautifulquran.ui.theme.ArabicTitleStyle
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.quietClickable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/** Dark ink for text sitting on the flat gold Open button — readable on both light and dark gold. */
private val OnGold = Color(0xFF241B00)
private val SurahWheelHorizontalPadding = 8.dp
private val SurahNumberColumnWidth = 22.dp
private val SurahNameGap = 8.dp

/**
 * A quiet pane of two dials that fades in beneath the search field while it
 * holds focus, floating above the surah list. The left dial spins across every
 * surah (number and name); the right dial spins across the ayahs of whichever
 * surah is centred. Centring a pair jumps straight to that ayah — a scrollable
 * alternative to typing.
 *
 * The whole pane cross-fades on [visible], so it appears and dismisses as one
 * soft breath rather than sliding into the layout.
 */
@Composable
fun SearchDialsPane(
    surahs: List<Surah>,
    visible: Boolean,
    onOpen: (surahId: Int, ayah: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && surahs.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(durationMillis = 220)),
        exit = fadeOut(animationSpec = tween(durationMillis = 180)),
        modifier = modifier,
    ) {
        SearchDials(surahs = surahs, onOpen = onOpen)
    }
}

@Composable
private fun SearchDials(
    surahs: List<Surah>,
    onOpen: (surahId: Int, ayah: Int) -> Unit,
) {
    val accents = LocalQuranAccents.current
    var surahIndex by remember { mutableIntStateOf(0) }
    var ayah by remember { mutableIntStateOf(1) }

    val currentSurah = surahs[surahIndex.coerceIn(0, surahs.lastIndex)]
    // Keep the ayah dial within the centred surah's bounds as it changes.
    val boundedAyah = ayah.coerceIn(1, currentSurah.ayahCount)
    if (boundedAyah != ayah) ayah = boundedAyah

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            WheelHeader()
            Spacer(Modifier.height(4.dp))

            val itemHeight = 42.dp
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val wheelEdgePadding = ((maxHeight - itemHeight) / 2).coerceAtLeast(0.dp)
                // A single gilded band marks the centre selection across both dials.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accents.gold.copy(alpha = 0.12f)),
                )
                Row(Modifier.fillMaxWidth()) {
                    SearchDialWheel(
                        itemCount = surahs.size,
                        selectedIndex = surahIndex,
                        itemHeight = itemHeight,
                        edgePadding = wheelEdgePadding,
                        onSelectedIndexChange = { surahIndex = it },
                        modifier = Modifier.weight(1.7f),
                    ) { index, selected ->
                        SurahItem(surahs[index], selected)
                    }
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    SearchDialWheel(
                        itemCount = currentSurah.ayahCount,
                        selectedIndex = ayah - 1,
                        itemHeight = itemHeight,
                        edgePadding = wheelEdgePadding,
                        onSelectedIndexChange = { ayah = it + 1 },
                        modifier = Modifier.weight(1f),
                    ) { index, selected ->
                        NumberItem(index + 1, selected)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            GoRow(
                surah = currentSurah,
                ayah = ayah,
                onClick = { onOpen(currentSurah.id, ayah) },
            )
        }
    }
}

@Composable
private fun WheelHeader() {
    Row(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .weight(1.7f)
                .padding(horizontal = SurahWheelHorizontalPadding),
        ) {
            Spacer(Modifier.width(SurahNumberColumnWidth))
            Spacer(Modifier.width(SurahNameGap))
            WheelLabel("Surah")
        }
        Spacer(Modifier.padding(horizontal = 6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f),
        ) {
            WheelLabel("Ayah")
        }
    }
}

@Composable
private fun WheelLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

@Composable
private fun SurahItem(surah: Surah, selected: Boolean) {
    val accents = LocalQuranAccents.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SurahWheelHorizontalPadding),
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.width(SurahNumberColumnWidth),
        ) {
            Text(
                text = surah.id.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = accents.gold.copy(alpha = if (selected) 0.95f else 0.6f),
            )
        }
        Spacer(Modifier.width(SurahNameGap))
        Text(
            text = surah.nameTransliteration,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = surah.nameArabic,
            style = ArabicTitleStyle,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.primary.copy(
                alpha = if (selected) 0.9f else 0.4f,
            ),
        )
    }
}

@Composable
private fun NumberItem(value: Int, selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun GoRow(surah: Surah, ayah: Int, onClick: () -> Unit) {
    val accents = LocalQuranAccents.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "${surah.nameTransliteration} · Ayah $ayah",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Text(
            text = "Open",
            style = MaterialTheme.typography.labelLarge,
            color = OnGold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .quietClickable(onClick = onClick)
                .background(accents.gold)
                .padding(horizontal = 22.dp, vertical = 9.dp),
        )
    }
}

/**
 * A vertical snap dial: [itemCount] rows scroll under a fixed centre slot, and
 * whichever row rests in the centre reports through [onSelectedIndexChange].
 * Setting [selectedIndex] from outside animates the dial to that row (used when
 * changing surah clamps the ayah). The centred index is read from layout during
 * the draw phase, so spinning the dial never triggers recomposition upstream.
 */
@Composable
internal fun SearchDialWheel(
    itemCount: Int,
    selectedIndex: Int,
    itemHeight: Dp,
    edgePadding: Dp,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // The rows dissolve into whatever paper the wheel is standing on. That is
    // this sheet's `surface` here, but the reader's repeat sheet is painted in
    // `background` — pass the wrong one and the gradient stops being a fade and
    // becomes a visible rectangle over the top and bottom rows.
    fadeColor: Color = MaterialTheme.colorScheme.surface,
    itemContent: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val maxIndex = (itemCount - 1).coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, maxIndex),
    )
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val clearColor = fadeColor.copy(alpha = 0f)

    // Pull the dial to a row chosen from outside (e.g. ayah clamped to a surah).
    LaunchedEffect(selectedIndex, itemCount) {
        val target = selectedIndex.coerceIn(0, maxIndex)
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex != target) {
            listState.animateScrollToItem(target)
        }
    }

    // Report whichever row is nearest the viewport centre as the selection.
    LaunchedEffect(listState, itemCount) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - viewportCenter)
            }?.index
        }
            .distinctUntilChanged()
            .collect { index ->
                if (index != null) onSelectedIndexChange(index)
            }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to fadeColor,
                        0.22f to clearColor,
                        0.78f to clearColor,
                        1f to fadeColor,
                    ),
                )
            },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = edgePadding),
            flingBehavior = snapBehavior,
            modifier = Modifier.fillMaxHeight(),
        ) {
            items(count = itemCount, key = { it }) { i ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                ) {
                    itemContent(i, i == selectedIndex)
                }
            }
        }
    }
}
