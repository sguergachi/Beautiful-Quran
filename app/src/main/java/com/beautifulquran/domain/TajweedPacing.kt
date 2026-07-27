package com.beautifulquran.domain

import com.beautifulquran.data.model.SubwordKeyframe

/**
 * Letter-level pacing of the active word's ink sweep, derived from tajweed
 * rules (docs/TAJWEED_PACING.md).
 *
 * The word-level timing measures how long the reciter dwelled on the word;
 * tajweed says *where inside that word* the time is being spent. This object
 * turns a word's Hafs Uthmani text (which carries every needed mark: shadda,
 * maddah, dagger alef, sukūn, hamzat wasl, quiescent zero) into a monotone
 * time → wash-position [Curve].
 *
 * **The model is a gated hint, not a redistribution.** Word timings are
 * contiguous — 99.8 % of segments end exactly where the next begins — so there
 * is no slack inside a word: every count handed to a madd is taken from its
 * neighbours, which then run *faster* than the plain sweep. Spreading every
 * letter by its raw counts therefore made most of each word quicker and
 * sharper, losing the whole-word breath the reveal is built around. Instead:
 *
 * - **Gate** — a word is paced only if it holds a genuinely dramatic letter
 *   ([Hold]). Everything else returns null and takes the plain sweep, so the
 *   page reads exactly as it does today almost everywhere.
 * - **Cruise** — ordinary letters move at one constant speed, capped at
 *   [Hold.cruiseCap] times the plain rate. At a cap of 1 they are untouched.
 * - **Hold** — the freed time parks the wash on the held letter, creeping
 *   ([Hold.creep]) rather than freezing so the bloom stays alive.
 * - **Waqf** — an ayah's final word is held about 2.9× longer than a mid-ayah
 *   word (median 2983 ms vs 1040 ms). That slack is real rather than
 *   borrowed, so it is budgeted separately ([Hold.waqfShare]) and spent on the
 *   closing letter — the madd ʿāriḍ the reciter is actually sustaining.
 * - **Wasl connect** — when the previous word ends in nūn sākinah or tanwīn
 *   and this word starts with an idghām / iqlāb / ikhfāʾ letter, the nūn is
 *   absorbed and the reciter sustains the **opening letter of this word**.
 *   Pass the previous word as [prevArabic]. The donor keeps its ordinary
 *   sweep; the separate cross-word bloom represents the connected handoff
 *   without accelerating the word that feeds it.
 * - **Waqf length scale** — [Hold.waqfLengthScale] ramps how much of
 *   [Hold.waqfShare] a closer may spend by letter count, so a high hold
 *   slider does not sprint the run-up on short/medium finals (e.g. عَظِيمًا)
 *   while long closers still get the full share.
 *
 * Pure Kotlin over immutable data, like [HighlightEngine] — no Android or
 * Compose dependencies, unit-tested on the JVM. The counts are murattal
 * heuristics (see the doc for calibration plans); letter widths are uniform
 * per letter in v1, which the wide feathered wash edge absorbs.
 */
object TajweedPacing {

    /**
     * A cross-word nūn/tanwīn connection whose opening letter is spoken
     * before the timing handoff reaches the next word.
     *
     * [prefixFraction] is the opening letter's approximate share of the
     * shaped word. [at] blooms that prefix over the absorbed nūn's tail,
     * smoothstepped so the letter eases across the junction instead of
     * popping on the last frames of a short donor (مِن، مَن).
     */
    data class Connection(val prefixFraction: Float) {
        /**
         * Opening-glyph bloom progress at normalized prior-word time [t].
         *
         * [prefixStart] is where the rise begins (see [waslPrefixStart]).
         * [completion] is how much of the target bloom clock fits before
         * handoff; values below one leave a soft edge for the next word.
         * Default matches a long-word late junction ([WASL_EXIT_FRACTION]).
         */
        fun at(
            t: Float,
            prefixStart: Float = WASL_EXIT_FRACTION,
            completion: Float = 1f,
        ): Float {
            val span = (1f - prefixStart).coerceAtLeast(1e-4f)
            val u = (((t - prefixStart) / span) * completion).coerceIn(0f, 1f)
            // smoothstep: zero slope at both ends — soft carry-in, soft settle.
            return u * u * (3f - 2f * u)
        }
    }

