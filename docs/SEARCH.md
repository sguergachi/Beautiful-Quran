# Quran search

Home search is one offline ranker shared by Android and web. It searches chapter
names and `surah:ayah` references, then ranks Quran results using five grounded
signals rather than a general-purpose language model:

1. **Literal text** — whole word or phrase in the English source belonging to
   the selected layout. Scroll searches the timed word-gloss prose; Mushaf
   searches its flowing English translation. Search results are never Arabic,
   and the other English source never contributes a hidden result. An exact field match leads an
   embedded phrase, reordered multi-word coverage, and a plain substring.
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
   a bounded corroboration bonus. Visible evidence of the query—or its
   spelling correction—in the ayah translation or word glosses receives a
   small grounding boost, so it leads broader ayahs that only share the tag.
   Multi-word phrases use the same pass: for example, `saving money`, `personal
   finance`, and `budgeting` resolve to `Wealth Management`. Focused aliases
   supplement gaps in the pinned ontology without an extra Quran scan.
5. **Spelling fallback** — a single insertion, deletion, substitution, or
   adjacent transposition, only for queries of four or more characters and
   only when literal, concept, and thesaurus stages all return nothing.

Results are deduplicated per ayah, sorted by descending score, and retain Quranic
order as the tie-break. Changing the reader layout immediately reruns an active
query against its new visible-source policy. The UI then groups that ranked
stream by surah. Literal word hits preserve their word position for the reader flash. Full-ayah phrase
and concept hits resolve to a grounded word gloss when the visible evidence has
one; translation-only auxiliaries such as `could` use the nearest grounded verb.
Word-position alignment deliberately does not reuse broad search substring
ranking: it matches whole gloss tokens and simple inflections, so an adjacent
preposition can never hide inside an unrelated gloss (for example, `in` inside
`indeed`). The nearby-word fallback is additionally restricted to modal verbs.
Translator additions with no Quran-word gloss, such as `[in Hellfire]` in
19:45, are searchable only in Mushaf English results; they never leak into
Scroll gloss results. When visible, they wipe the exact canonical-translation
term.
Result snippets color every visible term that helped the result rank: the typed
text, thesaurus terms such as `peace` and `tranquility`, query-related word
glosses, and non-filler words from the matched concept's label and vocabulary.
That vocabulary also grounds navigation, so `hell` can resolve a Hellfire
concept's visible `Fire` gloss and pulse that same word in the reader. Derived
and concept terms require whole-word or inflection matches: `fire` never colors
or targets the substring in `firewood`. Only the user's typed text retains
prefix matching. Overlapping terms resolve to the longest precise phrase, and
connective words remain ordinary ink. If the selected English source contains
none of those, the result stays unaccented rather than promising a highlight
the reader cannot locate. The gloss fallback coalesces adjacent copies of a
shared multi-word gloss, matching the reader's English prose instead of showing
the source phrase once per Arabic word.

Clearing a search keeps the field focused and the keyboard available for the
next query. Back/Escape remains the explicit focus-dismiss action.

The quiet reference line explains each result as `Text match`,
`Related · tranquility`, `Concept · Divine Mercy`, `Same Arabic root`, or
`Spelling match`. The section heading reports the number of relevant ayahs, and
the empty state says when no relevant ayah was found. Both apps begin searching
after a 120 ms pause. The web paints its loading cue, builds a cold index on the
next task instead of waiting for an idle callback, and then yields between scan
chunks; Android checks cancellation throughout the scan. Typing therefore
never waits on an obsolete rank.

When spelling fallback wins through either Quran text or concept vocabulary,
its actual corrected term is carried with the hit rather than inferred by the
UI. A quiet, regular-weight line above the Quran count says
`Searching instead for corrupt`; correctly spelled searches and quoted, root,
or thesaurus results never show an autocorrection notice.

Opening a positioned hit waits until the reader sheet and its target have both
settled, then loops the same soft orange window across every grounded word in
the ayah. For example, a Hell concept result whose visible evidence is
`punishment` and `Fire` carries both word positions into the reader rather than
discarding all but the first. Each window travels from outside the word's left edge,
across every letter, and fully out through its right edge before starting again.
Each cycle is one continuous enter → cross → exit motion rather than an
accumulating fill or a whole-word pulse. While it runs, all non-target chapter
text recedes to 40% strength; Arabic uses a paper cover rather than glyph alpha,
so marks stay clean and every orange target remains full-strength. The
scrolling layout waits for its
verse geometry; Mushaf waits for the requested
leaf to be visible and never depends on the unmounted scrolling list. The
orange overlay uses bold English or a tight glyph-shaped ink spread, making the
filled moment conspicuous without reflowing the verse or reshaping Arabic. If
English prose coalesced a gloss shared by several Arabic words, its hidden slot
redirects to the visible phrase; Arabic layouts still pulse the exact matched
Arabic word. Ayah-level phrase and concept hits are resolved back to the word
gloss behind the visible gold term before navigation, so a preview highlight
such as `hearts` is also the word that pulses in the reader.

## Exact quotes

A pair of straight or typographic double quotes around the complete query
disables spelling, thesaurus expansion, roots, concepts, token reordering, and
substring matching. Literal matching still observes the active reader sources.
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
