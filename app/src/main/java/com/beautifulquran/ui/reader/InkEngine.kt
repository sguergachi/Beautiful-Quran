package com.beautifulquran.ui.reader

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beautifulquran.domain.TajweedPacing

/**
 * The reader's single source of truth for *how a word's ink should behave*.
 *
 * Sits between [com.beautifulquran.domain.HighlightEngine] (pure recitation
 * timing: which word is lit, repeat/high-water facts) and the mode-specific
 * renderers in ReaderComponents.kt (how Arabic gloss, English, and Arabic-only
 * Hafs actually draw text). Every visual highlight decision routes through
 * here so the effect can be tuned in one place; the renderers consume
 * [InkEngine.Word] and never re-derive highlight semantics themselves.
 * See docs/INK_ENGINE.md.
 *
 * The word-policy core is deliberately pure — no Compose types in any
 * decision function — so it unit-tests on the JVM the same way
 * [com.beautifulquran.domain.HighlightEngine] and
 * [com.beautifulquran.ui.reader.focus.FocusEngine] do. The one Compose
 * dependency is [tuning] being snapshot-backed, so the developer-mode Ink Lab
 * can retune the feel live and every open animation picks the change up.
 *
 * What stays out (see the non-goals in docs/INK_ENGINE.md): scroll focus
 * (FocusEngine), timing lookup (HighlightEngine), Arabic shaping and draw
 * primitives (ui/theme/Fade.kt), and layout (the reader composables).
 */
object InkEngine {

    /** The four ink states every reading mode shares. */
    enum class State {
        /** Idle page, no recitation touching this ayah: full ink. */
        Plain,

        /** Not yet recited (or a recessed ayah while another plays): faint. */
        Upcoming,

        /** The word the reciter is on: letters sweep in to full ink. */
        Active,

        /** Already recited this pass: holds full ink. */
        Recited,
        ;

        /**
         * Apple-Music-lyrics treatment: the letters themselves carry the
         * highlight. Upcoming words rest faint on the page; the word being
         * recited breathes in to full ink; words already recited hold that
         * full strength.
         */
        fun inkAlpha(): Float = if (this == Upcoming) tuning.upcomingAlpha else 1f
    }

    /** Everything a renderer needs to draw one word's ink. */
    data class Word(
        val state: State,
        /** Whether the word is wearing the orange repeat wash. */
        val repeat: Boolean,
    )