    /**
     * Normalized prior-word time when the next opening letter begins to bloom.
     *
     * Enforces a **speed ceiling** on the wasl carry-in: the bloom window aims
     * for at least [minPrefixMs] of wall-clock (and may claim up to
     * [MAX_WASL_PREFIX_WINDOW] of a short donor) so pairs like مَن يَشْرِى do
     * not race the first glyph. Longer words stay near the absorbed-nūn tail
     * ([WASL_EXIT_FRACTION]). [minPrefixMs] is lab-tunable
     * (`InkEngine.Tuning.waslPrefixMs`); default matches [DEFAULT_WASL_PREFIX_MS].
     */
    fun waslPrefixStart(
        sweepMs: Int,
        minPrefixMs: Float = DEFAULT_WASL_PREFIX_MS,
    ): Float {
        val window = (minPrefixMs.coerceAtLeast(1f) / sweepMs.coerceAtLeast(1).toFloat())
            .coerceIn(MIN_WASL_PREFIX_WINDOW, MAX_WASL_PREFIX_WINDOW)
        return 1f - window
    }

    /**
     * Share of the target wasl bloom clock available before word handoff.
     *
     * Very short donors keep the initial pause imposed by
     * [MAX_WASL_PREFIX_WINDOW], then hand off an incomplete bloom rather than
     * compressing the whole fade into that tail. The incoming word continues
     * from the same edge. [maxCompletion] also keeps long donors from
     * pre-forming the whole opening before activation.
     */
    fun waslPrefixCompletion(
        sweepMs: Int,
        minPrefixMs: Float = DEFAULT_WASL_PREFIX_MS,
        maxCompletion: Float = DEFAULT_WASL_HANDOFF,
    ): Float {
        val start = waslPrefixStart(sweepMs, minPrefixMs)
        val availableMs = (1f - start) * sweepMs.coerceAtLeast(1)
        return (availableMs / minPrefixMs.coerceAtLeast(1f))
            .coerceIn(0f, maxCompletion.coerceIn(0f, 1f))
    }

    /**
     * Which moments deserve a hold, and what the hold may cost.
     *
     * [cruiseCap] is the honest trade: with no slack inside a word, hold
     * length and ordinary-letter speed are the same dial. 1.0 means ordinary
     * letters never speed up, which also means a mid-word madd can buy no
     * dwell at all — leaving [waqf] as the only drama.
     */
    data class Hold(
        /** 4–6 count madds (a maddah mark) trigger a hold. */
        val madd: Boolean = true,
        /** 2-count nasal hum on a mushaddad ن/م triggers a hold. */
        val ghunnah: Boolean = false,
        /** An ayah's closing word parks the wash on its final letter. */
        val waqf: Boolean = true,
        /** Cross-word nūn rules (idghām / iqlāb / ikhfāʾ): hold this word's
         *  opening letter when [prevArabic] feeds the rule. */
        val connect: Boolean = true,
        /** Whether this word closes its verse (drives [waqf]). */
        val isAyahFinal: Boolean = false,
        /** Ceiling on ordinary-letter speed, as a multiple of the plain rate. */
        val cruiseCap: Float = 1.25f,
        /** Share of an ayah-final word's dwell spent on the closing letter
         *  when the word is long enough (see [waqfLengthScale]). */
        val waqfShare: Float = 0.55f,
        /**
         * How strongly shorter closers reduce effective [waqfShare].
         * 0 = every ayah-final word uses the full share (run-up can sprint).
         * 1 = linear ramp from near-zero at [MIN_LETTERS] to full share at
         * [WAQF_FULL_LETTERS] pronounced letters. Default protects medium
         * closers like عَظِيمًا without muting long waqf.
         */
        val waqfLengthScale: Float = 0.7f,
        /** Fraction of its own slot the wash still crosses while holding, so
         *  the ink breathes instead of freezing dead. */
        val creep: Float = 0.08f,
    )

