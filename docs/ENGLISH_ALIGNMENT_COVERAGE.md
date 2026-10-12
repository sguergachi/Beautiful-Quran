# English alignment and reader ink coverage

The October 12 audit separates exact meaning ownership, continuous ink, and
rendered playback. A passing timing lookup or an early prefix does not prove
the correct owner for repeat tint or a tap. These checks do not establish
99.99% accuracy over all possible recitations, translations or UI sequences.

| Check | Result on the frozen runtime corpus and shipped database |
|---|---|
| Published English and actual lyricized word-card English | 6,236 verses and 77,429 Arabic steps each; ranges tile, boundaries do not split words, and every heard meaning advances a continuous prefix |
| Timing rows, all 13 reciters | 81,059 rows, 1,023,997 segments, 17,758 repeat occurrences |
| Boundary and seek queries, each English source | 12,382,926 checks in forward, backward and scrambled order; same clock gives the same word, high-water ink, repeat chain and handoff |
| Independent root/lemma meaning cues | 1,403 correspondences: 1,380 exact owners, 23 approximate owners, no delayed washes |
| Annotated exact meaning fixtures | 122 heads, including all 44 Acquainted occurrences, vision/brothers, prayer action/commerce, and reordered names/titles |
| Six distinct meaning heads | All 720 Arabic orders check exact owners, taps and continuous prefix completion |
| Reader transitions | Back to targets 2–12 with absent/stale/ahead focus, paused first-word waits, old/current generations, held/voice leaves; completed tails, same-verse seeks/repeats and playlist advances |
| Carried English | Every eligible word-space cut in the prayer, reported-head and identity fixtures checks semantic owners and range/prefix continuity on both fragments |

Twelve root/lemma owner disagreements were corrected by distinguishing the
divine Knower title from a nearby verb and preserving names across clauses.
Ordinary repeated content remains sentence-bounded. Prayer's printed
performance recognizes establishing/establishment without stealing a literal
performance of work. The same-verse first-word gate now uses the actual
playback generation on Android and web.

The remaining 23 correspondences in
`app/src/test/resources/english-shared-owners.tsv` have an identical multiword
card copied onto adjacent Arabic words, such as “Musa said.” The gloss alone
does not identify which word owns the name. Their wash reaches the meaning,
but their semantic spans remain approximate; the exact tap lookup returns no
owner. The strict owner audit remains red for these cases. Do not remove that
failure, invent exact ownership, or report them as correct repeat tint.

The nine unavailable timing rows are Alafasy 37:152; As-Sudais 2:198, 2:223,
13:37, 73:4; and Ash-Shuraym 12:50, 12:75, 12:76, 91:15. The audit lists them
separately and checks every present row, without creating timings.

Run the existing ownership audit on a locally exported, frozen runtime TSV
(`verse_key`, published translation, Arabic-ordered glosses joined by `|`).
Pass the annotated exact fixtures with `--semantic-cases`, the spoken fixtures
with `--spoken-cases`, and `english-meaning-cues.tsv` with `--meaning-cases`.
Add `--strict-meaning-owners` to require exact owners; it currently reports the
23 shared-card ambiguities. Then run:

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
