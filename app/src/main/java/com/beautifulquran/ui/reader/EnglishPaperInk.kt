package com.beautifulquran.ui.reader

import androidx.compose.ui.graphics.Color
import com.beautifulquran.ui.theme.ShapedWordBloom

/** The shader doubles this dial value: English leads by exactly one letter slot. */
internal const val ENGLISH_PAPER_INK_LEAD_FADE = 0.5f

/** Printed word bounds, cached with the paragraph rather than scanned on draw frames. */
internal fun englishPaperInkWordRanges(
    text: CharSequence,
    owners: List<IntRange>,
): Map<IntRange, List<IntRange>> = owners.filterNot { it.isEmpty() }.associateWith { owner ->
    ENGLISH_INK_WORD.findAll(text.subSequence(owner.first, owner.last + 1)).map { match ->
        (owner.first + match.range.first)..(owner.first + match.range.last)
    }.toList()
}

/**
 * A gloss's timing owner can cover several printed English words. Give each
 * its own ink field so the one-letter preview cannot cross a space. Project
 * both coats and the fallback through the same owner clock; repeats and rewind
 * evaporation therefore keep their deposited prefix.
 */
internal fun englishPaperInkBlooms(
    blooms: List<ShapedWordBloom>,
    wordsByOwner: Map<IntRange, List<IntRange>>,
): List<ShapedWordBloom> = buildList {
    for (bloom in blooms) {
        val words = wordsByOwner[bloom.range]
        when {
            words == null -> add(bloom)
            bloom is ShapedWordBloom.PaperInkWarm ->
                words.forEach { add(ShapedWordBloom.PaperInkWarm(it)) }
            bloom is ShapedWordBloom.PaperInk -> words.forEach { word ->
                fun progress(value: Float): Float = englishPaperInkWordProgress(
                    bloom.range.first + value * (bloom.range.last - bloom.range.first + 1),
                    word.first, word.last + 1,
                )
                fun bounded(layer: ShapedWordBloom): ShapedWordBloom = when (layer) {
                    is ShapedWordBloom.UpcomingDim -> layer.copy(range = word)
                    is ShapedWordBloom.InkReveal -> layer.copy(
                        range = word, progress = progress(layer.progress).coerceAtLeast(0f),
                    )
                    is ShapedWordBloom.ColorReveal -> layer.copy(
                        range = word, progress = progress(layer.progress).coerceAtLeast(0f),
                    )
                    else -> error("English paper ink fallback must be a cover or reveal")
                }
                val current = progress(bloom.frame.progress)
                val under = progress(bloom.frame.underProgress)
                add(ShapedWordBloom.PaperInk(
                    range = word,
                    frame = bloom.frame.copy(
                        progress = current,
                        wet = if (current < 0f) 0f else bloom.frame.wet,
                        underProgress = under,
                        underWet = if (under < 0f) 0f else bloom.frame.underWet,
                        leadFade = ENGLISH_PAPER_INK_LEAD_FADE,
                        fadeLeadOnEntry = true,
                    ),
                    fallback = bloom.fallback.map(::bounded),
                    after = bloom.after.map(::bounded),
                ))
            }
            else -> add(bloom)
        }
    }
}

private val ENGLISH_INK_WORD = Regex("\\S+")

/** The fallback and liquid field share one printed word's progress, never a phrase-wide mask. */
internal fun englishWordWashCover(
    range: IntRange,
    progress: Float,
    paper: Color,
    restingAlpha: Float,
    waitingCover: Float,
    recitedCover: Float,
): ShapedWordBloom? = when {
    progress <= 0f -> ShapedWordBloom.UpcomingDim(range, paper, waitingCover)
    progress < 1f -> ShapedWordBloom.InkReveal(range, progress, paper, restingAlpha)
    recitedCover > 0f -> ShapedWordBloom.UpcomingDim(range, paper, recitedCover)
    else -> null
}

/** A shared Arabic owner's wetness cannot activate English words ahead of the wash. */
internal fun englishPaperInkWordVisible(progress: Float, wet: Float, departing: Boolean): Boolean =
    progress >= 0f && (progress < 1f || wet > 0f || departing)

/** Only the prefix present on entry rises through seek recess; newly washed ink stays laid. */
internal class EnglishWashEntry {
    private var activation: Long? = null
    private var reciterId: Int? = null
    private var entryEdge = 0f

    fun onFrame(activation: Long, reciterId: Int?, washEdge: Float) {
        if (activation != this.activation || reciterId != this.reciterId) {
            this.activation = activation
            this.reciterId = reciterId
            entryEdge = washEdge
        }
    }

    fun recitedCover(range: IntRange, cover: Float): Float =
        if (range.last + 1 <= entryEdge) cover else 0f
}
