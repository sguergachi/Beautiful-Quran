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

The full-corpus follow-up also fixes irregular verbs, causative/passive glosses,
reflexive objects, inflected repetitions, divine titles and transliterated
names. Examples include “became divided” → “become divided” (98:4), “cause to
hear” → “make ... hear” (27:80), and Rahman's “Most Gracious” → “Most Merciful.”
Rahman and Rahim stay distinct when both occur. Printed compounds join only
when a gloss names that single meaning; separately spoken “acceptor” and
“repentance” (40:3) retain separate heads. This prevents phrase matching from
delaying the first word of a phrase. The existing partial-gloss fence remains
intact; a unique match can use the neighbouring anchors' sentence context,
without pulling a differently translated word from another clause.
Scoped compounds also cover “seems suitable” → “please,” “seek to hasten” →
“impatient,” and “My way” → “My cause.” Conditional “you come” can match
“brought” when the verse has no literal bring gloss; a real bring gloss stays
distinct. These rules fix the audited one-word delays in 2:145, 4:3, 27:46 and
60:1 without equating every use of come/bring or way/cause.

On a line shared by two verses, taps and holds prefer the caret's actual range
before the geometric hit-slop fallback. A wrapped verse's broad bounding
rectangle can overlap the next verse's words; it must not seek to the previous
verse. Lyric word taps use the same policy, retaining the whitespace fallback.

Previous and Next start the page-swipe animation as soon as they seek onto
another leaf, including while paused. Verse restarts open the first leaf of a
carried verse; a long-verse midpoint skip uses the target word's leaf and the
same continuous English wash as playback. Neither command waits for the word
poll or a font load before starting the turn. The target leaf is held until
the seek arrives, so a stale word cannot turn it back; ordinary playback lead
turns and hand swipes keep their existing ownership rules.
Quick opposite presses chain from the pending seek until Media3 reports its
landing, rather than skipping a verse from stale playback state. A held
carried English leaf releases on the word's aligned leaf, not the verse's
opening leaf.

## Verification on 2026-10-11

Four methods were used:

1. JVM regressions cover 58 real verses, including the reported 24:30/31,
   reordered subjects/adjectives, possessives, repeated verb forms, compounds,
   shared glosses, translator additions and context guards. A separate frozen
   semantic fixture checks all 44 Khabeer/Acquainted correspondences, including
   24:30 word 13. Another 52 selected content-head checks across both reported verses cover
   tell/say, apparent/appears and the headcover's wrap/draw meaning.
   A further frozen fixture checks 77 heard meanings in 56 audited verses.
   These checks require the full printed meaning to have washed by its Arabic
   owner; earlier inclusion is allowed by the forward-wash rule. Negative
   controls keep separately spoken phrase heads, Rahman/Rahim, and incomplete
   glosses distinct.
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
3. A separate reference manifest matches printed names/titles to QAC roots or
   lemmas and independently specified gloss cues, rather than importing the
   mapper's vocabulary. It covers 1,392 correspondences and checks the final
   character of each printed meaning. Ambiguous occurrence counts and added
   bracketed words are excluded; every manifest rule must cover actual cases.
   There are zero delayed washes. Semantic owners agree in 1,357 cases; the
   remaining 35 are earlier estimates, with the meaning already included.
4. Android API 35 playback with Alafasy confirms the reported ink visually and
   checks Media3's paused position against the bundled timing segments.

| Check | Before full-corpus follow-up `973a2ce1` | Follow-up |
| --- | ---: | ---: |
| Literal comparisons on their assigned owner | 37,226 / 37,310 | 37,271 / 37,310 |
| Literal agreement | 99.775% | 99.895% |
| Flagged comparisons | 84 | 39 |
| Delayed washes in QAC meaning checks | 563 / 1,392 | 0 / 1,392 |
| Structurally valid verse span maps | 6,236 / 6,236 | 6,236 / 6,236 |
| Continuous prefix checks | 77,429 / 77,429 | 77,429 / 77,429 |
| Annotated Khabeer/Acquainted owners | 44 / 44 | 44 / 44 |
| Annotated heads in the reported verses | 52 / 52 | 52 / 52 |
| Further annotated heard-meaning checks | — | 77 / 77 |

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
| Divided lit; until after still dim, 98:4 | 1,460 ms | word 2: 700–1,740 ms |
| First hear lit; nor will still dim, 27:80 | 2,439 ms | word 3: 2,080–2,940 ms |
| Second hear lit; the call still dim, 27:80 | 5,252 ms | word 6: 4,670–5,530 ms |
| Most Merciful lit; disobedient still dim, 19:44 | 12,048 ms | word 8: 10,880–12,580 ms |
| Shared-line tap selects 19:44 rather than 19:43; first Satan lit, later clause dim | 6,004 ms | word 4: 4,080–7,030 ms |
| Rebuilt release: Acquainted lit; with what still dim, 24:30 | 14,025 ms | word 13: 12,860–14,470 ms |
| Please lit; you and the later suitable still dim, 4:3 | 7,384 ms | word 9: 6,870–7,710 ms |

