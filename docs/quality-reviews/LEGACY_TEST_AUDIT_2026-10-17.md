# Legacy code and test audit — 2026-10-17

Audited Android production/JVM tests, web production/Vitest/browser checks,
Python tools and Worker tests from `792c0dcd`. Three independent subagents
traced callers, test assumptions and history; the parent reviewed the edits
and ran the baseline and final checks. This is a bounded audit, not proof that
every remaining symbol is reachable or every behavior is covered.

## Removal evidence

- **Android cross-verse silence seeking:** `a1a7bc26` removed the production
  imports/calls of `playbackSkipMs`, `isAfterLastWord` and `nextVerseAyah`,
  replacing cross-verse seeks with play-through and `VerseSeamFade` to avoid
  clicks. `nextVoicedMs` only served the abandoned helper. Whole-repository
  search found no remaining production callers. Remove those four internal
  functions and seven tests of the retired policy; keep the six live
  `VerseOpeningTest` cases for hizb-only seeking, recitation style, first-word
  voice, wash starts and silence parsing. Keep both seam-fade test classes.
- **Web repeat queue/context:** `4c0ae91f` introduced serial orange washes;
  `103307d0` added its seek helper. `8f868a77` replaced both renderers with
  `useRepeatWash`: measured dwell and concurrent residuals. No live hook read
  the old context. Its wrappers only still supplied `WordMotionProvider`.
  Keep that provider at the same verse/page scopes and in the dynamic browser
  harness; remove the unused context, queue and four tests of the old policy.
  Current repeat lifecycle checks remain in `check-ink-browser.mjs`.
- **Six web compatibility aliases:** `runPaperCoverPeel`, `runOpacityReveal`
  (`4eb7ebb6`), `initialAyahMountRange`, `slideWindowToward` (`ba35a5ae`),
  `BRUSH_CHECK_PARAMS`, `BRUSH_CHECK_PAINT_MS` (`fcc58c10`) had no application
  consumers. Only two tests asserted window alias equality. Remove those
  tests; retain all tests of the actual expand-only window and far-jump policy.
- **Python `_key`:** private helper in `find_flattened_resays.py`, introduced
  already unused in `0e547a18`. Repository-wide search found its definition
  only. Remove it; all flattened re-say generator regressions remain.

Searches included scripts and documentation, not just application imports.
History was read before removing the old queue/seek policy. No database,
timing correction, acoustic hold, migration or source lock was removed.

## Genuine defects fixed

- **English audit zero-match crash:** the literal diagnostic introduced in
  `d085e5c6` divided by zero when translation and gloss tokens did not overlap.
  A fully reviewed synthetic fixture wrote an established semantic certificate
  but then crashed before the gate completed. Report literal agreement as
  unavailable in that case. New CLI cases prove a complete reference succeeds
  and a missing translator addition still fails the semantic gate, without a
  traceback. Existing missing/wrong-owner cases remain.
- **CTC resume false success:** `c823676f` intentionally skipped represented
  success/error rows, but a resumed audit returned zero after a selected error
  was skipped. Preserve resumption and the original JSONL evidence; count
  selected recorded errors in the exit status, including when new rows succeed.
  Six mocked-model scenarios cover success, retained errors, mixed new work,
  errors outside the verse selection, and errors outside the selected shard.
- **Missing CI coverage:** dictionary matching tests (`7fd6fe89`) and timing
  delta rejection/CLI tests (`4861e0de`) were never wired into Build CI.
  Add both stdlib-only suites. The current-database delta check does not
  replace the verifier's synthetic negative tests.

## Retained coverage and limits

Android's deprecated Boolean `displayedSweepProgress` overload is still called
by repeat rendering. The other overload models the current entry-mask policy;
its tests preserve the prior-word flash regression. Deprecation and test-only
references alone do not prove obsolescence. Keep migration/serialization paths,
legacy timing replay, the dynamic `tarjiWorker` entry point, and test helpers
that verify production CSS. Other test-only policy helpers need stronger
evidence before removal.
In particular, Android download helpers `formatDownloadedBytes`,
`cachedBytesForKey`, `chapterStateIsLive` and `chapterStateIsAction`, and web
geometry/ink policy helpers, remain pending intent review. Sparse references
alone did not justify deleting their behavioral contracts.

The CTC core suite requires NumPy/PyTorch and remains a separately run local
check; Build CI does not install those dependencies. Browser checks use local
PCM and do not certify real-reciter acoustics. Full Compose lifecycle, device
performance and global semantic accuracy are outside this audit's evidence.

## Validation

- Baseline: 166 Android suites / 1,522 tests, 93 web files / 847 tests,
  47 Worker tests, all 13 `tools/test_*.py` scripts, and web production build.
- Android tests were explicitly executed with
  `./gradlew :app:testDebugUnitTest --rerun`; zero skipped/failing tests.
- Final Android: 166 suites / 1,515 tests after removing seven obsolete
  silence-seeking tests. All six live opening-policy tests remain.
- Final web: 92 files / 841 tests and typecheck/production build. Six removed
  tests exercised only the removed queue/helper and compatibility aliases.
- Existing Chromium browser harness: five viewport sizes, base wash
  pause/seek/residual/completion, concurrent repeat residuals, audio analysis
  and two-verse handoff; no page errors. Used its documented browser override
  to select installed Chromium when Playwright's default revision was absent.
- English gate: two unittest methods / six CLI scenarios; forced alignment:
  eleven test functions including six resume scenarios. Dictionary, timing
  delta and flattened re-say suites pass. Timing pipeline: all 115 cases pass.
- `./gradlew assembleRelease` passes with R8 enabled.

No failing assertion was deleted or weakened to obtain a green result.
