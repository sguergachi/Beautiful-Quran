# Complexity map and simplification guide

*Re-audited 2026-07-16 against the Android app, web app, data builder, scripts,
workflows, and tests. The first audit was 2026-07-12; this pass remeasured the
repository after the bookmark, Assistant/AppFunctions, profiling, settings,
ornament-lab, and search work that followed. This document describes the
current code rather than a future architecture.*

> **Quality follow-up (2026-07-22):** multi-agent Android *quality* reviews
> (correctness, lifecycle, tests—not only LOC) live under
> [`docs/quality-reviews/`](./quality-reviews/ANDROID_QUALITY_SUMMARY.md).
> Reader/share sizes and test counts there supersede the July 16 numbers for
> Android production quality prioritisation. This complexity map remains the
> cross-platform simplification guide until a full re-audit.

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
must understand before making a safe change.

## Review evidence

This review combines physical line counts with responsibility and lifecycle
inspection. Line count is only a discovery signal: a long pure algorithm can
be safer than a short coordinator with six mutable resources.

- Compared with the original audit commit (`c88a505`), the current tree has 96
  subsequent commits. Application, test, web, tool, and script sources changed
  by roughly +13,863/-2,070 lines after excluding the large timing-override
  JSON patches and database binary.
- The largest current source files were inspected by responsibility, not just
  sorted by size. New Android OS integrations and developer visual tooling were
  included explicitly in this pass.
- `./gradlew testDebugUnitTest` passes: 195 JVM tests *at the time of this
  audit*. **333 as of 2026-07-24** — check the current number before quoting it
  as evidence.
- `npm test` passes: 42 files and 265 tests. `npm run build` also passes and is
  part of the verification baseline because Vitest alone does not type-check
  the complete web application.
- Generated assets, timing JSON, fonts, and SQLite bytes are assessed through
  their owning pipelines and validation contracts, not treated as source LOC.

## Executive summary

The architecture is sound: build-time cleanup feeds a read-only database;
correctness-sensitive engines are pure; playback is behind a controller; and
UI state generally flows through view models or the web store. The most useful
work is therefore decomposition within the existing architecture, not adding a
framework.

| Rank | Area | Current signal | Why it is difficult | Best simplification |
|---:|---|---:|---|---|
| 1 | Web playback | `player.ts`, 927 lines | The facade still coordinates play intent, playlist/repeat, recovery, boundary timers, and publication, but element and join mechanisms are now isolated | Keep the facade stable; use the new event harness before extracting another state owner |
| 2 | Android app shell + OS actions | `MainActivity.kt`, 1,047 lines; `assistant/`, 1,008 | Four sheets, five overlays, return state, deep links, media search, shortcuts, and two AppFunctions scopes cross several lifecycles | Extract paper/overlay state and protocol-free policy tests; add a shared executor only when it removes proven duplication |
| 3 | Android settings + visual labs | `SettingsScreen.kt`, 1,903 lines; ornament lab, 736 | Production settings, brush/check geometry, live tuning, clipboard interchange, audio audition, profiling, and lab launchers share one file/path | Delete tuning transport after values ship or move the developer lab wholesale; keep production settings declarative |
| 4 | Android reader | `ReaderScreen.kt`, 1,128 lines + `ReaderComponents.kt`, 1,564 | Search, focus, follow, permission, layout, playback, and multiple renderers meet in one screen | Extract remembered session controllers and renderer-specific files; leave list assembly visible |
| 5 | Web reader/store | `ReaderScreen.tsx`, 1,074 + `appStore.ts`, 757 | React effects coordinate DOM focus, playback, navigation, search flash, root viewer, and persistence | Split commands into small coordinators while retaining one external-store snapshot |
| 6 | Web styling | `styles.css`, 4,616 lines | Every surface, lab, animation, theme, responsive rule, and state selector shares one global cascade | Split by tokens/shell/features/labs in unchanged order; retain one ordered entry stylesheet |
| 7 | Data generation | `build_db.py`, 972 lines | Fetching, alignment, timing heuristics, morphology, schema, validation, and writing are interleaved | Turn it into a small `tools/quran_db/` package with an orchestration-only entry point and fixture tests |
| 8 | Timing lab | screen 839 + view model 669 | Playback, recording, editing, undo/reset, override persistence, and patch export form one feature | Put edit transitions in a pure `TimingEditor`; make playback and export adapters explicit |
| 9 | Rendering parity | Android and web implement the same Highlight/Ink/Focus/search/brush policy separately | Behavior can drift even when each implementation is locally tested | Add shared language-neutral fixtures; do not create a cross-platform runtime dependency |

Large but well-contained code is not automatically a hotspot. `FocusEngine`,
`HighlightEngine`, `OrnamentGenerator`, fade math, and audio-boundary detection
are coherent algorithms. Prefer stronger fixtures and smaller helper functions
there over moving logic into more layers.

### Quality verdict

The codebase remains healthy in its foundations. Pure correctness policy is
prominent, both clients use the same canonical database, test suites are fast
and green, platform dependencies remain narrow, and performance invariants are
documented unusually well.

The quality trend since the first audit is mixed. Product capability and test
count grew, and playlist/catalog helpers were extracted. At the same time,
high-churn experiments accumulated inside already-large production files. The
most important next move is therefore not a framework or broad rewrite. It is
to finish experiments: delete tuning machinery that no longer earns its
shipping cost, move still-useful labs behind clean feature boundaries, and add
contract/event tests at the new OS and audio seams.

No evidence from this pass justifies replacing Compose, raw SQLite, the
external web store, or the paper stack. The risks are local ownership and
protocol coverage, not the architectural choices themselves.

### Simplification progress

