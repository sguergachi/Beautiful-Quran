# Repeat Highlighting (the orange second fade)

This note describes how the reader highlights words a reciter **repeats**, why
the *original* timing source couldn't express repeats, where the repeat-aware
data comes from, and the traps we hit making it ship.

> **Most of this document is history, in chronological order.** Sections below
> narrate a dataset that has since been replaced. Read **Current state** next,
> before you measure `data/quran.db` against any number further down.

## Current state

As of `quran-v57.db`, Android and web both read the same verified repeat-aware
rows from the bundled database. There is no runtime timing API, cache, backend,
or on-device audio analysis. The offline build pipeline combines QDC repeat
topology with quran-align's everyayah audio clock, applies every correction and
physical gate, and ships changes only through a reviewed app release.

The database deliberately excludes QF word gloss, transliteration, QCF, and
page-layout fields; authenticated Content Sync keeps those in the separate
seven-day runtime cache.
Bundling the QDC-derived topology requires written QF permission before release.
The code architecture and that permission question are separate.

The current timing table has:

| Fact | Value |
|---|---|
| Timing rows | 43,641 |
| Genuine backtracks (`position < maxBefore`) | 8,947 |
| Segments returning to the historical high-water (`position == maxBefore`) | 4,111 |
| Consecutive same-position pairs | 1,026 |
| Rows with a pair but no lower backtrack | 838 |

**`HighlightEngine`'s repeat test is `position <= maxBefore`, and the `<=` is
load-bearing. Do not "tighten" it to `<`.** Two things depend on it:

1. A genuine **single-word** repeat is two same-position segments — that is the
   only shape it can have. Ear-confirmed example: Hani
   **4:163 word 20** (1180 ms + 1510 ms).
2. A multi-word chain's **final** word returns to the high-water rather than
   below it (Mishary 2:14 replays 7…11; that closing `11` equals `maxBefore`).
   Under `<` the chain would drop its last word.

