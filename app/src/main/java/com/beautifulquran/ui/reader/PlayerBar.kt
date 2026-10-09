package com.beautifulquran.ui.reader

import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.beautifulquran.playback.PlayerUiState
import com.beautifulquran.ui.theme.DisclosureChevron
import com.beautifulquran.ui.theme.QuranTheme
import com.beautifulquran.ui.theme.quietClickable

/** Gap before the disclosure chevron, matched by [ReciterNameButton]'s centering offset. */
private val ReciterChevronGap = 2.dp

/** Chevron box. [DisclosureChevron] also asks for 20dp; the outer size here wins. */
private val ReciterChevronSize = 16.dp

/**
 * Reciter row plus the transport, without the navigation inset.
 * The live bar adds the phone's navigation inset under the 4 dp foot.
 * The verse list clears the measured height, which includes that inset.
 */
internal val ChapterPlaybackBodyHeight = 108.dp

/**
 * Flat playback controls that sit on the same sheet of paper as the text —
 * no elevation, no card. The reading column fades out just above it.
 *
 * The reciter name gets its own centered line above the transport row, so the
 * row itself stays symmetric — two controls either side of play — and the
 * play button lands exactly on the page's center line.
 */
@Composable
fun PlayerBar(
    state: PlayerUiState,
    isThisSurahLoaded: Boolean,
    /**
     * When false, transport stays visible but does not compete with a
     * contextual guide's **Got it** action drawn over the same corner.
     */
    enabled: Boolean = true,
    chromeAlpha: () -> Float,
    reciterName: String,
    onPlayPause: () -> Unit,
    onFastBackward: () -> Unit,
    onFastForward: () -> Unit,
    onRepeatClick: () -> Unit,
    onSpeed: () -> Unit,
    onReciterClick: () -> Unit,
    onDismissError: () -> Unit,
    inkLabAvailable: Boolean = false,
    inkLabOpen: Boolean = false,
    onInkLabClick: () -> Unit = {},
    /**
     * Cover controls in the reciter row, such as Close. The scrolling play
     * bar never puts a back arrow here. The caller aligns them. The
     * transport underneath does not move.
     */
    edgeChrome: @Composable BoxScope.() -> Unit = {},
    /** Cover context between the reciter and the transport. */
    belowReciter: @Composable () -> Unit = {},
    /** Insets the name so [edgeChrome] does not paint through it. */
    edgePad: Dp = 0.dp,
    /**
     * Puts the phone's navigation inset inside the paper, under the 4 dp
     * foot, so the transport stays above the home gesture bar. The paper
     * still meets the screen edge.
     */
    includeNavigationPadding: Boolean = true,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 340
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (includeNavigationPadding) Modifier.navigationBarsPadding() else Modifier,
                ),
        ) {
            Box(Modifier.fillMaxWidth()) {
                if (inkLabOpen && state.error == null) {
                    InkLabPanel(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(start = if (inkLabAvailable) 40.dp else 0.dp, end = edgePad),
                    )
                } else {
                    val centeredInset = maxOf(edgePad, if (inkLabAvailable) 48.dp else 0.dp)
                    ReciterNameButton(
                        name = reciterName,
                        notice = state.error,
                        onDismissNotice = onDismissError,
                        noticeDismissAtStart = edgePad > 0.dp,
                        onClick = onReciterClick,
                        enabled = enabled,
                        disclosure = true,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = if (state.error != null) 0.dp else centeredInset)
                            .graphicsLayer { alpha = if (state.error != null) 1f else chromeAlpha() },
                    )
                }
                if (inkLabAvailable && state.error == null) {
                    InkLabToggleButton(
                        expanded = inkLabOpen,
                        onClick = onInkLabClick,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .graphicsLayer { alpha = chromeAlpha() },
                    )
                }
                edgeChrome()
            }
            belowReciter()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    if (compact) 4.dp else 12.dp,
                    Alignment.CenterHorizontally,
                ),
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 8.dp else 12.dp,
                        end = if (compact) 8.dp else 12.dp,
                        bottom = 4.dp,
                    ),
            ) {
                val repeatActive = state.repeatMode != Player.REPEAT_MODE_OFF || state.repeatRange != null
                val speedActive = state.speed != 1f
                val singleAyahRange = state.repeatRange?.let { it.first == it.last } == true
                IconButton(
                    onClick = onRepeatClick,
                    enabled = enabled,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { alpha = if (repeatActive) 1f else chromeAlpha() },
                ) {
                    Icon(
                        imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE || singleAyahRange) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (repeatActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            QuranTheme.ink.furniture
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(
                    onClick = onFastBackward,
                    enabled = enabled && isThisSurahLoaded,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.FastRewind,
                        contentDescription = "Fast backward",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onPlayPause,
                    enabled = enabled,
                    modifier = Modifier.size(56.dp),
                ) {
                    if (state.isBuffering && isThisSurahLoaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying && isThisSurahLoaded) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onFastForward,
                    enabled = enabled && isThisSurahLoaded,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.FastForward,
                        contentDescription = "Fast forward",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onSpeed,
                    enabled = enabled,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { alpha = if (speedActive) 1f else chromeAlpha() },
                ) {
                    Text(
                        text = "${if (state.speed % 1f == 0f) state.speed.toInt() else state.speed}×",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = if (speedActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            QuranTheme.ink.furniture
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun InkLabToggleButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = if (expanded) "Close Ink Lab" else "Open Ink Lab",
            tint = if (expanded) MaterialTheme.colorScheme.primary else QuranTheme.ink.furniture,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Opens reciter settings. The press wash hugs the name and, when [disclosure]
 * is set, the chevron. The hit target stays 48dp. The name stays on the page
 * center; the chevron hangs to its right.
 *
 * A playback [notice] takes the name's place until dismissed or recovered,
 * using the same band so it never shifts the page.
 */
@Composable
internal fun ReciterNameButton(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disclosure: Boolean = false,
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
    /** Cover bars keep error dismissal opposite their session Close control. */
    noticeDismissAtStart: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val press = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val chevronFootprint = ReciterChevronGap + ReciterChevronSize
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset(x = if (disclosure) chevronFootprint / 2 else 0.dp)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (notice == null) Modifier.quietClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactions,
                onClick = onClick,
            ) else Modifier),
    ) {
        AnimatedContent(
            targetState = notice,
            // Centred, so the name never slides while the two cross.
            contentAlignment = Alignment.Center,
            transitionSpec = { fadeIn() togetherWith fadeOut() using null },
            label = "reciterNotice",
        ) { shown ->
            if (shown != null) {
                PlaybackErrorNotice(
                    message = shown,
                    onDismiss = onDismissNotice,
                    enabled = enabled,
                    dismissAtStart = noticeDismissAtStart,
                    // Undo the chevron offset so the notice sits on the page centre.
                    modifier = Modifier
                        .offset(x = if (disclosure) -chevronFootprint / 2 else 0.dp),
                )
                return@AnimatedContent
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .drawBehind {
                        if (!pressed) return@drawBehind
                        drawRoundRect(
                            color = press,
                            cornerRadius = CornerRadius(size.minDimension / 2f),
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = QuranTheme.ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (disclosure) {
                    DisclosureChevron(
                        expanded = false,
                        modifier = Modifier.padding(start = ReciterChevronGap).size(ReciterChevronSize),
                    )
                }
            }
        }
    }
}

/** An error in the reciter band; only its close control is actionable. */
@Composable
internal fun PlaybackErrorNotice(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dismissAtStart: Boolean = false,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
            color = QuranTheme.ink.muted,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 11.sp,
                maxFontSize = 13.sp,
                stepSize = 0.5.sp,
            ),
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(if (dismissAtStart) Alignment.CenterStart else Alignment.CenterEnd)
                .size(48.dp).quietClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onDismiss,
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss playback error",
                tint = QuranTheme.ink.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
