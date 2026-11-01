# English literal word finishes — 2026-10-25

Status: **accepted for publication** after independent read-only review on
2026-10-25. Implementation, numerical audit, JVM/debug/R8 gates, native visible
endpoint checks and both production trace replays pass. The clean committed-source
release rebuild and named raw APK delivery follow acceptance.

The user rejected `fa8e04cde` as still start/go and clarified the priority:
when an English word reliably maps to the word being recited, finish its ink
with the reciter, preferably 100 ms earlier. Whole-verse uniform distance
cannot ensure those endpoints. The earlier visible interword-idle reduction
was not evidence that the English words finished with their Arabic owners.
Word-by-word Mushaf remains locked.

## Endpoint admission and one continuous pace

`EnglishAlignment.timingWords` preserves literal-token provenance before owner
spans coalesce. A candidate requires the already verified final owner and an
identical literal in that owner's gloss. Added text, weak grammar, synonyms
and matching substrings inside a larger printed token cannot set a deadline.
No owner, matcher, translation, source timing or calibration algorithm changes.

The coordinate chain is cumulative audible fraction → `EnglishReadingPace`
→ existing ink/blank extent → printed offset. Drawing, material cooling,
printed exposure and carried-leaf follow use the same cached plan. Inversion
reverses exactly that chain. Speed, pause, buffering, seek and reciter resets
stay on the shared V2 media frame; there is no second live clock.

Compatible word ends are joined with a monotone cubic and positive interior
velocity. They do not receive independent easing-to-rest clocks. The target
is 100 **media** ms before the source end: 100 wall ms at 1x, 50 at 2x. It is
a presentation preference, not an output/audio calibration. If that target is
inside quiet, before the gate, or infeasible against existing endpoints, try
the last available voiced end once. Source end, voiced end, desired target,
effective target and relaxation reason are retained separately.

Multiple English tokens expanded from one source unit use its final printed
endpoint once. Repeated/ambiguous literals, crossing source ends, identical
audible coordinates and missing/empty source windows cannot become epsilon-
separated knots. Admission is deterministic and greedy in printed order,
not a maximal-coverage optimizer.

Fixed identity knots and unit tangents preserve the current flow through every
complete rendered word unit in a closed inversion and the entire terminal
residual. Protection expands across punctuation and merges touching ranges.
An outside match cannot compress those passages or leave only punctuation
moving through the 24:2 tail. The first-voice zero and terminal one remain hard
bounds. With no admitted matches, the warp is identity on the current flow.

An initial positive-only draft produced ten new collapsed cores and a peak
5,804-times relative velocity. That draft was not published. Admission now
requires derivative extrema within **.25–4 times** the current per-verse unit
rate, retrying the voiced end before deferring a conflict. This is a conservative
heuristic, not a readability score. It still permits a 16-fold velocity
contrast; bounded/positive derivative alone does not prove perceptual steadiness.
The shader, one-letter lead, fallback feather, entry floor, typography, cards,
Scroll, repeats and source-overlap fallback policies are unchanged.

## Numerical coverage and limitations

The current full pass covers 37,411 timing rows, 6,236 verses and six reciters.
Counts below are **reciter-row candidate events**, not distinct corpus words
or semantic accuracy scores.

| Candidate outcome in 33,602 safe reading rows | Events |
|---|---:|
| Literal candidates | 202,231 |
| Admitted endpoint | 134,054 |
| Preferred 100 ms lead | 132,511 |
| Quiet relaxation | 47 |
| Velocity relaxation | 1,482 |
| Voice-gate relaxation | 14 |
| Deferred total | 68,177 |
| Same-source interior endpoint | 10,078 |
| Terminal policy | 17,777 |
| Reordered phrase | 23,437 |
| Crossing target | 5,175 |
| Protected full-unit range | 3,708 |
| Nonpositive derivative | 811 |
| Outside heuristic pace range | 7,191 |

