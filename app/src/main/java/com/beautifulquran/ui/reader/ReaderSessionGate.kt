package com.beautifulquran.ui.reader

/**
 * One versioned navigation request for the reader: which chapter is intended,
 * optional autoplay ayah, and a monotonic [generation] so a slower older load
 * cannot install content/timings/autoplay after a newer intent has taken over.
 *
 * Pure of Android so JVM tests cover the race contract without a repository.
 */
internal class ReaderSessionGate {
    /** Bumps on every [begin]; 0 means nothing has been requested yet. */
    var generation: Long = 0L
        private set

    /** Chapter owned by the current generation (0 when idle). */
    var surahId: Int = 0
        private set

    /**
     * Autoplay target for the in-flight or just-completed load of [surahId].
     * Updated in place when a second "play this ayah" arrives while the same
     * chapter is still loading — without bumping the generation.
     */
    var pendingPlayAyah: Int? = null
        private set

    /**
     * Word within [pendingPlayAyah] the autoplay should open on, when the
     * request came from a tap on a specific word (a mushaf leaf whose surah
     * was not the loaded one). Resolved to a seek once that chapter's timings
     * are in hand; null means start at the top of the verse.
     */
    var pendingPlayWord: Int? = null
        private set

    /**
     * Starts a new navigation request. Callers must cancel any prior load job
     * so only this generation's materialize may [isCurrent]-pass to install.
     */
    fun begin(surahId: Int, pendingPlayAyah: Int? = null, pendingPlayWord: Int? = null): Long {
        generation += 1L
        this.surahId = surahId
        this.pendingPlayAyah = pendingPlayAyah
        this.pendingPlayWord = pendingPlayWord
        return generation
    }

    /** Same chapter still loading: attach or replace autoplay without a new gen. */
    fun setPendingPlay(ayah: Int, word: Int? = null) {
        pendingPlayAyah = ayah
        pendingPlayWord = word
    }

    /**
     * Returns and clears pending autoplay only if [gen] is still live —
     * so a stale load never plays under a newer request's intent.
     */
    fun takePendingPlay(gen: Long): Int? {
        if (gen != generation) return null
        val ayah = pendingPlayAyah
        pendingPlayAyah = null
        return ayah
    }

    /** Word for the autoplay just taken by [takePendingPlay]; clears it. */
    fun takePendingPlayWord(): Int? {
        val word = pendingPlayWord
        pendingPlayWord = null
        return word
    }

    fun isCurrent(gen: Long): Boolean = gen == generation

    fun isCurrent(gen: Long, surahId: Int): Boolean =
        gen == generation && this.surahId == surahId
}
