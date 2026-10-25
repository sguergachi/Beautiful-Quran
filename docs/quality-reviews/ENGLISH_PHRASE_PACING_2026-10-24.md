# Readable English phrase pacing — 2026-10-24

Status: accepted by the redirected alignment thread's final focused review.
The final 1,698-test Android gate, debug build and R8 release build pass. No
behavioral edits followed acceptance; the limitations below remain explicit.

The user rejected `75278a647` because the opening English phrase raced ahead
and then waited. They explicitly chose readable phrase pacing for reordered
translation sentences. This changes the earlier immediate-meaning deadline
for those phrases, while retaining a continuous soft edge and semantic owners
for taps, holds and repeats. No source timing, database, clock calibration,
font size or printed translation changes are part of this follow-up.

## Why the old checks missed it

Correctly assigning exalting to Arabic position 1 caused the suffix-minimum
projection to consume 13 printed words in Hani 64:1's 1,020–2,140 ms window.
Allah at position 2 then inherited the interval through position 9's onset,
2,140–6,770 ms. Exact owner labels and absence of paint flashes did not
establish faithful word exposure.

The reported clause now spans printed characters 0–73, source positions 1–8,
and media time 1,020–6,770 ms. To Him begins at position 9, 6,770 ms. The
fallback auxiliary assigned to position 9 does not enlarge the opening clause.
The next table records the production plan's complete opening exposure:

| Printed word | Start (ms) | Finish (ms) |
|---|---:|---:|
| Whatever | 1020 | 1650 |
| is | 1728 | 1886 |
| in | 1965 | 2122 |
| the | 2201 | 2437 |
| heavens | 2516 | 3067 |
| and | 3146 | 3383 |
| whatever | 3461 | 4091 |
| is | 4170 | 4328 |
| on | 4406 | 4564 |
| the | 4643 | 4879 |
| earth | 4958 | 5352 |
| is | 5430 | 5588 |
| exalting | 5667 | 6297 |
| Allah | 6376 | 6770 |

These are **phrase presentation** times, not claims that individual English
words coincide with Arabic heads. For example, exalting finishes 4,157 ms after
its Arabic owner's end, Allah 3,620 ms after, and heavens 2,023 ms before.
The opening changes from 13 words/1.12 s to 14 words/5.75 s. Ordinary monotone
stretches keep their word ranges and end with their represented speech.

## Design and guards

`EnglishWordAlignment.align()` retains verified content-anchor provenance before
semantic spans coalesce. `EnglishPhrase` closes the smallest crossing source
intervals, including shared owners. Weak grammar can attach to a neighboring
closed phrase but cannot set its source extent. Weak words below the interval
remain outside it; weak remainders already belonging to it cannot acquire a
second overlapping clock. Absorption begins immediately after the preceding
closed group, including a weak gap left by trimming the next inversion (2:123).
It keeps both the clause and source-owner guards. Word-card English retains
its prior forward ranges and actual linear occurrence windows, including
silence before the next owner. A null phrase plan selects that clock explicitly;
range equality alone would not preserve word-card pacing. The regression checks
source 1 at 0–400 ms and source 2 at 1,000–1,400 ms: the first word-card band
still ends at 1,000 ms and has progress 0.5 at 500 ms. With a 200 ms voice gate,
progress 0.5 and its inverse both occur at 600 ms.

A remaining overlap between adjacent presentation source intervals is an
explicit ambiguity, not permission to absorb weak text across a clause. The
whole verse then retains legacy occurrence bands, their next-owner/repeat
fences and complete voiced source union. This separate source-overlap fallback
handles 2:175's weak How/patient clause without clamping it to the already
spent 6–7 phrase. The occurrence band is 7,510–12,550 ms. Semantic spans remain
untouched. Source-overlap fallback takes precedence over internal-repeat block
fallback and has its own counter.

`EnglishWashPlan` is pure and cached against the whole-verse alignment, text
source, timing snapshot and voice-start gate. Rendering and carried-leaf follow
read the same plan. A window uses the union of spoken intervals: gaps hold the
edge and overlap counts once. Forward progress and inverse exposure use the
same voice gate. Fragments only project the whole-verse edge.

Normal bands finish with their own represented speech. Terminal residuals keep
the established verse-tail completion rule. A proposed merged block with an
internal backward repeat conservatively retains legacy occurrence fences;
it does not borrow the later utterance to lengthen its first wash. The 24:1
repeat and 24:2 terminal-tail/carried-cut controls remain separate regressions.

Material freshness follows each printed exposure window rather than expiring
with a phrase's first Arabic motion. Settled ink cannot re-wet on resume.
First-pass glimmer follows the presentation edge and ending motion's dry-down;
semantic repeat tint stays clipped to laid ink. A cosmetic 850 ms departure
releases untouched old words after a same-phrase or same-word rewind. Three
rapid generations inherit the currently visible opacity, rather than
rearming an evaporating coat. This cosmetic release never moves the audio clock.

## Validation and remaining limits

The final Android gate passes 1,698 JVM tests (zero failures/errors, one existing
skip), including all 16 phrase-pacing tests and the exported native trace replay.
The debug and R8 release builds also pass. New regressions cover all 17 real
Hani segments and each of the 14
printed opening words; lower leading grammar; weak remainders; shared-owner
and cross-clause closure; ordinary and skipped-owner windows; silence and
source overlap; internal/same-word repeats; the 24:1 repeat; 24:2 terminal and
carried cut; speed, pause, seek and reciter frames; wetness/cooling; delayed
voice gates; monotone departure through three generations; real 2:123 and
2:175 inputs; and source-overlap fallback with voiced gaps, onset gating and
a backward occurrence fence.

