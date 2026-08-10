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
at 8 kHz directly. On device the tap decimates the sink's PCM to roughly
8 kHz mono and publishes one detector state per ~20 ms content hop.
The original 2,048-sample handoff updated the renderer only every ~232–256 ms:
four visible states per second cannot express a 5–10 Hz vocal pulse.

### Signal chain

1.  **Frame → RMS.** An 80 ms rolling frame (4 hops) yields one RMS value
    every 20 ms, pushed into a 64-hop (≈1.3 s) evidence ring. `PEAK_DECAY`
     tracks the noise floor the hold gate uses. The newest 20 ms hop also gets
     its own RMS; it is the live intensity phase, never the event classifier.
     The acquired event start travels in the same history clock as the
     reverberation flag, so ownership never mixes raw detection with delayed
     gain.

2.  **Pitch.** The established 80 ms normalized-autocorrelation tracker over
    the reciter range 70–350 Hz remains the hold-identity clock.
    The lag range and Hz conversion scale with the decimated samples per hop
    (lags 22..114 at 8 kHz, wider for the 176-sample 44.1 kHz path). The
    shortest lag within 5 % of the best wins (otherwise lag *L* vs *2L* flips
    hop-to-hop and resets every hold).

    **Octave folding on the hold check.** Autocorrelation flips freely between
    a period and its double on real voice. `samePitch(a, b)` folds ratios by
    powers of 2 within ±8 % — so 156.9 Hz and 320 Hz are the same note. The
    running hold pitch is tracked *in the anchor's octave* so a one-hop flip
    never drags it.

    A separate 40 ms YIN-style difference tracker supplies the modulation
    path. Its sub-sample lag interpolation can see small periodic F0 movement
    that the whole-lag hold tracker quantizes away. This separation is
    intentional: better vibrato sensitivity must not move any existing hold,
    word, climax, or echo-tail boundary.

3.  **Hold.** A hold is a voiced, pitch-stable single note. `clarity ≥ 0.5`
    and `rms ≥ floor(MAX_FLOOR, 0.15·peak)` marks voiced; the pitch gate above
    keeps it one note. `holdMs += 20` while voiced+same-note; 6 hops of
    grace (`MAX_MISSES`) on estimation glitches; a pitch step restarts the hold.
    `HOLD_MIN_MS = 300` before reverberation is considered.

    Windows for the envelope scan start **at the hold onset**
    (`holdStartEnvCount`), not at "now" — otherwise the syllable attack ramp
    poisons the depth estimate for ~1 s.

4.  **Modulation scan.** Over the within-hold window (20..64 hops), a linear
    trend is removed independently from the RMS and F0 tracks. This matters:
    a plain crescendo or pitch glide is strongly autocorrelated but is not
    reverberation. Alternating residual crossings first prove that the shape
    oscillates; autocorrelation then measures its period.

    *   depth = `√2 · rms(residual) / mean` — the shipped
        tarjīʿ band is 1.5–10 Hz (`VoiceEnergy.maxTremoloHz`).
    *   rate = envelope autocorrelation peak. Lags `ceil(minLag(maxTremoloHz))`..33
        (1.5 Hz floor) with a **ceiling-only harmonic guard**: a true period
        above the scan floor still correlates at double its lag — without the
        guard a 5.5 Hz vibrato reads as 2.8 Hz under a 4 Hz ceiling. Guard
        rejects only when an *out-of-band* short peak is an exact submultiple
        of the in-band pick. The old in-band half-lag veto also killed real
        tarjīʿ on Alafasy/Hani 1:7 (slow swell + ~10–25 Hz texture). A new
        note scans only lags for which it has enough current samples; stale
        long-period bins from the preceding note must never seed its rate.
    *   acquisition needs alternating residual crossings, `bestC ≥ 0.4`, and
        a rate in band. For **AM acquisition**, the leading/trailing quarters
        of the window must also be within ~3× level: a sharp loud-to-quiet
        step can leave a curved detrended residual that resembles a fast pulse,
        and must not consume the word before the quieter real sustain arrives.
        This level guard does not veto coherent YIN pitch motion or let unsafe
        AM own an FM event's visual phase. A raw-envelope correlation view is
        used only to bridge a level transition after detrended evidence has
        already acquired the event.
    *   coherent amplitude modulation **or** coherent pitch modulation may
        acquire the event. About ten cents of periodic F0 excursion clears
        the pitch-depth floor. Pitch depth alone can never acquire or bridge:
        note transitions and octave errors can be deep, so the pitch channel
        must retain repeated-cycle evidence. Mixed AM+FM naturally passes;
        amplitude is the preferred visual polarity when both acquire together.
        The acquired phase channel stays fixed while it remains coherent, so
        a near-threshold AM track cannot flip an FM-driven glint by one frame.

    **Band limits and hop rate.** The envelope is sampled at 50 Hz (20 ms hops).
    Its mathematical Nyquist limit is 25 Hz, but the rolling 80 ms RMS window
    has its first null at 12.5 Hz and phase-inverts the lobe above it. Runtime
    controls and detector inputs therefore stop at the 10 Hz product band.
    The live 20 ms phase path no longer inverts there, but the long evidence
    envelope is still the wrong classifier above its first null.

    Hysteresis: stricter to switch *on* than to stay on (`DEPTH_OFF_RATIO`
    0.7, band widened by 1 Hz) — no flapping when the reverberation breathes.
    `tremoloGain` ramps 250 ms attack / 800 ms release. Evidence readiness is
    derived from the configured slowest period and the minimum correlation
    pairs, rather than a hard-coded timeout. Ten hops without coherent pulse
    evidence end an established event; deep AM can bridge a brief irregular
    climax only after a genuine period has been acquired.

    The level peak begins when tarjīʿ is first detected—not at the consonant
    attack—so Hani's room echo cannot make the sustain look like a dying tail.
    A sustained fall below 0.52 of that peak ends the event. If the same pulse
    cadence continues at a quieter stable level, the detector re-normalizes the
    peak and grants only enough time for the rolling window to replace the old
    level. A large cadence change is a new articulation and is allowed to end.
    After a genuinely steady gap, a second acoustic event may be acquired even
    on the same pitch. The released effect uses a 50 ms time constant and
    falls below the visual event gate in about 200 ms.

