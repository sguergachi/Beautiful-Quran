# English alignment and reader ink coverage

The October 12–13 audit separates exact meaning ownership, continuous ink, and
rendered playback. Neither the requested 99.9% confidence nor the earlier
99.99% semantic accuracy is established. A passing timing lookup or an early prefix does not prove
the correct owner for repeat tint or a tap. These checks do not establish
99.99% accuracy over all possible recitations, translations or UI sequences.

| Check | Result on the frozen runtime corpus and shipped database |
|---|---|
| Published English and actual lyricized word-card English | 6,236 verses and 77,429 Arabic steps each; ranges tile, boundaries do not split words, and every heard meaning advances a continuous prefix |
| Timing rows, all 13 reciters | 81,059 rows from the public DB plus six hash-verified runtime packs; 1,023,989 segments, 17,748 repeat occurrences |
| Boundary and seek queries, each English source | 12,384,384 checks in forward, backward and scrambled order; same clock gives the same word, high-water ink, repeat chain and handoff |
| Production English wash functions, published mapper output | 4,128,128 monotone-edge queries in one forward pass; 227,910 completion checks for previously started bands at repeats. Compiles the real window/progress functions; excludes frame interpolation and page-cut rendering |
| Independent root/lemma meaning cues | 1,403 correspondences: all exact owners, no delayed washes |
| Annotated exact meaning fixtures | The existing 194 heads plus both words of 24:4 “ever after,” including grammar and sentence-tail completion |
| Gloss-independent QAC candidates | 12,011 additional checks using vocalized lemmas and POS; all 13,413 combined correspondences pass with twelve reference guesses corrected by the reviewed labels |
| Quran.ai MCP review | The original 80 selected targets in 74 verses pass: 70 owner errors corrected, ten already-correct owners preserved; seven additional heads in 24:2–3, every “clear evidence/evidences” phrase in 32 verses, all main heads in 24:1, opening/female qualifiers, expanded descriptions, six modern-spelling targets and five word-card guard targets also pass |
| Full semantic reference | 160 of 158,066 printed words have model-reviewed, source-supported labels; 157,906 remain unreviewed. Grammar and translator additions count. The 99.9% gate fails; a complete independent benchmark is absent |
| Six distinct meaning heads | All 720 Arabic orders check exact owners, taps and continuous prefix completion |
| Reader transitions | Back to targets 2–12 with absent/stale/ahead focus, paused first-word waits, old/current generations, held/voice leaves; completed tails, same-verse seeks/repeats and playlist advances |
| Carried English | Every eligible word-space cut in the prayer, reported-head, identity, An-Nur opening and 32 clear-evidence fixtures checks semantic owners and range/prefix continuity on both fragments |

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
pronoun references or alternate senses.