    /**
     * Monotone piecewise-linear map from normalized sweep time (0..1 of the
     * karaoke hold) to wash position (0..1 across the word). Silent letters
     * contribute width but no time of their own — their slice is folded into
     * the neighbouring pronounced letter's glide so the edge crosses them in
     * motion rather than teleporting; the plateau after the spoken span keeps
     * the ink settled while the reciter breathes before the next word.
     */
    class Curve internal constructor(
        private val times: FloatArray,
        private val positions: FloatArray,
        /** Pronounced letters — drives the paced feather width. */
        val letterCount: Int,
    ) {
        fun at(t: Float): Float {
            if (t >= 1f) return 1f
            val c = t.coerceAtLeast(0f)
            // Last breakpoint at or before c (a duplicate time collapses to
            // the furthest position — the settle point when spoken == 1).
            var i = times.size - 1
            while (i > 0 && times[i] > c) i--
            if (i >= times.size - 1) return 1f
            val span = times[i + 1] - times[i]
            if (span <= 0f) return positions[i + 1]
            val f = (c - times[i]) / span
            return positions[i] + (positions[i + 1] - positions[i]) * f
        }
    }

    /**
     * Converts machine-aligned acoustic keyframes into the renderer's pacing
     * curve. The word clock remains authoritative; a final plateau preserves
     * the karaoke hold after the last voiced sub-word unit.
     */
    fun acousticCurve(
        keyframes: List<SubwordKeyframe>,
        durationMs: Long,
    ): Curve? {
        if (
            keyframes.isEmpty() ||
            durationMs <= 0L ||
            keyframes.first().offsetMs <= 0L
        ) return null
        val appendTail = keyframes.last().offsetMs < durationMs
        val times = FloatArray(keyframes.size + 1 + if (appendTail) 1 else 0)
        val positions = FloatArray(times.size)
        for (i in keyframes.indices) {
            times[i + 1] = keyframes[i].offsetMs.toFloat() / durationMs
            positions[i + 1] = keyframes[i].progress
        }
        if (appendTail) {
            times[times.lastIndex] = 1f
            positions[positions.lastIndex] = 1f
        }
        return Curve(times, positions, letterCount = keyframes.size)
    }

