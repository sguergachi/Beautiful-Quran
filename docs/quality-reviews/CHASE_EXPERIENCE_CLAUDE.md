I read `TajweedPacing.kt`, `InkEngine.kt`, the `rememberLetterSweep`/`rememberRepeatWash` paths, `Fade.kt`, and the V2 clock anchor. One note first: `~/.optmem/memo wake` needs approval in this session, so I worked without OptMem context.

## Summary

A great chase means the *visible* ink front sits on the letter the reciter just finished, never on one they haven't started, and gets there by changing speed rather than by starting and stopping. The letter timing you already have (CTC keyframes → `acousticCurve`) is truth; the feel should come from one chase law on top of it, not from three stacked smoothings. Right now the feel is spread across the curve knots (`alwaysMovingLetterFlow`), the curve easing (`cosineEase` per span), and the display step (`acousticWashStep`) — and, most importantly, the drawn feather is 1.6× the word wide, which low-pass filters the letter timing back out. The chase bar is not met yet, and the two structural reasons are geometric (feather) and control-theoretic (no feed-forward), not aesthetic.

## Chase design principles

**1. Target vs display: the curve is truth, the step is feel.**
`acousticCurve` should say only *where the voice is at time t* — letter onsets, exact, monotone. Every "keep it alive" trick (`HOLD_CREEP_FRAC`, `MIN_HOLD_CREEP`, `expandMicroAdvances`' 72 ms widening) is feel logic that has leaked into truth. Once it is in the curve, `Curve.at` can no longer be used as a reference for "am I late?", and you cannot debug lag because target and display are both fictional.

**2. Head position ≠ perceived position. Correct for the feather explicitly.**
In `Fade.kt` the wash head travels `p·(w + edge)` while the 50 %-alpha front sits `edge/2` behind it. With feather `f`, front position in word fractions is `q = p(1+f) − f/2`. At `f = 1.6`: `q = 2.6p − 0.8`. So the front only exists inside the word for `p ∈ [0.31, 0.69]` — **38 % of the curve's domain does 100 % of the perceived reveal, at 2.6× the letter clock**. Letter-timed positions are being drawn through a 2.6× gain. Either invert the map when driving the mask (`p = (q + f/2)/(1+f)`) or shrink `f` — ideally both.

**3. Lag/lead: the head leads, the front trails, and the front must never pass an unspoken letter.**
Target the front to land on the letter's *end*, 0–120 ms behind onset. Ink ahead of the voice reads as the app spoiling the word; ink a hair behind reads as reading-along. This is the perceptual restatement of your existing `if (tgt <= cur) return cur` clamp — but the clamp is currently applied to the head, where it means something different.

**4. Hold vs peel: during a hold the ink finishes the letter it is on; it never starts the next.**
This replaces both the creep constants and the park. Ceiling = the next letter's onset position. The wash drifts toward that ceiling at a floor velocity while the letter is sustained, so a madd reads as the letter *completing* over its sustain — which is what a madd looks like. Peels are then just the ceiling jumping forward, and speed follows automatically.

**5. Never zero velocity mid-word; true park only at breath and pause.**
Two legitimate zero states: after the ayah's closing letter is fully inked (breath), and when `AcousticClockAnchor.playbackSpeed == 0`. Everywhere else a floor velocity applies. Note the current design fails its own claim: `alwaysMovingLetterFlow` cosine-eases the creep to `endP`, so target velocity → 0 at the end of every long hold, and `acousticWashStep`'s `tgt <= cur` clamp then hard-stops the display. Long holds still end in a freeze.

**6. Multi-word handoff: one continuous front, restated in front coordinates.**
`waslHeadTravel` / `waslContinuationStart` are written in head units (`travel/(1+mainFeather)`), which is why they need comments to be legible. Under principle 2 they become one sentence: *by handoff the incoming opening letter is ~60 % inked*. The residual glide after handoff is not a cleanup — it is the shoulder of the same wash settling, and it should finish inside the first half of the next word, not at a fixed 0.85 word/s.

**7. Orange re-say obeys the same chase law, with a tighter edge.**
`rememberRepeatWash`'s acoustic branch already reuses `acousticWashStep` and the same curve — keep that. Two justified differences: the orange draws at `restingAlpha = 0` over already-black ink, so it is full-contrast and needs a *narrower* feather than the first pass to avoid reading as a whole-word glow pulse; and queued chain members are historical, not spoken, so they correctly stay on the ordered wall-clock wash. Don't add a repeat-specific speed — a slower re-say is already handled by velocity.

## Concrete tips

**Tip 1 — Make the feather letter-scaled, using the `letterCount` you already compute.**
*What:* drive the acoustic feather from `Curve.letterCount` instead of passing `null`. A transition band one letter wide is `f ≈ 2/n` (smootherstep's 10–90 % span is ~0.51·edge), clamped to ~[0.35, 0.9].
*Why:* this is the single largest fidelity loss in the stack — at `f = 1.6` no per-letter detail can survive to the screen regardless of how good the timing is.
*Where:* `rememberLetterSweep` line ~890 (`acousticFeather.value = if (soft) null else …`) and the mirror in `rememberRepeatWash`'s `lockedFeather`. `Curve.letterCount` is currently computed, unit-tested, and consumed by nothing.
*Risk:* below ~0.3 the wash becomes a wipe and you lose the paper metaphor. Keep the floor.

**Tip 2 — Invert the feather map when converting curve position to mask progress.**
*What:* draw `p = (q + f/2)/(1+f)` where `q` is the curve's letter position; spend the trailing breath running `p` to 1 (shoulder settle).
*Why:* makes "the front is on the spoken letter" literally true instead of true only at `p = 0.5`. It also gives the breath a job other than advancing the front.
*Where:* one place — where `curve.at(phase)` becomes the chase target in `rememberLetterSweep`.
*Risk:* the wash no longer reaches `p = 1` at the last letter, so the residual/settle path must be reliable or short words end masked.

**Tip 3 — Give the chase feed-forward: `speed = v_target + gap/τ`.**
*What:* replace `maxSpeed(v) · smoothstep(gap/0.22)` with velocity plus a proportional correction, `τ ≈ 0.12 s`.
*Why:* the current form is multiplicative, so both factors shrink together and the loop **cannot track a moving target without a standing gap**. Worked case: a widened 72 ms peel of 0.25 word → target 3.5 word/s, display capped at 2.3 → ~0.086 word head deficit, recovered at ~0.69 word/s ≈ **125 ms of lag after every large peel**, repeating letter by letter, right at the edge of the 50–150 ms window `SYNC_FIDELITY.md` sets. Feed-forward has zero steady-state error, and `ACOUSTIC_WASH_CRUISE`, `ACOUSTIC_WASH_EASE_GAP`, the `0.18` floor, `1.25/0.2/2.3` all collapse into `τ` plus a drift floor.
*Where:* `InkEngine.acousticWashStep`.
*Risk:* feed-forward without the never-lead clamp can overshoot on a noisy velocity estimate — keep the clamp, now against the ceiling (Tip 4).

**Tip 4 — Chase a ceiling, not a setpoint.**
*What:* pass `(target, ceiling)` where ceiling is the next letter's onset position. Clamp to ceiling instead of to target; apply a floor velocity (~0.03–0.05 word/s) whenever below ceiling.
*Why:* this is what makes "always moving" real instead of nominal, and it deletes `alwaysMovingLetterFlow` and `HOLD_CREEP_FRAC`/`MIN_HOLD_CREEP` entirely — the curve goes back to being honest.
*Where:* `acousticWashStep` signature + `Curve` exposing the next knot.
*Risk:* drift must be gated on `playbackSpeed > 0`, or ink creeps while paused. Also don't let drift reach ceiling early on very long waqf — cap total drift at the current letter's extent, which the ceiling already does.

**Tip 5 — Interpolate the curve C¹, and take velocity from the curve.**
*What:* replace per-span `cosineEase` with monotone cubic Hermite (PCHIP) across the knots, and add `Curve.velocityAt(t)` for the feed-forward term.
*Why:* cosine per span forces velocity to zero at *every* keyframe — that is literally "hard freezes on every syllable," just rounded. PCHIP hits every letter time exactly, never overshoots, stays monotone, and keeps velocity continuous. It also removes the need for `expandMicroAdvances`' 72 ms widening and `MAX_PEEL_STEAL_MS`, which exist only to give cosine room. The current `(target − lastTarget)/dt` estimate is a one-frame difference of a curve that oscillates at every knot — it feeds jitter straight into `maxSpeed`.
*Where:* `Curve.at` / `acousticCurve`.
*Risk:* PCHIP between two very close knots can look flat; the ceiling drift covers that.

**Tip 6 — Use `spokenMs` on the V2 path.**
*What:* `InkEngine.pacing`'s V2 branch passes only `keyframes` + `durationMs`, where `durationMs = holdEndMs − startMs` (the karaoke hold, including the gap to the next word). The final rise to 1 therefore spans any trailing silence.
*Why:* V1 already has this honesty via `spokenFraction`; V2 doesn't, so the front creeps through breaths.
*Where:* `InkEngine.pacing` (line ~451) and `acousticCurve`'s terminal `raw.add(durationMs to 1f)`.
*Risk:* on ayah-final words the "silence" may be voiced waqf sustain — verify against the segment before trusting `endMs`.

## Anti-patterns

- **Freeze at hold end.** Curve creep eased to zero + `tgt <= cur` clamp = a real stop at the end of every sustained letter. This is the current behaviour despite the "always moving" naming.
- **Whole-word breath sold as a chase.** `f = 1.6` means every letter is mid-bloom at all times; letter timing cannot be perceived. Two different products fighting over one constant.
- **Lag that scales with speed.** Any control law of the form `speed = k · f(gap)` is systematically late on fast peels. Feed-forward or accept the lag.
- **Feel logic inside the truth curve.** Once `acousticCurve` invents motion, you can't measure error, and Ink Lab knobs stop meaning anything.
- **Stacked easings.** `cosineEase` (curve) + cosine creep (`alwaysMovingLetterFlow`) + smoothstep (`acousticWashStep`) + smootherstep (gradient). Four soft curves compose into mush and one un-debuggable velocity profile.
- **Leading the voice.** Never let the front pass an unspoken letter — worse than lag, because it reads as prediction.
- **Mixed lead policies.** `highlightLeadMs = 114` on V1 vs no lead on V2 means the two lanes feel differently late; don't A/B feel across lanes without normalizing this.
- **Head-unit constants in front-unit conversations.** `waslHeadTravel`, `waslContinuationStart`, `ACOUSTIC_WASH_EASE_GAP = 0.22` (= 0.57 word-widths of *front* lag at `f=1.6`) — all unjudgeable by eye as written.
- **Wall-clock desync.** `acousticProgressFrame`'s `maxOf` blocks rewind but lets forward anchor jumps through as a visible race; the chase should absorb them, and it will once the gap term is bounded by τ.
- **Per-frame O(n) `Curve.at` scan** from the array end — not a chase defect, but it's on the hot path twice per active word.

## Suggested tuning order

1. **Fātiḥah 1:2 / 1:7, V2 on, Ink Lab feather sweep only** — set `f` to 2/n by hand (≈0.4 on a 5-letter word) with everything else unchanged. If the chase doesn't visibly improve here, nothing downstream will show either.
2. **Add the inverse feather map (Tip 2)** at that feather. Watch a long word (`ٱلرَّحْمَٰنِ`, `ٱلْمُسْتَقِيمَ`): the front should sit on the letter, not race the middle third.
3. **Swap in feed-forward + τ (Tip 3)**, τ ≈ 0.12 s. Audition on the fastest peels you have — 5:54's dense words. Symptom of τ too high: soft lag on peels. Too low: buzz/jitter at knots (fix with Tip 5, not by raising τ).
4. **Add ceiling + drift floor (Tip 4)**, then delete `alwaysMovingLetterFlow` and creep constants. Audition on a long waqf (1:7 closing word, though it's V1 fallback — use a V2 ayah-final word): the closing letter should complete, not the wash reach the word end.
5. **PCHIP (Tip 5)**, then drop `expandMicroAdvances`. Verify no knot overshoot on CTC micro-peels.
6. **6:10-style re-say / 5:54 repeat chain**, V2 on: confirm the orange follows the identical law with the tighter feather and that the queued chain still runs its ordered wall-clock wash.
7. **`spokenMs` breath honesty (Tip 6)** last — it's the smallest visual delta and easiest to misjudge before the front is trustworthy.
8. **Cross-check lanes:** same ayah V1 vs V2 back to back, and A2DP vs speaker, to make sure you tuned the chase and not the output latency.
