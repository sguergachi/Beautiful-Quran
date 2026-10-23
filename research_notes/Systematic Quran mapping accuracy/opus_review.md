# Systematic Quran mapping accuracy: critical Opus review (round 2) and revised recommendation

Reviewer: Claude Opus 5.5 (`claude-opus-5-5`), 13 October 2026. Scope: research, design and review only. No production code, DB, Git state, APK, memory or paid inference was changed or used. Reads: all three round‑1 notes (`opus_proposal.md`, `alignment_methods.md`, `validation_and_data.md`), the production mapper and its callers, the private copied‑mapper diagnostics in `/tmp/bq-validation-diagnostics/` (input SHA‑256 `d8de9c7f…`, token provenance `4fcb4f12…`, corpus `bc2f3814…`), and cached quran.ai morphology that was fetched earlier after `fetch_grounding_rules`. Two web fetches checked round‑1 citations. No private text beyond short per‑token words needed for the argument is reproduced here.

**Verdict in one paragraph.** Round 1's direction survives: move semantic decisions out of the runtime mapper and into a reviewed, versioned, text‑bound per‑token ledger that a thin lookup consumes. Its *acceptance and evidence logic* does not survive. Three things change. (1) Generator agreement is triage only. It never certifies anything. (2) The statistics: a credible scoped 99.9% claim needs a predeclared simple random sample of distinct tokens from an approved 6,236‑verse manifest, scored blind. Its power shows that the shipped map must truly be around 99.97% correct (≲50 wrong tokens in 158,066) to pass reliably. In practice that means a qualified human checks every token, not only the disagreements. (3) The representation needs one rule round 1 lacked: *every token of a meaning unit inks at the unit's ink position*. This is what removes the "ever after" / "clear evidence" / "performance of prayer" class by construction instead of rule by rule. The new evidence is a spot check of the current mapper. On three of the very verses that were "fixed" (24:1, 24:31, 24:37), I judge about 12 of 186 printed tokens still mis‑owned. This is my model judgement, not gold. Most of them sit in the fallback paths, and one is a copied‑gloss false anchor. This is why rule patches cannot converge, and why the gate must count every printed token.

---

## 1. Does the current mapper still fail on the "fixed" verses, and where do its errors come from?

### Takeaway
Yes. The copied mapper at `e6c2d9a2` gets the specifically patched tokens right ("clear evidence"→7, "ever after"→16, "performance"→10/"prayer"→11). The same verses still contain grammar, discontinuous‑meaning and copied‑gloss errors that no patch addressed. Errors concentrate in, but are not limited to, the 24.43% initial‑fallback population.

### Cited Findings
- Reproduced provenance: the 158,066 tokens split into mutually exclusive initial paths: grammar_claimed 73,428; content_verified 45,817; grammar_reflexive 199; content_fallback 10,221; grammar_fallback 20,054; addition_fallback 8,347. That gives 38,622 (24.4341%) initial fallbacks. Later reattachment changed 1,343 grammar‑fallback and 1,631 grammar‑claimed owners. These counts describe implementation paths. They are not error counts. — `/tmp/bq-validation-diagnostics/summary.json` (private; SHA‑256 of `token-provenance.tsv` = `4fcb4f1255fb…`).
- Additional verse‑level counts from the same TSV (this review): only **452 of 6,236 verses (4,346 tokens) have no fallback token**, **4,255 verses contain at least one content‑fallback token**, and tokens per verse range 2/21/255 (min/median/max). So a "review only the fallback verses" strategy still touches ~93% of verses.
- Per‑token spot check of final owners against numbered app Arabic slots (`input.tsv`) and grounded morphology (cached `fetch_word_morphology` outputs `morphology-24-31.json`, `morphology-24-37.json`). These are my judgements against the rubric in §3, not adjudicated gold:

