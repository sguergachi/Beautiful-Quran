# Modulation spectrum evidence for tarjīʿ

## How should a compact spectral detector combine amplitude, pitch, quality and harmonic evidence?

### Takeaway

Use one **quality-weighted modulation-spectrum bank**, implemented as a sinusoid fitted against a local linear trend, independently for AM and FM. An AM **or** FM channel can acquire: agreement between channels may strengthen a diagnosis, but it must never be a prerequisite. This is an acoustic periodic-held-voice candidate, not a theological determination.

### Cited Findings

- Nakano, Goto and Hiraga's frame-wise vibrato detector takes an STFT of the first difference of the F0 contour, with a 320 ms Hann window. It uses normalized spectral power and spectral sharpness, then also requires repeated crossings of the mean. Their 5–8 Hz and 30–150 cent limits describe their singing task; they do not establish limits for Qur'an recitation. — [Original Interspeech paper](https://www.isca-archive.org/interspeech_2006/nakano06_interspeech.pdf)
- Ventura, Sousa and Ferreira convert F0 to a logarithmic/MIDI scale before spectral analysis. They describe a smoother sinusoidal vibrato shape in that scale, emphasize F0 discontinuities, and use a sliding FFT with interpolation of the dominant spectral peak. Their natural-voice evaluation had no ground truth and assessed continuity/smoothness; their reported synthetic estimation errors are not detection accuracy on recitation. — [Authors' university PDF](https://fe.up.pt/voicestudies/artts/doc/publications/086%20-%20ACCURATE%20ANALYSIS%20AND%20VISUAL%20FEEDBACK%20OF%20VIBRATO%20IN%20SINGING.pdf)
- Zechmeister and Kürster express weighted sinusoid fitting as a generalized periodogram. Their normalized power is the relative reduction in weighted squared error, bounded from zero to one; weights and a fitted offset matter. This is a general spectral-estimation source, not a vocal detector. Extending its nuisance model from an offset to offset plus slope below is our proposed adaptation. — [Original paper, equations 4–6 and section 3](https://arxiv.org/pdf/0901.2573)
- Dromey, Reese and Hopkin studied microphone and electroglottographic AM in 17 female singers across pitch and loudness conditions. They explicitly investigate the distinction between laryngeal AM and acoustic AM arising from harmonics moving through vocal-tract resonances. — [Authors' repository record](https://scholarsarchive.byu.edu/facpub/1779/)
- The source-filter/sinusoidal analysis of vibrato describes pitch as slow intonation plus time-varying extent and rate. Amplitudes of different harmonics vary differently, and reverberation affects instantaneous magnitudes. Consequently a rigid AM/FM phase relation is not a sound universal gate. — [Original research paper](https://academica-e.unavarra.es/server/api/core/bitstreams/4a71a939-859f-4390-a8b2-ce2185370809/content)
- The current app already has a 20 ms hop RMS, an 80 ms RMS/hold tracker and a separate 40 ms interpolated YIN-style modulation tracker. Invalid short-pitch estimates are replaced by the previous value in the baseline's `pitchEnv`; that carried value must not be counted as newly measured FM evidence by an experiment. — [Tarji.kt](../../app/src/main/java/com/beautifulquran/playback/Tarji.kt), [implementation notes](../../docs/TARJI.md)

### Inferences

#### Proposed detector: normalized spectral evidence

This is a proposed engineering implementation, not a verbatim published detector. Keep the baseline and all shared feature extraction. Supply the experiment with a small per-hop record:

```text
hop index, exact hop duration, hold identity/onset,
20 ms RMS, 80 ms RMS, 80 ms voicing clarity,
fresh folded 40 ms YIN F0, YIN clarity, fresh-pitch-valid flag
```

The experiment only classifies evidence. The common hold/climax/event lifecycle and `TarjiEarTrack` remain responsible for event identity, gain and audible/source timing. Do not weaken the existing word gate, acquire/end boundaries, release-tail protections or event ownership.

1. **AM feature:** `yA[i] = ln(max(rms80[i], epsilon))`. The existing 80 ms envelope is the safest first implementation: it already rejects much of the carrier/consonant texture. The visual waveform remains the actual 20 ms RMS. If a later ablation uses 20 ms RMS as evidence, retain it as a separate, tested feature choice: finite-window carrier beats can masquerade as slow AM. Do not quietly switch features as the fitted rate changes.
2. **FM feature:** `yF[i] = 1200 * log2(foldedF0[i] / holdAnchorF0)`, in cents. Fold exact octave errors into the shared hold's octave before this transform. An invalid estimate gets weight zero; do not forward-fill it as a measurement. Preserve the original hop timestamps across missing frames.
3. **Feature validity:** AM weights are zero outside a currently voiced, usable-level shared hold. FM additionally requires a fresh valid estimate and adequate short-YIN clarity, with quality weights such as `qYin²`. Before acquisition, require about 85% valid hop coverage, useful samples in both halves, and no unsupported gap longer than roughly 60 ms. These numbers are initial tuning candidates. A sustained invalid channel becomes unavailable; it does not veto a healthy other channel.
4. **Normalization/detrending:** fit `a + b*t` with the same weights used by the spectral fit. Log AM turns multiplicative recording gain into an offset, removed by that fit. Cents make pitch excursion relative to the reciter's pitch. A straight glide in log pitch or an exponential level ramp has no residual oscillation. This avoids a hard high-pass filter that could remove slow 2.7 Hz motion or rotate the visual phase.
5. **Spectrum:** for each trial frequency, fit `a + b*t + c*cos(2πft) + d*sin(2πft)`. Use the relative improvement over the trend-only model as periodic power:

```text
SSE0 = min(a,b) Σ w[i] * (y[i] - a - b*t[i])²
SSE1(f) = min(a,b,c,d) Σ w[i] *
          (y[i] - a - b*t[i] - c*cos(2πft[i]) - d*sin(2πft[i]))²
Q(f) = clamp((SSE0 - SSE1(f)) / max(SSE0, epsilon), 0, 1)
D(f) = sqrt(c² + d²)
```

`Q` answers how much detrended variance a periodic component explains. `D` is its measured depth: dimensionless log-amplitude for AM, cents for FM. For small AM, log-amplitude depth approximates relative envelope depth. Neither `Q` nor the final score is a probability.

The small exact fit avoids a subtle normalization bug: on short non-integer windows, sine and cosine are not necessarily orthogonal to each other or to the local trend. A simple `2 * abs(DFT)² / totalEnergy` is only an approximation. Summing an oversampled bank's bins as if they were independent also makes the normalized result depend on grid spacing.

#### Minimal implementation of the fit

No FFT or matrix package is needed. Use weighted dot products and two tiny two-by-two solves:

```text
T = [1, t]
P(v) = T * inverse(T'WT) * T'Wv
r = y - P(y)
u = cos(2πft) - P(cos(2πft))
v = sin(2πft) - P(sin(2πft))
A = <u,u>; B = <u,v>; C = <v,v>
p = <u,r>; q = <v,r>
det = A*C - B*B
c = (C*p - B*q) / det
d = (A*q - B*p) / det
periodicEnergy = c*p + d*q
Q = periodicEnergy / max(<r,r>, epsilon)
```

Here `<x,y> = Σ w[i]*x[i]*y[i]`. Skip near-singular candidates and negligible residual energy instead of magnifying numerical noise. Reuse buffers and accumulate in `Double`; published GLS supplies the weighted fitting basis, while linear-trend projection is our adaptation.

#### Depth, noise and confidence

- Start from the existing approximate 3.5% AM / 10-cent FM physical depth floors, rather than accepting a normalized peak at arbitrarily tiny depth. Tune against held-out recordings. The existing absolute PCM floor and any explicit `minVolume` remain meaningful availability/product gates; normalization does not restore information below the recording's noise or quantization floor.
- Require periodic depth above a channel's empirical feature-noise floor. This can be a conservative floor established by steady-voice/quiet/noise fixtures, or a small running estimate from non-candidate holds. Residual error of the best fit provides an additional quality measure, but it includes legitimate irregularity and harmonics; treating all of it as noise will reject expressive holds.
- An initial operating point for comparison is `Q_on ≈ 0.60`, `Q_keep ≈ 0.45`, depth off/on ratio about 0.7, and several consecutive supported updates before acquisition. These are **unvalidated starting values**, not paper-derived optimal thresholds. Prefer calibrating one global operating point, then assessing per-reciter failures, over embedding 13 sets of arbitrary constants.
- A compact score can be `qualityCoverage * ramp(Q, Qlow, Qhigh) * ramp(D, Dfloor, 2*Dfloor)`. Overall evidence is `max(scoreAM, scoreFM)`. Do not multiply AM and FM scores or require phase coherence. Keep the chosen display channel stable through hysteresis; prefer AM when both legitimately acquire together, as the current product does.

#### Harmonic evidence without a subharmonic trap

- Evaluate candidates outside the product band too, up to about 20 Hz, as a veto/diagnostic within the **same channel**. A strong 12 Hz modulation must not open a 6 Hz event just because a slow fit or autocorrelation submultiple is available. This does not authorize detection above the product's 10 Hz ceiling.
- A component at `2*f` can corroborate a non-sinusoidal shape, but it must not create an invented fundamental at `f`. Require independent, measurable evidence at `f` before assigning that slower rate. With amplitude-only data containing only a clear 6 Hz component, claim 6 Hz AM; do not infer an unobserved 3 Hz cycle.
- AM at twice a valid FM rate, or AM/FM with different phase, is allowed. Record the relationship for diagnostics; both single-channel cases must still acquire. An out-of-band AM component cannot globally veto an in-band FM candidate.
- For the first compact implementation, retain the single-sinusoid fit and use harmonic-bank values diagnostically. A full second-harmonic joint fit adds four basis terms and more calibration; add it only if labelled non-sinusoidal holds are systematically missed. Spectral evidence should remain meaningfully distinct from the cycle-tracking proposal.

### Gaps

- I found no primary research that establishes these operating thresholds, rates or channel relationships for labelled tarjīʿ across these 13 reciters. Western singing-vibrato ranges are a starting point for signal reasoning, not transferable ground truth.
- Correlated amplitude noise can produce a convincing peak in a finite window. Neither normalized power nor mean crossings alone guarantees rejection; voicing, meaningful depth, persistence, context and held-out negative labels are necessary.
- Sharing the current 70–350 Hz extractor also shares its availability limits. A reciter whose actual F0 is outside that supported range cannot be rescued by better spectral classification alone.

## What windows, rate bounds, delay and device cost make the proposal practical?

### Takeaway

A 32-hop fast branch and a 64-hop slower branch, both updating every 20 ms, are a small fit for the existing pipeline and cover the important 2.7 Hz case. Spectral analysis controls confidence and approximate rate; the real waveform, source event timestamp and audible playback clock continue to control the light.

### Cited Findings

- Sysel and Rajmic derive generalized Goertzel evaluation at non-integer frequency indexes, so a bank need not round trial rates to FFT bins. A Goertzel bin costs approximately `3*N` real operations in their accounting. They also show that Goertzel is not universally cheaper than FFT: its advantage is strongest for few requested frequencies. Its fractional-index phase correction is irrelevant if only magnitude is used, but matters for interpreting phase. — [Original open-access paper](https://link.springer.com/article/10.1186/1687-6180-2012-56)
- Rossignol et al. compare signal- and F0-trajectory-based approaches to vibrato detection, extraction and estimation; separating the intonation trajectory from its oscillatory component is part of their framing. — [Original DAFx paper](https://www.dafx.de/paper-archive/1999/rossignol.pdf)
- The current detector's evidence ring is 64 hops, nominally 1.28 s. Its 80 ms RMS envelope has a first null at 12.5 Hz, so the product caps detection at 10 Hz even though the hop sequence's Nyquist limit is 25 Hz. — [Tarji.kt](../../app/src/main/java/com/beautifulquran/playback/Tarji.kt), [band/support explanation](../../docs/TARJI.md)
- `TarjiEarClock` maps source and sink presentation timestamps onto heard content; `TarjiEarPulse` evaluates the measured RMS or pitch series around the ear's instant. Rate never supplies a synthesized oscillator. — [TarjiSyncClock.kt](../../app/src/main/java/com/beautifulquran/playback/TarjiSyncClock.kt)

### Inferences

#### Rate and window policy

- Preserve the product's configured 1.5–10 Hz range. Start the bank at about 0.75 or 1 Hz and extend to 20 Hz for slow-trend/out-of-band comparisons; use trial increments around 0.25 Hz. That grid interpolates the spectral shape; it does **not** magically give a 640 ms window 0.25 Hz resolving power.
- Analyze the newest 32 usable history hops for faster rates and up to 64 within-hold hops for slower rates. A Hann weighting is a conservative initial choice for leakage; its effective evidence concentrates near the window's middle. Do not append zeros or samples from a preceding hold when the window is not ready.
- Require at least approximately 2.25 candidate cycles of current-hold support and genuine alternating residual excursions before acquisition. Then `T_ready >= 2.25/f`. At 2.7 Hz this is about 0.83 s / 42 hops; a 32-hop-only method is insufficient, while the 64-hop branch can acquire. At 5 Hz it is about 0.45 s, and at 10 Hz about 0.225 s, although the shared minimum hold and minimum useful fitting window may delay acquisition further.
- The same readiness rule needs 1.5 s / 75 hops at 1.5 Hz. **Do not claim that a 64-hop ring provides that evidence.** If the experiment must fully support the configured 1.5 Hz floor, a separate 96-hop evidence ring is a very small honest extension; keep the existing audible history untouched. Otherwise explicitly report insufficient low-rate evidence. For the user's 2.7 Hz floor, 64 is enough.
- Let the shorter branch refresh a rate as it accelerates; permit nearby bins/short-term drift rather than demanding one rate over the whole hold. A chirp spreads energy, so shorten the evidence window as rate rises. Very rapid or irregular changes may fall below spectral confidence and should release or bridge only under the existing lifecycle's rules.
- Use exact `hopContentDurationMs` for trial frequency/time support, not an assumed 50 Hz. This matters for the 176-sample, 8,820 Hz path and capture replay.

#### Acquisition latency is separate from phase

The slow 2.7 Hz branch needs approximately 0.83 s of oscillation plus a short persistence interval and whatever shared hold minimum applies. With a 60–100 ms acquisition persistence, about 0.9–1.0 s is a reasonable **design budget**, not measured app latency. An existing 1,200 ms hold knob can dominate this budget. An 80 ms feature warm-up/support is also part of the available evidence.

A full 64-hop Hann fit describes evidence centered roughly 0.64 s behind its newest sample. This does not require delaying the visible waveform by 0.64 s. Once confidence permits the event, use the unchanged measured pulse through the audible history. Preserve causal event-source semantics: do not backdate an acquisition to the window centre or move it into a previous word to make a plot look earlier. Existing immediate word/climax/voice gates must continue to stop stale long-window evidence.

The fitted sinusoid's phase is disposable. Never render `sin(2π*rate*clock)`, rotate the measured light to a fitted phase, or change `TarjiEarPulse` to match a spectral peak. Likewise, the strategy toggle must not change hop count, PCM support centre, route trim, sink timestamp mapping, or pause/seek behavior.

#### Cost and Kotlin shape

A direct projection is preferable to introducing a DSP library or a numerically persistent sliding DFT. For a 0.75–20 Hz bank at 0.25 Hz steps, there are about 78 frequencies. Two channels over 32+64 windows involve approximately `78 * 2 * 96 = 14,976` sample/bin visits per hop, around 0.75 million visits per nominal second. Each visit includes several multiply/add operations; weighted exact fits therefore cost more than a bare Goertzel magnitude. These are arithmetic counts, **not a measured Android runtime**.

Precomputed sine/cosine tables for 78 frequencies and 64 hops use roughly 40 KB with `Float` storage, or about 60 KB for 96 hops. Per-hop feature/validity arrays are a few KB. Reuse arrays; calculate logarithms once per feature hop; use `Double` only for sums/solves. A whole compact scanner can live in one pure JVM file with a reusable two-by-two trend/basis helper. No runtime FFT, ML framework, native code or model download is required.

If profiling makes a full fine bank unnecessary, coarse 0.5 Hz search followed by a few finer neighboring trials is an optional optimization. Do not claim Goertzel beats an FFT for all 78 bins, and do not add a complex recursive streaming spectrum merely to save these small windows.

```text
onHop(frame):
    push fresh RMS/pitch/quality with original hop time
    if shared hold is ineligible: return unavailable evidence
    for channel in [AM, FM]:
        scan eligible short/long windows using weighted trend + trial sine fit
        reject insufficient coverage/cycles/depth/excursions
        retain strongest supported in-band candidate
        check same-channel stronger out-of-band explanation
        update supported-rate/confidence hysteresis
    emit evidence = max(AM, FM), chosen channel, rate, diagnostics
    common event/lifecycle + audible history consume it
```

### Gaps

- No Android benchmark of this implementation exists yet. Measure total audio-thread time, p95 hop time, allocations and backlog on the Pixel, particularly while all developer strategies are running for comparisons.
- The 80 ms evidence envelope attenuates upper-band AM. A weak amplitude-only 10 Hz pulse may be missed even with perfect spectral shape. Avoid compensating a near-null by unbounded division; test any switch to a shorter AM feature against carrier-beat negatives first.
- Abrupt cadence changes remain a tradeoff between confidence and latency. The short/long branches are a compact compromise, not proof that every expressive rate trajectory will pass.

## Which negatives and cross-reciter tests would establish whether it works?

### Takeaway

Evaluate event behavior on real labelled holds and hard negatives, not just whether a modulation-rate estimate looks plausible. Select the operating point with reciters and recordings held out; preserve the existing waveform/clock tests separately from classifier comparisons.

### Cited Findings

- The app commits full Alafasy 1:7 and Hani 1:7 8 kHz WAVs, plus Hani 2:14 decimated PCM at 8,820 Hz. The tuned Hani JSON captures are explicitly `UNLABELED`, even though they contain user knobs and capture bounds. Their intervals are not independent positive ground truth. — [TarjiFixtures.kt](../../app/src/test/java/com/beautifulquran/playback/TarjiFixtures.kt), [committed fixture folder](../../app/src/test/resources/tarji/), [current regression tests](../../app/src/test/java/com/beautifulquran/playback/TarjiTest.kt)
- The source-filter research cautions that reverberation changes instantaneous harmonic magnitudes; a narrow phase/coherence rule derived from anechoic signals does not transfer unchanged to room recordings. — [Original paper, section 3](https://academica-e.unavarra.es/server/api/core/bitstreams/4a71a939-859f-4390-a8b2-ce2185370809/content)
- Generalized-periodogram significance calculations depend on noise and sampling assumptions. Overlapping voice features and temporally correlated envelopes do not justify interpreting raw fit power as a Gaussian-noise false-alarm probability. — [Original periodogram paper, section 3](https://arxiv.org/pdf/0901.2573)

### Inferences

#### Meaningful synthetic and feature tests

Use harmonic-rich synthesized voices and actual shared feature extraction, as well as direct feature tests of the bank. A sine carrier alone is useful but insufficient.

| Test family | Required behavior |
|---|---|
| AM-only at 2.7, 5, 8, 10 Hz; FM-only at the same rates | Each channel can acquire independently above adequate depth/quality; the opposite channel can be flat or invalid. |
| Mixed AM/FM, phase 0, π/2, π; AM at twice FM rate | No strict coherence requirement; channel selection remains stable and output follows measured waveform. |
| Modulation-rate sweep 2.7→4→7 Hz; slow/fast depth breathing | Do not stick to a stale harmonic bin; quantify acquisition/dropouts and approximate-rate error. |
| Constant note, amplitude ramp, exponential crescendo/release, log-linear/curved pitch slides | Zero/weak evidence or too few alternating excursions; no event from a monotone gesture. |
| Loud onset then quiet sustain; isolated bump; gain step; two pulses only | Do not spend a word's event before a later coherent sustain; warm-up cannot fabricate cycles. |
| Ordinary syllable-rate AM on a voiced phrase; consonant attacks; repeated words | Shared hold/word context and classifier should reject labelled non-held candidates. A periodic AM curve alone cannot establish the intended event. |
| Steady sine and harmonic-rich carriers across 70–350 Hz, especially hop/period near-integer boundaries | No false AM from finite RMS-window integration/alias beats. Repeat at 8 kHz and the actual 8,820 Hz / 176-sample path. |
| Noise at multiple SNRs; correlated amplitude/pitch noise; quantized tracking; octave jumps | Depth and validity do not promote tiny or fabricated periodic tracks; correctly folded octave jumps do not become vibrato. |
| One/two-hop pitch dropouts and longer invalid gaps | No phase/history corruption; longer gaps lose FM confidence while a legitimate AM channel may continue. |
| In-band true rate plus harmonics; out-of-band 12–20 Hz modulation | Do not invent a slower subharmonic or veto valid FM merely because AM has an out-of-band component. |
| Dry note convolved with measured/constructed room responses; delayed copies; release tails | Quantify echo false positives and tail overshoot; shared lifecycle remains authoritative. If echoes create actual periodic intensity within the feature space, identify the ambiguity rather than asserting perfect rejection. |
| Constant gain 0.25×, 0.5×, 2× without clipping; gain steps; compressed/clipped copies | Comparable events in the usable range for constant gain; availability may change below noise/floor or above clipping. Gain steps must not acquire. |

Thresholds should not be chosen by making exactly these generated test values pass. Noise levels, rates, carrier pitches, phases and starts need sweeps or held-out cases.

#### Small sanity calculation performed during this research

I ran the proposed weighted trend-plus-sinusoid fit on 64 **synthetic feature values**, with a Hann weight, a 0.25 Hz grid, and no app detector or audio. This is a mathematical smoke check, not an accuracy experiment:

| Feature input | Best fit Q | Rate bin | Alternating excursions |
|---|---:|---:|---:|
| 2.7 Hz sine | 0.995 | 2.75 Hz | 6 |
| Linear rate sweep 2.7→4.0 Hz over 1.26 s | 0.943 | 3.25 Hz | 8 |
| Constant / linear trend | 0 | none | 0 / 1 |
| Quadratic glide | 0.428 | 1.5 Hz | 2 |
| Single level step | 0.613 | 1.75 Hz | 3 |
| Single Gaussian bump | 0.897 | 1.5 Hz | 2 |
| One moving-average-colored-noise realization | 0.751 | 2.5 Hz | 5 |

Excursions here are sign changes after ignoring residual magnitude below 15% of `sqrt(2*mean(residual²))`. The bump/step examples show that fitted power alone is unsafe. The colored-noise example shows that adding a crossing check still does not prove correctness; depth/feature quality and temporal/recording-level validation are essential. Do not report these values as detection precision, or cherry-pick white noise as the only negative.

#### Real recording protocol

The coordinator has 26 real 1:7 / 44:59 recordings covering 13 reciters. Use their cached content and every relevant committed fixture through the same feature extractor. Have listeners mark clear candidate regions, clear negatives, and ambiguous regions independently of model output. Do not derive expected intervals from where the baseline already fires or from an unlabeled capture's full duration.

Freeze parameters on a development subset. Hold out reciters and, separately, recording conditions; report per-reciter outcomes rather than only an average. With only two cited ayahs per reciter, additional different-ayah recordings are needed before claiming broad reciter generalization. Distinguish differences in acoustic style from differences in microphone, compression, room/echo and feature validity.

Useful measurements: event precision/recall on labelled intervals, acquisition latency, false events per non-event minute, event fragmentation, release-tail overshoot, and duration of unavailable features. Approximate rate error is secondary and meaningful only when an actual event/rate annotation exists. Show an ambiguous/unavailable category instead of forcing every interval to positive or negative.

Run all supplied real clips over multiple hop offsets, not only the recorded alignment. Preserve the existing all-40-phase Hani 2:14 coverage, actual decimation durations, and audio/visual zero-hop correlation tests. Test seeks, silence, pauses, speed changes and strategy toggles through the shared audible/source clock. Classification improvements do not authorize changing a proven phase or ownership test.

### Gaps

- The proposed detector has not been implemented, benchmarked or assessed on those labelled recordings. Its main supported benefit is a principled normalized periodic-evidence alternative to autocorrelation, with explicit missing-feature handling; superior accuracy is still a hypothesis.
- AM/FM feature histories alone cannot reliably distinguish all periodic syllable rhythm, vocal tremor, room-induced modulation or expressive recitation. Strong-hold context and human labels define the product's useful event boundary.
- The best compact first version is therefore: **weighted trend-versus-sinusoid power, independent log-AM/cents-FM channels, 32/64-hop support for 2.7 Hz, explicit validity/depth/persistence, existing lifecycle and actual waveform clock preserved**. The second-harmonic fit and 96-hop low-rate support are justified only by labelled failures or a firm 1.5 Hz requirement.