5.  **Live modulation signal.** Detection and visualization have different
    clocks. The long detrended tracks decide whether a periodic held-note
    event exists; they never synthesize its phase. For AM, `tremolo` is the
    current 20 ms RMS relative to a linearly extrapolated local baseline. Its
    slope is measured across complete modulation cycles, which cancels the
    pulse itself while removing a crescendo. For pitch-only vibrato, it is the
    current short-YIN F0 residual against the same trend model. The selected
    YIN lag determines the analysis support centre (about 0.5–0.8 hop behind
    the live RMS centre); a three-sample local projection follows the F0
    curvature to the current hop. Neither path rotates phase from the noisy
    modulation-rate bin. `tremolo` is zero-centred, ~−1.5..1.5.

6.  **Output latency.** The PCM tap hears the voice *before* the listener.
    `ReaderViewModel` pushes the same route preset `HighlightClock` subtracts.
    The AudioTrack buffer supplies the session's initial tap-to-head delay;
    exact tap content time versus `positionMs` then tracks only queue growth or
    drain, without falling toward zero while an EMA warms up. Measured backlog
    is already content-time, so playback speed is applied only to wall-time
    route/buffer values. The reported
    `syncReverberating`/`syncTremolo`/`syncTremoloGain` are read through a
    64-hop history ring on the same playback-head reference as the word ink;
    fractional reads interpolate between hops instead of rounding the shimmer
    as much as 20 ms early.
    The hop clock includes the 80 ms analysis-frame warm-up; omitting those
    first three hops under-delays every session by 60 ms.

### What the real recordings taught us

*   Alafasy 1:7's ḍād sustain produces one coherent event at ~7.68–9.74 s.
    The later lām/nūn energy remains loud and uneven, but loses the original
    pulse's coherence and must not inherit its deep-AM fallback.
*   Hani 1:7 carries an echo-heavy, high-F0 attack followed by a sustain around
    ~8.5–10.4 s. Its audible AM fundamental is near 5 Hz, while the long-window
    autocorrelation can jump among slow trend, harmonic, and 10 Hz bins. Rate
    remains useful evidence, but it no longer rotates the visible phase.
    Referencing the detected sustain instead of the much louder attack keeps
    that event alive. Later consonant and echo pulses can still be real
    acoustic events; the per-word event gate prevents them from visually
    relighting the same active word.
*   A plain-periodicity scan locked on a 2 Hz swell reads it at ~16 Hz
    (lag 3 wins on a smooth decay) — the band floor **and** the low-pass
    interact. The autocorrelation scan plus the 1.5 Hz floor are both needed.
*   Both full EveryAyah recordings are committed as 8 kHz regression fixtures.
    Besides event/tail topology, the tests require the shimmer's flicker
    changes to correlate with live 20 ms RMS changes above 0.85 at zero hop
    and beat either adjacent hop. Synthetic fixtures independently pin
    AM-only, FM-only, mixed, crescendo, pitch-glide, loud-onset, and
    high-carrier/10 Hz edge cases.

