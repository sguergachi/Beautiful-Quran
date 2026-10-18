# HighlightEngine V2: synchronization overhaul plan

Status: **runtime implementation complete on Android and web; physical-output acceptance pending**.
The design was reviewed on 2026-10-18 against baseline `b94f4c88`.
See the [implemented contract and evidence](HIGHLIGHT_ENGINE_V2_CONTRACT.md).
Physical speaker/Bluetooth acceptance remains distinct from code completion.

## Goals and expected outcomes

V2 expands the pure highlight engine to own runtime synchronization policy,
including how playback samples become continuous word and wash progress.
Platform adapters report playback facts; renderers draw the engine's result.

| Goal | Expected outcome | Evidence required |
|---|---|---|
| Reduce intermittent highlight lag | Legitimate delayed samples and speed changes do not create accumulating highlight debt; stale samples cannot reveal future words unchecked | Replays of irregular sampling, stale clocks, and corrections, plus physical speaker/Bluetooth checks |
| Establish one synchronization authority | Arabic and English highlights, repeats, wasl, and follow behavior derive their appropriate clocks from one coherent playback snapshot | Shared frame contract and tests proving consumers use its outputs |
| Make transitions predictable | Pause, buffering, seek, loop, route change, and timing refresh preserve the correct word, progress, and playback intent | Deterministic event-sequence and motion-lifecycle tests |
| Preserve the product's ink behavior | Continuous forward wash, soft leading edge, genuine repeats, and English meaning ownership remain intact | Existing regressions, frame-level assertions, and rendered release checks |
| Reduce the bug surface | Timing decisions move out of duplicated polling loops and independently latched UI animations into small pure functions | Ownership audit; obsolete paths removed after replacement coverage passes |
| Make changes explainable | Every V1/V2 difference is either eliminated or covered by a narrowly specified, intentional correction | Replay comparison report with no unexplained differences |
| Preserve responsiveness | Continuous motion between polls without per-frame layout or reader-root recomposition | Controlled 60/90/120 Hz frame tests and device performance comparison |
| Align Android and web behavior | Both implementations satisfy the same behavioral fixtures while retaining platform-specific playback adapters | Shared trace results and documented platform exceptions |

The reported symptom is intermittent lag on Android with Bluetooth, possibly
also through the speaker. The investigation found reproducible clock and
animation risks, but did not establish one physical-device root cause. V2
must distinguish playback-clock errors, output latency, rendering delays,
and inaccurate source timings rather than treating them as one offset.

## Naming, scope, and ownership

**HighlightEngine V2** is the runtime synchronization overhaul. It is separate
from the offline **TimingEngine V1.5** normalization work and the separate
acoustic timing-data V2 proposal. It does not imply a timing database rebuild.
The [current engine documentation](HIGHLIGHT_ENGINE.md) continues to describe
the shipped implementation until the corresponding packages land.

The proposed ownership flow is:

1. Media3/browser adapter supplies coherent playback samples, transport events,
   route information, acknowledgments, and monotonic timestamps.
2. A pure synchronization core reconciles those inputs with previous state,
   immutable prepared timings, configuration, and an explicit frame timestamp.
3. The core derives heard position, word/ink position, active occurrence,
   continuous wash prefix, repeat/wasl progress, and follow-policy inputs.
4. Renderers apply existing shaping, pacing, masks, and geometry to that frame.

There is one owner of time policy, with distinct derived clocks where their
meaning differs. Heard audio, advanced word ink, and focus/scroll need not
use the same numeric position. Media position is projected first; calibration,
opening ramps, and clamping are then applied in their specified order.

The core remains Android-free and deterministic. Time acquisition, Media3,
coroutines, route observation, and drawing stay outside it. Existing prepared
timing lookup remains reusable; this is not a reason to introduce a framework
or a second audio player. The exact API is a WP0 deliverable, not prescribed
by this document.

Out of scope for the initial migration:

- Source timing repairs, database rebuilds, new acoustic alignment, or changes
  to English semantic ownership. Those retain their own review workflows.
- Replacing the existing tap/seek confirmation protocol. First model and
  preserve it; any redesign requires a separate proposal and evidence.
