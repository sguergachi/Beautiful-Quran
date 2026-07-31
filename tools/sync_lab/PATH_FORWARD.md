# Path forward — after testing Codex’s proposal

**Date:** 2026-07-26  
**Models:** lab runs on RTX 3080; structure/clock experiments in `test_codex_path.py`  
**Codex review:** [CODEX_APPROACH_REVIEW.md](CODEX_APPROACH_REVIEW.md)  
**Raw numbers:** [results/codex_path_eval.json](results/codex_path_eval.json)

---

## What we wanted

| Goal | Meaning |
|---|---|
| ~99% word timing | Onsets within ~25–100 ms of true speech |
| Correct repeats | Position sequences with backtracks when the reciter re-says |
| Scale | Automated build-time pipeline |
| Clean | Kill `timing_repairs/` rule pile (dephantom, unsplit, span protection, …) |

---

## What we tested (Codex §D, implemented)

| Experiment | Implementation | Status |
|---|---|---|
| Free decode with times | `grammar_structure.timed_free_decode` | Done |
| Canonical candidate set + score vs decode | `grammar_structure.select_structure` | Done |
| QDC as **candidate**, not unconditional fallback | `grammar+qdc_prior` method | Done |
| Clock on fixed structure | global FA vs `by_runs` vs “anchored” (global for now) | Done |
| Honest metrics | exact / BT P·R / **rep+ only** / clean FP | Done |

Not done (needs human labels): independent ear-onset gold, full 300-row random structure sample.

---

## Results (11 hard gold cases = shipped post-repair structure)

### Structure

| Method | Exact | Rep+ exact (7 cases) | Clean FP (4 cases) | Mean P / R |
|---|---:|---:|---:|---:|
| mono (1..N only) | 4/11 | **0/7** | 0 | 36% / 36% |
| decode_v1 (old unique-span) | 7/11 | **3/7** | 0 | 73% / 68% |
| grammar (decode candidates only) | 7/11 | **3/7** | 0 | 64% / 64% |
| **grammar + QDC as candidate** | **11/11** | **7/7** | **0** | **100% / 100%** |
| qdc alone (oracle on this set) | 11/11 | 7/7 | 0 | 100% / 100% |

**Repeat-positive cases recovered by decode alone (no QDC):**  
3:21, 2:14, 5:46 — same three as before.

**Need QDC candidate (decode empty / weak):**  
2:33, 4:169, 4:163, 5:44 — word-form / weak sim margin class.

**Critical scoring bug fixed during this run:**  
penalizing “extra words” on decode-similarity *rejected real multi-word re-says* when decode was noisy (4:169: gold sim **higher** than mono, but length penalty flipped the ranking).  
**Gate with a small mono margin on raw decode-sim, not a per-extra-token tax.**

### Clock (structure fixed to gold; vs *shipped* times = proxy only)

| Method | Weighted med \|Δstart\| vs shipped | ≤100 ms | Pad recovery |
|---|---:|---:|---:|
| global FA | ~130 ms | ~52% | **~0 ms** |
| by_runs (letter-weight slices) | ~138 ms | ~52% | n/a |
| anchored (currently = global) | ~130 ms | ~52% | **~0 ms** |

Interpretation:

- Pad residual **0 ms** → aligner is time-consistent (good).
- Large \|Δ\| vs shipped **does not** mean CTC is 130 ms wrong — clocks differ (qdc vs CTC), and some shipped rows look off (e.g. Husary 2:1 first start).
- **Cannot claim 25/60 ms product bar without ear-labeled onsets.**
- Prefer **global fixed-sequence FA** over proportional `by_runs` for production clock until true episode anchors exist.

---

## What this means for Codex’s architecture

### Confirmed (build this)

1. **Split structure and clock.** Forced-align path score for structure is dead (prior lab + Codex).
2. **Free decode is the right *acoustic evidence* for structure**, not FA path score.
3. **Score candidates against free decode** (edit / similarity), with a **mono abstention margin** for FP control — clean cases stayed **0 false backtracks**.
4. **QDC must be a scored candidate**, not “if no hit then QDC” (that slogan preserves false splits *and* real spans blindly).
5. **Clock = force-align the chosen position sequence** (global FA first). Karaoke hold is display policy after acoustic starts.

### Partially confirmed / refined

| Codex claim | After test |
|---|---|
| Grammar decoder alone → 99% structure | **Not yet.** Alone = **3/7** on rep+ hard set (same as decode_v1). Foundation, not finish. |
| QDC as prior helps | **Yes, when scored.** On this suite, **grammar+QDC candidate → 11/11** with **0 clean FP**. |
| Delete timing_repairs now | **No.** Need shadow + independent gold first. Candidate arbitration *replaces* the rule *pile*, but only after scale eval. |
| Anchor-bounded clock | Not truly tested; need timed episode anchors from decode char ranges wired into windows. |

### Circular-eval warning (Codex was right)

The 11 cases **are** post-repair QDC structure. Feeding that structure as a candidate and scoring 11/11 proves:

> “When QDC is the regression gold, decode-sim selection + margin prefers it over mono without inventing false BTs on clean cases.”

It does **not** prove QDC is acoustically true, or that we can invent structure without any prior.  
**Next gate must be independent ear labels + random sample.**

