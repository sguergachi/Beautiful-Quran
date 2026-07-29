package com.beautifulquran.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.R
import com.beautifulquran.domain.BASMALAH_UTHMANI as DomainBasmalahUthmani
import com.beautifulquran.domain.SURAH_WITHOUT_BASMALAH as DomainSurahWithoutBasmalah
import com.beautifulquran.domain.surahOpensWithBasmalahPreface as domainSurahOpensWithBasmalahPreface
import com.beautifulquran.ui.theme.letterFadeIn
import com.beautifulquran.ui.theme.quietClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Re-export for reader UI and existing tests. */
const val BASMALAH_UTHMANI = DomainBasmalahUthmani

/** Re-export for reader UI and existing tests. */
const val SURAH_WITHOUT_BASMALAH = DomainSurahWithoutBasmalah

/** Re-export for reader UI and existing tests. */
fun surahOpensWithBasmalahPreface(surahId: Int): Boolean =
    domainSurahOpensWithBasmalahPreface(surahId)

/**
 * Traditional Naskh manuscript calligraphy of the basmalah — a VectorDrawable
 * rendered through [InkEngine]'s calligraphy path. Used as the quiet opening
 * under every surah header except Al-Fatihah and At-Tawbah.
 *
 * While the lead-in clip plays, an RTL [letterFadeIn] wash advances across the
 * SVG on the clip's playback clock ([washProgress] 0..1), settling to full ink
 * before the audio ends. While another ayah is recited the glyph recesses to
 * Upcoming ink; at rest it is Plain. Tap ([onClick]) restarts playback from
 * the basmalah clip, then ayah 1.
 *
 * Artwork adapted from Wikimedia Commons File:Basmala.svg (Baba66, CC BY-SA 3.0).
 */
@Composable
fun BasmalahCalligraphy(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    dimmed: Boolean = false,
    /** Lead-in wash 0..1 from [ReaderViewModel.basmalahWashProgress]. */
    washProgress: StateFlow<Float?>? = null,
    onClick: (() -> Unit)? = null,
) {
    val inkState = InkEngine.prefaceState(isActive = active, dimmed = dimmed)
    val lyricInk by animateFloatAsState(
        targetValue = inkState.inkAlpha(),
        animationSpec = if (inkState == InkEngine.State.Active) {
            snap()
        } else {
            tween(InkEngine.tuning.inkFadeMs, easing = FastOutSlowInEasing)
        },
        label = "basmalahLyricInk",
    )
    val idleWash = remember { MutableStateFlow<Float?>(null) }
    val washState = (washProgress ?: idleWash).collectAsStateWithLifecycle()

    val ink = MaterialTheme.colorScheme.onSurface
    Image(
        painter = painterResource(R.drawable.basmalah_naskh),
        contentDescription = BASMALAH_UTHMANI,
        colorFilter = ColorFilter.tint(ink),
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .then(if (onClick != null) Modifier.quietClickable(onClick = onClick) else Modifier)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(
                if (active) {
                    Modifier.letterFadeIn(
                        progress = { washState.value?.coerceIn(0f, 1f) ?: 0f },
                        rtl = true,
                        restingAlpha = InkEngine.State.Upcoming.inkAlpha(),
                        feather = InkEngine.prefaceFeather(),
                    )
                } else {
                    Modifier.graphicsLayer { alpha = lyricInk }
                },
            ),
    )
}
