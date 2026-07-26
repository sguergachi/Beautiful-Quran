# Complexity map and simplification guide

*Re-measured 2026-07-26. This document is the cross-platform simplification
guide: the rules for deciding whether a refactor actually simplifies the app,
the invariants a refactor must preserve, and the currently open decompositions.*

> Line counts below are a **discovery signal only** and they go stale fast —
> re-measure before quoting one as evidence. Android *quality* findings
> (correctness, lifecycle races, test gaps) live in
> [`quality-reviews/`](./quality-reviews/ANDROID_QUALITY_SUMMARY.md).

## Why this document exists

Beautiful Quran has two kinds of complexity:

1. **Essential complexity** protects the product: repeat-aware word timing,
   Arabic shaping, gapless audio, focus geometry, offline data validation, and
   the paper interaction language. This logic should be isolated and tested,
   not made artificially short.
2. **Coordination complexity** comes from one file or object owning several
   independent lifecycles. This is where simplification pays: smaller
   coordinators, explicit state machines, shared policy fixtures, and named
   boundaries.

The goal is not the fewest lines. It is the smallest number of places a person
must understand before making a safe change. A long pure algorithm can be safer
than a short coordinator with six mutable resources.

## Complexity rules for this repository

- One owner per mutable resource: one scroll writer, one active audio-element
  owner, one source of settings truth, and one database-generation canon.
- Pure policy before platform glue. Timing, focus, search, repeat, playlist,
  and edit-transition decisions should accept values and return values.
- A facade may be large in capability but should be small in mechanism. Public
  player/store APIs can stay stable while mechanisms move behind them.
- Split by reason to change, not by line count. A renderer can be large if every
  line changes for the same typography reason.
- Cross-platform parity belongs in fixtures and contracts, not a forced shared
  runtime module. Do not create a cross-platform runtime dependency.
- Do not replace the existing raw SQLite, hand-rolled dependency injection,
  paper stack, or external store with a framework solely to reduce boilerplate.
  No pass has ever produced evidence justifying Hilt, Room, Navigation Compose,
  Redux, or Zustand here. **Avoid DataStore** too: `SharedPreferences` behind a
  `StateFlow` is the settings/bookmark contract, and a migration would add more
  code than it removes unless asynchronous persistence is shown to fix a
  *measured* startup cost.
- Preserve draw-phase animation and boundary-only state publication. A shorter
  implementation that rerenders at 30/60/120 Hz is a regression.
- **Check generic "Modern Android" advice against this repo before acting on
  it.** Recurring false positives: stability annotations (`@Immutable` /
  `@Stable`) are near-moot here because Kotlin 2.4 with no `composeCompiler {}`
  block runs **strong skipping** by default, so unstable params already skip on
  instance equality; and the reader's active word is published through
  `distinctUntilChanged`, so it changes on **word boundaries, not 33 ms poll
  ticks** — any argument premised on per-tick UI updates is wrong. Measure, or
  cite the code, before landing a "modernization".
- Finish or delete experiments. Developer mode is a runtime preference, not a
  debug source set, so all lab code ships in release builds. Every lab must
  justify its binary, maintenance, and app-shell lifecycle cost.

## System flow

```text
External Quran sources
    -> tools/build_db.py: fetch, normalize, align, validate, override, write
    -> data/quran.db (the one canonical artifact)
       -> Android asset copy -> raw SQLite repository
       -> Web build copy -> sql.js repository

Repositories -> view models / AppStore -> player facade + pure engines
             -> paper navigation -> reader renderers -> draw/paint-phase ink
```

The highest-risk seams are where arrows cross: database schema/query parity,
player-to-highlight clocks, focus-to-DOM/list geometry, and settings that cause
both content reflow and playback changes.

## Current hotspots

Measured 2026-07-26. Ranked by coordination cost, not size.

| Area | Signal | Why it is difficult |
|---|---:|---|
| Android reader rendering | `ReaderComponents.kt` 3,364 | Several independent renderer families plus page decoration in one file — now the largest source in the repo |
| Android reader screen | `ReaderScreen.kt` 2,427 | Search, focus, follow, permission, layout, playback, and overlays meet in one screen; many individually justified `LaunchedEffect`s whose *ordering* is the hard part |
| Web styling | `styles.css` 5,004 | Every surface, lab, animation, theme, and state selector shares one global cascade; selector order is an implicit dependency graph |
| Data generation | `build_db.py` 1,580 | Fetching, alignment, timing heuristics, morphology, schema, validation, and writing interleaved in one namespace |
| Web playback | `player.ts` 1,308 | Facade still coordinates play intent, playlist/repeat, recovery, boundary timers, and publication; remaining flags form an implicit state machine |
| Web reader/store | `ReaderScreen.tsx` 1,417 + `appStore.ts` 1,030 | React effects coordinate DOM focus, playback, navigation, search flash, root viewer, and persistence |
| Android app shell | `MainActivity.kt` 1,088 | Four sheets, five overlay families, return state, deep links, media search, shortcuts, and two AppFunctions scopes cross several lifecycles |
| Timings Lab | screen 876 + view model 685 | Editor, recorder, player coordinator, persistence adapter, and exporter in one feature |
| Rendering parity | Android and web implement Highlight/Ink/Focus/search/brush policy separately | Behavior can drift even when each side is locally tested |

Large but well-contained code is not automatically a hotspot. `FocusEngine`,
`HighlightEngine`, `OrnamentGenerator`, fade math, and audio-boundary detection
are coherent algorithms. Prefer stronger fixtures and smaller helper functions
there over moving logic into more layers.