| Date | Area | Completed |
|---|---|---|
| 2026-07-12 | Web word renderers | Extracted shared `useWordInteraction` for tap, keyboard, hold, movement cancellation, and click suppression; extracted the shared paper-cover reset. `WordUnit` fell from 487 to 427 lines and `HafsWord` from 280 to 221. |
| 2026-07-12 | Web playback | Extracted pure/tested playlist construction and a tested `MediaSessionBridge`. OS action handlers are bound once instead of being rebound on every metadata update. The `PlayerController` public API is unchanged. |
| 2026-07-15 | Android playback/catalog | Extracted `RecitationMedia.kt` so the service and controller share queue construction. The new MediaLibrary catalog remains inside `PlaybackService`, preserving a single player owner. |
| 2026-07-16 | Web playback | Extracted `MediaElementTransport` for active/standby identity, source loading, promotion, and stale-event rejection. Extracted `JoinCoordinator` for standby preparation, audible bounds, and cancellable fades. Added controller event-sequence tests; the public `PlayerController` API is unchanged. |
| 2026-07-16 | Verification | Re-ran both unit suites after rapid feature growth: 195 Android and 265 web tests pass. The new review priorities below replace stale file measurements from the first audit. |

## Complexity rules for this repository

Use these rules when deciding whether a refactor actually simplifies the app.

- One owner per mutable resource: one scroll writer, one active audio-element
  owner, one source of settings truth, and one database-generation canon.
- Pure policy before platform glue. Timing, focus, search, repeat, playlist,
  and edit-transition decisions should accept values and return values.
- A facade may be large in capability but should be small in mechanism. Public
  player/store APIs can stay stable while mechanisms move behind them.
- Split by reason to change, not by line count. A renderer can be large if every
  line changes for the same typography reason.
- Cross-platform parity belongs in fixtures and contracts, not a forced shared
  runtime module.
- Do not replace the existing raw SQLite, hand-rolled dependency injection,
  paper stack, or external store with a framework solely to reduce boilerplate.
- Preserve draw-phase animation and boundary-only state publication. A shorter
  implementation that rerenders at 30/60/120 Hz is a regression.

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

## Android application

### Build, application container, and dependency wiring

**Files:** `app/build.gradle.kts`, `baselineprofile/`, `QuranApp.kt`,
`ui/AppViewModel.kt`, manifest and resources.

**Current responsibility.** Gradle copies the committed database into generated
assets, configures release shrinking/signing, and pins Java/Kotlin 21. `QuranApp`
constructs application-scoped repositories and `PlayerController`.
`AppViewModelFactory` maps seven view-model classes to those singletons. The
small `baselineprofile` test module generates startup profiles and macrobenchmarks
the app on a connected device; generated startup rules ship from `app`.

**Complexity.** Low. The explicit wiring is easy to audit and supports the
minimal-dependency invariant. The factory's `when` is repetitive but also a
complete composition root. The profiling module is an intentional exception to
the former literal "single Gradle module" description, not a new runtime layer.

**Simplify safely.** Keep this structure. If constructor lists grow, introduce
one `AppGraph` data holder and let both `QuranApp` and the factory refer to it.
Do not add Hilt/Koin. Add a small factory test when a new view model is added so
missing branches fail before runtime. Keep the generated-asset task as the only
Android DB-copy path.

**Preserve.** Database content changes require a `quran-vN.db` filename bump;
release builds must still work with the documented signing fallback; DB and
font assets remain uncompressed; baseline generation must stay outside normal
unit-test/build requirements because it needs a device.

### Data models and database extraction

**Files:** `data/model/*`, `QuranDatabase.kt`.

**Current responsibility.** Immutable models define surahs, ayahs, words,
reciters, segments, morphology, and search results. `QuranDatabase` atomically
copies the packaged DB to no-backup storage and opens it read-only.

**Complexity.** Low, with one important operational invariant: extraction is
keyed by a filename version rather than SQLite schema metadata.

**Simplify safely.** Keep models as plain data classes. Make `DB_FILE_NAME`
internal rather than private if a unit test can then assert the expected
version, or add a build check that compares a committed DB metadata/version
file with the Kotlin suffix. That removes reliance on memory during data-only
changes. Do not make the runtime inspect or repair content.

**Preserve.** Temporary-copy then rename, cleanup of older extracted versions,
read-only open mode, and no runtime migrations.

### Quran repository

**File:** `data/QuranRepository.kt` (366 lines).

**Current responsibility.** Typed SQL queries, small immutable caches, segment
parsing, timing-override merge, root concordance, and a lazily built whole-Quran
word-search index.

**Complexity.** Medium. SQL mapping is direct and reviewable, but the repository
mixes three performance profiles: tiny cached metadata, per-surah reads, and a
77k-row search index. Timing overrides also make one nominally read-only query
path reactive.

**Simplify safely.** Keep raw SQL. Separate private query objects by concern
(`QuranTextQueries`, `TimingQueries`, `MorphologyQueries`) only when a new query
lands in that concern; the public repository can remain the facade. Give the
word-search index its own lazy holder so its memory cost and warm-up policy are
obvious. Introduce query contract tests against `data/quran.db` for representative
surah, timing, QCF span, and morphology rows. Use named column constants or
small row mappers where a query has more than six positional columns.

**Do not simplify by.** Adding Room, copying repair rules from the builder, or
turning every query into a generic abstraction. The current `queryList` is
enough for ordinary lists.

### Settings and bookmarks

**Files:** `SettingsRepository.kt`, `BookmarkRepository.kt`.

**Current responsibility.** SharedPreferences persistence presented as
`StateFlow`; bookmarks use a compact custom encoding.

**Complexity.** Low to medium. Settings keys are duplicated between read and
write, enum ordinals need compatibility handling, and a full settings object is
rewritten for each update. Bookmark encoding intentionally avoids another
serialization dependency.

**Simplify safely.** Centralize preference keys and enum codecs. Add a single
`persist(Settings)` function if read/write logic grows. Keep `update { copy() }`
as the public API. Document the bookmark delimiter format beside its tests and
version it before adding fields. Avoid DataStore unless asynchronous persistence
solves a measured problem; migration code would currently add more complexity
than it removes.

### Pure domain policy

**Files:** `domain/ArabicNormalize.kt`, `Basmalah.kt`, `EnglishTypography.kt`,
`HighlightClock.kt`, `HighlightEngine.kt`, `OutputLatency.kt`, `WordSearch.kt`.

**Current responsibility.** Text normalization, basmalah playlist rules,
English token alignment, stable playback clock selection, binary-search word
highlighting with repeat/high-water metadata, and search matching/sectioning.

**Complexity.** Medium, mostly essential. These files are small and independent
of Android. `WordSearch` is the broadest because it contains query gates,
matching, highlighting, and result sectioning.

