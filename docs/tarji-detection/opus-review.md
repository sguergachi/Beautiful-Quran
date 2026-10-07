# Tarjīʿ detection: Opus 5.5 review of the three proposals

Reviewer: Claude Opus 5.5 (`claude-opus-5-5`), first and only review round. I reviewed the code and wrote this document. I edited no code, data or tests.

**Verdict.** All three methods can be built and are genuinely different from each other, but none should ship as written. Cycles is sound once its staleness timeout is fixed. Spectrum needs one extra test, because a high Q alone does not show that a periodic pulse is present. The research note's own table shows a single bump scoring Q=0.90 and coloured noise scoring 0.75, both above its proposed Q_on of 0.6. Recording has a real ownership hazard: it backdates event starts into the preceding word. The Fatiha audit already records that Yasser's event starting before the word boundary is withheld. Nothing here establishes accuracy for any reciter. The Hani JSONs are unlabeled. The 26 clips are only evidence that the code runs.

## REQUIRED shared tweaks

**R1. One feature seam, and the baseline stays bit-exact.** At the end of `onHop()`, `Tarji` fills a reusable `TarjiFrame`. It does not allocate and does not run a second YIN. Fields: `hop, hopMs, hopRms, rms80, climaxLevel, voiced, holdMs, holdStartHop, holdPitchHz, f0Hz (folded, NOT carried), yinDip (d′ at chosen lag), yinThresholdHit, f0Valid, pitchLeadHops`. Define `f0Valid = yinThresholdHit && voiced && holdPitchHz>0 && f0 finite in 70–350 Hz && the 40 ms frame lies inside the current hold`. Leave the baseline `pitchEnv` carry-forward and the `MIN_CLARITY` fallback exactly as they are. Add a golden test before refactoring: hash the per-hop `tremoloGain/eventStartHop/lastRateHz/visualUsesAmplitude` across the three WAV fixtures and all 40 Hani 2:14 phases, and require the hash to match afterwards. In Current mode the only added cost is the frame writes.

**R2. One evidence feature per channel, shared by all three experimental modes.** If each mode uses a different feature, an A/B comparison measures the features rather than the methods. Use these:
- **AM:** `a[k] = ln(max(1e-5, ¼r[k−1] + ½r[k] + ¼r[k+1]))` over the 20 ms `hopRms`. This is the same blur `TarjiEarPulse` paints, and it costs one hop of latency. Do not use `rms80` for experimental evidence. Its boxcar keeps only 0.45 of an 8 Hz modulation and 0.23 of a 10 Hz one, and it nulls and inverts above 12.5 Hz. The 1-2-1 blur on 20 ms frames keeps about 0.74 at 8 Hz and 0.61 at 10 Hz. **The same `minTremoloDepth` therefore does not mean the same physical sensitivity as the baseline.** Document this in the Lab, and do not inverse-filter to compensate.
- **FM:** `p[k] = 1200·log2(f0/ref)`, where `ref` is the first valid F0 of the hold and is fixed for that hold. Use the drifting EMA `holdPitchHz` only for octave folding. Invalid hops get weight 0. Never forward-fill, interpolate or let them count toward coverage.
- Depth floors: AM half-excursion in log units is `atanh(minTremoloDepth)`. FM half-excursion is 10 cents. Both are floors on the measured feature.

**R3. One small `TarjiEventStage`, used only by the experimental modes.** Do not refactor the baseline lifecycle. It is load-bearing and entangled with `scanModulation`. The stage consumes per-hop `Evidence{amOpen, amKeep, fmOpen, fmKeep, rateHz, depth, levelBalance}` and keeps these existing semantics verbatim:
- Gates: `holdMs ≥ holdMinMs`; `minVolume` with a 0.7 off-ratio; AM opens only when `levelBalance ≥ 0.35`, while FM ignores that guard (the Chesterton fence from the Hani loud-to-quiet step).
- Channel: AM is preferred when both channels open, then locked for the event, falling back only when the chosen channel loses keep.
- Persistence: the event ends after `PULSE_GAP_PERSIST=10` hops without keep, extended during the first 50 event hops to two periods.
- Climax tail: the peak is taken from detection onward, and the event ends when the level stays below 0.52 for 24 hops. The same-cadence renormalisation applies when the rate ratio is ≤3.
- New events: a steady gap of ≥10 hops allows a new event on the same pitch.
- Gain: identical to the baseline's: `ramp(eventHops/50)·clamp((level−0.52)/0.23)`, with the attack/release taus and a 50 ms dry at end-of-hold.