Tests as of 2026-07-26: 394 Android JVM tests, 316 web tests. The web
production type-check/build is part of the gate because Vitest alone does not
type-check the app. Pure policy is well protected; the largest mutable
coordinators are still verified mostly indirectly.

## Open decompositions

Each is independently reviewable. Ordered by value, not size.

1. **Split `ReaderComponents.kt` by renderer responsibility** (behavior
   unchanged): `WordByWordAyah.kt` (word unit, connected unit, shared highlight
   layer), `EnglishAyah.kt`, `HafsAyah.kt` (shaped line renderer + hit testing),
   `ReaderDecoration.kt` (header, basmalah, ayah mark, page break, return pill);
   keep `AyahBlock` as the mode switch and state-to-ink adapter.
2. **Split web CSS while preserving cascade order** — move-only first
   (`tokens`, `base`, `paper-stack`, then per-feature), compare production CSS
   output, and only then replace repeated literals with semantic properties.
3. **Split `build_db.py` into a stdlib-only `tools/quran_db/` package** with an
   orchestration-only entry point: `fetch`, `text`, `timings`, `morphology`,
   `overrides`, `schema`, `validate`. Each loader returns typed dataclasses plus
   diagnostics instead of mutating shared lists; `main` assembles a build report
   and decides which warnings are fatal. Verify by comparing schema, counts, and
   a deterministic SQL dump — not by eyeballing.
4. **Extract Android `PaperStackState`** (position, maximum layer, settle, back,
   gesture blocking) and one overlay host, without changing the hand-rolled
   navigation behavior. Unit-test layer and settle decisions, including
   settings-without-reader.
5. **Extract a pure `TimingEditorState`** with `SelectWord`, `MoveStart`,
   `MoveEnd`, `RecordMark`, `Undo`, `ResetWord` commands; a reducer that
   validates ordering and returns state plus effects. Playback seeking and
   export stay adapters.
6. **Shared cross-platform fixtures** for Highlight, Clock, Ink, Focus,
   basmalah, search, brush constants, and ornament seeds, run from both JVM and
   Vitest. This is the parity mechanism — not shared runtime code.
7. **Contract tests at the untested seams**: AppFunctions execution and
   registration, MediaLibrary search/paging/queue resolution, shortcut
   publication, canonical DB queries for both clients, and a visual gate for
   Arabic shaping (no pure unit test can catch a shaping artifact).

Avoid broad snapshot tests of entire screens. They create churn while missing
timing, shaping, and event-order failures.

## Invariants a refactor must preserve

Grouped by area. Breaking one of these is a regression even if every test
passes.

**Data and build.** Build-time-only repair; the app never inspects or repairs
content. Canonical Uthmani segmentation, atomic cache writes, repeat-cleanup
fixpoint, overrides applied last, coverage failure is fatal, committed output,
and a `quran-vN.db` filename bump on any content change. DB and font assets stay
uncompressed; extraction is temp-copy-then-rename, read-only open, no runtime
migrations. Normal builds must never regenerate the database.

**Domain.** Every `domain/` file stays pure and Android-free. Karaoke gap
holding, silence before the first and after the last segment, repeat
backtracking/high-water behavior, and allocation-free prepared timing lookup.

**Playback.** One playback owner. `mediaId = surah:ayah:reciterId`, the ayah-0
basmalah lead-in, skip-on-preface-failure, range restart behavior, audio
focus/noisy handling, 1 GB LRU cache, and the shared `recitationQueue` builder.
Web: iOS single persistent element, desktop blob-backed standby, pinned cache
window, rAF ticker only while active, boundary-only store emissions, equal-power
join fallback, and watchdog recovery.

**Reader and focus.** `ReaderFocusController` is the sole scroll writer (web:
sole `scrollTop` writer), with mutex serialization, live-geometry home scroll,
far-target teleport, ayah-0 basmalah target, and tall-verse word keep-in-view.
Poll only while the surah is loaded and subscribed, 33 ms playing / 250 ms
paused, `distinctUntilChanged` boundary publication, timing-override
invalidation, and derived-state reads that avoid scroll-frame recomposition.
Follow pauses on hand gesture only.

**Rendering and ink.** Opaque Quran glyphs, paper-cover dimming, draw-phase
progress reads, one-ayah recomposition, QCF multi-word spans, directional wash,
and repeat-chain membership. Web: `useLayoutEffect` for progress-zero before
paint, no React state per animation frame, cancellation on unmount, and
independent repeat/search overlay layers. Keep Arabic and English base-paint
strategies separate — forced abstraction reintroduces dirty overlapping marks.

**Theme and ornaments.** Deterministic seeded output, the star-geometry
prohibition described in code, live screen-corner alignment, one-shot entrance,
and draw-phase animation.

**Timings Lab.** Bundled timings as the reset baseline, local overrides taking
immediate precedence, verbatim committed patch application, validation against
canonical word counts, and no editor logic in the production reader.

**Web shell.** Never cache `index.html` navigation, include both sql.js WASM
filenames, copy rather than fork the canonical DB, and gate service-worker
registration on a successful DB boot.

## Definition of done for a simplification

A refactor is simpler only when all of the following are true:

- A maintainer can name one owner for each mutable resource it touches.
- Public behavior and performance invariants have focused tests or an explicit
  manual verification case.
- Call sites know fewer mechanisms, not merely different class names.
- The number of lifecycle flags/effects understood together decreases.
- Android and web parity is preserved where intended.
- The canonical DB remains build-time validated and runtime read-only.
- No paper-design rule or draw/paint-phase performance rule is weakened.
- Relevant architecture/feature documentation is updated in the same change.
