# Causal cycle tracking for held-note tarjīʿ candidates

## How can short-window cycles distinguish a held-note oscillation from ordinary voice movement?

### Takeaway

Recommend a **voiced, detrended extrema tracker**: independently examine log RMS and cents F0, require four alternating substantial turning points with two consistent like-polarity periods, and return evidence to the shared event lifecycle. This is an implementable acoustic-modulation hypothesis, not a validated universal classifier of the religious term tarjīʿ.

### Cited Findings

- Rossignol et al. explicitly describe interpolated maxima/minima, the variance of successive maximum/minimum distances, in-band interval counts, and relative peak-to-trough extent for vibrato detection. Their demonstration band was 4–6.7 Hz; that band is **not** evidence for restricting Qur'an reciters to those rates. — [Original DAFx 1999 paper, §2.5](https://www.dafx.de/paper-archive/1999/rossignol.pdf)
- Horii measured individual modulation cycles in eight male singers. Timing was more regular than extent, and F0 increases were faster than decreases. This supports checking full periods without demanding identical depth or symmetric half cycles. It does not establish thresholds for Qur'an recitation. — [Original study's publisher abstract](https://pubs.asha.org/doi/10.1044/jshr.3204.829)
- Horii's theoretical analysis attributes much observed amplitude modulation and its varying phase relationship with F0 to resonance/harmonic interaction. A fixed AM/FM phase relationship should not be a necessary acquisition condition. Only the abstract was accessible for this paper. — [Original Journal of Voice abstract](https://doi.org/10.1016/S0892-1997(89)80120-1)
- pYIN retains several frequency candidates and uncertainty before temporal tracking; selecting one erroneous YIN candidate first loses useful alternatives. Its voiced probability is derived from a threshold distribution, not simply `1 - minimumDifference`. — [Author-hosted ICASSP 2014 paper, §2](https://webspace.eecs.qmul.ac.uk/s.e.dixon/pub/2014/MauchDixon-PYIN-ICASSP2014.pdf)
- Locally, the existing detector already has 80 ms hold identity, a 40 ms YIN modulation track, 20 ms live RMS, octave folding, hold/level/tail guards, and delayed output history. Invalid modulation pitch is currently replaced by the previous `pitchEnv` sample; its short-YIN clarity is private. An experiment must receive explicit validity rather than infer validity from the carried value. — [Tarji.kt](../../app/src/main/java/com/beautifulquran/playback/Tarji.kt), [implementation notes](../../docs/TARJI.md)

### Inferences

The following is a proposed adaptation of the cited extrema method; the thresholds are engineering starting points, not published Qur'an accuracy results.

**Input contract and invalid pitch**

Use one feature frame from the existing extraction work:

```
Feature(hop, hopRms, foldedF0Hz, shortYinClarity,
        pitchValid, voiced, holdMs, holdIdentity, pitchLeadHops)
```

`pitchValid` must be true only for an actual finite positive estimate with sufficient short-YIN clarity. Suggested experimental starting point: short clarity ≥ 0.70, plus the established hold track's voiced/energy decision. `1 - d'` is a reliability heuristic, not a calibrated probability. Do not duplicate the baseline PCM/YIN loop or implement pYIN/HMM as part of this small detector.

Invalid F0 has zero fitting weight and cannot create a turning point or count toward cycle coverage. Do not feed `lastFoldedPitchHz` without its validity bit: its carry-forward behavior hides dropouts. Permit at most one missing 20 ms hop inside an FM cycle; a gap longer than 40 ms breaks the FM run. Require ≥85% valid frames across the accepted cycle span. Hold grace can remain more permissive than FM evidence; missing pitch does not automatically prove a new note. AM also requires voiced coverage, although it need not require reliable short YIN at every hop.

**Small algorithm**

1. Reset only experimental evidence on a new established hold, seek, discontinuity, or mode switch. Keep PCM hop counts and audible history clocks running. Start processing the hold immediately; the existing configurable `holdMinMs` remains a separate lifecycle requirement.
2. Let `a[k] = ln(max(hopRms[k], epsilon))` and `p[k] = 1200 * log2(foldedF0Hz[k] / holdAnchorHz)`. A multiplicative volume change becomes an additive constant in `a`; a pitch transposition becomes an additive constant in `p`.
3. Work on the latest within-hold samples, growing naturally rather than waiting for a fixed 1.3 s window. A small 96-hop experimental ring can cover 2–2.5 periods at the existing 1.5 Hz floor; it does not replace the audible-history ring. The window must contain the recent candidate cycles, not all the preceding consonant attack. Once a period is available, retain roughly 2–2.5 periods. During acquisition, allow the available window to grow to that size, and accept as soon as the extrema evidence exists.
4. Remove a line independently in each channel. For a robust, small fit: calculate weighted OLS, calculate `sigma = 1.4826 * median(abs(residual - median(residual)))`, then refit once with `w *= min(1, 2.5*sigma / max(abs(residual), epsilon))`. Existing voicing/validity supplies the initial weights. This single reweight is a proposed safeguard against isolated attacks; it is not an excuse to bypass the existing level-transition guard.
5. Smooth the residual with a three-tap `1–2–1` average. Confirm local maxima/minima by an opposing excursion, rather than every tiny derivative sign change. Use the common depth floor as prominence hysteresis; a proposed reversal threshold is half the required peak-to-trough excursion. Keep actual hop timestamps. Parabolic interpolation around a supported extremum reduces 20 ms quantization; clamp its offset to ±0.5 hop.
6. Keep the latest four alternating substantial extrema `e0,e1,e2,e3` in each channel. These provide **two overlapping** full-period estimates, not two independent complete cycles:

```
P0 = t2 - t0
P1 = t3 - t1
P  = (P0 + P1) / 2
jitter = abs(P0 - P1) / P
halfFractions = [(t1-t0)/P, (t2-t1)/P, (t3-t2)/P]
halfExcursions = abs(diff(extremumValues)) / 2
```

Accept only if `1/maxHz ≤ P ≤ 1/minHz`, `jitter ≤ 0.20`, all half fractions are in `0.20..0.80`, the median half excursion clears the depth floor, and no excursion is <35% of that median. These deliberately allow uneven rising/falling durations and growing cycle depths. If the newest confirmed extremum is older than about `0.75*P`, stop reporting coherent current evidence. An optional fifth extremum supplies a more conservative two-period confirmation; it costs another half period.

For AM, use the cross-reciter dimensionless depth `depthAM = tanh(medianHalfExcursionLogRms)`. This equals `(Rmax-Rmin)/(Rmax+Rmin)` for the corresponding log peak/trough pair, and is approximately the fractional sinusoidal modulation depth for small oscillations. Start from the existing configured AM floor, rather than a raw PCM amplitude. For FM, use cents directly; about 10 cents of half excursion matches the existing sensitivity's order of magnitude. These measures do not need separate hardcoded values for each reciter.

Return only `{coherentAM, coherentFM, rateHz, depth, regularity, usesAmplitude}`; for diagnostics, a simple regularity score is `clamp(1 - jitter/0.32, 0, 1)` (the baseline's 0.4 periodicity knob then corresponds approximately to a 19% period mismatch). Do not claim it is numerically equivalent to autocorrelation confidence.

**What the gates can and cannot distinguish**

| Input | Why this method should help | Remaining ambiguity |
|---|---|---|
| Constant note / monotone crescendo / pitch glide | Detrending plus four alternating excursions, rather than depth alone | Strong nonlinear bends can perturb a short fit; test them explicitly |
| One consonant attack / one loud-to-quiet step | Usually lacks three repeated substantial excursions; robust fit and existing level guard remain | Echoed or repeated attacks can become periodic |
| Changing syllables | Voicing gaps, changed established hold, irregular periods, and existing eligible-word gate | Rhythmic same-pitch voiced syllables can be acoustically indistinguishable |
| Quiet held modulation | Log/cents normalization removes global recording level and pitch differences | Below the established PCM/noise floor there is no trustworthy evidence |
| Echo-only tail | Existing falling-level/tail lifecycle and word gate still veto | A voiced delayed copy can preserve pitch and cadence; RMS/F0 alone cannot prove direct voice |
| Fast texture on a slow swell | Prominence and full-period consistency can identify the stronger slow cycle | Skipping small out-of-band extrema can manufacture a subharmonic; retain the existing ceiling/harmonic safeguard |

Do not demand AM and FM agree to acquire: genuine acoustic AM-only and FM-only are already supported product cases. Prefer coherent AM for visible polarity when both acquire, and keep the phase channel stable while coherent, as the baseline does. Normalized depth, voicing, and cycle timing should adapt broadly, but no primary source found establishes the same operating point across all reciters and rooms.

### Gaps

- The cited studies concern musical/sung vibrato. No primary-source classifier with validated reciter-independent precision/recall for the app's tarjīʿ definition was located.
- Echo and periodic same-note articulation cannot be fully separated from modulation using only RMS, folded F0, and clarity. Acoustic detection plus held-word/lifecycle constraints is the honest claim.
- Short-YIN clarity 0.70, period jitter 20%, cycle coverage 85%, and prominence settings require held-out validation. They must remain experimental defaults, not a silent replacement of baseline guards.

## How quickly can cycle tracking acquire a slow 2.7 Hz held tail, and what does it cost?

### Takeaway

Four alternating turning points require about 1.5 modulation periods between the first and fourth extremum, with additional initial phase and reversal-confirmation delay. At 2.7 Hz a defensible practical target is approximately **0.7–0.9 s after modulation begins**, while a hold shorter than that cannot be confidently classified causally by repeated-cycle evidence.

### Cited Findings

- Rossignol notes that short notes can contain only a few modulation cycles, complicating Fourier frequency resolution; its trajectory methods use short analysis segments. Its extrema method obtains rate from actual temporal distances rather than Fourier bins. — [DAFx 1999, §§2.3 and 2.5](https://www.dafx.de/paper-archive/1999/rossignol.pdf)
- The current implementation scans up to 64 20 ms hops with linear detrending and autocorrelation; its eligibility and event lifecycle are already separate concerns. Existing tests cover AM, FM, mixed modulation, crescendo, glide, loud onset, faster texture, real recordings, and timing alignment. — [Tarji.kt](../../app/src/main/java/com/beautifulquran/playback/Tarji.kt), [TarjiTest.kt](../../app/src/test/java/com/beautifulquran/playback/TarjiTest.kt)

### Inferences

`T = 1/2.7 = 370.4 ms`; four extrema span approximately `1.5T = 555.6 ms`. Starting at an arbitrary phase adds up to approximately half a period, and confirmation/filter support adds several hops. Requiring a fifth extremum for two independent periods adds another ~185 ms. A configurable `holdMinMs=1200` still imposes 1.2 s of hold age; an alternative evidence algorithm must not quietly override it.

Small exploratory **feature-level**, not end-to-end Kotlin/PCM, check performed 2026-10-06: 50 Hz `log(1 + .14*sin(2π*f*t+phase))`, 16 starting phases, robust one-reweight line fit, 1–2–1 smoothing, four-extrema/20%-jitter checks. The untuned Python exploration acquired 2.7 Hz in **0.72–0.88 s**, 5 Hz in **0.40–0.52 s**, and 10 Hz in **0.32–0.34 s**. Constant level, linear/curved crescendo, a level step, and a single Gaussian swell did not acquire. A ramp plus 2.7 Hz acquired at 0.74 s. This is feasibility evidence only: it omits short-YIN validity, hold gating, PCM window responses, and noise, and therefore cannot establish shipped accuracy.

The same exploration exposed a boundary problem at exactly 1.5 Hz: quantized periods and a short window delayed acquisitions unpredictably to 1.26–2.48 s. Do not conceal this result. Test interpolation and adequate 2–2.5-cycle support at the band edge before shipping; do not weaken the existing band guard to make a test pass.

For implementation, use reusable primitive arrays: ≤96 feature slots and perhaps 8 extrema per channel. Two line fits, small median sorts, smoothing, and a scan at 50 Hz should require hundreds to a few thousand simple operations per hop (order 10⁴–10⁵ per second), excluding the already-shared baseline pitch extractor. This is a cost estimate, not an Android benchmark. No FFT, tensor model, new dependency, or second PCM pitch estimator is required.

### Gaps

- The prototype is not the proposed production implementation and used offline extrema discovery over each causal prefix. Actual online prominence logic must be tested against its boundary behavior.
- A 300–500 ms 2.7 Hz tail contains less than 1.5 independent oscillations. This approach cannot confidently identify it as periodic without prior/offline evidence. A developer preview may display measured raw movement for diagnosis, but must not render an invented oscillator as accepted tarjīʿ.

## How should this adapt across recordings, preserve real-voice phase, and be validated?

### Takeaway

Keep the existing feature extractor, acoustic/word guards, shared experimental lifecycle, and `TarjiEarTrack` clock. Compare only event evidence; visible modulation remains the measured voice from the existing ear history, with no rate-driven sinusoid or reset of playback counters.

### Cited Findings

- `TarjiEarPulse` computes the measured RMS/F0 residual relative to a centered period mean. RMS uses a small blur; FM uses the pitch-support lead. `TarjiEarTrack` and the presentation-clock history supply the heard instant. The current visual mean is clamped to 6–16 hops, so very slow periods already incur a documented depth limitation. — [TarjiSyncClock.kt](../../app/src/main/java/com/beautifulquran/playback/TarjiSyncClock.kt), [TARJI.md](../../docs/TARJI.md)
- Three committed WAV fixtures provide Alafasy 1:7 (8 kHz), Hani 1:7 (8 kHz), and Hani 2:14 beginning at 12,270 ms (8,820 Hz with 176-sample hops). The tuned exported Hani JSON samples are `UNLABELED`; their metadata reports 8,000 Hz despite ~19.9547 ms 176-sample hops, so do not assume the PCM cadence solely from that field when replaying them. — [Fixture contract](../../app/src/test/java/com/beautifulquran/playback/TarjiFixtures.kt), [resource directory](../../app/src/test/resources/tarji), [sample guidance](../../tools/tarji_samples/README.md)
- The local sample instructions require positive holds and matched stills from each reciter, quiet/loud and slow/fast examples, multiple rooms/mics, and held-out improvement. The catalog CSV audits measure response and allowed glow, not human-labeled acoustic correctness. — [Sample guidance](../../tools/tarji_samples/README.md), [reader audit](../../tools/tarji_samples/reader_fatiha_audit_2026-10-01.csv)

### Inferences

**Integration boundary:** baseline `Tarji` continues its normal feature work. Feed the selected experimental method one shared feature frame per hop, with explicit short-YIN validity. Cycle/spectral methods can share event-start, confirmed/acquiring state, attack/release, level/tail and word-boundary policy. On a live developer-mode switch clear experimental evidence and gain for warm-up, but preserve the hop counter, capture source timestamps, presentation clock, and common feature history. Baseline remains the default.

Keep `usesAmplitude` stable during an accepted event. Classifier smoothing/trend delay is **not** a new phase delay to add to the renderer: detection merely gates the event; the audible pulse still comes from the same raw history and F0-support correction. A missing F0 may remain a separately flagged held value for rendering continuity if the existing renderer needs it, but must never count as cycle evidence. Preserve zero-hop/adjacent-hop phase tests and the glyph wash untouched.

**Exploratory real-wave check:** extracting 20 ms RMS from the three WAVs and inspecting substantial extrema within the documented sustain regions found plausible repeated local cycles, but variable intervals. Hani 1:7 had several successive peak intervals around 0.16–0.22 s; Alafasy included intervals 0.18–0.58 s; Hani 2:14 included 0.18–0.60 s. This supports local rather than whole-hold statistics and warns that a strict global cadence is wrong. It is not a labeled accuracy result or a claim that every detected extremum was heard tarjīʿ. See the cited fixtures; the prototype used no new audio source or provider.

**Meaningful synthetic PCM validation** (run through the shared extractor, then method):

- Constant carriers at 70, 90, 140, 220, 350 Hz, across hop offsets and non-integer carrier cycles; watch for artificial 20 ms RMS beating. Vary absolute gain until the established floor is crossed; below-floor results should be withheld, not normalized into noise.
- AM-only, FM-only (10/25/60 cent half excursion), and mixed modulation, at 1.5/2.2/**2.7**/5/8/10 Hz, 32 starting phases, at 8 kHz and the actual 8.82 kHz/176-hop path. Measure acquisition time, accepted-hop recall, false event count, and rate error separately.
- Modulation beginning only in the last 0.4/0.6/0.8/1.0 s of a held note, following a loud attack and 0.3–1.0 s of still vowel. A method can properly withhold 0.4 s at 2.7 Hz; a 1.0 s tail should be a realistic improvement target.
- Linear/exponential/sigmoid crescendo, pitch glide, note step, one swell, irregular pulses, periodic vowel/consonant alternation, breath/noise, a voiced echo-only decay, and out-of-band 12/16/20/25 Hz texture mixed with an in-band 2.7 Hz swell. Specifically ensure dropped extrema do not manufacture an admitted slower subharmonic.
- Deliberate octave/subharmonic flips and unreliable YIN segments: no FM cycle may be assembled from repeated stale pitch; short outages should not shift the hold or audible phase. Changing reciter volume or pitch should preserve evidence above the existing floor.
- Rate sweep and cycle-depth growth/decay: a 2.7 Hz period must not disappear mid-cycle merely because no fresh maximum has arrived. Shared lifecycle may bridge a bounded analysis gap but never acquire from depth alone.

**Real-audio validation:** first replay the existing fixtures across every 20 ms hop alignment, keeping all current event/tail and word-eligibility assertions. Add human-labeled positive/negative clips for every catalog reciter, from several ayat and recording conditions, and leave at least one ayah/recording per reciter held out. Label actual voiced held modulation onset/end and audible crests separately from a religious classification. Report per-reciter event precision/recall, false relights, onset delay, and accepted phase correlation; a large average may hide complete failure on one voice. Untuned JSONs and response CSV counts are not accuracy ground truth.

The highest-value integration regression remains real voice versus heard-time phase: AM output changes should correlate with live/ear RMS changes at zero hop more strongly than either adjacent hop; FM should match the measured pitch residual at its support-corrected heard time. Verify pause/seek/speed/route behavior and no shimmer on ineligible or dry-down words. Mode switching should create warm-up, not shift the history or replay old evidence.

### Gaps

- No all-reciter human-labeled dataset or verified false-positive baseline is committed in the inspected sample set. Validation cannot honestly certify “good for every recording” yet.
- Recorded-room echo remains a known limitation of these low-dimensional features. Failure cases should guide tuning or abstention; do not add an unvalidated dereverberation stack to this small experiment.
- Suggested runtime cost and acquisition targets still need JVM/Android replay measurements after implementation. Primary evidence supports the method family, while the actual thresholds and product behavior remain testable engineering choices.
