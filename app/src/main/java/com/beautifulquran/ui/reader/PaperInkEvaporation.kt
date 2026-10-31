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
        // The word's state can read unread a moment before [transition] hears
        // of it (the state flips in composition; the transition runs after).
        // That frame's deposit is not the ink the word is leaving: recording it
        // made the departure start from unread, so the shader painted the faded
        // ink at once and no fade length mattered. While this still believes
        // the word is read, an unread deposit is that race and is not kept.
        if (!(read && !upcoming && current.progress < 0f)) last = current
        return current
    }
}
