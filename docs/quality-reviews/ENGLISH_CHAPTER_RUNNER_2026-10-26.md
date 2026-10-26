# Resumable English chapter review — 2026-10-26

The offline runner and the AI-reviewed Chapter 64 pilot are complete. The
packaged experiment remains off by default, separate from human review.
The existing word-card mapping/clock and the Arabic/source timing data are unchanged.

## Scope and result

`review_english_chapters.py` supports chapter lists/ranges, a default budget of
25 new/retried verses, 1–4 workers, saved model stages, bounded attempts,
provider-limit stopping, explicit held cases, and per-chapter/full-book reports.
Only compiler-valid, fully model-reviewed verses can reach the candidate lookup.
The whole candidate must pass production playback checks before atomic promotion.

Chapter 64 has **18/18 verses and 503 printed tokens**, with no held verses.
All tokens include grammar and additions. Drafting and fresh critique used
`gpt-6.1-sol`, xhigh reasoning, and fetched quran.ai Arabic, Sahih International,
Arabic Jalalayn commentary and word morphology. Actual reader surfaces and
positions were checked after excluding verse-number markers.
There are **61 changed tap heads, 62 changed ink anchors and 33 null-linked
tokens** relative to the mapper draft. Changes are not an independently measured
error count. Previous An-Nur/Al-Furqan/regression decisions are reused with their
original provenance: the complete AI artifact now contains **162 verses and
5,074 tokens**, with **6,074 fallback verses**. Human-reviewed coverage remains zero.

Local unit IDs that mixed contributors or reused one ID across disconnected
runs were split deterministically into contiguous contributor portions. No
meaning, contributor, interaction head or ink anchor is changed by this step.
Raw drafts, separation receipts and the exact critique inputs are preserved.

## Playback and regression checks

The audit uses production typesetting, actual reviewed-record lookup and
`EnglishVerseAlignments`, then the same presentation plan, forward word progress
and inverse finish functions used by drawing/follow. It also compares word-card
maps with AI selection enabled and disabled. Public bundled voices supplement
the six authorized runtime packs; withheld runtime rows are not backfilled.

- **81,059 timing rows, 6,236 verses, 13 reciters**.
- **162 reviewed lookups and 162 word-card isolation checks**.
- **1,203,184 shared-projection checks; 432,577 quiet/plateau checks**.
- **312,572 admitted finish events**, maximum independent forward first-crossing error **0 ms** under the stated numerical allowance.
- **0 AI-introduced collapsed cores** relative to the current default reader.
- **401 legacy source-order skips** remain. The broader 13-voice check also found
  **six existing default collapsed cores in reciter 9, 7:40**, outside this AI
  corpus. Both current/default and legacy exposure times are retained. These
  are recorded, not treated as fixed or as semantic certification.

With `--alignments`, any AI mapping that removes positive default exposure fails.
Without that option, the earlier strict comparison against legacy windows remains.
This separate comparison prevents inherited default defects from being mistaken
for AI regressions while preserving an explicit inventory of them.

The final JVM/debug gate passes **1,744 tests, zero failures/errors, one existing skip** in 194 suites. The ledger compiler has 12 passing Python regressions, the chapter runner has 16, and the semantic acceptance gate has two. Debug and exact-source R8 release assembly pass. The delivery script rebuilds the clean committed release before sharing.

## Resume, provenance and limits

The first run exposed actual server contracts: tafsir entries use `ayahs`, and
morphology includes a final verse-number marker. The client handles these
explicitly and still rejects wrong positions/surfaces or mismatched verse/edition
responses. The CLI later hit its usage limit; incomplete work stayed held. It
resumed after access returned. Exact completed prompts/schemas/model/reasoning
were reused, with **36 stage-reuse receipts** in the publication run.
Loaded review policy is captured once; live editor line-number changes cannot
silently rebind executing functions. Every new ledger row retains automation,
rubric, draft and critique digests. The manifest records runner/evidence/rubric
components in addition to the text/export/compiler components.

The emulator lease and Device panel were opened for a native check. The exact
returned agent-device launcher failed with `Remote daemon is unavailable`.
No new native visual, physical audio-route or device-frame result is claimed;
the lease/panel were released. The renderer, one-letter feather, typography,
clock calibration and source segments were not edited by this work.

AI critique is not independent bilingual gold. quran.ai shares upstream data
with the app. Compiler completeness, timing consistency and positive velocity do
not establish semantic accuracy, universal perceptual steadiness or 99.9% confidence.
Only Hani has measured within-word quiet in this path; other voices use timing gaps.
The remaining chapters are available to the resumable runner, not already reviewed.

## Reproduction and private evidence

See [the ledger instructions](../ENGLISH_ALIGNMENT_LEDGER.md#resumable-chapter-review)
for the run/report commands. The pilot used `--chapters 64 --workers 2 --timeout
1200`, after an initial three-worker run. The work directory is
`~/.cache/bq-english-chapters/`: frozen export, raw evidence, model stages,
checkpoints, report/coverage, promoted lookup and playback inventories. The
13-voice default control is under `default-baseline-control/`. Raw/provider
responses, caches and logs are excluded from Git and the APK.

Local logs: `/tmp/bq-chapter64-publication-run.log`,
`/tmp/bq-chapter-final-full.log`, `/tmp/bq-chapter-semantic-final.log`,
`/tmp/bq-chapter-ledger-final.log`, and the final release/send logs.