Every experimental "open" already requires periodic evidence, so events are confirmed from the moment they start. The stage needs no unconfirmed or false-start path; delete that machinery from this copy rather than porting it.

**R4. Modes replace only the decision fields.** A mode supplies `{gain, eventStartHop, rateHz, usesAmplitude, reverberating}`. `TarjiEarTrack` always stores the tap's own `hopRms/pitch/pitchLead`. The light is always `TarjiEarPulse` over measured RMS/F0, with the period clamped to 6–16 hops. No fitted phase and no sine. Add a test that the stored voice series is identical across all four modes for the same PCM. The source-PTS/sink-snapshot clock, the speed-scaled trims, the display/smoothing lead and the routed-device path all stay as they are.

**R5. Mode switch.**
- Store a mode generation per ear-track slot. `read()` returns gain 0 and no event for slots whose generation differs from the current one. Without this, the 150–500 ms backlog already published replays the old mode's events.
- Add `mode` to the keys of `rememberTarjiGate`'s `LaunchedEffect`, so a fresh `TarjiWordGate` and `GlintLight` are created on switch.
- Leave untouched: `hopCount`, `sessionContentMs`, `earClock`, capture, playback.
- Every new event must start at or after the switch hop.
- Causal modes warm up because their feature ring restarts. Optionally, keep a 96-hop ring of shared frames that is always filled (≈6 floats/hop) and replay the within-hold part on switch. That gives evidence immediately, but still never an event that starts before the switch.
- Seek or flush clears the experiment exactly as it clears `Tarji`.

**R6. Offline correctness (sparklines, Lab, Recording).**
- Result key: `reciterId | audioUrl | mode | ALGO_VERSION | detectorKnobs`.
- Clear `InkEngine.tarjiTrace` lines when the key changes. Today `apply()` leaves the previous result painted while a new one is computing.
- Pending state = flat, labelled "Recording · pending". It must never fall back to the baseline under an experimental label.
- `analyzeTarjiCapture` has no cancellation checks. Add `ensureActive()` every 64 hops, as `lines()` already does.
- Add `mode` to the Lab's pending comparison so a mode change re-runs the analysis.
- Make the selection a single global dev setting in `InkEngine.Tuning` (`tarjiDetector`, default `Current`). Exclude it from the per-reciter `TarjiLabKnobs` persistence, or `applyToTuning` will flip the mode when a reciter changes.

**R7. Policy.** Developer mode ships in release builds (COMPLEXITY.md: "finish or delete experiments"). Once labelled validation picks a winner, delete the losing modes and the selector.

## A. Cycles: causal extrema tracker (sound, with fixes)

**Findings**
- The `0.75P` staleness timeout will flap. In steady state the next extremum confirms at `0.5P` plus the retreat time. At 1.5× the depth floor, the retreat time is `acos(1−1/1.5)/2π ≈ 0.20P`, and the blur adds about 0.05P at 2.7 Hz. That totals about 0.75P.
- Accepting at `P = 1/1.5 Hz` needs a reliable ring of at least 2.5 periods (84 hops). The research's own 1.26–2.48 s edge result shows this is fragile, so test it rather than loosening the band.

**Recipe**
1. Use a 96-hop ring of `a` and `p` with weights. The span is the within-hold hops, capped at `min(96, ceil(2.5·P_last))` once a period exists.
2. Fit a weighted line, then reweight once with `w·min(1, 2.5σ/|r|)`, where `σ = 1.4826·MAD`. Skip the fit when `Σw < 12`. For FM, apply a valid-only 1-2-1 blur, renormalised; if the centre hop is invalid, the output is invalid.
3. Track extrema with a Schmitt zigzag. Confirm an extremum when the residual retreats by `h` (the half-excursion floor) from the running extreme. Refine its time with a parabolic fit clamped to ±0.5 hop, and keep its value.
4. FM resets its extrema list on more than 2 consecutive invalid hops. It also requires ≥85% valid hops over `[e0,e3]`.
5. From the latest `e0..e3`: `P = ((t2−t0)+(t3−t1))/2` must be in `[1/maxHz, 1/minHz]`. Also require `jitter ≤ 0.20`, each half-fraction in `[0.2, 0.8]`, median half-excursion `≥ h`, and minimum `≥ 0.35·median`. AM additionally reports `levelBalance` as the ratio of mean `a` over the first and last half-cycles.
6. **open** = accepted, and the newest extremum is at most `0.85P` old.
7. **keep** = an accepted P exists, the newest extremum is at most `1.1P` old, and the newest excursion is `≥ 0.7h`.