    /**
     * Build the pacing curve for one word of Hafs Uthmani [arabic] text, or
     * null when the word should take the plain sweep untouched.
     *
     * [spokenFraction] is the voiced share of the sweep (spoken span ÷
     * karaoke hold). The letters are laid out across it and the curve rests at
     * 1 for the remainder, so the ink settles as the voice stops rather than
     * smearing across a breath gap. (Only ~0.2 % of segments have any gap at
     * all, but the tail costs nothing and is right when one exists.)
     *
     * Returns null — meaning "plain sweep, exactly as before" — when the word
     * holds no dramatic letter under [hold], when the hold can afford no dwell
     * (`cruiseCap` of 1 on a non-final word), when the word is too short to
     * pace, or when nothing tokenizes.
     *
     * [prevArabic] is the same-ayah predecessor (Hafs Uthmani) for wasl nūn
     * entry. Null means no predecessor — never looks across an ayah boundary.
     */
    fun curve(
        arabic: String,
        spokenFraction: Float = 1f,
        hold: Hold = Hold(),
        prevArabic: String? = null,
    ): Curve? {
        val events = tokenize(arabic)
        if (events.isEmpty()) return null
        val counts = FloatArray(events.size) { weight(events, it) }
        var letters = counts.count { it > 0f }
        if (letters < MIN_LETTERS) return null
        val n = events.size
        var lastPronounced = counts.indexOfLast { it > 0f }

        // Wasl entry: previous nūn/tanwīn + this opening letter (idghām /
        // iqlāb / ikhfāʾ) — sustain the first letter, not the nūn.
        val waslEntry = hold.connect &&
            !prevArabic.isNullOrEmpty() &&
            endsWithNoonSakinOrTanween(prevArabic) &&
            isWaslNoonTarget(events[0].base)
        if (waslEntry) {
            counts[0] = maxOf(counts[0], GHUNNAH)
        }

        // A verse-closing word is sustained on its final letter (madd ʿāriḍ
        // li-s-sukūn, 2/4/6 counts), whatever that letter's mid-flow value.
        val isWaqf = hold.waqf && hold.isAyahFinal
        if (isWaqf && lastPronounced >= 0) {
            counts[lastPronounced] = maxOf(counts[lastPronounced], MADD_LAZIM)
        }

        val held = BooleanArray(n) { i ->
            counts[i] > 0f && when {
                waslEntry && i == 0 -> true
                isWaqf && i == lastPronounced -> true
                hold.madd && counts[i] >= MADD_MUTTASIL -> true
                hold.ghunnah && counts[i] >= GHUNNAH && isGhunnah(events[i]) -> true
                else -> false
            }
        }
        val spoken = spokenFraction.coerceIn(MIN_SPOKEN_FRACTION, 1f)
        if (held.none { it }) return null

        val dwellShare = (
            if (isWaqf) effectiveWaqfShare(hold.waqfShare, letters, hold.waqfLengthScale)
            else 1f - 1f / hold.cruiseCap.coerceAtLeast(1f)
            ).coerceIn(0f, MAX_DWELL_SHARE)
        if (dwellShare <= 0f) return null

        // Time each hold gets, in proportion to how far past a plain harakah
        // it reaches. Width each hold still creeps through while holding.
        val excess = FloatArray(n) { if (held[it]) (counts[it] - 1f).coerceAtLeast(0.5f) else 0f }
        val excessTotal = excess.sum()
        val creep = hold.creep.coerceIn(0f, MAX_CREEP)
        var creepWidth = 0f
        for (i in 0 until n) if (held[i]) creepWidth += creep * slotWidth(i, n, counts, lastPronounced)
        // Constant cruise rate: the width left over after the creeps, spread
        // across the time left over after the dwells.
        val cruiseRate = (1f - creepWidth) / (1f - dwellShare)

        // Two breakpoints per hold (arrive, release), one per plain letter,
        // plus the origin, the final glide and the settle tail. Silent letters
        // get none of their own: their width is crossed on a neighbour's glide.
        val times = ArrayList<Float>(letters + held.size + 4)
        val positions = ArrayList<Float>(letters + held.size + 4)
        times += 0f
        positions += 0f
        var t = 0f
        var x = 0f
        fun glideTo(target: Float) {
            t += (target - x) / cruiseRate
            x = target
            times += t * spoken
            positions += x
        }
        for (i in 0 until n) {
            if (counts[i] <= 0f) continue
            val slotEnd = if (i == lastPronounced) 1f else (i + 1f) / n
            if (!held[i]) {
                glideTo(slotEnd)
                continue
            }
            // Park mid-letter: the glyph is caught half-bloomed, visibly being
            // sustained, rather than held before or after itself.
            val slotStart = slotEnd - slotWidth(i, n, counts, lastPronounced)
            glideTo(slotStart + HOLD_ANCHOR * (slotEnd - slotStart))
            t += dwellShare * excess[i] / excessTotal
            x += creep * (slotEnd - slotStart)
            times += t * spoken
            positions += x
        }
        // A held final letter still has the tail of its own slot to cross.
        if (x < 1f) glideTo(1f)
        // Letters done at the voiced boundary; rest full until handoff.
        times[times.lastIndex] = spoken
        positions[positions.lastIndex] = 1f
        times += 1f
        positions += 1f
        return Curve(times.toFloatArray(), positions.toFloatArray(), letters)
    }

    /**
     * Describes the visible handoff from [prevArabic] into [arabic], or null
     * when the pair has no idghām, iqlāb, or ikhfāʾ connection.
     */
    fun connection(prevArabic: String, arabic: String): Connection? {
        if (!endsWithNoonSakinOrTanween(prevArabic)) return null
        val events = tokenize(arabic)
        if (events.isEmpty() || !isWaslNoonTarget(events.first().base)) return null
        return Connection(prefixFraction = 1f / events.size)
    }

