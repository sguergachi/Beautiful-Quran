# Systematic Quran mapping validation and data

## What should the benchmark measure, and is a single Arabic owner well-defined?

### Takeaway

The project needs an explicit meaning-and-interaction contract before another accuracy target. A single Arabic owner can be a useful *tap head policy*, but it is not a complete linguistic representation of every English word: translation can expand one Arabic word, combine several words, supply English grammar, or explain something not explicitly said.

### Cited Findings

- QAC documents Arabic words containing conjunction, stem and attached subject/object pronouns, and contractions containing two stems. It distinguishes roots from lemmas and identifies English indefinite articles through Arabic inflection rather than a separate source article. Morphological segmentation is richer than one source lexical head. — [QAC morphological features](https://corpus.quran.com/documentation/morphologicalfeatures.jsp)
- The foundational word-alignment evaluation used human annotation, Sure/Probable alignment sets, NULL links, and, for one language pair, two annotators plus third-person arbitration. Alignment evaluation distinguished coverage including NULL from coverage excluding NULL. — [Mihalcea and Pedersen, 2003, §§2.3.1–3](https://aclanthology.org/W03-0301.pdf)
- Beautiful Quran currently maps every output span to one Arabic position. It derives the continuous wash by scanning semantic spans backwards and taking the minimum Arabic position, so earlier English words inherit an earlier wash trigger when a later printed meaning is spoken. This deliberately separates semantic interaction ownership from monotone display progress. — [EnglishWordAlignment.kt, EnglishAlignment and align](../../app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt)
- The mapper explicitly says synonyms, ambiguous repetitions and translator additions use an approximate share. Its return value does not preserve which path assigned an owner or whether that assignment was uncertain. `ownerAt` can return null for grammar/additions while `alignment.positionAt` still supplies a total integer owner. — [EnglishWordAlignment.kt, documentation, align and ownerAt](../../app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt)
- The coverage document candidly distinguishes selected model/source-supported review from an independent benchmark. It describes failures spanning gendered expansions, multiword meanings and grammar tails, including “ever after,” and documents that content-only accounting previously hid a real grammar-word failure. — [ENGLISH_ALIGNMENT_COVERAGE.md](../../docs/ENGLISH_ALIGNMENT_COVERAGE.md)

### Inferences

**Recommended annotation representation, offline; keep the runtime small.** For each frozen verse and exact English character span, record `meaning_id`, source Arabic word-position set (optionally source morphemes), relationship class, sure versus acceptable links, preferred interaction head or approved head set, reveal-start/reveal-complete source positions where meaningful, evidence citations, uncertainty, and reviewer/adjudication provenance. Collapse this richer reference into the existing compact runtime spans after decisions are reviewed. The reference need not become a graph framework in the app.

Use these relationship classes:

| Class | Annotation instruction | Product policy requiring agreement |
|---|---|---|
| Direct lexical | Link the actual Arabic head in its clause and sense; do not infer from matching English gloss alone. | Exact tap/repeat head normally available. |
| One Arabic meaning → several English words | Mark the whole printed realization, including necessary qualifiers and function words. “Clear evidence” and “ever after” must each receive complete-span treatment when one source meaning supplies them. | Do not leave the last printed word behind; all taps may share that head when linguistically supported. |
| Several Arabic words → one English meaning | Link all contributors; distinguish lexical head from grammatical participants. | Choose and document a preferred tap head, while reveal start/completion may refer to different positions. |
| Explicit grammar / attached pronoun | Identify the separate particle or the morpheme inside the containing Arabic word. | Do not blanket-exclude English stop words from the benchmark. |
| English-required grammar | Mark as supplied grammar and attach to its syntactic meaning span, rather than inventing a direct spoken-word correspondence. | A contextual tap target is an explicit policy decision, not a claim that this separate English word was spoken. |
| Translator explanation | Mark the explaining phrase and the source meaning/phrase it clarifies, or NULL if no determinate local link exists. | Decide how taps and repeat tint treat explanations; the forward prefix still includes every printed word. |
| Ellipsis / referent / alternate sense | Record uncertainty and evidence; adjudicate with Arabic context and suitable tafsir. | Accept a declared set of legitimate heads or leave exact lexical ownership unresolved. |

**Annotation procedure.** Recruit two Arabic–English readers with Classical/Quranic Arabic competence to label independently, blind to the mapper's owner predictions. Give them the exact published English, actual numbered app Arabic slots, source segmentation, adjacent verse context, translation edition ID, and morphology as evidence. A third qualified reader adjudicates disagreements. Pilot the rubric before production annotation and keep every disagreement, not just the final winner. Model proposals, Opus critique, QAC cues and Quran.ai retrieval can assist discovery and explanation; none should silently become independent human gold.

Adjudication rules should explicitly address clauses, repeated identical lexemes, names, nominalization versus verb, attached pronouns, bracketed explanations, ambiguous heads, and whole-phrase boundaries. Preserve Arabic vocalization. A root is evidence for a family of meanings, not proof that two occurrences have the same sense. Multiple translations clarify evidence; the benchmark still judges alignment to the exact printed edition.

**Separate scores.** Report (1) lexical/span link correctness, (2) preferred/acceptable tap-head correctness, (3) repeat tint association, and (4) forward-wash obligations. A continuous prefix can force a not-yet-spoken English word to appear early because English order differs. Therefore “every highlighted English word has already been spoken” is incompatible with the product's continuous-prefix rule in reordered sentences. Score “no heard meaning is delayed and no intervening word is skipped,” with unavoidable early exposure distinguished from unsupported early exposure. Test reveal completion as well as reveal onset; prefix tiling alone cannot detect a chopped meaning phrase.

Keep the existing literal overlap score and QAC candidate checks as discovery diagnostics. Do not call a high score on the uniquely matchable subset accuracy over all printed English.

**Failure taxonomy for the review queue and regressions:** `source_slot_mismatch`, `text_or_token_boundary_drift`, `shared_gloss_copy`, `repetition_or_clause_collision`, `sense_or_name_collision`, `nominalization_or_synonym_gap`, `one_to_many_phrase_split`, `many_to_one_head_ambiguity`, `grammar_or_attached_pronoun`, `translator_expansion_or_NULL`, `forced_owner_on_unresolved_text`, `display_prefix_or_completion`, and `seek_repeat_carry_generation`. Record cause and visible symptom separately; several symptoms can share one cause.

### Gaps

- No full independent bilingual alignment reference exists in the inspected repository; the 160 selected labels are model-adjudicated and source-supported. Their presence does not answer whether the remaining words are right. — [reviewed-owner file header](../../docs/english-alignment-reviewed-owners.tsv)
- Exact tap behavior on an explanation with no single source head, and the reveal timing of a genuinely many-to-many meaning, are product requirements that need a written policy. Existing integer owners cannot resolve that ambiguity by themselves. — [current output types](../../app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt)
- Qualified annotator availability, budget, per-verse annotation time and inter-annotator agreement are unknown. Time/cost estimates should follow a timed pilot, not invented productivity assumptions.

## What evidence would support a scoped 99.9% claim?

### Takeaway

There are two distinct defensible claims: verified correctness on an adjudicated finite corpus, or a statistically supported estimate for a predeclared population and sampling design. Existing full-population coverage scoring is conservative and useful, but it is not a statistical confidence interval; selected regressions and millions of playback queries cannot substitute for representative semantic observations.

### Cited Findings

- NIST gives exact binomial confidence limits and distinguishes one-sided limits; the symmetric normal approximation can be inaccurate with few failures. — [NIST exact binomial](https://itl.nist.gov/div898/software/dataplot/refman2/auxillar/exacbino.htm)
- NIST defines the hypergeometric model for a random sample taken without replacement from a finite population with a fixed number of marked units. — [NIST hypergeometric probability functions](https://itl.nist.gov/div898/software/dataplot/refman2/ch8/hypppf.pdf)
- Census requires analyses and uncertainty measures to account for unequal selection probabilities, stratification and clustering, and requires assumptions, plans and reproducible computations to be documented. — [Census Statistical Quality Standard E1, E1–2 through E1–6](https://www.census.gov/about/policies/quality/standards/standarde1.html)
- The current audit validates labels against the exact token text and offset, rejects duplicate labels and invalid owners, includes grammar/additions when a semantic reference or target is supplied, and treats unreviewed tokens as not verified. Its `verified_percent_of_full_population` is `100 × correct / population`, not a confidence bound. — [audit_english_alignment.py, lines 99–127 and 248–283](../../tools/audit_english_alignment.py)
- The timing audit consumes mapper-generated ownership and derives the expected English prefix from those same semantic spans. Its independent linear oracle tests timing lookup, retained ink, repeats and handoff against segments; the English portion tests consistency of the mapper's own outputs, not external semantic truth. — [audit_reader_sync.py, probe](../../tools/audit_reader_sync.py)
- On the supplied private frozen corpus I independently counted 6,236 verses, 77,429 Arabic gloss slots and 158,066 printed English tokens. SHA256 is `bc2f381494642ae7ed8950b3b77117f6a5d617becc11b51fd12516916f390449`. ASCII and Unicode-letter token counts agree here; the published text has no non-ASCII letters or astral codepoints. The corpus and its full text remain outside Git. — [private frozen corpus](/tmp/bq-english-mapping/runtime-corpus.tsv)

### Inferences

**Finite reference target.** With `N = 158,066`, 99.9% finite-corpus correctness permits at most 158 erroneous token decisions; 99.99% permits at most 15. The current conservative verified-fraction gate can reach 99.9% only with at least 157,908 correctly reviewed decisions, leaving at most 158 incorrect or unreviewed decisions. This is a coverage guarantee conditional on trustworthy adjudication, not a claim of 95% statistical confidence. A sampled benchmark should have a separate command, report and threshold; do not weaken this existing gate and rename a sample percentage “full verified coverage.”

**Zero-error sample computations**, derived from the NIST models, not observed semantic accuracy:

| Desired correctness | IID binomial, one-sided 95%, zero errors | SRS without replacement from N = 158,066, zero errors |
|---|---:|---:|
| 99% | 299 | 298 |
| 99.9% | 2,995 | 2,949 |
| 99.99% | 29,956 | 26,989 |

For IID Bernoulli observations with no failures, the exact lower success bound is `0.05^(1/n)`, so `n = ceil(log(0.05) / log(target))`. For the fixed corpus, test the first disallowed defect count `K = floor(N × (1-target)) + 1`; the zero-error probability is `C(N-K,n) / C(N,n)`. The finite-population 99.9% calculation rejects `K = 159` at `n = 2,949`, with probability `0.0499887032`. The 99.99% calculation rejects `K = 16` at `n = 26,989`, with probability `0.0499966130`. I computed these using a stable sum of `log1p(-K/(N-i))` and integer binary search. Nonzero errors require the appropriate exact CDF inversion and a larger sample; none of these zero-error numbers applies automatically after finding failures.

**Crucial cluster distinction.** If the sample is a uniform random set of distinct tokens from the fixed full corpus, the hypergeometric calculation remains valid even when deterministic errors occur in phrase clusters: randomness is in the token draw, not an assumed independent generative process. Annotators may read the complete verse as context, but only sampled tokens count toward that token probability sample. If instead whole verses are sampled and every token inside them is counted, observations are clustered and long verses/unequal inclusion probabilities matter; use a design-aware estimator/interval. Counting 100 words in one reviewed clause as 100 IID trials is not valid. Reciter/frame repeats are neither distinct token decisions nor new semantic evidence.

**Feasible benchmark design.**

1. Pilot approximately 50–100 varied verses to settle annotation categories, inter-reviewer disagreement and effort. Treat this as development evidence, not a certification sample.
2. Maintain a challenge set covering every failure family and all identified mismatched source slots. Split development versus held-out verses/near-duplicate phrase families before tuning. Report challenge performance by category; it is intentionally not representative and cannot estimate global accuracy.
3. Freeze the candidate mapper, approved edition, Arabic slots and decision policy. Draw a deterministic, seeded simple random token sample from the entire approved 158,066-token population for a final independent blind assessment. Roughly 3,000 zero-error sampled decisions can support a narrowly scoped 99.9% finite-corpus statement; if the sample fails, report failure, improve the class in development and use a new preplanned validation sample. Avoid repeatedly trying samples until one passes. A statistician should define repeated-assessment error control if many candidates are tested.
4. Independently adjudicate every sampled token, including grammar and additions. Context may be whole verse. Mark unresolved adjudication as unresolved; do not drop it from the denominator to improve the score. Report both strict unresolved-as-not-verified and linguistically acceptable-set results.
5. Phrase the final claim precisely: “For this frozen edition/corpus and declared token-decision policy, the one-sided 95% lower bound is at least 99.9%.” It is not accuracy for unseen translations, future QF revisions, every reciter's acoustic boundaries or all UI sequences.

Stratified random sampling can oversample the fallback/expansion/long-verse/repeated-head classes for better diagnosis, but retain known inclusion probabilities and weights and compute design-aware uncertainty. Keep the much simpler uniform final token sample separate from a larger actively selected development queue. Stratify reports by lexical/grammar/addition, phrase size, ambiguity, clause distance and source trust, even if only the global probability sample has enough power for a 99.9% bound.

For a single combined 99.9% claim, define success as *all declared token obligations* being satisfied (acceptable head, whole-meaning attachment and repeat association). Alternatively declare separate targets and account for multiple comparisons. Mechanically exhaustive continuous-prefix checks can remain a separate zero-regression gate.

**Disagreements with Opus round 1 that the separate review should resolve.** Its proposed 300 simple-random verses / approximately 7,600 words do not justify treating 7,600 words as IID events or using `3 / 7,600` as the upper error bound. A verse cluster can fail as a unit. A verse bootstrap also cannot estimate unobserved rare error clusters from a zero-error sample: every resample of those observed verses has zero errors, so the naive interval degenerates to zero. Merely increasing from approximately 120 to 300 verses does not supply a defensible effective sample size. Use a token SRS and exact finite-population bound for the token claim, or a genuinely design-aware cluster inference with an explicit rare-event method and a justified sampling plan. The 300-verse exercise remains useful as a pilot and a source of annotation-cost estimates. — [Opus proposal Q5](opus_proposal.md)

Do not exclude the five known problematic verses or the 160 selected labels from a sample and still call it the full-population headline. That changes the population and removes known risk. An honest alternative is to define the previously exposed population as a separate certainty stratum, independently review it, then sample the rest with known inclusion probabilities and combine the results. A generalization-only holdout can exclude development exposures, but its claim is then explicitly about the held-out population. Also, reviewing predictions for most of the reference introduces anchoring risk; a small blind subset can diagnose that risk, not retroactively make the remaining gold independent. — [Opus sampling/annotation/independence recipe](opus_proposal.md)

Candidate agreement is a prioritization signal, not automatic semantic certification. G1, English-paraphrase G2 and model G3 may share the same gloss, segmentation assumptions and inferred head, so three agreements can be one repeated error. If the product chooses to ship consensus-generated maps, mark them as generated/unreviewed, retain their full population and assess the consensus stratum with independently blind labels; promote an acceptance policy only after measuring its error/coverage tradeoff on held-out evidence. Consensus must not populate the independent-gold ledger. Likewise, general-domain alignment error rates are not mathematical upper bounds on this Quran-specific task; they establish neither its difficulty nor its likely error rate. — [Opus acceptance policy, Q2 and Q6](opus_proposal.md)

### Gaps

- I did not assess actual global semantic correctness. These sample sizes are planning calculations conditional on the specified gold labels and random-sampling design.
- Adjudication itself can have systematic errors that sampling cannot remove. Agreement between QF and Quran.ai is not independent evidence because the project documents shared upstream data. — [coverage limitations](../../docs/ENGLISH_ALIGNMENT_COVERAGE.md)
- An acceptable-head policy can make a benchmark more linguistically honest, but must not become a permissive way to accept unrelated owners. Keep sure links, approved alternatives and unresolved cases distinct.

## How can the entire unknown population be found and quality sustained without heavy runtime machinery?

### Takeaway

Audit-only provenance plus a versioned review artifact would expose the full uncertainty surface and turn isolated failures into class fixes. The current frozen corpus has a substantial interpolation population, and source-segmentation trust must be repaired before morphology can safely certify those cases.

### Cited Findings

- I copied the current mapper into private `/tmp` scratch, instrumented its *initial owner-assignment path*, compiled it with the normal pure dependencies, and ran it over the frozen published corpus plus read-only bundled Arabic/lemma data. Production code/data/git were not changed. Mapper SHA256 was `efcd59484d1f40647a76b425bc233879ab77b77628f1cb64cf182236a32e3fe5`. Printed-token weighting produced the following counts. — [private diagnostic summary](/tmp/bq-validation-diagnostics/summary.json); [source assignment logic](../../app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt)

| Initial assignment path | Printed English tokens |
|---|---:|
| Grammar claimed by alignment | 73,428 |
| Content selected by verification rules | 45,817 |
| Reflexive grammar rule | 199 |
| Content fallback/interpolation | 10,221 |
| Grammar fallback/interpolation | 20,054 |
| Addition fallback/interpolation | 8,347 |
| Total | 158,066 |

- Thus 38,622 tokens (24.4341%) initially use fallback/interpolation. Subsequent sentence/grammar attachment changes 1,343 initial grammar fallbacks and 1,631 claimed grammar owners. These are implementation-path counts, **not** error counts, unresolved-gold counts or probabilities of correctness; even a claimed or “verified” path can be semantically wrong. — [private diagnostic summary](/tmp/bq-validation-diagnostics/summary.json)
- Reproduction is preserved privately as [reproduce.py](/tmp/bq-validation-diagnostics/reproduce.py), frozen component copies in [snapshot](/tmp/bq-validation-diagnostics/snapshot), frozen five-column input in [input.tsv](/tmp/bq-validation-diagnostics/input.tsv), component/input/toolchain hashes in [snapshot-manifest.json](/tmp/bq-validation-diagnostics/snapshot-manifest.json), generated-output hashes in [diagnostic-manifest.json](/tmp/bq-validation-diagnostics/diagnostic-manifest.json), and all 158,066 token rows in [token-provenance.tsv](/tmp/bq-validation-diagnostics/token-provenance.tsv). Run `python3 /tmp/bq-validation-diagnostics/reproduce.py` to recompile the copied sources and reproduce the tally. Each token has exactly one initial category; the changed-owner flag is cross-cutting and must not be added as another category. The TSV records the final owner *after* both sentence-tail and grammar-prefix reattachment, not an earlier approximation. None of these private files belongs in Git. — [reproduction script](/tmp/bq-validation-diagnostics/reproduce.py)
- QAC ingestion reads source `(surah,ayah,position)` directly into the app's space-split slots, selects a stem/root-bearing segment, omits positions absent in QAC and logs ten count mismatches. It does not align source surfaces to reader slots to correct an interior shift. `QuranRepository` then joins the lemma by this position. The Arabic-candidate audit excludes count-mismatched verses, but production mapper input still receives the joined morphology. — [load_qac_morphology](../../tools/build_db.py); [QuranRepository.surahContent](../../app/src/main/java/com/beautifulquran/data/QuranRepository.kt); [audit count guard](../../tools/audit_english_alignment.py)
- The current audit hashes only the corpus TSV, `EnglishWordAlignment.kt`, and supplied reference TSV. The compiled behavior additionally depends on `EnglishTypography.kt`, `ArabicNormalize.kt`, models, bundled morphology and the compiler/runtime, none of which is included in this certificate's behavior fingerprint. — [audit input/probe/certificate construction](../../tools/audit_english_alignment.py)
- The audit builds dictionaries from verse keys and token locations and does not require an approved 6,236-unique-verse manifest. A different/subset input receives its own denominator and corpus hash. This is useful for diagnostic subsets but insufficient to label any passing invocation full-Quran certification. — [audit row/population/certificate construction](../../tools/audit_english_alignment.py)
- The inspected web domain has `EnglishTypography.ts` and Arabic `HighlightEngine.ts`, but no `EnglishWordAlignment` implementation. Its verse render assembles lyricized English word cards and a separate translation paragraph; the current Kotlin ownership audit alone cannot establish browser published-prose semantic parity. — [web domain directory](../../web/src/domain); [AyahBlock.tsx](../../web/src/render/AyahBlock.tsx)

### Inferences

**Full uncertainty inventory.** Add an audit-only diagnostic result alongside the existing pure output (or a copied instrumented probe, as used here): exact token/phrase offsets, candidate owners, selected owner, direct/phrase/lemma/grammar/interpolation reason, candidate score margin, clause, evidence trust, later attachment changes and ambiguity flags. Export one row per *all printed tokens*. Join with the reference by text hash and offsets to distinguish:

- independently reviewed correct/incorrect;
- model/source-supported but not independently reviewed;
- candidate-supported but unreviewed;
- interpolated/unresolved;
- source-untrusted or manifest-invalid.

Never equate an integer owner or an internal `verified` variable with external semantic certainty. The initial fallback path can seed the review queue, but review non-fallback high-risk cases too: false anchors are a major error source. Publish aggregated counts and lightweight evidence/IDs; keep copyrighted full source text and runtime packs private.

Rank reviews by untrusted segmentation first, competing/weak anchors, content interpolation, shared copied cards, long expansion phrases, repeated heads across clauses, meaning splits, grammar tails/additions, and observed owner changes. Group by lemma + relationship/failure class + local syntax; review multiple distinct contexts before promoting a general rule. Sample a random slice of every supposedly safe class to check false confidence. Do not tune the final held-out sample through this queue.

**Source trust before semantic inference.** Build a source-to-reader segmentation crosswalk in the data pipeline, grounded in actual normalized Arabic surfaces and original QAC segment forms. Record source IDs, output slot sets and exceptional merges/splits. Check sequence/surface agreement even when total counts match; compensating split/merge errors can preserve the count. Preserve all morphological segments needed for grammar/pronouns in the offline reference. Until reviewed, mark mismatched slots untrusted rather than treating shifted lemmas as lexical truth. This is data provenance work, not runtime data repair. Any shipped DB-content change must follow the existing filename/fingerprint version-bump invariant.

**Small runtime option.** For a fixed edition, a generated compact file of approved character spans and interaction/reveal owners can be shared between Android and web. Keep richer evidence, candidate alternatives and adjudication histories in tools/reference files. The runtime merely loads immutable spans and derives the same monotone prefix. This does not require an NLP model, new dependency framework or per-verse conditionals. A generated artifact still requires corpus-wide review/validation; generation alone does not make its values correct.

**Versioning and invalidation.** One manifest should identify edition/resource ID and revision, actual printed text hash per verse, Arabic slot/surface hash, segmentation crosswalk hash, morphology DB/source digest, tokenizer/offset convention, mapper and every relevant dependency digest, output artifact digest, review-rubric version, review provenance, sample seed/design and benchmark split. Declare UTF-16 versus Unicode-codepoint offsets and have Android, JavaScript and Python convert explicitly. The present corpus avoids non-ASCII letters/astral characters; that is not a guarantee for later editions or modern transliterations. On change, produce a semantic diff and invalidate affected labels/approvals; a certificate must reject the wrong approved population or manifest rather than merely emit a different hash.

**Concrete closed-corpus manifest contract.** A small JSON header plus one JSONL record per verse suffices; no service or runtime framework is needed. The canonical serializer and its version must be fixed before hashing. The header requires `schema_version`, `corpus_id`, `approved_manifest_sha256`, `verse_key_set_sha256`, `verse_count`, `arabic_word_count`, `printed_token_count`, `translation_edition_id`, `translation_resource_revision`, `text_pipeline_component_sha256`, `arabic_source_revision`, `morphology_source_sha256`, `bundled_db_sha256`, `crosswalk_sha256`, `tokenizer_version`, `offset_unit`, and `normalization_version`. For this frozen published corpus the expected counts are exactly 6,236 / 77,429 / 158,066. Word-card mode is a separate corpus ID with its own text and token counts.

Each verse record requires `verse_key`, `printed_text_sha256`, `gloss_sequence_sha256`, `arabic_reader_slot_sequence_sha256`, `source_record_ids`, `arabic_word_count`, `printed_token_count`, `token_boundary_sha256`, `morphology_slot_sequence_sha256`, and `segmentation_trust`. The Arabic slot digest covers `(1-based position, exact surface)` in order; the morphology digest covers positional features and trust, not just the multiset of lemmas. Hash the actual typography output shown to the reader, rather than raw translation HTML. Validate uniqueness before constructing dictionaries; compare verse keys to an independently pinned canonical key list; check all header sums against records; then compare the whole manifest to the preapproved digest. A diagnostic subset can still run, but must identify `scope = subset` and cannot pass the release-certification gate.

Keep the behavior/output approval separate: `mapper_components_sha256`, `generated_alignment_sha256`, `reference_sha256`, `rubric_version`, `reviewer_provenance`, `sampling_design`, `sample_seed`, `sample_units_sha256`, `development_exposure_manifest_sha256`, `evaluation_result`, and `confidence_method`. A reference row must bind to the verse's printed-text and Arabic-slot digests; merely retaining a comment with an old corpus hash is insufficient. A change to text, segmentation, edition, normalization, typography or mapper dependencies triggers a diff and invalidates the matching approvals. Changed timing packs do not create new semantic observations but do invalidate timing-specific verification.

**Concrete segmentation crosswalk contract.** One record maps source identity to reader identity: `source_system`, `source_revision_sha256`, `verse_key`, `source_word_positions`, `source_segment_ids`, `source_surface_sequence_sha256`, `reader_word_positions`, `reader_surface_sequence_sha256`, `relation` (`one_to_one`, `source_split`, `source_merge`, `complex`, `excluded_nonword`), `normalization_version`, `evidence`, `trust`, and `review_provenance`. Preserve all source morpheme IDs within their source words; head selection is a separate semantic operation. Multiple source segments can belong to one timed reader word without granting each segment an independent timing.

The validator must account for every source word and every reader word exactly once at the crosswalk-group level, permitting declared split/merge groups rather than an arbitrary numeric clamp. Enforce verse-key equality, ordered position coverage, valid slot ranges, exact surface digests and equivalence under the approved limited orthographic normalization. For an excluded nonword (for example a verse marker), require its explicit source type and reason. Equal total counts do not bypass sequence verification. A non-equivalent split/merge cannot be auto-approved merely because concatenation lengths match; queue it for linguistic/source review. Unreviewed groups have `trust = quarantined`, cannot supply “verified” lexical heads, and remain visible in the uncertainty denominator. Approved morphology distribution to reader slots must follow this crosswalk and have a regression for every merge/split class. This avoids putting data-repair heuristics in the runtime.

**Quality gates, staged and separate:**

1. **Input/artifact integrity:** approved verse coverage and uniqueness, valid words/slots, trusted source crosswalk, no stale text offsets, expected translation and runtime-pack revisions, deterministic serialization/digests. Duplicate verse keys and corpus subsets must fail the release-certification mode.
2. **Mechanical exhaustive:** spans tile all English, offsets match actual printed tokens, owners within Arabic range, complete meanings wash no later than their approved trigger, monotone continuous prefix with no gaps, exact final completion. Keep timing physics/lookup/repeat and unavailable rows explicit.
3. **Semantic reference:** blind independent sampled labels and class challenge reference; exact/acceptable interaction owner, whole-meaning attachment and repeat association. Existing selected fixtures remain valuable regression tests, with their selection/provenance clearly stated.
4. **Change gate:** diff all changed owner/reveal spans against baseline, group by the producing rule, and require review or explicit uncertainty for affected populations. Refuse invented improvement percentages from the same source used to generate predictions.
5. **Cross-client contract:** feed the same licensed/private fixture inputs and shared generated alignment artifact to Android and web. Compare token ownership and prefix state byte-for-byte or by canonical JSON at every Arabic step; client rendering may differ without semantic drift. Include both English sources, reordered phrases, expansions, grammar/additions, every eligible carried-fragment cut, seeks, paused starts, repeat chains, stale focus/generation and playlist changes. R8 release checks exercise production evidence wiring and representative interaction paths.

Android and web parity must be specified by feature: source-relative lyricized cards and mapped published prose are different inputs, not interchangeable gold. Separate semantic correctness from actual reciter/audio alignment. App reciter 1 is Alafasy/QF 7; app reciter 7 is Hani/QF 5, as documented by the project; every timing fixture must carry both identities and audio clock convention. — [reciter identity warning](../../docs/ENGLISH_ALIGNMENT_COVERAGE.md)

**Practical delivery sequence:** settle the rubric and pilot; inventory/provenance plus source crosswalk; evaluate baseline honestly; fix families with development evidence and compact generated alignments; freeze and conduct an independent probability-sample assessment; add the scoped claim only if it passes; sustain integrity/semantic-diff/client gates with future revisions. This converts the uncertainty into a finite, visible workload and prevents another sequence of unmeasured local patches.

### Gaps

- Source-to-reader crosswalk correctness, the exact affected interior slots, and equal-count segmentation mismatches need their own targeted audit; I identified the positional-join risk, not a new confirmed list of incorrect production lemmas.
- The scratch counts cover the frozen published edition and current mapper, not all future editions, the complete word-card mode, or browser prose rendering. Confidence cannot be inferred from path labels.
- The generated alignment artifact's delivery/licensing/revocation arrangement must fit the existing QF content-sync contract; full copyrighted texts and private runtime caches must not be committed as a shortcut. — [project content-sync contract](../../docs/QF_CONTENT_SYNC.md)
- This research did not change production code, databases, memory, APKs or Git state; only the requested research notes and private scratch diagnostics were written.
