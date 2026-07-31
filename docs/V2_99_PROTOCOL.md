# Timing V2 — 99% gates

**Status:** binding product protocol  
**Companions:** `docs/TIMING_FIRST_PRINCIPLES.md`, **`docs/V2_STRUCTURE.md`**
(rebuild charter after mono-CTC structure failure — **read that first**).

---

## 0. Structure before Lab onsets (hard)

Lab-gold onset scores **do not** prove product quality if phrase re-says are
missing. Canonical fail: **6:10** V1/QUA `…6,7,8,6,7,8…` vs shipped CTC V2
mono `1…13`. See `docs/V2_STRUCTURE.md`.

**Primary structure bar (binding for rebuild):**

> Backtrack / occurrence structure must match gold (QUA∪V1∪Lab). Free mono CTC
> is **not** a structure source. Golden case `6:10` must be exact.

---

## 1. Primary product bar: Timings Lab ground truth

> On grammar-valid Alafasy Timings Lab historical patches, V2 must match **100%
> structure** and ≥**99%** of word onsets within 100 ms (med ≤25, p90 ≤60).

This is enforced as a unit test:

```bash
python3 tools/sync_lab/eval_v2_vs_lab_gold.py --require-pass
python3 tools/test_build_db.py   # includes the same gate
```

Lab gold is shipped as the highest-priority V2 lane
(`tools/timing_v2/alafasy_lab_gold.json` from `generate_lab_gold_v2.py`),
parallel to pure V1 in `timings`. Grammar-invalid bulk-import Lab rows (40)
are excluded.

### Secondary automated bar (scale, non-Lab ayahs)

> On rows V2 *accepts* outside Lab gold, ≥99% of post-pause energy onsets
> within 100 ms (med ≤25, p90 ≤60). Coverage separate.

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
