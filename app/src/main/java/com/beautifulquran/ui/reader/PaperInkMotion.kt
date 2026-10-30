package com.beautifulquran.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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

/** A cosmetic departure clock; it never delays or changes the audio wash. */
internal class PaperInkDeparture(
    val history: PaperInkEvaporation,
    val remaining: State<Float>,
) {
    val active: Boolean get() = history.isEvaporating(remaining.value)
}

@Composable
internal fun rememberPaperInkDeparture(
    enabled: Boolean,
    state: InkEngine.State,
    /** The word's verse was just recited: its ink leaves at the verse's slow pace. */
    leavingVerse: Boolean = false,
): PaperInkDeparture? {
    if (!enabled) return null
    val upcoming = state == InkEngine.State.Upcoming
    val read = state == InkEngine.State.Active || state == InkEngine.State.Recited
    val history = remember { PaperInkEvaporation(upcoming, read) }
    SideEffect { history.transition(upcoming, read) }
    val remaining = animateFloatAsState(
        targetValue = if (upcoming) 0f else 1f,
        animationSpec = if (upcoming) {
            tween(
                if (leavingVerse) InkEngine.VERSE_DEPARTURE_MS else InkEngine.INK_DEPARTURE_MS,
                easing = FastOutSlowInEasing,
            )
        } else {
            snap()
        },
        label = "paperInkEvaporation",
    )
    return remember(history, remaining) { PaperInkDeparture(history, remaining) }
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
    get() {
        val deposit = paperInkDeposit()
        return paperDeparture?.active == true || isActive ||
            (!repeat && deposit.progress > 0f && deposit.progress < 1f) ||
            deposit.wet > 0f || deposit.repeatTint > 0f || paperAhead > -1f
    }

/** Upcoming words cannot retain an old wet or repeat clock after a seek. */
private fun InkMotion.paperInkDeposit(): PaperInkDeposit {
    val upcoming = ink.state == InkEngine.State.Upcoming
    val repeating = !upcoming && (repeat || repeatAlpha > 0f)
    val current = PaperInkDeposit(
        progress = when {
            repeating -> paperRepeatProgress
            upcoming -> waslPrefix?.let { maxOf(paperAhead, it.letterProgress()) } ?: paperAhead
            else -> paperSweepProgress
        },
        wet = if (upcoming) paperPrefixWetness else paperWetness,
        repeatPass = if (repeating) 1f else 0f,
        repeatTint = if (repeating) repeatAlpha else 0f,
        retained = if (repeating) 1f else 0f,
        underProgress = paperSweepProgress,
        underWet = if (repeating) paperUnderWetness else 0f,
    )
    return paperDeparture?.let { it.history.sample(current, it.remaining.value) } ?: current
}

/** Cover-wash fallback for a departing word whose liquid field is unavailable. */
internal val InkMotion.paperDepartureProgress: Float
    get() = paperInkDeposit().let { if (it.retained > 0f) 1f else it.progress }

internal val InkMotion.paperDepartureAlpha: Float
    get() = InkEngine.State.Upcoming.inkAlpha().let { floor ->
        floor + (1f - floor) * (paperDeparture?.remaining?.value ?: 1f)
    }

/** This frame of the word for [PaperInk.drawWord]. */
internal fun InkMotion.paperInkFrame(style: PaperInkStyle): PaperInk.Frame {
    val t = InkEngine.tuning
    val deposit = paperInkDeposit()
    return PaperInk.Frame(
        progress = deposit.progress,
        wet = deposit.wet,
        repeatPass = deposit.repeatPass,
        repeatTint = deposit.repeatTint,
        retained = deposit.retained,
        underProgress = deposit.underProgress,
        underWet = deposit.underWet,
        dryInk = style.dryInk,
        wetInk = style.wetInk,
        repeatColor = style.repeatInk,
        unreadAlpha = InkEngine.State.Upcoming.inkAlpha(),
        pooling = t.paperInkPooling,
        reflection = t.paperInkReflection,
        softness = 0f,
        leadFade = t.paperInkLeadFade,
        flowing = t.paperInkFlowing,
        fibreFringe = t.paperInkFibreFringe,
        evaporation = paperDeparture?.takeIf { it.active }?.let { 1f - it.remaining.value } ?: 0f,
    )
}

/**
 * The paper ink's adapter for a word that is its own node (gloss tiles, the
 * mushaf's QCF words): the node's text is the word, so it is the source
 * as-is, and nothing needs erasing — the node draws the ink instead of its
 * content. Overlays for the same word ([hiddenWhilePaperInk]) stand aside
 * while it does. A word that began washing in its fallback finishes there.
 */
internal class PaperInkNode(
    /**
     * Which patch of the shared paper this word bleeds into. Pass something
     * stable for the word (its ayah and position), so it bleeds the same way
     * each time its node is recreated by scrolling.
     */
    private val placement: Int = PaperInk.nextPlacement(),
) {
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
            val source = layout?.let { PaperInk.Source(it, 0f, 0f, rtl, letterCount, placement) }
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

    /**
     * Start this word's field before its turn (see [PaperInk.warm]). A word
     * node otherwise first asks during its own fade-in lead, and a field takes
     * longer than that to build on a slow device, so the word would wash in
     * the fallback. False until laid out or while the frame's ration is spent.
     */
    fun warm(rtl: Boolean, letterCount: Int = 0): Boolean =
        layout?.let { PaperInk.warm(PaperInk.Source(it, 0f, 0f, rtl, letterCount, placement)) } ?: false

    /** An overlay of this word (orange repeat, wasl, glint) that the ink replaces. */
    fun Modifier.hiddenWhilePaperInk(): Modifier = drawWithContent {
        if (!drawing) drawContent()
    }
}

@Composable
internal fun rememberPaperInkNode(placement: Int? = null): PaperInkNode =
    remember(placement) { placement?.let(::PaperInkNode) ?: PaperInkNode() }

/** Past this much of its wash, a word that began in the fallback finishes there. */
private const val PAPER_INK_NODE_LATCH = 0.12f