- Changing glyph shaping, page fitting, typography, the soft wash, or glimmer's
  acoustic event ownership.
- Guessing new Bluetooth offsets to hide clock errors, or adding analytics.

## Behavioral contract

Every work package must preserve these rules unless an intentional defect
correction is explicitly specified and tested:

- **Continuous motion:** interpolation continues smoothly between legitimate
  polls. Reintroducing a cap that stalls the wash at every poll is unacceptable.
  Delayed polls and stale controller estimates need distinct treatment.
- **Trust and correction:** preserve protection against plausible-looking stale
  clocks followed by a large backward correction. Retaining ink alone cannot
  undo future words already revealed by an untrusted forward estimate.
- **Transport:** ordinary pause and buffering freeze progress; resume and speed
  changes continue from the same edge. Seek, restart, and loop use explicit
  transition semantics rather than being inferred from every position jump.
- **Identity:** distinguish item/reciter, timing revision, clock revision,
  performance/ink generation, and occurrence. Repeated instances of one word
  are distinct. A stale event cannot reset or confirm a newer performance.
- **Seek intent:** a tap seed is provisional, not confirmation of heard audio.
  Actual player acknowledgments and external controls must be handled without
  assuming Media3 carries a custom seek token. Keep existing rollback guards.
- **Silence and completion:** waiting for voice, a gap holding the previous
  word, paused playback, and the completed audio tail are explicit states.
  Retained location belongs to the same item and performance; it must not
  manufacture a new active word or premature page turn.
- **Calibration:** wall-time output lag is speed-scaled exactly once. Preserve
  the existing media-time semantics of reciter/word lead and the opening ramp.
  Do not move clamps across transforms: these operations do not commute.
- **Continuous coverage:** never reveal a later word while leaving a hole in
  the underlying wash. Preserve the faded leading edge and fractional progress.
- **Repeats:** genuine phrase and same-word repeats remain visible. Residual
  completion cannot serialize short occurrences into accumulating wash debt.
  Define the short-word residual policy before replacing its implementation.
- **English:** keep semantic owner, continuous prefix, and repeat tint distinct.
  Reordered phrases advance through intervening English words. Base ink must
  finish before its repeat tint; Arabic letter/wasl pacing is not English prose
  timing. Preserve carried fragments and hidden-aside behavior.
- **Timing replacement:** publish prepared timings and related basmalah data
  atomically. Withdrawal must not resurrect stale timing content; unrelated
  reciter refreshes must not restart the current performance.
- **Output route:** service observation remains active while the reader is
  closed or paused. Use the actual routed output and avoid double compensation.
  Stop/prepare must preserve position and paused intent without phantom playback.
- **Other presentation:** glimmer retains acoustic ownership and presentation
  alignment; word lead must not shift which acoustic event owns a glimmer.
- **Performance:** frame updates are draw work. Do not rebuild timing/semantic
  maps, measure text, or recompose the reader root every frame. Preserve prepared
  lookup cost and existing caches.

Before removing a guard, inspect its introducing commit with `git log -S`,
record the failure it prevented, and prove the replacement covers that failure.
See [CHESTERTON.md](CHESTERTON.md) and [COMPLEXITY.md](COMPLEXITY.md).

## Work packages

Each package may span several small PRs. Keep behavior-preserving restructuring
separate from intentional bug fixes so reviewers can identify each difference.

### WP0 — Specify the contract and migration boundaries

**Work:** inventory current clock, animation, reset, seek, and page gates;
define event ordering, identities, phases, derived clocks, and the frame API.
Choose numerical comparison tolerances, stale-clock recovery budgets, and the
short-word/residual policy before implementing replacements.

**Acceptance:** each existing guard has an explained purpose and replacement
test; success is measurable without changing tolerances after observing failures.
Record which state owns each transition and which existing seek behavior stays.

**Tests:** turn the behavioral contract into fixture specifications, using
existing clock, wash-reset, discontinuity, output-latency, repeat, and carried-page
regressions as the starting inventory. Unresolved budgets block WP3 cutover.

### WP1 — Build baseline recording and deterministic replay

