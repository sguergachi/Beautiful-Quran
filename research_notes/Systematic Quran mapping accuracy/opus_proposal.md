# Systematic Arabic→English highlight mapping: Opus proposal (round 1)

Scope: a proposal plus sourced analysis. No production files changed. Workspace
`test/reader-sync-coverage` @ `e6c2d9a2`. Local evidence was read from the
private corpus `/tmp/bq-english-mapping/runtime-corpus.tsv` (6,236 rows,
SHA-256 `bc2f3814…`) and `/home/sammy/.t3/artifacts/Beautiful-Quran/english-sentence-tails-e6c2d9a2/`.
Neither is committed or published. Probe scripts are in `/tmp/opus-probe/`.

**Recommendation in one paragraph.** Stop changing the runtime mapper to
improve semantics. Make the mapping a **versioned, build-time data asset per
published edition**: for every printed English token, store one *primary
Arabic owner*, a token kind (lexical / grammar / addition), and an optional
small set of *also* owners. Store no text, only owners keyed by
edition + verse + text hash. Generate it from **several independent candidate
aligners** (the current deterministic mapper, a gloss-based English↔English
matcher, and a structured-output LLM adjudicator grounded in fetched
morphology). Auto-accept a token only when the candidates agree. Send the
disagreements to bilingual human review. Measure accuracy on a
**stratified random, human-annotated verse sample** using a written guideline
with measured inter-annotator agreement. At runtime, the existing
`EnglishAlignment(spans)` → `washSpans` path consumes the bundled owners, and
the current mapper is kept only as a hash-mismatch fallback. The winner was
not assumed: the comparison below explains why each alternative alone falls
short.

---

## Q1. Why the current approach cannot reach a 99.9% claim by adding rules

### Takeaway
The runtime mapper infers owners from lexical overlap between Sahih
International prose and QF word-card glosses. About a quarter of printed
tokens have no unique lexical evidence. The unmatched content vocabulary has a
long tail of about 2,400 stems, ~1,000 of which occur only once. Each new
equivalence or phrase rule therefore fixes a class only as wide as one stem
pair. That is the "whack-a-mole" the user is objecting to.

