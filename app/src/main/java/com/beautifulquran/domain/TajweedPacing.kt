package com.beautifulquran.domain

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
 *   Pass the previous word as [prevArabic]. The previous word can pass
 *   [nextArabic] so its trailing nūn exits early (absorbed, not settled).
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
     * shaped word. [at] blooms that prefix over the absorbed nūn's tail.
     */
    data class Connection(val prefixFraction: Float) {
        /** Opening-glyph bloom progress at normalized prior-word time [t]. */
        fun at(t: Float): Float =
            ((t - WASL_EXIT_FRACTION) / (1f - WASL_EXIT_FRACTION)).coerceIn(0f, 1f)
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
         *  opening letter when [prevArabic] feeds the rule; exit early when
         *  [nextArabic] absorbs this word's trailing nūn. */
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
     * [prevArabic] / [nextArabic] are same-ayah neighbours (Hafs Uthmani) for
     * wasl nūn rules. Null means no neighbour on that side — never looks
     * across an ayah boundary.
     */
    fun curve(
        arabic: String,
        spokenFraction: Float = 1f,
        hold: Hold = Hold(),
        prevArabic: String? = null,
        nextArabic: String? = null,
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

        // Wasl exit: this word's trailing nūn is absorbed into the next word —
        // soft weight and early layout so the wash does not settle on it.
        val waslExit = hold.connect &&
            !nextArabic.isNullOrEmpty() &&
            endsWithNoonSakinOrTanween(arabic) &&
            startsWithWaslNoonTarget(nextArabic)
        if (waslExit && lastPronounced >= 0) {
            counts[lastPronounced] = minOf(counts[lastPronounced], ABSORBED_NOON)
            if (counts[lastPronounced] <= 0f) {
                lastPronounced = counts.indexOfLast { it > 0f }
                letters = counts.count { it > 0f }
                if (letters < MIN_LETTERS && !waslEntry) return null
            }
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
                // Absorbed trailing nūn is never held — the next word owns it.
                waslExit && i == lastPronounced -> false
                isWaqf && i == lastPronounced -> true
                hold.madd && counts[i] >= MADD_MUTTASIL -> true
                hold.ghunnah && counts[i] >= GHUNNAH && isGhunnah(events[i]) -> true
                else -> false
            }
        }
        val spoken = spokenFraction.coerceIn(MIN_SPOKEN_FRACTION, 1f)
        // Letters finish early when the trailing nūn is absorbed next door.
        val layoutSpoken = if (waslExit) {
            (spoken * WASL_EXIT_FRACTION).coerceIn(MIN_SPOKEN_FRACTION, spoken)
        } else {
            spoken
        }

        // The gate: nothing dramatic — unless wasl exit alone needs an early finish.
        if (held.none { it }) {
            if (!waslExit) return null
            return earlyExitCurve(layoutSpoken, spoken, letters)
        }

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
            times += t * layoutSpoken
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
            times += t * layoutSpoken
            positions += x
        }
        // A held final letter still has the tail of its own slot to cross.
        if (x < 1f) glideTo(1f)
        // Letters done at layoutSpoken; rest full until spoken, then handoff.
        times[times.lastIndex] = layoutSpoken
        positions[positions.lastIndex] = 1f
        if (layoutSpoken < spoken) {
            times += spoken
            positions += 1f
        }
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

    /** Plain early finish when the only wasl signal is an absorbed trailing nūn. */
    private fun earlyExitCurve(layoutSpoken: Float, spoken: Float, letterCount: Int): Curve {
        val times = ArrayList<Float>(4)
        val positions = ArrayList<Float>(4)
        times += 0f
        positions += 0f
        times += layoutSpoken
        positions += 1f
        if (layoutSpoken < spoken) {
            times += spoken
            positions += 1f
        }
        times += 1f
        positions += 1f
        return Curve(times.toFloatArray(), positions.toFloatArray(), letterCount)
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

    /** First base letter is an idghām / iqlāb / ikhfāʾ target (not iẓhār, not wasla). */
    private fun startsWithWaslNoonTarget(arabic: String): Boolean {
        val first = tokenize(arabic).firstOrNull() ?: return false
        return isWaslNoonTarget(first.base)
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
    /** Trailing nūn absorbed into the next word — almost no solo duration. */
    private const val ABSORBED_NOON = 0.25f
    /** Fraction of the spoken span used to finish letters when wasl-exiting. */
    private const val WASL_EXIT_FRACTION = 0.82f

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