**Work:** capture actual V1 selection and motion outputs together with playback
samples/events, settings, timing revisions, and frame timestamps. Add a fake
player and controlled frame clock that exercise the production motion lifecycle,
including effects and cancellation. Version the trace format independently.

**Acceptance:** replay is deterministic; correct baseline traces are preserved;
known incorrect behavior is tagged as a defect rather than blessed as expected.
Every comparison difference has a narrow explanation and regression. Traces
remain local/offline and one player supplies both implementations.

**Tests:** delayed polls, late corrections, pause/buffer during a wash, rapid
seeks, repeat chains, page remounts, and timing refresh during playback. A helper
test alone does not establish that the actual animation/effect wiring behaves.

**Gate:** no clock replacement until the harness reproduces the relevant
historical failures and verifies representative correct behavior.

### WP2 — Consolidate playback sampling without changing behavior

**Work:** introduce one adapter providing coherent item, position, sample time,
speed, transport state, and transition events. Feed existing consumers first.
Preserve service route observation and command ordering.

**Acceptance:** V1 parity; no snapshot combines one item's identity with another
item's clock. Position conflation must not discard discrete transition events.
Existing lead ramps, tap seeds, and external playback commands still work.

**Tests:** permuted callback order, item advancement, paused seeks, external
controls, output-route changes, rapid cancellation, and stop/prepare behavior.

**Dependencies:** WP0 and WP1.

### WP3 — Introduce the pure V2 synchronization core

**Work:** implement pure state transitions and frame projection over explicit
inputs. Retain prepared lookup. Reconcile trusted playback time separately from
retained ink; handle delayed samples, stale estimates, and correction feedback.
Apply calibration after media projection in the specified order.

**Acceptance:** no Android, Compose, coroutine, or implicit clock dependency;
deterministic results; continuous interpolation and measured correction recovery.
Preserve forward-overshoot and anti-replay protections. Each intended improvement
has an explicit defect class rather than a blanket V1/V2 exception.

**Tests:** regular and irregular polls, slow creep, smooth stale estimates with
late snap-back, backward and out-of-order samples, supported speeds, and
calibration/clamping counterexamples. Include the investigated delayed first
poll and repeated long-poll cases.

**Gate:** compare in shadow mode with V1 visible; explain every difference before
using V2 for drawing. Shadow mode must not start a second playback pipeline.

### WP4 — Integrate transport, timing revisions, and reader lifecycle

**Work:** map existing seek/tap confirmation, loops, reader reopen, reciter
switch, timing refresh, and withdrawal onto explicit V2 transitions. Keep the
initial seek protocol intact while moving its policy into testable functions.

**Acceptance:** old acknowledgments cannot confirm new intents; one confirmed
seek cannot cause two wash resets. Tap seeds do not count as heard voice. Silent
tails retain the right location without triggering early navigation; stale
timing revisions cannot reappear after withdrawal.

**Tests:** overlapping same-target and alternating seeks, failed/out-of-range
seeks, paused seeks, root auditions, external commands, background playback,
page remounts, refresh during seek, and withdrawal during playback.

**Dependencies:** WP2 and WP3.

### WP5 — Migrate Arabic wash, repeats, and wasl

**Work:** replace independently latched wall-time progression in separate PRs
for normal wash, repeat wash, and wasl. Keep shaping, letter pacing, and masks.

**Acceptance:** pause/buffering freeze the edge; speed changes retain progress;
late rendering follows the specified catch-up policy. Short words and residuals
do not accumulate queue debt or suppress genuine repeats. No gaps in base ink,
hard cuts, or loss of the soft faded leading edge.

**Tests:** short-word chains, madd/wasl handoff, interrupted residuals, same-word
and phrase repeats, page-tail repeats, and controlled 60/90/120 Hz frame streams.
Rendered Pixel checks are required before making this path the default.

**Dependencies:** WP3 and WP4.

### WP6 — Migrate English and shared follow behavior

**Work:** feed English scroll and mushaf progression from V2, preserving meaning
ownership, continuous ranges, fractional projection, carried fragments, and
base-before-repeat ordering. Integrate basmalah and follow inputs using their
appropriate derived clocks.

