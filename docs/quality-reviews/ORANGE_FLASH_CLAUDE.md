I read the full wash lifecycle, the gate, the draw modifiers, both render paths (gloss `WordUnit` and shaped `shapedWordBloom`), and the timing source. Here's the diagnosis.

---

# Root cause

Ranked. #1 and #2 are the flash you're seeing; #3–#5 are real but second‑order.

### 1. `OrderedWashGate` leaks cancelled jobs → a stale wash replays on a word that's holding full orange

`OrderedWashGate.run` (`OrderedWashGate.kt:39-46`) enqueues a `Job` into `pending`, then `done.await()`. **Nothing removes the entry when the caller is cancelled.**

The caller *is* cancelled, routinely: `ReaderComponents.kt:442` (`washJob?.cancel()`) fires on every Reveal, and a queued (non‑active) chain member sits suspended in `gate.run(position) { runWash() }` (`:544`). Two guaranteed triggers:

- seek‑into‑chain queues words 3..7; the reciter advances and word 5 becomes the lit word → `repeat && wasRepeat && active && !wasActive` → Reveal (`repeatWashAction`, `:283`) → `cancel()`. Word 5's queued job stays in `pending`.
- second seek → `activation != previousActivation` → Reveal → another cancel, another orphan. Same position stacks in the `ArrayDeque` ("seek re-washes stack", `:31`).

Later the pump pops the orphan and runs `job.block()` **in the pump's own coroutine** — the cancelled outer job is irrelevant. The stale `runWash` closure then executes against a word that already washed and is holding at full orange:

```
guard: alpha == 1f → dissolveThenEmpty()   // 900 ms fade-out of a word that should be holding
hardRestartToEmpty()                        // edge → 0
alpha.snapTo(1f)                            // full orange layer back on, empty edge
→ re-wash from 0
```

**That is the flash**: spontaneous dissolve → empty → re‑wash, on a word the voice has already passed, N times for N cancelled reveals. Monotonic progress cannot help — the stale job dissolves first, so it's "legal" under the peak rule.

Same defect, second failure mode: the orphan block runs in the pump while the word's live `washJob` also mutates the *same* `alpha`/`clock` `Animatable`s. `Animatable.snapTo/animateTo` go through `MutatorMutex`, so the second caller **cancels** the first — the `CancellationException` surfaces inside `job.block()`, hits `catch (ce: CancellationException) { …; throw ce }` (`OrderedWashGate.kt:74-79`), and kills `pump()`. `pump` is started once by `LaunchedEffect(repeatWashGate)` (`ReaderComponents.kt:2972`) and the key never changes → **the ayah's gate is dead for the rest of its life**; every later queued member never washes at all. Flashing words next to silent words is the signature.

`OrderedWashGateTest.kt` has no cancellation test — this is entirely uncovered.

### 2. The Reveal sequence is not atomic; the "dissolve first" guard is evaluated once, then `hardRestartToEmpty` *snaps* a visible alpha

`runWash` (`:445-464`) reads `alpha.value` once at `:449`, then crosses five suspension points on two Animatables before `alpha.snapTo(1f)` at `:464`. `hardRestartToEmpty` (`:388-395`) then re‑checks and, if alpha came *back up* in between, does `alpha.snapTo(0f)` — **a hard cut of fully painted orange to nothing, no dissolve.** That is precisely the "never flash full→empty" law, violated inside the function that exists to enforce it.

Reachable whenever two coroutines touch the word's `alpha` (the orphan pump block from #1, or a Release job racing a Reveal): a foreign `snapTo(1f)` cancels the live `animateTo(0f)` inside `dissolveThenEmpty` (`:399`), the live job dies mid‑dissolve, and the winner's `hardRestartToEmpty` snaps. The guard/act split is the structural bug — the check and the mutation must be one critical section.

### 3. Two owners of `displayProgress`/`peakProgress`, and Release resurrects the edge to 1

