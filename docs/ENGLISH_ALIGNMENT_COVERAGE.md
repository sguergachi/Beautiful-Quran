# English alignment and reader ink coverage

The October 12 audit separates exact meaning ownership, continuous ink, and
rendered playback. The requested 99.99% semantic accuracy is not established. A passing timing lookup or an early prefix does not prove
the correct owner for repeat tint or a tap. These checks do not establish
99.99% accuracy over all possible recitations, translations or UI sequences.

| Check | Result on the frozen runtime corpus and shipped database |
|---|---|
| Published English and actual lyricized word-card English | 6,236 verses and 77,429 Arabic steps each; ranges tile, boundaries do not split words, and every heard meaning advances a continuous prefix |
| Timing rows, all 13 reciters | 81,059 rows, 1,023,997 segments, 17,758 repeat occurrences |
| Boundary and seek queries, each English source | 12,382,926 checks in forward, backward and scrambled order; same clock gives the same word, high-water ink, repeat chain and handoff |
| Independent root/lemma meaning cues | 1,403 correspondences: all exact owners, no delayed washes |
| Annotated exact meaning fixtures | 194 heads, including all 44 Acquainted occurrences, vision/brothers, prayer action/commerce, shared phrases, repeated clauses and lexical variants |
| Gloss-independent QAC candidates | 12,011 additional checks using exact vocalized lemmas and POS; combined rules flag 80 disagreements for review, not a semantic accuracy score |
| Full semantic reference | 56,224 non-added English content tokens; a complete independent reference is absent, so the 99.99% gate fails |
| Six distinct meaning heads | All 720 Arabic orders check exact owners, taps and continuous prefix completion |
| Reader transitions | Back to targets 2–12 with absent/stale/ahead focus, paused first-word waits, old/current generations, held/voice leaves; completed tails, same-verse seeks/repeats and playlist advances |
| Carried English | Every eligible word-space cut in the prayer, reported-head and identity fixtures checks semantic owners and range/prefix continuity on both fragments |

Twelve root/lemma owner disagreements were corrected by distinguishing the
divine Knower title from a nearby verb and preserving names across clauses.
Ordinary repeated content remains sentence-bounded. Prayer's printed
performance recognizes establishing/establishment without stealing a literal
performance of work. The same-verse first-word gate now uses the actual
playback generation on Android and web.

The 23 former shared-card disagreements in
`app/src/test/resources/english-shared-owners.tsv` are now resolved with the
bundled QAC lemmas. A copied “Musa said” card does not identify its two heads;
Musa's Arabic lemma does. `QuranRepository` joins that evidence into the loaded
words, and `EnglishVerseAlignments` supplies it to the pure mapper for both text
sources. Without complete evidence the old ambiguous fallback still applies.

A lexical Arabic head outranks an English gloss mentioning that head as a
complement, such as “fear Allah.” Equal repeated English heads can cross clauses
when their Arabic lemmas are identical. Different Arabic meanings retain the
sentence guard: the burdens in 2:286 have different lemmas. Vocalization is
kept: stripping it conflates an angel with a king and a book with writing.
These decisions and every eligible carried cut of the shared-card fixtures
are regression tested. Sky/heaven, land/earth, true/truth, mosque spellings,
prayer action and plural wives are recognized in the lexical vocabulary.

The additional rules in `english-arabic-meanings.tsv` use QAC directly,
without reading the English input gloss as the owner reference. They are
candidates for linguistic review, not automatically gold labels. The source
word counts reject ten verses whose bundled QAC positions are clamped against
a different segmentation. `untrusted-morphology.tsv` preserves their keys and
counts. Counts alone cannot resolve translation expansion, omitted nouns,
pronoun references or alternate senses; all 80 remaining candidate
disagreements stay in `meaning-issues.tsv` for review.

## Establishing a semantic accuracy claim

`--require-semantic-accuracy 99.99` measures the full, frozen published-English
population: 56,224 unbracketed content tokens, using the audit's documented
English token/grammar filter. This is a definition of content-word ownership,
not every aspect of listening accuracy or every possible future translation.
At most five tokens may be wrong **or unreviewed** to meet that threshold.

`--semantic-reference reviewed.tsv` accepts one independently reviewed row per
token: verse key, character start, exclusive end, exact printed token, and
1-based Arabic owner. Blank owners remain unreviewed. The audit rejects
changed text, invalid owners and duplicate labels, so repeating rows, timing
queries, frames or reciters cannot increase semantic confidence. It records
corpus and mapper hashes in `semantic-certification.json` and exports
`semantic-reference-template.tsv` without predicted owners, ready for a
bilingual review. Missing reference data makes the requested gate fail.

Automated QAC candidates, literal agreement and passing regression fixtures
are kept separate from this reference. They have uncovered real bugs, but
cannot validate the remaining unreviewed meanings. The finite gold-reference
score also does not establish a statistical claim about unseen inputs. A
95% one-sided bound of 99.99% would need at least 29,956 independent,
representative zero-error observations; these selected checks and repeated
frames are not that sample.

The nine unavailable timing rows are Alafasy 37:152; As-Sudais 2:198, 2:223,
13:37, 73:4; and Ash-Shuraym 12:50, 12:75, 12:76, 91:15. The audit lists them
separately and checks every present row, without creating timings.

Run the existing ownership audit on a locally exported, frozen runtime TSV
(`verse_key`, published translation, Arabic-ordered glosses joined by `|`).
Pass the annotated exact fixtures with `--semantic-cases`, the spoken fixtures
with `--spoken-cases`, and `english-meaning-cues.tsv` with `--meaning-cases`.
Add `--strict-meaning-owners` to require exact owners; it currently reports the
shared-card ambiguities (now none in that original cue set). Then run:

```bash
python3 tools/audit_reader_sync.py "$BQ_CORPUS" /tmp/published/ownership.tsv --output /tmp/published-timings.json
python3 tools/audit_english_alignment.py "$BQ_CORPUS" --word-by-word --output-dir /tmp/lyricized
python3 tools/audit_reader_sync.py /tmp/lyricized/corpus.tsv /tmp/lyricized/ownership.tsv --output /tmp/lyricized-timings.json
./gradlew testDebugUnitTest
cd web
npm test
npm run build
```

These tools compile the production Kotlin mapper/timing engine, use a linear
timing oracle, retain diagnostic disagreements and read the database without
changing it. Keep the full runtime corpus outside Git and packaged assets.