**Acceptance:** correction feedback reaches the English clock without restarting
it at every poll. No poll-driven micro-stalls, whole-letter jumps, unfinished
base ink under repeats, premature page release, or Arabic pacing imposed on
English. Preserve focus/scroll lead semantics.

**Tests:** reordered 24:2, asides and words without English owners, hidden asides,
carried fragments, retuning, page remounts, and interruption during a repeat.

**Dependencies:** WP3 and WP4; validate interactions with WP5 before cutover.

### WP7 — Establish Android/web behavioral parity

**Work:** implement the pure contract on web with shared fixtures and native
browser adapters. Resolve existing clock-policy differences deliberately,
including large backward samples after settling; do not blindly port one side.

**Acceptance:** equivalent identities, occurrence selection, prefix, repeat,
and transition results for shared traces. Document platform exceptions. Report
the implementation actually drawing, including fallback and shadow modes.

**Tests:** shared deterministic traces, generated event sequences, and actual
browser motion lifecycle. Passing separate unit suites alone is not parity.

**Dependencies:** the Android contract and relevant migration packages must be
stable before their corresponding web behavior is finalized.

### WP8 — Validate, cut over, and retire replaced paths

**Work:** run the pinned full-corpus audit, device/output checks, performance
comparison, and release validation. Enable V2 progressively with a temporary
fallback. Remove replaced clocks, duplicate animations, and fallback only once
their replacements pass the recorded gates; update architecture and engine docs.

**Acceptance:** no unexplained behavior differences; historical/generated tests
pass; Pixel speaker/Bluetooth and route-change checks pass; no material performance
regression with tracing disabled. Publish evidence, coverage, and uncertainty.

**Tests:** all layers below, release build, and end-to-end checks of the engine
actually selected. A planned package or passing shadow run is not cutover.

**Dependencies:** WP0–WP7 for the platform/surface being released. Do not declare
cross-platform completion while a corresponding web package is still pending.

## Required historical regressions

Use pinned reciter/settings/timing revisions from the original regression
evidence. Verse numbers alone are insufficient fixtures.

| Case | Behavior to preserve or correct |
|---|---|
| 1:7 and 4:145 late clock corrections | No replayed bloom or phantom restart after correction |
| Shuraym 7:146 | Genuine short repeat chain remains visible without the former accumulated wash debt |
| Hani 4:148 | First wash respects the voice gate rather than an earlier stored timing alone |
| English 24:1 | Smooth fractional motion; base ink completes before repeat tint |
| English 24:2 | Reordered meaning advances a continuous prefix through the verse tail |
| 24:33 carried fragments and silent tail | Tap ownership, retained location, and next-verse release remain stable |
| 2:12 ↔ 2:11 rapid backward/forward taps | Playing and paused seeks preserve prior ink and provisional target semantics |
| Leading silence and basmalah variants | Include Hani/Husary as well as Sudais, a short opening word, and surah 9 without basmalah |

These are a minimum inventory, not a replacement for existing tests. Expand it
from memory, introducing commits, and the suites during WP0/WP1. Test product
behavior as well as the mechanism that used to enforce it.

## Testing and release gates

### 1. Pure tests and cross-layer replay

Keep current unit regressions, then exercise the actual sample → sync state →
frame → motion lifecycle. Assert word/occurrence, fractional edge, continuous
prefix, repeat state, transport phase, and page/follow decisions. Record the
commit, actual engine version, trace-schema version, timing/content hashes,
configuration, monotonic timestamps, and frame schedule with every trace.

Run V1 and V2 against identical inputs. A difference must be eliminated or
mapped to an approved defect correction with a precise expected result. Never
use a broad tolerance or allowlist to hide an entire class of mismatches.

### 2. Generated event sequences

Generate reproducible combinations of polls, corrections, pause/resume,
buffering, speed, seeks, loops, item changes, route changes, and timing refresh.
Exercise repeated, reordered, delayed, and stale callbacks. Keep seeds and
shrink failures into permanent regression fixtures. Check identity isolation,
no unintended reset, continuous coverage, and bounded recovery.

### 3. Pinned corpus audit

