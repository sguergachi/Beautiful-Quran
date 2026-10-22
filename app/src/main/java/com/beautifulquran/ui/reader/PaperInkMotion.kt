package com.beautifulquran.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import com.beautifulquran.ui.theme.LocalQuranAccents
import com.beautifulquran.ui.theme.paperink.PaperInk

/**
 * Paper-theme ink, as the reader's surfaces see it: the theme's colours and
 * the Ink Lab dials, captured once per composition. Null when the paper ink
 * does not apply — another theme, Android 12 or older, or switched off in the
 * Ink Lab — and every surface keeps its paper-cover wash.
 */
internal class PaperInkStyle(
    /** The page's recited ink: a dried word lands exactly on it. */
    val dryInk: Color,
    val repeatInk: Color,
) {
    /** Fresh carbon ink, darker than dry (the study's #060708). */
    val wetInk: Color = Color(0xFF060708)
}

/** Paper is the theme with no glimmer accent (see docs/GLIMMER.md "When it appears"). */
@Composable
internal fun rememberPaperInkStyle(dryInk: Color): PaperInkStyle? {
    val accents = LocalQuranAccents.current
    val enabled = accents.glintInk == null && PaperInk.supported && InkEngine.tuning.paperInk
    return remember(enabled, dryInk, accents.repeatInk) {
        if (enabled) PaperInkStyle(dryInk, accents.repeatInk) else null
    }
}

/**
 * Whether this word is drawn by the paper ink this frame: it is being recited,
 * still washing or drying, held or releasing orange, or the next word fading
 * in. Everything else is static ink the surface already draws.
 */
internal val InkMotion.paperInkLive: Boolean
    get() = isActive ||
        (!repeat && sweepProgress > 0f && sweepProgress < 1f) ||
        paperWetness > 0f ||
        repeatAlpha > 0f ||
        paperAhead > -1f

/** This frame of the word for [PaperInk.drawWord]. */
internal fun InkMotion.paperInkFrame(style: PaperInkStyle): PaperInk.Frame {
    val t = InkEngine.tuning
    val repeating = repeat || repeatAlpha > 0f
    val progress = when {
        repeating -> repeatProgress
        paperAhead > -1f -> paperAhead
        else -> sweepProgress
    }
    return PaperInk.Frame(
        progress = progress,
        wet = paperWetness,
        repeatPass = if (repeating) 1f else 0f,
        repeatTint = if (repeating) repeatAlpha else 0f,
        retained = if (repeating) 1f else 0f,
        dryInk = style.dryInk,
        wetInk = style.wetInk,
        repeatColor = style.repeatInk,
        unreadAlpha = InkEngine.State.Upcoming.inkAlpha(),
        pooling = t.paperInkPooling,
        reflection = t.paperInkReflection,
        softness = t.paperInkSoftness,
        leadFade = t.paperInkLeadFade,
        flowing = t.paperInkFlowing,
    )
}

/**
 * The paper ink's adapter for a word that is its own node (gloss tiles, the
 * mushaf's QCF words): the node's text is the word, so it is the source
 * as-is, and nothing needs erasing — the node draws the ink instead of its
 * content. Overlays for the same word ([hiddenWhilePaperInk]) stand aside
 * while it does. A word that began washing in its fallback finishes there.
 */
internal class PaperInkNode {
    /** Set from the node's `onTextLayout`. */
    var layout: TextLayoutResult? = null

    /** True during a frame the paper ink draws this word (read by its overlays). */
    var drawing: Boolean = false
        private set
    private var latched = false

    fun Modifier.paperInkBase(motion: InkMotion, style: PaperInkStyle?, rtl: Boolean): Modifier =
        paperInkBase({ motion }, style, rtl)

    /** [motion] is read in the draw phase (the mushaf's per-frame ink pack). */
    fun Modifier.paperInkBase(
        motion: () -> InkMotion?,
        style: PaperInkStyle?,
        rtl: Boolean,
        /** Letters to pace across when the text is glyph codes (QCF). */
        letterCount: Int = 0,
    ): Modifier =
        drawWithContent {
            val motion = motion()
            if (style == null || motion == null || !motion.paperInkLive) {
                latched = false
                drawing = false
                drawContent()
                return@drawWithContent
            }
            val frame = motion.paperInkFrame(style)
            val source = layout?.let { PaperInk.Source(it, 0f, 0f, rtl, letterCount) }
            val word = if (latched || source == null) null else PaperInk.word(source)
            if (word == null || source == null) {
                if (frame.progress > PAPER_INK_NODE_LATCH || frame.wet > 0f) latched = true
                drawing = false
                drawContent()
                return@drawWithContent
            }
            drawing = true
            with(PaperInk) { drawWord(word, source, frame, erase = false) }
        }

    /** An overlay of this word (orange repeat, wasl, glint) that the ink replaces. */
    fun Modifier.hiddenWhilePaperInk(): Modifier = drawWithContent {
        if (!drawing) drawContent()
    }
}

@Composable
internal fun rememberPaperInkNode(): PaperInkNode = remember { PaperInkNode() }

/** Past this much of its wash, a word that began in the fallback finishes there. */
private const val PAPER_INK_NODE_LATCH = 0.12f
