# English spoken ownership

The English leaf washes the meaning being recited. Each printed span owns a
1-based Arabic word position. A word may own separate English ranges, and an
English word ahead of an unspoken neighbour may wash first. Every range keeps
the existing soft left-to-right edge. Arabic high-water state retains all ink
already laid during a repeat; English sentence order does not decide retention.

`EnglishWordAlignment.align` returns these spans plus the guarded forward
frontier used for unmatched text. `EnglishVerseAlignments` caches the selected
published translation or lyricized gloss. The wash, tap, hold and carried-leaf
follow use the same ownership policy. The paragraph projects ranges onto its
fragment once; equal adjacent paper covers merge during drawing.

## Verification on 2026-10-11

Three methods were used:

1. JVM regressions cover 58 real verses, including the reported 24:30/31,
   reordered subjects/adjectives, possessives, repeated verb forms, compounds,
   shared glosses, translator additions and context guards. Projection tests
   check page fragments and punctuation; source-selection and follow tests
   check that ink and interaction read the same ownership.
2. An independent Python literal-word diagnostic evaluates the actual Kotlin
   mapper against all 6,236 verses and 77,429 word rows exported from the live
   Android QF cache, joined to the packaged Sahih International translation.
   All span ranges tile their verse, stay between words and name valid Arabic
   positions. The comparator uses its own tokenization, possessive/plural
   normalization, unique/equal occurrence counts and sentence restriction.
3. Android API 35 playback with Alafasy confirms the reported ink visually and
   checks Media3's paused position against the bundled timing segments.

| Check | Previous PR head `c6c33253` | This change |
| --- | ---: | ---: |
| Literal comparisons on their assigned owner | 35,245 / 37,310 | 37,226 / 37,310 |
| Literal agreement | 94.465% | 99.775% |
| Flagged comparisons | 2,065 | 84 |
| Structurally valid verse span maps | — | 6,236 / 6,236 |

The corpus export SHA-256 is
`bc2f381494642ae7ed8950b3b77117f6a5d617becc11b51fd12516916f390449`.
The live cache differs from the older local page source in 14 verse rows; the
reported 24:30/31 fixtures use the live cache.

| Android playback observation, 24:31 | Paused clock | Arabic timing |
| --- | ---: | --- |
| Vision lit; guard still dim | 6,115 ms | word 5: 3,820–6,170 ms |
| Brothers lit; following brothers' sons dim | 55,876 ms | word 35: 53,600–55,940 ms |
| Reordered sons wash ahead of dim husbands' | 49,264 ms | word 32: 48,650–50,940 ms; word 33 begins 50,940 ms |

The word positions also agree with the
[Quranic Arabic Corpus, 24:31](https://corpus.quran.com/wordbyword.jsp?chapter=24&verse=31).
The soft edge and fresh-ink glimmer remain visible; repeat color uses the same
owned ranges. All 1,313 JVM tests and release assembly passed; the
unchanged timing pipeline passes all 104 patch cases.

## What the percentage means

**99.775% is literal agreement on the selected comparisons, not semantic
accuracy over every translated word or every playback instant.** The diagnostic
cannot judge synonyms it does not recognize, translator explanations, omitted
meanings, shared phrases or ambiguous repetitions. Some of its remaining 84
flags are grammatical words or differently translated meanings, rather than
proven mapping errors. For example, the literal diagnostic pairs “conduct”
in 2:282 with the later gloss “sinful conduct”; the annotated regression
requires the earlier business meaning. Reusing the original context guards
preserves that earlier owner, and lowers this literal proxy's score compared
with unconstrained keyword matching. Annotated regressions cover gaze/vision and other
non-literal correspondences separately. Audio-boundary accuracy is also separate
from translation ownership.

Exact global 99.9% semantic accuracy would need an independently annotated
benchmark covering these cases. This change retains approximation where the
word-card data does not establish a reliable owner.

## Repeat the corpus diagnostic

Export TSV rows as `verse_key<TAB>published_translation<TAB>gloss1|gloss2|...`,
with word glosses ordered by Arabic position. In a debug installation those
come from `no_backup/qf-content-cache.db`, table `reader_words`; the published
text comes from `data/quran.db`, table `ayahs`. Keep the snapshot frozen for a
before/after comparison. The audit requires local `kotlinc` and Java and makes
no network requests:

```sh
python3 tools/audit_english_alignment.py /tmp/qf-corpus.tsv --output-dir /tmp/english-audit
```

It compiles the actual mapper, emits character spans, validates structure and
writes `ownership-audit.json` and `ownership-issues.tsv`. An optional
`--baseline-ends` accepts the old mapper's TSV of verse key and comma-separated
character ends for the same frozen corpus. Its output explicitly labels the
score as a literal diagnostic.