Audit all 13 supported reciters against a pinned delivered timing snapshot.
Probe starts/ends and adjacent milliseconds, gaps, silence, repeated positions,
and forward/backward/scrambled lookup order. Validate English prefix behavior
without changing semantic ownership. Include downloaded/provider timing packs
where applicable and record their revisions and missing coverage explicitly.

This is a read-only runtime audit: do not rebuild the DB or commit restricted
provider content as test fixtures. Store an evidence manifest for external
corpus runs. Logical lookup agreement does not prove acoustic boundaries,
English semantic correctness, or rendered motion.

### 4. Rendered release and physical output

On the Pixel, test speaker and Bluetooth, connection/disconnection during
playback, paused route changes, supported speeds, Arabic/English, scroll/mushaf,
repeat chains, rapid taps, and background/reopen. Check the visible faded edge,
continuous prefix, page carry, glimmer alignment, and follow behavior.

Measure physical output synchronization separately from internal screen/audio
capture: internal recording does not establish when sound reaches a headset.
Record device, route, reciter, verse, configuration, measured error, and method.
Use WP0's numerical recovery/performance budgets, not an unmeasured claim that
the result “looks synchronized.” Compare frame work with tracing disabled and
simulate 60/90/120 Hz; record the physical device's actual supported refresh rate.

### Existing gates and baseline

The review at `b94f4c88` ran successfully:

- Android JVM: **1,516 tests across 166 suites**.
- Web: **850 tests across 92 files**.
- Timing pipeline: **116 cases** via `tools/test_build_db.py`.

These are baseline results, not V2 implementation evidence. No physical
speaker/Bluetooth validation or V2 replay harness has been completed by this plan.

As packages land, add their regressions to CI and run the relevant gates:

```sh
./gradlew testDebugUnitTest
python3 tools/test_build_db.py
# From web/ when web behavior changes:
npm test
npm run build
# Before Android cutover:
./gradlew assembleRelease
```

Use JDK 21. Follow [PERFORMANCE.md](PERFORMANCE.md) for hot-path checks and the
repository's release/APK delivery workflow for implementation changes.
`lintDebug` has documented existing Media3 opt-in errors; do not substitute it
for the established release gate or claim those failures were introduced by V2.

## Versioning and completion

Track engine generation independently from application, trace, and data versions:

- Designate the existing runtime implementation as diagnostic engine **1** and
  the new implementation as **2**. Report whichever is actually active; a build
  containing V2 in shadow mode is still drawing with engine 1.
- Version trace schemas separately so old recordings remain interpretable.
- The runtime update uses Android **0.12 / versionCode 13** and web **0.12.0**.
  Engine generation is **2**; new native trace schema is **2**, independently.
- Packaged timing data remains unchanged at v67. Runtime-only work does not
  require a DB bump; any later data change follows the separate fingerprint
  and versioning rules.

WP0–WP7 are implemented: coherent shared sampling, pure sync and motion windows,
transport/lifecycle integration, Arabic/repeat/wasl and English migration,
Android/web fixtures, local sample/frame replay, and read-only corpus audit.
WP8 includes release rendering/performance checks and removal of replaced
speech animation owners. Physical Pixel speaker/Bluetooth acceptance remains
unmeasured; APK delivery is paused at the user's request until engine work is complete.

V2 is complete when the relevant surfaces use the pure synchronization core,
every removed guard has replacement coverage, all differences are explained,
historical/generated/corpus/device gates pass, performance is within the agreed
budgets, and obsolete paths and documentation are updated. Record remaining
coverage gaps explicitly. Tests and staged rollout increase confidence; they
cannot justify a promise of zero regressions.

## Related documentation

- [Current highlight engine](HIGHLIGHT_ENGINE.md) and [ink engine](INK_ENGINE.md)
- [Architecture](ARCHITECTURE.md) and [complexity rules](COMPLEXITY.md)
- [Output latency](OUTPUT_LATENCY.md) and [tajweed pacing](TAJWEED_PACING.md)
- [Repeat highlighting](REPEAT_HIGHLIGHTING.md) and [glimmer](GLIMMER.md)
- [English alignment coverage](ENGLISH_ALIGNMENT_COVERAGE.md)
- [Provider content and timing revisions](QF_CONTENT_SYNC.md)
- [Web implementation](WEB.md)