The source-onset-gated corpus run covers 37,411 cached timing rows, 6,236
verses and six reciters: app IDs 1/2/3/4/5/7 with 6,235/6,236/6,236/6,236/6,232/
6,236 available rows. It does not cover all 13 voices; five absent source rows
remain absent. The input corpus SHA-256 is
`6bcfa75c289458d04b1455bfe64f23469219319eac92218dda37ee715af0a566`.
The run verifies tiling, monotone edges, nonnegative exposure and ordered
windows, including 3,778,511 sampled forward edges. Its 15,599 proposed
reordered block instances, 492 source-overlap fallback rows across 82 verses,
and 69 internal-repeat fallback blocks are presentation inventory, not semantic
certification. The source-overlap fallback deliberately retains the old policy
for this small ambiguous subset; later reviewed semantic maps may refine it.

The run also records early/late exposure against every inferred semantic
owner: 512,814 words finish more than 100 ms before that owner's end and
70,707 more than 100 ms after. This includes weak grammar and intentionally
reordered presentation; those counts are **not accuracy scores**.

The original whitespace-token diagnostic allowed 1,925 zero-duration rows
across 143 verses. The independent review tightened this to actual letter-core
ranges wholly contained in a band, using
`\p{L}[\p{L}\p{M}]*(?:['’][\p{L}\p{M}]+)*`. Its pre-fix snapshot had
1,773 collapsed cores; 1,372 had positive exposure under exact legacy
`englishWashWindows` (1,369 ordinary and three repeat controls). Fixing the
trimmed weak gap alone left 1,066 collapsed cores. The combined gap fix and
explicit source-overlap fallback leave:

| Final diagnostic | Count |
|---|---:|
| Contained letter cores examined | 948,253 |
| Letter cores crossing a presentation/legacy band boundary | 0 |
| Collapsed letter cores | 401 |
| New collapses with positive exact legacy exposure | **0** |
| Collapsed cores classified as legacy source-order skips | 401 |
| Collapsed repeat or other legacy controls | 0 |
| Collapsed raw whitespace tokens | 405 |

The 401 residual cores span 34 verses, on app reciters 1/3/7 (10/232/159
cores respectively). Every residual core's requested owner exists in the timing row, but the first
timing at or beyond it is a later owner; the requested owner occurs only
later. Each core also had zero exposure under the exact old window function.
These source-order skips remain explicitly unresolved at baseline. The script
fails on any newly collapsed positive-legacy core and records the classifications
separately. The 1,925/405 whitespace counts and 1,773/401 letter-core counts
have different tokenization and must not be mixed. Passing these checks does
not certify ordinary-word readability or semantic alignment across the corpus.

Reproduce with an authorized local runtime corpus/cache:

```sh
python3 tools/audit_english_phrase_pacing.py "$CORPUS_TSV" "$TIMINGS_CACHE" \
  --output-dir "$AUDIT_DIR"
```

The tool compiles the actual legacy window function alongside the production
phrase plan. It writes all whitespace-word exposures, per-verse plan counts,
collapsed-core classifications, crossing-core inventory, timing revisions and
source fingerprints locally. Its TSV is unquoted; parse with
`line.split('\t')` or `csv.QUOTE_NONE`. Raw provider data, video and playback
exports stay outside Git. Pixel checks and production-clock replay are separate
from the ownership and exposure audit. Emulator results do not establish
physical speaker/Bluetooth audio synchrony or corpus-wide semantic accuracy.

## Independent review and native evidence

The requested review thread identified two additional edge cases, now covered
by regressions. Voice-start gating is applied once to the cached plan, and
`mediaAt` also supports the same explicit gate as forward progress. This keeps
printed finish times/material cooling aligned with a delayed first onset.
Rapid consecutive rewinds inherit each still-departing word's current opacity;
a second or third generation cannot reset the old coat to full ink.

The native API 35 emulator captured Hani 64:1 and natural transitions. Across
29 fixed stroke regions in the first three English lines at 20 samples/s,
excluding the first three seconds of explicit restart, the 45-second capture
has zero abrupt lightening events over 25 gray levels (largest increase 10.58).
The reviewed 35-second capture has zero such events (largest increase 0.03).
The earlier rejected paint baseline had eight, up to 111.59 levels. The
45-second capture's log includes 336 field decisions, 142 using the shader,
so field/fallback decisions remain independently observable.

The reviewed seek recording performs three taps on the same English word,
verified as Arabic position 3 in the log, approximately 0.45–0.49 seconds apart.
An untouched word ahead of all three new edges fades monotonically from mean
stroke gray 36.8 to 187.2; its largest downward sample step during release is
0.01, rather than a resurrection of full ink. AndroidRuntime records no crash.
The exported sample/frame replay passes the production replay test. These are
separate paint/transport checks; they do not certify semantic timing or resolve
the retained source-order skips above. The redirected reviewer accepted this
native evidence without requesting duplicate recordings.

The independent acceptance checked the final production digests, full JVM
results, word-card linear/gated clock, weak-gap fix, explicit source-overlap
fallback, shared rendering/follow plan and inverse, and seek-opacity wiring.
It confirmed zero new positive-legacy core collapses and retained the 401
baseline source-order skips as a stated limit. Publication was held until the
final R8 release succeeded. The private coordination acceptance record remains
outside Git with the runtime input evidence.