## B. Spectrum: replace "max-Q" with banded fits plus a split-half coherence test

**Findings**
- The GLS-with-trend algebra (Frisch–Waugh projection, `Q = (c·p + d·q)/⟨r,r⟩`) is correct.
- Taking the maximum Q over about 40 bins with roughly 21 effective samples (a Hann-weighted 32-hop window) is badly biased. The note's own table admits bumps, steps and coloured noise.
- The 12–20 Hz "out-of-band veto" is invalid on `rms80`, which nulls and inverts above 12.5 Hz. It is also unnecessary. A sinusoid fit, unlike autocorrelation, does not put peaks at sub-multiples, so the subharmonic trap the baseline guard handles largely does not arise.
- The stated 15k visits/hop is overspent.

**Recipe**
1. Use three window/band pairs, each clipped to `[minHz, maxHz]` and restricted to the current hold. Within a window, a trial frequency f is evaluated only once the span reaches `≥ 2.25/f` seconds.
   - W1: 32 hops, 4.5–10 Hz, step 0.625 Hz, plus guard bins to 14 Hz.
   - W2: 64 hops, 2.25–4.5 Hz, step 0.31 Hz.
   - W3: 96 hops, 1.5–2.25 Hz, step 0.21 Hz.
   The step is 0.4/T. That gives about 28 bins in total.
2. Weights are quality × Hann over the available span. Precompute cos/sin tables (Float, about 30 KB) and evaluate every second hop.
3. The best in-band bin must be the global maximum of Q over `[minHz−0.5, 14]` within its own channel. Otherwise the evidence is "out-of-band dominant" and nothing opens. Refine f parabolically across 3 bins.
4. **Coherence (the new, essential test).** At the best f, refit trend plus sinusoid separately on each half-window, using the same global time origin. Require `|wrap(φ1−φ2)| ≤ π/3`, `D1/D2 ∈ [0.5, 2]`, and each half's `Q ≥ 0.3`. A single bump or step fails because only one half carries it, and coloured noise fails because its phase drifts.
5. **open:** `Q ≥ 0.60`, coherence passes, depth `≥ h`, coverage `≥ 85%`, and the rate stays within one bin for 3 consecutive evaluations (about 120 ms).
6. **keep:** `Q ≥ 0.45`, coherence relaxed to π/2, and depth `≥ 0.7h`.
7. Depth: AM is `tanh(D)` and FM is D in cents. Rate is the refined f.

## C. Recording: two-sided seeded segmentation (refine its ownership)

**Findings**
- "Event start = first supported cycle" will regress word ownership. Timing boundaries are only accurate to tens of milliseconds, and a backdated onset becomes a `priorEvent`.
- The "centred baseline of two local cycles" is circular, because it needs the period before the period is known.
- Look-ahead extension can push an event into an echo decay that the live tail rule would end.
- EveryAyah files often contain digital silence (MAD = 0).
- Decoding through `MediaExtractor` can keep the MP3 encoder/decoder priming (about 30–40 ms) that ExoPlayer trims, which moves the timeline by up to 2 hops.

**Recipe**
1. Replay the shared extractor over the decoded verse PCM, reusing the `TarjiVersePulse` decode cache. Record the frames, including the baseline hold id. Check cancellation every 64 hops.
2. Calibration may only raise floors: `floorE = max(MIN_FLOOR, minVolume, N + 6·MAD)`. Compute N and MAD over hops with `E < P20` and hold clarity below 0.5. Skip the calibration when there are fewer than 25 such hops or when MAD is 0.
3. Components are runs of a single baseline hold. Never smooth or bridge across them.
4. Pass 1: residual against a centred mean of `round(1/(minHz·hopS))` hops. Shrink the window symmetrically at component edges, which keeps it zero-phase. Detect extrema with the same Schmitt rule as Cycles.
5. Pass 2: from the local same-polarity periods `P_k`, re-detrend each neighbourhood with a centred mean over exactly `P_k`, as `TarjiEarPulse` does, and re-find the extrema.
6. Classify each cycle:
   - **strong:** in band, jitter vs its neighbours `≤ 0.2`, depth `≥ h`, FM coverage `≥ 85%`, and AM adjacent-cycle level ratio `≥ 0.35`.
   - **weak:** depth `≥ 0.7h`, jitter `≤ 0.35`, coverage `≥ 70%`.
   A **seed** is at least 2 consecutive strong cycles (5 extrema) in one channel. Extend seeds both ways through contiguous weak cycles. Bridge holes only up to `min(120 ms, 1·P)`, only when both sides are bracketed, and only inside the component.
