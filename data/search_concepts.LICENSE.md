# Search concept data attribution

`search_concepts.json` is an adapted, compact search index generated from
**QSAC — Quran Semantic Annotation Corpus 1.0**, Copyright © 2026 Ahmad Bilal:

<https://github.com/dev-ahmadbilal/quran-semantic-annotation-corpus>

QSAC's dataset and ontology are licensed under the
[Creative Commons Attribution 4.0 International License](https://creativecommons.org/licenses/by/4.0/).
The generated index retains only the ontology vocabulary and ayah-to-concept
assignments; it omits QSAC's copies of the Arabic text and English translation.
The generator also appends a small, declared set of product-authored search
aliases where natural English phrasing is absent from the ontology vocabulary.

The exact upstream revision and source-file hashes are pinned in
`tools/build_search_concepts.py`.

The same generated file also contains focused lexical links adapted from
**Open English WordNet 2025**, Copyright © the Open English WordNet
contributors:

<https://en-word.net/>

Open English WordNet is released under the
[Creative Commons Attribution 4.0 International License](https://creativecommons.org/licenses/by/4.0/).
The generated index retains related lemmas and graph distance only; definitions,
examples, and the full lexical database are not distributed.