---

## Best path forward (decisive)

```text
BUILD-TIME (per reciter × ayah)
───────────────────────────────
1. CTC free decode (timed chars)          ← one forward pass
2. Build candidates:
     • mono 1..N
     • decode-derived resay sequences (grammar inserts)
     • validated QDC structure (if present)
     • later: second CTC model paths
3. Score each by similarity(decode, concat(words[positions]))
4. Pick best; if non-mono, require score ≥ mono + margin
5. If top two too close → FLAG (keep shipped / Lab), do not guess
6. CLOCK: global force-align of chosen positions → startMs
7. holdEndMs = next start (display)
8. Validate invariants → DB row or flag

RUNTIME unchanged: HighlightEngine + OutputLatency
```

### How this kills the repair pile

| Old rule | Replacement |
|---|---|
| repeat-vs-split discriminator | Candidates scored; no FA path for structure |
| dephantom | Margin + no short function-word-only resays without span evidence |
| “never erase qdc span” | QDC is a candidate; can lose to mono if decode agrees mono |
| unsplit_false_pairs | False QDC splits lose when mono scores better |
| realign_span for shifts | Decode span evidence or QDC candidate compete on one score |
| Whole-ayah CTC re-time for structure | Clock only after structure is fixed |

### Production rollout stages

| Stage | What ships | Gate |
|---|---|---|
| **0 — now** | Keep current DB + repairs | — |
| **1 — shadow** | Run grammar+candidates offline; log winner vs shipped; no DB write | 0 clean FP on hard suite; flag rate report |
| **2 — reclock only** | Keep structure from shipped; reclock starts with Arabic CTC where confidence high | Ear sample onset med ≤40–50 ms before broad ship |
| **3 — structure cutover** | Winner of candidate scorer → structure; repairs generator frozen | Independent gold: ≥99% exact on random test + ≥99% rep-event P/R on challenge set |
| **4 — delete repairs** | Remove `timing_repairs/` generator | Stage 3 green for one full reciter regenerations cycle |

---

## Ranked approaches (post-test)

| Rank | Approach | Use |
|---:|---|---|
| **1** | Free-decode candidate scoring + QDC candidate + mono margin + fixed-sequence clock | **Primary product path** |
| **2** | Same + second CTC / MMS as extra candidates | Confidence + word-form recovery |
| **3** | Keep shipped (qdc+repairs) as migration baseline | Stage 0–2 safety |
| **4** | Quran-finetuned free decode | After architecture + labels; fixes 2:33/4:169/4:163 class |
| **5** | decode_v1 alone | Research baseline; keep as one candidate generator |
| ✗ | FA path multi-hyp for structure | Stop |
| ✗ | Whisper text for structure | Stop |
| later | MFA phone clock | After structure is boring |

---

## This week / next (concrete)

### Do now (engineering)

1. **Shadow runner** over full Alafasy (or one reciter): write JSON of  
   `{winner, mono_sim, qdc_sim, margin, flag}` per ayah — no DB bump.  
2. **Product structure gate** in tests:  
   - clean FP must stay 0 on gold_structure_cases  
   - report rep+ exact separately (fail if we regress below 3/7 decode-only or 7/7 with prior on this suite)  
3. **Wire true episode anchors:** map resay insert points → decode char times → FA windows (finish Codex clock experiment properly).  
4. **Do not** regenerate `timing_repairs/` or delete them yet.

### Do next (labels)

5. Ear-label **structure** on ~100 random + all hard cases (double-label 10%).  
6. Ear-label **onsets** on ≥50 ayahs / ≥1000 words.  
7. Only then claim 25/60 ms or 99% structure.

### Explicitly later

- Fine-tune Quran CTC decode  
- MFA / sub-word wash keyframes  
- Full cutover + DB version bump  

---

## Definition of done (defensible “what we want”)

| Metric | Bar | Notes |
|---|---|---|
| Structure exact (random test) | ≥99% | Independent of shipped DB |
| Repeat-event P/R (challenge) | ≥99% | Enriched for re-says |
| Clean FP rate | ~0 on non-repeat sample | More toxic than a miss |
| Onset med / p90 | ≤25 / ≤60 ms | Ear gold only |
| Onsets within 100 ms | ≥99% | Ear gold |
| Flag rate | Documented; Lab residual | Flags ≫ silent wrong structure |
| Rules in `timing_repairs/` | **None in the hot path** | After cutover |

---

## Bottom line

**Best path:** Codex’s S/C split is right.  

**Tested refinement:**  
- Decode-only structure ≈ **half** the hard re-says (3/7), zero clean FPs.  
- **Scoring QDC (and mono, and decode inserts) on free-decode similarity** with a **small margin** is the clean arbitration layer that can retire the repair rules.  
- On the current regression suite that reaches **11/11 with 0 clean FP** — necessary but not sufficient (circular gold).  
- Clock: **fixed-sequence global CTC FA**; pad-stable; true ms quality needs ear gold.

**Ship order:** shadow candidate scorer → reclock-with-fixed-structure → independent gold → structure cutover → delete repairs.

**Refuse:** more FA structure templates, unconditional QDC fallback slogans, 99% claims from 11 circular cases or composite energy scores.
