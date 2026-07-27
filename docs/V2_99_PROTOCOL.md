# Timing V2 — automated 99% (no ear labeling)

**Status:** binding product protocol  
**User constraint (2026-07-27):** no human listening / labeling farm. Scale and
quality must come from **audio + models only**.

**Companion:** `docs/TIMING_FIRST_PRINCIPLES.md`

---

## 1. What “99%” means without humans

Primary **automated** bar (non-circular):

> On rows V2 *accepts*, ≥99% of word starts that follow a clear pause
> (≥250 ms near-silence) land within 100 ms of the independent **energy-rise
> onset** in the same everyayah file. Also report median ≤25 ms and p90 ≤60 ms
> on that set. Coverage is a separate number.

Why this is allowed:

- Post-pause energy rise is measured from the waveform, not from the CTC model
  path that produced the spans (different algorithm: RMS threshold vs CTC FA).
- It is exactly the product event users hear: “after a pause, the next word
  lights with the voice.”

Also report (secondary, still automated):

| Metric | Role |
|---|---|
| Dead-zone onset rate | Must be ~0 on accepted rows |
| CTC path-score distribution | Abstention / quality |
| Dual-model agreement (optional) | Extra abstention signal |
| Coverage of Alafasy | Always stated with accuracy |

**Illegal claims**

- Folding coverage into accuracy (“99% of the Quran” when 40% accepted).
- Scoring CTC against itself, or QUA against QUA-derived clocks only.
- Claiming structure/repeat 99% without an automated structure metric.

**Optional later (not required to ship automated bar):** frozen waveform labels
remain in `independent_labels/` as research, not a release gate.

---

## 2. Automated architecture

```text
everyayah MP3 + canonical words[1..N]
        │
        ├─► pinned Arabic CTC forced align → spans + keyframes
        ├─► snap lead-in / energy refine / trim (reconciled to CTC evidence)
        ├─► auto confidence: no dead-zone starts; optional onset-match frac
        ├─► ACCEPT → timings_v2   else ABSTAIN → bundled V1
        │
        └─► measure: eval_v2_postpause_gold.py  (CI / release gate)

Repeats (structure ≠ 1..N):
        QUA same-take transfer when waveform identity matches, else V1
```

Primary generator: `tools/sync_lab/generate_timing_v2_auto.py`  
(wraps `generate_timing_v2.py@3` + `auto_confidence.py`).

---

## 3. Release commands

```bash
source /tmp/alignlab-venv/bin/activate

# full Alafasy mono CTC (~40 min on RTX 3080, checkpointed)
python tools/sync_lab/generate_timing_v2_auto.py \
  --min-path-score -1.5 \
  --min-onset-match-frac 0.0 \
  --out tools/timing_v2/alafasy_ctc_auto.json \
  --checkpoint tools/sync_lab/results/v2_auto_full.jsonl

# automated accuracy gate (must clear bars on accepted rows)
python tools/sync_lab/eval_v2_postpause_gold.py \
  --payload tools/timing_v2/alafasy_ctc_auto.json \
  --out tools/sync_lab/results/v2_postpause_gold_ctc.json

# merge QUA same-take repeats (structure CTC cannot encode)
python tools/sync_lab/merge_v2_lanes.py \
  --ctc tools/timing_v2/alafasy_ctc_auto.json \
  --qua tools/timing_v2/alafasy_qua.json \
  --out-dir tools/timing_v2/

python tools/build_db.py   # then bump QuranDatabase.DB_FILE_NAME
python tools/sync_lab/test_timing_v2.py
./gradlew testDebugUnitTest
```

**Ship rule:** post-pause `within100Pct ≥ 99`, `medianMs ≤ 25`, `p90Ms ≤ 60` on
the accepted CTC payload; dead-zone starts = 0; coverage printed.

---

## 4. Calibration note (2026-07-27 sample n=150)

Raw CTC mono + energy refine (path ≥ −1.5, no onset-frac cut):

- accept ~93% of attempted ayahs  
- post-pause gold: **med 9 ms / p90 52 ms / 100% ≤100 ms** (n=41 matched onsets)

Strict onset-match frac ≥0.85 collapses coverage without helping this bar.
Prefer path-score + dead-zone abstention; raise frac only if post-pause regresses.

---

## 5. Decision log

| Date | Decision |
|---|---|
| 2026-07-27 | User rejects ear-label farm; automated post-pause gold is the ship bar. |
| 2026-07-27 | Primary clock = everyayah CTC FA, not QUA transfer, for mono scale. |
| 2026-07-27 | QUA same-take retained only for **repeat structure** lanes. |
| 2026-07-27 | Accuracy and coverage always reported separately. |