| Verse | Printed token(s) | Current owner (path) | Rubric owner and grounding | Visible effect |
|---|---|---|---|---|
| 24:37 | "neither" | 3 (content_fallback) | 2 *lā*, "Negative particle" | ink one word late |
| 24:37 | "whom" | 2 (grammar_fallback) | 3 *tulhīhim*, "+ 3rd person masculine plural object pronoun" | tap wrong |
| 24:37 | "in" (in which) | 16 (grammar_fallback) | 17 *fīhi*, "Preposition + … pronoun" | early by one word |
| 24:37 | "about" (turn about) | 19 (grammar_fallback) | 16 *tataqallabu*, card gloss "will turn about" | ink ~3 words late; tail of meaning chopped |
| 24:31 | "male" (male attendants) | 49 (content_fallback) | 54 *al‑rijāl* "[the] men" (*min al‑rijāl*) | ink 5 words early |
| 24:31 | "physical desire" | 50 (content_verified) | 52 *al‑irba*. Slots 50–52 all carry the copied card "having no physical desire" | ink early; false anchor on the "verified" path |
| 24:31 | "in repentance" | 74 (fallback) | 71 *tūbū*, imperative "turn [in repentance]" | ink 3 words late; same class as 24:37 "performance" |
| 24:31 | "all" (all of you) | 75 (grammar_fallback) | 74 *jamīʿan* "altogether" | ink one word late |
| 24:31 | "not" (let them not stamp) | 64 (grammar_fallback) | 63 *wa‑lā* | tap only (wash masks it) |
| 24:1 | "might" (that you might) | 9 (grammar_fallback) | 8 *laʿallakum* "so that you may" | ink one word late |

- Crossing semantics already work where the mapper has anchors: 24:2 "Last Day" gives "Last"→21, "Day"→20, and 24:31 "husbands' fathers" gives husbands'→28, fathers→27. `washSpans` turns these into a continuous prefix by suffix‑minimum — `app/src/main/java/com/beautifulquran/domain/EnglishWordAlignment.kt:11-23`.

### Inferences
- About 12 of the 186 tokens in these three verses look mis‑owned by my judgement. These are non‑random, heavily worked verses, so the share is **not** a corpus error estimate. It shows that patching a reported token leaves its neighbours untouched. The user will keep finding new ones, which is exactly the whack‑a‑mole pattern.
- The "physical desire" case shows that a "verified" path can be wrong when the QF card is copied across slots (`shared_gloss_copy`). Review triage cannot trust path labels. Lexical agreement with a copied card is not evidence.
- Most of the listed errors are *meaning‑unit splits*: "turn about", "in repentance", "male attendants", "lā … tulhīhim". If each token takes its meaning unit's ink position (§3), most of them become one decision per unit instead of several independent guesses.

### Gaps
- The table is one reviewer model's reading under a draft rubric. Qualified bilingual adjudication may reverse some rows, especially "whom", "not" and the "you" in "all of you", which turn on rubric choices.

---

## 2. Ranked material objections to round 1 (resolved or explicitly rejected)