    /**
     * Effective waqf dwell share after length scaling.
     *
     * [lengthScale] 0 → raw [share]. 1 → factor ramps from a small step at
     * [MIN_LETTERS] to 1 at [WAQF_FULL_LETTERS]. Mixed: factor = (1 − s) + s·t
     * so short/medium closers keep a readable run-up when the hold slider is
     * high without dropping the waqf gate entirely.
     */
    private fun effectiveWaqfShare(share: Float, letters: Int, lengthScale: Float): Float {
        val s = lengthScale.coerceIn(0f, 1f)
        if (s <= 0f) return share.coerceIn(0f, MAX_DWELL_SHARE)
        // +1 so the shortest paced closer (3 letters) still has a non-zero
        // factor when scale is 1 — otherwise dwellShare hits 0 and the gate
        // returns null (plain sweep).
        val span = (WAQF_FULL_LETTERS - MIN_LETTERS + 1).toFloat()
        val t = ((letters - MIN_LETTERS + 1).toFloat() / span).coerceIn(0f, 1f)
        val factor = (1f - s) + s * t
        return (share * factor).coerceIn(0f, MAX_DWELL_SHARE)
    }

    /** Width slice of the pronounced event at [i]: its own slot plus any
     *  silent letters folded into it (leading silents ride the letter after
     *  them, trailing silents the letter before). */
    private fun slotWidth(i: Int, n: Int, counts: FloatArray, lastPronounced: Int): Float {
        var start = i
        while (start > 0 && counts[start - 1] <= 0f) start--
        val end = if (i == lastPronounced) n else i + 1
        return (end - start).toFloat() / n
    }

    /** A mushaddad ن/م — the nasal hum that can be leaned on. */
    private fun isGhunnah(e: Event): Boolean =
        e.shadda && (e.base == NOON || e.base == MEEM)

    /**
     * Whether [arabic] ends in a nūn sākinah or tanwīn that can feed a wasl
     * nūn rule into the next word. Mutaharrik nūn (has a ḥaraka) does not.
     */
    private fun endsWithNoonSakinOrTanween(arabic: String): Boolean {
        val events = tokenize(arabic)
        val lastIndex = events.indexOfLast { !it.silent }
        if (lastIndex < 0) return false
        val last = events[lastIndex]
        if (last.tanween) return true
        // Open fatḥatan can sit on the preceding letter with a written,
        // unvoiced carrier alif after it: رُّسُلٗا → مُّبَشِّرِينَ.
        if (last.base == ALEF && events.getOrNull(lastIndex - 1)?.tanween == true) return true
        return last.base == NOON && !last.haraka
    }

    private fun isWaslNoonTarget(base: Char): Boolean =
        base in IDGHAM_YARMALUN || base in GHUNNAH_AFTER_NOON

    /** One base letter plus the combining marks that ride on it. */
    private class Event(val base: Char) {
        var shadda = false
        var sukoon = false
        var maddah = false
        var fatha = false
        var damma = false
        var kasra = false
        var tanween = false
        var madd = false
        var silent = false
        val haraka: Boolean get() = fatha || damma || kasra
    }

    private fun tokenize(arabic: String): List<Event> {
        val events = ArrayList<Event>(arabic.length)
        for (ch in arabic) {
            // The DB is always decomposed (alef + combining maddah), but NFC
            // normalization anywhere upstream would fuse them into U+0622 —
            // and as a bare base letter it would silently weigh 0.5 counts
            // instead of a madd. Unfuse it defensively.
            if (ch == ALEF_MADDA) {
                events += Event(ALEF).apply { maddah = true }
                continue
            }
            if (isBaseLetter(ch)) {
                events += Event(ch)
                continue
            }
            val e = events.lastOrNull() ?: continue // stray leading mark
            when (ch) {
                SHADDA -> e.shadda = true
                // QPC Hafs writes the voiced sukūn as the small dotless khah
                // (ۡ); the round U+0652 marks a letter that is *written but
                // not voiced* (the و of أُوْلَٰٓئِكَ, the plural-waw alif) —
                // same role as the rectangular zero on أَنَا۠.
                VOICED_SUKUN -> e.sukoon = true
                SILENT_SUKUN, RECTANGULAR_ZERO, ROUNDED_ZERO -> e.silent = true
                MADDAH -> e.maddah = true
                FATHA -> e.fatha = true
                DAMMA -> e.damma = true
                KASRA -> e.kasra = true
                // Sequential forms are iẓhār tanween; the un-sequenced trio
                // (U+0656/57/5E) marks ikhfāʾ/idghām tanween in this text.
                FATHATAN, DAMMATAN, KASRATAN,
                OPEN_FATHATAN, OPEN_DAMMATAN, OPEN_KASRATAN,
                SUBSCRIPT_ALEF, INVERTED_DAMMA, FATHA_TWO_DOTS,
                -> e.tanween = true
                // Dagger alef / small waw / small yeh: the elongation of a
                // harakah into a 2-count madd (مَٰ, لَهُۥ, بِهِۦ, إِبۡرَٰهِـۧمَ).
                DAGGER_ALEF, SMALL_WAW, SMALL_YEH, SMALL_HIGH_YEH -> e.madd = true
                else -> Unit // other marks carry no duration
            }
        }
        return events
    }

