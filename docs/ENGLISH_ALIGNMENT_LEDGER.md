# Offline English alignment ledger — v1

This implements the integrity and pilot stage of the offline mapping proposal.
The initial compiled artifact has **zero approved verses**; all 6,236 verses
still use the mapper. The existing 160 selected model adjudications are not
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
whole verse must qualify. Partially reviewed verses never silently mix approval
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

The TSV carries only keys, fingerprints, ink anchors and interaction heads;
`=` avoids duplicating equal arrays. Android reads it once on IO, validates each
verse's exact text/surfaces, then caches it through EnglishVerseAlignments. Stale
records fall back with `usesApprovedRecord = false`. Missing or malformed
assets also fall back and log the problem. Word-card mode remains a
separate corpus and always uses its own mapper. Soft wash, page cuts, repeat
clipping and frame interpolation remain shared. Metadata is read directly from
assets; no SQLite extraction version bump or DB content change is needed.

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
