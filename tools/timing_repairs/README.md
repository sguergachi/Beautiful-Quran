# timing_repairs/

Auto-generated structural repairs for reciter word timings. `build_db.py`
applies every `*.json` here before the local `tools/timing_overrides/` scratch
layer. CI rejects shipping scratch overrides; permanent corrections belong in
this systematic repair path.

`*.flagged.json` files are **not** applied — they list the ayahs the generator
refused to auto-repair (low CTC coverage or an implausible lead-in). They are
the manual review queue.

## Repairs are structural, not frozen ayah clocks

Repair files contain whole rows for reproducibility, but `build_db.py` does not
blindly replace the current source row. It sequence-diffs the word positions,
uses repair timing only for changed structural spans plus one neighbour on each
side, and keeps current qdc timing everywhere else. This is systematic across
all repair files and prevents an old missing-word/repeat repair from
reintroducing unrelated stale boundaries when qdc later improves them.

Regression fixtures pin the stale-repair failures from Alafasy 2:214 and 5:52,
alongside the existing cleaner and span-protection cases.

## How these are produced

The generator lives outside this repo (`~/qasr`, see
`docs/TIMINGS_LAB.md` for the manual path). It runs a general-Arabic CTC model
over the same everyayah audio the app streams and compares the acoustic
structure to the qdc segments we ingest. Only *structural* disagreement drives
a repair:

| qdc says | CTC says | action |
| --- | --- | --- |
| repeat | repeat | `keep` — qdc timing is trusted |
| no repeat | no repeat | `keep` |
| repeat | no repeat | `unsplit` — merge that qdc split, keeping qdc timing |
| no repeat | repeat | `restore` — qdc flattened a real re-recitation |
| word missing | word present | `drop` — fill the uncovered position |

CTC is used because it decodes acoustically. A seq2seq model with a Quran
language-model prior (Whisper and every Quran-fine-tuned model) normalises a
re-recitation back to the canonical text — the lower-WER model is the wrong
tool here precisely because it "corrects" the thing we need to observe.

## The repeat-vs-split invariant

Two consecutive CTC tokens on the same canonical word are either a genuine
repeat or one word the model split mid-utterance (elongation/madd emits blank
frames, which is common on low-bitrate and slow recitations). Getting this
wrong in either direction is the defect class that produced most of the
"Timings patch" issues, so the discriminator is deliberately conservative and
has three conditions — **all** must hold to emit a repeat:

1. **A real pause separates them** (≥ 300 ms). Contiguous spans are one word.
2. **Each half stands alone** — both tokens independently resemble the whole
   canonical word (normalised edit distance ≤ 0.45). In a split, only the
   *concatenation* matches; each fragment alone is a poor match.
3. **The halves resemble each other** (≤ 0.45). A repeat is the same word said
   twice, so the two renderings should be alike.

Each condition exists because dropping it caused a real regression:

- Without (1), the aligner collapsed Alafasy 3:21 فَبَشِّرۡهُم (two full
  utterances 640 ms apart) into one span, hiding the repeat.
- Without (2), Husary's split words became false repeats — 2:8 ءَامَنَّا decodes
  as `آمَ` + `نَّابِ` across a 920 ms gap. This produced 1184 false restores on
  Husary alone (19% of the Quran) before the condition was added.
- Without (3), the muqatta'at slip through: 2:1 الٓمٓ decodes as `ألِف` + `لم`,
  and on a 3-letter word both fragments pass (2) by coincidence — but they look
  nothing like each other.

`~/qasr/test_align.py` pins all of these as regression cases. Run it before
regenerating any repair file; aggregate repair counts alone will not reveal a
broken discriminator.

## Phantom function-word repeats (issues #531/#533)

Even a correctly-detected pair can be a mirage. When CTC drops or splits a word,
the leftover fragment often matches a short earlier function word (مَا, مِنۡ, فِي,
إِلَىٰ, …) and the aligner backtracks to it, inventing a repeat that is not in the
audio. `dephantom()` removes these: a re-cover is kept only when it is **part of
a re-recited span** (an adjacent re-cover at the consecutive position) **or on a
distinctive word** (≥ 4 normalised chars). An isolated re-cover of a short word
is folded away.

The discriminator is span-vs-isolated, **not** word length: 2:33 أَلَمۡ أَقُل
لَّكُمۡ is a genuine re-recitation on 3-char words and survives because it is a
span; Hani 4:157 وَمَا / مِنۡ are lone short-word re-covers and are dropped.

## Non-contiguous span phantoms (Alafasy 5:54 class)

A different phantom shape slips past both the isolated-stray rule and CTC span
protection: qdc stamps an **early** function-word index at the *onset* of a real
near-high-water re-say. Example (raw Alafasy 5:54 after high-water 23):

```
… 21, 22, 23,  4, 21, 22, 23, 24 …
               ^ mislabel of the long يُجَٰهِدُونَ onset as مَن
```