    /**
     * Every knob that shapes the highlight *feel*, gathered so polish is a
     * one-file affair. Defaults are the shipped values; the developer-mode
     * Ink Lab mutates [tuning] to audition changes live. Lab edits persist
     * across process restarts via [InkLabStore] until Reset (see
     * docs/INK_ENGINE.md); **Copy values** still produces a paste-ready
     * constructor for promoting a feel into these shipped defaults.
     */
    data class Tuning(
        /** Resting ink of an upcoming / recessed word. */
        val upcomingAlpha: Float = 0.22f,
        /** State tween between resting inks (Active snaps — see
         *  ReaderComponents.animatedInkAlpha for why). */
        val inkFadeMs: Int = 400,
        /** Fade of the ﴿N﴾ ayah mark up to full when its verse gains focus. */
        val ayahMarkFadeMs: Int = 400,
        /** Softening when a verse recesses or returns (Arabic-only paper cover).
         *  Matched to [inkFadeMs] so every reading mode moves at the same pace. */
        val recessMs: Int = 400,
        /** Letter-sweep duration clamps around the reciter's actual dwell. */
        val minSweepMs: Int = 140,
        val maxSweepMs: Int = 8_000,
        /** Repeat wash sweep when the active word carries no timing. */
        val repeatSweepMs: Int = 450,
        /** Dissolve of the orange wash once the repeat chain releases. */
        val repeatFadeOutMs: Int = 900,
        /** Peak strength of the orange repeat overlay (and search-hit flash).
         *  Multiplies [com.beautifulquran.ui.theme.QuranAccents.repeatInk]
         *  so the hue stays theme-owned while visibility is live-tunable. */
        val repeatInkAlpha: Float = 1f,
        /** Dissolve of the white-gold first-gloss glint (see [glinting]) back
         *  to plain recited ink once the voice moves on to the next word. */
        val glintFadeMs: Int = 1_000,
        /** Tint strength plus the subtle glyph-outline halo around Nightfall's glint. */
        val glintTintAlpha: Float = 0.62f,
        val glintGlowAlpha: Float = 0.49f,
        val glintGlowRadius: Float = 10f,
        /** Width of the ink feather relative to the word (see
         *  ui/theme/Fade.kt: the wash reads as a whole-word breath). */
        val washFeather: Float = 1.6f,
        /** Control points of the sweep easing: a steady glide, softened only
         *  at the very ends so it never snaps into or out of motion. */
        val sweepEaseX1: Float = 0.3f,
        val sweepEaseY1: Float = 0.24f,
        val sweepEaseX2: Float = 0.7f,
        val sweepEaseY2: Float = 0.78f,
        /** Letter-level tajweed pacing of the active word's sweep — the ink
         *  dwells on held letters (madd, ghunnah) instead of sweeping at a
         *  constant rate. Off by default; auditioned via the Ink Lab.
         *  See docs/TAJWEED_PACING.md. */
        val tajweedPacing: Boolean = false,
        /** Feather of a paced word. Defaults to [washFeather]'s whole-word
         *  breath: the hold now reads through the wash *stopping*, so the
         *  edge no longer has to be sharpened (which is what cost the reveal
         *  its softness the first time round). */
        val pacedFeather: Float = 1.6f,
        /** Which moments earn a hold — see [TajweedPacing.Hold]. */
        val holdMadd: Boolean = true,
        val holdGhunnah: Boolean = false,
        val holdWaqf: Boolean = true,
        /** Cross-word idghām (nūn/tanwīn + يرملون): hold the next word's
         *  opening letter. See [TajweedPacing.Hold.connect]. */
        val holdConnect: Boolean = true,
        /** Ceiling on ordinary-letter speed while a hold is bought, as a
         *  multiple of the plain sweep rate. Word timings are contiguous, so
         *  hold length and this cap are the same dial; 1 means ordinary
         *  letters are never hurried and only [holdWaqf] can hold. */
        val cruiseCap: Float = 1.25f,
        /** Share of a verse-closing word spent sustaining its final letter
         *  when the word is long enough (see [waqfLengthScale]). */
        val waqfShare: Float = 0.55f,
        /** How strongly shorter closers reduce effective [waqfShare] — see
         *  [TajweedPacing.Hold.waqfLengthScale]. */
        val waqfLengthScale: Float = 0.7f,
        /** How far the wash still creeps while holding, so it breathes. */
        val holdCreep: Float = 0.08f,
    )

    /**
     * Optional on-device store attached from [com.beautifulquran.QuranApp].
     * When set, every lab edit auto-saves; cold start reloads the last
     * snapshot. Unit tests leave this null so mutations stay in-memory.
     */
    private var labStore: InkLabStore? = null

    /** Suppress auto-save while restoring shipped defaults or a snapshot. */
    private var suppressLabPersist = false

    /**
     * Live tuning values. Snapshot-backed so the Ink Lab's edits reach every
     * running animation. With a store attached, edits survive process death;
     * without one (tests, or before [attachLabStore]), values are in-memory.
     */
    private var tuningState by mutableStateOf(Tuning())
    var tuning: Tuning
        get() = tuningState
        set(value) {
            tuningState = value
            persistLab()
        }

    /**
     * Highlight-tab *sync* knobs — not ink feel. Kept outside [Tuning] so
     * visual paste/copy stays about the wash, while these adjust when the
     * karaoke clock and ayah handoff fire. Persisted with Tuning via
     * [InkLabStore].
     */
    /**
     * How early word ink runs ahead of [com.beautifulquran.domain.HighlightEngine]
     * segment times (ms). Added to the playhead before the engine query so
     * the next word's wash can start before the timed startMs. Default 0.
     */
    private var highlightLeadState by mutableStateOf(DEFAULT_HIGHLIGHT_LEAD_MS)
    var highlightLeadMs: Int
        get() = highlightLeadState
        set(value) {
            highlightLeadState = value
            persistLab()
        }