**Simplify safely.** Keep every domain file pure. Split `WordSearch` into
normalization/matching and presentation-sectioning only if either changes
independently again. Add shared JSON fixtures for HighlightEngine,
HighlightClock, basmalah, typography, and search, consumed by JVM and Vitest.
Fixtures should cover malformed/empty input and real repeat regressions.

**Preserve.** Karaoke gap holding, silence before the first and after the last
segment, repeat backtracking/high-water behavior, and allocation-free prepared
timing lookup.

### Android playback

**Files:** `playback/PlayerController.kt`, `PlaybackService.kt`,
`AudioPrefetcher.kt`.

**Current responsibility.** The service owns ExoPlayer/MediaSession/cache.
The controller connects asynchronously, maps media IDs, constructs ayah
playlists including basmalah, handles seek/repeat/range commands, and publishes
`PlayerUiState`. Prefetch warms further clips through the same cache.

**Complexity.** Medium-high but bounded. `PlayerController` is the primary
mutable protocol boundary; range repeat is enforced by both item transitions
and a near-end monitor because player events alone are insufficient.

**Simplify safely.** Extract pure `PlaylistPlan` construction (media IDs,
basmalah offset, ayah/index mapping, repeat range indices) and test it without
Media3. Keep connection lifecycle and commands in the controller. Represent
repeat configuration as one sealed/value state instead of nullable range plus
repeat mode if another repeat variant is added. Keep service cache construction
in one function shared by player and prefetch configuration.

**Preserve.** `mediaId = surah:ayah:reciterId`, the ayah-0 basmalah lead-in,
skip-on-preface-failure, range restart behavior, audio focus/noisy handling,
and 1 GB LRU cache.

### Paper stack and activity orchestration

**Files:** `MainActivity.kt`, `ui/PageTurnSounds.kt`.

**Current responsibility.** Four-sheet navigation (bookmarks, home, reader,
settings), drag/fling geometry, system back, entrance ownership, timing/root/
ink/ornament overlays, return-to-origin state, Assistant action fulfillment,
foreground AppFunction registration, and page-turn audio synchronized to
fractional stack position.

**Complexity.** Very high coordination complexity. `MainActivity.kt` grew from
784 to 1,047 lines after the first audit. The paper stack itself remains a
coherent custom navigation model, but `PaperStackApp` now coordinates seven
view models, four stack layers, five independently animated overlay families,
cross-sheet return flows, and incoming OS actions. Visibility plus
"still-rendered during exit" booleans are necessary locally, but repeating the
pair for every overlay makes the host expensive to reason about.

**Simplify safely.** Introduce a saveable `PaperStackState` owning position,
maximum layer, settle, back, and gesture blocking. Put root-return behavior in a
small state holder with named events (`jumped`, `returnRequested`, `dismissed`).
Create one overlay host composable for the timings lab and one reader-owned host
for reader overlays. Keep `paperStackDrag` as a single modifier. Unit-test pure
layer and settle decisions, including settings-without-reader.

**Do not simplify by.** Replacing the stack with Navigation Compose. The custom
continuous position drives visuals and sounds that a route stack does not model.

### Assistant, AppFunctions, shortcuts, and media catalog

**Files:** `assistant/*`, `playback/PlaybackService.kt`,
`playback/RecitationMedia.kt`, manifest and `res/xml/*` metadata.

**Current responsibility.** Several Android protocols expose the same Quran
operations: deep links and App Actions reach `MainActivity`; launcher shortcuts
publish those links; global AppFunctions call repositories/settings/playback;
foreground AppFunctions emit navigation actions; and Media3's library callback
serves/searches chapters and expands requests into full recitation queues.

**Complexity.** High at the protocol boundary, though each adapter is locally
readable. The deterministic string-parsing core in `AssistantAction` is well
tested, while its outer intent/URI adapter remains Android-bound. The main
quality gap is that global/foreground AppFunction execution, registration lifetime,
MediaLibrary paging/search/queue expansion, and shortcut publication have no
focused tests. Validation and verse resolution are also repeated across the
intent parser, activity fulfillment, AppFunctions service, and media library.
KDoc is part of the executable agent contract, so wording changes carry more
behavioral risk than ordinary comments.

**Simplify safely.** Keep protocol adapters separate. First extract and test
pure catalog search/paging/request resolution from the MediaLibrary callback.
If another duplicated operation lands, introduce one small command boundary
that validates chapter/verse/reciter and returns commands/results without
depending on `Activity`, AppFunctions documents, or Media3 callbacks; inject
small repository/settings/bookmark/player ports so global commands can then be
tested directly. Keep foreground registration as a thin lifecycle adapter and
extract its parameter decoding for ordinary JVM tests.

**Do not simplify by.** Combining the global service and activity registration
APIs, or hiding KDoc/metadata behind reflection. They have genuinely different
lifetimes and Android contracts.

**Preserve.** One playback owner, the shared `recitationQueue` builder,
basmalah queue rules, full-surah queues for media clients, clamped verse
validation, cancellation propagation, and graceful behavior on devices below
API 37.

### Home and search UI

**Files:** `ui/home/HomeScreen.kt`, `HomeViewModel.kt`, `SearchDials.kt`,
`FloatingPlaybackControl.kt`.

**Current responsibility.** Surah list, continue target, direct `surah:ayah`
navigation, word search, grouped expansion, search dials, and cover-sheet
playback control.

**Complexity.** Medium. Search has three modes with different execution costs,
and the screen owns focus/scroll-driven dismissal behavior. The pure query gate
and filtering helpers are already extracted and tested.

**Simplify safely.** Model search mode explicitly (`Idle`, `SurahFilter`,
`AyahReference`, `WordSearch`) so the screen does not infer it repeatedly from
query/results. Move expanded-section bookkeeping into a remembered search UI
state. Keep row composables stateless. If search warm-up becomes visible,
expose index status in `HomeUiState` instead of adding UI-local jobs.

**Preserve.** Word-search minimum gate, result cap/grouping, Arabic
normalization, and the distinction between loaded playback and visible cover
transport.

### Reader view model

**File:** `ui/reader/ReaderViewModel.kt` (560 lines).

