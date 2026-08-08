# Tarjīʿ resonance — implementation notes

This is the build log for the tarjīʿ shimmer (PRs #664–#675). The shipped
feature is small — one slider, one detector, one draw-phase multiplier — but
the path to it hit every layer of the reader. This file keeps that context
so the next change does not reopen a closed trap.

## 1. The term

**Tarjīʿ (ترجيع)** — the repeated reverberation of the voice on a single
held note. The pulsing a reciter carries into a long madd or waqf sustain.

The word comes from the ḥadīth of ʿAbdullāh ibn Mughaffal al-Muzanī: he saw the
Prophet ﷺ on his camel on the Day of the Conquest of Makkah reciting Sūrat
al-Fatḥ, *yurajjiʿu* — his voice reverberating on the held vowels (al-Bukhārī
5048, Muslim 793). That is exactly the acoustic event the detector looks for
and the shimmer rides.

## 2. Architecture — why a PCM tap, not a Visualizer

The #665 attempt used `android.media.audiofx.Visualizer` on the player's
audio session. It requires `RECORD_AUDIO`, which the app did not declare.
The constructor throws `SecurityException` (swallowed by `catch
(RuntimeException)`) on every device — and a `Visualizer` on an emulator
or on several Bluetooth routes never fires anyway. Only the free-running
sine ever ran during field testing.

The replacement taps the app's **own PCM** before it reaches the output:
`VoiceTapAudioProcessor`, a pass-through `BaseAudioProcessor`, sits in the
player's sink (`PlaybackService.tarjiRenderersFactory` → `DefaultAudioSink`
with the processor prepended). It mirrors the PCM the listener hears — any
route, any speed, no permission. The detector (`playback/Tarji`, pure JVM
DSP) sees the signal in wall-clock order at consumption speed, so the
shimmer's rate is inherently the *heard* rate; only a constant output-route
latency shifts it.

### The byte-order bug that shipped with the tap

`AudioProcessor` byte buffers are little-endian PCM16, but `ByteBuffer` defaults
to big-endian. Reading `buf.short` with the wrong order kept full energy but
killed all periodicity. On-device logs showed healthy RMS with `hold=0`
forever — the same "full energy, zero pitch" signature. The fix is one line:

```kotlin
val buf = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
```

Pin this: every new processor must.

## 3. The detector (`playback/Tarji`)

Pure, no Android imports, hop-count time only. Unit tests synthesize waves
at 8 kHz directly. On device the tap decimates the sink's PCM to 8 kHz mono
and feeds 20 ms hops.

### Signal chain

1.  **Frame → RMS.** Each 80 ms frame (4 hops) yields one RMS value pushed
    into a 64-hop (≈1.3 s) envelope ring. `PEAK_DECAY` tracks the noise floor
    the hold gate uses.

2.  **Pitch.** Normalized autocorrelation over the reciter range 70–350 Hz
    (lags 22..114 at 8 kHz). The shortest lag within 5 % of the best wins
    (otherwise lag *L* vs *2L* flips hop-to-hop and resets every hold).

    **Octave folding on the hold check.** Autocorrelation flips freely between
    a period and its double on real voice. `samePitch(a, b)` folds ratios by
    powers of 2 within ±8 % — so 156.9 Hz and 320 Hz are the same note. The
    running hold pitch is tracked *in the anchor's octave* so a one-hop flip
    never drags it.

3.  **Hold.** A hold is a voiced, pitch-stable single note. `clarity ≥ 0.5`
    and `rms ≥ floor(MAX_FLOOR, 0.15·peak)` marks voiced; the pitch gate above
    keeps it one note. `holdMs += 20` while voiced+same-note; 4 hops of
    grace (`MAX_MISSES`) on estimation glitches; a pitch step restarts the hold.
    `HOLD_MIN_MS = 400` before reverberation is considered.

    Windows for the envelope scan start **at the hold onset**
    (`holdStartEnvCount`), not at "now" — otherwise the syllable attack ramp
    poisons the depth estimate for ~1 s.

