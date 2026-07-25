# Repeat Highlighting (the orange second fade)

This note describes how the reader highlights words a reciter **repeats**, why
the *original* timing source couldn't express repeats, where the repeat-aware
data comes from, and the traps we hit making it ship.

> **Most of this document is history, in chronological order.** Sections below
> narrate a dataset that has since been replaced. Read **Current state** next,
> before you measure `data/quran.db` against any number further down.

## Current state

Repeat-aware qdc timings are **shipped**. Measured against the committed
`data/quran.db` (re-verified 2026-07-24):

| Fact | Value |
|---|---|
| Timing rows | 43,650 |
| Genuine backtracks (`position < maxBefore`) | 8,805 |
| Segments returning to the historical high-water (`position == maxBefore`) | 3,624 |
| …of those, *consecutive* same-position pairs | 575 |
| Consecutive pairs in rows with no backtrack at all | 415, across 392 rows |

**`HighlightEngine`'s repeat test is `position <= maxBefore`, and the `<=` is
load-bearing. Do not "tighten" it to `<`.** Two things depend on it:

1. A genuine **single-word** repeat is two same-position segments — that is the
   only shape it can have. Ear-confirmed example still live in the DB: Hani
   **4:163 word 20** (1180 ms + 1510 ms).
2. A multi-word chain's **final** word returns to the high-water rather than
   below it (Mishary 2:14 replays 7…11; that closing `11` equals `maxBefore`).
   Under `<` the chain would drop its last word.