    private fun isBaseLetter(ch: Char): Boolean =
        ch in 'ء'..'غ' || ch in 'ف'..'ي' || ch == ALEF_WASLA

    /** The event's duration in harakah counts (0 = silent in context). */
    private fun weight(events: List<Event>, i: Int): Float {
        val e = events[i]
        val prev = events.getOrNull(i - 1)
        val next = events.getOrNull(i + 1)
        if (e.silent) return 0f
        // Hamzat wasl elides in continuous recitation; the definite article's
        // lam assimilates into a following sun letter (ٱلضَّآلِّين → "aḍ-ḍ").
        if (e.base == ALEF_WASLA) return 0f
        if (e.base == LAM && prev?.base == ALEF_WASLA && next?.shadda == true) return 0f
        // Maddah marks a madd beyond two counts: lazim when the elongation
        // runs into a sukūn/shadda, muttasil/munfasil otherwise.
        if (e.maddah) {
            return if (next != null && (next.shadda || next.sukoon)) MADD_LAZIM else MADD_MUTTASIL
        }
        var counts = 0f
        if (e.haraka || e.tanween) counts += 1f
        if (e.shadda) counts += if (e.base == NOON || e.base == MEEM) GHUNNAH else 1f
        // A small madd letter extends the harakah to 2 counts; a bare letter
        // wearing a dagger alef (the و of صَلَوٰةِ) *is* the 2-count madd.
        if (e.madd) counts += if (e.haraka) 1f else MADD_TABII
        if (e.sukoon) counts += if (e.base in QALQALAH) QALQALAH_COUNTS else SAKIN_COUNTS
        if (counts > 0f) return counts
        // Bare letter: a madd letter riding the previous harakah, a ghunnah
        // noon/meem (Hafs leaves ikhfāʾ/iqlāb noon unmarked), or plain sākin.
        return when {
            e.base == ALEF || e.base == ALEF_MAKSURA ->
                if (prev?.fatha == true) MADD_TABII else 0f
            e.base == WAW && prev?.damma == true -> MADD_TABII
            e.base == YEH && prev?.kasra == true -> MADD_TABII
            e.base == NOON && next != null && next.base in GHUNNAH_AFTER_NOON -> GHUNNAH
            e.base == MEEM && next?.base == BEH -> GHUNNAH
            else -> SAKIN_COUNTS
        }
    }

