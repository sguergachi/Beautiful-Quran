# Android deep code review — 10-agent Flash fleet

**Date:** 2026-08-10
**Tree:** `c81156a8` (`t3code/deep-code-review-agents`, master tip at review time)
**Scope:** Full Android app (`app/`), 10 parallel read-only deep reviews, one concern per agent
**Model:** `opencode-go/deepseek-v4-flash` via `opencode run` (OpenCode Go provider)
**Method:** 10 independent `opencode` processes, each given a narrowly scoped area, the repo's product laws from `AGENTS.md`, and an instruction to verify every finding against the code (incl. `git log -S` for guards). Agents 05/08/09 died silently mid-read on the first launch (known Flash flakiness) and were relaunched; all 10 completed.

| # | Area | Verdict (condensed) | Full report |
|---|---|---|---|
| 01 | data/ — SQLite wrapper, repos, models, fingerprints | Clean; one real defect: dead index (`idx_morph_ayah`), rest P3 hygiene | [below](#1-data-layer) |
| 02 | domain/ — HighlightEngine | Correct pure engine, no sync bug; Lab poll re-prepares repeat tables every tick (P2) | [below](#2-highlight-engine) |
| 03 | ink wash engine + reader policy files | Ship-quality; stale `TAJWEED_PACING.md` live-curve claim, FF consumed-flag leak (P2) | [below](#3-ink-wash-engine) |
| 04 | playback/ — PlayerController, service, prefetch | Gate-based serialization defuses the old race; **P1 unhandled connect-failure crash**, stale state after `stop()`, out-of-gate monitor mutations | [below](#4-playback-layer) |
| 05 | ReaderScreen/ReaderComponents hot path | No correctness bug; three composition-scope leaks (whole-screen per-frame recompose while scrolling; ayah item recomposes per frame during repeat wash/glint) | [below](#5-reader-screen--components) |
| 06 | MainActivity shell, entrance, home | Narrow edge cases: assistant-action blank-screen race at cold start, non-virtualized home list | [below](#6-app-shell-entrance-home) |
| 07 | settings, theme, Ink Lab | No confirmed bug; Ink Lab per-tick full-snapshot persistence (historical dirty-save class), integer log-slider quantization | [below](#7-settings-theme-ink-lab) |
| 08 | timingslab editor | Architecturally strong; record/commit state machine has silent data-loss paths (debounce-clear, partial-pass commit, repeat inversion) — near-zero test coverage | [below](#8-timings-lab) |
| 09 | VoiceEnergy / Tarji DSP + output latency | Strongest subsystem; both flagged P2s refuted; torn multi-field publish, `release()`/audio-thread race, untested glue | [below](#9-voice-dsp--output-latency) |
| 10 | tests, invariants, CI, Gradle | Excellent pure-logic test culture; **lint fully disabled in CI** (doc claim self-contradictory), R8 release only validated post-merge, untested glue | [below](#10-tests-invariants-ci) |

---

## Consensus verdict

**B+ / strong B.** No reviewer found a reproducible correctness bug in the pure cores (HighlightEngine, InkEngine, Tarji) — those are uniformly praised and well-pinned by tests. The risks concentrate, as in the 2026-07-22 audit, in the **compositions**: PlayerController's machines, ReaderViewModel/ReaderScreen glue, and now a new theme — **performance/scoping** (recomposition leaks) and **silent data-loss paths in the Timings Lab's state machine**.

## Highest-priority findings (cross-area, deduped)

| Sev | Finding | Agent |
|---|---|---|
| P1 | **Unhandled crash when MediaController connection fails** — `buildAsync().await()` with no `try/catch`/`CoroutineExceptionHandler` in `PlayerController.ensureController`; every public command funnels through it (PlayerController.kt:101-122, 311-324) | 04 |
| P2 | **Lint is completely disabled in CI** — `checkReleaseBuilds = false` disables `lintVitalRelease`, CI runs neither lint task; `AGENTS.md:106-108`'s "real gate" claim is self-contradictory (app/build.gradle.kts:91-96, build.yml:34-61) | 10 |
| P2 | **Whole reader recomposes every scroll frame** — `ReaderScreen.kt:323-326` reads `firstVisibleItemScrollOffset` (a per-frame `MutableIntState`) in composition, violating the codebase's own PERFORMANCE.md §4c rule | 05 |
| P2 | **Active ayah recomposes per frame during repeat wash / glint dry-down** — animated `State<Float>`s read in `AyahBlock` composition body (ReaderComponents.kt:1323-1330, 1378) | 05 |
| P2 | **Ink Lab auto-saves a full ~60-field snapshot per slider tick on main** — the exact dirty-write class of the historical "saved ~0.03" bug (InkLabPanel.kt:853-856 → InkEngine.kt:362-365 → InkLabStore.kt) | 07 |
| P2 | **Timings Lab: debounced save fires mid-record and clears the override**; partial tap passes commit as whole-ayah overrides; repeat marks can be slid before their first-pass twin (inverting backtrack encoding) (TimingsLabViewModel.kt:515, 392-407, 497-504) | 08 |
| P2 | **`word_morphology` root lookups full-scan 77k rows** while `idx_morph_ayah` (build_db.py:2047) is a dead prefix of the PK; needs `idx_morph_root(root)` + DB bump (QuranRepository.kt:372) | 01 |
| P2 | **Multi-field volatile publish in VoiceEnergy is a torn-frame contract** (3 independent stores/loads → 20 ms phase skew); single immutable `TarjiFrame` reference fixes it (VoiceEnergy.kt:197-201, ReaderComponents.kt:1144-1149) | 09 |
| P2 | **Repeat-boundary monitor mutates the player outside the gate** — pause at the loop seam can be undone by the poll loop's `seekTo`+`play()` (PlayerController.kt:405-445) | 04 |
| P2 | **R8 release build is the least-verified artifact** — runs only on master; `proguard-rules.pro` referenced but absent; `testReleaseUnitTest` never runs (app/build.gradle.kts:57, build.yml:55-61) | 10 |

## Thematically recurring

- **Composition-scope discipline** (05, 03): animated/dynamic state must be read in draw phase only; `InkMotion` identity instability defeats word-level skipping (§2 doc drift).
- **Untested glue** (10, 08, 09, 04): the pure leaves are well pinned; the ViewModels' wiring (chapter install, Lab record machine, PlayerController loops, VoiceEnergy feed) is not.
- **Docs drift** (03, 05, 10): `TAJWEED_PACING.md` claims live curve tracking (code freezes it), `PERFORMANCE.md` §2 overstates skipping, `AGENTS.md` overstates the lint gate. The repo treats docs as load-bearing — each risks a future "fix" that re-introduces a regression.

---

# Per-area reports

## 1. Data layer

### Summary

The data layer is unusually clean: raw SQLite used deliberately (no Room), every cursor is closed via `.use`, all queries are parameter-bound (no injection surface), all reads are wrapped in `Dispatchers.IO` off the main thread, the bundled DBs are opened strictly `OPEN_READONLY`, and the fingerprint/version-bump invariant is enforced by both a test and explicit Gradle input declarations. No P1 bugs found. The main actionable defects are index layout (a full-scan on `word_morphology` for root lookups while a dead prefix-index exists) and a few P3 hygiene/concurrency nits in the Timing Lab store.

### Issues

**P2 — `QuranRepository.rootSummary` full-scans `word_morphology`; `idx_morph_ayah` is dead weight.**
`app/src/main/java/com/beautifulquran/data/QuranRepository.kt:372` (renderings query) filters `WHERE m.root = ?` against a table whose PK is `(surah_id, ayah_number, position)` and whose only secondary index `idx_morph_ayah(surah_id, ayah_number)` (built in `tools/build_db.py:2047`) is a strict prefix of that PK — SQLite never uses it. Verified with `EXPLAIN QUERY PLAN`: `SCAN m` over all 77,424 rows + temp B-tree for GROUP BY, per Root Viewer open (~6 ms SSD desktop, realistically 15–30 ms on a phone). Fix: replace `idx_morph_ayah` with `CREATE INDEX idx_morph_root ON word_morphology(root)` in `build_db.py` (plus the mandatory `DB_FILE_NAME` bump and `data/quran.db.sha256` update — the fingerprint test will police it).

**P3 — Four of the five secondary indexes are redundant with PK prefixes.**
`idx_timings(reciter_id, surah_id)`, `idx_root_occ(root)`, `idx_words_ayah(surah_id, ayah_number)` (and `idx_morph_ayah`, see above) each duplicate the leftmost columns of their table's composite PK (`build_db.py:1814-1878, 2047-2050, 2267-2268`). Harmless but pure APK bloat on a read-only shipped DB; the Root Viewer work (issue above) is the natural moment to consolidate. No correctness risk, hence P3.

**P3 — Cross-thread read-modify-write on `TimingOverrides._overrides` can lose an update.**
`app/src/main/java/com/beautifulquran/timingslab/TimingOverrides.kt:106-120` does `_overrides.value.toMutableMap().apply { put/remove }` — a non-atomic RMW. `QuranRepository.timings()` performs a *reader-side migration* `set()` on `Dispatchers.IO` (QuranRepository.kt:472) while the Lab's `persistNow()` writes on `viewModelScope` (Main). Interleaving can drop a concurrent `put`, and since `clockVersions[key]` is bumped in both paths, a lost migration write is never retried (that ayah keeps the stale row but is marked migrated). Developer-tool-only, low likelihood; a `synchronized`/mutex around the RMW would close it.

**P3 — Synchronous file I/O on the main thread in the overrides store.**
`TimingOverrides` constructor runs `load()` → `file.readText()` in `QuranApp.onCreate` (QuranApp.kt:58), and the Lab's `persistNow()` → `set()` → `json.encodeToString` + `writeText` + `renameTo` runs on the main dispatcher (TimingsLabViewModel.kt:582-592). Small files, debounced, but each is a disk stall on main. Worth an explicit `Dispatchers.IO` hop for the write path.

**P3 — `QuranDatabase` extraction has no integrity check and failure surfaces as an uncaught crash.**
`QuranDatabase.kt:22-43`: the 27 MB copy is not length/hash-verified after `copyTo`, so a silent short write would pass `renameTo` and later fail at query time. Unlike `LexiconDatabase`/`DictionaryDatabase`, which degrade to "no entry" via `runCatching` + nullable `db` (LexiconDatabase.kt:21-29), a `quran.db` open/extraction failure propagates an uncaught `SQLiteException` through every `suspend` repository call into whatever screen requested it. The app can't function without it, so a crash is arguably correct — but a legible error/retry would be kinder than a stack trace from a coroutine.

**P3 — `wordSearchIndex` retains ~15–25 MB for the process lifetime.**
`QuranRepository.kt:288-335` builds and pins all 77,429 entries (with shared ayah contexts, which is a good optimization) on the first home-sheet search. Intentional and documented; the alternative (query-per-search) would cost more. Just be aware this is a permanent heap resident once any search runs.

### Test gaps

- **No query regression test against the committed DBs.** `DatabaseFingerprintTest` only hashes files; a schema/column change that breaks a repository query (e.g., a renamed column in `build_db.py`) passes CI and fails only at runtime on a device. An in-memory SQLite test that runs the real SQL strings over `data/quran.db` (read-only, JVM `sqlite-jdbc` or `org.sqlite`) would close this — worth it given the pipeline/app split.
- **`rootSummary` aggregation is untested end-to-end.** `LemmaGloss.pick` is unit-tested, but the GROUP BY + `groupBy { lemma to pos }` + frequency-sort path (QuranRepository.kt:372-411) has no test against real morphology rows.
- **`bookmarkedAyahs` chunked row-value `IN` batching** (QuranRepository.kt:191-229) is untested — the `(a,b) IN ((?,?),(?,?))` syntax is Android-SQLite-specific and worth locking down.
- **`TimingOverrides` persistence round-trip** (serialize → `parseFile` → load) has no unit test, despite being the on-device patch store.
- `surahContent`'s words→`Ayah` grouping mapping and `shiftToBundledClock`'s median/tie-break logic are only covered indirectly (via `AudioOnsetTest`).

### Verdict

No P1s; a disciplined, minimal, well-guarded data layer whose only real defect is an index on the wrong column — the rest is P3 hygiene and missing SQL-level regression coverage.

## 2. Highlight engine

### Summary

`HighlightEngine` is small, genuinely pure (only imports `data/model/Segment`), and correct on its documented input contract: the upper-bound binary search, the repeat/high-water tables, and the chain walk-back all check out under hand-tracing (including the subtle case where a chain's final word returns to high-water, and where a walk-back must stop at new material). The pipeline (`build_db.py` clamps/sorts/validates starts) and the Lab's `withDerivedEnds` guarantee the sorted, non-overlapping, strictly-increasing-start shape the engine assumes. No sync-correctness bug found; the real issues are a hot-path invariant violated in the Timings Lab, doc drift about allocations, and unenforced robustness preconditions.

### Issues

**P2 — Timings Lab rebuilds the repeat tables every 33 ms poll tick, on the exact path the engine warns against.** `TimingsLabViewModel.kt:147-148` calls the convenience `HighlightEngine.activeInfo(it, pos)`, which per `HighlightEngine.kt:120-121` calls `PreparedTimings.prepare` on every call — the KDoc there says this is "fine for tests / one-shots", and the reader correctly holds a `PreparedTimings` (ReaderViewModel.kt:179-187). The Lab is precisely the surface where users build long repeat chains, and `prepare`'s walk-back (`HighlightEngine.kt:86-92`) is worst-case quadratic in chain length, so a long recorded re-say makes the poll O(n²) + two `IntArray` allocations per tick at 30 Hz. Fix: cache a `PreparedTimings` in Lab state and rebuild only on edit (the reader already demonstrates the pattern); the active-word poll then becomes the same binary-search + O(1) path.

**P3 — "Allocates nothing on the hot path" is documented but false.** `docs/HIGHLIGHT_ENGINE.md:143-148` ("the 33 ms poll … allocates nothing until a word boundary") and `ReaderViewModel.kt:178` overstate the design. Each tick still allocates an `ActiveInfo` (`PreparedTimings.activeInfo`, HighlightEngine.kt:55-73) and an `ActiveWord` (ReaderViewModel.kt:388-404), then `distinctUntilChanged` (ReaderViewModel.kt:242) discards duplicates. The original commit (127b7aa0) intended only "no *IntArray* allocation," which is true. ~60 small objects/s is negligible, so fix the wording — or make it true by returning the index and constructing the enriched info only when the index changes.

**P3 — The sorted/non-overlapping input precondition is unenforced and untested, and the failure mode is silent.** `activeIndex` (HighlightEngine.kt:125-138) assumes strictly increasing `startMs`: duplicate starts make the search return the *later* equal-start segment, giving the earlier one a zero-length hold (it never lights); unsorted starts return a wrong index; `prepare` stores the list unchecked. Today the pipeline (`build_db.py:1156,1570`; clamp at 1161-1164) and the Lab's `withDerivedEnds` guarantee the shape, so this is latent — but it's the one place a future caller could break karaoke without a crash. A cheap validation in `prepare` (or a documented contract + test) would pin it.

**P3 — `holdEndMs.coerceAtLeast(seg.startMs)` (HighlightEngine.kt:68) doesn't defend the overlap case.** With `seg[i].endMs > seg[i+1].startMs`, `holdEndMs` (next start) is *shorter* than the voiced span, so the sweep truncates and the boundary belongs to the later word — the coerce only guards the degenerate `holdEnd < start` side. Unreachable with clean data (pipeline clamps; Lab derives ends), so worth a comment or explicit invariant rather than a fix.

### Test gaps

- **No full-timeline sweep test.** The tests sample isolated points; a property test iterating every ms (or every 10 ms) across `0..last.endMs` asserting the exact lit-word sequence (`1,1,…,2,2,…,null`) would pin binary-search + boundary correctness exhaustively and double as the `PreparedTimings`/convenience equality check (the current one at HighlightEngineTest.kt:170-181 samples only 6 points).
- **No degenerate-shape tests**: single-segment list, duplicate starts, unsorted starts, overlapping segments — each of the P3 shapes above is unreachable in clean data and so has no regression net if a guard is ever added.
- **No repeat-chain adjacency test**: the walk-back's hardest case — a first chain whose last element returns to high-water, immediately followed by a second independent chain (`1,2,3,2,3,4,5,4,5`) — must prove `repeatStart` is 4 (not 2). Hand-traced correct; it deserves a pin.
- **No test that a chain truncated by the ayah end behaves**: `1,2,3,2,3` at `>= last.endMs` must go null while the final re-say is `isRepeat` with `highWater 3`.
- **`holdEndMs` at a repeat-chain boundary** (sweep cut for the *first* pass of a word whose next segment is a re-say) is untested — the existing `hold end` test (HighlightEngineTest.kt:156-168) only uses a plain non-repeat row.

### Verdict

Correct, well-tested pure engine on its documented contract — no sync bug found; the actionable items are the Lab's per-tick `prepare` (P2) and closing the untested degenerate-input gaps (P3).

## 3. Ink wash engine

### Summary

This is a mature, unusually well-engineered ink engine. The four-state word policy is pure and JVM-tested; the no-reset law is pinned by dedicated tests (`WashResetTest`, `InkEngineTest`); the entry-mask (`applied` MutableState + `SideEffect`) correctly handles the once-per-word recompose constraint; and the wasl/repeat/wasl-handoff continuity math is self-consistent and monotone. I found no P1. The main risks are a stale authoritative doc that contradicts the shipped freeze behavior, one mid-word lab retune that bypasses the entry-snapshot freeze, and a small fast-forward state leak.

### Issues

**P2 — `docs/TAJWEED_PACING.md:335-337` claims the pacing curve is tracked live; the code freezes it**
The doc says *"the curve itself is tracked live (`rememberUpdatedState`), so every Ink Lab knob reshapes the word already on screen instead of waiting for the next activation."* The code does the opposite: `rememberLetterSweep` (ReaderComponents.kt:683-835) captures the curve in `lockedPacing` at Active entry (line 768) and releases it only after the residual finishes (809-810); there is no `rememberUpdatedState` for the curve, and `INK_ENGINE.md:507-517` documents the freeze as the shipped rule ("retuning tajweed or speed mid-word cannot remap a half-finished wash — which read as 'resetting and playing again'"). Because this repo treats docs as load-bearing, the next editor "fixing" the code to match this stale line would re-introduce the exact mid-wash remap regression that the Arm/capture design exists to prevent. Fix: rewrite those two lines to describe the entry-snapshot freeze.

**P3 — `ReaderViewModel.kt:680-712` — FF midpoint-consumed flag leaks across a re-seek into the same ayah**
`longAyahMidpointConsumed` is set by `nextConsumedAyah` and cleared only in `commitPrepared` (591) and the basmalah branch (687). `noteInkRestart` (808-812), which `playFromWord`/`playFromAyah`/`fastBackward` all call, never resets it. So: FF to the midpoint of a long ayah (flag=N) → user taps a word before the midpoint → FF again → `action()` (FastForwardPolicy.kt:36-44) sees `midpointConsumedForAyah == ayah` and skips the midpoint, jumping straight to the next ayah even though the playhead is pre-midpoint. Intent-based consumption is right for the async double-tap case; it should be cleared when a manual seek lands before the midpoint.

**P3 — `ReaderComponents.kt:1591-1662` — per-frame 9-stop colour lists + Brush per bloom**
`buildShapedBlooms` rebuilds the whole bloom list every frame of a wash (documented trade-off), and inside the draw phase each `InkReveal` (267-272) and `ColorReveal` (330-335) re-derives `paperColors`/`washColors` (`stops.map` = 9 `Color` allocations) plus a `Brush.horizontalGradient` per bloom. On 2:282 that is ~127×9 allocations/frame. The colour list depends only on `(restingAlpha, rtl)` — hoistable to one shared per-frame (or per-modifier) array so the draw only allocates the brush. This is the documented "temporary bloom lists" headroom (PERFORMANCE.md:262), but it is a cheap, self-contained win.

**P3 — `ReaderComponents.kt:877-890` — the wasl bloom edge is not frozen like the main sweep**
`entryPrefixStart`/`entryCompletion` are `remember(identity, activation, waslPrefixMs[, waslHandoff])`, so an Ink Lab change to `waslPrefixMs`/`waslHandoff` mid-donor-tail remaps the in-flight wasl bloom — the same "remap a half-finished wash" the main sweep's entry snapshot exists to prevent. The window is normally ~120 ms but a long waqf donor gives the tail up to ~1.17 s (18 % of 6505 ms), making the remap visible if a lab slider is moved during it. Developer-only path, but it breaks the documented invariant (INK_ENGINE.md:515-517 "the wasl edge is latched the same way"). Fix: latch prefixStart/completion inside the tracker, keyed only on `(identity, activation)`.

**P3 — `ReaderComponents.kt:2415,2437` — O(n) scans on every AyahBlock recomposition**
`ayah.words.indexOfFirst { it.position == aw.wordPosition }` and `inks.indexOfFirst { it.state == Active }` re-scan per recomposition (2-3×/s). Positions are contiguous 1..n, so a position→index map (or a running index from `activeWord.wordPosition`) would make both O(1). Negligible in absolute terms; not worth touching unless AyahBlock recomposition cost is being trimmed.

### Test gaps

- **Wash primitives vs. cross-mode fidelity:** `InkWashAlphaTest` pins `inkWashAlpha`, but nothing asserts the *shaped* adapter's paper alpha equals `1 − glyphAlpha` from the word-unit `letterFadeIn` (the gloss/Hafs/English consistency contract). A drift between the two paint adapters would violate "same product" without a red test.
- **No integration test for repeat chain → `OrderedWashGate` → activation:** `repeatWashAction`, the gate ordering, and seek-into-chain re-wash are each tested in isolation (`InkEngineTest`, `OrderedWashGateTest`), but nothing exercises the full path (chain entry queues every member, N+1 waits on N, mid-chain seek re-queues one position) with real activations.
- **`OrderedWashGate` cancellation contract:** no test for pump cancellation → `done.await()` callers being cancelled (the `CancellationException` path at OrderedWashGate.kt:74-79), nor for re-entrancy safety.
- **HighlightClock settle convergence:** `WashResetTest` pins monotonicity through settle, but not the forced-convergence cap (`SETTLE_CAP_POLLS`) nor the settle "clamp forward gap to `believableStepMs`" branch that protects fast-forward (HighlightClock.kt:107-111).
- **The FF consumed-flag leak (P3 above) has no test** — `FastForwardPolicyTest` covers consumed-by-intent but not the re-seek-into-same-ayah case.
- **No test pins `Curve.at(1f) == 1f`/`at(0f) == 0f` exactly** as the residual-release safety (the identity-at-1 that makes dropping the captured curve at 809 invisible).
- **Wasl edge retune** (P3 above) and the deprecated `displayedSweepProgress(entryPending, …)` overload have no dedicated coverage.

### Verdict

Exceptionally disciplined pure-policy ink engine with the no-reset and wash-fidelity laws genuinely pinned by tests — ship-quality; fix the stale `TAJWEED_PACING.md` live-curve claim and the FF consumed-flag leak, then consider the wasl-edge freeze and per-frame allocation nits.

## 4. Playback layer

### Summary
The playback layer is well-engineered for its risk class: the fire-and-forget concern flagged by the prior audit is substantially mitigated by `PlayerCommandGate` (epoch + mutex), which I verified correctly handles stop-vs-pending-play and latest-play-wins, including the mid-connect invalidation case, and is covered by real JVM tests. Media3 1.10.1 was checked at bytecode level: all `MediaController` mutations are **silent no-ops when disconnected** (never throw), so "commands after release" cannot crash — they silently vanish instead. Remaining risks: an unhandled-exception crash on controller-connection failure, stale `PlayerUiState` after `stop()`, and the repeat-boundary monitor mutating the player *outside* the gate (a genuine pause-at-seam race). The service side (cache, prefetch, focus, becoming-noisy, foreground) is clean.

### Issues

**P1 — Unhandled exceptions in `withController`/`ensureController` crash the process.** `PlayerController.kt:311-324` + `:101-122`. `MediaController.Builder.buildAsync().await()` can complete exceptionally (session never bound, service teardown race, `onGetSession` returning null); there is no `try/catch` or `CoroutineExceptionHandler`, and the scope is a bare `SupervisorJob() + Dispatchers.Main` (`:54`). The exception escapes `runIfCurrent`'s `mutex.withLock` body (mutex is released, so no deadlock, but the exception is uncaught) → app crash. Every public command funnels through this path. Fix direction: wrap `ensureController()` + `block(c)` in `runCatching`, surface failure via `_state.error`, or install a `CoroutineExceptionHandler` on the scope.

**P2 — `stop()` leaves `PlayerUiState` stale synchronously.** `PlayerController.kt:386-400`. `stop()` resets `repeatRange` but never touches `isPlaying`/`nowPlaying`/`isConnected`; it relies on the async `STATE_IDLE` event round-trip through `onEvents` (`:125-134`) to clear them. A consumer reading `player.state.value` immediately after `player.stop()` (e.g. `HomeViewModel.dismissFloatingPlayback`, `HomeViewModel.kt:242`) sees `isPlaying=true` + the old ayah for a beat — the exact state-reporting race the audit called out, though it self-heals once the event lands. Fix direction: set `_state.value = PlayerUiState()` (or at least `isPlaying=false`, `nowPlaying=null`) synchronously in `stop()`.

**P2 — Repeat-boundary monitor mutates the player outside the gate; pause at the loop seam gets undone.** `PlayerController.kt:405-445`. The poll loop calls `c.seekTo(firstIdx, 0L); c.play()` directly, bypassing `withController`/epoch. Interleaving on the main looper: iteration reads `c.isPlaying == true` (`:419`), suspends at `delay(16)` (`:420`); a user `pause()` runs during that window; the loop resumes and — without re-checking `isPlaying` or any epoch — fires `seekTo` + `play()`, restarting the ayah the user just paused. The seek/play also isn't ordered against `stop()`'s `clearMediaItems()`, so a stop at the seam can produce a brief unsolicited audio blip. `loopRangeIfNeeded`/`loopSingleAyahIfNeeded` (`:186-222`) share the same outside-the-gate mutation, though their `onEvents` drive is serialized with commands on main, so they're lower risk. Fix direction: route monitor mutations through `withController` (capture epoch, re-check `isPlaying` inside the gate) or cancel the job with the gate held; re-check `repeatRange`/`isPlaying` after the poll delay.

**P3 — `AssistantAudioResume` also plays outside the gate.** `AssistantAudioResume.kt:23-32`. `player.play()` on the 750 ms debounce runnable can override a *manual* user pause made during the assistant window. Guarded (requires `!player.playWhenReady`, non-IDLE/ENDED, confirmed focus loss), so it's narrow; but it's the same "mutation not epoch-checked" class. Consider adding an epoch/`isPlaying` check at fire time.

**P3 — Public `PlayerController` methods have an undocumented main-thread affinity.** `playSurah`/`setRepeatRange` write non-volatile fields `repeatRange`, `basmalahLeadIn`, `repeatEndPositionMs` (`:70-79`) that the main-thread listener reads in `syncFromController`/`playlistIndex`. Today all callers are main-bound (UI, Timings Lab, ForegroundAppFunctions use `activity.mainExecutor`), but the `AppFunctionService` path (`QuranAppFunctions.kt:298`) is the one non-obvious entry point — worth a KDoc note or `@Volatile` on those fields if an off-main call is ever introduced.

**Not findings** (verified, deliberately excluded): post-release MediaController calls are silent no-ops in 1.10.1 (bytecode-confirmed `isPlayerCommandAvailable` → `return`), not crashes — the epoch gate plus no-op semantics already defuse the "after release" class; `TIME_UNSET` positions are absorbed by `OutputLatency.heardMs`'s `coerceAtLeast(0)` + the poll's coherence gate; prefetch keys match the player's (default URI `CacheKeyFactory`); `onDestroy` teardown order is safe.

### Test gaps
`PlayerCommandGateTest` and `MediaIdTest` are genuinely good. Missing coverage:
- `recitationQueue` index math (basmalah lead-in `startIndex` when `startWithBasmalah`), `RecitationMedia.kt` — pure and trivially testable.
- `loopSingleAyahIfNeeded` / `loopRangeIfNeeded` wrap logic (boundary, `endedLoopHandledIndex` dedup, seek-back index arithmetic) — the logic is pure-enough to extract and test.
- `startRepeatBoundaryMonitor` seam behavior, especially the pause-then-resume race (extract the "should seek back" decision as a pure function like the gate was).
- `syncFromController` state mirroring and the `stop()` stale-state window.
- `AudioPrefetcher` `readAhead` index math (`drop(currentIndex + 2)`) and `warmSurah` metered-network gating.
- No test pins down the contract that MediaController mutations are no-ops while disconnected (integration-level; document rather than test).

### Verdict
Solid, gate-based command serialization that meaningfully defuses the flagged race class; the sharpest remaining defects are the unhandled connect-failure crash, `stop()`'s stale state, and the boundary monitor's out-of-gate mutations — all fixable in a small, testable pass.

## 5. Reader screen & components

### Summary

The reader is an unusually disciplined piece of Compose work: word-sync policy lives in pure, heavily-tested engines (`InkEngine`, `HighlightEngine`, `FocusEngine`, `ReaderInteraction`); animations are overwhelmingly draw-phase (`letterFadeIn`, `shapedWordBloom`, `graphicsLayer`); polling is confined to a `WhileSubscribed` flow chain that publishes only boundary-rate changes; and per-ayah `derivedStateOf` successfully confines word-boundary recomposition to one list item. The risks I found are all about composition reads leaking back into the hot path that the codebase's own `PERFORMANCE.md` rules forbid, plus one design-law ripple deviation — none are correctness bugs I could verify.

### Issues

**P2 — Whole reader recomposes every scroll frame (`ReaderScreen.kt:323-326`)**
`LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, sheenFollowScroll)` — both key expressions are snapshot reads taken in the `ReaderScreen` composition. `firstVisibleItemScrollOffset` is a plain `MutableIntState` updated from the measure result, so reading it in composition invalidates the whole screen on every scroll frame. This directly violates the codebase's own rule in `docs/PERFORMANCE.md` §4c ("reading `layoutInfo` in plain composition would recompose the whole reader while scrolling") — the focus controller is carefully wrapped in `derivedStateOf`, yet this effect reads the equivalent state raw. The body re-executes (~2600 lines of remembers + ~30 `LaunchedEffect` key evaluations + small allocations per frame), and during a chapter-edge pull the `nextPullRubberPx`/`nextChapterPull` reads at `ReaderScreen.kt:1840-1843` add a second per-frame composition channel. Item bodies are protected by LazyLayout slot reuse, so the damage is bounded — but it is exactly the "butter smoothness" class the docs promise never to ship. Fix: hoist `scrollSheenValue()` into a `remember(listState) { derivedStateOf { ... } }` and key the effect on that, or run `snapshotFlow { listState.firstVisibleItemScrollOffset }` inside the effect.

**P2 — Animated wash states read in composition: the active ayah item recomposes at frame rate during repeats and glint dry-down (`ReaderComponents.kt:1323-1330, 1378`)**
`orangeWash`/`showRepeatLayer`/`showGlintLayer` read `State<Float>`s that animate (repeat wash reveal + 900 ms fade-out; the 1 s glint dry-down in `rememberGlintAlpha`, lines 469-481). These are read in the `HighlightLayeredText` composition body, i.e. the whole `AyahBlock` item subscribes and is invalidated per animation frame. Because every `WordUnit` of the ayah lives in that same item scope, the *entire verse* (up to 21 words on 2:282) recomposes per frame for the duration of any repeat wash (all themes) and for ~1 s after every word in Nightfall/Royal Green (2 of 3 themes). This contradicts the app's headline technique #1 ("a running fade recomposes and re-lays-out nothing") and compounds the P3 below. Fix direction: decide the membership booleans from the raw `State<Float>`s inside the draw modifiers (`repeatInkLayer`, `glyphLayerAlpha`) rather than in the body, e.g. pass the wash/glint `State` and let the draw lambda branch on `.value > 0f`; or gate the whole `if` on a draw-phase-only read.

**P3 — `WordUnit` children never skip; doc claim in `PERFORMANCE.md` §2 is inaccurate (`ReaderComponents.kt:988, 1181-1234`)**
`rememberInkMotions` allocates a fresh `ArrayList<InkMotion>` (new object identities) every time the `AyahBlock` recomposes, and `InkMotion` is a plain class with no `equals`. Even if the compiler inferred it stable, identity comparison defeats skipping — so on every word boundary the whole active ayah's `WordUnit`s re-execute their bodies, not "only the two words whose state changed" as §2 claims. The remember'd sweeps/washes preserve the actual animation state, and the item is still confined to one ayah, so this is bounded — but it is the floor cost of both P2s. Fix: give `InkMotion` structural equality (data class) or `remember` each wrapper keyed on the stable states it wraps.

**P3 — `activeAyah` read at screen scope contradicts §2's confinement claim (`ReaderScreen.kt:444-446`)**
`val activeAyah = if (isThisSurahPlaying) activeAyahState.value else null` reads the flow in the screen scope, so the whole reader recomposes at every ayah boundary (~2×/s during recitation), not just the two blocks whose focus bit flips. Boundary-rate, so it is cheap; the doc overstates. The per-item `derivedStateOf` at `ReaderScreen.kt:2059` correctly confines word-level changes, but only because `activeWordState` (line 237) is deliberately not delegated. Same minor doc drift applies to `bookmarkedAyahs` (line 239, delegated — a bookmark toggle recomposes the whole screen, despite the "read per-ayah" comment at 240-242).

**P3 — Transport buttons leak Material ripple, violating invariant #4 (`PlayerBar.kt:101-186`, `ReaderScreen.kt:1134-1210`)**
Everything on the paper uses `quietClickable` (`indication = null`, `theme/Interaction.kt:31-54`), but the reader's `IconButton`/`TextButton` (repeat, rewind, play, forward, speed, reciter, and the top-bar back/search) are Material3 components with the default ripple indication. `DESIGN.md` §Motion and AGENTS.md invariant #4 say "no Material ripple anywhere." If the paper metaphor is law, these should pass a null indication or move to quiet clickables (same pattern as `home/FloatingPlaybackControl`).

**P3 — Active word's `WordUnit` recomposes per scroll frame while following (`ReaderComponents.kt:1253-1254`)**
`wordCoordinates` is read in the `LaunchedEffect` keys and written by `onGloballyPositioned`, which fires every frame the tracked word moves — so during word-band follow the single active `WordUnit` recomposes at frame rate. Bounded to one word, but it can be made a pure `snapshotFlow` watch to avoid the composition invalidation entirely.

### Test gaps

- **ViewModel polling flow**: only `pollingIdentity` is tested (`ReaderPollingTest.kt`). Nothing covers the actual `pollingWhileLoaded` emission behavior — boundary-rate dedup, the `coherent` live-item skip, one-shot `forcedHighlight` consumption, `inkActivation` bumping on a >250 ms backward clock jump, or paused→resume immediate sampling. These are the sync engine's correctness core and are currently verified only by hand.
- **Composition-read regressions** (P2-1/P2-2/P3-1): nothing enforces "animated state read only in draw phase." A Macrobenchmark recomposition-count assertion (e.g. Compose's `CompositionTracer`/recomposition counter while a repeat wash runs, or while scrolling) would have caught all three. `PERFORMANCE.md` documents profiles as future work but nothing measures recomposition scope today.
- **Small pure helpers untested**: `wordFadeAlpha` (`ReaderComponents.kt:169`), `buildShapedBlooms` cover/feather arithmetic, `SurahSearchState.activeQuery` trimming. Minor, but they are the exact cheap JVM targets the repo's own convention prefers.
- **`FadeLead`/`RepeatSheet`** wiring is well tested; **`AyahBlock`'s per-ayah derived-state keying** is untestable without instrumentation and is the highest-risk untested surface (it is the mechanism every §2 claim rests on).

### Verdict

Excellent, test-disciplined sync engine and a correctly confined per-ayah recomposition design, held back by three composition-scope leaks that violate the codebase's own draw-phase-only rule — fixable in small, low-risk steps, no correctness bugs found.

## 6. App shell, entrance, home

### Summary
A disciplined, high-quality shell. The paper stack, saveable-state threading, back handling, and entrance ceremony are carefully designed and the pure decision functions (`filterSurahs`, `parseAyahReference`, `shouldShowFloatingPlayback`, `CoverFrameGeometry`) are properly unit-tested. Most risks are narrow edge cases in the ceremony/assistant handoff and a structural non-virtualized home list — no P1 found.

### Issues

**P2 — Assistant action / deep link can blank the screen at cold start** (`MainActivity.kt:173-175`, `:192`, `:198`). `entranceDone` flips true from `LaunchedEffect(assistantAction)`, which unmounts `EntranceCover`; but `stackMounted` is only ever set by the cover's `onWarmStack`/`onFinished` (`EntranceCover.kt:268-272`, two frames in). If an action lands within that first ~2-frame window of a cold start that was *not* itself a deep link, `stackMounted` stays false forever and neither branch renders — a permanent blank background. Fix: `stackMounted = stackMounted || entranceDone`. Related UX wart: an action mid-ceremony cuts the cover with no exit animation or hinge sound (functional, jarring).

**P2 — Home chapter list is one LazyColumn item: no virtualization or keys** (`HomeScreen.kt:272` `item(key = "chapter-page")`, `:314-322` all 114 `SurahRow`s, `:332-357` word sections in the same block). The whole document — masthead, continue row, saved-passages row, every surah row, and every expanded word section (up to `WORD_SEARCH_MAX_HITS` = 400) — is always composed and laid out regardless of viewport. Each `uiState`/`playerState` change (e.g. an ayah transition while the float is up) recomposes the entire block; a broad word search makes the single item several hundred rows tall, all always composed. Suggest `item { masthead }` + `items(surahs, key = { it.id })` + `items` per section (search dismiss already keys off `firstVisibleItemIndex`/offset, so it survives).

**P3 — Cover-open sound can be silently dropped on a fast skip** (`PageTurnSounds.kt:166` returns when `sampleId !in loadedSamples`, `:175-180`; fired from `EntranceCover.kt:298`). On a very early tap, the SoundPool async load may not have completed, so the hinge opens silent — the ceremony's one audio cue vanishes on exactly the path (skip) where it matters most. Also the drop stem lands at 820 ms while the hinge runs 1,150 ms — the "heavy board" landing slightly precedes the swing's end.

**P3 — Two SoundPools, and the cover's is held for the whole session** (`MainActivity.kt:193-196` releases only on activity destroy, `:219/:229-233` cover pool, `:357` a second `PageTurnSounds` for the stack). The cover's 9-sample pool stays alive after the ceremony forever. Trivial memory; could release in `onFinished`.

**P3 — `selectedStartPlayback` is a saveable one-shot that is never cleared** (`MainActivity.kt:290`, set `:446`, consumed `ReaderScreen.kt:341`). After an Assistant "play" it stays true, so a later process-death restore re-seeds `ReaderInteraction` with `playbackRequested = true` even though nothing is playing. Harmless today (`ReaderInteraction.kt:71-74` only uses it to disambiguate a paused-selection jump) but a latent trap if `initialState` changes.

**P3 — Drag detector keyed on `selectedSurahId` can strand the stack mid-gesture** (`MainActivity.kt:625`, `:1040-1139`). `pointerInput(gestureKey)` is rekeyed on navigation identity; if a jump/Assistant action changes `selectedSurahId` while a horizontal turn is in flight, the coroutine is cancelled before the post-loop `onSettle` (`:1136`), leaving `stackPosition` at a non-integer value. Self-heals when the triggering action's own `animateTo` runs, so low impact.

### Test gaps
- Paper-stack layer-coercion and back-order logic (`settleTo` bounds, min/max layer, the two `BackHandler`s, overlay precedence) — a pure enough decision layer with zero tests.
- `fulfillAssistantAction` routing table (action → layer/load/animate) — untested.
- Home ribbon-unfurl state machine (`bookmarkCount` vs `previousBookmarkCount` + `coverSheetVisible` gating, `HomeScreen.kt:172-187`) — untested.
- Entrance ceremony: the phase/skip machine and `openFade` curve (`EntranceCover.kt:501`) are pure and untested; only `CoverFrameGeometry` has coverage.
- `PlayerController.loopRangeIfNeeded` / repeat-boundary topology — only `PlayerCommandGate` and `parseMediaId` are covered.

### Verdict
A carefully engineered app shell with only narrow, low-probability edge cases to harden — the entrance/assistant blank-window race and the single-item home list being the two worth addressing.

## 7. Settings, theme, Ink Lab

### Summary

The settings/theme/Ink-Lab slice is unusually clean: settings flow to every consumer, the paper metaphor is upheld (no dialogs/cards/ripple), enum persistence is migration-safe, and the pure logic that matters (nudge, log-slider mapping, snapshot wire format, engine apply/reset) is unit-tested. I found no confirmed functional bug; the highest risks are the Ink Lab's per-tick full-snapshot persistence on the main thread (the class that produced the historical "saved ~0.03" bug), an integer log-slider thumb-quantization artifact, and a panel tab that resets itself whenever the contextual-guide visibility flickers. `SettingsRepository`'s SharedPreferences write path itself is untested.

### Issues

- **P2 — Ink Lab auto-saves a full ~60-field JSON snapshot on *every* slider tick, synchronously on the main thread.** `InkLabPanel.kt:853-856` (`onValueChange`) → `InkEngine.kt:221-226` (`tuning` setter) → `persistLab()` at `InkEngine.kt:362-365` → `captureLabSnapshot()` + kotlinx `encodeToString` + `putString`/`apply` (`InkLabStore.kt:33-35, 232-236`). A drag produces ~60–120 such serializations/sec while a recitation with PCM mirror + highlight polling is live — the exact dirty-write class that previously persisted a stale `~0.03` depth. The current capture reads live engine state (stale capture is gone), but the write amplification remains and can jank the audition. Fix direction: debounce persistence (trailing ~200 ms) or write through only on slider release / panel collapse; keep the in-memory `tuning` live per tick.
- **P2 — `SettingsRepository.update()` writes all 19 keys (incl. `lastSurah`/`lastAyah`) on every change, and the text-size drag calls it per pixel.** `SettingsRepository.kt:158-182` + `SettingsScreen.kt:1402-1405` (`detectHorizontalDragGestures { setFromX(...) }` → `onScale` → `update`). Not a lost-write (main-thread serialized, transform reads fresh `_settings.value`), but it's the same amplification the repo already fixed for playback (`updateListeningPosition`, `SettingsRepository.kt:128-142`). Fix direction: route the text-size drag through a narrow `updateFontScale` setter, or dedupe inside `update` when the transform is a single-field `copy`.
- **P2 — Integer log-sliders quantize the thumb against the finger.** `InkLabPanel.kt:851-857` computes `position` from the *rounded* engine value, so a drag at position `t` writes `round(raw)` and the controlled `value` snaps the thumb back to `position(round(raw)) ≠ t` on the next frame — the thumb visibly rubber-bands up to half a stop, and the value at release can sit one step below intent. Data stays internally consistent; feel is degraded while auditioning. Fix direction: track drag position locally during `onValueChange` and only remap to `position(raw)` when not actively dragging (e.g. remember `isDragging`), or keep a transient unrounded local while dragging.
- **P3 — `InkLabPanel` tab resets every time `guideActive` flips.** `InkLabPanel.kt:83-85` keys `remember` on `guideActive`, so when the bookmark/ayah-rail lesson appears (you were on Highlight or Repeat) the panel jumps to Guide, and when it dismisses it jumps back to Ink — you lose the tab mid-tuning. Fix direction: key only the initial value (`remember { ... }`) and set the Guide tab via a `SideEffect`/effect only on rising edge.
- **P3 — Ink Lab is a floating rounded "card".** `InkLabPanel.kt:110-114`: `clip(RoundedCornerShape(16.dp)).background(…0.96f)` — a bottom-end floating container is the one shape DESIGN.md reserves for exceptions ("no cards", `docs/DESIGN.md:22-35` lists only `FloatingPaperControl` ornaments + ink bleeds). It's developer-only and documented in GLIMMER/INK_ENGINE, so likely accepted — worth a one-line exemption note in DESIGN.md so it doesn't become a precedent.
- **P3 — `InkLabSnapshot.schema` is stored but never validated.** `InkLabStore.kt:53, 165-166`; `decode` (`:235-236`) ignores it. Additive field evolution is safe via defaults, but a breaking wire change would silently misparse. Fix direction: on `schema > SCHEMA`, return null (fall through to shipped defaults) or run a migration.
- **P3 — Brush-lab preset vs custom label drift.** `SettingsScreen.kt:607` shows `brushCircleParams(settings.brushCircleStyle).label` ("Hairline", …) even after a slider drag has stamped every `Spec` with `label = "Custom"` (`SettingsScreen.kt:1061-1134`). The mark is custom but the header still names the preset. Fix direction: derive the header from a "is-dirty" flag on `brushParams`.
- **P3 — `ThemeColorPreview` SYSTEM-mode gilt uses a magic luminance test.** `SettingsScreen.kt:1443-1446` (`c.red + c.green + c.blue < 1.5f`) duplicates the theme-mode luminance decision already made in `themePreviewColors`/`BeautifulQuranTheme`. Fix direction: have `themePreviewColors` also return the accent set (or a `isDark` flag) so the gilt pick is token-derived, not re-derived.

### Test gaps

- **`SettingsRepository` itself is untested** (only `enumForOrdinal`, defaults, and copy semantics via `DeveloperModeSettingsTest`). `update()` full-write, `updateListeningPosition` no-op dedup, `rearmEducation`, and especially the `homeBookmarkStyle` v2 migration (`SettingsRepository.kt:91-98`, incl. the ordinal-==-3 legacy branch) have zero coverage. Needs Robolectric or an extracted pure mapping.
- **Text-size drag arithmetic duplicates `nudgeFontScale` untested.** `SettingsScreen.kt:1380-1384` (`setFromX`) inlines the same stop math `nudgeFontScaleTest` covers — extract a `fontScaleFromFraction(f)` pure function, reuse it in both, and test it.
- **Reset's store side is untested.** `InkLabStoreTest` covers `resetLabToShippedDefaults`'s engine behavior but not that `InkLabStore.clear()` actually removes the key (store wiring needs a device).
- **Full snapshot-default lock missing.** `shippedDefaults_match…` (`InkLabStoreTest.kt:19-40`) asserts a subset of fields; a field-by-field `InkLabSnapshot() == capture-from-fresh-engine` (and `toTuning()` round trip for all 60) would pin drift across future tarjīʿ/glint knobs.
- **Integer log-slider quantization** (P2 above) has no coverage — a property test asserting `position(raw) == thumb position` during drag would have caught it.

### Verdict

Highly disciplined, well-tested subsystem with no confirmed functional bug — the one thing I'd fix before anything else is the Ink Lab's per-tick full-snapshot persistence (debounce it), since that is the live remainder of the historical dirty-save class.

## 8. Timings Lab

### Summary
Architecturally strong and well-disciplined: the DB stays read-only, `HighlightEngine` remains pure and drives the live preview, the override store is single-file/atomic with a clock-versioned fusion point in `QuranRepository.timings()`, and the export path is correctly a *fixture-not-override* contract (the exported body even tells maintainers to fix classes systematically). The editor's *state machine* is where it gets fragile: a debounced save can fire mid-record and clear the ayah's override, partial record passes are committed without any coverage guard, repeat marks can be slid in front of their first-pass twin (inverting the backtrack encoding), and the preview can diverge from what actually persists (onset floor / whole-row shift applied on read but not shown live). Almost none of this is unit-tested.

### Issues

**P2 — Debounced save fires during a record pass and silently clears the override**
- `TimingsLabViewModel.kt:515` (`nudgeSelected` → `persistDebounced()`), `:574-580` (600 ms `saveJob`), `:320-340` (`startRecord` never cancels it), `:588-589` (`persistNow`: `passes.isEmpty()` → `overrides.clear(key)`).
- If a user nudges a word and hits **Re-sync** within 600 ms, the pending save executes mid-record with `passes = emptyList()` and removes the stored correction. If the pass is then cancelled (0 taps → backup restored only in memory, `finishRecord` at `:396-399` leaves `edited` untouched) and the app is killed before the next persist, the correction is gone. If the app is killed mid-pass, the last nudge is gone.
- Fix: cancel `saveJob` and `persistNow()` the `recordBackup` *before* entering RECORD, or make `persistNow` a no-op while `mode == RECORD`.

**P2 — A record pass commits even when it covers almost no words**
- `TimingsLabViewModel.kt:392-407` (`finishRecord` persists whatever `recordMarks` holds), committed via several paths that do not require the audio to end: the **Done** pill (`TimingsLabScreen.kt:692`), the close chevron / back → `onExit` (`:614-620`), ‹/› stepping (`changeTarget:180`), and rotation-orphan re-open (see lifecycle below).
- A 3-of-20-word tap pass becomes the *whole ayah's* override; `HighlightEngine.activeIndex` (`HighlightEngine.kt:125-138`) returns null after the last mark's end, so the rest of the verse renders unhighlighted. "0 taps = cancel" vs "1 tap = commit" is also inconsistent with what a user hitting Done early intends.
- Fix: require the pass to reach the last word or the audio end to auto-commit; otherwise warn/confirm. Add an explicit discard path that restores `recordBackup`.

**P2 — Repeat marks can be slid before their first-pass occurrence, inverting the backtrack encoding**
- `TimingsLabViewModel.kt:497-504`: repeat marks "float free" with `coerceIn(0L, maxStart)` and no floor at their first-pass sibling. Drag one left past the sibling and, after the by-`startMs` sort, the earlier copy becomes index 0 → `runningMax` sees the *same* position later → the real first-pass mark is now classified `isRepeat` by both `repeatFlags` (`TimingsLabScreen.kt:115-122`) and `HighlightEngine.prepare` (`HighlightEngine.kt:76-105`). Gold/orange attribution silently flips, and the exported patch carries a topology the pipeline would read as a gap-phantom class bug.
- Fix: clamp a repeat's `newStart` to ≥ its earliest sibling's `startMs` (or block the drag past it).

**P2 — The preview is not what persists: onset floor / whole-row shift diverges WYSIWYG**
- `TimingsLabViewModel.kt:588-589` persists raw `st.passes`; `QuranRepository.timings()` (`QuranRepository.kt:464-470`) applies `holdOpeningBehind` (`:65-81`) on read, which **uniformly shifts the whole row** when both word 1 and 2 predate the voice floor (codified in `AudioOnsetTest.kt:196-218`). The Lab's automatic post-save replay (`finishRecord → seekTo(0)`, `:406`) verifies the *raw* marks, so the user judges something the reader and a fresh Lab open will not show.
- This contradicts the Lab's one design rule ("the real fade … exactly as the reader will show it"). Fix: preview the aligned result (apply the same `alignToAudioClock` the repository will) or persist the aligned row so preview == served. (Same root drives the exported patch for current rows containing the raw marks, which won't byte-match what the device serves.)

**P2 — Synchronous, unguarded disk I/O on the main thread in the override store**
- `TimingOverrides.kt:128-146` (`persist`: `tmp.writeText` + `renameTo`); every `persistNow` caller in the ViewModel runs on main (`:404`, `:477`, `:515`/`:578`, `:538`, `:552`, `:617`, `:626`). An `IOException` (disk full, permission) propagates uncaught to the main thread (crash), and because `persist` throws *before* `_overrides.value = next` (`:112`), memory and disk desync.
- Fix: move the write to `Dispatchers.IO`, catch-and-log, and update the `StateFlow` regardless of write outcome (or reconcile).

**P3 — Non-atomic read-modify-write across threads**
- `TimingOverrides.set`/`clear` (`:106-120`) mutate `_overrides.value.toMutableMap()` without a lock. The repository's one-time migration write-back runs on `Dispatchers.IO` (`QuranRepository.kt:471-473`) concurrently with main-thread Lab writes for a different ayah; an interleaved read-then-write can drop an entry from both the file and the `StateFlow`. Narrow (migration is once per legacy key) but a mutex around `set/clear` is a one-line fix.

**P3 — `persist` ignores `renameTo`'s result and never fsyncs**
- `TimingOverrides.kt:145`: a failed rename silently keeps the old file while memory claims the new state; no `FileOutputStream.fsync()` before the rename means a crash can leave a torn file. `load()`'s `runCatching` would then silently empty the store. `renameTo`'s Boolean should be checked (fall back to a retry / direct write).

**P3 — Per-word "Reset" can splice a different-clock mark into the row**
- `defaultPasses` is the raw bundled row (`QuranRepository.kt:437-441`) while `passes` is the fused/floor-clamped row; `resetSelectedWordToDefault` (`TimingsLabViewModel.kt:468-479`) splices the unfloored default in, and `canReset` (`TimingsLabScreen.kt:490-492`) is always true when the fused row was uniformly shifted. Result: a mixed-clock word that sits audibly off. Edge case (requires an onset + early bundled row) but silent.

**P3 — Rotation orphans a live RECORD pass that is later committed**
- `labVisible` is `remember`, not `rememberSaveable` (`MainActivity.kt:305`), so rotation closes the Lab while the activity-scoped ViewModel keeps `mode = RECORD` and `recordMarks`. Re-opening the Lab calls `changeTarget` (`MainActivity.kt:397`), which `finishRecord()`s (`TimingsLabViewModel.kt:180`) the partial pass — this is the concrete mechanism behind the coverage issue above. Even without rotation, `onExit` (`:614-620`) commits a partial pass when the user just closes the sheet mid-recording.

**P3 — Timeline tap hit radius makes scrubbing near a mark impossible**
- `TimingsLabScreen.kt:606-609`: `hitPx = w * 0.05f` — 5% of the strip. On a 30 s ayah that's ~1.5 s of "select the nearest mark" territory, so taps intended to scrub frequently select instead. Also, the timeline remains tappable for scrubbing during RECORD (zoom is 0), which is undocumented and an easy accidental seek while tap-along is in progress.

### Test gaps
- **`TimingsLabViewModel` has zero tests** despite six-plus behavioral commits (undo/rewind `:366-379`, nudge clamps `:489-517`, repeat free-float, addRepeat `:523-540`, deleteSelected `:542-553`, finishRecord `:392-407`, debounced save `:574-592`). These are exactly the pure-ish, decision-heavy paths the repo's own conventions demand be extracted and tested (`withDerivedEnds` `:558-570` is a prime candidate).
- **`TimingOverrides` file round-trip** is untested: parse/persist, corrupt/truncated file handling, `renameTo` failure, the concurrent `set` read-modify-write, and the migration write-back idempotence (the *clock* math is well covered by `AudioOnsetTest`, the *store* is not).
- **`TimingsPatchExporter` output shape** is untested against the `tools/timing_overrides/` contract (`README.md:49-67`) — a drift here would ship un-appliable patches to maintainers.
- **Preview-vs-persisted divergence** (raw taps vs `holdOpeningBehind` result) has no test; `alignToAudioClock` is tested, but "what the Lab shows vs what `timings()` serves after save" is not — the exact class of bug that would ship bad word-1 boundaries silently.

### Verdict
Thoughtful, systematic design with the right invariants intact, but the Lab's record/commit state machine contains several silent data-loss and mis-encode paths that its near-zero unit-test coverage lets through; fix the debounce-clear, partial-pass commit, and repeat-inversion issues before anything else.

## 9. Voice DSP & output latency

### Summary

High overall quality — this is the strongest subsystem in the repo. `Tarji` is a genuinely pure, content-time-invariant DSP core (no Android deps), clocked in exact 19.955 ms hops, with a relative-clock backlog design that is mathematically sound, and it is pinned by both synthetic waves and two real 8 kHz recorded fixtures with tight phase-lock/build/release assertions. Both flagged P2 residuals are **refuted** in the current tree: `eventStartContentMs` never existed (git `-S` empty), and Sonic latency is correctly accounted in *both* delay branches. Remaining risks are cross-thread publish consistency, one real `stop()` race with the audio thread, and untested glue (decimator, route mapping, reader wiring).

### Issues

**P2 — Multi-field volatile publish leaves a torn-frame contract (the surviving core of the flagged residual).** `VoiceEnergy.analyzeHop()` writes `reverberating`, `tremolo`, `tremoloGain` as three *independent* volatile stores (VoiceEnergy.kt:197–201), and the draw path reads them in three separate loads — `shimmerGain` (→ `tremoloGain`), then `reverberating`, then `tremolo` (ReaderComponents.kt:1144–1149). A frame can observe hop N+1's `reverberating` with hop N's `tremolo`/`tremoloGain` (a one-hop, 20 ms phase skew for one vsync). The class comment promises "no locks, no composition" but nothing enforces the snapshot. Impact is sub-perceptual, but it is the exact hazard the earlier review flagged, and it is cheap to fix: publish one immutable `TarjiFrame(reverberating, tremolo, tremoloGain, holdMs, rateHz)` in a single volatile write and let the draw path read one reference.

**P2 — `release()` races the audio thread over unsynchronized `Tarji` state.** `PlayerController.stop()` and `onDisconnected` call `voiceEnergy.release()` (PlayerController.kt:389, :111) on the main thread; `release()` runs `tarji.reset()` (VoiceEnergy.kt:218) while the audio thread can be mid-`onHop()`/`analyzeHop()`. `Tarji`'s fields (`frame`, `env`, `work`, `corrs`, hop counters, envelope arrays) are plain and non-volatile, so this is a genuine data race on shared mutable arrays. The outcome is benign in practice (a few garbage hops before the sink tears down, and `isLive` collapses within 350 ms), but it's the one place the subsystem's careful audio-thread isolation is violated. Fix: have `release()` only clear the volatile mirrors and defer `tarji.reset()` to the audio thread's `resetTapSession()` (already called on `onFlush`/`onReset`).

**P3 — `VoiceTapAudioProcessor.sink` has no memory barrier.** `sink` is a plain field written by the renderer-factory thread (VoiceTapAudioProcessor.kt:27–29, from PlaybackService.kt:134) and read on the audio thread (line 53). Almost certainly benign (the sink exists before any `queueInput`), but it is the same unsynchronized-field pattern this subsystem otherwise rejects. Mark `@Volatile`.

**P3 — Ink Lab "Ear delay ms" is treated as content-time, so it mis-scales at non-1×.** `earDelayMs` is folded into the `sonicContentMs` pool (VoiceEnergy.kt:177) and later divided by speed in the diagnostic (VoiceEnergy.kt:183), while `routeMs` is correctly wall-scaled (`routeMs * speed`, Tarji.kt:1021). A wall-time nudge added as content-time applies `earDelayMs / speed` of wall delay — ±33% at the 0.75×/1.5× speed extremes. Harmless at the shipped 1× default, but the Ink Lab contract ("Ear delay ms", GLIMMER.md) reads as a wall-time device nudge. Fix: split wall-time (`earDelay`) from content-time (`sonic`) in the `earDelayHops` signature, or document it as content-time.

**P3 — `TYPE_HEARING_AID` misclassified as A2DP; `TYPE_BLE_HEARING_AID` unmapped.** AudioOutputLatency.kt:55–57 maps ASHA hearing aids (LE-band, ~20–80 ms) to the 180 ms classic-A2DP preset, and API 34+ `TYPE_BLE_HEARING_AID` isn't in the table at all — an only-hearing-aid route therefore classifies LOCAL (0 ms). Wrong preset in both directions for that niche. `kindForType` also has **no unit test**.

**P3 — Fixed pitch band excludes speed-shifted voices.** `MIN/MAX_PITCH_HZ = 70/350` (Tarji.kt:895–896) is in content time. At 0.75×, a low closer (~70–100 Hz) drops to 52–75 Hz — the low edge exits the band and hold identity (and therefore tarji) is silently lost on exactly the verses where it matters most; at 1.5×, falsetto (300 Hz+) exits the top. Detection cadence is speed-invariant, but the pitch band is not. A speed-scaled band (`band / speed`) would close it.

### Test gaps

- **No test for `VoiceEnergy` itself** (the only reference in `app/src/test` is a comment): the decimator (`onPcm16` → `decimStep`/`hopSamples`), 48 kHz, stereo/multi-channel, non-divisor rates, the fallback-vs-measured selection in `analyzeHop`, and `earDelayTotalMs` are all untested glue.
- **No `AudioOutputLatency` test** — the entire `kindForType` route-mapping table is unpinned.
- **Reader wiring untested**: the `isReady → capture → EMA → measuredBacklogContentMs` sequence, session/speed re-anchoring, and the fallback→measured handoff in the reader (ReaderViewModel.kt:294–326) have no test — only the pure `TarjiBacklogAnchor` does.
- **Only 8 kHz mono fixtures exist**; no 44.1 kHz stereo fixture through the real feed path, and no end-to-end `VoiceTapAudioProcessor` (BaseAudioProcessor `onConfigure`/`queueInput`/`onFlush` contract) test.
- No test that a Sonic/route/latency change mid-hold (delayHops jump) doesn't phase-snap the delayed readout beyond the EMA.

*(Verified/refuted: `earDelayHops` adds `sonicContentMs` unconditionally and `VoiceEnergy` passes `sonicMs` in both branches — the flagged "omits SONIC_LATENCY_MS at non-1× fallback" is refuted. The design is self-consistent precisely because the reader's backlog is a *relative* clock: its `estimate` (TarjiSyncClock.kt:23–28) can only track drift, so the constant Sonic content is invisible to it and must be added separately — the maintainers' reasoning in the docs is correct, and the only constant-downstream term is the track buffer, which the `sinkLatencyMs·speed` initial value does cover.)*

### Verdict

Strong, carefully-reasoned subsystem; the flagged P2s are refuted, but the torn multi-field publish, the `release()`/audio-thread race, and the untested decimator/route/reader glue are the real residual risks.

## 10. Tests, invariants, CI

### Summary

This is a genuinely strong test infrastructure: the product laws are pinned by behavior-focused JVM tests (HighlightEngine sync/repeats, InkEngine no-reset lifecycle, a 1,254-line DSP spec for tarjīʿ fed with real 8 kHz wavs, cross-platform knob locks against the web suite), the timing pipeline is locked by 53 patch-cases plus a whole-DB audit that also rejects committed overrides (all green here), and the fingerprint invariant is correctly wired as unit-test task inputs so it can't go stale. The honest gaps match the prior audit's own conclusion: the pure leaves and gates are well defended, but the *compositions* — PlayerController's loops, ReaderViewModel's chapter-install writers — and the release/CI path (lint fully disabled, R8 built only on master) are the weak edge. Most important risks: no lint gate at all despite a doc claiming one, and no feature-branch verification of the R8 release artifact.

### Issues

**P2 — Lint is completely disabled in CI; the documented "gate" does not exist.** `app/build.gradle.kts:91-96` sets `checkReleaseBuilds = false`, which means `lintVitalRelease` is *not* wired into `assembleRelease`. CI runs only `testDebugUnitTest` (`.github/workflows/build.yml:34-35`) and `assembleRelease` (`:55-61`) — neither executes any lint task. Yet `AGENTS.md:106-108` claims "The real gate is lintVitalRelease inside assembleRelease (checkReleaseBuilds = false)." That claim is self-contradictory: with `checkReleaseBuilds=false` there is no release lint gate, and the full `lintDebug` (which would surface the "~37 @UnstableApi errors") is never run either. Net effect: zero lint enforcement anywhere; a new fatal issue (security lint, `NewApi` misuse, missing permission) ships unnoticed. Fix direction: keep `checkReleaseBuilds = true` and dispose of the 37 opt-in errors properly — add `@OptIn(UnstableApi::class)` where missing (only `PlaybackService.kt:120` opts in today) or land a lint baseline — so everything *other* than Media3 opt-ins still gates release.

**P2 — The R8-minified release build is the least-verified artifact in the pipeline.** `assembleRelease` runs only on master (`.github/workflows/build.yml:55-61`), so feature branches never exercise minification/resource-shrink; a keep/reflection regression fails only *after* merge. Compounding: `proguard-rules.pro` is referenced at `app/build.gradle.kts:57` but is **absent from the repo**, so R8 runs with only the default `proguard-android-optimize.txt`; the app rides kotlinx.serialization, Media3, KSP-generated AppFunctions, and `xz` on default keep behavior. No unit test runs against the release variant (`testReleaseUnitTest` never runs), and nothing verifies the built APK is installable. The tradeoff is documented (`build.yml:37-40`) but worth tightening: at minimum run `assembleRelease` (or `lintVitalRelease` alone) on PRs, or a smoke test against the release build type.

**P2 — PlayerController composition still untested; the prior P1 race class is only half-addressed.** The prior audit's #1 finding ("stop() vs pending play") was fixed by extracting `PlayerCommandGate` and `PlayerCommandGateTest` pins the epoch contract well (including stop-during-connect). But `PlayerController` itself — the `loopSingleAyahIfNeeded`/`loopRangeIfNeeded` one-shot guards (`PlayerController.kt:186-222`), the 16 ms repeat-boundary monitor with its settle/guard constants (`:405-445`), and the `withController`+`ensureController` connect re-check (`:101-122`, `:311-324`) — has no test. A regression in `endedLoopHandledIndex` reset timing or `repeatBoundary` off-by-one would only be caught by ear. The gate is tested; the machine built on it is not.

**P2 — ReaderViewModel multi-path chapter install untested (prior P1, same story).** `ReaderSessionGateTest` pins the versioned-session contract, but the four writers that mutate shared state under it — `load()` (`ReaderViewModel.kt:508-543`), `installPrepared()` (`:579-585`), `onReciterChanged()` (`:604-631`), and the `timingOverridesChanged` collector (`:490-502`) — are untested. Nothing verifies the actual VM wiring: pending-play consumed exactly once, a stale materialize can't override a fresher load, reciter change re-installs timings only when the session is still current. The gate test proves the gate; it proves nothing about how the VM uses it.

**P2 — The app's SQL layer has zero coverage against the real committed schema.** `tools/test_build_db.py` validates `data/quran.db` in Python and `DatabaseFingerprintTest` hashes it, but no JVM test opens the DB with the app's queries: `testImplementation` is only junit + coroutines-test (`app/build.gradle.kts:192-193`), there's no sqlite-jdbc, no Robolectric, and no `androidTest` source set. `QuranDatabase`/`QuranRepository`/`Lexicon*`/`Dictionary*` are Context-backed and untested. A column rename that build_db.py and the app both adopt would pass every CI gate and crash at runtime. Given invariant #2 (app treats the DB as a clean read-only contract), a schema-contract test is the cheapest insurance there is — and it doubles as a catch for the "DB version bumped but schema drifted" class.

**P3 — Rolling "latest" release republishes the same `versionCode`.** `app/build.gradle.kts:27-28` holds `versionCode = 8` for every master push between versionName bumps, so sideloaders of the previous "latest" face equal-versionCode update semantics. Also, `.github/workflows/build.yml:80` hardcodes "Debug-signed APK" in the release notes even though `:41-51` can restore a store keystore — the two signatures "cannot update over each other" (per the gradle comment at `:62`), so a future keystore flip silently changes what the channel distributes. Suggest deriving versionCode per publish and computing the note from whether the keystore was restored.

**P3 — `test_build_db.py` built-in checks are and-chained booleans with opaque failures.** `check_confidence` (`tools/test_build_db.py:206-248`), `check_audio_onset_pipeline` (`:251-322`), `check_completion_pipeline` (`:325-385`), and `audit_bundled_db`'s `exact` chain collapse into single generic "FAIL" lines (`:602-611`) — `check_audio_onset_pipeline` chains ~15 independent behaviors (onset parse, duration_ms, Xing frames, retry ranges, evidence load, refit, trim). The file-driven patch cases report precise per-case diffs; the built-ins force manual bisection. Convert the sub-assertions to individual failure records.

### Test gaps

- **PlayerController** loop/repeat-boundary machines and the connect-epoch composition — a fake `MediaController`/coroutine-scope harness would close the audit's #1 item.
- **ReaderViewModel** chapter-install paths (load / installPrepared / onReciterChanged / overrides collector) driven against a fake repository + player.
- **PlaybackService / MediaController session wiring, AudioPrefetcher read-ahead policy, AssistantAudioResume, VoiceEnergy itself** (only the `Tarji` detector is exercised; VoiceEnergy's hop/decimation/backlog path is untested).
- **App SQL vs `data/quran.db` schema** (see P2 above) — an actual `SELECT`/`PRAGMA` contract test.
- **Release-variant verification** — no test runs against minified output; nothing catches a keep-rule or resource-shrink regression pre-merge.
- **Share/AppFunctions execute-and-register paths** — leaves tested (`AssistantActionTest` parses well); the orchestration the prior audit flagged remains uncovered.

### Verdict

Excellent pure-logic and pipeline test culture that genuinely pins the product laws; the remaining risk is the untested glue (PlayerController/ReaderViewModel compositions, SQL layer) and a CI path where lint is off and the R8 release build is only validated post-merge on master.
