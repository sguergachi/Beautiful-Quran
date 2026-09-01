# Quran search

Home search is one offline ranker shared by Android and web. It searches chapter
names and `surah:ayah` references, then ranks Quran results using four grounded
signals rather than a general-purpose language model:

1. **Literal text** — whole word or phrase in Arabic, Saheeh International,
   word glosses, or transliteration. An exact field match leads an embedded
   phrase, reordered multi-word coverage, and a plain substring.
2. **Spelling** — a single insertion, deletion, substitution, or adjacent
   transposition, only for queries of four or more characters.
3. **QAC roots** — once a literal word matches, other Quran words sharing its
   Quranic Arabic Corpus root enter below the literal result. This supplies
   morphology-aware Arabic retrieval without stemming sacred text in the app.
4. **Concept vocabulary** — QSAC tag names, primary terms, secondary terms,
   categories, and domains resolve an English concept to its annotated ayahs.
   Direct tag/primary vocabulary leads secondary vocabulary; category and
   domain matches are broader and score lower. Rarer concepts receive a small
   specificity bonus, and ayahs supported by several matching concepts receive
   a bounded corroboration bonus.

Results are deduplicated per ayah, sorted by descending score, and retain Quranic
order as the tie-break. The UI then groups that ranked stream by surah. Literal
word hits preserve their word position for the reader flash. Full-ayah phrase
and concept hits use position zero and do not fabricate a highlighted word.

## Exact quotes

A pair of straight or typographic double quotes around the complete query
disables spelling, roots, concepts, token reordering, and substring matching.
It keeps only a literal whole word or contiguous phrase, ignoring case and
punctuation boundaries:

```text
mercy                 concept, root, spelling, and literal ranking
"mercy"               literal word/phrase only
"day of judgment"     that contiguous phrase only
```

Quoted `"2:255"` is text search; unquoted `2:255` remains the direct ayah jump.

## Concept asset

`data/search_concepts.json` is a deterministic 179 KB adaptation of
[QSAC — Quran Semantic Annotation Corpus 1.0](https://github.com/dev-ahmadbilal/quran-semantic-annotation-corpus),
licensed CC BY 4.0. It contains 338 ontology concepts, their search vocabulary,
and 16,309 assignments covering all 6,236 ayahs. It deliberately omits QSAC's
copies of Arabic text and English translation; the app continues to render its
canonical `quran.db` rows.

The source revision and hashes are pinned. To deliberately update it:

```bash
python3 tools/build_search_concepts.py
```

The generator rejects unknown tags, duplicate ayahs, source hash drift, or any
ayah-key mismatch with `quran.db`. Android packages the JSON directly and
decodes it on first search. The web build copies the same canonical file and
fetches/caches it on first search. Attribution is retained in
`data/search_concepts.LICENSE.md`.

## Tests

Pure Android and web tests lock quote parsing, phrase ranking, spelling,
root expansion, concept retrieval, literal-over-concept precedence, and parity.
`SearchConceptRepositoryTest` also checks the committed asset's concept,
assignment, and complete-ayah counts plus a known vocabulary/ayah witness.