**Current responsibility.** Loads content/timings, adapts player state,
publishes boundary-only active word/ayah state, applies settings changes,
controls playback/repeat, and persists bookmarks/last position.

**Complexity.** High. It is the Android reader's orchestration hub. Its polling
helper is sound, but content, timing, player command, settings reaction, and
bookmark concerns all meet here.

**Simplify safely.** Extract a pure `ReaderPlaybackPolicy` for selecting start
ayah, handling reciter changes, and mapping repeat choices to player commands.
Keep flows and lifecycle in the view model. Group commands into small private
facades (`playbackCommands`, `bookmarkCommands`) only if that reduces constructor
knowledge at call sites. Represent loaded reader data as one immutable session
object containing content, timing maps, and reciter identity so mismatched
snapshots cannot be observed during reload.

**Preserve.** Poll only while the surah is loaded/subscribed, 33 ms playing and
250 ms paused cadence, `distinctUntilChanged` boundary publication, and timing
override invalidation.

### Reader screen and focus

**Files:** `ReaderScreen.kt`, `reader/focus/FocusEngine.kt`,
`ReaderFocusController.kt`, `AyahSelectorRail.kt`.

**Current responsibility.** `ReaderScreen` assembles the reader and coordinates
search, notification permission, initial jump, follow enable/disable, active
playback focus, reflow correction, system chrome, selector rail, and overlays.
`FocusEngine` computes geometry; `ReaderFocusController` is the sole
`LazyListState` writer; the rail has its own dial/settle/grace state machine.

**Complexity.** Very high in the screen, intentionally medium-high in focus.
The many `LaunchedEffect`s are individually justified but their ordering and
shared latches are hard to understand as a whole. Focus complexity is essential
and already isolated well.

**Simplify safely.** Keep `FocusEngine` and the single-writer controller intact.
Extract these screen-level state holders:

- `ReaderSearchSession`: query, matches, index, close, and focus request;
- `ReaderJumpSession`: requested target, pending playback ayah, search flash,
  and completion acknowledgement;
- `ReaderFollowSession`: user-interruption detection, enabled state, and
  current playback focus target;
- existing `PlaybackPermissionState`, kept with its sheet.

Each holder should expose events, not its mutable fields. Move the reader list
body into `ReaderContent` only after those lifecycles are named; a purely visual
file split will not reduce cognitive load. Add integration-style coroutine
tests around jump/follow state transitions using fake focus and player ports.

**Preserve.** `ReaderFocusController` as sole scroll writer, mutex
serialization, live-geometry home scroll, far-target teleport, ayah-0 basmalah
target, tall-verse word keep-in-view, and derived-state reads that avoid
scroll-frame recomposition.

### Reader rendering and ink

**Files:** `ReaderComponents.kt`, `InkEngine.kt`, `Fade.kt`, `PlayerBar.kt`,
`VerseBookmarkRibbon.kt`, `RepeatSheet.kt` (was `RepeatDialog.kt` at audit
time), `InkLabPanel.kt`, basmalah and search-flash helpers.

**Current responsibility.** Three text modes, QCF/Hafs shaping, word hit
testing, gloss/transliteration/translation, ink state, first-pass/repeat/search
washes, bookmark animation, playback controls, and chapter/page decoration.

**Complexity.** Very high but mostly essential. `ReaderComponents.kt` still
contains several independent renderer families and page decoration. Shared
word highlight scaffolding has already been extracted locally.

**Simplify safely.** Split the file by renderer responsibility without changing
behavior:

- `WordByWordAyah.kt`: `WordUnit`, connected word unit, shared highlight layer;
- `EnglishAyah.kt`: responsive English token/range renderer;
- `HafsAyah.kt`: shaped Hafs/QCF line renderer and hit testing;
- `ReaderDecoration.kt`: header, basmalah block, ayah mark, page break, return pill;
- keep `AyahBlock.kt` as the mode switch and state-to-ink adapter.

On the web and Android, name the same conceptual transitions and drive them
from shared fixtures. Do not share rendering code. Add screenshot/device checks
for dense marks, long wraps, QCF span words, repeat wash, and all themes; pure
unit tests cannot detect shaping artifacts.

**Preserve.** Opaque Quran glyphs, paper-cover dimming, draw-phase progress
reads, one-ayah recomposition, QCF multi-word spans, directional wash, and
repeat-chain membership.

### Root viewer

**Files:** `ui/rootviewer/*`.

**Current responsibility.** Morphology labels, root summary/concordance, a
bounded one-word audio clip, and jump/return navigation.

**Complexity.** Medium. The view model coordinates a mini playback session and
must avoid accidentally resuming normal recitation. The screen is long mainly
because the overlay has distinct expanded/collapsed visual states.

**Simplify safely.** Extract a pure `wordClipBounds`/clip-plan object (already
partly done) and model clip lifecycle as `Idle`, `Loading`, `Playing`, `Done`.
Keep morphology label mapping pure and parity-tested with web. Let the app shell
own return navigation; the viewer should emit a jump intent only.

**Preserve.** Missing morphology degrades gracefully, clip start/end use timing
segments, and closing the viewer does not resume a one-shot clip.

### Settings UI

**Files:** `ui/settings/*`, `ornamentslab/*`, `ui/reader/InkLabPanel.kt`.

**Current responsibility.** Reciter, reader appearance, theme, selector side,
developer unlock, timing/ink/ornament lab entry, page-sound audition, brush
circle and check-mark geometry, live tuning sliders, clipboard import/export,
and profiling controls.

**Complexity.** Very high and newly urgent. `SettingsScreen.kt` is now 1,903
lines, the largest Kotlin source file. Production controls themselves are
straightforward; most growth comes from the developer brush/check labs and
their private geometry, parameter codecs, slider metadata, paint replay state,
and clipboard plumbing. Because normal segmented controls and switches consume
the same live parameters, the experimental state is initialized and coordinated
by the production screen even when developer mode is hidden. The separate
ornament lab adds another 736 lines and another app-shell overlay lifetime.

