# Steady English reading flow — 2026-10-25

Status: accepted for publication after independent final review of the source
and silence fingerprints, full tests/audit, native render/transport evidence,
production replay and exact-source R8 release. The limits below remain explicit.

Follow-up: the user saw no noticeable improvement in this build and confirmed
Translation Mushaf; word-by-word Mushaf already felt good. The clock checks
below remain valid, but do not establish visible continuity between glyphs.
The [visible-transition follow-up](ENGLISH_TRANSLATION_TRANSITIONS_2026-10-25.md)
records the subsequent blank-distance/fallback-feather experiment and actual
same-layout measurements. Its visible-character distance replaces the
whitespace-inclusive distance described here for safe translation runs.

The user rejected `08c813c96` as too much stop/go. They want constant paced
movement through connected recitation and holds when the reciter stops. This
supersedes independent per-band velocities for published English translation.
Word-card timing stays locked. Semantic owners, source timings, output
calibration, typography and translation wording do not change.

## Clock and integration

`EnglishPhrase` and the reviewed occurrence windows remain semantic/presentation
anchors and conservative fallbacks. They partition the page's geometry. A safe
forward verse now has one `EnglishReadingFlow`: union **all** source speech,
including owners without their own English band, clip to the first-voice and
terminal fences, then subtract the normalized union of measured quiet once.
The printed prefix advances linearly with cumulative audible time.

A word boundary, Arabic-owner change or English punctuation does not restart
speed. Source gaps and measured quiet freeze the exact fractional prefix already
reached, including within a reordered phrase. The edge resumes there at the
same rate. Quiet alone does not establish an English lexical boundary, so the
clock never snaps a pause onto a word or meaning.

`EnglishReadingWindow` projects that single clock into each existing span.
Rendering, word exposure, printed finish times, paper freshness and carried-leaf
follow use the same mapping. The plan is cached against alignment, timing
snapshot, first-voice gate, silence evidence and reciter. Silence belongs to the
actual audio ayah; playlist ayah 0 maps to the shared Al-Fatihah 1:1 clip.
Speed, pause, buffering and seeks remain on the calibrated V2 media clock.

Forward drawing ignores rounded inverse metadata as a phase origin. Inverse
calculations use Double arithmetic, then integer-media-ms quantization before
selecting the earliest finish or latest start of a pause. This prevents Float
rounding from choosing the opposite side of a long silence. A later explicit
voice gate retains the same finite forward/inverse contract.

Word-card alignments (`phrases == null`) retain their exact legacy linear
windows, including silence before the next owner. Source-overlap verses and
backward/same-word source repeats retain the reviewed occurrence policy.
Unusable source windows and zero audible duration have explicit fallback
reasons; no zero-duration reading clock is installed. The 24:1 repeat and 24:2
terminal-tail/carried-leaf controls remain separate anchor regressions.

## Measured quiet and limits

The existing Hani asset contains quiet inside word timing intervals, including
64:1 at 9,442–9,813 ms. Its contiguous word row alone cannot identify that pause.
The new reading clock holds throughout those 371 ms. All other voices currently
have only timing-gap evidence in this path; their silence assets are absent.
This change does not generate new acoustic data or claim every pause is known.

For Hani 64:1, 1,020–14,625 ms contains 13,234 ms of known audible time after
subtracting the measured pause. The 168-character translation therefore moves
at one rate, about 12.69 printed character slots per audible second. Letter-core
Allah still finishes around 6,770 ms; subsequent ordinary bands share the same
rate instead of restarting at their Arabic owners' deadlines. The 9,442 ms
hold is fractional and does not claim a semantic word match.

This is readable presentation, not individual English/Arabic word synchrony.
The revised inventory has 490,639 whitespace words finishing more than 100 ms
before their inferred semantic owner's end, and 317,357 more than 100 ms after.
Those counts include weak owners and intentional reordered presentation; they
are not accuracy scores.

## Validation

The final full Android gate passes 1,709 JVM tests, zero failures/errors and
one existing skip, plus the debug build. Regressions cover constant positive
velocity across unequal Arabic bands, swallowed source owners, source overlap
union, clipped/overlapping quiet, silence strictly inside an inversion, the real
Hani pause, inverse plateaus, explicit late gates, locked word-card pacing and
preserved occurrence fallbacks. A geometry boundary at .39999 beside a .4
plateau verifies that rounded metadata cannot move or gate the runtime edge.
The existing V2 speed/pause/seek/reciter test uses the live reading plan.

The runtime corpus audit covers 37,411 available timing rows, 6,236 verses and
six voices, with the same five absent source rows as the earlier review:

| Check | Result |
|---|---:|
| Steady reading rows | 33,602 |
| Source-overlap fallback rows / verses | 492 / 82 |
| Source-repeat fallback rows | 3,317 |
| Existing internal-repeat fallback blocks | 69 |
| Equal-rate boundary checks | 633,618 |
| Quiet plateau and inverse checks | 13,669 |
| Contained letter cores | 948,253 |
| New collapses with positive exact legacy exposure | 0 |
| Existing source-order core collapses | 401 |
| Raw whitespace-token collapses | 405 |

Every residual core collapse was already zero under exact legacy windows and
remains classified as a source-order skip. These are unresolved at baseline.
The audit retains separate fallback reasons and source/timing/silence hashes;
raw corpora and evidence stay local. It also checks tiling, monotone forward
edges and ordered windows. Neither these checks nor the independent review
certifies corpus-wide readability, semantic accuracy or physical output sync.

Reproduce with the authorized local runtime inputs:

```sh
python3 tools/audit_english_phrase_pacing.py "$CORPUS_TSV" "$TIMINGS_CACHE" \
  --output-dir "$AUDIT_DIR"
```

The independent reviewer checked source union/quiet subtraction, mode isolation,
actual-audio identity, cache keys and shared paint/follow wiring. Seed 878's
10,000 non-binary plateau inversions have zero wrong-side selections or ms
errors. Across 200 random safe timelines, the current runtime edge differs
from the shared global clock by at most 1.49e-8 normalized, with zero retreats.
These are numerical checks, not perceptual acceptance.

## Native and release checks

The current debug reader was exercised on the API 35 emulator in published
English translation Mushaf with Hani. A 35-second recording shows the soft
prefix progressing through 64:1 and natural subsequent verses. The exported
log spans playlist 0 and verses 1–4, with 802 samples, 2,131 frames and zero
dropped samples/frames. It replays through the production filters successfully.
There are 704 captured frames for 64:1, including 19 at ink times
9,466–9,811 ms inside the known quiet. Field decisions include both rendering
paths (49 shader decisions of 134). AndroidRuntime records no crash.

The page initially showed an empty text map after fresh emulator/cache setup;
refreshing the reader from Mushaf to Scroll and back rendered the published
text before recording. The blank environment was not counted as a pacing pass,
and no product change was made for it. Video and cropped contact sheets were
inspected locally. These are render/transport checks, not physical acoustic
speaker/Bluetooth synchronization or a quantitative visual velocity score.

The exact final source passes the R8 release build. Native files, runtime
corpora, caches, logs and private acceptance evidence remain outside Git.