    /** How early the next ayah prepares before the last word ends (ms). */
    private var fadeLeadState by mutableStateOf(DEFAULT_FADE_LEAD_MS)
    var fadeLeadMs: Int
        get() = fadeLeadState
        set(value) {
            fadeLeadState = value
            persistLab()
        }

    /**
     * Extra output lag subtracted from the media playhead before the highlight
     * clock, or null to use the route preset from [AudioOutputLatency]
     * (speaker ≈ 0, A2DP ≈ 180, LE ≈ 80). Lab override is absolute when set.
     */
    private var outputLatencyOverrideState by mutableStateOf<Int?>(null)
    var outputLatencyOverrideMs: Int?
        get() = outputLatencyOverrideState
        set(value) {
            outputLatencyOverrideState = value
            persistLab()
        }

    /**
     * Session-only Ink Lab override: when false, playback auto-scroll and
     * word-band follow stop so the page can be panned freely while auditioning
     * ink. Not part of [Tuning] — never persisted, never copied into shipped
     * defaults, and Reset on the panel does not touch it.
     */
    var focusEngineEnabled: Boolean by mutableStateOf(true)

    /** [Tuning.sweepEaseX1]..[Tuning.sweepEaseY2] as a Compose easing. */
    val sweepEasing: CubicBezierEasing
        get() = tuning.let {
            CubicBezierEasing(it.sweepEaseX1, it.sweepEaseY1, it.sweepEaseX2, it.sweepEaseY2)
        }

    /** Shipped defaults for highlight sync (lab knobs start here). */
    const val DEFAULT_HIGHLIGHT_LEAD_MS = 0
    const val DEFAULT_FADE_LEAD_MS = 500

    /**
     * Attach [store] and restore any saved lab numbers. Call once from
     * application start. Subsequent lab edits write through automatically.
     */
    fun attachLabStore(store: InkLabStore) {
        labStore = store
        store.load()?.let { applyLabSnapshot(it, persist = false) }
    }

    /** Capture the live lab numbers (Tuning + Highlight knobs). */
    fun captureLabSnapshot(): InkLabSnapshot = InkLabSnapshot.capture()

    /**
     * Apply [snapshot] into the live engine. When [persist] is true and a
     * store is attached, the snapshot is written through.
     */
    fun applyLabSnapshot(snapshot: InkLabSnapshot, persist: Boolean = true) {
        val previous = suppressLabPersist
        suppressLabPersist = true
        try {
            tuningState = snapshot.toTuning()
            highlightLeadState = snapshot.highlightLeadMs
            fadeLeadState = snapshot.fadeLeadMs
            outputLatencyOverrideState = snapshot.outputLatencyOverrideMs
        } finally {
            suppressLabPersist = previous
        }
        if (persist) persistLab()
    }

    /**
     * Drop any on-device lab overrides and restore shipped defaults. Focus
     * freeze is left alone (session-only).
     */
    fun resetLabToShippedDefaults() {
        labStore?.clear()
        val previous = suppressLabPersist
        suppressLabPersist = true
        try {
            tuningState = Tuning()
            highlightLeadState = DEFAULT_HIGHLIGHT_LEAD_MS
            fadeLeadState = DEFAULT_FADE_LEAD_MS
            outputLatencyOverrideState = null
        } finally {
            suppressLabPersist = previous
        }
    }

    private fun persistLab() {
        if (suppressLabPersist) return
        labStore?.save(captureLabSnapshot())
    }