Alignment artifacts that *would* read as false repeats are stripped by the
**offline build normalizer**, by duration and ratio rather than adjacency — see
[Cleanup](#false-repeats-the-qdc-artifacts-we-scrub). Anything surviving into a
release database is data the pipeline judged real. If a specific row is wrong,
ear-check it and fix its class in `tools/build_db.py`; never with an engine-wide
heuristic or a client-side repair.

## What it is

Reciters often repeat a word or phrase — most obviously in teaching (*muallim*)
recitations, but also in ordinary *murattal* for emphasis or breath. The normal
follow-along highlight is a one-way karaoke sweep: each word lights once, in
order, and holds. It has no way to say "he just said that again."

The repeat feature adds a **second, orange fade**: when the recitation jumps back
to re-recite an earlier word, that word blooms in orange (`QuranAccents.repeatInk`)
as it is said again, riding on top of the already-lit ink, and then **dissolves
back to the standard ink** once the recitation moves past the repeated stretch.
Nothing dims backward; the orange is a transient overlay that marks "this is a
repeat," then fades.

## The data problem: quran-align can't encode repeats *(history)*

Our original timing source is [`cpfair/quran-align`](https://github.com/cpfair/quran-align):
a **one-pass forced aligner** that maps each Quran word to exactly one time span,
in order. A repeat would have to appear as a segment whose word index goes
*backward* (`… 9 10 11 7 8 9 10 11 12 …`). A one-pass aligner structurally cannot
emit that.

We confirmed this empirically **against the DB as it stood in July 2026** (37,415
rows, quran-align only): a real repeat shows a segment `position` dropping below a
previously-seen position, and there were **zero** such backtracks. (The only 30
non-monotonic rows were a benign tokenization artifact — a duplicated final word,
time-contiguous, identical across all reciters.) So the orange fade was a **data**
problem, not a rendering problem.

> **Every number in that paragraph is dead.** It describes the pre-qdc dataset,
> and it is kept only to explain *why* the qdc import happened. The current
> counts are in [Current state](#current-state); a same-position pair in today's
> data is a real repeat, not the old artifact. Reading this section as live state
> and then "fixing" the engine's `<=` deletes ear-confirmed repeats — that has
> already happened once in review.

## The data solution: quran.com `qdc` segments

The quran.com audio API preserves repeats. Its per-verse segment data uses the
same `[word_index, start_ms, end_ms]` shape we already parse, **except the word
index backtracks** when the reciter repeats.

```
GET https://api.quran.com/api/qdc/audio/reciters/{id}/audio_files?chapter_number={n}&segments=true
```

Two facts make this a drop-in source rather than a new recording to license:

1. **Same audio.** The `qdc` audio for these reciters is the *same everyayah
   recording the app already streams*. We verified by duration: everyayah
   `Alafasy_128kbps/002014.mp3` is 23.71 s and quran.com's 2:14 window is
   23.77 s; Hani 2:16 is 18.89 s vs 19.15 s. The small delta is gapless-file
   silence trimming.
2. **The repeats are real.** quran.com's murattal backtracks are not alignment
   noise: the repeated words occupy substantial, contiguous, non-overlapping
   time. This was **ear-confirmed** for Mishary — in `002014.mp3` he audibly
   recites words 7–11, then says 7–11 again (ayah-relative ~6.4–10.6 s, then
   ~10.6–16.5 s).

### Per-reciter availability

Sampled backtrack counts across ~765 ayahs per reciter (quran.com recitation id
in parentheses). "Encodes repeats" = has backtracks. Enabled reciters were
ear-verified (Mishary 2:14, Hani 2:16); **verify the rest before enabling.**

| Our reciter | qdc id | Encodes repeats? |
|---|---|---|
| Mishary Alafasy (murattal) | 7 | ✅ enabled, ear-verified |
| Hani ar-Rifai | 5 | ✅ enabled, ear-verified |
| Al-Husary (murattal) | 6 | ✅ enabled (not ear-verified) |
| Al-Husary — **Muallim** (teaching) | 12 | yes (dense; not yet imported) |
| AbdulBaset (murattal) | 2 | ✅ enabled (not ear-verified) |
| Minshawi (murattal) | 9 | ✅ enabled (not ear-verified) |
| Minshawi (mujawwad) | 8 | yes (very dense) |
| As-Sudais | 3 | ✅ enabled (not ear-verified) |
| Ash-Shuraym | 10 | no (one-pass) |
| AbdulBaset (mujawwad) | 1 | no (one-pass) |

## How a repeat is detected

Given an ayah's segments sorted by start time, a segment is a **repeat pass** if
its word position is ≤ the maximum position already seen earlier in the ayah.
`HighlightEngine.activeInfo` computes this at the current playback position and
returns:

- `isRepeat` — the active word points back at an earlier position;
- `highWater` — the furthest word reached so far in the ayah;
- `repeatStart` — the first word of the **current repeat chain** (the word the
  reciter jumped back to). Equals the active position when not repeating.

`highWater` is what keeps the display sane during a repeat: when the active word
jumps backward, every word up to the high-water mark was *already recited*, so it
must **hold full ink** instead of reverting to the dim "upcoming" state.

`repeatStart` is what makes the highlight a **chain, not a flash**. While a
reciter re-recites a stretch, every word from `repeatStart` through the word now
being said is a member of the chain and holds orange together; the chain releases
only when the recitation advances past `highWater` onto new, unread words. The
chain start is found by walking back from the active segment over the contiguous
run of backtracked segments and taking the minimum position (see `activeInfo`).

## TimingEngine V1.5 (`tools/build_db.py`)

TimingEngine is deliberately not a source-voting system. Each input answers one
question it is qualified to answer:

- qdc supplies repeat topology;
- quran-align supplies the streamed-file clock and monotonic fallback;
- measured audio supplies the playable opening and ending;
- CTC repairs and typed verdicts resolve only evidence-backed exceptions.

The engine does three things in order: clean topology from local positive
evidence, anchor it to the audio file, then enforce completion and physical
safety. Uncertainty never grows another heuristic branch: it falls back to the
monotonic reference, or withholds word timing when even that is unsafe.

Repeat-aware reciters are listed in `QDC_REPEAT_RECITERS` (map: our `reciter_id`
→ quran.com recitation id). `load_qdc_timings()` fetches and hash-verifies their
offline build inputs, then `tools/build_db.py` produces the bundled rows:

- `load_qdc_timings()` rebases each verse's gapless-file offsets to ayah-relative
  ms (`start − timestamp_from`) and preserves repeats. A normal build includes
  this path; `--quran-align-only` exists only for explicit fallback audits.
- `adjust_qdc_segments()` clamps word positions to our canonical word count,
  drops zero-length spans, keeps repeats, and counts the repeat spans.
- `rebase_qdc_clock()` translates the complete repeat-aware row by the upper
  median of matching later-word starts plus the first-word end. Quran-align
  supplies the exact everyayah MP3 clock; qdc continues to supply repeat
  topology. Excluding the first start prevents a malformed opening from
  dragging every later word behind the voice; conflicting two-word witnesses
  prefer the offset closest to zero. The translation is refused outright when
  the witnesses disagree by more than `MAX_CLOCK_DISAGREEMENT_MS`, or when the
  result would run past the recording's measured duration — quran-align
  sometimes stretches a word across a long pause and every later boundary in
  that row drifts, and no median of scattered witnesses is a real clock.
  When no translation reconciles a qdc row with its recording but the
  quran-align row does fit inside it, that ayah falls back to quran-align: it
  was aligned against the very file the app streams, so it tracks the voice.
  The trade is that ayah's repeat topology — a repeat drawn on a clock that
  outruns the audio is worse than no repeat at all.
- `clean_qdc_artifacts()` scrubs aligner artifact classes that would otherwise
  render as repeats the reciter never made (see below). New structural classes
  go here — not into one-off overrides — and each is locked by a case under
  [`tools/timing_patch_cases/`](../tools/timing_patch_cases/README.md).

After cleanup, generated CTC evidence is sequence-diffed onto only the changed
structural spans. Substantial same-word re-says are restored per position, so
an unrelated missing-word repair can still land; a repair cannot flatten a
multi-word re-say. Shapes that topology and CTC cannot safely decide use narrow
typed operations under `tools/timing_corrections/`.

The finalizer then enforces four corpus laws:

1. Every shipped row covers every canonical word; repeats may add occurrences.
2. Starts are unique and increasing; spans are positive and non-overlapping.
3. The row uses the exact everyayah file clock, starts on/after measured voice,
   and fits inside the MP3 duration.
4. A same-clock reference may fill missing words only when the splice preserves
   the source's exact repeat signature. Any new backtrack falls back to the
   complete monotonic quran-align row; if that is also unsafe, word timings are
   withheld and the reader highlights the whole ayah.

The committed database audit checks all reciters for full word coverage, repeat
topology, file duration, voice onset, non-overlap, provenance, and exact known
fixtures. The Hani 5:2 two-pass phrase is pinned explicitly so a one-pass build
cannot silently ship again.

### The small heuristic set

The cleaner defaults to **preserving a repeat**. It changes topology only when
one of these local shapes supplies positive evidence:

- a same-position half is a short/dwarfed split fragment;
- a forward jump immediately retreats, making it a premature label;
- disconnected positions inside one backtrack run are relabeled onto its
  near-high-water component;
- a backtrack run occupies a skipped forward gap;
- a duplicated forward destination exactly accounts for words absent
  everywhere else in the row.

A real re-say does **not** have to return to the previous high-water tip. If a
skipped word appears later, the duplicate-gap rule abstains. Acoustics alone
also cannot erase a qdc repeat: same-word split versus re-say is ambiguous
without topology or an explicit ear-verified verdict. Once a skipped gap is
filled, its new positions advance the same high-water state as ordinary input,
so a later heuristic cannot adjudicate that gap a second time.

### Production boundary and Timing V2

**TimingEngine V1.5 is the production engine.** [Timing V2 PR
#617](https://github.com/sguergachi/Beautiful-Quran-/pull/617) explores the
right longer-term model: occurrence structure is separate from the audio
clock, and letter keyframes can drive a more faithful within-word wash. That
model has a higher quality ceiling, but its current implementation is a
developer research lane rather than a replacement for V1.5.

The distinction is evidence, not ambition:

- V1.5 has one small, auditable heuristic set, has been checked across the
  complete Alafasy and Hani corpora, and fails closed when topology and the
  exact everyayah MP3 clock cannot be reconciled.
- V2's full QUA lane preserves valuable word and letter structure, but currently
  makes QUA's surah-audio timestamps verse-relative and scales an overflowing
  row to the everyayah duration. Duration fit is not clock alignment: without
  same-take proof, waveform correlation, or fixed-sequence alignment against
  the exact streamed file, precise-looking keyframes may follow a different
  recording.
- V2 still uses V1 repeat topology and runtime fallback where a lower lane
  flattens a re-say. That is a sound safety net, but also means V2 does not yet
  supersede V1.
- Replaying Lab gold proves those rows were reproduced; it is not independent
  validation. The frozen independent V2 evaluation set must be labeled before
  it can support a production accuracy claim.

V2 should therefore grow as a precision layer over V1.5's safety contract:

```text
independent occurrence topology (QUA / qdc)
        ↓
fixed-sequence alignment against the exact everyayah audio
        ↓
word and letter keyframes
        ↓
V1.5 completion and physical-safety finalizer
        ↓
V1.5 row whenever any evidence gate fails
```

V2 may become the production default only when all of these are true:

1. Every accepted row is genuinely reclocked to the exact file the app streams;
   scaling a foreign clock to fit its duration is not sufficient.
2. A frozen, independently labeled structure-and-onset set meets its declared
   thresholds. Inputs reused as output priorities are reported separately.
3. V2 is compared with V1.5 on the same corpus and the same timing patch cases,
   with accepted, rejected, and fallback rows counted separately.
4. An Alafasy-only precision lane is described as per-reciter enhancement, not
   as an engine replacement, until it matches the production coverage it
   claims to replace.
5. Timing-data correctness and subword rendering remain separable changes, so
   neither needs the other to be reviewed or shipped safely.

The intended destination is not a larger rule set. It is V2's richer structural
model constrained by V1.5's smaller laws: use each source only for what it can
prove, align against the audio that actually plays, and abstain rather than
manufacture precision.

## False repeats: the qdc artifacts we scrub

The raw qdc segments are aligner output, and some of their apparent backtracks
are **not audible repeats**. Artifact classes scrubbed in `clean_qdc_artifacts`
(see also non-contiguous span phantoms in
[tools/timing_repairs/README.md](../tools/timing_repairs/README.md)):

1. **Split slivers.** The aligner sometimes emits a word's onset or tail as a
   tiny extra segment sharing that word's position (`… [18, 0, 1410],
   [18, 1410, 1500], …` — a 90 ms tail). The sliver satisfies
   `position <= maxBefore`, so it bloomed orange as an instant one-word
   "repeat." Fix: merge a same-position, time-contiguous neighbour when it is a
   fragment rather than a full utterance. A span is a fragment when it is either
   **shorter than `QDC_SPLIT_FRAGMENT_MS` (200 ms)** — too short to be any spoken
   word — **or shorter than `QDC_SPLIT_FRAGMENT_CEIL_MS` (700 ms) *and* under
   `QDC_SPLIT_FRAGMENT_RATIO` (0.40) of its neighbour's length** — dwarfed by the
   word it split off from. The flat floor alone missed slivers in the 200–450 ms
   band (Hani 4:143 word 10 = a 210 ms onset + 1290 ms body, issue #123, which
   bloomed as a false repeat); the ratio clause catches those while the warning
   below keeps the rule from eating real single-word repeats. A prior CEIL of
   500 ms left dwarfed 500–700 ms tails classified as peers (Alafasy 4:171
   إِلَىٰ = 1600+600 ms, issue #634), so `preserve_peer_repeats` also blocked
   the matching CTC unsplit — the CEIL/ratio pair now covers that band.
2. **Mislabeled strays.** A single segment carrying a wrong, *earlier* word
   index — often a sound-alike (49:9 goes `… 7, [1], 9 …`: word 8 فَإِن was
   tagged as word 1 وَإِن) — then the recitation continues forward past the
   high-water mark. A real repeat never does this: it walks forward again as a
   chain. Fix: drop an isolated backjump segment whose successor jumps past
   the high-water mark, folding its span into the previous word. This is the
   class behind the original "single word flashes orange but isn't repeated"
   report.
3. **Forward spikes.** The same mislabel in the other direction (`… 2, [8],
   3, 4, 5 …`). Worse than it looks: the spike inflates `highWater`, so every
   normal word after it (3–7 here) satisfied the backtrack test and a long
   false orange chain appeared. Fix: drop a segment that jumps ≥
   `QDC_SPIKE_JUMP` past the high-water mark and immediately retreats. A +2
   jump is also a spike only when the aligner duplicates that premature
   position and the retreat immediately walks forward through it: Alafasy
   16:106 emitted `…12, 7…11, [14,14], 12,13,14…`; dropping the premature 14s
   preserves the real 7…11 re-say and prevents the normal 12…14 continuation
   from appearing as a second repeat.
4. **Non-contiguous span phantoms.** The aligner stamps an early function-word
   index at the *onset* of a real near-high-water re-say (Alafasy 5:54:
   `… 21, 22, 23, [4], 21, 22, 23, 24 …` — long يُجَٰهِدُونَ labeled as مَن).
   CTC span protection trusts the multi-position run; the reader paints orange
   from 4 through 23. Fix: within each backtrack run, keep the position
   component nearest the high water and relabel orphan components onto it
   (`QDC_SPAN_CONNECT_GAP`). Locked by `tools/timing_patch_cases/noncontiguous-*.json`.
5. **Backtrack-gap phantoms.** A backtrack run followed by a resume that skips
   first-pass words occupies those missing words' time (`…11,8,9,13…`, word 12
   absent). A one-segment backtrack (`…2,1,4…`) is retried as a coverage-only
   recovery only when no reference or CTC repair completes the row; it then
   relabels onto word 3 rather than losing the sole coverage witness as a
   stray. A real earlier re-say that resumes at `highWater + 1` is untouched.
6. **Forward-gap duplicates.** A duplicated destination exactly accounts for
   an otherwise absent gap (`1,3,3,4` becomes `1,2,3,4`). If the skipped word
   appears anywhere later, the rule abstains; Alafasy 16:106 locks that
   counterexample.

> **⚠️ A genuine single-word repeat looks exactly like a split sliver — same
> position, ~0 ms gap — so the merge must key on *duration*, not the gap.**
> When a reciter says a word and immediately says it again, qdc emits two
> same-position segments with no gap between them, identical in shape to a
> split. The only reliable difference is that a real repeat's two halves are
> both *full, comparable utterances* (across all six reciters the shorter half's
> median is ≥ ~1.2 s; ear-confirmed peers like Hani 4:4 = 1710+1120 ms sit well
> above the dwarfing gate), whereas a split's extra piece is a sub-word sliver
> or dwarfed tail (a fragment: < 200 ms, or < 700 ms and under 0.40 of the word
> it split from). An earlier version of this cleanup merged on the gap alone and
> silently ate real repeats — e.g. Hani **4:163 word 20** (1180 ms + 1510 ms,
> ear-confirmed via a Timings Lab correction). The duration/ratio fragment test
> is what fixes that: only slivers and dwarfed tails fold; two substantial,
> comparable utterances stay a repeat. The ratio clause keys on the split being
> *dwarfed* by its neighbour, so it can never touch two peer utterances however
> the absolute floor is tuned.

Each rule has a paired survival fixture. Real repeats need not revisit the
previous high-water tip, and substantial same-word peers survive even with a
zero gap. The ear-verified repeats (Mishary 2:14, Hani 2:38's
`12,13,14 — 12,13,14`, Hani 4:163's doubled word 20) survive cleanup. The
cleanup runs to a fixpoint because dropping a spike can reunite a word with its
stray sliver (9:51: `4, [7], 4` → `4, 4`, then merged only if one `4` is a
sliver).

When evidence remains ambiguous, preserve the source repeat and require a
typed ear/acoustic verdict. A false orange bloom is visible, but silently
deleting a genuine re-say is not an acceptable default either.

When a real repeat is still missed or a false one slips through:

1. **Systematic first.** If the shape is a class (spikes, non-contiguous
   phantoms, false splits), extend `clean_qdc_artifacts` or the CTC repair
   generator and add a unit test under
   [`tools/timing_patch_cases/`](../tools/timing_patch_cases/README.md) whose
   expected output is the Timings Lab / ear-verified fix. Run
   `python3 tools/test_build_db.py`.
2. **Narrow ambiguity only.** If topology cannot decide, add the smallest typed
   operation under `tools/timing_corrections/` with evidence provenance.
3. **No one-off shipping.** A Timings Lab override may reproduce the issue
   locally, but it must be deleted before commit.

## The rendering path

```
HighlightEngine.PreparedTimings.activeInfo(positionMs)
    → ActiveWord(wordPosition, durationMs, isRepeat, highWater, repeatStart)   (ReaderViewModel, ~30/s)
    → InkEngine.wordState(...) uses highWater to hold already-recited words lit;
      InkEngine.inRepeatChain(position, activeWord) = repeatStart..wordPosition membership
    → renderers wash chain members in QuranAccents.repeatInk, from full ink
```

- `InkEngine.wordState` adds a `position <= activeWord.highWater → Recited`
  clause so a backward jump doesn't dim the words ahead of the active one.
- **Chain membership, not a single word.** `InkEngine.inRepeatChain(position,
  activeWord)` is true when `repeatStart ≤ position ≤ wordPosition`. Every
  member holds orange until the
  chain releases together, so a repeated *section* stays highlighted as one unit.
  When Active advances to the next member, the previous member only dries its
  glimmer; its completed orange sweep is held and must not restart. Only chain
  entry or a genuine non-zero seek activation while that same word remains
  Active can begin that sweep. A session's older seek generation must not
  queue a second wash as each later chain member becomes Active.
- **Audio-bound residual wash (law).** On Android, every live member begins at
  its own spoken boundary and uses its measured audio dwell. A predecessor may
  finish its soft residual edge concurrently, but can never queue ahead of the
  word being spoken. `Tuning.repeatSweepMs` is only a fallback when no active-
  word clock exists. Seeking into the middle of a chain marks earlier inactive
  members complete and reveals only the current word; replaying the already-
  heard prefix would manufacture seconds of lag. Web, pending its tajweed
  pacing port, uses `repeatSweepMs`. Active handoff must **not** cancel
  an in-flight wash (no `LaunchedEffect(activation)` cancel; no snap
  incomplete→full). Release finishes any residual progress by animating the
  remainder, then dissolves alpha (web: `runRepeatReleaseAsync`).

  This replaced a serialized 450 ms minimum that could not stay on the audio
  clock: short words accumulated queue debt even though their database
  boundaries were correct. A corpus replay of the removed policy found visual
  delay in 499 timing rows (70 reached at least 250 ms and 10 reached at least
  450 ms). Shuraym 7:146 was 670 ms behind by repeated word 18; Minshawy 18:16
  reached 215 ms at its final repeated word. The full Shuraym boundary chain is
  an executable regression in `InkEngineTest`.
- **The orange blooms from the read (full-ink) colour, not the dim unread one.**
  A repeated word was already recited, so its base ink stays full strength and
  the orange arrives as its **own directional wash on top** — it does not re-run
  the base layer's dim→ink sweep, and it is not a colour tween either. Both
  renderers use the same soft feathered edge as first-pass ink:
  - Layered Arabic + gloss word units: `Modifier.repeatInkLayer` =
    `glyphLayerAlpha { wash.alpha }` over `letterFadeIn(progress = wash.progress,
    restingAlpha = 0f, feather = Tuning.washFeather)`.
  - Continuous English and Arabic-only Hafs:
    `ShapedWordBloom.ColorReveal` — re-draw the shaped run,
    `BlendMode.SrcIn`-tint it, then `DstIn`-wash it.

  Android timing lives once in the ayah's per-word `InkMotion`
  (`rememberRepeatWash` is its internal repeat clock), shared by gloss, Hafs,
  and English; web uses `WordUnit` / `HafsWord`. On Android, chain entry captures the active
  word's measured sweep duration, tajweed curve, and paced feather;
  `Tuning.repeatSweepMs` (450 ms by default) is used only without a live timing
  clock. Each word owns that captured animation, so an Active handoff cannot
  erase its pacing and a prior residual cannot delay the new word. On release,
  residual progress finishes independently, then alpha dissolves over
  `Tuning.repeatFadeOutMs` (900 ms). Web keeps the constant 450 ms clock until
  tajweed pacing is ported. A live chain member's displayed progress is pinned
  at 0 until its retained animation clock resets, preventing a one-frame full-
  orange/glimmer flash before the directional edge begins.
  On Nightfall, each newly active repeat word also
  replays the white-gold glimmer over that orange bloom: the repeat is a new
  event even though the word's base ink was already revealed. This includes
  same-word repeats and repeat-chain re-entry — every Active entry glints, with
  no replay suppression to override. Repeat glimmer normally uses the
  same dark terracotta as the orange wash. When a single word enters its repeat
  before the first-pass white-gold glimmer has released, that existing glimmer
  instead dries away as the directional terracotta wash replaces it—there is
  no hard colour swap at the repeat boundary, and the old gold stays gone while
  the orange ink dissolves after handoff to the next word. See
  [GLIMMER.md](GLIMMER.md) for the lifecycle, layer order, and artifact rules.
- `repeatInk` is defined per theme in `QuranAccents` — `#B4551E` (light),
  `#E06A18` (Nightfall + Royal Green). Peak overlay strength is
  `InkEngine.Tuning.repeatInkAlpha` (default 1; Ink Lab **Repeat ink** slider).

Worked example (Al-Baqarah 2:14, `… 7 8 9 10 11 [7 8 9 10 11] 12 …`): the first
pass 1–11 is normal white karaoke. On the jump back to 7 the chain opens
(`repeatStart = 7`); as the reciter re-says 7, 8, 9, 10, 11 each turns orange
**and stays orange**, so by word 11 the whole phrase 7–11 glows. When the voice
reaches word 12 (past `highWater = 11`) the chain closes and 7–11 dissolve back to
read ink together while 12 fades in white as a new word.

## Traps we hit (read before touching this)

- **Do not replace the pinned repeat rows without full-corpus parity.** Change
  the canonical pipeline, run its regression suite, and prove the replacement
  matches the accepted topology before shipping. The timing delta gate stays
  fail-closed.
- **Bump the DB version when bundled fallback content changes.** `QuranDatabase.DB_FILE_NAME`
  (`quran-vN.db`) is the extraction key: the bundled asset is copied to internal
  storage only if that file doesn't already exist. Changing the DB's *content*
  without bumping the suffix means existing installs keep the stale cached copy —
  which is exactly why the orange first "didn't appear" in the historical
  bundled implementation. Always read the live value rather than trusting a
  number here; every timing change requires a database bump and app release.
- **quran.com timestamps are gapless-file offsets**, not per-ayah. Always
  subtract the verse's `timestamp_from`. (The build does this; noted here because
  it's the first thing that looks wrong if you inspect the raw API.)
- **First-ayah bismillah.** For a surah's ayah 1 the audio includes the bismillah;
  quran.com's window covers it, and single-word first ayahs (e.g. الٓمٓ) simply
  span the whole window. Not a problem in practice, but don't expect a separate
  bismillah segment.
- **Enabled reciters are ear-verified; the rest are not.** Mishary (2:14) and
  Hani (2:16) were confirmed by ear. The other reciters' backtracks are almost
  certainly real (contiguous, realistically timed) but haven't been listened to.
  Verify before enabling — a false positive would flash orange where the reciter
  didn't actually repeat.

## Adding another repeat-aware reciter

1. Add `our_reciter_id: qdc_id` to `QDC_REPEAT_RECITERS` in `tools/build_db.py`.
2. Run an audit build and check repeat-span, coverage, physical gates,
   and exact reader parity without committing the audit database.
3. Ear-verify flagged ayahs, add regression fixtures, rebuild `quran.db`, bump
   `DB_FILE_NAME` and the fingerprint, then ship it through an app release.