The run is multi-position so CTC `dephantom` treats it as a trusted span-repeat;
`HighlightEngine` paints orange from word 4 through 23 ("repeated more than it
should"). This is fixed **in `tools/build_db.py` → `clean_qdc_artifacts`**, not
in the CTC repair generator: within each backtrack run, position components
separated by more than `QDC_SPAN_CONNECT_GAP` (2) are split, the component
nearest the high water is kept, and orphan positions are relabeled onto that
component's start (so the time stays on the word being said). Real contiguous
spans, same-word re-says, and spans with a single internal drop survive.
Regression cases live in `tools/test_build_db.py`.

## Gap phantoms (Alafasy 5:59 class)

A backtrack run that does **not** re-cover the high-water tip, immediately
followed by a first-pass resume that **skips** words, is a mislabel of the
skipped span — not a re-say. Example (raw Alafasy 5:59 after high-water 11):

```
… 10, 11,  8, 9,  13 …
              ^   ^  word 12 missing; 8/9 sit on its time
```

`relabel_gap_phantoms` in `clean_qdc_artifacts` remaps the run onto
`HW+1 … next−1` when `run_max < HW` and `next > HW+1`. A real re-say of the tip
re-covers HW; a real earlier re-say resumes at HW+1 — both are untouched.
Case: `tools/timing_patch_cases/gap-phantom-alafasy-5-59.json`.

## False إِلَّا أَن phrase loops (issues #594/#598)

Qdc labels four Alafasy occurrences of stretched `إِلَّا أَن` as
`A,B,A,B`. The two Lab reports, cached CTC, and the monotonic quran-align
witness all identify the middle labels as fragments, not a re-recited span.
`collapse_false_phrase_loops` fixes the exact recurring text/topology class
to the first A and final B. Other alternating phrases still pass through the
ordinary span-repeat protection unchanged.

The Lab boundary clock is then applied surgically with `kind: "boundary"`
repair entries. Such entries contain only the uniquely occurring positions
they replace; `apply_boundary_repair` cannot alter the rest of the ayah.

## Trusting a qdc span-repeat CTC collapsed (issue #533)

CTC confirms or restores a repeat, but it must never **erase** one. CTC
routinely collapses a re-recited phrase into one long span (it merges the
re-say), so absence of a CTC repeat is not evidence the repeat is false.
Therefore a qdc **span-repeat** (two or more consecutive positions re-covered,
e.g. Hani 4:169 `[1,2,3,1,2,3,…]`) is kept even when CTC does not confirm it.

A lone same-position qdc pair is still judged by CTC — merge it when CTC hears
one utterance (the false-split class), keep it when CTC confirms (3:21). Without
the span protection the generator deleted the correct Hani 4:169 re-recitation.

### Apply-time guard (issue #570)

Older committed repair files still contain `drop` rows that flattened real
span-repeats (Alafasy 5:59 is the archetype: raw qdc had `[…7,8,9,7,8,9,…]`,
the drop repair shipped a monotonic 20-word row). `build_db.apply_timing_repairs`
skips any repair whose segments would erase a multi-position span-repeat
already present in the cleaned qdc row (`erases_span_repeat`). Counts show as
`span-protected N` in the build log. Prefer regenerating repairs with the
generator invariant; the guard is the safety net for committed files.

## Splits are judged PER POSITION (issues #551/#558/#559)

One ayah can hold a real repeat *and* a false split at the same time, so the
decision cannot be made for the ayah as a whole. Alafasy 5:44 has a genuine
`[14,15]` span plus a bogus split on word 6 (وَنُورٞ); 5:46 has a genuine repeat
on 18 plus a bogus split on 17 (وَنُورٞ again — the same word splits at the same
spot in both ayahs). An earlier whole-ayah rule asked only "does this ayah
contain any real repeat?", so the real one immunised the false one and both
shipped.

`unsplit_false_pairs()` therefore walks the same-position pairs one at a time.
A pair is real when CTC confirms that position, or when it is span-supported
(a neighbouring re-cover at the consecutive position — CTC collapses spans, so
they are trusted unconfirmed). Everything else is merged.

The merge is **surgical**: the two spans are folded together inside qdc's own
timing, and the rest of the ayah is left byte-for-byte alone. This is better
than the previous behaviour, which re-derived the whole ayah from CTC — a
rougher clock — just to remove one bad boundary.

## Re-timing a mis-positioned span (issues #417/#531/#533)

qdc sometimes places a real re-recitation a word or two off: Hani 2:14 marks
`[13,14]` (مَعَكُمۡ إِنَّمَا) when the reciter actually repeats `[12,13]` (إِنَّا
مَعَكُمۡ); 2:33 marks `[11,12]` for a re-say of `[9,10,11]` (أَلَمۡ أَقُل لَّكُمۡ).
CTC hears the boundary correctly, so `realign_span()` re-times from CTC — but
ONLY for the narrow shifted-span shape: both the qdc and CTC repeats must be
contiguous runs of ≤ 4 positions that overlap or abut. A long qdc span
(2:110 `[5..12]`) or a fragmented CTC span (2:145 CTC `[20,21,24,26,28]`) is left
on qdc's timing — there CTC is the rougher source and re-timing would make it
worse. This is what let the four ear-verified `patches-from-417.json` overrides
be deleted: the generator now reproduces all of them (2:14, 2:33, 4:143, 67:22)
with no manual patch.

## Regenerating

Repairs must be generated against a **raw qdc** database — i.e. with this
directory emptied and `data/quran.db` rebuilt — because the generator diffs
against what is in the DB. Generating against an already-repaired DB silently
produces an incomplete file (already-applied fixes read as `keep`).