4.  **Envelope scan.** Over the within-hold window (30..64 hops) the demeaned
    RMS envelope is scanned for a periodic oscillation:

    *   depth = `√2 · rms(d) / mean` — the 1.5–10 Hz tarjīʿ band, ~2 Hz Hani
        swells to ~6–8 Hz Alafasy vibrato. Default ceiling 10 Hz, Ink-Lab-
        tunable `maxTremoloHz` 2–50 Hz (`VoiceEnergy.maxTremoloHz`). Raising it
        lets Hani's 2:16 closing elongation (fast texture ~16 Hz) shimmer.
    *   rate = envelope autocorrelation peak. Lags `minLag(maxTremoloHz)`..33
        (1.5 Hz floor) with a **harmonic guard**: a true period above the scan
        floor still correlates at double its lag — without the guard a 5.5 Hz
        vibrato reads as 2.8 Hz and slips under a low ceiling. Guard rejects it
        when `norm[bestLag/2] ≥ 0.7·best`.
    *   `periodic` needs `bestC ≥ 0.4` and rate in band.

    **Band limits and hop rate.** The envelope is sampled at 50 Hz (20 ms hops).
    Nyquist is 25 Hz — nothing above that is literally measurable. At high
    ceilings the shimmer answers on the alias/faith, as the Ink Lab caption
    says. The 20 ms hop also low-passes the envelope: a 12 Hz AM loses ~10 % of
    its depth to averaging; faster texture needs more depth. Real closing
    elongations carry depth ~0.6, so they clear the gate even fast.

    Hysteresis: stricter to switch *on* than to stay on (`DEPTH_OFF_RATIO`
    0.7, band widened by 1 Hz) — no flapping when the reverberation breathes.
    `tremoloGain` ramps 250 ms attack / 800 ms release. The long release
    bridges sub-second lulls inside a long pulsing hold (the reciter breathing
    *within* the tarjīʿ) without dropping the shimmer.

5.  **Tremolo signal.** `(latest − mean)/amp` smoothed with a 0.35 EMA, then
    phase-lead-compensated by the analysis+smoothing lag (~45 ms):

    ```
    s(t+τ) ≈ s·cos ωτ + ṡ·sin ωτ / ω
    ```

    so the gold swells *with* the voice, not 45 ms behind. `tremolo` is
    zero-centred, ~−1.5..1.5.

6.  **Output latency.** The PCM tap hears the voice *before* the listener.
    `VoiceEnergy.outputLatencyMs` (pushed from `ReaderViewModel`'s
    `outputLatencyMs()` — the same estimate `HighlightClock` subtracts) and
    `playbackSpeed` are pushed into the detector; the reported
    `syncReverberating`/`syncTremolo`/`syncTremoloGain` are read through a
    64-hop history ring delayed by `latencyMs·speed / 20 ms`.

### What the real recordings taught us

*   Alafasy 1:7's closer is 2.44 s (`[5530,7970]` for Hani Ar-Rifai's 2:16
    analog) — but the detector's clock saw the hold only later; validated
    offline with `ffmpeg → f32le 8 kHz → Tarji` on the everyayah MP3s.
*   A plain-periodicity scan locked on a 2 Hz swell reads it at ~16 Hz
    (lag 3 wins on a smooth decay) — the band floor **and** the low-pass
    interact. The autocorrelation scan plus the 1.5 Hz floor are both needed.
*   Fast texture at 22 Hz aliases but still lands in-band — that is why the
    50 Hz ceiling needs the harmonic guard.
*   Measured on the real files: the shimmer locks ~130 ms into a
    ghunnah-hummed hold and holds the whole 2.4 s hum, releasing as the voice
    slides into decay — exactly the product span.

## 4. When the shimmer is allowed to show

Three gates, all pure alpha (never positional — the reveal edge never moves
mid-animation, so the bloom can never appear to restart):

1.  **Eligible word:** `TajweedPacing.Curve.hasStrongHold` — a hold on the
    word's *own* letters (long madd, ghunnah, or the verse-closing waqf).
    A wasl-entry hold alone sustains the *previous* word's nūn and never
    qualifies — those are the "shimmer on words with no holds" reports.
