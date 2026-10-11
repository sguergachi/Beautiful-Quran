# English continuous forward wash

The English leaf reaches the meaning being recited with one continuous
left-to-right wash. **It never leaves a dim word before a later highlighted
word.** If Arabic names a later English meaning, every intervening word joins
its wash. In “husbands' fathers,” both words wash when Arabic says “fathers”; a
later “husbands” never moves the front backwards. The leading edge keeps the
existing feather. Arabic high-water state retains the prefix through repeats.

`EnglishWordAlignment.align` returns semantic `spans` and continuous
`washSpans`. Each wash range takes the earliest Arabic owner at or beyond it
in English order; equal adjacent owners merge. This gives the smallest prefix
that includes every meaning reached so far. Unmatched text uses the guarded
interpolated alignment. Taps, holds and repeat tint keep semantic owners;
carried-leaf follow uses the forward wash. First-pass glimmer shares its range
and clock, so it cannot jump ahead of the leading edge. The selected published
translation or lyricized gloss and both projected maps are cached.

After a backward seek, fading glimmer and repeat tint are suppressed on
Upcoming words, so an old light cannot survive beyond the new prefix. Recited
high-water words keep their fade during a genuine repeat.

“All-Aware” is a phrase equivalent for “Acquainted,” covering Khabeer across all
44 published occurrences. Ordinary “aware” remains a distinct key.
The vocabulary also connects tell/say and apparent/appears. Wrap/draw is scoped
to a verse's headcover phrase, rather than applied to unrelated uses of “draw.”

## Verification on 2026-10-11

Three methods were used:

1. JVM regressions cover 58 real verses, including the reported 24:30/31,
   reordered subjects/adjectives, possessives, repeated verb forms, compounds,
   shared glosses, translator additions and context guards. A separate frozen
   semantic fixture checks all 44 Khabeer/Acquainted correspondences, including
   24:30 word 13. Another 52 selected content-head checks across both reported verses cover
   tell/say, apparent/appears and the headcover's wrap/draw meaning.
   Projection tests check page fragments and punctuation;
   source-selection and follow tests
   check continuous washes, semantic taps and forward follow.
2. An independent Python literal-word diagnostic evaluates the actual Kotlin
   mapper against all 6,236 verses and 77,429 word rows exported from the live
   Android QF cache, joined to the packaged Sahih International translation.
   All span ranges tile their verse, stay between words and name valid Arabic
   positions. At all 77,429 Arabic positions, a separate prefix oracle checks
   that the wash includes the furthest spoken semantic span and every character
   before it, with no skipped word and no delayed recognized meaning.
   The literal comparator uses its own tokenization, possessive/plural
   normalization, unique/equal occurrence counts and sentence restriction.
3. Android API 35 playback with Alafasy confirms the reported ink visually and
   checks Media3's paused position against the bundled timing segments.

| Check | Previous PR head `c6c33253` | This change |
| --- | ---: | ---: |
| Literal comparisons on their assigned owner | 35,245 / 37,310 | 37,226 / 37,310 |
| Literal agreement | 94.465% | 99.775% |
| Flagged comparisons | 2,065 | 84 |
| Structurally valid verse span maps | — | 6,236 / 6,236 |
| Continuous prefix checks | — | 77,429 / 77,429 |
| Annotated Khabeer/Acquainted owners | — | 44 / 44 |
| Annotated heads in the reported verses | — | 52 / 52 |

The corpus export SHA-256 is
`bc2f381494642ae7ed8950b3b77117f6a5d617becc11b51fd12516916f390449`.
The live cache differs from the older local page source in 14 verse rows; the
reported 24:30/31 fixtures use the live cache.

| Release playback observation | Paused clock after live capture | Arabic timing |
| --- | ---: | --- |
| Vision lit; guard still dim, 24:31 | 5,929 ms | word 5: 3,820–6,170 ms |
| Husbands' fathers both lit, 24:31 | 37,370 ms | word 27: 35,270–37,620 ms; word 28 begins 37,620 ms |
| Brothers repeat tinted; preceding prefix retained, 24:31 | 58,090 ms | repeated word 35: 56,990–59,430 ms |
| Acquainted lit; with what still dim, 24:30 | 14,223 ms | word 13: 12,860–14,470 ms |
| Backward seek from fathers to vision leaves no old glimmer ahead, 24:31 | 4,433 ms | new word 5: 3,820–6,170 ms |
| Wrap lit before headcovers, 24:31 | 17,630 ms | word 15: 16,090–18,100 ms |
| Backward seek from wrap to appears leaves no old glimmer ahead, 24:31 | 14,795 ms | new word 13: 14,300–15,120 ms |

The word positions also agree with the
[Quranic Arabic Corpus, 24:31](https://corpus.quran.com/wordbyword.jsp?chapter=24&verse=31).
The new Khabeer correspondence is Arabic word 13 in the
[Quranic Arabic Corpus, 24:30](https://corpus.quran.com/wordbyword.jsp?chapter=24&verse=30).
The soft edge and fresh-ink glimmer remain visible; repeat color uses the same
semantic ranges. All 1,320 JVM tests and release assembly passed; the
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
with unconstrained keyword matching. Annotated regressions cover gaze/vision
and other non-literal correspondences separately. The continuous-prefix checks establish
the no-gap policy, not exact timing for English words advanced by reordering.
Audio-boundary accuracy is also separate from translation ownership.

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
python3 tools/audit_english_alignment.py /tmp/qf-corpus.tsv --output-dir /tmp/english-audit \
  --semantic-cases app/src/test/resources/english-semantic-alignment.tsv \
  --semantic-cases app/src/test/resources/english-semantic-heads.tsv
```

It compiles the actual mapper, emits semantic and wash character spans,
validates structure and the independent prefix oracle, checks supplied semantic
fixtures, and
writes `ownership-audit.json` and `ownership-issues.tsv`. An optional
`--baseline-ends` accepts the old mapper's TSV of verse key and comma-separated
character ends for the same frozen corpus. Its output explicitly labels the
score as a literal diagnostic.
