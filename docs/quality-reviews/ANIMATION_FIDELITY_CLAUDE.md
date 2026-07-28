I reviewed the wash lifecycle end to end (`rememberRepeatWash`, `rememberLetterSweep`, glint layers, `OrderedWashGate`, the three renderers, and the ink tests) against AGENTS invariants 7/8 and the four docs. I did not run the test suite.

## Summary

The *steady-state* wash is genuinely at the bar: `monotonicWashProgress` + `washMayHardRestart` make a mid-animation rewind structurally impossible, the orange rides `letterFadeIn`/`ColorReveal` with the first-pass feather, and duration follows reciter dwell. The gap is not flash — it's **missing motion**. Two deliberate anti-flash fixes over-fired: collapsing `activation` to `0L` for non-Active chain members turns every genuine re-say into `Hold`, and dropping glint on repeat orphaned the whole `glintCarryAlpha` mechanism. In a multi-episode repeat (`7 8 9 7 8 9 …`, the exact 5:54 shape SYNC_FIDELITY calls out), the second utterance of word 7 renders **zero animation** — base layer is bypassed for repeat words, orange holds, glint is suppressed. That is a fidelity hole, not a polish nit.

Estimate: **~92%** of the bar.

## Scorecard

| # | Criterion | Verdict | Notes |
|---|---|---|---|
| 1 | Soft directional wash | **PASS** | `repeatInkLayer` (`ReaderComponents.kt:595`) = `glyphLayerAlpha` + `letterFadeIn(restingAlpha=0, feather=washFeather)`; Hafs uses `ShapedWordBloom.ColorReveal` with the same smootherstep profile (`Fade.kt:264`). No opacity-pop path in the karaoke/orange product path. |
| 2 | No mid-animation reset | **PASS** | `monotonicWashProgress` (`:294`) is a separate draw channel clamped to `max(peak, raw)` while `alpha ≥ 0.05`; `hardRestartToEmpty` (`:368`) forces alpha invisible before touching `clock`; `Reveal` order is empty→`snapTo(1)`→animate. Seek dissolves first (`:405`). |
| 3 | Orange = first-pass law | **PARTIAL** | Chain-advance Hold is correct (`:266–274`), gate ordering is correct. But re-say **inside** an open chain is also Hold (Issue 1), and the "Active starts immediately" bypass is keyed on `act != 0L`, not on being Active (Issue 3). |
| 4 | Duration fidelity | **PASS** | `repeatWashDurationMs = max(activeSweepMs, repeatSweepMs)` (`:324`), captured at entry before the gate wait, so handoff can't erase it. Test comment at `InkEngineTest.kt:776` contradicts the code — stale (Issue 6). |
| 5 | Curve / park fidelity | **PASS** | V2 `softWash` → no feather override, curve eases inside `Curve.at`; V1 tajweed → `LinearEasing` clock + `pacedFeather`; plain → `sweepEasing` cubic. Entry snapshot (`lockedPacing`/`lockedDurationMs`) is never remapped mid-wash. |
| 6 | First-pass sweep lifecycle | **PASS** | `finishResidual` gated to `Recited` only (`:1292`); `applied` MutableState mask (`:921`, `:957`) kills the full-ink flash without a parent recompose; `residualSweepAnchor` (`:674`) only rewinds the idle 1f ceiling; wasl edge latched via `effectiveRevealStart` (`:684`). Well covered by tests. |
| 7 | Glint | **FAIL** | Glint suppressed on repeat (`:1281`, `:1704`) while GLIMMER.md:31/59-64 and REPEAT_HIGHLIGHTING.md still require it, and the replacement behaviour regressed (Issue 2). |
| 8 | Cross-mode parity | **PARTIAL** | One `rememberRepeatWash` for gloss/English/Hafs — no second sequencer. But the English path omits `pacing` (`:1747`) while gloss/Hafs pass it, so the same recitation gets a differently-shaped orange edge across modes. |
| 9 | Tests | **PARTIAL** | Laws locked at the pure-function level (`washMayHardRestart`, `monotonicWashProgress`, `repeatWashAction`, sweep entry/residual). Nothing locks the composable sequencing, and two glint tests now assert dead code (Issue 5). |
| 10 | V2 vs V1 | **PARTIAL** | First-pass sweep is genuinely media-clock-driven with no aesthetic lead (`ReaderViewModel.kt:348` zeroes lead for acoustic rows; `sweepMs` skips the floor for V2). But the orange wash is a wall-clock `tween`, not the acoustic clock (Issue 4). |

## Issues

### 1. **bug** — A repeated word re-said inside an open chain gets no orange wash at all

