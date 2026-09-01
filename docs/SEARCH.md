# Quran search

Home search is one offline ranker shared by Android and web. It searches chapter
names and `surah:ayah` references, then ranks Quran results using five grounded
signals rather than a general-purpose language model:

1. **Literal text** — whole word or phrase in Arabic, Saheeh International,
   word glosses, or transliteration. An exact field match leads an embedded
   phrase, reordered multi-word coverage, and a plain substring.
2. **Thesaurus vocabulary** — when fewer than three grounded results exist, a
   compact Open English WordNet graph supplements them with related words that
   actually occur in this Quran. Synonyms lead precise derivational, similar,
   and narrower terms; ambiguous target words and broad hypernyms are withheld.
   Thus `calm` reaches `peace`, `tranquility`, and `stillness`, not the
   edit-distance neighbor `call` or the unrelated locative sense of `settled`.
3. **QAC roots** — once a literal word matches, other Quran words sharing its
   Quranic Arabic Corpus root enter below the literal result. Semantic matches
   do not seed more roots, avoiding broad chains such as `calm → peace → سلم`.
   This supplies morphology-aware Arabic retrieval without stemming sacred
   text in the app.
4. **Concept vocabulary** — QSAC tag names, primary terms, secondary terms,
   categories, and domains resolve an English concept to its annotated ayahs.
   Direct tag/primary vocabulary leads secondary vocabulary; category and
   domain matches are broader and score lower. Rarer concepts receive a small
   specificity bonus, and ayahs supported by several matching concepts receive
   a bounded corroboration bonus.
5. **Spelling fallback** — a single insertion, deletion, substitution, or
   adjacent transposition, only for queries of four or more characters and
   only when literal, concept, and thesaurus stages all return nothing.

Results are deduplicated per ayah, sorted by descending score, and retain Quranic
order as the tie-break. The UI then groups that ranked stream by surah. Literal
word hits preserve their word position for the reader flash. Full-ayah phrase
and concept hits use position zero and do not fabricate a highlighted word.
Result snippets color every visible term that helped the result rank: the typed
text, thesaurus terms such as `peace` and `tranquility`, query-related word
glosses, and non-filler words from a matched concept label. Overlapping terms
resolve to the longest precise phrase, and connective words remain ordinary
ink. If the canonical translation contains none of those, the result stays
unaccented (or uses the word-gloss line) rather than coloring a merely similar
or unrelated word.

The quiet reference line explains each result as `Text match`,
`Related · tranquility`, `Concept · Divine Mercy`, `Same Arabic root`, or
`Spelling match`. The section heading reports the number of relevant ayahs, and
the empty state says when no relevant ayah was found. Both apps begin searching
after a 120 ms pause. The web paints its loading cue, builds a cold index on the
next task instead of waiting for an idle callback, and then yields between scan
chunks; Android checks cancellation throughout the scan. Typing therefore
never waits on an obsolete rank.

## Exact quotes

A pair of straight or typographic double quotes around the complete query
disables spelling, thesaurus expansion, roots, concepts, token reordering, and
substring matching.
It keeps only a literal whole word or contiguous phrase, ignoring case and
punctuation boundaries:

```text
mercy                 literal, concept, root, thesaurus, then spelling ranking
"mercy"               literal word/phrase only
"day of judgment"     that contiguous phrase only
```

Quoted `"2:255"` is text search; unquoted `2:255` remains the direct ayah jump.

## Concept asset

`data/search_concepts.json` is a deterministic 560 KB adaptation of
[QSAC — Quran Semantic Annotation Corpus 1.0](https://github.com/dev-ahmadbilal/quran-semantic-annotation-corpus),
plus [Open English WordNet 2025](https://en-word.net/), both licensed CC BY 4.0.
It contains 338 ontology concepts, 16,309 assignments covering all 6,236 ayahs,
and 10,426 focused thesaurus entries linked only to vocabulary present in the
packaged Quran. It deliberately omits QSAC's copies of Arabic text and English
translation and WordNet definitions/examples; the app continues to render its
canonical `quran.db` rows.

The source revision and hashes are pinned. To deliberately update it:

```bash
python3 tools/build_search_concepts.py
```

The generator rejects unknown tags, duplicate ayahs, either source's hash
drift, or any ayah-key mismatch with `quran.db`. Android packages the JSON
directly and decodes it on first search. The web build copies the same canonical
file and fetches/caches it on first search. Attribution is retained in
`data/search_concepts.LICENSE.md`.

## Tests

Pure Android and web tests lock quote parsing, phrase ranking, semantic-before-
spelling tiers, root expansion, concept retrieval, and parity.
`SearchConceptRepositoryTest` also checks the committed asset's concept,
assignment, and complete-ayah counts plus a known vocabulary/ayah witness.