The final admitted derivative extrema span .2500116–3.9999343 relative to the
prior unit rate. The complete audit passes 679,710 shared-projection checks and
13,669 quiet plateau/inverse checks. Source-overlap and repeat fallback counts
remain 492 and 3,317, with 69 internal-repeat blocks. Of 948,253 contained letter
cores, zero newly collapse relative to exact legacy windows. The 401 baseline
source-order skips remain unresolved; the separate raw whitespace denominator
has 405 collapses. Only Hani has measured within-word quiet in this path; other
voices use timing-gap evidence. No new audio evidence or timing data is created.

For every admitted target, the audit records the inverse/cache exposure end
and independently checks the **value** of `englishWashThrough` through the
production `englishPaperInkWordProgress`. A binary search of forward progress
finds the first tolerance-complete integer media millisecond independently of
the inverse's exact-knot shortcut. Both maximum target errors are **0 ms**.

The chosen numerical allowance is four ULPs of the normalized target, converted
to characters and then word phase. The same formula applies to every row.
Maximum allowed shortfall is **.00031781197 characters**, and minimum actual
phase at the target is **.9999878**. These are tolerance-complete checks, not
claims that every Float branch equals exactly one or a formal bound on every
composed operation. A fixed .99999 phase threshold had rejected a long 2:282
projection with this tiny offset rounding. Visible glyph timing is a separate
measurement below.

The full JVM gate passes **1,727 tests, zero failures/errors, one existing skip**
in 193 preserved suites. Debug and exact-source R8 release builds pass. New
controls cover literal/whole-unit provenance, word deadlines, positive interior
motion, shared-owner expansion, short/gated words, quiet/overlapping targets,
no-anchor identity, protected inversions, 24:2 whole-tail cores/punctuation and
200 varied paced quiet/inverse timelines. Existing repeat, source-overlap,
word-card, speed/seek/reciter and carried-leaf controls remain.

## Targeted native checks

Two 30-second captures show the actual Translation Mushaf with Hani, Junicode
and the current dirty source. Both exports replay through production filters;
there are zero dropped trace samples/frames and no app runtime exception.
The chosen controls include preferred leads and relaxed finishes:

| Verse / literal | Desired ms | Effective ms | Source end ms | Voiced end ms |
|---|---:|---:|---:|---:|
| 64:1 dominion, | 7,960 | 7,960 | 8,060 | 8,060 |
| 64:1 praise, | 9,630 | 9,442 | 9,730 | 9,442 |
| 64:11 except | 3,880 | 3,880 | 3,980 | 3,980 |
| 64:11 permission | 4,710 | 4,810 | 4,810 | 4,810 |
| 64:11 Allah. | 5,950 | 5,950 | 6,050 | 6,050 |
| 64:11 believes | 8,050 | 8,050 | 8,150 | 8,150 |
| 64:11 Allah | 9,070 | 9,070 | 9,170 | 9,170 |
| 64:11 heart. | 10,770 | 10,770 | 10,870 | 10,870 |

The capture fixture adds a small header timestamp from Android's Choreographer
frame time, leaving the text layout and release code untouched. Each original
video frame is paired with ffprobe's `best_effort_timestamp_time`, without an fps
filter and with ffmpeg passthrough. Timestamp digits are decoded against fixed
monospace templates and matched to the selected verse's exported frame `atNanos` (322 and 350 frames); maximum match
difference is .813 ms (the overlay truncates to integer milliseconds). The videos
contain 669 and 697 original frames, approximately 22.1 and 23.1 frames/second.
They do not provide independent 60 fps samples.

Fixed masks cover the deep body of the **last letter**, excluding antialiasing
and trailing punctuation. Each pixel uses its own unread reference at ink time
1,100–1,900 ms and fully laid reference at effective target +300–800 ms. The
completion diagnostic requires the tenth percentile of normalized pixel coverage
to reach .95, sustained into the next captured frame. Thresholds .90/.95/.99 gave
the same crossing frame for all eight controls. This is a glyph-core diagnostic,
not proof that every edge pixel has reached its final material color.

