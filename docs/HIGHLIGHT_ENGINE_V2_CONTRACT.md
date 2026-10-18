# HighlightEngine V2: initial executable contract

Status: **candidate core and sample-clock replay implemented; V1 still draws**.
This is the first implementation slice of [the V2 plan](HIGHLIGHT_ENGINE_V2.md).
It is not completion of the rendering migration or permission to cut over.

## Implemented boundary

`HighlightPlaybackSample` carries one item, raw file position, monotonic sample
timestamp, speed, playing/buffering flags, and cumulative clock/ink revisions.
`PlayerController.highlightSample` checks the published and live item before
and after the read. An incoherent read is skipped without publishing a null
word. Domain timestamps may have a negative origin; only their ordering and
elapsed intervals matter.

`HighlightTimingContext` identifies an immutable prepared timing publication
and its first-voice gate. The reader increments its local publication revision
on each install, including withdrawal. It remains distinct from provider
generation and navigation generation.

`HighlightSession` owns the production V1 filter and optionally evaluates V2
against those exact inputs. The normal path uses V1's existing calibration,
tap latch, discontinuity rearm, and prepared lookup. It does no V2 projection
or trace hashing when a developer capture is not active. Word snapshots still
publish only on change through the existing cache/flow.

`HighlightSync.sample(previous, sample, timing, calibration)` and
`HighlightSync.frame(state, frameNanos)` are the candidate pure core. Neither
acquires time, reads Android state, starts playback, or schedules a coroutine.
Frames expose heard and ink positions, occurrence identity, linear progress,
and an explicit phase. Letter pacing, English ownership, masks, and page
geometry have not moved into this core.

## Identity and transition rules

| Input change | Candidate behavior |
|---|---|
| Different surah, ayah, or reciter | Start a new item; do not inherit its predecessor's ink |
| Increased ink generation | Start a new performance, allowing a backward reset |
| Increased clock revision with the same ink generation | Accept the authoritative media landing, retain laid ink |
| Lower clock/ink revision, lower timing publication, or older timestamp | Reject the stale input, including callbacks naming a different item |
| New timing publication | Replace occurrence lookup atomically on the next coherent sample |
| Empty timing publication | Expose missing timings, never the preceding occurrence |
| Ordinary backward position correction | Hold laid ink; project the corrected raw series until it catches up |
| Pause or buffering | Freeze projection from that sample |
| Resume after pause or buffering | Restart the trust-settle window without restarting ink; frozen samples do not prove realtime convergence |
| Speed change | Previous speed owns the preceding interval; new speed owns subsequent frames |
| Calibration change | Recompute after media projection; retain ink within the same performance |

Clock/ink revisions are cumulative within a `PlayerController` lifetime. A new
controller/session must start a new `HighlightSession`; resetting counters on
an existing candidate state would be rejected as stale.

The provisional V1 word-tap latch remains an explicit input to the baseline
driver. V2 does not treat that provisional tap as an acknowledgment: its
transport/seed integration remains a WP4 gate. This is recorded in traces so
it cannot disappear into an unexplained comparison mismatch.

## Initial numerical contract

These values are pinned before renderer cutover and tested in
`HighlightSyncTest` and `HighlightReplayTest`:

- Sample-clock comparisons use exact integer milliseconds. A frame schedule's
  fractional conversion permits at most 1 ms rounding; progress comparisons
  use an absolute tolerance of `0.000001`.
- Ideal 60/90/120 Hz schedules must advance every frame during an active,
  unclamped one-second span. Projection has no next-poll deadline.
- A legitimate delayed first poll at 250 ms, repeated 110 ms polls, and 1.5×
  playback sampled every 50 ms incur **zero additional sample-clock debt**.
- Within a performance, a correction causes **zero backward ink steps** and
  **zero new ink generations**. Projection after a correction starts from the
  raw series rather than adding elapsed time to its retained ceiling.
- The candidate settle minimum is 400 ms and its maximum is 1,500 ms. Four
  consecutive believable samples permit convergence after the minimum.
  During settle, the maximum raw step is `max(100 ms, 3 × elapsed media time)`;
  accepted catch-up is `max(66 ms, 2 × elapsed media time)`. Elapsed media time
  uses the preceding sample's transport and speed. These preserve the original
  guard's nominal thresholds while accommodating legitimate delayed polls.