The acoustic frame loop (`:471-533`) and the wall‑clock publisher (`:579-600`) both write `displayProgress`/`peakProgress`; the only handoff is `acousticFollow`, and it is flipped from *outside* the loop that owns it (`:443` in the collector, `:528`/`:531` inside `withFrameNanos`). The acoustic path never advances `clock`, so the moment `acousticFollow` goes false the publisher's `raw` is `lockedPacing.at(0) == 0` — safe today **only** because `peakProgress` holds.

But Release (`:562-566`) does `clock.snapTo(1f)` and *then* zeroes peak/display. The publisher is a separate, never‑cancelled effect: it wakes with `raw = 1`, `alpha = 0` → invisible → `monotonicWashProgress` returns `1 to 1`. **After every Release the word is left at `display = 1, peak = 1`.** So the peak guard — the thing standing between #2 and a visible flash — is silently re‑armed at full by a race, on every chain release.

### 4. `showRepeatLayer` reads alpha in composition (gloss path only)

`WordHighlight.showRepeatLayer` (`:1319`) reads `repeatWash.alpha.value` during composition at `:1517`. That (a) recomposes the whole word every alpha frame during the 900 ms dissolve — against `docs/PERFORMANCE.md` — and (b) adds/removes the orange `InkOverlayText` a frame *behind* the draw‑phase values, so gloss mode and the shaped path (which read alpha in the draw lambda, `:1984`/`:2301`) disagree by one frame at both edges of the wash. Not the main flash; it widens the window for #2.

### 5. Entry pop: the orange starts at ~21–31 % of the word, not at 0

`maskProgressForLetterFront(0f, f) = (f/2)/(1+f)` (`InkEngine.kt:445-448`). With the repeat feather capped at 0.75 (`:460`) that's **0.214**; with the default 1.6 feather it's **0.308**. So the first acoustic frame targets ~¼ of the word while `displayProgress` is 0, and `acousticWashStep` closes that gap on a τ=0.12 s exponential → the leading quarter of every orange word blooms in over ~300 ms independent of the reciter. Reads as a pop at the head of each word, on top of `alpha.snapTo(1f)`.

### Ruled out (don't chase these)
- `activation = activeWord?.activation ?: 0L` (`:2839`) collapsing to 0 **cannot** produce a spurious Reveal: `inRepeatChain` requires a non‑null `activeWord` (`InkEngine.kt:330-332`), so `repeat` is already false whenever activation collapses, and `!repeat && wasRepeat → Release` wins the `when`.
- Mid‑ayah timing gaps don't null the active word — `activeIndex` holds the last segment whose start ≤ position (`HighlightEngine.kt:143-156`).
- Repeat‑chain members are never `Upcoming` (high‑water keeps them `Recited`), so there's no full‑ink pop under the orange from the skipped `InkReveal` bloom at `:1971-1982` / `:2266-2282`.

---

# Recommended fix

Net **deletes** more than it adds. Three changes; the first two are the fix, the third closes the peak‑resurrection hole.

### Fix 1 — make the gate cancellation‑safe (`OrderedWashGate.kt`)

Two edits:

1. In `run`, wrap `done.await()` in `try/finally` and remove this exact `Job` from `pending` under `NonCancellable` on the way out. An orphaned block must never execute.
2. In `pump`, rethrow the `CancellationException` **only if the pump's own coroutine is cancelled** (`if (!coroutineContext.isActive) throw ce`); otherwise complete the waiter and keep draining. A foreign `MutatorMutex` cancellation must not kill the ayah's gate.

### Fix 2 — one atomic, paint‑safe Reveal (`ReaderComponents.kt`)

Collapse `hardRestartToEmpty` + `dissolveThenEmpty` (`:388-408`) into a single `armEmpty()` that is the **only** way to reach an empty edge, and hold a per‑word `Mutex` across the whole `runWash` body so no two coroutines can ever be inside the sequence for one word.