### Cited Findings
- The runtime mapper is a weighted lexical alignment of the gloss list against the published prose, with interpolation for unmatched runs, plus hand-written tables: `EQUIVALENT_WORDS` (~110 pairs), `PHRASES` (~40), `ARABIC_HEADS` (~70 lemma→meaning), `HEAD_GLOSS_VARIANTS`, `GLOSS_SUPPLEMENTS`, and a ~150-word `GRAMMAR` stop list — `app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt:656-835` (local file).
- Its own KDoc says: "Synonyms outside the small shared vocabulary, ambiguous repetitions and translator additions still use an approximate share, not an exact timing" — `EnglishWordAlignment.kt:50-53`.
- Recent fixes were all lexical/scoped: "performance of prayer" (24:37), "commerce"→trade, "clear evidence/evidences" (24:1 and 32 verses), gender-head variants (24:2–3), and a sentence-tail grammar rule (24:4) — `docs/ENGLISH_ALIGNMENT.md` §"Iqamah ownership regression"; `docs/ENGLISH_ALIGNMENT_COVERAGE.md` (24:2, 24:1, 24:4 sections).
- The repo's own certification is RED: 160 of 158,066 printed tokens are reviewed (all correct) and 157,906 are unreviewed. `established: false` — `semantic-certification.json` in the e6c2d9a2 artifacts directory (local).
- The docs already say that literal agreement (99.895%) "does not measure this synonym miss", and that the QAC manifest/frozen fixtures are "not a random sample" — `docs/ENGLISH_ALIGNMENT.md` §"What the percentage means".
- **Local probe (my measurement; crude proxy, not the mapper's own vocabulary)** over all 6,236 verses / 158,083 tokens (my tokenizer differs from the audit's 158,066 by 17): 52.1% are function words, 22.4% are content tokens whose stem appears in exactly one gloss of the verse, 4.9% are content tokens matching several glosses, **8.0% (12,616) are content tokens with no lexical match in any gloss**, 7.3% are function words absent from every gloss, and 5.3% are bracketed additions. **4,699 of 6,236 verses (75%)** contain at least one unmatched content token. — `/tmp/opus-probe/classes.py` on the private corpus.
- The unmatched content stems are long-tailed: 2,393 distinct stems, 1,029 singletons. The top 100 cover 37.6% of unmatched tokens, the top 500 71.1%, the top 1,000 86.1% and the top 2,000 96.9% — same probe. (Some top stems, e.g. scripture/deity/satan/pharaoh/moses, are already handled by the mapper's tables. The real residual is smaller, but it has the same shape.)

### Inferences
- A rule fixes one stem pair, and even 1,000 rules would leave a double-digit share of the residual. More importantly, a rule cannot know *which* Arabic word is the owner when the evidence is synonymy, paraphrase, ellipsis or pronoun reference (e.g. 24:31 vision/gaze, 24:37 performance/iqām). These need per-token judgment, which is data, not code.
- Every rule is global and can silently change other verses. The docs repeatedly report "the other 6,235 maps are identical" as a separate check, so each patch carries corpus-wide regression risk. Per-token data changes are local by construction.
- The five user reports (24:1, 24:2, 24:4, 24:31, 24:37) are all *owner-inference* failures. None needs a richer runtime representation (see Q3), so the fix belongs in how owners are produced.

### Gaps
- My proxy does not reproduce the mapper's equivalences, so 12,616 overstates the true no-evidence count. Exporting the mapper's per-token evidence class (exact / stem / table / sentence-guard / interpolated) from `audit_english_alignment.py` would give the real figure. It was not run here (no production or tool edits in this round).

---

## Q2. Comparing the three solution families

### Takeaway
No family is sufficient alone. Deterministic code is cheap and auditable, but
lacks semantics. Neural/LLM alignment supplies semantics, but published error
rates are far above 0.1%. Curated per-token data is the only representation
that can *hold* a verified 99.9% answer, but it needs a production pipeline.
No authoritative ready-made Quran prose↔word alignment was found. The
recommended design combines all three at build time and ships only the third.

### Cited Findings
**A. Improve the current deterministic mapper**
- It is already 870 lines with hand tables. Its correctness evidence is literal agreement, QAC cue manifests (1,403 / 13,413 correspondences) and selected fixtures, which the docs themselves say are not semantic accuracy — `docs/ENGLISH_ALIGNMENT_COVERAGE.md` table and §"Establishing a semantic accuracy claim".
- Strength: runtime-pure, deterministic and fast (≤1.4 ms for 2:282 per the class KDoc, `EnglishWordAlignment.kt:827-833`).

**B. Contextual / neural / LLM-assisted build-time alignment**
- On the GALE Arabic–English Parallel Aligned Treebank (test 315 sentence pairs), the best fine-tuned XLM-R awesome-align setting reached **AER 27.8**, with fast_align at 47.4 — [Hu et al., "How Do Multilingual Encoders Learn Cross-lingual Representation?" (arXiv 2207.05737)](https://arxiv.org/pdf/2207.05737) (search-snippet figures; table not re-read in full).
- SimAlign extracts alignments from multilingual embeddings without parallel training data. awesome-align fine-tunes multilingual encoders on parallel data — [SimAlign, Jalili Sabet et al. 2020](https://arxiv.org/pdf/2004.08728); [awesome-align (PyPI)](https://pypi.org/project/awesome-align).
- LLMs on an Ancient Greek→English word-alignment gold set (Ugarit, human **IAA 86.08%**): Claude 3.5 Sonnet scored F1 ≈0.84–0.85 / **AER ≈0.150–0.157**, GPT-4o AER 0.179–0.224, an XLM-R aligner AER ≈0.22, and Llama 3.3 8B/70B ≈1.0 AER before fine-tuning. Of 138 gold alignments, Claude's 5-shot output had **64 wrong or partial**, concentrated on adverbs, conjunctions and determiners. The authors say fine-tuning on Claude's "silver" data "will perpetuate these same errors" and recommend human revision, noting that amending ~85%-correct alignments is much cheaper than writing them from scratch — [Nadel & Crane 2025, Computational Humanities Research 1:e12, doi:10.1017/chr.2025.10010](https://doi.org/10.1017/chr.2025.10010).
- Autoregressive LLMs can't revisit an earlier link once a phrase turns out to be larger ("But after all" case). The authors suggest multi-pass / agentic parse→align→edit pipelines — same source.

**C. Curated / versioned span mappings or authoritative data**
- QAC word-by-word glosses are based on Sahih International, Pickthall and Yusuf Ali. The FAQ describes them as contextual glosses, not as an alignment into a sentence translation. Licence: GNU, "for non-commercial research use" — [QAC FAQ](https://corpus.quran.com/faq.jsp).
- Searches found **no published gold Arabic→Sahih-International token alignment** and no Quran prose↔word span dataset. A 2017 survey mentions an "aligned corpus" in an Egypt toolkit with no granularity details — [Critical Survey of Freely Available Arabic Corpora, arXiv 1702.07835](https://arxiv.org/pdf/1702.07835) (snippet only). Recent Quran resources are Arabic-internal (morphology, orthography mapping) — [QAMAR, AbjadNLP 2026](https://aclanthology.org/2026.abjadnlp-1.38.pdf); [arXiv 2609.14967](https://arxiv.org/abs/2609.14967v1).
- The Quran.ai MCP (server tools listed in `/tmp/bq-quran-ai-mcp/tools.json`) has morphology, translation, tafsir, concordance and reader-layout tools, but **no translation-alignment tool**. It is evidence for adjudication, not an alignment source (local tool list).
- Precedent from the Bible domain: Clear-Bible/Alignments publishes "both automatic alignments and manually corrected alignments" as versioned data under CC BY 4.0, and is moving to a standard Scripture Burrito alignment flavour — [Clear-Bible/Alignments](https://github.com/Clear-Bible/Alignments). A compact companion dataset stores token links as integer pairs that need the original editions to resolve the words — [bcv-commons/compact-alignments](https://huggingface.co/datasets/bcv-commons/compact-alignments) (snippet). This is the same "data without text, resolved against an edition" pattern proposed here.

### Inferences
- The app's task is **much easier than open Arabic↔English alignment**. Every Arabic word already carries an English gloss (an interlinear), so most links reduce to English↔English paraphrase matching inside one verse, constrained by morphology. The general-domain AER figures above are therefore *upper bounds on error*, not predictions. The actual error rate is **unknown until measured** (Q5).
- Even so, no published automatic method approaches 0.1% error, and human IAA on full alignment is ~86% in the Greek study. **99.9% is only attainable under a narrow, annotatable definition** (Q4) with human-confirmed labels. Model agreement alone cannot support it.
- Family C is the only one where a fixed answer can be reviewed once, frozen, hashed and shipped. A and B become *candidate generators* feeding C.

### Gaps
- I did not obtain an Arabic–English LLM alignment benchmark, and found no Quran-specific aligner evaluation.
- The Clear-Bible schema and its manual-correction workflow were not read in detail (the README page lacked them).

---

## Q3. Is a single owner per token enough, or are spans and many-to-many links needed?

### Takeaway
For what the UI actually does, **one primary owner per English token is
sufficient and should stay the runtime contract**. That means continuous wash
(earliest owner at or beyond), tap/hold (one Arabic word), and repeat tint (the
English the repeated word owns). Add an optional, rarely-used *also-owners*
set for discontinuous or fused translations (repeat tint/tap only), and a
*kind* flag. Full many-to-many phrase alignment belongs in the build-time
annotation layer, where it helps reviewers, but should not change runtime
semantics.

### Cited Findings
- Runtime consumers use only `EnglishWordSpan(from, to, position)`. `washSpans` takes the minimum owner scanning from the right. `positionAt` returns one owner for taps. The sheet passes `washSpans` or `spans` to ink layers — `EnglishWordAlignment.kt:8-31`; `ui/reader/MushafEnglishSheet.kt:598,612,689-706,1319-1373` (local).
- Every reported regression was fixed by changing *which single word* owns a token: "clear evidence" → word 7 (bayyināt), "ever after" → word 16, "performance" → word 10 with "prayer" → 11 — `docs/ENGLISH_ALIGNMENT_COVERAGE.md` (24:1 and 24:4 sections); `docs/ENGLISH_ALIGNMENT.md` (24:37).
- App segmentation is QF word positions with clitics attached (77,429 rows in `data/quran.db` `words`). Many one-Arabic→many-English cases (wa-/bi-/-hum clitics) are therefore *one-to-many*, which a single owner already represents (local DB count).
- In the Greek LLM study, most errors involved function words and phrase-sized correspondences — [Nadel & Crane 2025](https://doi.org/10.1017/chr.2025.10010).

### Inferences
- True many-to-one cases (one English token that expresses two separate Arabic words) do exist: discontinuous restriction *lā…illā* → "only", cognate-accusative idioms, and supplied pronoun referents. For these, the wash needs a policy, not a richer runtime: **wash at the primary owner** (the word carrying the lexical head), and tint the token on repeat of any *also* owner. This keeps `washSpans` unchanged.
- A meaning-unit id (grouping "clear evidence", "performance of prayer") is useful at build time for review and validation ("all tokens in a unit share an owner unless split deliberately"). It is redundant at runtime because equal adjacent owners already merge into one span.

### Gaps
- How often English tokens genuinely need two Arabic owners is unknown. The pilot (Q5) should count it. If it exceeds about 1% of tokens, revisit the runtime contract.

---

## Q4. The recommended architecture

### Takeaway
A text-free, hash-keyed owner table for each bundled edition. It is produced by
a reproducible build-time pipeline: candidates → agreement → validators → human
adjudication of disagreements → a frozen versioned asset. The runtime builds
`EnglishAlignment` from the table and falls back to the current mapper only on
hash mismatch.

### Cited Findings
- `data/quran.db` contains the Sahih International prose (`ayahs.translation_en`, 6,236 rows populated). It also has QF word rows, but **`words.translation_en` is empty for all 77,429 rows**, and `data_provenance` says QF content is "excluded from committed database; runtime cache only" (local sqlite queries).
- The bundled Sahih text differs from the MCP edition's text. For 24:37 the DB has "Allah… zakah… turn about" while MCP `en-sahih-international` returns "Allāh… zakāh… turn about -" — local DB vs [Quran.com 24:37, translation 20](https://quran.com/24:37?translations=20) fetched via MCP after `fetch_grounding_rules`. Owners must therefore be keyed to the *app's exact text*, verified by hash, never to an externally fetched string.
- The docs report that 14 live-cache rows differ from the older local page source, and that 10 verses have QAC positions clamped against a different segmentation (`untrusted-morphology.tsv`) — `docs/ENGLISH_ALIGNMENT.md`; `docs/ENGLISH_ALIGNMENT_COVERAGE.md`.
- `EnglishVerseAlignments` currently runs the full mapper even in word-by-word (lyricized) mode, where each displayed gloss is by construction its own Arabic word's text apart from coalesced duplicates (`EnglishTypography.lyricize` / `coalescedGlossOwnerIndex`) — `EnglishWordAlignment.kt:848-866`; `domain/EnglishTypography.kt:27-60` (local).

### Inferences: concrete design

**1. Data representation** (new table in `quran.db`, bump `DB_FILE_NAME`):

```
english_owner_maps(
  edition TEXT,            -- 'sahih-intl@<db text revision>'
  surah_id INT, ayah_number INT,
  text_sha256 BLOB,        -- of the exact ayahs.translation_en string
  arabic_words INT,        -- must equal words count for the verse (segmentation check)
  tokenizer_rev INT,       -- the shared tokenizer that defines token i
  owners BLOB,             -- 1 byte per printed token: primary Arabic position (1..n)
  kinds BLOB,              -- 2 bits/token: lexical | grammar | addition | (reserved)
  also BLOB NULL,          -- sparse (token, arabicPos) pairs for discontinuous/fused cases
  PRIMARY KEY (edition, surah_id, ayah_number))
```
- No English text, gloss text or QF content is stored. Size is about 158 KB of owners + ~40 KB of kinds before SQLite page overhead, which is small next to the 25.9 MB DB. 1 byte suffices because no verse exceeds 255 words.
- Token boundaries come from **one shared tokenizer** (Kotlin, with a Python twin tested for equality). It is hashed into `tokenizer_rev`, so build and runtime agree on what "token i" is.
- The build-time *source of truth* is a reviewable TSV in the repo (`verse, token_index, token_text_hash, owner, kind, also, provenance, reviewer_status`). The token text appears only as a short hash so reviewers can resolve it locally against the edition. That keeps the repo free of the Sahih text duplication and of any QF gloss.

**2. Generation (build time, offline, never on device)**
- Inputs: app segmentation (`words` positions), QAC morphology (except untrusted verses), the private QF gloss cache, Sahih text from `quran.db`, and Quran.ai MCP evidence (Arabic, morphology, Sahih + Abdel Haleem translations, Jalalayn), cached privately after `fetch_grounding_rules`.
- Candidate G1: the current Kotlin mapper, frozen at a commit.
- Candidate G2: a *different-mechanism* aligner, an English-gloss↔English-prose similarity matcher (static or contextual embeddings, SimAlign-style argmax/itermax within the verse). It is constrained to monotone-optional matching and uses Arabic POS as a soft prior.
- Candidate G3: an LLM adjudicator with **structured output per token** (owner, kind, also, short evidence string citing gloss/lemma). It gets the written guideline plus the fetched morphology and both translations. It runs two prompts or two model families; to avoid the autoregressive limitation noted by Nadel & Crane, it first lists meaning units, then assigns tokens.
- Agreement policy: a token is **auto-accepted** only if G1 = G2 = G3 on the primary owner and validators pass. Any disagreement goes to the human queue with all three proposals shown. Provenance records which path accepted it.

**3. Validators (deterministic, CI-enforced)**
- Hashes: text, segmentation count, tokenizer revision and morphology trust.
- Structure: every printed token has exactly one owner in 1..n, and the kinds are consistent with brackets.
- Wash invariants via the existing oracle: continuous prefix, no skipped word, and completion at the reciter's last word. These reuse `audit_english_alignment.py` and the 12.38M timing/seek checks unchanged.
- Soft lint queues (not gates): verb-in-English ↔ non-verb Arabic owner, named entity ↔ lemma mismatch (`ARABIC_HEADS` becomes a lint rule, not a runtime rule), and owner distance outliers.

**4. Guideline (needed before any accuracy number means anything)**
1. A token translating a separate Arabic word → that word.
2. A token rendering a clitic or inflection (and, with, them, -ing tense) → the host word.
3. English-only grammar (articles, copula, supplied "do") → the owner of its English syntactic head.
4. Bracketed additions → the owner of the word they explicate; kind=addition (excluded from taps as today).
5. Discontinuous or fused meaning → primary = lexical head, others in `also`.

**5. Runtime integration (minimal)**
- `EnglishVerseAlignments.alignment(ayah)` checks for a bundled map for (edition, verse) whose `text_sha256` matches `verse.translation`, whose `arabic_words` matches `verse.words.size` and whose `tokenizer_rev` matches. If all match, it builds `EnglishAlignment(wordEnds, spans)` directly. Spans come from owners exactly as `align()` builds them now (merge equal adjacent owners), and `wordEnds` from the same span geometry, so the downstream code is unchanged. That covers `washSpans`, soft edge, glimmer, high-water, repeat tint, carried leaves and taps.
- Any mismatch falls back to today's `EnglishWordAlignment.align`, and a debug counter makes a mismatch visible.
- Word-by-word mode: replace inference with construction (each lyricized gloss owns its own word; coalesced duplicates use `coalescedGlossOwnerIndex`). This is a candidate simplification. Per the Chesterton rule, `git log -S` the lyricized `align` call first to learn why it was routed through the mapper (e.g. punctuation or merged glosses).
- A side benefit: on a fresh install with no QF cache, glosses are absent, so today's mapper has nothing to align. The bundled owners need no glosses and would give correct ink from first launch.

**6. Rollout and removal criteria**
- Phase 0: pilot (Q5) on its own; no app change.
- Phase 1: ship the table for the verses whose tokens are all accepted, with fallback elsewhere. The runtime code path is a single function.
- Phase 2: full corpus once the benchmark gate passes.
- Removal: delete the semantic tables `EQUIVALENT_WORDS`, `PHRASES`, `HEAD_GLOSS_VARIANTS`, `GLOSS_SUPPLEMENTS`, the sentence-tail rule and `ARABIC_HEADS`-at-runtime only when (a) 100% of published verses have a hash-valid map in the shipped DB, (b) the fallback counter is zero across the full audit, and (c) every existing semantic fixture passes through the table path. Keep a minimal proportional or lexical fallback for unknown editions. Each deletion follows invariant #9: `git log -S`, and state which fixture covered its reason.
- Future fixes become **data edits** (one verse row, reviewed) plus a regression row. They are never global rules.

### Gaps
- Licensing of a derived owner table built *with* QF glosses as evidence was not checked against QF terms. It stores no QF text, but the user should confirm that a derived alignment may ship. (G3 could also run from MCP-fetched morphology + translations without QF glosses, at some accuracy cost.)
- Reviewer availability and cost (bilingual Arabic/English with tafsir literacy) are unknown.

---

## Q5. A credible benchmark, the measurable gain, and what 99.9% requires

### Takeaway
Define two metrics on a **stratified random verse sample annotated
independently by two humans under the guideline**, report IAA, and compute
exact (cluster-aware) confidence bounds. Model agreement and selected
fixtures are tracked separately as diagnostics. Whether the pipeline reaches
99.9% is **unknown**; the pilot measures it.

### Cited Findings
- The repo's gate already demands every printed token and rejects duplicates. It notes that a 95% one-sided bound of 99.9% needs ≥2,995 independent, representative zero-error observations, and 29,956 for 99.99% — `docs/ENGLISH_ALIGNMENT_COVERAGE.md` §"Establishing a semantic accuracy claim".
- Human IAA on Greek–English word alignment was 86.08% — [Nadel & Crane 2025 citing Palladino et al.](https://doi.org/10.1017/chr.2025.10010).
- AER uses sure/possible gold links (Och & Ney 2003). When only sure links exist, possible = sure — [arXiv 2306.08702](https://arxiv.org/pdf/2306.08702).

### Inferences: benchmark design
- **Metric M1 (heard-meaning, user-visible):** share of *content* tokens (lexical kind) whose wash arrives no later than gold's primary owner and no earlier than gold's previous content owner. This is the "omission / delayed ink" class the user keeps finding.
- **Metric M2 (owner exact):** share of all printed tokens whose primary owner equals gold, which affects taps and repeat tint. This is the strict 158,066-token gate.
- Report both. Release claims use M1 and M2 separately, never merged.
- **Sample:** stratify verses by length band and by surah period, plus oversampling of verses that the candidates disagree on, which is reported separately. The *headline* sample must be simple random verses so it estimates the population. Plan 300 random verses (~7,600 tokens at the corpus mean of 25.3 tokens/verse) for the pilot.
  - Bounds (Poisson approximation): 0 errors → upper error ≈3.0/7,600 = 0.039%; 3 errors → 7.75/7,600 ≈ 0.10%.
  - Tokens cluster within verses, so use a verse-level cluster bootstrap. The effective n is smaller, which is why 300 verses and not ~120 is needed.
- **Annotation protocol:** a ~50-verse subset is labelled **blind** (no predictions shown) by two annotators to measure IAA and anchoring bias. The rest is review-of-prediction with blind spot checks. If IAA on M2 is below ~99.5%, the guideline is too loose for a 99.9% claim, and grammar-token rules must be tightened before measuring further.
- **Independence:** annotators must not be the model, and the five user-reported verses and the 160 adjudicated labels are excluded from the random headline sample (they remain regression fixtures).
- **Pilot comparison (Phase 0, ~300 verses):** measure M1 and M2 for G1 (current mapper), G2, G3, and *agreement-accepted* tokens. Also record the auto-accept rate, the precision of accepted tokens and the human queue size. Together these give a measured corpus-wide improvement and a review-cost estimate before committing to Phase 1.
- **Expected improvement:** unknown in magnitude. The probe shows ~25% of tokens currently lack unique lexical evidence, so G1's weakness is concentrated there. The pilot's G1-vs-pipeline delta on that stratum is the number to report. The Greek study's "amending ~85%-correct alignments is much cheaper" supports the claim that review cost scales with disagreements, not with all 158k tokens.

### Gaps
- No Quran-domain baseline numbers exist for G2/G3, so the auto-accept rate and the residual error of agreed tokens (correlated errors) are unknown.

---

## Q6. Critique: realistic counterexamples and failure risks

### Takeaway
The main risks are correlated errors among candidates, guideline ambiguity
for grammar tokens, edition/segmentation drift, and the claim outrunning the
evidence. Each has a stated mitigation, but none is eliminated.

### Cited Findings
- LLM and aligner errors cluster on function words and phrase boundaries — [Nadel & Crane 2025](https://doi.org/10.1017/chr.2025.10010). The mapper's tables also target those categories (`GRAMMAR`, `PHRASES`), so G1 and G3 can agree *for the same wrong reason*.
- Text drift is real: MCP text ≠ DB text for 24:37 (macrons, trailing dash), there are 14 live-cache diffs, and 10 verses have untrusted QAC positions (Q4 citations).

### Inferences
- **Correlated agreement (e.g. 24:37 "performance" → Allah):** G1 got this wrong by interpolation. A gloss-similarity G2 could also miss "performance"/"establishing". Only G3 or a human would catch it. Mitigation: never auto-accept a *content* token unless at least one *semantic* candidate (G3 or a human) agrees, and audit a random sample of auto-accepted tokens as a separate stratum, so a high agreement rate cannot hide correlated error.
- **"Ever after" (24:4):** "after" is grammar by the stop list, but semantically part of *abadan*. Guideline rule 2/5 makes it a unit with "ever" → word 16. A human would agree; the old metric excluded it. The benchmark counts every token, so this class is visible.
- **Reordered possessive "husbands' fathers" (24:31):** the single-owner representation plus the earliest-owner wash already handles it. Confirms Q3.
- **Supplied referents** (English names a subject that Arabic expresses only as a verb suffix): the owner is the verb. If annotators disagree here, IAA exposes it, and the guideline's head rule settles it.
- **Edition update:** a hash mismatch silently reverts that verse to the old mapper, which is a regression the user might see. Mitigation: a CI check that every DB row has a matching map, and a build failure on any mismatch.
- **Overhead of a data asset:** reviewers must exist, and the TSV must stay mergeable. The risk is a stalled queue that leaves the fallback in place indefinitely. Removal criteria make that state explicit rather than hidden.
- **Overclaiming:** model agreement ≠ gold. The `semantic-certification.json` gate stays RED until human labels exist, as now.
- **Option A rebuttal:** "just add better rules" would be cheaper short term, but the long-tail measurement (1,029 singleton stems) shows no plausible rule count closes the gap. Rules also cannot *record* a verified answer.

### Gaps
- No empirical estimate of correlated-error rates between G1/G2/G3 on this corpus. The pilot's stratum audit is the only way to get it.
