package com.beautifulquran.ui.reader

/** The last deposited film, independent of fonts, surfaces and playback clocks. */
internal data class PaperInkDeposit(
    val progress: Float,
    val wet: Float,
    val repeatPass: Float,
    val repeatTint: Float,
    val retained: Float,
    val underProgress: Float = 1f,
    val underWet: Float = 0f,
)

/**
 * Remembers visible ink when a word becomes unread again. The transport can
 * reset immediately while drawing dissolves the old deposit in place. A word
 * mounted unread has no departure; re-entering it cancels any old departure.
 */
internal class PaperInkEvaporation(
    private var upcoming: Boolean,
    private var read: Boolean = !upcoming,
) {
    private var last: PaperInkDeposit? = null
    private var departing: PaperInkDeposit? = null

    fun transition(upcoming: Boolean, read: Boolean = !upcoming) {
        if (upcoming && !this.upcoming) departing = last.takeIf { this.read }
        if (!upcoming) departing = null
        this.upcoming = upcoming
        this.read = read
    }

    fun isEvaporating(remaining: Float): Boolean = upcoming && departing != null && remaining > 0f

    /** Record even static ink, so a long-settled word cannot resurrect old gloss. */
    fun sample(current: PaperInkDeposit, remaining: Float): PaperInkDeposit {
        if (isEvaporating(remaining)) return departing!!
        last = current
        return current
    }
}
