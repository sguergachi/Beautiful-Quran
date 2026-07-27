# sync_lab — automated word-timing research + production aligner

Build-time only. Finds (and can regenerate) millisecond word timings for
everyayah recitations **without human ear passes**.

## Start here

**Handoff (single canonical doc):**  
**[docs/TIMING_FIRST_PRINCIPLES.md](../../docs/TIMING_FIRST_PRINCIPLES.md)** — learnings, plan, roadmap, 409-patch gate, next tasks.

Supporting notes (evidence, not the plan):

1. [CODEX_APPROACH_REVIEW.md](CODEX_APPROACH_REVIEW.md) — Codex architecture review  
2. [PATH_FORWARD.md](PATH_FORWARD.md) / [PATCH_REGRESSION.md](PATCH_REGRESSION.md) — earlier summaries  
3. [RESULTS.md](RESULTS.md) / [STRUCTURE_RESULTS.md](STRUCTURE_RESULTS.md) — bake-offs

## Layout

| Path | Role |
|---|---|
| `decode_structure.py` | **Structure:** free decode + unique span resays |
| `reclock.py` | **Clock:** FA given fixed position sequence |
| `structure_engine.py` | Pause/path-score multi-hyp (negative result) |
| `aligners.py` | Shared CTC force-align primitives |
| `test_decode_structure.py` | Gold hard-case gate (repair regression suite) |
| `gold_structure_cases.json` | Cases that created timing_repairs rules |
| `batch_align.py` | Earlier mono clock dry-run |
| `audio/` | Cached everyayah MP3s (gitignored) |
| `results/` | Lab JSON outputs |

## Quick commands

```bash
source /tmp/alignlab-venv/bin/activate   # CUDA torch + deps

# bake-off
python tools/sync_lab/run_lab.py --pad-test --out lab.json

# re-time one surah
python tools/sync_lab/batch_align.py \
  --reciter Alafasy_128kbps --surah 1 \
  --out tools/sync_lab/results/out.json
```

Winner (2026-07-26): **Arabic CTC + lead_in + onset±40 + silence trim**.
