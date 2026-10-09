# Whole recording tarjīʿ analysis

## Which small offline sequence method is strongest?

### Takeaway

Use **two-sided seeded cycle segmentation** on the current verse's decoded PCM. It is a distinct detector: future cycles confirm earlier acoustic onsets and weak continuations, rather than replaying the causal detector and calling that offline analysis. Reuse shared RMS/pitch features and the existing measured-phase helper; avoid a new model runtime or a full pYIN implementation for the first developer toggle.

### Cited Findings

- Desain et al. detect half-cycles between local pitch maxima/minima, constrain cycle rate and extent, and separately estimate note onsets/offsets. Their analysis applies a filter in both directions for zero phase shift. Their small musical-performance experiment also finds changing rates within notes and ambiguous transition boundaries. Its thresholds and findings are not a Qur'an accuracy result. — [Author-hosted paper, 1999](https://www.mcg.uva.nl/mcg-2023/papers/dhat-2/dhat-2.html)
- pYIN retains multiple frame-wise YIN candidates, then uses an HMM/Viterbi sequence pass to favour smooth pitch and fewer voicing changes. Discarding candidates before smoothing limits what later processing can recover. This establishes the value of sequence context, not the accuracy of a custom tarjīʿ classifier. — [Mauch and Dixon, original paper, 2014](https://webspace.eecs.qmul.ac.uk/s.e.dixon/pub/2014/MauchDixon-PYIN-ICASSP2014.pdf)
- Offline vibrato analysis first needs a correctly delimited sustained region. Frequency and amplitude variations need not share identical patterns; vocal-tract resonances can make amplitude variation less regular than pitch variation. — [Herrera and Bonada, original DAFx paper, 1998](https://www.dafx.de/paper-archive/1998/HER60.PS.pdf)
- Essentia's author documentation reports vibrato rate in Hz and extent in peak-to-peak cents, with independently constrained rate/extent and zero outputs when absent. Its default 4–8 Hz band and 50-cent extent must not replace this project's broader 1.5–10 Hz product band and smaller pitch-depth floor. — [Essentia Vibrato](https://essentia.upf.edu/reference/std_Vibrato.html); [project detector contract](../../docs/TARJI.md)

### Inferences

The following is a proposed deterministic implementation, not an established published detector or validated parameter set.

1. Extract immutable per-hop features once: raw 20 ms RMS `E`, short-YIN `f0`, pitch confidence/clarity, and the pitch estimate's support-centre offset. Use `hopMs` and `hopSamples` from `Decoded`, not an assumed 8,000 Hz or exact 20 ms; the tap's 44.1 kHz path yields 8,820 samples/s. Share this extraction with the other developer modes.
2. Build hard voiced/note components. Require raw volume, noise margin, and absolute pitch-quality floors. Cut at sustained unvoiced spans and genuine note/articulation transitions; a repeated same-pitch syllable can still have a new attack. Keep existing hold/note identity policy, rather than making a looser new octave-folding rule. Never smooth across these boundaries. One isolated unreliable pitch frame may be ignored for classification, but a fabricated replacement must not become painted phase.
3. Within each component, use a centred local baseline, covering roughly two local cycles, to remove slow swell/glide. Initial candidate discovery can use a window covering two cycles of the configured slowest rate. Recompute local baselines using measured cycle periods where useful. A symmetric centred mean cancels a linear trend at its centre without introducing a causal delay; clamp its support to the component. A centred 1–2–1 blur may suppress carrier beats, as the existing phase helper does. Do not apply an 80 ms envelope average to the 10 Hz phase signal.
4. Independently find alternating AM and FM extrema with real prominence. For pitch use `C[i] = 1200 log2(f0[i]/fReference)` and residual `C[i] − baseline(C)[i]`. For amplitude use `E[i] − baseline(E)[i]`. For successive same-polarity extrema `t[k]`, local rate is `r[k] = 1000/(t[k+1] − t[k])`; depth is half the adjacent peak–trough difference, divided by local level for AM and kept in cents for FM. Compare successive periods locally, permitting gradual rate change. One rate for the whole recording is inappropriate.
5. A **strong seed** requires at least two complete supported cycles, the configured rate band, minimum extent/depth, good pitch/voice quality, and repeat-shape/period agreement. AM additionally needs a stable local level across the candidate span: a loud attack followed by a quiet step must not count as modulation. A **weak candidate** may use existing-style lower retention thresholds, but still obeys all hard quality/boundary gates. Assign candidate support only to its observed alternating-cycle span, not an entire centred window that happens to include future cycles.
6. Extend strict seeds both backward and forward through adjacent weak candidates. Every accepted region must contain a strict seed. Brief classifier holes can be bridged only when valid evidence brackets them on both sides inside the same note; provisionally cap them at 120 ms and also at one measured cycle. Never bridge a note/consonant boundary, a bad-pitch interval, or an unsupported terminal echo. Exact limits need labels. This is a small two-pass hysteresis operation; a three-state Viterbi/HSMM is an optional later experiment, not required complexity.
7. Choose AM or FM from the accepted evidence and lock the visual channel for the event; prefer AM when both are comparably coherent. Precompute the selected channel's **actual residual waveform**, with a depth floor, using `TarjiEarPulse` semantics. Zero unknown phase instead of inventing it through a gap. Save pulse, gain, local rate, selected channel, and real event-start time for every hop. Source event start is the first supported acoustic cycle after the hard boundary, not the later decision time, a word boundary, or a backdated window edge.

Minimal sequence pass, once features/candidates exist:

```text
for each hard voiced/note component and channel:
    weak = supported local cycles passing retention + hard quality gates
    strong = weak cycles passing strict depth/regularity and ≥2-cycle support
    extend strong left through weak; extend strong right through weak
    accept only connected weak regions containing strong
    bridge only short, doubly supported classifier holes
    reject runs shorter than max(holdMinMs, 2 measured cycles)
merge overlapping accepted evidence into acoustic events
select one measured phase channel per event; publish immutable hop arrays
```

Look-ahead gains: confirm onset without waiting until the listener has already heard two cycles; evaluate the quiet side of an attack; distinguish a transient estimator failure from an actual event ending; give immediate source-correct samples after a seek. It does not make pitch extraction, word ownership, or echo/source separation infallible.

### Gaps

- No labeled cross-reciter tarjīʿ benchmark was found in the primary sources consulted. This proposal's precision/recall and optimal constants are unknown.
- If the shared feature extractor supplies only one wrong pitch estimate, later sequence segmentation cannot recover a discarded true candidate. A small multi-candidate YIN/Viterbi extension may eventually help; adding it immediately would enlarge this toggle substantially.
- Single-channel recording analysis cannot guarantee separation of direct voice from a reverberant copy with similarly clear pitch and periodicity. Rapid melisma, brief/irregular pulses, timbre-only modulation, compression pumping, and breathy or clipped voice remain difficult.

## How should each recording be calibrated without promoting noise?

### Takeaway

Normalize units and local baselines, **not the recording's strongest candidate to a target strength**. Calibration may raise evidence thresholds when the recording is noisy; it must not lower absolute voice, pitch-quality, or physical modulation floors merely because no strong event exists.

### Cited Findings

- Median absolute deviation is `median(|x − median(x)|)` and gives less influence to tail extremes than standard deviation. It is suitable for a robust spread estimate; it does not by itself identify which samples are noise. — [NIST measures of scale](https://www.itl.nist.gov/div898/handbook/eda/section3/eda356.htm)
- CREPE Notes combines pitch gradient with inverted pitch confidence for segmentation, handles same-pitch repeated notes separately, and trims using amplitude. It warns that pitch predictions at very low levels may not match perceptual note boundaries. — [Original CREPE Notes paper, 2023](https://arxiv.org/html/2311.08884v1)
- CREPE's original package requires a pretrained CNN and a TensorFlow runtime, supports smaller capacity models and optional Viterbi smoothing, and was trained on listed vocal/instrumental datasets. These establish pitch tracking capability, not a trained Qur'an tarjīʿ event detector. — [Author repository](https://github.com/marl/crepe)
- The torchcrepe authors explicitly warn that silent regions can receive high pitch confidence because CREPE was not trained on silent audio; they supply a separate silence gate. — [Author implementation documentation](https://github.com/maxrmorrison/torchcrepe)

### Inferences

- Volume normalization: compute `E[i] = sqrt(mean(x²))` without gain manipulation. Estimate a recording background reference only from quiet, low-clarity frames, excluding voice attacks. For example `N = median(E_quiet)` and `sN = MAD(E_quiet)`. Require `E[i] ≥ max(userMinVolume, absoluteFloor, N + k sN)` plus a configured noise contrast. `k` and contrast are provisional validation constants. With no credible quiet frames, mark that calibration unavailable and use conservative existing floors; do not silently call the quietest vowel noise. Call the ratio a background contrast estimate, not measured true SNR.
- AM depth as a fraction of a **local voiced level** is insensitive to overall gain above the absolute gates. FM depth in cents is comparable across different F0 registers. Keep an actual extent floor, such as the current approximately ten-cent pitch criterion; a recording with tiny jitter must remain still.
- Optional robust residual-noise estimates can only increase the amplitude/pitch depth floor: `effectiveFloor = max(configuredPhysicalFloor, noiseDerivedFloor)`. Obtain pitch jitter estimates from trusted still/steady spans, not from a percentile of all modulating spans. If trustworthy still spans are unavailable, retain the configured floor.
- Never use `(candidate − verseMin)/(verseMax − verseMin)`, a per-verse z-score, a reciter-specific top percentile, or a threshold chosen solely to guarantee detection. Those can turn the strongest noise fluctuation into a full-strength event. Display normalization comes **after** independent event acceptance and keeps the existing absolute phase floors.
- ML can be an external research reference for difficult pitch examples. A new Android inference runtime/model should require labeled evidence that the smaller deterministic method fails and that the extra runtime improves those exact failures. A generic pitch model still needs event segmentation, silence/quality gates, and echo-tail rules.

### Gaps

- The proposed quiet-frame selector and noise margins require testing on compressed, echo-heavy, quiet, and continuously voiced recordings. Neither MAD nor a pitch confidence score is a calibrated probability of tarjīʿ.
- Short Lab captures cannot reproduce whole-recording calibration unless they carry the full-recording statistics or use the cached original recording. Report sample-local analysis honestly; do not imply exact equivalence to whole-verse calibration.

## How should this fit the app and be validated?

### Takeaway

Compute once per audio/analysis-settings identity, then sample immutable arrays at the **existing corrected media clock**. Keep the baseline default and `TarjiWordGate` unchanged. Recording mode is silent while pending, unavailable, or rejected; those states must be visible in developer diagnostics.

### Cited Findings

- `TarjiVersePulse` already shares decoded verse PCM using Deferred/LRU entries, limits MediaCodec decoders with a semaphore, checks cancellation during analysis, and excludes paint-only knobs from analysis keys. Its existing `lines()` reruns causal `Tarji`; that is a useful baseline comparison, not the proposed sequence method. — [Current implementation](../../app/src/main/java/com/beautifulquran/ui/reader/TarjiVersePulse.kt)
- `TarjiEarClock` maps sink content onto media-item position and carries speed/output-route timing. `TarjiEarPulse` reads centred actual RMS/F0 fluctuations with absolute amplitude/pitch floors, rather than synthesizing phase. — [Clock and measured pulse implementation](../../app/src/main/java/com/beautifulquran/playback/TarjiSyncClock.kt)
- The current 26-recording audit checks execution/tuning response; the 13-reciter reader audit additionally checks admission/ownership. Neither claims perceptual detection accuracy. The sample documentation explicitly requests positive holds and matched stills across rooms/levels. — [Audit definitions](../../docs/TARJI_LAB.md); [Sample collection contract](../../tools/tarji_samples/README.md)
- Existing full Alafasy/Hani 1:7 WAVs and Hani 2:14 excerpt support replay/phase regressions. The two tuned Hani JSON files are `UNLABELED`, with empty crest labels and notes; they are not independently annotated ground truth. — [Fixtures](../../app/src/test/resources/tarji/); [Existing tests](../../app/src/test/java/com/beautifulquran/ui/reader/TarjiVersePulseTest.kt)

### Inferences

- Extract a shared suspend PCM-access path from the private decoder/cache instead of decoding a second copy. Cache shared raw features independently of knobs. Result key: reciter ID, verse/audio URL and available audio revision, feature/algorithm version, selected mode, and analysis-relevant knobs. Add timing/eligibility identity only if caching gated per-word output; an ungated full-verse detector does not depend on word timings.
- Scope expensive analysis to the current verse and optionally the next, not every visible leaf. Keep decoding on the existing limited worker path and sequence analysis off the audio/main threads. Check cancellation throughout feature extraction and segmentation, not just before final publication. Shared PCM work may finish for other consumers; canceled observers must never apply it.
- Before publication/adoption, verify the current audio/settings key and request generation. Clear the sampled result on reciter, item, mode, or knob changes. A same-recording seek may reuse ready arrays, but must sample at the new existing corrected source instant and retain existing seek/word-gate resets. Never replay an old sample while waiting for the new clock/key.
- Use existing audible media position and trims/speed exactly once; do not reuse the graph helper's fixed `EAR_BEHIND_HOPS` as a live output delay. Interpolate pulse/gain only within a valid event/channel. Event IDs/onsets are discrete source metadata. Outside decoded support or when the clock is unknown, return zero rather than clamp to the last audible pulse.
- Complexity: the local sequence/hysteresis pass is linear in hop count apart from small local feature windows; ordinary 3-state DP, if later used, needs only nine transition comparisons per hop. At 50 hops/s, pulse/gain/rate Float arrays + a byte channel + Int event-start array cost approximately 850 bytes/s, about 51 kB for one minute. Existing mono float PCM costs approximately 32–35 kB/s. Pitch extraction and decode dominate; no on-device processing time has been measured. Frame sampling is O(1) with no allocation. Put a byte budget on cached PCM/results rather than relying only on entry counts for long verses.
- Evidence latency: two cycles require about 200–1,333 ms at 10–1.5 Hz, plus analysis support. Full-recording look-ahead pays that before playback reaches the event, once analysis completes. Cold download/decode may take longer; playback should continue and developer state should say Pending. Do not add playback delay or silently substitute the baseline while presenting recording-mode results.
- Validation protocol: listen and label positive event intervals plus matched still/echo/consonant negatives across all 13 catalog reciters; include quiet/loud, slow/fast, madd/waqf/ghunnah and multiple recordings per reciter. Freeze labels before tuning, split by complete recording (also test held-out reciters), and report per-reciter plus pooled event precision/recall, negative shimmer duration, onset/offset error, missed short events, and raw phase/crest error at the same source instant. Existing nonzero-audit counts are not an accuracy score.
- Synthetic regressions: AM-only, FM-only, mixed, changing rate, gradual depth change, gain-scaled copies above/below raw quality floor, silence/noise-only, steady notes, pure crescendos/glides, note jumps, repeated same-pitch articulations, attack steps, missing pitch, echoes, and 10 Hz/carrier beats. Vary decoder hop alignment and real hop duration. Integration tests cover late completion after reciter/mode/knob changes, same-item seeks, pause/buffering/speed/output trims, canceled work and failed decode. Preserve existing zero-shift source-phase tests and word ownership tests.

### Gaps

- No phone CPU/battery/startup-latency measurements or newly labeled accuracy results were produced in this research task. The performance numbers above are storage arithmetic and complexity bounds, not benchmarks.
- Pre-word acoustic starts can remain deliberately withheld by `TarjiWordGate`; a brighter Lab trace cannot justify weakening that guard. Source detection quality and reader admission must be reported separately.