    /**
     * The ink state of the word at [position].
     *
     * [activeWord] is non-null **only for the ayah that owns the reciting
     * word** (the caller passes it filtered by `it.ayah == this ayah`), and it
     * tracks the true audio position. [isActiveAyah] is the *fade-led* focus
     * bit, which flips to the next ayah [fadeLeadMs] before the audio
     * boundary so the next verse can begin fading in early. The two disagree
     * during that lead — most visibly across a waqf, where the closing word is
     * still being held while focus has already moved on.
     *
     * So the reciting word's own state follows [activeWord], not the fade-led
     * focus: while this ayah owns the active word, its words light and hold
     * (Active / Recited by high-water) regardless of [isActiveAyah] — otherwise
     * a sustained final letter drops out of its paced hold 500 ms early. Only
     * once [activeWord] is null (this ayah is not the one reciting) does the
     * focus bit decide between the faint Upcoming wait and resting Plain ink.
     */
    fun wordState(
        position: Int,
        activeWord: ActiveWord?,
        isActiveAyah: Boolean,
        dimmed: Boolean,
    ): State = when {
        activeWord == null -> if (isActiveAyah || dimmed) State.Upcoming else State.Plain
        position == activeWord.wordPosition -> State.Active
        position < activeWord.wordPosition -> State.Recited
        position <= activeWord.highWater -> State.Recited
        else -> State.Upcoming
    }

    /**
     * Whether the word at [position] belongs to the active repeat chain: from
     * the word the reciter jumped back to ([ActiveWord.repeatStart]) through
     * the word now being re-recited. The whole section holds orange together
     * and only releases once the chain completes and the recitation moves on
     * to new, unread words.
     */
    fun inRepeatChain(position: Int, activeWord: ActiveWord?): Boolean =
        activeWord != null &&
            activeWord.isRepeat &&
            position in activeWord.repeatStart..activeWord.wordPosition

    /** [wordState] + [inRepeatChain] bundled for the renderers. [activeWord]
     *  is already filtered to this ayah, so its presence — not the fade-led
     *  [isActiveAyah] — gates the repeat wash, keeping the orange alive through
     *  the same waqf lead that [wordState] guards. */
    fun word(
        position: Int,
        activeWord: ActiveWord?,
        isActiveAyah: Boolean,
        dimmed: Boolean,
    ): Word =
        Word(
            state = wordState(position, activeWord, isActiveAyah, dimmed),
            repeat = inRepeatChain(position, activeWord),
        )

    /**
     * How long the active word's letter sweep should run: the time the
     * word stays lit (karaoke hold until the next word), corrected for
     * playback speed, floored at [Tuning.minSweepMs] so short holds (and
     * first-word timing quirks with near-zero remaining Active time) still
     * get a visible wash. The renderers finish an incomplete wash after
     * handoff rather than snapping to full ink — so scaling past the lit
     * lifetime no longer flickers Arabic-only paper cover. Long holds are
     * capped by [Tuning.maxSweepMs]. Null when nothing is lit.
     */
    fun sweepMs(activeWord: ActiveWord?, playbackSpeed: Float): Int? {
        val word = activeWord ?: return null
        val raw = (word.durationMs / playbackSpeed).toInt().coerceAtLeast(0)
        if (raw <= 0) return tuning.minSweepMs
        return raw.coerceIn(tuning.minSweepMs, tuning.maxSweepMs)
    }

    /**
     * The active word's tajweed pacing curve — how the sweep's linear clock
     * maps to wash position so the ink sustains the letter the reciter is
     * sustaining — or null for the plain sweep (toggle off, no dramatic
     * letter in the word, no dwell affordable, or nothing recognisable).
     *
     * [arabic] is the active word's Hafs Uthmani text and [isAyahFinal] marks
     * the verse-closing word, whose waqf carries the only slack that is not
     * borrowed from its neighbours. [prevArabic] / [nextArabic] are same-ayah
     * neighbours for wasl nūn rules (entry hold on this opening letter; early
     * exit when this trailing nūn is absorbed next). The voiced share comes
     * from [ActiveWord.spokenMs] vs the karaoke hold, so the ink settles
     * rather than smearing across a breath gap.
     */
    fun pacing(
        arabic: String,
        activeWord: ActiveWord,
        isAyahFinal: Boolean,
        prevArabic: String? = null,
        nextArabic: String? = null,
    ): TajweedPacing.Curve? {
        val t = tuning
        if (!t.tajweedPacing) return null
        val spokenFraction =
            if (activeWord.durationMs <= 0L) 1f
            else activeWord.spokenMs.toFloat() / activeWord.durationMs
        return TajweedPacing.curve(
            arabic = arabic,
            spokenFraction = spokenFraction,
            hold = TajweedPacing.Hold(
                madd = t.holdMadd,
                ghunnah = t.holdGhunnah,
                waqf = t.holdWaqf,
                connect = t.holdConnect,
                isAyahFinal = isAyahFinal,
                cruiseCap = t.cruiseCap,
                waqfShare = t.waqfShare,
                waqfLengthScale = t.waqfLengthScale,
                creep = t.holdCreep,
            ),
            prevArabic = prevArabic,
            nextArabic = nextArabic,
        )
    }

