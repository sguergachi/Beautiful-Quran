# Offline English alignment ledger — v1

This implements the integrity stage of the offline mapping proposal and an
opt-in AI-reviewed An-Nur and Al-Furqan translation corpus.
The human-reviewed compiled artifact has **zero approved verses**; all 6,236
verses still use the mapper by default. The existing 160 selected model adjudications are not
human review or independent gold. There is no new semantic accuracy claim.

## Export

```bash
python3 tools/english_alignment_ledger.py export --output-dir /tmp/english-review
# Optional baseline drafts from a private complete runtime TSV:
python3 tools/english_alignment_ledger.py export --output-dir /tmp/english-drafts --draft-corpus /path/to/corpus.tsv
```

The exporter invokes the production Kotlin typesetter and tokenizer. Quote
continuation uses the next verse in the same chapter, exactly as the repository
does. Every printed word counts, including grammar and bracketed additions.
Offsets are UTF-16. The full manifest binds verse keys, ordered Arabic surfaces,
printed text, token offsets/text and counts. All generation component hashes
(DB, normalization, typography, mapper, models, lookup, exporter, compiler and
rubric) remain provenance. Active binding checks only the text/Arabic input
tables and the production typesetter/tokenizer; timing or documentation edits
do not invalidate reviewed decisions.
Private word cards stay in the export; none enter the APK.

Each ledger verse binds to its own text/surface fingerprint and token digest,
so unchanged reviewed rows can be reused with a new full manifest. The source
manifest hash stays as provenance. With private cards supplied, the mapper
supplies model_draft anchors and heads. Without them all decisions are unresolved.
QAC trust is explicitly unreviewed: this tool does not certify its surface
crosswalk or repair segmentation. Equal counts cannot establish positional
trust. Qualified reviewers use the actual Arabic slots and independent evidence.

## Token decisions and rubric

- `meaning`: the reviewed meaning or an explicit translator-addition decision.
- `contributors`: Arabic slot positions; `[]` for a null-linked addition.
  `null` means undecided and cannot ship. Never invent a source for an addition.
- `unit`: a local meaning sub-unit. Words expanded from one Arabic word share
  ink. Multi-source phrases keep their separate portions: 24:37 performance
  belongs to word 10 and prayer to word 11.
- `head`: the explicit tap/repeat target, within the semantic contributors.
  For an addition this is a navigation
  cue, not an assertion of an Arabic semantic contributor.
- `ink_anchor`: the spoken position beginning the forward wash. When semantic
  contributors exist it must be one of them. Reordering retains a gapless prefix.
- `status`: unresolved, model_draft, model_reviewed, consensus_silver, disputed,
  reviewed or blind_gold. Agreement only orders review; it never proves a label.
- `review`: reviewer identity, evidence references and method human_review or
  blind_adjudication. These are attestations; software cannot verify reviewer
  qualifications, independence or linguistic correctness.

Only reviewed/blind_gold tokens with complete provenance can compile, and the
whole verse must qualify in the default human-review policy. Partially reviewed verses never silently mix approval
and guesses. V1 retains the existing band-duration policy: first wash ends at
the next band, first repeat or spoken end. Independent arbitrary completion
clocks are outside this format. Unit identifiers must be contiguous and use
consistent contributors; distinct portions require distinct sub-units.
Discontinuous meanings, lā…illā, multi-source
onsets and verse-final additions require an explicit reviewed policy decision.
Leave them unresolved in its absence; do not apply a universal earliest-source
rule. One token's correction cannot change another verse.

## Compile and load

```bash
python3 tools/english_alignment_ledger.py compile \
  --ledger /tmp/english-review/ledger.jsonl \
  --manifest /tmp/english-review/manifest.json \
  --output /tmp/english-alignments.tsv --coverage /tmp/coverage.json
```

The default rejects every fallback. `--allow-partial` explicitly enables a
development pilot; all omitted/unapproved verses stay in its denominator.
The committed initial ledger is empty and compiled in that mode. CI reproduces
the artifact and coverage report. Duplicate keys, invalid rows, stale manifests
and missing component hashes fail in either mode. Export into a new directory
after text/binding changes; unchanged verse decisions remain usable, while
changed verses need review. Existing review
exports are never overwritten.