2.  **Audible reverberation:** `VoiceEnergy.shimmerGain (= isLive ?
    tremoloGain : 0) > 0.01`. No detection → still gold, even on the longest
    steady waqf. This is the product law the last round established.
3.  **Active word:** `isActive` (and not a repeat terracotta word). The gate
    hard-closes at handoff: the dry-down dissolve is never modulated.

When all three pass, the gold's tint and halo ride

```
1 + depth·g·tremolo   // synced to the voice
```

re-centred below 1 (`RESONANCE_CENTER 0.9`, bounded `[0.55, 1.25]`) so the
gold can both brighten and dim. Before the fix the multiplier was centred
on 1 — which clips at full opacity, so only the dimming half was visible
(the halo dimming, the feather flicker); peaks never rose above the formed
ink. The centre now eases in with the gain (`1 + (0.9−1)·g`) — at `g = 0`
the multiplier is exactly 1, so a word gives **no tell** that it is about to
shimmer. The halo's formation floor (`max(smootherstep(p), 0.88·g)`) scales
the same way, for the same reason.

The free-running sine that used to run inside the waqf window on steady holds
is gone — steady holds keep still gold.

### Ink Lab

*   **Tarjīʿ** toggle (`glintResonance`), **Resonance strength**
    (`glintResonanceDepth` 0–1), **Tarjīʿ max rate** (`glintResonanceMaxHz`
    2–50 Hz, shipped 10). The max-rate band scans from that ceiling;
    sub-harmonics are rejected. The panel now shows a live **Detector**
    line under the toggle (polled every 200 ms while open):
    `tarjīʿ · hold 1.2s · 4.8 Hz · gain 0.84` / `holding … — no tarjīʿ yet` /
    `listening…` / `silent — no PCM` — so vanishing shimmer is diagnosable
    (no hold, wrong rate, depth, silent sink).

## 5. The sweep that must never restart — and the clock that kept restarting it