**Simplify safely.** First decide whether each tuning surface is still needed.
If a brush/check design has shipped and the knobs are no longer used regularly,
delete its sliders, clipboard parser/formatter, replay tokens, and presets; keep
only the named immutable shipped geometry and the small drawing composable.
Deletion is preferable to moving a finished experiment into more files. For
labs that remain useful, move the complete developer section and its state into
`ui/brushlab/`, and put reusable shipped ink controls in the theme/component
kit. `SettingsScreen` should receive stable callbacks and render declarative
choices, not own lab sessions. Keep `SettingsViewModel` thin and continue to
lazy-create page-sound resources.

Add tests for parameter parsing only if that interchange remains a supported
lab workflow. Do not write tests merely to preserve disposable tuning code.
Avoid a universal dynamic form system that hides Compose behavior.

**Quality note.** Developer mode is a hidden runtime preference, not a debug
source set, so all lab code ships in release builds. That is acceptable for
Timings Lab by product choice, but every new lab must justify its binary,
maintenance, and app-shell lifecycle cost explicitly.

### Theme, entrance, and ornament generation

**Files:** `ui/theme/*`, `ui/theme/ornament/*`, `ui/entrance/*`.

**Current responsibility.** Color/type tokens, no-ripple interactions,
draw-phase fades, ink-reveal overlays, generated cover geometry, entrance
ceremony, and reusable paper controls.

**Complexity.** Algorithmically high in `OrnamentGenerator.kt` (685 lines) and
`Ornament.kt` (667), but cohesive. Entrance timing combines window geometry,
skip readiness, status bars, and animation phases.

**Simplify safely.** Treat ornament generation as a library: separate immutable
geometry generation from Compose drawing (already largely true), add golden
seed fixtures, and avoid UI state in the generator. Consolidate repeated theme
alpha/timing constants into named tokens only when semantics match. Express
entrance phases as an enum/state machine if another phase is added. Keep small
interaction modifiers centralized.

**Preserve.** Deterministic seeded output, prohibition on inappropriate star
geometry described in code, live screen-corner alignment, one-shot entrance,
and draw-phase animation.

### Timings Lab and overrides

**Files:** `timingslab/*`, `docs/TIMINGS_LAB.md`,
`tools/timing_overrides/`.

**Current responsibility.** Developer playback, per-word boundary adjustment,
recording marks, undo/reset, local override persistence, and export to a
committable patch consumed by the DB builder.

**Complexity.** Very high. The view model (669 lines) is an editor, recorder,
player coordinator, persistence adapter, and exporter. The screen (839 lines)
contains a timeline, gestures, transport, controls, and submit ribbon.

**Simplify safely.** Make a pure `TimingEditorState` with commands such as
`SelectWord`, `MoveStart`, `MoveEnd`, `RecordMark`, `Undo`, and `ResetWord`.
Its reducer validates ordering and returns a new state plus optional effects.
Put player seeking/record-clock sampling in `TimingLabPlayback`, persistence in
`TimingOverrides`, and patch formatting in the existing exporter. Split the
screen into header, adjustment panel, timeline, transport, and submit sections.
Fixture-test the reducer with repeated positions and boundary collisions.

**Preserve.** Bundled timings as reset baseline, local overrides taking
immediate precedence, verbatim committed patch application, validation against
canonical word counts, and no editor logic in the production reader.

## Web application

### Bootstrap, shell, and paper navigation

**Files:** `web/src/main.tsx`, `ui/App.tsx`, `ui/paper/stack.ts`, entrance UI.

**Current responsibility.** Boot repository, theme metadata, service-worker
recovery, entrance cover, keyboard back, and mounting the three paper sheets.

**Complexity.** Low to medium. `App` is appropriately small. Paper layer math
is pure; CSS performs most visual stacking.

**Simplify safely.** Keep `App` as the composition root. Move cache/service
worker purge into `swRegistration` so boot recovery policy has one owner. Keep
layer calculations in `stack.ts` and test settings-with/without-reader paths.
Do not add a routing library unless URLs/deep links become a product feature.

### Web database and repository

**Files:** `data/database.ts`, `repository.ts`, `models.ts`, `settings.ts`.

**Current responsibility.** Locate/load sql.js with WASM/ASM fallbacks, stream
the 27 MB DB with progress, expose synchronous typed queries, preload chapters
over idle slices, persist settings/bookmarks, and build search indexes without
blocking the entrance.

**Complexity.** High at boot and medium after boot. Browser WASM filenames,
fallback fetch behavior, main-thread SQL, caching, and progressive warm-up are
all operationally significant.

**Simplify safely.** Keep database loading separate from domain queries. Create
one explicit `DatabaseLoader` state machine (`wasm`, `asm`, `db`, `ready`,
`failed`) rather than several fallback branches if more compatibility paths are
added. Mirror Android query contract tests against the same DB. If profiling
shows long main-thread stalls, move the entire database/repository behind one
Web Worker message API; do not mix worker and main-thread queries piecemeal.
Generate or share schema/query fixtures rather than duplicating schema repair.

**Preserve.** One canonical `data/quran.db`, fallback asset names, progress
reporting, chapter-at-a-time idle warm-up, timing laziness, and local-only
settings/bookmarks.

### Web domain policy

**Files:** `domain/*`, pure helpers under `ui/reader`, `ui/paper`, and
`playback/playlistNext.ts`.

**Current responsibility.** Ports Android Highlight/Clock/Ink/Focus/search
behavior and adds pure browser-specific decisions such as follow gestures,
reader windows, rail math, playback selection, and playlist-next decisions.

**Complexity.** Medium and healthy. The risk is policy scattering: some pure
domain logic lives under UI or playback because it originated during a feature.

**Simplify safely.** Keep files near their feature, but maintain a documented
list of parity-critical modules. Consume shared fixtures for cross-platform
policy. Ensure browser-only policies remain DOM-free when possible. Avoid one
large generic `domain` package that loses feature ownership.

### Web external store

**File:** `store/appStore.ts` (757 lines).

**Current responsibility.** One external-store snapshot owns boot, paper
navigation, content/timing session, settings, player adaptation, highlighting,
search flash, follow, bookmarks, and root viewer. It deliberately emits only
UI-visible boundaries rather than every player tick.

**Complexity.** Very high coordination complexity. The single snapshot makes
React subscription predictable, but the class has too many reasons to change.
Mutable timing/prepared caches sit beside user-visible state, and commands both
decide policy and invoke platform services.