The sequence that must be atomic, in this order:

| # | step | invariant |
|---|---|---|
| 1 | if `alpha ≥ WASH_INVISIBLE_ALPHA`: `alpha.animateTo(0f, repeatFadeOutMs)` | dissolve with the **old** feather/pacing still locked |
| 2 | `alpha.snapTo(0f)` | now provably invisible |
| 3 | `clock.snapTo(0f)`; `peak = 0`; `display = 0` | edge rewinds only while invisible |
| 4 | lock `durationMs` / `pacing` / `feather` | new entry values, after the dissolve |
| 5 | `alpha.snapTo(1f)` | first visible frame has edge = 0 |
| 6 | `acousticFollow = true` (or `clock.animateTo(1f)`) | chase starts |

No frame may be painted between 3 and 5 with `alpha ≥ WASH_INVISIBLE_ALPHA`; no other coroutine may touch `alpha`/`clock` during 1–6. `alpha.snapTo(0f)` is legal **only** at step 2, immediately after a completed dissolve — never as a shortcut.

Note the ordering change vs today: locked pacing/feather move *after* the dissolve (today `:452-462` sets them before `hardRestartToEmpty`, so the dissolving edge can change width mid‑fade).

### Fix 3 — Release must not resurrect the edge

In the Release branch, replace `clock.snapTo(1f)` + manual zeroing (`:562-566`) with the same `armEmpty()`, then null the locked pacing/feather. `clock` ends at 0, so the publisher can only ever republish 0 and `peak` stays honest.