The bug that replayed a word's bloom (word 2/3 of an ayah, consistently)
**has nothing to do with the shimmer path.** The word has no pacing curve
(أَنۡعَمۡتَ's marked sukūn nūn is iẓhār → `curve()` returns null), so no glint
code can run on it, and the tap maps frames 1:1 without moving the playhead.
The mechanism is the one `HighlightClock`'s own KDoc has documented since #271:

> At a media-item handoff the controller's position estimate creeps forward
> believably (≤100 ms/poll passes the step cap), lighting word 2/3 early,
> then snaps back — and if that snap-back lands *after* the settle window
> it is accepted as a genuine seek and the word's wash replays.

### What the clock does

*   `MediaController.currentPosition` is an extrapolated estimate — small
    backward steps (~40 ms) are jitter; after a seek or item handoff the
    estimate can be hundreds of ms wrong.
*   Within one `key` (the playing `NowPlaying` item) the clock holds every
    small regression (`< SEEK_THRESHOLD_MS 250`) — that is the jitter gate.
*   After a seek/handoff/key change the clock enters a **settle** window.
    During settle every backward step is held and implausible forward jumps
    (`> MAX_SETTLE_STEP_MS 100`) are ignored — a post-seek correction cannot
    bounce the word. Before this work settle was a fixed 12 polls (~400 ms)
    then 36 polls (~1.2 s).

### Why fast-forward broke longer verses

After an FF midpoint seek, a creeping wrong estimate rode the clock **ahead**
of the voice for the whole verse: during settle the clock accepted up to
3× realtime steps verbatim, so the highlight ran ahead of the reciter for
seconds until playback caught up.

Settle is now **convergence-based**: it ends only when the estimate has
tracked realtime playback for `STABLE_POLLS_NEEDED = 4` consecutive polls
(`advance ∈ [0, BELIEVABLE_STEP_MS 66]` ≈ 2× realtime), never less than the
old 12-poll minimum, hard-capped at 45 polls (~1.5 s). While never-converged
*every* regression is held — a late snap-back cannot read as a seek. During
settle forward steps are **clamped to 66 ms/poll** — the clock may trail,
never lead, so the correction residue stays tiny and rejoining is fast.
Normal playback converges in ~130 ms; a creeping estimate never converges
until real tracking resumes. `lastRawMs` feeds the believable check — clamping
cannot fake convergence.

### Pinning the law

`WashResetTest` is the regression suite for the product law
("no mid-animation wash reset; hard-restart only when invisible"):

*   `sweepEntryAction` arms **only** on a rising edge or a genuine activation
    bump, never mid-word (100-frame soak).
*   `residualSweepAnchor` never rewinds a wash that already moved; only the
    untouched idle ceiling may rewind to 0.
*   `continuedSweepProgress` is monotonic as the wash advances.
*   `HighlightClock` never steps backward through a handoff creep-and-snap-back,
    a mid-verse seek wobble, or an FF-seek creep — and the FF scenario
    rejoins the true position exactly.

These live where the reset actually lives: the pure entry/residual helpers and
the clock, not the glint paint adapters. A new shimmer knob must keep the
contract `gain = 0 ⇒ multiplier ≡ 1` and `halo floor ∝ gain` so a not-yet-
reverberating word stays pixel-identical to a plain word.

## 6. Tajweed nuance that gates all of this

*   The words that matter here wear their orthography: a long madd (counts ≥
    `MADD_MUTTASIL`), a mushaddad ن/م **ghunnah** (`counts ≥ GHUNNAH &&
    isGhunnah`), or the verse-closing **waqf**. The suite's `hasStrongHold`
    is exactly "a hold on the word's own letters" — a wasl-entry hold alone
    (idghām of a previous nūn) sustains the *previous* word and does not make
    the next word eligible. The DB test that pins this is
    `strong-hold eligibility covers madd ghunnah waqf but never wasl alone`
    (مِنْ + وَقَالَ  → wasl only).
*   The tokenizer's QPC distinction matters: voiced sukūn is `ۡ` (U+06E1);
    `ْ` (U+0652) is the *silent* mark (أُوْلَٰٓئِكَ, أَنَا۠ …). Getting it wrong
    made the wasl test `curve()` return null and blocked the suite — with no
    clue beyond "Required value was null."

## 7. How to audition and verify

*   Nightfall/Royal Green, 1×, Hani Ar-Rifai 2:16's closer or 4:145's
    ghunnah hum are the canonical ear cases — open the Ink Lab Tarjīʿ tab
    and watch the **Detector** line. A steady hold without an audible pulse
    stays `holding … — no tarjīʿ yet` and still gold by design; a pulsing
    hold flips to `tarjīʿ` within ~0.6 s of the reverberation's onset and the
    gold rides it.
*   Drag **Tarjīʿ max rate** to 50 to let fast vocal texture (~12–17 Hz) in;
    the harmonic guard keeps 5.5 Hz vibrato from masquerading at 2.8 Hz under
    a low ceiling. The envelope hop is 20 ms (50 Hz → Nyquist 25 Hz); above
    that the detector answers on the alias, faithfully — the caption says so.
*   Verify the no-reset law with

    ```bash
    ./gradlew testDebugUnitTest --tests "com.beautifulquran.domain.HighlightClockTest" \
                                --tests "com.beautifulquran.ui.reader.WashResetTest"
    ./gradlew assembleDebug
    ```

    The automated engine tests prove the policy; screenshots at formation/peak/
    fade remain required for paint quality (see [GLIMMER.md](GLIMMER.md)).

## 8. Known sharp edges

*   The Detector line is lab-only. The app ships no `android.permission.RECORD_AUDIO`.
*   Float PCM input (when `enableFloatOutput` is true) leaves the tap idle — the
    16-bit path is the exercised one for everyayah MP3s. A float packet would
    silently become "no shimmer" on a device that prefers it.
*   Very short ayah-item handoffs to a 1 s ayah on single-ayah repeat can
    freeze the highlight at the old clock value until the raw catches up if the
    estimate never converges — bounded to ≤ ~0.8 s of highlight-early residue by
    the clamped settle and the 45-poll cap. The alternative (replaying the wash)
    is worse.
*   `docs/ornaments.css` and `docs/ornaments/*.svg` are generated (web ornament
    pipeline) — do not hand-edit. `npm run build:ornaments` from `web/` rewrites
    them and a different seed grows a different composition.