**Simplify safely.** Retain one `useSyncExternalStore` facade and immutable
`AppState`, but delegate commands to collaborators:

- `ReaderSession`: open/reload content, timing maps, prepared timings;
- `PlaybackCoordinator`: play/seek/repeat commands and active-word derivation;
- `PaperCoordinator`: layer/back transitions;
- `RootViewerCoordinator`: load, close, jump intent;
- persistence stays in existing settings helpers.

The facade applies returned patches and emits once. Collaborators should return
state patches/effects rather than call React listeners. Add a deterministic
store harness with fake repository/player to test open-surah races, reciter
reload, pending search flash, and boundary-only emission.

**Do not simplify by.** Introducing Redux/Zustand merely to move the same
coupling into slices. First isolate commands and ports; choose a library only if
the resulting interfaces demonstrate a missing capability.

### Web player

**Files:** `playback/player.ts`, `mediaElementTransport.ts`,
`joinCoordinator.ts`, `audioPrefetch.ts`, `audioBounds.ts`, `audioFade.ts`,
`iosMedia.ts`, `playbackStallWatchdog.ts`, `playlistNext.ts`.

**Current responsibility.** Playlist construction, desktop dual-element joins,
iOS single-element swaps, Cache API/blob prefetch, audible-bound analysis,
equal-power edge fades, stall recovery, repeat, Media Session, and rAF position
publication.

**Complexity.** Still the highest correctness risk, but ownership is improved.
`MediaElementTransport` now owns the active/standby elements and rejects events
from a retired element. `JoinCoordinator` owns standby preparation generations,
audible-bound storage, promotion, and cancellable fades. `player.ts` fell from
1,097 to 927 lines and retains public commands, playlist/repeat policy, play
intent, watchdog recovery, boundary scheduling, and state publication. Its
remaining flags still form an implicit high-level state machine.

**Simplify safely.** Keep `PlayerController` as the stable public API and split
mechanisms in this order:

1. `PlaylistSession`: playlist construction and next-index policy are now pure;
   move mutable index/ayah mapping only if another playlist mode lands;
2. `MediaElementTransport`: completed for event binding, source loading,
   readiness, active identity, standby promotion, and stale-event rejection;
3. `JoinCoordinator`: completed for standby preparation, audible bounds, fades,
   and promotion; high-level boundary timing intentionally stays in the facade;
4. `PlaybackWatchdog`: already mostly separate, expanded to return recovery
   actions rather than mutate controller flags;
5. `MediaSessionBridge`: completed; metadata and OS action handlers now live
   outside the controller;
6. controller: serialize commands, combine state, publish snapshots.

The audio-element factory is injected for Node event-sequence tests. Add an
explicit high-level state enum only if the next playback behavior would add
another lifecycle flag; the current extraction made element legality structural
without duplicating `PlayerState`. Inject clock/timers only when testing the
watchdog or fade scheduler requires them. Keep the existing pure helpers and
unit tests.

**Preserve.** iOS single persistent element, desktop blob-backed standby,
pinned cache window, play-intent buffering state, rAF ticker only while active,
boundary-only store emissions, equal-power join fallback, and watchdog recovery.

### Web reader and focus

**Files:** `ui/reader/ReaderScreen.tsx`, `focus/*`, selector rail, return button,
progressive window helpers.

**Current responsibility.** DOM reader assembly, progressive ayah mounting,
focus geometry and scrolling, follow interruption, reflow correction, search
and jump selection, rail interaction, player chrome, and root overlay.

**Complexity.** Very high in `ReaderScreen`; high but coherent in the focus
controller. React effects and refs bridge imperative scroll/paint work with
boundary-only store state.

**Simplify safely.** Mirror the Android conceptual state holders, implemented
as focused hooks: `useReaderSearch`, `useReaderJump`, `useReaderFollow`, and
`useReaderLayoutRecovery`. Each hook should own its effects and expose a small
event/value interface. Keep the focus controller as the sole `scrollTop` writer.
Move player chrome and rail assembly into components only after callback
identities remain stable. Test hooks with fake focus/player ports and preserve
the existing pure focus/math tests.

**Preserve.** Materialize targets before measuring, content-space geometry,
forced visibility during focus, no per-frame React updates, hand-gesture-only
follow pause, continuous home scroll, and tall-verse word band correction.

### Web renderers and ink animation

**Files:** `render/*`, `ui/reader/InkEngine.ts`, `WordHighlight.ts`,
`theme/Fade.ts`.

**Current responsibility.** Arabic/English/Hafs rendering, word hold/tap,
paper-cover and glyph washes, repeat/search overlays, basmalah animation,
bookmark ribbon, and memoized ayah boundaries.

**Complexity.** Very high. `WordUnit.tsx` (427 lines) and `HafsWord.tsx` (221)
still carry parallel portions of ink lifecycle. Their duplicated gesture and
paper-cover reset behavior has been extracted. Imperative DOM paint is necessary
for performance but makes cleanup/cancellation critical.

**Simplify safely.** `useWordInteraction` and `clearPaperCover` are complete.
Next extract `useActiveInkWash`, `useRepeatWash`, and `useSearchFlash` with
explicit element refs and cleanup contracts. Share secondary-paint policy only
where the render modes truly match. Keep Arabic and English base-paint
strategies separate: forced abstraction here can reintroduce dirty overlapping
marks. Replace the long
manual `AyahBlock` memo comparator with a named `AyahRenderKey` only if profiling
confirms stable behavior; never drop memoization casually.

**Preserve.** `useLayoutEffect` for progress-zero before paint, no React state
per animation frame, cancellation on unmount/state exit, opaque Arabic glyphs,
and independent repeat/search overlay layers.

### Web UI surfaces and component kit

**Files:** `ui/home`, `ui/settings`, `ui/root`, `ui/entrance`, `ui/kit`.

**Current responsibility.** Browser equivalents of Android sheets plus
accessible Base UI-backed paper controls.

