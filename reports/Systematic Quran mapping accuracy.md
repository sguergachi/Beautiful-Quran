# Replace guessed owners with a reviewed ledger

Beautiful Quran should stop improving English highlighting by adding rules to the runtime mapper. Instead, it should keep a **versioned, offline ledger of reviewed meanings and contributors** for every printed English token. The ledger is bound to the exact typeset text and Arabic reader slots, and a deterministic tool compiles it into a thin anchor table that the app looks up at runtime. Earlier coverage work could not get there. Its literal-overlap diagnostics left out grammar and translator additions. Its mechanical oracles take their expected answers from the mapper itself, so they test consistency, not semantic truth. Its only semantic reference is **160 selected labels, reviewed by a model with source support, out of 158,066 printed tokens**. So 99.9% accuracy has not been established, and the repository says so ([coverage doc](../docs/ENGLISH_ALIGNMENT_COVERAGE.md)). No public authoritative Sahih-prose↔Arabic span map was found, and published aligners have not been measured on this task. That leaves a reviewed finite corpus. A scoped 99.9% claim becomes defensible only through one specific design: a predeclared, uniform random sample of distinct tokens, labelled blind and independently. With zero errors in **2,949** sampled tokens, the one-sided 95% lower bound is ≥99.9% for this frozen corpus. **Current state:** the shipped APK is master-synced at `e6c2d9a2` with a verified Pixel receipt and still runs the heuristic mapper. Everything below is a proposal. New PR work to implement it has been authorized and is under way, but none of it ships yet.

## Every printed word counts, and none is gold yet

The mapper infers owners from lexical overlap between Sahih International prose and QF word-card glosses. Where it finds no match, it interpolates. Its own documentation says synonyms, ambiguous repetitions and translator additions "use an approximate share" ([EnglishWordAlignment.kt](../app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt)). The validation researcher ran an instrumented private copy over the frozen corpus (6,236 verses, 77,429 Arabic slots, 158,066 tokens). It found that **38,622 tokens (24.43%) take an initial fallback or interpolation path**: 10,221 content, 20,054 grammar and 8,347 additions ([validation notes](<../research_notes/Systematic Quran mapping accuracy/validation_and_data.md>)). That figure counts code paths, **not errors**. A fallback can land on the right owner, and a "verified" path can land on the wrong one. Even so, only 452 verses contain no fallback token at all. Reviewing "just the fallbacks" would therefore still touch about 93% of verses ([Opus review §1](<../research_notes/Systematic Quran mapping accuracy/opus_review.md>)).

The Opus round-2 reviewer spot-checked three verses that had already been patched (24:1, 24:31, 24:37). The patched tokens were correct: "clear evidence"→7, "performance"→10 with "prayer"→11. The same verses still held about **12 of 186 tokens that the reviewer judged mis-owned**. Examples are "turn about", "in repentance", "male attendants", and "physical desire". In that last case the QF card is copied across slots 50–52, so a "verified" lexical match was a false anchor ([Opus review §1](<../research_notes/Systematic Quran mapping accuracy/opus_review.md>)). These are one model's judgements on deliberately selected verses. They are **not gold, and they are not a global error rate**. What they do show is how the whack-a-mole pattern works: each patch fixes the reported token and leaves its neighbours alone. A first-round probe found that unmatched vocabulary has a long tail of about 2,400 stems, roughly 1,000 of them singletons ([Opus proposal Q1](<../research_notes/Systematic Quran mapping accuracy/opus_proposal.md>)). Each rule covers a class about as wide as one stem pair, and a rule cannot *record* a decision that has been verified.

## Published aligners draft proposals; none measures this task

