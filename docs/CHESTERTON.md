# Chesterton's fence — worked examples

Backing detail for [AGENTS.md](../AGENTS.md) invariant #9. Read this when a
guard in this repo looks wrong and you are about to "fix" it.

The pattern is always the same: the check looks too broad, the narrower version
looks obviously correct, and the narrower version silently removes the whole
protection. What saves you is one command — `git log -S "<the line>"` — and two
minutes reading the commit that added it.

## 1. The override gate fails on *uncommitted* files, and that is the point

`tools/test_build_db.py` fails when **any** `*.json` sits in
`tools/timing_overrides/`, even one you never staged.

That reads as a bug. `tools/timing_overrides/README.md` calls the directory
local scratch and explicitly tells you to put Lab patches there to reproduce a
report. So the gate appears to punish the sanctioned workflow, and the obvious
repair is to check committed files instead:

```python
overrides = subprocess.run(["git", "ls-files", "tools/timing_overrides/*.json"], ...)
```

That is exactly backwards. `apply_timing_overrides` globs the directory **on
disk** (`tools/build_db.py`, `OVERRIDES_DIR.glob("*.json")`) — it never
consults git, and it cannot. So any scratch file present at build time is baked
into whatever `quran.db` you generate. Walk the sanctioned workflow:

1. Drop a Lab patch in `tools/timing_overrides/` to reproduce a report ✅
2. Run `python3 tools/build_db.py` → the one-off is now inside the database
3. Delete the scratch file before committing ✅ *(the README says to)*
4. Commit the database

The result is a 27 MB binary carrying a hand-edited override with no trace of
it anywhere in the repo, and nobody can regenerate it. Because step 3 removes
the file, a committed-files check would **never fire in the case that matters**.

The fence went up in `fd5866d5` (#591), nine days after `1e1128df` did exactly
this. A red suite while scratch exists on disk is the correct signal: it means
every database you build right now is contaminated.

**Keep the on-disk check.** If the diagnostics are unhelpful, fix the *message*,
not the condition.

## 2. The timings checklist is duplicated into AGENTS.md on purpose

`AGENTS.md` § "Landing Timings Lab / GitHub timing patches" carries a six-step
checklist and a symptom table that also live in
[TIMINGS_LAB.md](TIMINGS_LAB.md) and `tools/timing_patch_cases/README.md`. It is
~13% of an always-loaded file, and moving it into `docs/` looks like free
savings.

`git log -S` puts it in `0b899234` (#571) — the commit that *corrected* #570.
Issue #570 was first landed as a one-off override, the canonical anti-pattern,
by an agent working while that guidance existed only in `docs/`. The checklist
is inlined precisely because agents do not reliably open the doc.

The duplication is the mechanism, not an oversight. Leave it.

## 3. The OptMem block is duplicated from a global config

`AGENTS.md` opens with 43 lines of OptMem memory instructions that are also in
the maintainer's personal `~/.claude/CLAUDE.md`. Loaded twice per Claude
session; obvious deletion.

But `AGENTS.md` also carries a "GPT/Codex specific instructions" section — this
file is read by agents that never see a Claude-specific global config. Deleting
the copy strips memory instructions from every non-Claude agent.

It is also ~420 tokens, well under 10% of the file. Not worth the risk even if
it were safe.

## 4. `assert_qcf_v2_runs` refuses layouts that "look fine"

`tools/build_db.py` walks every one of the 604 pages and fails the build unless
that page's `qcf_v2` codepoints, read in word order, are exactly the contiguous
run from U+FC41. It is a whole-book assertion over data that is already
published and versioned upstream, and it will look like belt-and-braces.

It is the only thing tying the layout to the fonts. A QCF V2 page font is a
positional encoding, not a character set: `QCF2570.qcf` maps U+FC41 onward, one
codepoint per glyph, to *page 570's* words in order. So `qcf_page` does not
label a word — it chooses the font that draws it. Give a word the wrong page and
the font renders whatever its own word list happens to hold at that codepoint:
a different, perfectly well-formed Arabic word. Nothing crashes, nothing is
blank, and the leaf is quietly wrong.

That is what shipped. The layout mirror the build used to read
(`zonetecde/mushaf-layout`) put whole verses on the wrong side of a page break,
and broke this invariant on 31 pages. It was noticed only on page 570, where the
codepoints ran past the end of font 570's glyph list and produced tofu — and,
because U+FCEF was an ayah circle, one gold tofu. The 30 pages that fell inside
their font's range showed no symptom at all.

**Keep the assertion, and keep it over all 604 pages.** A spot check on the page
someone complained about is exactly the audit that would have missed this.

## The rule this generalizes to

A check whose *reason* is invisible in the diff is the most likely to be wrong
about being wrong. Before you touch one, be able to finish this sentence:
**"If I remove this, the thing that breaks is ______."** If you cannot, you have
not finished reading yet.
