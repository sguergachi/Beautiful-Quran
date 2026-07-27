# CLAUDE.md

All agent guidance for this repository lives in **[AGENTS.md](AGENTS.md)**.
Read it before making changes — it covers the repo map, build/test commands,
the invariants you must not break, code conventions, and where the detailed
documentation lives.

Quick essentials (details and rationale in AGENTS.md):

- Test with `./gradlew testDebugUnitTest` (JDK 21); run it before committing.
- `data/quran.db` is a committed asset — only regenerate it
  (`python3 tools/build_db.py`) when deliberately changing data, and bump
  `QuranDatabase.DB_FILE_NAME` whenever its content changes.
- UI follows a strict paper metaphor: no dialogs, ripples, shadows, or cards.
  Read `docs/DESIGN.md` before any UI change.
- Ink karaoke fidelity is non-negotiable: soft directional wash with a visible
  faded leading edge (never hard peels / whole-word opacity for "perf").
- **Word↔recitation sync is the product core** — timing fidelity first; see
  `docs/SYNC_FIDELITY.md`.
- **Timings Lab / GitHub timing patches: systematic first.** Do **not** paste
  issue JSON into `tools/timing_overrides/` by default. Classify (raw qdc →
  clean → repairs → Lab), fix the class in `clean_qdc_artifacts` or span-protect
  / repairs, lock with `tools/timing_patch_cases/` + `python3 tools/test_build_db.py`.
  Overrides are last resort for true one-off boundaries only. Full checklist:
  AGENTS.md invariant #8 and "Landing Timings Lab / GitHub timing patches".

## PR Workflow

When creating pull requests:
- Create the PR and push your changes
- Do not monitor PR status, CI, or reviews after opening it
- Do not follow up on CI failures, review comments, or merge conflicts
- Do not worry about PR issues or whether it's mergeable
- Let the user review and check the PR status themselves

Just make the PR and move on.

**Before follow-up work:** check whether the PR is already merged. If it is,
open a **new** PR from a fresh branch off `master` — never push onto a merged
PR's branch. Full rule in [AGENTS.md](AGENTS.md) ("PR workflow (agents)").