The TSV carries only keys, fingerprints, ink anchors, interaction heads and
one contributor-presence bit per token;
`=` avoids duplicating equal arrays. Android reads it once on IO, validates each
verse's exact text/surfaces, then caches it through EnglishVerseAlignments. Stale
records fall back with `usesApprovedRecord = false`. Missing or malformed
assets also fall back and log the problem. Word-card mode remains a
separate corpus and always uses its own mapper. Soft wash, page cuts, repeat
clipping and frame interpolation remain shared. The optional fifth TSV column
is the contributor bit string; older four-column records still load with the
legacy presentation policy. New records build readable phrases from reviewed
ink owners and contributor provenance, retaining separate semantic tap heads.
Only a source-linked, whole printed literal also present in the selected
owner's runtime gloss can establish a word-finish target. Additions, synonyms
and missing glosses do not acquire fabricated exact deadlines. The same
`EnglishReadingFlow` handles drawing, inverse finish times and carried follow.
Metadata is read directly from
assets; no SQLite extraction version bump or DB content change is needed.

## Developer AI approval

The user requested a separate opt-in path while a qualified reviewer is
unavailable. Settings → Developer → **AI-approved English mappings** is off by
default. Triple-tap the Settings logo to expose Developer; disabling developer
mode also disables the experiment without forgetting its preference.

`--ai-reviewed` permits fully `model_reviewed` verses in a separate artifact:

```bash
python3 tools/english_alignment_ledger.py compile \
  --ledger data/english_ai_alignment_ledger.jsonl \
  --manifest data/english_alignment_manifest.json \
  --output data/english-ai-alignments.tsv \
  --coverage data/english_ai_alignment_coverage.json --allow-partial --ai-reviewed
```

Every token needs meaning, contributors, ink/head decisions and model-review
evidence. `qac_trust: surface_verified` attests that the returned word surfaces
match the app's ordered slots after Arabic normalization, excluding the verse
number marker. It is not inferred from counts. The compiler enforces structural
completeness and binding; linguistic correctness and this source attestation
remain AI judgments. Silver agreement, drafts, disputed or unresolved decisions
do not qualify. The default compiler still rejects model review as human approval.

The AI artifact covers **all 64 An-Nur verses / 2,613 printed tokens** and
**all 77 Al-Furqan verses / 1,847 tokens**, plus the retained regression verses
9:4, 9:25 and 23:83: **144 verses / 4,571 tokens**
in total. One Codex model (`gpt-6.1-sol`) annotated every token using
fetched Quran.ai Arabic text, Sahih International, word morphology and Jalalayn
commentary. This was not an independent second-model review or a blind sample.
`english_ai_review_evidence.json` retains source digests and fetched citation URLs;
raw replies remain in the private artifact archive. The ledger stores the model's
decisions, including explicit cues for null-linked additions. Review every token
against the actual printed edition; bracketed wording can have an Arabic source.

The chapters' 1,316 and 893 actual reader slots were cross-checked in order
with the fetched surfaces. Source comparison decomposes canonical Unicode forms
before removing marks and normalizing alef/ya variants; this handles the
decomposed hamza in 24:11 slot 16. Runtime normalization is unchanged and record
fingerprints still bind the exact app text and surfaces.

The APK carries only a thin second lookup. Valid human-reviewed rows win; the
toggle selects AI rows for prose only, with **6,092 fallback verses**. Word cards
and web remain unchanged. Wash, tapping, repeat and carried-leaf following share
the selected mapping and preserve the continuous prefix. No model or Quran.ai
request runs on the phone. Coverage records the AI policy explicitly and keeps
semantic accuracy unestablished. The other chapters have not been AI-reviewed
in full.

Al-Furqan's model notes preserve edition-specific additions: 25:22's bracketed
speaker and 25:77's supplied subject have explicit null links and timing cues.
The fetched Jalalayn commentary supplies different referents; the ledger binds
the printed Sahih International edition without resolving that disagreement.

Chapter validation uses the actual DB text with production quote continuation,
typesetting, lookup selection and wash functions. Every verse loads offline;
word-card mappings are identical with the toggle on and off. The 832 An-Nur
timing rows across 13 reciters pass 2,669,366 wash-edge queries, the same number
of reverse-seek queries and 6,428 repeat-window checks. These verify continuous
ink and integration, not linguistic accuracy. The JVM suite, 12 ledger
tests and semantic-gate regression pass.

Al-Furqan adds 1,001 timing rows across the same 13 reciters, passing 1,620,211
wash-edge queries, the same number of reverse-seek queries and 1,329
repeat-window checks. All 77 lookup records load from the actual printed text
offline; word-card mappings again remain identical with the toggle on and off.