## 4. When the glimmer is allowed to pulse

Four gates, all pure alpha (never positional — the reveal edge never moves
mid-animation, so the bloom can never appear to restart):

1.  **Eligible word:** `TajweedPacing.Curve.hasStrongHold` — a hold on the
    word's *own* letters (long madd, ghunnah, or the verse-closing waqf).
    A wasl-entry hold alone sustains the *previous* word's nūn and never
    qualifies — those are the "shimmer on words with no holds" reports.
2.  **Audible reverberation:** `VoiceEnergy.shimmerGain (= isLive ?
    tremoloGain : 0) > 0.01`. No detection → still gold, even on the longest
    steady waqf. This is the product law the last round established.
3.  **Active word:** `isActive` — first-pass white-gold **and** repeat
    terracotta. The gate hard-closes at handoff: the dry-down dissolve is
    never modulated.
4.  **One acoustic event per utterance:** `TarjiWordGate` ignores gain inherited
    from the preceding word, waits for this utterance's live detection, lets
    that sustain breathe, then latches off when it settles. A later consonant
    or room-echo pulse cannot relight the same active word. A repeat transition
    creates a fresh gate for the new performance event. The detector carries
    the event's start through the same delayed history as its gain and pulse,
    then maps that start to the media-item clock. A pulse whose start precedes
    the active word cannot cross its boundary through output latency; only an
    event that actually starts inside the word can arm it.

When all four pass, tarjīʿ **turns the glimmer on and off** with the voice
(not a soft breath around permanent sheen). The sign is acoustic phase:
positive is a vocal swell and negative is its trough.

```
pulse = smootherstep((clamp(tremolo, -1, 1) + 1) / 2)
crest = smootherstep(clamp(tremolo, 0, 1))
gated = 1 − depth·(1 − pulse)
mult  = 1 + g·(gated − 1)                // g = tremoloGain
peak  = g·depth·crest
```

At `depth = 1` (shipped) swells leave the sheen fully on and troughs
extinguish it — gold-on-gold needs that contrast. `crest` alone boosts the
colour. Never use `abs(tremolo)`: it flashes on both the loud crest and quiet
trough, doubling the visual pulse rate. At `g = 0` the multiplier
is exactly 1 — **no tell** before the voice actually reverberates. The halo
forms only with the directional wash (`smootherstep(glintProgress)`); there is
no whole-word formation floor when resonance engages.

**Frame gate:** the Active strong-hold word runs a vsync sampler
(`rememberTarjiGate`) that writes the multiplier every frame. The wash's
Animatable stops invalidating draw once the word parks; without the sampler
the pulse freezes on long closers (1:7) even when the detector is live.

The free-running sine that used to run inside the waqf window on steady holds
is gone — steady holds keep still gold.

### Ink Lab

*   **Tarjīʿ** toggle (`glintResonance`), **Pulse depth**
    (`glintResonanceDepth` 0–1, shipped 1), **rate band**
    (`tarjiMinHz` / `glintResonanceMaxHz`), **Hold before tarjīʿ ms**,
    **Min depth**, **Min periodicity**, **Pitch drift**, **Attack / Release
    ms**. All push live into `VoiceEnergy` → `Tarji`. The panel shows a live
    **Detector** line under the toggle (polled every 200 ms while open):
    `tarjīʿ · hold 1.2s · 4.8 Hz · gain 0.84` / `holding … — no tarjīʿ yet` /
    `listening…` / `silent — no PCM` — so vanishing shimmer is diagnosable
    (no hold, wrong rate, depth, silent sink).

Any sink flush/seek/reconfiguration clears the detector, its history, and the
partial PCM hop. A discontinuity is a new acoustic event; phase or gain from a
previous word must never leak into it.

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
contract `gain = 0 ⇒ multiplier ≡ 1` so a not-yet-reverberating word stays
pixel-identical to a plain word. Do not reintroduce a whole-word halo
formation floor keyed on detection gain — that lit the entire glyph slightly
ahead of the directional reveal.

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
    ghunnah hum are the canonical ear cases — open the Ink Lab Tajweed tab
    and watch the **Detector** line. A steady hold without an audible pulse
    stays `holding … — no tarjīʿ yet` and still gold by design; a pulsing
    hold flips to `tarjīʿ` within ~0.6 s of the reverberation's onset and the
    gold rides it.
*   **Tarjīʿ max rate** tops out at the 10 Hz product band. The
    harmonic guard keeps 5.5 Hz vibrato from masquerading at 2.8 Hz under a
    lower ceiling. A reciter's higher vocal pitch is handled separately by the
    70–350 Hz pitch tracker; it is not a reason to admit high-rate envelope
    texture.
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