Alignment artifacts that *would* read as false repeats are stripped at **build
time**, by duration and ratio rather than by adjacency — see
[Cleanup](#false-repeats-the-qdc-artifacts-we-scrub). Anything surviving into the
DB is data the pipeline judged real. If you suspect a specific row is wrong,
ear-check it and fix it in `tools/build_db.py` or a timing override; never with
an engine-wide heuristic.

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
| As-Sudais | 3 | ✅ enabled (not ear-verified; also fills our missing-timings gap) |
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

## The build pipeline (`tools/build_db.py`)

Repeat-aware reciters are listed in `QDC_REPEAT_RECITERS` (map: our `reciter_id`
→ quran.com recitation id). For those, the build fetches `qdc` segments instead
of quran-align:

- `load_qdc_timings(qdc_id)` fetches all 114 surahs, **rebases** each verse's
  gapless-file offsets to ayah-relative ms (`start − timestamp_from`), preserves
  repeats, and caches the assembled result in `tools/.cache/qdc_<id>.json`.
- `adjust_qdc_segments()` clamps word positions to our canonical word count,
  drops zero-length spans, keeps repeats, and counts the repeat spans.
- `clean_qdc_artifacts()` scrubs aligner artifact classes that would otherwise
  render as repeats the reciter never made (see below). New structural classes
  go here — not into one-off overrides — and each is locked by a case under
  [`tools/timing_patch_cases/`](../tools/timing_patch_cases/README.md).

After cleanup, Mishary yields **3,142 repeat spans** at full 6,236/6,236
coverage; Hani yields **2,037 repeat spans** at 6,235/6,236 (one ayah has no
quran.com segments and falls back to whole-ayah highlighting). Everyone not in
`QDC_REPEAT_RECITERS` still uses quran-align exactly as before.

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
   word — **or shorter than `QDC_SPLIT_FRAGMENT_CEIL_MS` (500 ms) *and* under
   `QDC_SPLIT_FRAGMENT_RATIO` (0.35) of its neighbour's length** — dwarfed by the
   word it split off from. The flat floor alone missed slivers in the 200–450 ms
   band (Hani 4:143 word 10 = a 210 ms onset + 1290 ms body, issue #123, which
   bloomed as a false repeat); the ratio clause catches those while the warning
   below keeps the rule from eating real single-word repeats.
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
   `QDC_SPIKE_JUMP` past the high-water mark and immediately retreats.
4. **Non-contiguous span phantoms.** The aligner stamps an early function-word
   index at the *onset* of a real near-high-water re-say (Alafasy 5:54:
   `… 21, 22, 23, [4], 21, 22, 23, 24 …` — long يُجَٰهِدُونَ labeled as مَن).
   CTC span protection trusts the multi-position run; the reader paints orange
   from 4 through 23. Fix: within each backtrack run, keep the position
   component nearest the high water and relabel orphan components onto it
   (`QDC_SPAN_CONNECT_GAP`). Locked by `tools/timing_patch_cases/noncontiguous-*.json`.

> **⚠️ A genuine single-word repeat looks exactly like a split sliver — same
> position, ~0 ms gap — so the merge must key on *duration*, not the gap.**
> When a reciter says a word and immediately says it again, qdc emits two
> same-position segments with no gap between them, identical in shape to a
> split. The only reliable difference is that a real repeat's two halves are
> both *full utterances* (across all six reciters the shorter half's median is
> ~1.2 s and is ≥ ~500 ms), whereas a split's extra piece is a sub-word sliver
> (a fragment: < 200 ms, or < 500 ms but a fraction of the word it split from).
> An earlier version of this cleanup merged on the gap alone and silently ate
> real repeats — e.g. Hani **4:163 word 20** (1180 ms + 1510 ms, ear-confirmed
> via a Timings Lab correction). The duration/ratio fragment test is what fixes
> that: only slivers fold; two substantial, comparable utterances stay a repeat.
> The ratio clause keys on the split being *dwarfed* by its neighbour, so it can
> never touch two peer utterances however the absolute floor is tuned.

None of these rules can touch a genuine repeat: real multi-word chains re-walk
forward after the backjump (so their members are never "isolated" strays or
spikes), and single-word repeats are preserved by the duration floor. The
ear-verified repeats (Mishary 2:14, Hani 2:38's `12,13,14 — 12,13,14`, Hani
4:163's doubled word 20) survive cleanup. The cleanup runs to a fixpoint
because dropping a spike can reunite a word with its stray sliver (9:51:
`4, [7], 4` → `4, 4`, then merged only if one `4` is a sliver).

When a real repeat is still missed or a false one slips through:

1. **Systematic first.** If the shape is a class (spikes, non-contiguous
   phantoms, false splits), extend `clean_qdc_artifacts` or the CTC repair
   generator and add a unit test under
   [`tools/timing_patch_cases/`](../tools/timing_patch_cases/README.md) whose
   expected output is the Timings Lab / ear-verified fix. Run
   `python3 tools/test_build_db.py`.
2. **Override last.** The per-ayah **Timings Lab override**
   (`tools/timing_overrides/`) replaces an ayah's segments verbatim at build
   time and always wins over the heuristic — use it only when no structural
   rule applies (document why in the file `notes`).

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
  entry or a genuine non-zero seek activation can begin that sweep.
- **The orange blooms from the read (full-ink) colour, not the dim unread one.**
  A repeated word was already recited, so its base ink stays full strength and
  the orange arrives as its **own directional wash on top** — it does not re-run
  the base layer's dim→ink sweep, and it is not a colour tween either. Both
  renderers use the same soft feathered edge as first-pass ink:
  - Per-word units (gloss / English): `Modifier.repeatInkLayer` =
    `glyphLayerAlpha { wash.alpha }` over `letterFadeIn(progress = wash.progress,
    restingAlpha = 0f, feather = Tuning.washFeather)`.
  - Arabic-only Hafs: `ShapedWordBloom.ColorReveal` — re-draw the shaped run,
    `BlendMode.SrcIn`-tint it, then `DstIn`-wash it.

  Timing lives in `rememberRepeatWash`: on chain entry the wash sweeps 0→1 over
  the **active word's own lit lifetime** (`sweepMs`), falling back to
  `Tuning.repeatSweepMs` (450 ms) for a chain member that is not the active
  word; on release, progress pins at 1 and alpha dissolves over
  `Tuning.repeatFadeOutMs` (900 ms). Both are Ink Lab sliders.
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

- **`quran.db` is a committed app asset.** Normal local and CI builds use
  `data/quran.db` directly so they do not depend on the external
  data sources. When repeat timing data changes, update `tools/build_db.py`,
  rebuild the asset with `python3 tools/build_db.py`, and commit the regenerated
  database with the code change.
- **Bump the DB version when content changes.** `QuranDatabase.DB_FILE_NAME`
  (`quran-vN.db`) is the extraction key: the bundled asset is copied to internal
  storage only if that file doesn't already exist. Changing the DB's *content*
  without bumping the suffix means existing installs keep the stale cached copy —
  which is exactly why the orange first "didn't appear." Adding repeats required
  bumping `quran-v5.db` → `quran-v6.db`; the extractor's cleanup step deletes the
  old file. (That pair is the historical example — the asset has been rebumped
  many times since. Read the live value from `QuranDatabase.DB_FILE_NAME`, which
  is `quran-v17.db` as of 2026-07-24, rather than trusting any number here.)
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
2. `python3 tools/build_db.py` and check the printed repeat-span / coverage stats.
3. Bump `QuranDatabase.DB_FILE_NAME` to the next `quran-vN.db`.
4. Rebuild + reinstall; ear-verify a flagged ayah before trusting it.