## Resumable chapter review

`tools/review_english_chapters.py` runs the offline work with the authenticated
local Codex CLI. It uses fresh, ephemeral, read-only sessions for drafting and
critique, explicitly naming the requested model with xhigh reasoning. It adds
no phone-side model, service, credentials or new Python dependency. The CLI's
structured-output interface follows the [official Codex example](https://developers.openai.com/cookbook/examples/codex/build_iterative_repair_loops_with_codex).

```bash
python3 tools/review_english_chapters.py run --chapters 64 \
  --work-dir "$HOME/.cache/bq-english-chapters" \
  --corpus "$PRIVATE_RUNTIME_CORPUS" --timings-cache "$PRIVATE_TIMING_CACHE" \
  --model gpt-6.1-sol
python3 tools/review_english_chapters.py report \
  --work-dir "$HOME/.cache/bq-english-chapters"
```

Use `--chapters 1-114`, a range or a comma-separated list for bulk processing.
`--max-verses 25` bounds a batch; subsequent invocations resume completed work.
Each verse has a private atomic checkpoint. Only one process may own a work
directory. Two verse workers run by default; `--workers 1` through `4` controls
bounded concurrency while canonical fetches remain serial and rate limited.
Completed model stages also resume after interruption when their exact input
and schema match. Completed reviews are reused only with matching text, source
evidence, model, rubric and review implementation. Changed bindings fail closed;
export into a new work directory after a typesetter/corpus binding change.
`--retry-held` explicitly starts another bounded attempt budget and preserves
the old attempt files. Drafting/critique defaults to two attempts per verse,
with a configurable 1–3 limit and a 600-second model-call timeout.

The runner fetches grounding rules first, discovers editions, and selects the
concise Arabic Jalalayn commentary for lexical/phrase parsing. Every passage's
edition, verse keys and pagination are checked. Morphology excludes explicit
verse-number markers, then compares each actual reader position and normalized
surface; equal counts alone never pass. Raw UTF-8 replies, source URLs and
digests stay in the private work directory. Surface mismatches remain held.
The model supplies decisions only; the runner retains immutable printed text,
UTF-16 offsets and the complete 6,236-verse manifest. The prompt rubric is
`tools/english_chapter_review.md`.

Every token needs a structurally valid decision before the fresh critique can
approve the verse. Review disagreement, malformed output, missing evidence,
invalid owners and incomplete units stay held. This is two model passes, not
independent bilingual review or blind gold. Previous AI ledger records outside
the selected chapters are reused with their original provenance and are
reported separately, not relabelled as freshly critiqued.

The candidate lookup must then pass `audit_english_phrase_pacing.py` through
the actual reviewed-record loader, `EnglishVerseAlignments`, presentation plan,
forward word progress and inverse finish functions. It exercises the private
runtime voices plus the other bundled voices without filling withheld runtime
rows. Word-card mappings with the toggle on/off must match. Corpus checks use
the export's production-typeset edition, including neighbouring-verse quote
state, rather than assuming an older private TSV already has current typography.
Only a passing candidate becomes `english-ai-alignments.tsv` in the work
directory. It never edits packaged data or publishes an APK on its own.

`report.json` keeps each selected chapter's full verse denominator, reviewed and
held counts, reasons, playback state and completion flag. `coverage.json` keeps
the full-book denominator and `semantic_accuracy_established: false`. The
playback folder retains per-word exposure/deadline diagnostics, source and
artifact hashes, explicit fallback reasons and baseline collapses. Playback
passing is structural/timing evidence; it does not certify linguistic accuracy
or universal visual steadiness. Bundling remains a normal reviewed code/data
change using the existing developer toggle; word-by-word mode remains locked.

## Evidence still needed

Review coverage and semantic correctness are separate. Compiler and legacy
audit report semantic accuracy as unestablished. A complete tiny example cannot
certify the book. The legacy conservative reference fraction stays a diagnostic.
The next evidence is a blind 50–100-verse pilot with qualified readers and
adjudication, followed by corpus review and a frozen, predeclared uniform token
sample with independent blind gold and exact finite-population bounds.
No such sample or qualified review was performed by this implementation.
Model agreement and selected regression fixtures provide no confidence bound.
Rights to distribute metadata derived from private QF cards remain unestablished;
the initial committed artifact contains none.