### Delete while you're in there
- `repeatWashShouldRestart` (`:319-328`) — three `@Suppress("UNUSED_PARAMETER")` params wrapping a one‑line alias for `washMayHardRestart`. Dead indirection; delete it and fold its test into the `washMayHardRestart` test.
- The `when` at `:497-499` — two branches returning the identical `1f`.
- The `localGate` + per‑word `LaunchedEffect(localGate) { localGate.pump() }` fallback (`:376-380`): `AyahBlock` always provides a gate (`:2978`), so this spawns a dead pump coroutine per word in production. Keep only if a test depends on it; otherwise require the composition local.
- Optional (#4): drop `showRepeatLayer` and compose the orange `InkOverlayText` on a non‑animated boolean (chain membership), letting `glyphLayerAlpha`'s `alpha <= 0f` early‑return handle visibility in the draw phase — matching the shaped path.

Leave #5 (entry pop) alone in this pass; it's a tuning question (`letterFadeIn` head offset vs. the feather map), not a lifecycle bug, and changing it now will confound the verification.

---

# Implementation checklist

1. **Lock the bug first.** Add the two failing `OrderedWashGateTest` cases from below. They must fail against `HEAD`.
2. `OrderedWashGate.run`: `try { done.await() } finally { withContext(NonCancellable) { mutex.withLock { remove this Job from pending[position]; drop the key if empty } } }`. Compare by identity — same position can hold several jobs.
3. `OrderedWashGate.pump`: in the `CancellationException` catch, `job.done.cancel(ce)`, then `if (!coroutineContext.isActive) throw ce` — otherwise `continue` the drain loop. Run the new tests; they should pass.
4. `ReaderComponents.kt`: replace `hardRestartToEmpty` + `dissolveThenEmpty` with a single `armEmpty()` implementing steps 1–3 of the table.
5. Add `val washLock = remember { Mutex() }` and wrap the whole `runWash` body in `washLock.withLock { }`. Keep `gate.run(position) { runWash() }` outside the lock so the gate still orders by word position and the queue wait doesn't hold the lock.
6. Rewrite `runWash`'s head to `armEmpty(); lock duration/pacing/feather; alpha.snapTo(1f)` — delete the `if (!washMayHardRestart(alpha.value)) dissolveThenEmpty()` guard (now inside `armEmpty`) and the duplicated `hardRestartToEmpty()` at `:463`.
7. Release branch: `alpha.animateTo(0f, …)` → `armEmpty()` → null the locked pacing/feather. Delete `clock.snapTo(1f)`.
8. Keep `acousticFollow.value = false` at `:443` **before** `washJob?.cancel()` is irrelevant now — but do not move it inside the lock, or a cancelled acoustic loop can deadlock the new job waiting on the lock. Verify: cancel → old job unwinds → `withLock` released in `finally` → new job proceeds.
9. **No‑lag check:** the new job's `withLock` waits for the old job's cooperative unwind (one frame at most, since the acoustic loop suspends in `withFrameNanos`). Confirm on device that a chain advance still starts its wash on the same frame the word lights — if the lock ever costs more than a frame, the acoustic chase will visibly catch up.
10. `./gradlew testDebugUnitTest` (JDK 21), then run a real repeat with a seek into an open chain and a multi‑loop re‑say, per `docs/SYNC_FIDELITY.md`.

---

# Tests to add

All JVM, no Robolectric. The first two are the regression locks; the rest hold the law.

**`OrderedWashGateTest`**

1. `` `a cancelled waiter never runs its block` `` — `runBlocking`: start `pump`; `val a = launch { gate.run(5) { ran += "stale" } }` while position 3 is held busy; `a.cancel()`; then `gate.run(7) { ran += "live" }`. Assert `ran == listOf("live")`. Fails today.
2. `` `a block cancelled by a foreign mutator does not kill the pump` `` — enqueue a block that throws `CancellationException` while the pump's own scope is still active; then enqueue a second job and assert it runs. Fails today (pump dies).
3. `` `re-enqueue after cancel washes once, not twice` `` — cancel a queued position‑5 waiter, enqueue position 5 again, assert the block ran exactly once.

**`InkEngineTest`** — extract the Reveal ordering into a pure, testable step list so the sequence itself is assertable without Compose:

```kotlin
internal enum class WashStep { Dissolve, ClearAlpha, ZeroEdge, LockPacing, ShowFull }
internal fun revealSteps(visibleAlpha: Float): List<WashStep>
```

4. `` `revealing a painted wash dissolves before the edge rewinds` `` — `revealSteps(1f) == [Dissolve, ClearAlpha, ZeroEdge, LockPacing, ShowFull]`; assert `ZeroEdge` never precedes `Dissolve` for any alpha ≥ `WASH_INVISIBLE_ALPHA`, and `ShowFull` is always last.
5. `` `an invisible wash cold-starts without a dissolve` `` — `revealSteps(0f)` omits `Dissolve`, still ends `ShowFull`.
6. `` `pacing locks after the dissolve, never during it` `` — `indexOf(LockPacing) > indexOf(Dissolve)` whenever `Dissolve` is present (guards the mid‑dissolve feather change).
7. `` `release leaves the edge empty, not full` `` — a small frame simulator: feed the Release sequence through `monotonicWashProgress` with the publisher's `raw` derived from a `clock` left at its post‑Release value, and assert the resulting `peak` is `0f`. Fails today (peak comes back as `1f`), and this is the test that stops #3 from regressing.
8. `` `no frame is ever painted with a visible alpha and a shrinking edge` `` — property‑style: run a scripted lifecycle (Reveal → Hold → Reveal(multi‑loop) → Release → Reveal) frame by frame through `repeatWashAction` + `revealSteps` + `monotonicWashProgress`, recording `(alpha, progress)` per frame; assert no consecutive pair has `alpha ≥ WASH_INVISIBLE_ALPHA` with `progress` decreasing, **and** no pair has `alpha` dropping from ≥ 0.5 to 0 in a single frame (the hard‑cut flash from #2, which the existing monotonic tests do not catch).

Test 8 is the one that encodes the product law directly; 1, 2 and 7 are the ones that fail on the current branch.
