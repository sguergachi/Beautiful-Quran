# Timing V2 — how we claim 99% (protocol)

**Status:** binding for any “99% accurate” statement about Timing V2  
**Companion:** `docs/TIMING_FIRST_PRINCIPLES.md` §5

---

## 1. What “99%” means (operational)

One sentence, always:

> **≥99% of word-occurrence onsets on the frozen held-out ear-label test set
> fall within 100 ms of the labeled audio-file start, measured only on rows
> V2 *accepted*; coverage is reported in the same sentence.**

Also report:

| Metric | Bar |
|---|---|
| Median \|error\| | ≤ 25 ms |
| p90 \|error\| | ≤ 60 ms |
| Within 100 ms | ≥ 99% |
| Structure exact (random stratum) | ≥ 99% |
| Repeat-event P/R (challenge stratum) | ≥ 99% |
| Clean false backtracks (non-repeat) | ~ 0 |
| Coverage | stated, never folded into accuracy |

**Illegal claims**

- Full-mushaf 99% when coverage is 43% (or any partial accept rate).
- 99% against historical Lab patches, QDC, QUA self-labels, or CTC self-agreement alone.
- Composite scores that hide structure misses inside onset averages.

**Legal interim claims** (must name the subset)

- “Dual-witness (QUA∩CTC maxΔ≤100 ms): 100% agreement on N onsets at C% coverage” — agreement, not ear gold.
- “Post-pause objective gold: med/p90/within100 on energy-rise after ≥250 ms silence.”
- “Hard structural flags: 0 past-duration / empty / non-monotonic on accepted rows.”

---

## 2. Minimum path that is not a lie

```text
                    ┌─ energy snap starts (±40 ms)
QUA same-take ──────┼─ drop dead-zone onsets
                    ├─ mono dual-witness CTC max|Δ|≤T  →  high-agreement subset
                    └─ V1 fallback for abstentions

Frozen ear test (independent_labels/frozen_sample_v1.json)
        │
        ▼
label on waveform (not live ear) → measure accepted-row accuracy
        │
        ▼
if test ≥99% within 100 ms (and med/p90 bars) → claim allowed for that coverage
else raise T / fix generator / keep abstaining until true
```

Ear labels are **unavoidable** for the headline number. Two aligners share
failure modes. Protocol time cost: ~3 hours of careful waveform labeling for
~120 ayahs / ~1,200–1,500 onsets (Wilson LCB still ≥99% with 6–8 misses).

---

## 3. Label protocol (non-negotiable)

1. **Freeze first:** `python tools/sync_lab/freeze_label_sample.py`  
   Output: `tools/sync_lab/independent_labels/frozen_sample_v1.json`  
   Never change membership after tuning begins.
2. **Waveform labeling only** — spectrogram + waveform cursor to voicing onset  
   (±10–20 ms human agreement). No real-time ear tapping (±50 ms jitter).
3. **Blind to V2** — seed cursor from V1 or random ±150 ms; never display V2 starts.
4. **Double-label ≥15%** of the test split; publish disagreement rate as the measurement floor.
5. **Evaluate only `split=test`** for the claim; `validation` may tune thresholds.

Fill labels with `tools/sync_lab/label_onsets.py` (or any tool that writes the
same schema into `segments` and sets `labelStatus=done`).

---

## 4. Engineering gates (run without labels)

```bash
# dual-witness + energy snap (slow; GPU)
python tools/sync_lab/gate_timing_v2.py \
  --ctc-max-abs-ms 100 \
  --out tools/timing_v2/alafasy_qua_gated.json \
  --report tools/sync_lab/results/v2_gate_report.json

# objective easy subset
python tools/sync_lab/eval_v2_postpause_gold.py \
  --payload tools/timing_v2/alafasy_qua_gated.json \
  --out tools/sync_lab/results/v2_postpause_gold.json

# freeze sample (once)
python tools/sync_lab/freeze_label_sample.py
```

Ship gated artifact only after `load_timing_v2` accepts it and unit tests pass.
Bump `QuranDatabase.DB_FILE_NAME` on content change.

---

## 5. Decision log

| Date | Decision |
|---|---|
| 2026-07-27 | 99% = accepted-row onset accuracy on frozen ear test; coverage separate. |
| 2026-07-27 | Dual-witness maxAbs≤100 yields 100% CTC-agreement on ~58% of mono sample rows — interim only. |
| 2026-07-27 | Smallest label set ~120 ayahs / ~1.2–1.5k onsets; structure-only labels for repeat P/R. |