| Verse / literal | Last-letter core crossing bracket, ink ms | First qualifying frame minus target | Paint route |
|---|---:|---:|---|
| 64:1 dominion, | 7,864–7,941 | −19 ms | fallback |
| 64:1 praise, | 9,236–9,293 | −149 ms | fallback |
| 64:11 except | 3,722–3,759 | −121 ms | shader |
| 64:11 permission | 4,756–4,789 | −21 ms | fallback |
| 64:11 Allah. | 5,672–5,710 | −240 ms | shader |
| 64:11 believes | 7,930–7,956 | −94 ms | shader |
| 64:11 Allah | 8,973–9,007 | −63 ms | shader |
| 64:11 heart. | 10,541–10,615 | −155 ms | fallback |

All selected last-letter cores are visibly laid by their target, with adjacent
source-frame intervals of 33–81 ms. The soft edge can finish a letter before the
whole printed unit, particularly when punctuation follows it; these measurements
do **not** claim that visible glyph completion is exactly 100 ms early. Native
endpoint crops retain the actual frame PTS and exported ink time. On the 64:11
shader and fallback crops, partial last-letter ink develops into fully laid ink
before the source end. The 12 recorded 64:11 passage checkpoints advance the sampled region's visible
body coverage from .106 at ink 2,009 ms to .931 at 13,012 ms, including progress
between the admitted endpoints. This supports continued passage motion; sparse
checkpoints and analytic derivative bounds do not certify perceptually uniform
motion at every transition or on every device.

The fresh emulator initially had blank content and an Android system profiling
service crash. Neither is counted as a pacing pass. A private instrumentation
fixture seeds the previously audited content/timing snapshots, refreshes only
local cache-freshness metadata, warms the retained words and adds the timestamp
header. No source segment or production file is edited by that fixture. Playback
is warmed until real text is visible before the accepted captures. Device actions
and recording use the returned agent-device launcher; the temporary device service
and pool lease are cleaned up afterward.

Native source media, calibrated presentation `inkMs`, heard position and video
PTS remain separate coordinates. Matching the overlay to exported frames tests
rendered completion against the app's own presentation clock. Exports contain no
microphone audio, so these checks cannot certify physical speaker/Bluetooth
synchronization, semantic accuracy or universal readability. Only two Hani verses
are visually sampled; the corpus endpoint checks remain numerical coverage.

## Local evidence and reproduction

```sh
python3 tools/audit_english_phrase_pacing.py "$CORPUS_TSV" "$TIMING_CACHE" \
  --output-dir "$AUDIT_DIR"
```

Private evidence remains outside Git:

- `~/.cache/bq-mushaf-report/word-finish-audit/`: `summary.json`,
  `word-finishes.tsv`, `pace-diagnostics.tsv`, exposure and collapse inventories;
  current production, timing and present/absent silence fingerprints.
- `/tmp/bq-word-finish-audit.log`, `/tmp/bq-word-finish-full.log`,
  `/tmp/bq-word-finish-full-tests`, `/tmp/bq-word-finish-release.log`.
- `/tmp/bq-word-finish-hani64-{1,11}-clock.mp4`, corresponding `-native.txt`
  exports and crash logs; `-frames.npz` and `-clock-match.npz` preserve original
  PTS, decoded frame times and matching exported ink times.
- `/tmp/bq-word-finish-{decode,metrics}.py`, `-visible-results.json`,
  `-visible-motion.json`, per-capture masks and target-relative endpoint crops.
- `/tmp/bq-word-finish-replay{1,11}.log`: both production replays pass.
- `/tmp/bq-word-finish-fixture/`: private instrumentation source and APK; raw
  content, recordings and fixture artifacts are excluded from Git.
