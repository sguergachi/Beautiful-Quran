# Authenticated timing repair and promotion — 2026-10-17

Alafasy 6:145 contains a genuine second word 12. The old `one_utterance`
correction erased it. The Pixel 10 Lab report supplies the maintainer's ear
witness; Arabic XLSR and MMS independently prefer the restored occurrence
sequence by 0.002710 and 0.001871 log-probability/frame. Its inserted word has
label probability 0.666 / 0.957. The intervening first word-13 label is unspoken.
The existing `discard_unspoken_rewind_onset` operation removes that false lead
and keeps both word-12 occurrences and the later real word 13.

The Lab painted the repeat at 15,957–16,557 ms. That is an estimate inside the
utterance: model starts are about 15,450–15,500 ms and decoded audio contains a
held vowel through about 17,560 ms. The implemented source-clock repeat spans
15,390–17,590 ms. Quran-align independently places real word 13 at 17,590 ms.
No Bluetooth offset or global clock change is inferred. The Bluetooth path is
unchanged, as requested. Full exact-row model outputs are in
`tools/timing_evidence/alafasy-6-145-resay.jsonl`; its regression is
`audio-vetted-rewind-onset-alafasy-6-145.json`.

## Separate defect classes and coverage

| Class / search predicate | Coverage | Disposition |
|---|---|---|
| Every `one_utterance` operation in every corrections file at `de44c6eb` | 3 operations, all reciters included | Repair 6:145; retain ear-reviewed legacy 2:229 / 2:235 and their exact-input authenticated retirements. Both runtime rows remain protected by their existing acoustic holds. |
| Every adjacent equal-position pair in the delivered corpus | Existing complete dual-model audit: 81,059 rows, 13 reciters, 1,021 pairs; 71 repair / 784 retain / 166 uncertain | Promote 67 exact authenticated merges; retain four source mismatches below. No change to the 950 retained/uncertain decisions. |
| A new high-water word immediately before a rewind | 4,337 inventory candidates; 4,313 compatible with the typed shape | Acoustic coverage remains **partial**: 5:2 and 6:145 only. The rest stay unchanged; no class-wide removal is authorized. |
| Lagged peer restoration / repair drift | Three additional authenticated deltas: Abdul Basit 33:6 / 35:10, Sudais 59:5 | Hold complete v65 rows pending exact authenticated acoustic review. This publication does not resolve that separate audit. |

The complete open/closed issue query returned 60 issues, below its 1,000-row
limit. The existing issue inventory records 31 patch edits, including #594,
#598, #856 and #873. The original same-word audit's search, model revisions,
audio hashes, source traces, every disposition and genuine-repeat controls
remain in `WORD_SPLIT_AUDIT.md`, its linked corpus report and
`tools/timing_evidence/word-split-*`. Alafasy 24:25's real word-6 repeat is the
explicit negative control; both models reject merging it. Closed #565's
duplicate component is among the accepted merges. The 6:145 fixture adds a
real repeat that must survive the false-lead removal.

## Authenticated replay reconciliation

All six assembled snapshot hashes remain pinned by `build_db.QF_SOURCE_SHA256`;
the exact private v65 baseline SHA-256 is
`3489bd89a91177d2701a2bbc3e8e414d957e308a539c6ec71122a568015f7c8d`.
The 2026-10-17 live capture equals the preserved October 12 reviewed database
in every row and onset. Raw snapshots, combined databases, full replays and
publication packs stay outside Git under
`~/.local/share/beautiful-quran/timings/runtime/2026-10-17-lab-6-145/`.

The replay initially exposed 89 audit holds removed in `7e3fae8e` (#863).
Reading that commit showed a UI change had pruned them against the public
asset, which now excludes QF voices. Restoring all 94 holds passes the existing
exact archived-baseline parity test. These holds protect reviewed payloads;
their absence from the public asset is not evidence that they are obsolete.

The authenticated replay produces 924 deltas against v65. Of the generated
merge cohort, 67 exactly match both reviewed payload hashes and pass the
unchanged sequence, confidence, emitting-frame ownership and waveform gates.
Their complete existing proofs are promoted into `qf-source-transition.json`.

| Candidate | Authenticated mismatch | Decision |
|---|---|---|
| Alafasy 3:86 | Source word-13 tail ends at 19,395 rather than 20,080 ms | Retire only this exact authenticated operation input; existing whole-row hold remains. |
| Alafasy 7:113 | Reviewed word-2 pair is absent | Same exact-input retirement and baseline hold. |
| Alafasy 11:58 | Reviewed word-5 pair is absent | Same exact-input retirement and baseline hold. |
| Alafasy 47:4 | Replay also changes word-7–9 boundaries rejected by the previous review | Hold the combined candidate; the proof for an exact merge cannot approve other boundary changes. |

The three retirements preserve the legacy guards and fail again if their
authenticated input hashes change. No source assertion or acoustic gate is
weakened. The four candidates remain outstanding even though their original
delivered-row merge proposals passed the separate corpus audit.

Alafasy 5:2's previously reviewed topology candidate is also reconciled against
the exact v65 baseline, including its already accepted 25 ms opening-start
change. Fresh exact-row XLSR/MMS outputs are retained as
`tools/timing_evidence/alafasy-5-2-authenticated.jsonl`. Its later device-painted
boundary proposal remains rejected, and the returning word-11 / word-16 onset
questions stay outstanding. The topology verdict does not approve those edits.

`tools/timing_evidence/runtime-promotion-2026-10-17.json` enumerates every
promoted key, held key, retired input hash and the separate audit coverages.
`tools/timing_verdicts/OUTSTANDING.md` retains the unresolved work. No bundled
asset changes, database extraction bump, raw-source bundle or override JSON
are needed for these runtime voices.

## Validation and publication

The ordinary unmodified `sync_qf_timings.py` preparation passes: 609 accepted
deltas and 315 exact-baseline holds. The output has 81,059 combined rows and
37,411 runtime rows. Compared with the saved live capture, exactly 69 rows
change: the two reviewed rewind examples and 67 exact merges. Every other row,
all onsets, and the seven independent corpora remain identical.

Validation passes: 116 pipeline cases (including archived hold parity), 32 QF
Python tests, 23 same-word/proof tests, eight rewind-operation tests, 47 Worker
tests, and the Android JVM gate (1,523 tests, 166 suites). Semantic review
verifies all 924 exact ledger bindings, recomputes every accepted acoustic gate,
and confirms only 5:2 and 47:4 replace pre-existing verdict entries. Seventy-one
new entries cover the 67 merges, 6:145 and three newly explicit drift holds.

Publication uses the ordinary `sync_qf_timings.py --publish` path. The private
directory retains preparation, publication and live verification logs. An APK
alone does not publish these resources. On a device with a fresh six-day timing
cache, **Settings → Developer → Force Quran cache update** fetches the reviewed
revision immediately without clearing the user's data.