A backward seek from the second “hear” to the first in 27:80 also leaves the
later phrase fully dim, with no old glimmer surviving ahead of the new prefix.

Additional transport checks in the release build on 2026-10-12 confirm the
visible swipe in both directions:

| Reading | Playback state | Previous command | Visible leaf |
| --- | --- | --- | --- |
| Arabic | Paused and playing | 2:6 → 2:5 → 2:6 | 3 → 2 → 3 |
| English | Paused and playing | 2:12 → 2:11 → 2:12 | 3 → 2 → 3 |
| English | Paused | 2:26 midpoint, word 21 at 21,790 ms | 4 → 5, the carried leaf |

Rapid Back → Next during the first swipe also returns to the original verse
and leaf in both readings. The earlier 2:21 → 2:20 check confirms that Back
opens the carried verse on leaf 3, rather than its tail on leaf 4.

The word positions also agree with the
[Quranic Arabic Corpus, 24:31](https://corpus.quran.com/wordbyword.jsp?chapter=24&verse=31).
The new Khabeer correspondence is Arabic word 13 in the
[Quranic Arabic Corpus, 24:30](https://corpus.quran.com/wordbyword.jsp?chapter=24&verse=30).
The full-corpus verb cases also agree with the
[QAC analysis of 98:4](https://corpus.quran.com/wordbyword.jsp?chapter=98&verse=4)
and [27:80](https://corpus.quran.com/wordbyword.jsp?chapter=27&verse=80).
The soft edge and fresh-ink glimmer remain visible; repeat color uses the same
semantic ranges. All 1,339 JVM tests and release assembly passed; the
unchanged timing pipeline passes all 104 patch cases.

## What the percentage means

**99.895% is literal agreement on the selected comparisons, not semantic
accuracy over every translated word or every playback instant.** The diagnostic
cannot judge synonyms it does not recognize, translator explanations, omitted
meanings, shared phrases or ambiguous repetitions. The remaining 39
flags are reviewed in [the audit ledger](english-alignment-audit.tsv). They
include grammatical phrases, different senses and approximate semantic owners
whose meaning is already included by the continuous wash. For example, the
literal diagnostic pairs “conduct”
in 2:282 with the later gloss “sinful conduct”; the annotated regression
requires the earlier business meaning. Reusing the original context guards
preserves that earlier owner, and lowers this literal proxy's score compared
with unconstrained keyword matching. Annotated regressions cover gaze/vision
and other non-literal correspondences separately. The continuous-prefix checks establish
the no-gap policy, not exact timing for English words advanced by reordering.
Audio-boundary accuracy is also separate from translation ownership.

The QAC manifest and frozen examples check heard-meaning coverage, not a random
sample of every translated word. Exact global 99.9% semantic accuracy would
need an independently annotated benchmark covering the remaining vocabulary,
ambiguity and audio boundaries. This change retains approximation where the
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
  --semantic-cases app/src/test/resources/english-semantic-heads.tsv \
  --spoken-cases app/src/test/resources/english-semantic-patterns.tsv \
  --meaning-cases app/src/test/resources/english-meaning-cues.tsv
```

It compiles the actual mapper, emits semantic and wash character spans,
validates structure and the independent prefix oracle, checks supplied semantic
fixtures, and
writes `ownership-audit.json` and `ownership-issues.tsv`. An optional
`--baseline-ends` accepts the old mapper's TSV of verse key and comma-separated
character ends for the same frozen corpus. Its output explicitly labels the
score as a literal diagnostic.
