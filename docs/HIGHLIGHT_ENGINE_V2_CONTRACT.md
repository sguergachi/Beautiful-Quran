# HighlightEngine V2 runtime contract

V2 supplies visible synchronization on Android and web. It reconciles playback
facts and projects one media clock; the existing lookup, English ownership,
tajweed curves, soft masks, typography, and acoustic glimmer remain separate
policies. See the [goals and work packages](HIGHLIGHT_ENGINE_V2.md).

## Ownership

Android's `PlayerController.highlightSample` checks the published and live item
before and after one position read. `readerPlaybackPolls` supplies that coherent
packet to word, voice, basmalah, and follow consumers. Transport, speed, ink/clock
revision, and timing publication changes interrupt the paused polling sleep.
Incoherent reads are skipped without publishing an empty word.

`HighlightSession(HighlightEngineVersion.VISIBLE)` advances the live pure
`HighlightSync` state once per packet. `HighlightTimeline` projects that state
on display frames. The reader root never reads frame progress. Word snapshots
publish only on occurrence/metadata changes; completed residuals release their
frame subscription at the next word boundary. Only repeat completion's Boolean
is observed in composition, with structural equality.

`HighlightMediaMotions` captures a `HighlightWashWindow`, pacing, feather, and
wasl carry at entry. Progress is a function of calibrated media time rather
than elapsed animation time. The existing minimum sweep floor is retained:
its wall duration is converted to media duration once at entry speed. A later
speed change changes the shared clock slope. Repeat occurrences use measured
dwell, have independent windows, and cannot wait behind a repeat queue.
Prose can gate on its predecessor without shifting its own window into the
future. Cosmetic recess/glimmer/tint dissolution still use their existing fades.

`EnglishWashClock` is metadata projected through that same frame. It owns no
second interpolation anchor and cannot ignore ordinary clock corrections.
English band windows, meaning owners, continuous prefixes, carried fragments,
repeat location, and follow lead retain their existing policies.

Web uses the equivalent `HighlightSync.ts`, the shared replay fixtures, and a
single `highlightTimeline` read by DOM mask callbacks. React receives boundary
updates. Browser play intent is distinct from actual transport advancement:
waiting for a source, buffering, a paused/seeking element, or an unstarted
Gapless-5 backend cannot advance V2. Explicit seek, playlist, native-loop seeking,
and backend index events supply revisions. Existing preview/cosmetic washes
without a playback window retain their animation clock.

## Identities and transitions

| Input | Behavior |
|---|---|
| New item or reciter | New performance; no inherited predecessor ink |
| Increased ink generation | Explicit restart/seek/loop may reset ink backward |
| Increased clock revision, same ink generation | Accept landing and retain laid ink, including silence skips |
| Older timestamp or lower clock/ink/timing revision | Reject stale callback, including another item |
| Timing publication / withdrawal | Atomically replace lookup / expose missing timings on a coherent sample, including while paused |
| Ordinary backward correction | Hold laid ink and project the corrected raw series until it catches up |
| Pause / buffering | Freeze media projection; existing surface recess/reading policy remains in charge of presentation |
| Resume | Rearm trust settling without resetting ink; frozen samples cannot prove convergence |
| Speed change | Prior speed owns the preceding interval, new speed owns subsequent frames |
| Calibration change | Project media first, then recalibrate; retain laid ink in the same performance |
| Observer suspension | Stop extrapolation; first fresh sample rebases after the unobserved interval |
| Provisional tap | Hold its wash at zero until a matching word with a current player generation acknowledges it |
| Consecutive same-word re-say | Distinct orange occurrence; ordinary base ink is not restarted |

Revisions are cumulative within a player/controller lifetime. A new controller
requires a new sync session. Negative monotonic timestamp origins are valid.
Timing publication, provider generation, reader navigation, clock revision,
performance generation, and occurrence identity remain distinct.

## Numerical policy

- Integer sample/frame clocks compare exactly; fractional frame conversion
  permits 1 ms rounding and progress tolerance is `0.000001`.
- Projection is continuous at 60/90/120 Hz and has no next-poll deadline.
- Legitimate first polls delayed 250 ms, repeated 110 ms polls, and 1.5×
  playback sampled every 50 ms add zero sample-clock debt.
- Within a performance, corrections create zero backward ink steps and zero
  new ink generations. Projection starts from the corrected raw series rather
  than adding elapsed time to the retained ceiling.
- Settling lasts at least 400 ms and at most 1,500 ms. Four believable samples
  permit convergence after the minimum. Maximum raw step is
  `max(100 ms, 3 × elapsed media time)`; accepted catch-up is
  `max(66 ms, 2 × elapsed media time)`, using prior transport/speed.
