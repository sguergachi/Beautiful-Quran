# Detector audit

This JVM helper measures **unlabeled execution coverage and compute cost**, not
tarjīʿ accuracy, reader admission, decoder alignment, or audible phase. The 26
cached mono PCM16 WAVs cover 13 catalog reciters and ayat 1:7 and 44:59. Input
SHA-256 values, compiled `Tarji*` class digests, and exact default knobs are
saved beside results.

After compiling current application classes, run:

```bash
bash tools/tarji_samples/run_detector_audit.sh /path/to/Gradle/classpath.txt
```

The optional second and third arguments select cached input and result folders.
Input WAVs/manifest live under `tools/.cache/tarji_detector_audit`; the files are
not downloaded by this helper. The Gradle JVM classpath file contains the
dependencies previously resolved for the unit-test runtime. Application classes
come from `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`.

Every recording uses common default settings for Current, Cycles, Spectrum, and
Recording. Recording receives exactly the same snapshotted extracted frames as
the causal experiments. A 160-sample hop represents 20 ms; trailing incomplete
hops are ignored. CSV active duration is `active_hops × 20 ms`; gain duration is
`gain_gt_0_01_hops × 20 ms`, including release. `events` counts distinct admitted
event-start IDs, and first-event time is their earliest source timestamp.
These raw detector outputs have **not** passed `TarjiWordGate` or Recording's
live alignment/readiness checks. Nonzero output is not a positive label, and
zero output is not proof of a miss.

The helper runs five warmup iterations and ten measured iterations per
recording/mode. The same causal detector workspace is cleared/reused between
iterations. Extractor + Current timings include PCM feature extraction and
baseline decisions; Cycles/Spectrum timings include only `next()` on prepared
frames, including their lifecycle. Offline timings include Recording's
post-feature analysis/result construction. Input loading, frame snapshots,
sorting statistics, and constructors are excluded. Per-hop `nanoTime` timer
overhead remains; allocation instrumentation and Pixel latency are not measured.
Pooled p50/p95 are hop-weighted across these recordings, rather than an average
of per-recording percentiles. Offline us/audio-minute is the sum of per-recording
median compute time divided by total audio duration.

The six-field baseline digest uses the same representation as the pre-change
`BaselineAudit.java`: tremolo, gain, hold age, rate, start hop, and active flag
per hop. The runner compares `baseline-after.csv` byte-for-byte against the
frozen `baseline-before.csv` when present. Separate app golden tests also cover
visual channel and Hani's other hop alignments.

Performance numbers are wall-clock JVM measurements, sensitive to CPU scheduling,
JIT/GC, and concurrent builds. Rerun after algorithm changes. No result here
demonstrates listener-label precision/recall or performance on the Pixel.

The final 2026-10-06 run used Linux amd64/JDK 21.0.12.1 on an Intel Core i7-8700K,
after precomputed Spectrum Hann weights and a shared valid-only FM feature blur.
Exact WAV duration was 326.24475 seconds; 325.96 seconds remained after ignoring
incomplete final hops. All 104 clip/mode executions completed. Added decision
p50/p95 were 2.329/9.119 µs for Cycles and 0.164/165.729 µs for Spectrum; the
Spectrum median includes skipped/ineligible updates, and its p95 exceeds the
review's provisional 150 µs budget. Recording post-feature work normalized to
2.102 ms/audio-minute. Frozen baseline digests matched for every recording.