`repeatWashAction` requires `previousActivation != 0L` to re-Reveal (`ReaderComponents.kt:268-271`), but every call site zeroes activation for non-Active members: `activation = if (isActive) activation else 0L` (`:1305`, `:1659`, `:1686`). So a word's activation sequence across two utterances is `N → 0 → N`, and the second Active entry evaluates `previousActivation == 0L` → **Hold**.

`HighlightEngine.prepare` (`HighlightEngine.kt:99-119`) keeps one continuous run across consecutive episodes, so `7 8 9 [7 8 9] [7 8 9] 10` never closes the chain — `repeat` stays true and word 7's second re-say is Hold. Compounding it: `WordHighlight.baseLayer` returns bare `Modifier` when `repeat` (`:1187`) and the Hafs path skips `InkReveal` for repeat words (`:1851`), and glint is off for repeat. Net: **the reciter audibly says the word again and nothing on screen moves.** Same failure on tap-to-play a chain member (`playFromWord` bumps `inkActivation`, but the member's previous activation was zeroed).

This is exactly what `InkEngine.glinting`'s own doc warns about — "skipping it made replayed words look inert" — and what GLIMMER.md:31 requires ("Yes, every repeat event").

**Fix:** stop overloading `activation` as the Active flag. Pass the real `activation` unconditionally plus an explicit `active: Boolean` into `rememberRepeatWash`, and make `repeatWashAction` Reveal iff `activation != previousActivation` (regardless of zero), Hold when only `active` changed. Test at `InkEngineTest.kt:667-675` encodes the current behaviour and must be rewritten alongside.

### 2. **bug** — Glint-on-repeat drop orphaned `glintCarryAlpha`; the gold no longer recedes with the orange

`glinting = … && !ink.repeat` (`:1281`, `:1704`) means `GlintIdentity.update` is never called with `glinting && repeat`, so the branch at `:577` can't fire: `replacedByRepeat` is permanently false and `glintCarryAlpha` (`:591`) always returns `1f`. `glintIsRepeat` is likewise always false, so the repeat-coloured glimmer branches at `:1885-1904` and `:2205-2224` are dead.

The felt consequence is the case GLIMMER.md:59-64 specifically legislates: when a word repeats before its first-pass gold has released, `glinting` flips false and `rememberGlintAlpha` dissolves it over a fixed `glintFadeMs` (1 s), **decoupled from the orange edge**, at full-word coverage (`letterFadeIn(progress = sweepProgress)`, which is already 1). So instead of the gold visibly receding under the incoming terracotta wash, a full-strength sheen sits over a mid-edge orange on its own timer — a slower version of the flash 59d6fc89 was trying to kill.

**Fix:** either (a) restore repeat glint and keep the flash guard by driving its alpha off `repeatWash.progress` rather than snapping to 1, or (b) commit to the drop, tie the carry-over dissolve to `repeatWash.progress` explicitly, delete `glintIsRepeat`/`glintReplacedByRepeat`/`glintCarryAlpha` and the repeat branches at `:1885-1904`/`:2205-2224`, and update GLIMMER.md + REPEAT_HIGHLIGHTING.md. Right now the code says one thing, three docs say another, and the machinery for the doc's behaviour is present but unreachable.

### 3. **bug** — "Active starts immediately" depends on `inkActivation` having been bumped

`if (act != 0L) runWash() else gate.run(position) { runWash() }` (`:450-454`). `inkActivation` starts at `0L` (`ReaderViewModel.kt:314`) and only bumps in `noteInkRestart` or on a detected backward scrub. Open the reader on a surah that is **already playing** (mini-player / notification) — a fresh `ReaderViewModel` with `inkActivation == 0`, and no `noteInkRestart` on that path. Every Active repeat word then enters the position gate and waits behind the previous member's `max(dwell, 450 ms)` wash. With short words (V1 floor is `minSweepMs + highlightLeadMs` = 254 ms, orange floor 450 ms) the orange falls ~200 ms further behind the voice per chain member — cumulative across a 5-word chain.

**Fix:** same as Issue 1 — gate on an explicit `active` flag, not on `activation != 0`.

### 4. **suggestion** — On true V2 the orange is not on the acoustic clock

`rememberRepeatWash` has no `clockProgress` parameter; it runs `clock.animateTo(1f, tween(sweepMs, LinearEasing))` shaped by the captured curve. On V2 the first-pass sweep follows `AcousticClockAnchor.progressAt` every frame (`ReaderComponents.kt:824-847`) while the orange runs a wall clock captured at entry. Pause, scrub, or a speed change mid-orange desyncs the two washes on the same page — `sweepMs` bakes in `playbackSpeed` at capture (`InkEngine.sweepMs`) and never re-reads it. SYNC_FIDELITY.md:153 says the acoustic curve is "the sole animation clock" for a true V2 occurrence; the orange is currently outside that.