- Manual output lag is wall time, speed-scaled once. Word lead is media time.
  The existing net clamp and voice-opening ramp follow media projection.
  Media3's presentation clock receives no guessed Bluetooth preset.

A smoothly advancing but wrong player estimate cannot be detected from
position alone. Retained ink cannot undo a future word already revealed.
These are deterministic recovery rules, not a physical latency guarantee.

## Preserved fences and intentional changes

| Historical policy | Replacement / evidence |
|---|---|
| #527 / `271f0846` post-seek overshoot | Elapsed-aware guard, exact spike/correction fixtures |
| #673 / `02a60404` creeping estimates | Minimum/convergence/cap guard; frozen/resume regressions |
| Large correction is not a seek | Only authoritative revisions reset a performance |
| #859 / `bfa0e76b` continuous English motion | One uncapped frame clock; shared transport/frame fixtures; ordinary samples now feed corrections |
| #569 / `8eb5d108` short-word floor | Captured media window retains floor, feather, pacing, and independent residual |
| #631 / `a800db9e` one motion owner | `rememberInkMotions` remains shared by scroll/mushaf; replaced wall-time speech owners removed |
| Shuraym 7:146 short repeat chain | Independent occurrence windows; no queued debt; existing repeat fixtures retained |
| Tap seed and page carry | Existing protocol retained; stale same-word polls cannot acknowledge a newer tap; manual seeks clear pending silence marks |

V1 `HighlightClock` remains a diagnostic/replay comparator. It does not select
visible words. Reverting the migration requires reverting the renderer and
adapter changes together; changing a version label is not a rollback switch.

## Local capture and replay

Developer → **Record performance profile** explicitly records a bounded local
report. Sharing remains an explicit user action. Normal use records no trace,
uploads no samples, and introduces no analytics.

Trace schema 2 (`sample-and-frame-clock`) records source SHA/dirty flag, app
version, actual visible engine, exact timing triples/voice gates and SHA-256,
ordered playback/calibration/revision samples, V1 checkpoint, live V2 checkpoint,
and display frames referring to their actual accepted sample. A mid-verse
capture preserves live V2 rather than resetting it. Storage keeps a prefix of
1,024 samples and 2,048 frames, with separate dropped counters. Schema 1 sample
captures remain replayable.

```sh
./gradlew testDebugUnitTest --tests '*HighlightReplayTest' \
  -PhighlightTrace=/absolute/path/to/profile.txt
./gradlew testDebugUnitTest --tests '*HighlightCorpusTest' \
  -PhighlightCorpus=/absolute/path/to/runtime-timings.tsv
```

Both external files are declared Gradle inputs. Export the second using
`audit_reader_sync.py --v2-output`, supplying the frozen English corpus,
ownership TSV, and all six reviewed `--runtime-pack` inputs. Packs and complete
local traces stay outside Git. Ordinary CI uses sanitized shared fixtures and
seeded sequences, requiring neither network nor provider content.

## Validation and limits

The read-only delivered-corpus run covered **81,059 rows / 13 reciters**, with
**12,378,291 V2 boundary/reverse/scrambled queries** and **17,748 repeat
occurrences**. The existing Arabic/English continuous-prefix oracle passed
**12,384,384 queries per English view** over the same snapshot (published prose
and word-card text). Nine unavailable rows remain
explicitly unavailable. No database or timing payload changed.

A release emulator capture of the integrated engine replayed **158 samples and
390 display frames**, with zero dropped records. Its header identifies engine
2, schema 2, and dirty source honestly. Local evidence lives under
`~/.t3/artifacts/Beautiful-Quran/timing-audit/t3-112e60be/v2/`.

The earlier baseline capture (`98bb2766`) reproduced all 284 samples; its largest
V1 clock debt was 143 ms under profiling. Sanitized irregular delays are pinned
as intentional elapsed-aware differences. The integrated capture also covers
actual display-clock projection, but does not certify glyph pixels, acoustic
boundaries, or sound arrival at a headset.

Matched 15-second Android release playback runs on the same emulator, Alafasy
24:2, Arabic scroll, tracing and recording disabled, had zero reported janky
frames and p95 8 ms for both V1 and V2 (p99 19 vs 18 ms). This migration
measurement is local host evidence; it is not a physical-device frame guarantee.

Physical Pixel speaker/Bluetooth listening and measured output error remain
release acceptance work. Emulator traces and internal recording cannot replace
that evidence. No zero-regression or semantic-accuracy guarantee is claimed.

Full local gate totals and methods are recorded in the [validation report](quality-reviews/HIGHLIGHT_ENGINE_V2_2026-10-18.md).
