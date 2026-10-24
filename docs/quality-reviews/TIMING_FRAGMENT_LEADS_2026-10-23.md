# Merged fragment leads — 2026-10-23

Alafasy 64:11 advanced the English Scroll wash through the next two printed
words while the first Allah was still held. The English owner is correct:
quran.ai morphology returns the app's eighth Arabic slot as the two-word gloss
shown in the report. The defect is the reciter timing, shared by both languages.
English Scroll retains the master pacing restored in `4db33bb9`.

## Cause and correction

Authenticated raw source has word 7 at 6,545–6,985 ms, followed by word 8 twice
at 6,985–8,885 and 8,885–9,245 ms. The 360 ms second occurrence is a dwarfed
fragment, so cleanup merges the pair. Clock rebase adds 25 ms; the delivered
word-8 onset becomes 7,010 ms. Quran-align instead keeps word 7 through
8,890 ms and starts word 8 at 8,900 ms. Fresh XLSR and MMS both force word 8
at 8,965 ms, with word-7 ends at 8,444/8,464 ms. FFmpeg's -32 dB/200 ms
silence detector finds a breath at 8,664.626–8,929.433 ms.

The correction returns the merged lead to word 7 and retains the original
second source onset on the witnessed file clock: 8,910 ms. Only word 7's end
and word 8's start change. Every occurrence, other boundary, audio onset,
English owner, renderer and pacing rule remains unchanged.

`fragment_same_position_leads` is a reusable detector applied after rebase.
It requires the existing short-fragment test, tiled source spans, the preceding
canonical owner, an exact single merged occurrence, an independently
corroborated file clock, quran-align's onset at least 300 ms after the false
lead and within 250 ms of the second occurrence, and its preceding word still
owning the lead. Missing evidence and repeat ambiguities abstain. No existing
fragment, peer-repeat or publication guard was weakened. History consulted:
`2a95fdfc` (#634/#638, dwarfed fragment merging) and `47847881` (#748, substantial
false leads). The older false-lead path remains separate because it depends
on an `unsplit` repair; the new class has already lost the duplicate in cleanup.

## Coverage and dispositions

The complete delivered capture contains 81,059 rows across thirteen reciters.
All six authenticated snapshots were fetched and match the existing pinned
source hashes. The predicate scans every adjacent equal-position fragment pair
having a preceding source label; source/correction/hold ownership excludes none.
The other seven delivered voices do not consume this QDC fragment-cleaning path.

There are 193 source pairs: 185 retain their baseline under the detector's
individual guards; eight qualify across Alafasy, Abdul Basit and Sudais. Every
qualifying row and four controls received real inference from both models.
Initial GPU allocation failures were preserved and rerun; final coverage is
12/12 successful recordings on each model, with no unresolved inference errors.
Both models use the same exact recording hashes and fixed occurrence sequences.
The boundary witness is mean absolute start residual, not Viterbi score.

Five candidates pass both non-regression metrics, the decoded waveform bounds,
unchanged/extended voiced ends, and a maximum 250 ms changed-onset residual:
Alafasy 43:46, 48:17, 64:11; Sudais 8:69, 33:25. Three are held: Abdul Basit
34:27, Sudais 4:104, 21:28. Although their row means improve, the source-derived
onsets remain 264–332 ms from a witness. The prior baseline is retained.

Controls remain byte-for-byte unchanged: genuine repeats Alafasy 3:21 and Hani
4:4, and older substantial-lead fixes Alafasy 19:48 and Hani 66:6. The bounded
[machine-readable review](../../tools/timing_evidence/fragment-lead-review.json)
contains every pair's disposition, source revisions, all changed-row/audio
hashes, both model revisions/metrics and control evidence. Full private source
and neural reports remain in `~/.cache/bq-allah64-proof/`; authenticated replay
and output remain in the private `timings/runtime/2026-10-23-allah64/` directory.

The legacy pinned-source comparison found twelve candidates; four extra Alafasy
rows are no longer candidates in the authenticated source. That comparison
does not authorize a legacy publication. All sixty open/closed repository
issues were searched without a truncated result; no additional issue names a
candidate verse. Existing source history and repeat controls are included above.

## Reproduction and validation

Capture delivery with `tools/audit_word_splits.py capture`. Inventory with
`tools/audit_fragment_leads.py inventory --db <live.db> --snapshots <private>
--source-cache <pinned-cache> --out <private-inventory.json>`. Build the complete
authenticated candidate; the ordinary gate rejects unreviewed rows. An audit
observer saved its pre-verdict rows to private scratch while leaving that gate
active. Run `score` with `--model xlsr` and `--model mms`, the same `--db`,
`--candidate-db`, `--inventory` and private `--out` paths. `review` requires both
complete reports and emits the bounded decisions used by the authenticated ledger.

The authenticated build passes with 74 total accepted historical/current
verdicts and 318 held at the pinned baseline. Compared with current delivery,
its complete output changes exactly the five listed rows. The 64:11 fixture,
six guard regressions and the full timing pipeline pass; the JVM suite also
passes. No public database asset changes or cache-version bump is involved.

This audit covers the merged-fragment-lead class, not arbitrary word boundaries.
Other model residuals in 64:11 remain unchanged and are explicitly recorded in
[OUTSTANDING](../../tools/timing_verdicts/OUTSTANDING.md). Acoustic models and
quran.ai share upstream limitations; this is not a semantic or corpus-wide
accuracy certification. Publication and a fresh runtime read must be checked
separately; an APK rebuild alone cannot deliver authenticated timing changes.