    /** Cross-word prefix bloom for a nūn/tanwīn connection, when enabled. */
    fun connection(prevArabic: String, arabic: String): TajweedPacing.Connection? {
        val t = tuning
        if (!t.tajweedPacing || !t.holdConnect) return null
        return TajweedPacing.connection(prevArabic, arabic)
    }

    /**
     * Feather width for a tajweed-paced wash. Paced words keep the whole-word
     * breath by default: the hold reads as the bloom *stopping*, so sharpening
     * the edge is no longer the only way to see it — and sharpening is what
     * cost the reveal its ethereal quality when pacing first shipped.
     */
    fun pacedFeather(): Float = tuning.pacedFeather

    /**
     * Whether the word should wear the fresh-ink glint: the subtle white-gold
     * sheen a genuinely new word carries while its ink is still wet, dissolving
     * back to plain recited ink over [Tuning.glintFadeMs] once the voice moves
     * on. Themes opt in via a non-null `QuranAccents.glintInk` (Nightfall and
     * Royal Green); this predicate is the *word* half of the gate.
     *
     * Active repeat words glint over their orange wash too. [startRevealed]
     * is retained for API parity with older call sites; wash always restarts
     * on Active entry, so ordinary seeks also glint as a fresh play of the word.
     */
    fun glinting(state: State, repeat: Boolean, startRevealed: Boolean): Boolean =
        state == State.Active && (repeat || !startRevealed)

    /**
     * Whether a word entering [current] from [previous] should start its
     * letter sweep already fully revealed (progress 1) instead of snapping to
     * the faint upcoming floor.
     *
     * Always false: every Active entry re-runs the ink wash — including
     * Recited → Active when the listener taps a word to play it again, seeks
     * backward, or a loop restarts. Skipping that wash made replayed words
     * look inert (full ink already, no motion). Sampling jitter is filtered
     * by [com.beautifulquran.domain.HighlightClock] so accidental bounce no
     * longer needs this suppression.
     */
    @Suppress("UNUSED_PARAMETER")
    fun startRevealed(previous: State, current: State): Boolean = false

    /**
     * Ink for the surah-header basmalah calligraphy (a VectorDrawable, not
     * shaped text): Active while the lead-in clip plays, Upcoming while
     * another ayah is recited (same recess as verse words), Plain at rest.
     */
    fun prefaceState(isActive: Boolean, dimmed: Boolean): State = when {
        isActive -> State.Active
        dimmed -> State.Upcoming
        else -> State.Plain
    }

    /**
     * How far the calligraphy ink wash has traveled (0..1) across the SVG.
     *
     * Driven by the lead-in clip's playback clock — not equal word slices —
     * so the feathered [letterFadeIn] edge reaches full ink before the audio
     * ends. [letterFadeIn] only clears the resting floor at progress ≥ 1, and
     * the wide wash feather leaves the trailing edge faint until then; settling
     * at [PREFACE_WASH_SETTLE_FRACTION] of the clip gives that edge time to
     * finish while the basmalah is still playing.
     */
    fun prefaceWashProgress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        if (positionMs <= 0L) return 0f
        val settleAt = (durationMs * PREFACE_WASH_SETTLE_FRACTION).toLong().coerceAtLeast(1L)
        if (positionMs >= settleAt) return 1f
        return (positionMs.toFloat() / settleAt.toFloat()).coerceIn(0f, 1f)
    }

    /** Fraction of the lead-in clip at which the SVG wash must be fully settled. */
    const val PREFACE_WASH_SETTLE_FRACTION = 0.88f
}
