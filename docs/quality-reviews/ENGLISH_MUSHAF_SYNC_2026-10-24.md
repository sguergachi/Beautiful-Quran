# English Mushaf opening and flicker — 2026-10-24

The user rejected the presentation in `75278a647`: it rushed the opening and
then waited. The selected semantic labels and absence of paint flashes below
were insufficient to validate pacing. The readable-phrase follow-up is recorded
in [ENGLISH_PHRASE_PACING_2026-10-24.md](ENGLISH_PHRASE_PACING_2026-10-24.md).

Hani Ar-Rifai, translation English Mushaf, 64:1 reproduced two independent
problems: a delayed opening wash and words briefly returning to faint ink.
The fixes affect runtime alignment and Android painting. No timing row,
packaged database, reciter clock offset, or English Scroll pacing changed.

## Opening ownership

The published translation's exalt family did not match the word gloss's
glorify family. In the baseline (`c8692a4ac`), `exalting` in 64:1 belonged to
Arabic position 9 and the first continuous wash band belonged to position 2.
Hani's first segment is 1,020–2,140 ms; position 2 starts at 2,140 ms. That
introduced a 1,120 ms delay after the first spoken onset.

Three shared lexical keys now bridge glorify, its existing `glorifi` stem,
and glorification to exalt. The opening band's owner is position 1, while
semantic taps retain their own owners, including heavens at position 5.
Reordered text still advances as a continuous prefix, without skipped words.

The canonical reference was the public Quran.ai MCP, after
`fetch_grounding_rules`: Arabic, Sahih International, and all 17 morphology
slots for [64:1](https://quran.com/64:1?translations=20), plus translation
checks for the other six fixture verses and the four retained cases below.
Returned keys and pagination were checked. App word positions agree for the
reported verse. Lexical ownership labels are reviewed model decisions, not an
independent semantic benchmark. Raw provider responses remain local.

## Candidate coverage and limits

The reproducible search predicate was a word gloss containing the glorify or
glorification family and published English containing the exalt family. It
found 52 Arabic positions in 48 verses of the 6,236-verse runtime corpus.
Every candidate has bundled QAC root `سبح`.

[The complete inventory](english-mushaf-glorification-candidates-2026-10-24.tsv)
records every candidate's before/after family owners and disposition:

- Nine positions have explicit regression labels; the seven fixture verses
  contain 15 exact-owner and continuous-prefix targets, including controls.
- Eighteen additional positions acquire a candidate anchor. Twenty retain an
  existing candidate anchor. These classifications are mapping observations,
  not additional independently certified labels.
- Five positions retain unresolved baseline ownership: the first exalt head
  in 17:44 and distinct glorification/elevation constructions in 6:100, 10:18,
  16:1 and 17:43. Their affected heads remain unchanged. In 17:44, the separately
  reviewed later exalting head is corrected to position 17.

The complete before/after comparison changes ownership in 29 verses, all
inside the 48-verse candidate set; the other 19 candidate verses are retained.
Existing phrase handling keeps the Exalted in Might title on its distinct
Arabic owner in 57:1, 59:1, 61:1 and 62:1. Six reordered-head permutations also
check exact owners and continuous prefixes.

The existing audit passes all 6,236 verse structures and 77,429 Arabic wash
steps, with 15 annotated semantic and 15 annotated spoken targets. Its literal
correspondence counts are 37,265 on-owner, 28 early and 17 late out of 37,310;
these diagnostics are not semantic accuracy. This work does not establish
99.9% accuracy or resolve the wider unreviewed semantic population.

Reproduce against an authorized local runtime export:

```sh
python3 tools/audit_english_alignment.py "$CORPUS_TSV" \
  --output-dir "$AUDIT_DIR" \
  --semantic-cases app/src/test/resources/english-glorification-owners.tsv \
  --spoken-cases app/src/test/resources/english-glorification-owners.tsv
```

Private capture fingerprints (SHA-256):

| Input | Digest |
|---|---|
| Full corpus TSV | `6bcfa75c289458d04b1455bfe64f23469219319eac92218dda37ee715af0a566` |
| Before owner snapshot | `4a9c6ca7d534b7befb84e820ebd8f1210f1743b45ef9c26f21918316142425b6` |
| After owner snapshot | `62180c2befdbe26fbef2c714791108ea64143615097c0dfc96f91d476527b3f8` |

## Rendered flicker

The liquid material projected the semantic wash's character edge into printed
words, while its underlying fallback cover swept a whole phrase by pixel
width. Those edges differ for wide and narrow glyphs. When the wet material
retired, the phrase cover could re-cover a completed word. Shared-owner wetness
also activated fields for words the forward wash had not reached.

Both layers now use the same per-word character projection. Future words stay
unread, with only the next word prewarmed. Equal unread covers merge across
spaces, and word ranges are cached with the paragraph. The soft feather,
word-boundary lead, repeat tint and deposited rewind remain in place.

The combined opening test exposed another flash: several English words can
finish inside one short Arabic window while the verse's 520 ms recess lift
is still running. Reapplying that lift after each word finishes dims it again.
`EnglishWashEntry` limits recess to the prefix present when the playback
generation enters. Newly washed words stay laid. Middle seeks still lift their
existing prefix; changing the generation or reciter resets the entry.

Native verification used the same pooled API 35 emulator, Hani 64:1 through
64:2, 1080×2400 capture, and 29 fixed stroke regions across the first three
English lines. At 20 samples/s, ignoring the initial three seconds of explicit
restart, an abrupt lightening means an increase over 25 gray levels between
samples:

| Capture | Samples | Abrupt lightening events | Largest increase |
|---|---:|---:|---:|
| Baseline | 467 | 8 | 111.59 |
| Paint-only control, original mapping | 465 | 0 | 11.79 |
| Final mapping and paint fixes | 466 | 0 | 11.72 |

A separate combined capture before the entry fix had two opening spikes of
113–139 levels. The final recording removes those too. No AndroidRuntime
crash was recorded. Source videos, stroke samples and exported logs remain
local; they are not packaged or committed.

The full Android gate passes 1,682 JVM tests with one existing skip, including
the exported sample/frame replay, exact Hani opening window, fallback material
retirement, unread words, opening recess, seek, reciter-change and reordered
prefix regressions. Emulator clocks and pixels do not establish physical
speaker/Bluetooth audio synchrony; that still needs a phone recording.