**Complexity.** Medium. Most components are feature-local. Entrance ornament
generation is algorithmically large but pure and tested. Root is reasonably
bounded, but settings is now 740 lines and mirrors much of Android's developer
brush/check lab state. Web brush helpers add another 959 lines across
`brushMark.ts` and `brushCheck.ts`; those helpers are separate and have focused
tests. *(Android has since matched that separation: the marks moved out of
`SettingsScreen.kt` into `ui/theme/BrushMarks.kt` — ~500 lines — and are now
shared with the reader's repeat sheet. Android still lacks the focused tests.)* `OrnamentsLab.tsx` adds a hash-selected
developer-only application surface outside the paper stack.

**Simplify safely.** Keep Base UI wrappers as the only place library APIs and
paper styling meet. Move repeated section/choice markup into the kit only after
two consumers have identical accessibility semantics. Keep generated ornament
geometry separate from React drawing and parity-test seeds with Android. Apply
the same finish-or-delete rule as Android to brush tuning transport; do not let
temporary clipboard/slider sessions remain permanent settings responsibilities.

### Web CSS and themes

**File:** `ui/theme/styles.css` (4,616 lines).

**Current responsibility.** Tokens, all three themes, paper stack, every
screen, responsive layout, render-state selectors, animations, reduced motion,
and browser fixes.

**Complexity.** Very high discoverability cost. The file grew by more than
1,300 lines since the first audit as bookmarks, root prose, settings parity,
brush tooling, and ornament labs landed. Selector ordering is an implicit
dependency graph, component ownership is not visible from the filesystem, and
short visual changes repeatedly touch the same global file. This is now a
maintenance problem even though the resulting production CSS still builds to
only about 70 kB before gzip.

**Simplify safely.** Split without changing cascade order:

```text
ui/theme/styles.css        imports only, in canonical order
ui/theme/tokens.css        colors, type, spacing, motion, themes
ui/theme/base.css          reset, app shell, accessibility/reduced motion
ui/theme/paper-stack.css
ui/home/home.css
ui/reader/reader.css
ui/reader/ink.css
ui/playback/playback.css
ui/settings/settings.css
ui/lab/labs.css
ui/root/root-viewer.css
ui/entrance/entrance.css
```

First perform a move-only split and compare production CSS/order. Then replace
repeated literal colors/timings with semantic custom properties. Add comments
for intentional cross-component selectors and a small visual smoke matrix for
three themes, narrow/wide viewports, playing/paused, and reduced motion.

**Preserve.** Selector order, paper-metaphor restrictions, solid-paper edge
fades, CSS-driven global recess, and accessibility focus/keyboard states.

### PWA, assets, and service worker

**Files:** `public/sw.js`, manifest/icons/fonts, `swRegistration.ts`,
`assetUrl.ts`, `scripts/sync-data.mjs`, Vite config.

**Current responsibility.** Copy the canonical DB, resolve Pages base paths,
cache immutable shell/data assets after successful boot, and keep navigation
network-only so deployments cannot pin stale HTML.

**Complexity.** Medium operational complexity. Cache version bumps and asset
path rules are manual but intentionally conservative.

**Simplify safely.** Put cache name and documented policy together, generate a
build asset manifest where practical, and test navigation-vs-asset strategy as
pure request classification. Keep registration gated on successful DB boot.

**Preserve.** Never cache `index.html` navigation, include both sql.js WASM
filenames, and copy rather than fork the canonical DB.

## Data pipeline, tooling, and delivery

### Canonical database builder

**File:** `tools/build_db.py` (972 lines).

**Current responsibility.** Fetch/caches five source families, normalizes text,
aligns QCF visual and canonical words, parses quran-align and QDC timings,
cleans repeat artifacts, applies committed overrides, imports QAC morphology,
defines schema/indexes, validates coverage/counts, and atomically writes the DB.

**Complexity.** Very high and correctness-critical. Source-specific heuristics
and schema orchestration share one namespace. Validation is mostly embedded in
the main build path, making isolated fixture tests difficult.

**Simplify safely.** Convert to a standard-library-only package:

```text
tools/build_db.py                 CLI and orchestration only
tools/quran_db/fetch.py           atomic cache/download/archive helpers
tools/quran_db/text.py            text, WBW, page/QCF loading + alignment
tools/quran_db/timings.py         quran-align/QDC load, cleanup, adjustment
tools/quran_db/morphology.py      QAC parsing and Buckwalter conversion
tools/quran_db/overrides.py       committed patch validation/application
tools/quran_db/schema.py          DDL and all writes/indexes
tools/quran_db/validate.py        explicit build report and fatal thresholds
tools/quran_db/tests/fixtures/    small frozen upstream fragments
```

Have each loader return typed dataclasses plus diagnostics instead of mutating
shared lists/stats. `main` assembles a `BuildReport` and decides which warnings
are fatal. Test QCF many-to-many alignment, basmalah adjustment, real versus
false repeats, coverage threshold, malformed overrides, morphology mismatch,
and deterministic schema output. Keep network integration out of ordinary unit
tests; test fetch caching with local responses/files.

**Preserve.** Build-time-only repair, canonical Uthmani segmentation, atomic
cache writes, logged known mismatches, repeat cleanup fixpoint, overrides last,
coverage failure, committed output, and runtime DB version bump.

### Artwork and font tooling

**Files:** `tools/artwork/*`, `scripts/fetch_qcf_v2_fonts.sh`.

**Current responsibility.** Convert sourced SVG artwork to Android vector data
and acquire/package QCF page fonts.

**Complexity.** Medium because generated assets have licensing/provenance and
format constraints.

**Simplify safely.** Keep these as explicit one-purpose tools. Add `--check`
or checksum/manifest modes so CI can verify generated outputs without rewriting
them. Record source URL, license, hash, and generation command beside assets.

### Android setup/run/release scripts

**Files:** `scripts/*.sh`.

**Current responsibility.** Environment setup, SDK/emulator provisioning,
build/install/run, QEMU fallback experiments, font fetch, and signed bundle
verification.

**Complexity.** Medium and platform-specific. Setup and run scripts inevitably
contain detection/fallback branches; the QEMU script is the largest and most
specialized.

**Simplify safely.** Treat `android_env.sh` as the shared source of environment
truth and remove duplicated SDK/JDK discovery from callers over time. Separate
supported emulator flow from experimental QEMU flow in documentation. Add
`bash -n`/ShellCheck in CI and a `--check` mode for release prerequisites.
Do not make normal builds regenerate the database.

### CI and release workflows

**Files:** `.github/workflows/build.yml`, `web.yml`.

**Current responsibility.** Verify DB presence, run Android tests, publish an
APK on master, run Vitest/typecheck/build, and deploy the combined marketing
site/web reader to Pages.

**Complexity.** Low to medium. Android and web intentionally have different
delivery paths, but shared assumptions (Node/JDK versions and canonical DB)
are repeated.

**Simplify safely.** Keep workflows separate for clear failure ownership. Add
named reusable composite steps only after a third workflow needs them. Cache
Gradle/npm downloads, validate shell/Python syntax, and add a cheap SQLite
contract check. Ensure release jobs consume artifacts from already-tested build
jobs rather than rebuilding differently.

### Tests

**Current shape.** 29 Android JVM test files (195 passing tests) and 42 web test
files (265 passing tests) cover pure engines, search, focus math, timing parsing,
persistence helpers, database loading, prefetch/fades/watchdog, reader policy,
ornaments, brush geometry, and stack math. The web production type-check/build
also passes. A connected-device baseline-profile suite exists separately and
cannot run in the ordinary JVM gate.

Coverage quality is uneven by design but the gap is widening: pure policies
are well protected, while the largest mutable coordinators remain mostly
verified indirectly. The global and foreground AppFunctions, MediaLibrary
callback, shortcut publication, `PaperStackApp`, settings lab sessions, and
watchdog/failed-join `PlayerController` sequences still have little or no
focused coverage. Pause-during-load, retired-element events, readiness, and
superseded standby preparation now have an initial event harness. There is
still no visual Arabic-shaping gate. These are higher-value gaps than raising
raw test count around already-pure helpers.

**Simplify safely.** Tests should make future decomposition safer, not freeze
implementation details. Prioritize:

1. Android OS-action contract tests: AppFunctions commands, MediaLibrary
   search/paging/queue resolution, and foreground parameter decoding;
2. extend web player event-sequence tests to watchdog recovery and failed joins;
3. shared cross-platform policy fixtures;
4. Android reader jump/follow tests with fake player/focus ports;
5. Timings Lab reducer tests;
6. canonical DB query/schema contract tests for both clients;
7. a small manual or automated screenshot matrix for Arabic shaping and paper
   states;
8. performance traces for boundary-only renders and scroll-frame behavior.

Avoid broad snapshot tests of entire screens. They create churn while missing
timing, shaping, and event-order failures.

### Documentation

**Files:** `AGENTS.md`, `README.md`, `docs/*`, `web/README.md`, `PLAN.md`.

**Current responsibility.** Architecture and invariants are unusually well
documented. Feature docs capture hard-won timing/rendering traps. `PLAN.md` is
historical; feature docs are current contracts.

**Complexity.** Medium information architecture: behavior is sometimes
described in architecture, performance, feature, and web-plan documents.

**Simplify safely.** Keep `ARCHITECTURE.md` as the map, this file as the
complexity/refactoring guide, and feature docs as behavior contracts. When a
feature changes, update one canonical detailed section and link to it elsewhere
instead of duplicating prose. Add review dates to audit documents. Move
completed review logs to an archive when they stop helping current work.

## Recommended execution plan

These are intentionally small, independently reviewable changes.

### Phase 0: establish safety rails

1. Add pure contract tests around the new Android OS-action surface: chapter
   search/paging/request resolution, command validation, and foreground
   parameter decoding.
2. Add fake ports/event harnesses for web audio and reader focus/playback.
3. Add canonical DB contract checks and an automated DB-version assertion.
4. Add shared JSON fixtures for Highlight, Clock, Ink, Focus, basmalah, search,
   brush constants, and ornament seeds; run them from Android and web tests.

### Phase 1: finish experiments and make ownership visible

1. Audit the brush/check tuning and ornament-lab surfaces. Delete shipped-out
   experiments; move any retained lab wholesale out of production settings.
2. Split web CSS while preserving import/cascade order.
3. Extract Android `PaperStackState` and a single overlay host/state model
   without changing the hand-rolled navigation behavior.
4. Split Android reader renderers/decorations by responsibility.
5. Split timing-lab screen sections.
6. Split `build_db.py` into package modules without changing output; compare
   database schema, counts, hashes or a deterministic SQL dump as appropriate.

### Phase 2: make state machines explicit

1. Extend the extracted web transport/join event harness to watchdog and
   failed-join sequences; extract `PlaylistSession` only if playlist policy grows.
2. Introduce a shared Android command gateway only if OS action tests reveal
   real validation/execution duplication; keep every protocol adapter thin.
3. Extract Android/web reader search, jump, and follow sessions.
4. Extract pure `TimingEditorState` and commands.
5. Extract the web paper coordinator if its store commands continue to grow.

### Phase 3: reduce cross-platform drift

1. Align conceptual command/state names in both implementations.
2. Run parity fixtures in CI.
3. Keep platform-specific rendering/audio behind adapters and document intended
   differences (Media3 versus HTML audio, Compose shaping versus browser paint).

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

## Coverage checklist

This audit covers every repository area listed in `AGENTS.md`:

- Android application/build/composition root;
- data models, SQLite extraction, repositories, settings, bookmarks;
- pure domain engines and search;
- Media3 service/controller/prefetch/catalog, deep links, shortcuts, Assistant,
  and global/foreground AppFunctions;
- paper stack, entrance, home, reader, focus, renderers, root viewer, settings,
  theme/ornaments, Ornaments Lab, and Timings Lab;
- web bootstrap, database, repository, domain policy, store, player, renderers,
  all UI sheets, CSS/theme, PWA/service worker, and assets;
- canonical database builder, timing overrides, artwork/font tools;
- Android environment/run/release scripts;
- Android and web CI/release workflows;
- Android JVM, connected-device baseline-profile/macrobenchmark, and web
  Vitest/type-check/build suites;
- repository documentation.

Binary assets, fonts, audio samples, icons, and the SQLite bytes are understood
through their generation/copy/consumption paths rather than line-by-line source
review. Their provenance and validation belong with the corresponding tooling.
