# Structure lab — eliminating the repair-rule pile

**Date:** 2026-07-26  
**Goal:** Accurate **position sequences** (including re-recitation backtracks)
without the growing stack of CTC heuristics in `tools/timing_repairs/`.

Gold = post-repair `data/quran.db` structure on the cases that *created*
those rules (see `gold_structure_cases.json`).

---

## What we learned (hard)

### Forced-align **path score cannot be the structure judge**

On Alafasy 3:21 chunk 2 (the re-say of فَبَشِّرۡهُم):

| Template | Path score |
|---|---:|
| `(16,17,18)` forward (wrong structure) | **−0.73** (wins) |
| `(16,16,17,18)` correct re-say | −0.88 (loses) |

CTC forced alignment **prefers collapsing** a re-say into one longer word.
That is exactly why `timing_repairs` needed: span protection, unsplit rules,
dephantom, etc. **More path-score multi-hypothesis does not remove those rules**
— it re-derives them.

Pause-first multi-hyp (path-score) on the gold set:

| Metric | Result |
|---|---:|
| Exact position sequence | 4/11 |
| Backtrack bag match | 4/11 |
| False-positive free (no-BT cases) | yes (good) |
| Real re-say recall | poor |

### Free **decode string** *does* surface re-says

Greedy CTC letter decode often still **emits the phrase twice**, even when
forced-align path score wants one span. Matching unique word-spans against
that decode string:

| Case | Result |
|---|---|
| Alafasy 3:21 فبشرهم ×2 | **exact** |
| Husary 2:8 false split | **exact** (no false BT) |
| Hani 2:14 span 12–13 | **exact** |
| Hani 4:157 phantoms | **exact** (no false BT) |
| Alafasy 5:46 word 18 | **exact** |
| Muqattaʿāt 2:1 | **exact** |
| Hani 2:33 span 9–11 | miss (decode mangles أَقُل) |
| Hani 4:169 early span | miss (decode mangles جهنم) |
| Alafasy 5:44 [14,15] | partial (got 15 only) |
| Hani 4:163 word 20 | miss (decode drops يونس) |

**Score: 7/11 exact, 73% mean BT precision, 68% mean BT recall**  
**Zero timing_repairs rules.** False-positive cases stay clean.

Remaining misses are **ASR word-form failures** on specific tokens, not
missing heuristics — fix with a better acoustic model or qdc prior, not more
if/else.

---

## Clean architecture (what actually scales)

```
                    ┌─────────────────────────────┐
  everyayah audio ──┤ STRUCTURE (positions only)  │
  canonical words ──┤  1. greedy CTC decode       │
                    │  2. unique span match → BT  │
                    │  3. else mono 1..N          │
                    │  4. low conf → keep qdc     │
                    └─────────────┬───────────────┘
                                  │ position sequence
                                  │ e.g. […,14,15,14,15,16,…]
                    ┌─────────────▼───────────────┐
                    │ CLOCK (ms only)             │
                    │  force-align that sequence  │
                    │  karaoke hold ends          │
                    └─────────────┬───────────────┘
                                  │
                                  ▼
                         segments → DB
```

### Rules that become obsolete

| Old `timing_repairs` rule | Why gone |
|---|---|
| repeat-vs-split 3-condition discriminator | Structure not inferred from FA path score |
| `dephantom()` function-word filter | Short singles never admitted as resays |
| “never erase qdc span-repeat” | Structure is primary data, not a CTC veto |
| per-position `unsplit_false_pairs` | No whole-ayah CTC structure pass |
| surgical `realign_span` for shifts | Decode match finds correct span (2:14) |
| Re-derive whole ayah from CTC clock | Clock is always “align this fixed sequence” |

### What remains

| Piece | Role |
|---|---|
| **qdc as fallback structure** | When decode confidence low / no unique span |
| **Timings Lab** | Residual hard ayahs (4/11 class until better ASR) |
| **Path-score CTC** | **Clock only**, never structure judge |
| Optional Quran-finetuned CTC decode | Recover 2:33 / 4:169 / 4:163 class |

---

## Code

| File | Role |
|---|---|
| `decode_structure.py` | Structure via free decode + unique spans |
| `structure_engine.py` | Pause/path-score experiments (negative result) |
| `reclock.py` | Clock given fixed positions |
| `test_decode_structure.py` | Gold-case gate (7/11 exact today) |
| `test_structure.py` | Path-score multi-hyp eval |
| `gold_structure_cases.json` | The repair-regression suite |

```bash
source /tmp/alignlab-venv/bin/activate
python tools/sync_lab/test_decode_structure.py   # structure gate
```

---

## Path to ~99%

| Layer | Target | How |
|---|---|---|
| Structure on hard set | 11/11 | Decode method + Quran-domain decode model + qdc fallback when no hit |
| Structure full Quran | ≥99% BT agreement with ear/qdc | Same; flag disagreements for Lab |
| Timing given structure | ≤50 ms median vs ear | MFA or reciter-adapted FA on fixed sequence |
| Patches | Near zero | No rule pile; only Lab on flagged ayahs |

**Do not** try to hit 99% by tuning another path-score multi-hyp on full-ayah CTC — the 3:21 score table is the proof that path is the wrong signal for structure.
