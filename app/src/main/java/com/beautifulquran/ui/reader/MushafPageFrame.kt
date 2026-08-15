package com.beautifulquran.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.beautifulquran.ui.entrance.mushafPageFrameGeometry
import com.beautifulquran.ui.theme.GeneratedCornerSeals
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.MushafCoverFrame
import com.beautifulquran.ui.theme.ornament.generateCoverOrnament
import com.beautifulquran.ui.theme.ornament.pageOrnamentSeed

/** Extra paper inside the inner gilt, on top of [CoverFrameGeometry.innerInsetPx]. */
internal val MushafFrameBreathing = 8.dp

/**
 * Quiet open-page frame: a doubled gilt hairline and four small generated
 * seals. No frieze band — that belongs on the closed cover.
 */
@Composable
internal fun MushafPageFrame(
    page: Int,
    sheen: State<Float>,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current.density
    val geometry = remember(density) { mushafPageFrameGeometry(density) }
    val ornament = remember(page) { generateCoverOrnament(pageOrnamentSeed(page)) }
    val accents = LocalQuranAccents.current
    val goldBright = accents.goldBright.copy(alpha = 0.48f)
    val goldDeep = accents.goldDeep.copy(alpha = 0.36f)
    val embossDark = accents.embossDark.copy(alpha = 0.18f)
    val embossLight = accents.embossLight.copy(alpha = 0.10f)
    Box(modifier.fillMaxSize()) {
        MushafCoverFrame(
            brightGold = goldBright,
            deepGold = goldDeep,
            embossDark = embossDark,
            embossLight = embossLight,
            sheen = sheen,
            geometry = geometry,
            modifier = Modifier.fillMaxSize(),
        )
        GeneratedCornerSeals(
            spec = ornament.cornerSeal,
            geometry = geometry,
            brightGold = goldBright,
            deepGold = goldDeep,
            embossDark = embossDark,
            embossLight = embossLight,
            sheen = sheen,
        )
        val innerPad = with(LocalDensity.current) { geometry.innerInsetPx.toDp() + MushafFrameBreathing }
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPad),
            content = content,
        )
    }
}