7. Turn the accepted regions into per-hop evidence, then run them **forward through the same `TarjiEventStage`**. As a result, the event start is `max(region start, holdStart + holdMinMs)`, the tail obeys the 0.52×24 rule, and channel and gain follow R3. Publish `acousticOnsetHop` as a separate diagnostic field.
8. Live use: sample the arrays at `ear.mediaMs` for the matching item key only. A gapless handoff, a mismatched key or an unknown clock gives zero.
   - The phase still comes from the live tap's ear track, using the result's channel and rate. Add a tiny `TarjiEarTrack.readPulse(contentMs, usesAmplitude, rateHz)`. Never take the phase from the decoded file.
   - Measure the decode-to-tap offset once per item. Cross-correlate the decoded `hopRms` against the ear track's `hopRms` over ±6 hops after 1.5 s of play. Apply the offset when the peak correlation is ≥0.9 and show it in the Detector line. Otherwise mark the item "unaligned" and output gain 0.
9. In the Lab, Recording analyses lead-in plus capture and is labelled **"capture-local"**. Capture timestamps are session-relative, and the note itself says whole-verse calibration is not reproducible from a capture.

## Tests and measurement gates

- **Synthetic, through the real extractor at 8 kHz and on the 8,820 Hz/176-sample path, with 32 phases each:**
  - AM-only, FM-only (10/25/60 ¢) and mixed AM+FM at relative phases 0, π/2 and π, at 1.5/2.2/2.7/5/8/10 Hz.
  - Modulation starting only in the last 0.4/0.6/0.8/1.0 s of a hold.
  - Negatives: constant tone, exponential and sigmoid crescendos, glide, note step, single bump, gain step and loud→quiet step (AM must not open), two pulses only, and coloured noise at 3 SNRs.
  - Harmonic carriers at 70–350 Hz in 1 Hz steps, to catch false AM from 20 ms carrier beats.
  - 12–16 Hz texture over a 2.7 Hz swell.
  - 1–2 hop and 60 ms F0 dropouts, and octave flips.
  - Report per mode: acquisition time, false events, events per negative minute and rate error. The 2.7 Hz case should acquire within about 0.9 s in Cycles and Spectrum. Recording must never give an `eventStart` earlier than `holdStart + holdMinMs`.
- **Real audio:**
  - The golden baseline hash (R1).
  - On the three WAV fixtures across all 40 hop phases, for every mode: no event that starts before the word in Hani 2:14, a zero-hop pulse/RMS correlation > 0.85 that beats either adjacent hop (unchanged, because the pulse is shared), and the existing word-gate counts reported, not asserted.
  - Rerun the 26-clip and 13-reciter Fatiha audits per mode and record the results as coverage only.
  - Accuracy needs listener labels: positives, matched stills, echo tails and consonants, frozen before tuning, with held-out recordings and reciters, reported per reciter.
- **Sync, switch and seek:**
  - A switch mid-event leaves no old-mode gain in the backlog, and every new `eventStart` is at or after the switch hop.
  - `hopCount`, `sessionContentMs` and the ear clock are unchanged by a switch.
  - Seek and flush clear the experiment. Pause holds. Speed 0.75/1.5 keeps zero-hop phase. Reciter, knob and mode changes never apply stale results. Pending and unaligned states read zero.
- **Performance:** JVM benchmark ns/hop (p50/p95) per mode over the Alafasy WAV ×20. Starting gates:
  - Current within 5% of master.
  - Cycles ≤ 20 µs/hop and Spectrum ≤ 150 µs/hop on JVM.
  - Recording post-feature work ≤ 50 ms per audio-minute.
  - On the Pixel, `DevProfiling` hop-time p95 per mode, and no steady-state allocations on the audio thread. These are targets to measure, not results.

## Optional refinements

- Warm-up replay from the shared frame ring (R5).
- A Cycles fifth extremum for stricter acquisition.
- A Spectrum second-harmonic term, only if labelled non-sinusoidal holds are missed.
- An FM noise floor from labelled still spans in Recording.
- Trimming `KEY_ENCODER_DELAY` in the verse decoder instead of measuring the offset.
- A Recording-mode Viterbi pass only after labels show the hysteresis method fails.