- Output lag is wall time, speed-scaled once. Word lead is media time. Project
  media first, then use the existing net clamp and opening-ramp transform.

This guard cannot identify a smoothly advancing but incorrect player estimate
without additional evidence. Retaining ink prevents replay after a correction;
it cannot undo future ink already revealed. Physical-output error budgets,
stale-source trust policy, short-word residual completion, and release
performance budgets remain unresolved cutover requirements. The initial
thresholds are candidate behavior, not device-validated latency guarantees.

## Guard history retained

| Guard / policy | Introducing evidence | Replacement coverage / current disposition |
|---|---|---|
| Post-seek overshoot rejection | `271f0846` / #527: estimate crosses word 2/3 then snaps back | V1 unchanged; V2 spike/correction and pinned trace tests |
| Convergence-aware settle | `02a60404` / #673: creeping estimates outlive a fixed settle window | V1 unchanged; V2 elapsed-time settle and long correction tests |
| No inferred seek from large correction | Existing `HighlightClockTest` late-snap-back cases, including 1:7 | V1 unchanged; V2 stale/correction tests; only revisions authorize resets |
| Continuous English media anchor | `bfa0e76b` / #859: poll-bounded interpolation visibly stalls | English renderer unchanged; candidate 60/90/120 Hz projection tests |
| Captured tajweed pacing | `271f0846`: retuning mid-word replays the edge | Renderer unchanged; existing wash-reset tests remain authoritative |
| Repeat residuals without a queue | Historical Shuraym 7:146 regression in the V2 plan | Renderer unchanged; migration still requires actual motion tests |
| Provisional tap and carried-page gates | Existing tap/seek and reader transport suites | Preserved; candidate integration deferred to WP4 |

No guard is retired by this slice. Complete the remaining guard inventory and
motion-policy budgets before calling WP0 done.

## Capture and replay

Use Developer → **Record performance profile** while reproducing a timing
issue. Its local report includes JSON between `highlight-sync-trace-begin`
and `highlight-sync-trace-end`. Sharing remains the existing explicit action;
there is no upload or analytics path.

Trace schema 1 records:

- Source SHA and dirty flag, application version, visible engine **1**, candidate
  engine **2**, and scope **`sample-clock`**.
- Exact timing triples, first-voice gates, and SHA-256 fingerprints, including
  replacements and withdrawal. These are local developer evidence, not bundled
  fixtures for restricted provider content.
- Ordered samples, transport/speed/calibration, clock/ink/timing revisions,
  explicit baseline rearm, and the V1 checkpoint at capture start. A capture
  started mid-verse therefore preserves the original settle state.
- V1/V2 clock and occurrence results. Candidate phase and linear progress are
  retained alongside them.

The recorder retains at most 1,024 samples, preserving the complete prefix.
`droppedSamples` reports truncation; a truncated run does not cover its tail.
Sampling and serialization run only during an explicit capture.

Replay an extracted JSON file or the whole report with JDK 21:

```sh
./gradlew testDebugUnitTest \
  --tests com.beautifulquran.domain.HighlightReplayTest \
  -PhighlightTrace=/absolute/path/to/profile.txt
```

The optional file is declared as a Gradle test input. Replay verifies the
fingerprints and reruns the production V1 filter and candidate core without a
player, network, or provider cache. Pinned regression traces and seeded
transport sequences run in ordinary CI without an external report.

Sample-clock replay does **not** capture the V1 Compose animation/effect
lifecycle, rendered glyph edges, page carry, or physical speaker/Bluetooth
latency. It must not be reported as evidence for those gates.

## Remaining migration work

The reader's word, follow, and basmalah polls now use coherent packets, but
they remain separately scheduled consumers. Consolidating their scheduling,
ensuring transport events cannot be lost in conflation, and completing the
actual-motion replay harness remain WP1/WP2 work.

WP4–WP8 remain pending: candidate transport/seed integration, Arabic normal/
repeat/wasl migration, English correction feedback, browser parity, pinned
13-reciter coverage, rendered release checks, physical output measurement,
and retirement of replaced paths. V2 remains shadow-only until those gates
are satisfied. The application and database versions are unchanged.