The 80 October 12 flags were reviewed through the official Quran.ai MCP
(`https://mcp.quran.ai`, server 0.1.9), after `fetch_grounding_rules`.
`fetch_word_morphology` and `fetch_quran` supplied Arabic evidence;
`fetch_translation` supplied Sahih International and Abdel Haleem. Jalalayn
was selected from `list_editions` for concise word-level parsing and fetched
for the adjacent phrase in [5:12](https://quran.com/5:12/tafsirs/926). All returned verse keys were checked against
the request. Raw responses and the full corpus stay in private audit scratch.

The review found 70 owner errors and ten already-correct owners. Twelve
dictionary guesses were wrong: ten were false alarms, while two identified
an error but proposed the wrong correction. Noun/verb ambiguity and different
translation wording caused these mistakes. The complete selected review is
in [english-alignment-review-queue.tsv](english-alignment-review-queue.tsv),
and its character-bound labels are in
[english-alignment-reviewed-owners.tsv](english-alignment-reviewed-owners.tsv).
The audit uses reviewed labels ahead of dictionary guesses and retains every
conflict in `candidate-reference-issues.tsv`.

The mapper now distinguishes punishment from its verb, recognizes scoped
gloss variants, and keeps repeated night/home/reward families from stealing
a differently worded head. Reversed adjacent gloss heads require reciprocal
Arabic identities. A grammatical card with no head requires the actual
Arabic word to corroborate its lemma; this prevents three clamped-position
regressions exposed by the full word-card scan. Latin accents are normalized
only in matching keys, preserving printed words and combining marks.

On the R8 release emulator, holding “punishment” in 88:24 opens `العذاب`,
while “punish” opens `فيعذبه`. Holding “hear” in 7:198 opens `يسمعوا`, even
though its input word card says only “do they not.” These checks exercise
the production reader's Arabic-evidence wiring as well as the pure mapper.

The word-card literal diagnostic has one discrepancy: the card labels “way”
at 5:12 as word 44, while Arabic `السبيل` is word 45. The reviewed semantic
owner is 45 and the wash remains continuous. Literal agreement is a diagnostic,
including when it exceeds 99.99%; it does not certify semantic accuracy.

These are model-adjudicated, selected labels grounded in fetched evidence.
Quran.ai and this app share Quran.com/Quran Foundation data, and these 160
cases are not an independent representative sample or a complete gold corpus.

The October 13 report exposed an uncovered opening in 24:2. Its word card
names the female head as “fornicatress,” while Sahih International prints
“woman” with a longer explanation. The old fixture checked only `lash<=4`,
which neither required the correct verb owner nor checked the opening ink.
New tests first reproduced an Upcoming opening during Arabic word 1 and a
lash tap on the later noun. Scoped gender-head variants now recognize the
expanded wording while preserving literal gender and offender heads; flog
and lash share their action cue. The first phrase belongs to word 1, man to
word 2 together with its expanded description, the lash action to word 3
and lashes to word 8. The fetched
[Sahih International translation](https://quran.com/24:2?translations=20),
Arabic morphology and [Jalalayn](https://quran.com/24:2/tafsirs/926) support
the reviewed labels. Exact ownership, active/upcoming ink and every eligible
carried cut of the new fixture are regression tested.

The adjacent 24:3 fixture checks all three printed fornicator heads. Its
“[female] fornicator” belongs to Arabic word 5, rather than the following
conjunction at word 6. A female qualifier recognizes the retrieved feminine
source head without changing the male head. The rule requires that source
head and preserves a wholly bracketed addition. These labels use the fetched
[24:3 translation](https://quran.com/24:3?translations=20) and morphology.

The further 24:1 report exposed an incomplete phrase: “clear” belonged to
bayyināt at word 7, but “evidence” waited for word 8. The production mapper
now treats “clear evidence/evidences” as a single printed meaning when the
source names that meaning; separate literal adjective and noun cards keep
separate owners. Bayyina and mubīn lexical heads distinguish repeated
“clear” phrases, including the later clear authority in 4:153. The full
frozen corpus has 32 occurrences: MCP Arabic, both translations and full
morphology were retrieved for every verse, with verse-number tokens excluded
when checking actual Arabic slots. Reviewed labels identified 28 wrong owners
across 27 verses; all 64 phrase-token labels and every eligible carried cut
now pass. The nine main heads of 24:1 also have exact-owner and tap tests, and
its entire phrase has active ink before the following meaning. These are
source-supported model reviews, including
[Jalalayn on 24:1](https://quran.com/24:1/tafsirs/926), rather than a full
independent benchmark.

The 24:4 report exposed a coverage hole in the metric itself: “ever” matched
Arabic word 16, but “after” was classified as grammar and fell back to word 17.
The literal diagnostic and the old content-only reference excluded “after.”
Their scores therefore could not detect this error. A general sentence rule
now keeps an unclaimed grammar tail with its sentence's final Arabic anchor
when the immediately following Arabic word anchors the next sentence. It
preserves explicit matches, earlier owners, additions and unresolved content
tails; it does not add a verse-specific phrase or change the wash clock.
The fetched [Sahih International](https://quran.com/24:4?translations=20),
[Abdel Haleem](https://quran.com/24:4?translations=85), full morphology and
[Jalalayn](https://quran.com/24:4/tafsirs/926) ground the reviewed 24:4 labels.
Its two printed words, every eligible carried cut, the Alafasy
21,830–23,550 ms and Hani 19,480–20,850 ms completion windows are tested.
App reciter 1 is Alafasy (QF 7); app reciter 7 is Hani (QF 5), so source
and app IDs must not be interchanged. General regressions cover
sentence punctuation, separately spoken tails, missing anchors and reflexives.

## Establishing a semantic accuracy claim

Any `--require-semantic-accuracy` threshold or `--semantic-reference` now
automatically includes **every printed word**, including grammar and bracketed
translator additions. `--all-printed-words` selects that population without
requiring a threshold. The frozen published corpus has 158,066 words; at most
158 may be wrong **or unreviewed** to meet 99.9%, or 15 to meet 99.99%.
An audit without these options retains the old content-only diagnostic; its
report explicitly identifies that scope. Literal agreement is never the gate.

`--semantic-reference reviewed.tsv` accepts one independently reviewed row per
token: verse key, character start, exclusive end, exact printed token, and
1-based Arabic owner. Blank owners remain unreviewed. The audit rejects
changed text, invalid owners and duplicate labels, so repeating rows, timing
queries, frames or reciters cannot increase semantic confidence. It records
corpus and mapper hashes in `semantic-certification.json` and exports
`semantic-reference-template.tsv` without predicted owners, ready for a
bilingual review. It records the selected-reference coverage fraction, but
`established` remains false: this file format carries no independent blind-gold
provenance. `--require-semantic-accuracy` consequently fails even for a fully
labelled subset. Meaning-delay and strict-owner checks still run first.

Automated QAC candidates, literal agreement and passing regression fixtures
are kept separate from this reference. They have uncovered real bugs, but
cannot validate the remaining unreviewed meanings. The finite gold-reference
score also does not establish a statistical claim about unseen inputs. A
95% one-sided bound of 99.9% would need at least 2,995 independent,
representative zero-error observations; 99.99% needs 29,956. These selected
checks and repeated frames are not that sample. A finite-reference coverage
target does not establish the independence of that reference.

`python3 tools/test_audit_english_alignment.py` exercises the production
mapper and gate with hand-labelled synthetic input: an unreviewed grammar
word, an unreviewed addition and a wrong owner each fail; a complete correct
reference meets its local coverage target. Neither a subset nor model labels
can set semantic accuracy to established. CI runs these regressions and the
offline ledger's binding/provenance gates alongside the JVM tests. The initial
[ledger artifact](ENGLISH_ALIGNMENT_LEDGER.md) has no approved records.

The nine unavailable timing rows are Alafasy 37:152; As-Sudais 2:198, 2:223,
13:37, 73:4; and Ash-Shuraym 12:50, 12:75, 12:76, 91:15. The audit lists them
separately and checks every present row, without creating timings.

Run the existing ownership audit on a locally exported, frozen runtime TSV
(`verse_key`, published translation, Arabic-ordered glosses joined by `|`).
Pass the annotated exact fixtures with `--semantic-cases`, the spoken fixtures
with `--spoken-cases`, and `english-meaning-cues.tsv` with `--meaning-cases`.
Use `--semantic-reference docs/english-alignment-reviewed-owners.tsv` with
the additional Arabic rules to apply and expose the selected adjudications.
Add `--strict-meaning-owners` to require exact owners; it currently reports the
shared-card ambiguities (now none in that original cue set). For the current public database, supply all six reviewed runtime packs to
`audit_reader_sync.py` with repeated `--runtime-pack /path/to/pack.json` flags.
The audit checks their revision hashes and rejects overlapping generations;
37,411 runtime rows plus 43,648 independent bundled rows recover the full
81,059-row listening corpus. Keep those packs outside Git and packaged assets.
Then run:

```bash
python3 tools/audit_reader_sync.py "$BQ_CORPUS" /tmp/published/ownership.tsv --output /tmp/published-timings.json
python3 tools/audit_english_alignment.py "$BQ_CORPUS" --word-by-word --output-dir /tmp/lyricized
python3 tools/audit_reader_sync.py /tmp/lyricized/corpus.tsv /tmp/lyricized/ownership.tsv --output /tmp/lyricized-timings.json
./gradlew testDebugUnitTest
cd web
npm test
npm run build
```

The timing commands below need those `--runtime-pack` flags on v67.
These tools compile the production Kotlin mapper/timing engine, use a linear
timing oracle, retain diagnostic disagreements and read the database without
changing it. Keep the full runtime corpus outside Git and packaged assets.