### Takeaway
Ten objections were raised. I accept eight in full and two with modifications. I reject none outright. The most material are: agreement≠gold (#5), the benchmark arithmetic (#6), the missing meaning‑unit/ink contract (#4), and source‑slot trust (#2).

### Cited Findings
- arXiv 2207.05737 is Shijie Wu's PhD thesis "How Do Multilingual Encoders Learn Cross‑lingual Representation?". Its abstract page does not show the GALE Arabic–English AER 27.8 / 47.4 figures that round 1 attributed to "Hu et al." — [arXiv 2207.05737](https://arxiv.org/abs/2207.05737). **Round‑1 claim removed as unverified and misattributed.**
- Nadel & Crane (Tufts), Ancient Greek→English: gold IAA 86.08% (citing Palladino et al.); Claude 3.5 Sonnet 5‑shot had 64 of 138 gold alignments wrong or partial (27 adverbs/conjunctions, 11 determiners/articles); "finetuning on this 'silver' quality data will perpetuate these same errors"; amending ~85%‑correct alignments is "much less taxing" than composing them — [Nadel & Crane 2025](https://www.cambridge.org/core/product/identifier/S2977815825100109/type/journal_article). The exact F1/AER values sit in table images I could not read. **Retain the qualitative findings; drop round 1's specific AER ranges unless they are re‑read.**
- SimAlign and awesome‑align original evaluations contain no Arabic. XL‑WA has an English–Arabic gold test set (210 test / 90 dev), where neural systems reach micro F1 ≈82.8–82.9 — see `alignment_methods.md` (SimAlign Table 2, awesome‑align Table 2, XL‑WA Table 2).
- The audit certificate hashes only the corpus TSV, `EnglishWordAlignment.kt` and the reference — `tools/audit_english_alignment.py:265-267`.
- The printed prose is `EnglishTypography.typeset(raw DB translation_en, quoteContinues)`. The quote state depends on the *next* verse. Glosses come from the runtime QF cache — `app/src/main/java/com/beautifulquran/data/QuranRepository.kt:193-195, 255-293`.
- With no alignment, the wash falls back to plain Arabic word positions — `ui/reader/MushafEnglishSheet.kt:598-599`. `align` returns null when no gloss words are found — `EnglishWordAlignment.kt:73-79`.

### Inferences: ranked objections and dispositions
1. **#5 Agreement is correlated silver evidence. ACCEPTED.** G1 (mapper) and an English‑gloss similarity G2 both read the same QF cards, so the copied card in 24:31 50–52 would fool both. Agreement only *orders the review queue* and *selects which tokens get a random audit*. It never sets a status above `silver`. Statuses are defined in §4.
2. **#6 Benchmark arithmetic. ACCEPTED, and strengthened.** Poisson on 7,600 clustered verse tokens and a zero‑error cluster bootstrap are both invalid, as the validation notes argue. The new point is power (§5): with a zero‑error rule, n = 2,949 passes only 57% of the time if the map truly has 30 errors (99.98% correct). The 99.9% *bound* therefore needs a ~99.97% *truth*, and the plan has to budget for near‑exhaustive human review.
3. **#4 Contract conflation. ACCEPTED, with a lean resolution.** Semantic relation, tap/repeat head and ink/reveal are different fields offline. At runtime two ints suffice, and the second is needed only where they differ (§3).
4. **#2 Source‑slot trust. ACCEPTED.** QAC is joined by raw position. Equal counts can hide compensating split/merge errors. A surface‑sequence crosswalk with a trust flag must exist before any morphology‑derived evidence counts. This is a *risk*: I found no new list of corrupt slots.
5. **#1 Provenance. ACCEPTED.** The copied‑mapper counts replace round 1's 158,083‑token rough probe, which was a different tokenizer and is withdrawn.
6. **#3 Certificate. ACCEPTED.** The release mode requires the approved 6,236‑key manifest and hashes every behaviour dependency. The existing full‑population verified‑fraction gate stays as it is, separate and labelled as coverage, not a CI.
7. **#7 Unverifiable sources. ACCEPTED.** The GALE figure is removed. General‑domain scores are **not** upper bounds on our error. No public gold Sahih prose↔Arabic span map was found. quran.ai is grounding evidence from the same QF data family, not independent gold.
8. **#9 Fallback and state. ACCEPTED, MODIFIED.** In prose mode the printed text comes from the bundled DB through `typeset`, not from the live cache. Most binding can therefore be enforced at build/CI time. Runtime only needs a cheap check plus explicit fallback provenance. The fresh‑install claim is **narrowed**: with no QF cache, prose mode falls back to per‑word proportional wash (null alignment, code path above). A bundled owner table would improve that state, *if* rights allow bundling it. Word‑card mode has nothing to show without the cache and is out of scope.
9. **#8 Binding and rights. ACCEPTED.** Bind to the typeset printed string, the Arabic reader‑slot surfaces and the tokenizer/typeset component digests, not to `ayahs.translation_en` alone. Distribution rights for metadata derived with QF cards are an open user/legal decision. No approval workflow is proposed.
10. **#10 Scope. ACCEPTED, MODIFIED.** Mandatory three‑aligner ensembles, new binary formats, a full many‑to‑many runtime and model training are all dropped from the minimum plan (§6). One LLM drafter plus the existing mapper is enough to draft and triage. The finite corpus is reviewed, not modelled.

### Gaps
- Whether QF/QAC maintainers hold unpublished prose‑span alignments is unknown. Asking them is an outward‑facing action that needs the user's go‑ahead.

---

## 3. Revised representation and lean schema / compiler / lookup contract

### Takeaway
Offline, keep a rich *meaning‑unit ledger*. Ship a compiled table of two small integers per token (ink, head) per verse, bound to digests. The runtime change is one lookup branch in `EnglishVerseAlignments.alignment` and one optional field in `EnglishWordSpan`. The wash geometry, `wordEnds` construction, smooth clocks, highwater/back‑page ink and soft leading edge are not touched.

### Cited Findings
- Current runtime contract: `EnglishWordSpan(from, to, position)`. `washSpans` is the suffix‑min of `position`, `positionAt` serves taps, and `through()` serves seeks/highwater — `EnglishWordAlignment.kt:8-31`. Only `washSpans` positions feed the wash windows — `MushafEnglishSheet.kt:598`. `englishOwnedSpans` chooses `washSpans` or `spans` depending on mode — `MushafEnglishSheet.kt:605-612`.
- Word‑by‑word mode routes lyricized card text through the same `align` call — `EnglishWordAlignment.kt:848-860`. It was introduced in commit `d085e5c6` ("Wash English spans when their Arabic meaning is spoken"), found with `git log -S`.

### Inferences: the contract

**A. Offline ledger (source of truth; one row per printed token, plus a meaning‑unit table).** Private or committed depending on the rights decision.

```
token row:  verse_key, token_index, start_utf16, end_utf16, token_sha256_8,
            unit_id, kind ∈ {lexical, attached_grammar, supplied_grammar, addition},
            status, reviewer_ids, rubric_version
unit row:   verse_key, unit_id, sources (reader slots; optional source morpheme ids),
            relation ∈ {one_to_one, one_to_many, many_to_one, discontinuous, null_link},
            head (one reader slot ∈ sources — tap/repeat target),
            alt_heads (explicit acceptable set, benchmark only),
            ink (one reader slot — earliest slot at which the unit's meaning has been spoken),
            evidence ids, notes
```
Rubric: as in round 1 rules 1–5, plus three new rules.
- **(R6)** All tokens of one unit share `ink`. This is what makes "ever after", "turn about", "in repentance" and "performance of prayer" a single decision each.
- **(R7)** `supplied_grammar` and `addition` tokens join the unit they syntactically modify or explicate, and inherit its `ink`/`head`. A `null_link` unit (no determinable local source) takes the `ink` of the next unit. This keeps the prefix monotone without inventing a spoken correspondence, and it is reported as its own category.
- **(R8)** For discontinuous or many‑to‑one units (*lā … illā* → "only"), `ink` is the **first** contributing slot (no heard meaning is delayed) and `head` is the lexical head. Both are recorded explicitly.

**B. Compiled runtime table.** Per verse: `verse_key, manifest_digest (8 bytes), ink[] (1 byte/token), head_sparse[] (token_index, slot) only where head ≠ ink`. No text of any kind. 255 Arabic words is the safe upper bound, so 1 byte holds a slot. Storage format is a DB table, or a small asset if rights require separating it from `quran.db`. Bundling it in `quran.db` means bumping `QuranDatabase.DB_FILE_NAME`.

**C. Compiler (build/CI tool, deterministic). It fails closed on any of:**
1. The manifest is not exactly the approved 6,236‑key set (or a declared `scope=subset`, which can never pass release), or there are duplicate keys.
2. A per‑verse digest mismatches. The digest covers the typeset printed string (Kotlin `EnglishTypography.typeset` output, exported by a JVM test, so there is no Python twin tokenizer), the token boundaries from the Kotlin `proseWords` exporter, and the ordered (position, surface) Arabic reader slots.
3. A component digest mismatches: `EnglishTypography.kt`, `ArabicNormalize.kt`, the tokenizer/exporter, the crosswalk, morphology source, ledger and rubric version.
4. A structural rule fails: every token appears exactly once; ink/head lie in 1..n; head ∈ sources; ink ∈ sources, or is inherited per R7; all tokens of a unit carry an equal ink.
5. A morphology‑derived source is used from a crosswalk group that is not `trusted`.

After those checks the compiler builds `spans` exactly as `align()` does today (merge adjacent equal `ink`), sets `wordEnds` from the same span geometry, and runs the existing exhaustive wash audits unchanged: continuous prefix, no skipped words, completion at the last word, highwater/repeat/seek checks.

**D. Lookup (runtime).** `EnglishVerseAlignments.alignment(ayah)`: if not word‑by‑word mode, a compiled row exists, and its digest equals the digest of the verse's current typeset text plus slot surfaces (cheap; computed once per verse and cached as today), then build `EnglishAlignment` from the row. `EnglishWordSpan.position` = ink, and a new optional `head` defaults to `position`. Only `positionAt` (taps) and repeat tint read `head`. Otherwise call today's mapper and mark the alignment `provenance = MAPPER_FALLBACK`. That flag is visible in debug/audit output and makes the verse ineligible for any accuracy claim. The runtime never silently relabels: the claim is computed by CI over the compiled table, and CI fails if any bundled verse would fall back. Word‑card mode stays on the current path as a separate corpus ID.

**E. Deliberately *not* in the runtime:** sources sets, alt_heads, relation classes, many‑to‑many repeat tint. Repeat tint of non‑head contributors is deferred. Add it only if the pilot finds that `many_to_one` + `discontinuous` units are a non‑trivial share *and* the user wants that tint behaviour.

### Gaps
- The proposed `head` field is the one runtime addition. If the pilot shows head ≠ ink is rare or only tap‑relevant, it can be omitted and `position` = ink kept. That decision follows from pilot counts, not from assumption.
- R8 (ink at the first contributor) is a product policy that needs the user's confirmation. The alternative, "ink at the head", can delay a heard negation.

---

## 4. Statuses and what may be released or claimed

### Takeaway
Statuses are ordered by evidence, not by agreement. Shipping provisional silver data is allowed only after a non‑inferiority check against the current mapper (which is itself silver), and it carries no accuracy claim. Only an independent blind probability sample produces a claim.

### Cited Findings
- The current gate treats unreviewed tokens as not verified and computes `100 × correct / population` — `tools/audit_english_alignment.py` (lines 99–127, 248–283, per `validation_and_data.md`). The 160 reviewed labels are model/source‑supported, not independent gold — `docs/english-alignment-reviewed-owners.tsv` header (per the validation notes).

### Inferences
Statuses, mutually exclusive per token:
- `quarantined`: source text or slot trust failed.
- `drafted`: one generator only.
- `consensus_silver`: the LLM draft and the mapper agree, unreviewed.
- `disputed`: they disagree, unreviewed.
- `reviewed`: a qualified human confirmed or corrected it after seeing the draft (anchoring risk).
- `blind_gold`: two independent blind labels plus adjudication.
- `unresolved`: the adjudicator could not decide; counted as not correct in every claim.

Release rules:
- **(a)** The compiled table may ship to users in place of the mapper only for verses where every token is `reviewed` or `blind_gold`. Optionally, consensus_silver verses may ship if the pilot shows their blind‑gold error is no worse than the mapper's on the same tokens. The release notes then say "improved, unmeasured", never a percentage.
- **(b)** `blind_gold` lives in a separate ledger and never trains or tunes the drafter on held‑out rows.
- **(c)** The existing coverage gate counts `reviewed` + `blind_gold` correct over 158,066 and stays a coverage figure.
- **(d)** The only statistical claim comes from §5's final sample.

---

## 5. Feasible pilot and scoped 99.9% acceptance requirements

### Takeaway
Run a staged plan: automatable integrity work now, a 50–100‑verse blind development pilot with measured go/no‑go gates, full‑corpus drafting plus human review, then one predeclared blind simple random sample of distinct tokens. The recommended final rule is **n = 6,173 tokens drawn without replacement, accept if ≤ 2 errors (unresolved counted as errors)**. That rule gives a one‑sided 95% bound of ≥99.9% and passes ~89% of the time when the true map has 30 errors.

### Cited Findings
- Exact hypergeometric computations (this review; N = 158,066, null K = 159 errors, one‑sided α = 0.05). The sample size n is the smallest that rejects K = 159 at the given acceptance number. Pass probability is shown at true error counts D:

| Accept if errors ≤ | n | D=10 | D=16 | D=30 | D=50 | D=80 |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 2,949 | 0.83 | 0.74 | 0.57 | 0.39 | 0.22 |
| 1 | 4,660 | 0.97 | 0.92 | 0.78 | 0.56 | 0.31 |
| 2 | 6,173 | 0.99 | 0.98 | 0.89 | 0.69 | 0.39 |
| 3 | 7,592 | 1.00 | 0.99 | 0.95 | 0.78 | 0.46 |

  n = 2,949 matches the validation notes' independent figure. Method: exact hypergeometric CDF via log‑gamma, binary search on n.
- NIST hypergeometric and exact binomial references, and Census design‑based uncertainty requirements — cited in `validation_and_data.md`.

### Inferences: staged plan with go/no‑go
**Stage 0: automatable now, no reviewer, no paid inference.**
- (i) Build the QAC→reader‑slot crosswalk on surface sequences, including equal‑count split/merge detection, and mark groups `trusted` / `quarantined`.
- (ii) Promote the private provenance probe to an audit‑only exporter, a JVM test that emits token boundaries plus typeset digests.
- (iii) Harden the certificate: approved‑manifest mode, full component digests, subset never passes.
- (iv) Write rubric v0 (R1–R8) with ~20 worked examples drawn from the five reported verses and §1's table.
- Go when the crosswalk has zero unexplained groups and the manifest reproduces 6,236 / 77,429 / 158,066.

**Stage 1: development pilot of 50–100 verses.**
- Use a seeded stratified draw: length bands, Meccan/Medinan, fallback density, plus the five reported verses as a known challenge stratum, kept out of all held‑out sets.
- Two qualified readers label blind (no mapper or draft shown), and a third adjudicates. Separately, one drafter (a single LLM with structured per‑unit output grounded in fetched morphology; *future cost, not run here*) and the mapper are scored against the adjudicated labels.
- Measure: minutes per verse; per‑kind disagreement, split into rubric‑ambiguity vs slip; unresolved share; head≠ink share; many‑to‑one/discontinuous share; mapper and drafter per‑token error with design‑aware intervals (diagnostic only); consensus‑stratum error.
- **Go** to Stage 2 when: rubric‑ambiguity disagreements reach 0 in the last 25 verses for lexical/attached kinds; unresolved ≤ 0.5% of tokens; and review‑of‑draft is clearly faster than blind labelling.
- **No‑go** if annotator slips after adjudication cannot be driven near zero. Then 99.9% is not achievable with this reviewer pool, and the claim must be rescoped, for example to wash obligations only.

**Stage 2: full corpus.**
- The drafter and the mapper label all 6,236 verses. Every token gets qualified review (`reviewed`). Disputed, quarantined, copied‑card and meaning‑split tokens get a second reviewer.
- Agreement only orders the queue. The power table explains why skipping review of consensus tokens is not viable: unless consensus tokens are shown to be near‑perfect, they would push D past ~30–50.
- Data fixes are per‑unit ledger edits plus a regression row. No global rules are added.

**Stage 3: certification.**
1. Freeze the ledger, compiled table, rubric and all digests.
2. Publish the sampling plan before drawing: seed, n = 6,173, accept ≤ 2, and every printed category included.
3. Draw distinct tokens uniformly from the approved 158,066.
4. Two new blind annotators who did not review that verse label each sampled token in whole‑verse context; adjudicate. Unresolved counts as an error. A token passes only if all declared obligations hold: ink correct, head ∈ {head} ∪ alt_heads, unit membership correct.
5. Pass → claim: "For Sahih International as printed by app build X (manifest digest …), the one‑sided 95% lower bound on per‑token ink+head correctness is ≥ 99.9%."
6. Fail → report it, fix the classes in development, and draw a *new* pre‑registered sample. Do not resample until one passes. Have a statistician set the error control if more than one attempt is planned.
7. The existing exhaustive wash/timing gates and the coverage gate stay separate.

**Expected cost and accuracy: UNKNOWN.** Reviewer time per verse, IAA and drafter accuracy on this corpus have never been measured. Stage 1 exists to produce those numbers. The only firm planning facts are the population (158,066 tokens, 6,236 verses) and the sample sizes above.

### Gaps
- Qualified reviewer availability and cost.
- Whether the user wants the claim on ink + head combined (recommended), or ink only, which is easier and sufficient for wash fidelity.

---

## 6. Which round‑1 decisions survive, and lean ranking

### Takeaway
The data‑over‑rules direction, edition binding, thin lookup, fallback on mismatch and eventual removal of runtime heuristics all survive. Autoaccept, the 300‑verse Poisson/cluster‑bootstrap benchmark, the three‑generator mandate, the "single owner suffices" claim, the GALE citation and "general scores are upper bounds" do not.

### Inferences
**Survive (some amended):**
- Offline versioned per‑token data; no text stored.
- Edition/text‑hash binding, extended to the typeset string, slot surfaces and component digests.
- Thin lookup feeding the unchanged `washSpans` path.
- Per‑verse fallback, now with explicit provenance and CI zero‑fallback.
- Kind flag.
- Rule‑table removal only after full table coverage and passing fixtures, following Chesterton invariant #9 (`git log -S` per table).
- Fixes become data edits.
- Word‑card mode handled separately; the `d085e5c6` routing reason must be read before changing it.

**Replaced:**
- Autoaccept becomes triage.
- G2 embedding matcher becomes optional.
- "Single owner + also" becomes a unit ledger offline with ink + optional head at runtime.
- The 300‑verse Poisson benchmark becomes the Stage 1 dev pilot plus the Stage 3 token SRS.
- "Exclude known failures from the headline sample" becomes a challenge stratum, while the final SRS covers the full population.
- Annotator review‑of‑prediction as gold is replaced: only blind labels are gold.

**Lean ranking:**
1. Stage 0 integrity work (cheap, needs no reviewer, valuable regardless).
2. Rubric plus 50–100‑verse blind pilot. This is the true decision point.
3. Compiler and lookup behind a manifest, shipping only reviewed verses.
4. Full review.
5. Certification sample.
6. Runtime heuristic removal.

Not recommended: training a supervised aligner, multiple mandatory aligners, a binary many‑to‑many runtime format, or new on‑device dependencies.