Neither of the standard contextual aligners was evaluated on Arabic in its original paper. SimAlign reports English paired with Czech, German, Persian, French, Hindi and Romanian ([SimAlign](https://aclanthology.org/2020.findings-emnlp.147.pdf)). Awesome-align reports German, French, Romanian, Japanese and Chinese ([awesome-align](https://aclanthology.org/2021.eacl-main.181.pdf)). The real English–Arabic gold benchmark is XL-WA: **210 test and 90 dev sentences**. On its test set, neural systems reach micro link F1 of **~82.8–82.9** ([XL-WA paper, Table 2](https://iris.cnr.it/retrieve/102935e2-dea3-4598-9f63-fdb5cec8061a/Martelli_XL-WA_2023.pdf)). Link F1 on general-domain sentences is a different quantity from this app's accuracy at picking the correct owner for every token. It neither predicts that accuracy nor bounds it. Round 1 also cited a GALE Arabic–English figure ("AER 27.8"), attributed to arXiv 2207.05737. That paper is actually Shijie Wu's thesis, and the figure could not be verified, so it has been **removed**. So has round 1's claim that general-domain error rates are "upper bounds" on the app's error ([arXiv 2207.05737](https://arxiv.org/abs/2207.05737); [Opus review §2](<../research_notes/Systematic Quran mapping accuracy/opus_review.md>)). The LLM evidence points the same way. In an Ancient Greek study, Claude 3.5 Sonnet got 64 of 138 gold alignments wrong or partial, mostly function words. The authors warn that fine-tuning on that silver data perpetuates its errors, and they note that correcting drafts is cheaper than writing alignments from scratch ([Nadel & Crane 2025](https://doi.org/10.1017/chr.2025.10010)).

The existing resources also fall short. Quran Foundation's verse API returns word-by-word glosses and whole-verse translations as separate records, with no prose offsets or span links ([QF API](https://api-docs.quran.foundation/docs/content_apis_versioned/4.0.0/verses-by-verse-key/)). QAC offers manually annotated morphology, treebank and ontology data, but no prose alignment ([QAC](https://corpus.quran.com/documentation/)). The quran.ai tools ground morphology, translation and tafsir and have no alignment endpoint ([quran.ai guide](https://github.com/quran/mcp.quran.ai/blob/master/skills/quran.ai-MCP-usage/SKILL.md)). They also share QF data upstream with the app, so agreement with quran.ai is **grounding, not independent corroboration**. The practical role for models is therefore **one structured LLM drafter, with the current mapper as a baseline**, producing proposals for qualified human review. No mandatory ensemble, no training run and no new framework is needed.

## A ledger separates meaning, head, onset and completion

The proposed offline ledger stores one row per printed token: offsets, a short token hash, unit ID, kind (lexical / attached grammar / supplied grammar / addition), status and review provenance. A separate unit table records the contributing reader slots (and optionally morphemes), a relation class, and the heads. The ledger must keep four things apart that the current single `position` integer merges together ([EnglishWordAlignment.kt](../app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt)):

- **Semantic relation:** which Arabic slots the English meaning comes from.
- **Tap/repeat head:** the slot that taps and repeat tint point to.
- **Reveal onset:** the earliest point at which ink may appear.
- **Reveal completion:** the point at which the whole meaning must be inked.

Crossing links remain legitimate semantics. For example, 24:31 "husbands' fathers" maps husbands'→28 and fathers→27. The existing suffix-minimum `washSpans` reduction then turns these crossing links into a continuous prefix, as a separate presentation step. Monotonicity should never be written into the semantic labels themselves.

The round-2 rubric needs coordinator corrections. Its rule R6 says that all tokens of a meaning unit share one ink. That rule applies **only when a single Arabic word expands into several English words**: "ever after"→*abadan*, "turn about"→*tataqallabu*, "clear evidence"→*bayyināt*. It must not merge phrases that have several Arabic contributors. In 24:37, "performance"→10 and "prayer"→11 **keep separate constituent anchors**, even though the reviewer groups them as one phrase. A unit with several Arabic contributors needs **contribution-aware subunits and completion portion by portion**, not an earliest-of-all merge that inks "prayer" before *al-ṣalāt* is heard. R8 says ink starts at the earliest contributor. It applies only to discontinuous units, and even there *lā … illā*→"only" is an **open policy question for the owner**, not an established universal meaning onset.

Additions and supplied grammar attach to the unit they modify or explain. A truly null-linked addition must not be given an invented source. Round 2 proposed that it inherit the next unit's ink. That leaves the **terminal case unresolved**: a null addition at the end of a verse has no next unit. The ledger should flag such tokens and leave them unresolved until the owner decides.

Runtime stays thin. The compiler emits, per verse, a manifest digest, one ink byte per token, and sparse head entries only where head ≠ ink. No text of any kind is shipped. The lookup in `EnglishVerseAlignments.alignment` builds `EnglishAlignment` from that row, and an optional `head` field is read only by taps and repeat tint ([Opus review §3](<../research_notes/Systematic Quran mapping accuracy/opus_review.md>)). Smooth clocks, the soft leading edge, first-wash repeat behaviour, highwater and back-page ink are all unchanged. Integration tests must still exercise the **head/ink split** directly, because a tap or repeat that reads `head` while the wash reads `position` is a new seam. The ledger can carry rich metadata offline. **Its file format should be chosen after the pilot**, once the head≠ink share and the many-to-one share have been measured.

## Bind the ledger to manifests, modes and trust

A label is only valid against the exact text it was made for. Prose-mode text is `EnglishTypography.typeset(translation_en, quoteContinues)`, and the quote state depends on the **neighbouring verse** ([QuranRepository.kt](../app/src/main/java/com/beautifulquran/data/QuranRepository.kt)). The binding must therefore hash the typeset printed string, the Kotlin token boundaries and the ordered Arabic reader-slot surfaces. Raw DB text is not enough.

The current audit certificate hashes only the corpus TSV, the mapper source and the reference file. It also accepts any subset of verses as its own population ([audit_english_alignment.py](../tools/audit_english_alignment.py)). A release mode needs four things:

- The **approved 6,236-key manifest**, with count checks of 6,236 verses, 77,429 slots and 158,066 tokens.
- **Behaviour digests for every component**: typography, Arabic normalisation, tokenizer, crosswalk, morphology, ledger and rubric.
- Rejection of duplicate verse keys.
- A rule that a subset run can never pass release.

Source trust comes before any morphology-based evidence. QAC ingestion joins entries by raw position into space-split slots and only logs count mismatches ([build_db.py](../tools/build_db.py)). Equal counts can still hide compensating split/merge errors. The fix is a crosswalk from source to reader slots, built on actual surface sequences, that also checks sequences when counts match. Groups that have not been reviewed stay `quarantined` and cannot supply verified heads ([validation notes](<../research_notes/Systematic Quran mapping accuracy/validation_and_data.md>)).

Modes are separate corpora. Prose mode reads bundled DB text. Word-card mode reads QF cards from the live runtime cache ([QF_CONTENT_SYNC.md](../docs/QF_CONTENT_SYNC.md)) and keeps its own corpus ID and its own claim. A verse whose digest fails falls back to the mapper with `MAPPER_FALLBACK` provenance, and **fallback verses are excluded from any accuracy claim**. CI should fail if any bundled verse would fall back. Round 1 claimed that a fresh install with no QF cache would gain correct prose ink. That claim should be dropped: if the launch gate hides the reader until content syncs, the user never sees that state. The right to distribute metadata derived with the help of QF cards is **unknown**, and settling it is the owner's decision. This report proposes no approval flow.

## A blind token sample can bound 99.9%; agreement cannot

Statuses must stay separate:

| Status | What it means |
|---|---|
| `consensus_silver` | The drafter and the mapper agree; no review |
| `disputed` | They disagree; no review |
| `reviewed` | A qualified human checked or corrected the label after seeing the draft (anchoring risk) |
| `blind_gold` | Independent blind labels plus adjudication |
| `unresolved` | No decision reached; always counted as an error |

Agreement between generators **only sets the review order**. The mapper and a gloss matcher read the same cards, so a copied card fools both.

Statistics follow from that. The old conservative verified-fraction gate (`100 × correct / population`) stays as a separate coverage figure. It is not a confidence interval ([audit_english_alignment.py](../tools/audit_english_alignment.py)). The certification design works as follows. Draw a predeclared, uniform random sample of **distinct tokens from all 158,066**, covering grammar and additions too. The test is set against **K = 159**, the smallest error count that would put the corpus below 99.9% (`floor(N × 0.001) + 1`). Zero errors in **2,949** sampled tokens then rejects K at a probability of 0.0499887. That gives a one-sided 95% lower bound of ≥99.9% under the exact hypergeometric model ([NIST hypergeometric](https://itl.nist.gov/div898/software/dataplot/refman2/ch8/hypppf.pdf); [NIST exact binomial](https://itl.nist.gov/div898/software/dataplot/refman2/auxillar/exacbino.htm)).

The Opus reviewer computed an alternative: **6,173 tokens, accepted with ≤2 errors**. It is a computation only; no sample has been run. Its pass rate is conditional on how many errors the map really contains. At 30 true errors it passes about 89% of the time, against about 57% for the zero-error plan ([Opus review §5](<../research_notes/Systematic Quran mapping accuracy/opus_review.md>)). The reviewer said the map "must be ~99.97%" correct. That is a **planning target for statistical power**, not a mathematical necessity. In the same way, exhaustive human review of every token is the recommended way to reduce risk, not the only possible proof.

Some shortcuts are invalid:

- Round 1's Poisson bound on about 7,600 tokens clustered within verses.
- A verse bootstrap of a zero-error sample. Every resample also has zero errors, so the interval collapses to nothing.
- Repeating the draw until one sample passes.

The sample must also be **blind, independent gold**, labelled by people who never reviewed those verses. Unresolved tokens count as errors, and the holdout must stay untouched by tuning ([validation notes](<../research_notes/Systematic Quran mapping accuracy/validation_and_data.md>); [Census E1](https://www.census.gov/about/policies/quality/standards/standarde1.html)). A passing result supports one narrow claim: for this manifest, edition and declared obligations, the bound is ≥99.9%. It says nothing about other translations, other cache revisions or reciter timing.

| Phase | Work | Gate |
|---|---|---|
| 0 Automatable now | Input trust/provenance exporter, surface crosswalk, manifest-mode certificate, rubric v0 with worked examples | Manifest reproduces 6,236/77,429/158,066; zero unexplained crosswalk groups |
| 1 Dev pilot | 50–100 verses, labelled blind by two qualified readers plus an adjudicator; drafter and mapper scored | Measure cost, agreement, unresolved share, head≠ink share; go/no-go |
| 2 Corpus | Draft every verse, qualified review of all tokens, thin compiled lookup integrated | Only `reviewed`/`blind_gold` verses ship; zero-fallback CI |
| 3 Certification | Freeze; new blind annotators; predeclared token sample | Pass or report failure; a new preregistered sample for any retry |
| 4 Cleanup | Remove runtime heuristic tables | Full valid coverage; `git log -S` history read for every guard first |

Drafter accuracy, reviewer time and cost, reviewer availability, inter-annotator agreement and distribution rights are all **unmeasured or unknown**. Phase 1 exists to measure the first four of them.

## Conclusion

The real change is in the unit of work. Today the work is a user-reported verse plus a global rule that can silently move other verses. Under the proposal it becomes a ledger row that is local, reviewable and bound to a digest, so fixes stop interacting and every remaining uncertainty shows up in the denominator. Two decisions belong to the owner and gate everything downstream: the onset policy for discontinuous and multi-contributor units, and the right to distribute derived metadata. The pilot then decides whether a claim backed by human labels is affordable. If it is not, the honest fallback is a narrower claim, for example covering wash obligations only. It is not a percentage taken from model agreement.