**Fix:** thread the same `clockProgress` state into `rememberRepeatWash` for V2 occurrences and drive `clock` from it (still through `monotonicWashProgress`, so the no-rewind law is preserved for free).

### 5. **suggestion** — Two glint tests lock behaviour the call sites can no longer produce

`InkEngineTest.kt:627` asserts `glinting(Active)` with the comment "A repeat glints over its orange wash on the same terms" — false at the call site since `:1281`. `InkEngineTest.kt:783-799` exercises `GlintIdentity.update(glinting = true, repeat = true)`, an input combination that is now unreachable. Both pass, so they read as coverage for a behaviour that no longer ships. Nothing locks the *new* rule ("repeat words never glint"), and nothing locks the composable-level sequencing at all (gate bypass, Release→Reveal ordering, the seek dissolve).

**Fix:** delete/retarget those two tests as part of Issue 2, and add a `glintingWord(state, repeat)` pure predicate on `InkEngine` so the actual gate is testable rather than living inline in two composables.

### 6. **nit** — Stale comment contradicts the assertion beneath it

`InkEngineTest.kt:776-777`: "orange bloom itself always uses `Tuning.repeatSweepMs` (not the multi-second karaoke hold)" — immediately followed by `assertEquals(1_800, repeatWashDurationMs(1_800, 450))`. The dwell-following behaviour is the shipped law (criterion 4); the comment is left over from the reverted `92f9fa52`/`64daf9aa` experiments and will mislead the next person into "restoring" a fixed wipe.

### 7. **nit** — `rememberSearchHitWash` snaps alpha before progress

`:529-531` does `alpha.snapTo(1f)` then `progress.snapTo(0f)`, with `progress` initialised at `1f` (`:517`) and left at `1f` after each pulse. `rememberRepeatWash` is careful to do the opposite (`hardRestartToEmpty()` *then* `alpha.snapTo(1f)`, `:426-427`) precisely because the other order can paint a full-ink frame. In practice the uncontended `MutatorMutex` likely lands both in one dispatch, so I can't claim a visible flash — but it is the inverted order of the law and costs nothing to fix.

### 8. **nit** — English orange edge differs from gloss/Hafs

`rememberRepeatWashes` at `:1747` omits `pacing`; `:2023` passes it. Same recitation, different orange edge shape depending on reading mode — minor, but criterion 8 asks for one law.

## What already meets the bar

- **The no-reset law is structural, not defensive.** `monotonicWashProgress` as a separate draw channel means even a buggy clock rewind cannot produce a flash — the right design, and it's unit-tested.
- **Entry snapshotting is thorough.** `lockedDurationMs`/`lockedPacing`/`lockedFeather` are captured *before* the gate wait, so an Active handoff can't erase a queued member's pacing; `lockedPacing` is released only at progress 1 where the mapping is the identity (`:987-994`) — a genuinely subtle correctness point, correctly reasoned in the comment.
- **The `applied` MutableState mask** (`:915-921`) is the right answer to a hard problem, and the comment documents both failed alternatives so nobody re-tries them.
- **`OrderedWashGate`** correctly solves same-frame position inversion with the `yield()` batch and cancels waiters via `done.cancel(ce)` rather than `completeExceptionally` — that detail prevents a foreign cancellation from killing the word's collector.
- **Duration fidelity is real:** `max(dwell, 450 ms)` gives long words the reciter's timing and short words a visible soft edge, with no fixed robotic wipe anywhere.
- **Wasl continuity** (`effectiveRevealStart` + latched `lifecycle.revealStart`) closes the prior-word unread-flash on handoff, with a dedicated test.
- **V2 lead discipline:** `highlightPositionMs` zeroes the aesthetic lead for acoustic rows and `sweepMs` skips the floor/cap — matches SYNC_FIDELITY.md:151-153.

## Verdict

**NOT READY** for a "99% animation fidelity" claim. Highest-leverage fix: stop encoding "is Active" as `activation != 0L` — pass an explicit `active` flag into `rememberRepeatWash` and make `repeatWashAction` Reveal on any genuine activation change, which fixes both the silent re-say (Issue 1) and the gate-bypass regression (Issue 3) in one change.


## Follow-up (2026-07-28)

All Issues 1–8 addressed on branch `t3code/e6685bd5`:
- explicit `active` + real `activation` (multi-loop Reveal, Active-immediate gate)
- glint restored on repeat
- V2 orange follows `clockProgress` when present
- English shares acoustic phase; search-hit edge-before-alpha; tests updated