    // Counts (harakah units, murattal heuristics — docs/TAJWEED_PACING.md).
    private const val MADD_TABII = 2f
    private const val MADD_MUTTASIL = 4f
    private const val MADD_LAZIM = 6f
    private const val GHUNNAH = 2f
    private const val SAKIN_COUNTS = 0.5f
    private const val QALQALAH_COUNTS = 0.75f
    private const val MIN_LETTERS = 3
    /** Pronounced letters at which [Hold.waqfShare] is applied in full when
     * [Hold.waqfLengthScale] is 1. Medium closers (≈5) sit mid-ramp. */
    private const val WAQF_FULL_LETTERS = 8
    /** Floor on the voiced share so degenerate timing data cannot compress
     * the whole word into a blink followed by a long rest. */
    private const val MIN_SPOKEN_FRACTION = 0.25f
    /** Ceiling on the dwell budget: past this the rest of the word has to
     * sprint, which is the very problem the gate exists to avoid. */
    private const val MAX_DWELL_SHARE = 0.85f
    private const val MAX_CREEP = 0.5f
    /** Where inside its own slot the wash parks: half-way, so the held letter
     * is caught mid-bloom rather than sustained before or after itself. */
    private const val HOLD_ANCHOR = 0.5f
    /** Default junction for long donors; short donors may begin earlier. */
    private const val WASL_EXIT_FRACTION = 0.82f
    /** Floor on the wasl prefix bloom window (matches 1 − [WASL_EXIT_FRACTION]). */
    private const val MIN_WASL_PREFIX_WINDOW = 1f - WASL_EXIT_FRACTION
    /**
     * Max share of a short donor spent on the next-letter bloom. Must be high
     * enough that [DEFAULT_WASL_PREFIX_MS] is reachable on مَن/مِن-scale holds
     * (~500 ms); at 0.50 the ceiling never applied (only ~250 ms of fade).
     */
    private const val MAX_WASL_PREFIX_WINDOW = 0.75f
    /**
     * Shipped speed ceiling (ms) for the next-letter wasl bloom — also the
     * default for [InkEngine.Tuning.waslPrefixMs] / Ink Lab.
     */
    const val DEFAULT_WASL_PREFIX_MS = 480f
    /**
     * Max share of the wasl bloom clock laid down before the next word becomes
     * active. Smoothstep maps 0.45 clock progress to ~0.43 visible progress,
     * leaving most of the incoming word's wash observable after handoff.
     */
    const val DEFAULT_WASL_HANDOFF = 0.45f

    private const val ALEF_WASLA = 'ٱ'
    private const val ALEF = 'ا'
    private const val ALEF_MADDA = 'آ'
    private const val ALEF_MAKSURA = 'ى'
    private const val WAW = 'و'
    private const val YEH = 'ي'
    private const val NOON = 'ن'
    private const val MEEM = 'م'
    private const val LAM = 'ل'
    private const val BEH = 'ب'
    private const val SHADDA = 'ّ'
    private const val MADDAH = 'ٓ'
    private const val VOICED_SUKUN = 'ۡ'
    private const val SILENT_SUKUN = 'ْ'
    private const val FATHA = 'َ'
    private const val DAMMA = 'ُ'
    private const val KASRA = 'ِ'
    private const val FATHATAN = 'ً'
    private const val DAMMATAN = 'ٌ'
    private const val KASRATAN = 'ٍ'
    private const val OPEN_FATHATAN = 'ࣰ'
    private const val OPEN_DAMMATAN = 'ࣱ'
    private const val OPEN_KASRATAN = 'ࣲ'
    private const val INVERTED_DAMMA = 'ٗ'
    private const val FATHA_TWO_DOTS = 'ٞ'
    private const val SUBSCRIPT_ALEF = 'ٖ'
    private const val DAGGER_ALEF = 'ٰ'
    private const val SMALL_WAW = 'ۥ'
    private const val SMALL_YEH = 'ۦ'
    private const val SMALL_HIGH_YEH = 'ۧ'
    private const val ROUNDED_ZERO = '۟'
    private const val RECTANGULAR_ZERO = '۠'

    /** Qalqalah letters (ق ط ب ج د): a sākin one bounces, slightly longer. */
    private val QALQALAH = charArrayOf('ق', 'ط', 'ب', 'ج', 'د')

    /** Letters that give an unmarked ن sākinah its ghunnah word-internally:
     * the fifteen ikhfāʾ letters plus ب (iqlāb). Idghām only occurs across
     * words (and the word-internal يرملون cases are famously iẓhār). */
    private val GHUNNAH_AFTER_NOON = charArrayOf(
        'ت', 'ث', 'ج', 'د', 'ذ', 'ز', 'س',
        'ش', 'ص', 'ض', 'ط', 'ظ', 'ف', 'ق',
        'ك', 'ب',
    )

    /** Idghām letters after nūn sākinah / tanwīn (يرملون): the next word's
     * first base letter absorbs the nūn under wasl. */
    private val IDGHAM_YARMALUN = charArrayOf('ي', 'ر', 'م', 'ل', 'و', 'ن')
}
