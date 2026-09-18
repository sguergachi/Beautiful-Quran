package com.beautifulquran.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.R
import com.beautifulquran.domain.BASMALAH_UTHMANI as DomainBasmalahUthmani
import com.beautifulquran.domain.SURAH_WITHOUT_BASMALAH as DomainSurahWithoutBasmalah
import com.beautifulquran.domain.surahOpensWithBasmalahPreface as domainSurahOpensWithBasmalahPreface
import com.beautifulquran.ui.theme.ArabicWordStyle
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
 * Width of the basmalah artwork, in ems of the verse text beside it, so it is
 * written with the same pen as the recited verses at every text scale.
 *
 * Measured on the nuqta — the calligrapher's unit, the square the pen leaves:
 * - `basmalah_naskh` (viewport 608 wide): the dots of ب and ي are each
 *   6.4 × 7.0 units, √area 5.46.
 * - `hafs_uthmanic.ttf`: the dots of ب / ن / ف have √area 0.082 em.
 *
 * 608 × 0.082 / 5.46 = 9.13 em. Not the alif: this hand's alifs are short for
 * its pen, so matching alif height (11.82 em) left it a 1.4–1.7× heavier stroke
 * than the verses on device and it read as larger text. Re-measure if either
 * the artwork or the verse face changes (web mirrors this in
 * `.basmalah-calligraphy`).
 */
const val BASMALAH_WIDTH_EM = 9.13f

/** Side margin inside the calligraphy's own tap target. */
private val BasmalahInset = 12.dp

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
    /** The reader's text scale; sizes the artwork to the verses ([BASMALAH_WIDTH_EM]). */
    fontScale: Float = 1f,
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
    val artWidth = with(LocalDensity.current) {
        (ArabicWordStyle.fontSize * fontScale * BASMALAH_WIDTH_EM).toDp()
    }
    Image(
        painter = painterResource(R.drawable.basmalah_naskh),
        contentDescription = BASMALAH_UTHMANI,
        colorFilter = ColorFilter.tint(ink),
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            // Never wider than the column: at large scales it fills the line.
            .widthIn(max = artWidth + BasmalahInset * 2)
            .then(if (onClick != null) Modifier.quietClickable(onClick = onClick) else Modifier)
            .fillMaxWidth()
            .padding(horizontal = BasmalahInset)
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
