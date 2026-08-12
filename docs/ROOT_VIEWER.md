# Root Word Viewer

A hold-to-reveal lexicon surface that helps a reader understand an Arabic
word on their own: the triliteral (or quadriliteral) **root**, how this form
is built from it, a short sense of what the root carries in the Quran, and
a **concordance** of every other place that root appears — with counts and
one-tap jumps into those ayahs.

It is **not** a floating popup, dialog, or sheet in the Material sense. It
is an **ink bleed on the reader sheet** — the same surface primitive as the
repeat question. The wash soaks *that* paper only (hosted inside the reader
`PaperPage` / web `.sheet`), never a full-screen layer above the stack. See
[DESIGN.md](DESIGN.md) ("The ink bleed") and the shared composable
`InkRevealOverlay` in `ui/theme/InkReveal.kt`.

## Why it exists

The reader already shows a per-word English gloss. That answers "what does
this token mean here?" It does not answer how Arabic works underneath:
shared roots, derived forms, and how the same letters recur across the
Quran with related senses. The Root Word Viewer exists so a reader can
press a word they care about, see the root it stands on, learn the pattern,
and then **follow that root wherever it appears** — how often, in which
ayahs, in which chapters — and jump straight there.

## The surface: ink bleed

Long-press a word in the reader. Ink blooms outward from that word as an
expanding circle (`InkRevealOverlay`), the held word is the origin, and when
the bloom settles the revealed surface *is* the lexicon page — still the
same reader sheet of paper. Closing opens a hole back onto the exact reader
page underneath — no stack push, no window, no card, no full-screen float.

Palette follows the Timings Lab convention: a **contrasting** workbench
(Royal Green on paper themes; Nightfall when the reader is already Royal
Green) with the dark accent set, so the bloom actually reads against the
reader's own colours.

Hard rules from [DESIGN.md](DESIGN.md) still apply: no dialogs, snackbars,
FABs, cards, borders, elevation, or Material ripple. Hierarchy is spacing,
type size, and ink strength only.

## What it shows

On the pressed word at `(surahId, ayah, wordPosition)`:

1. **This word** — the Uthmani Arabic the reader already knows, plus the
   bundled gloss and transliteration (same data as the word-by-word row).
   A quiet **speaker** beside the Arabic (in the in-page header and again
   in the collapsing top-bar title) plays **only that word** with the
   currently selected reciter: it seeks to the word's timing mark and
   pauses at the word's end. Without a usable timing segment the speaker
   does nothing (it never starts the rest of the ayah).
2. **Root** — the radical letters (e.g. ك ت ب), shown large. When a bundled
   Lane entry exists, a short Form‑1 English lead from that entry sits quietly
   under the radicals (not invented from Quran word glosses). If Lane marks
   more than one form, a quiet "See more detail" line scrolls to Classical
   lexicon below.
3. **Lemma** — the exact QAC lemma on the left at the same Arabic size as the
   root radicals, with (when bundled) English Wiktionary senses on the right
   from `dictionary.db` (kaikki / wiktextract; prefers the open word's QAC
   part of speech; preview unfolds; a one-line Wiktionary / CC BY-SA credit
   lives in the Lemma ⓘ; soft-hides when there is no match; quiet exit to
   Wiktionary).    Lemma and first gloss share one baseline; a long horizontal
   stub on that line meets a quiet column rule down the sense stack. A single
   sense keeps the same gutter width but centers the elbow in that gap so
   short glosses are not hugged by the vertical. Grammar and lemma frequency
   sit under the senses (above the Wiktionary link).
   Root, lemma, occurrences, and related forms each have a quiet info mark
   that unfolds a short definition inline.
4. **Occurrences** — how often this root appears in the Quran, and where.
   This is a first-class part of the surface, not a footnote:
   - A clear **count** ("appears *N* times in the Quran").
   - Optionally a secondary count for this exact surface form if it helps,
     but the primary unit of exploration is the **root**.
   - A scrollable list of occurrences grouped by **surah** (chapter), each
     row showing surah name / number, ayah number, the Arabic word form at
     that location, and its gloss.
   - The held word's chapter plus the first seven other chapters appear
     initially. Only one chapter is open at a time; opening another closes the
     previous one. An open chapter shows at most **five** references before a
     quiet show-more line. This keeps roots with thousands of hits small at
     first render.
   - Each occurrence is tappable: choosing one **closes the bleed and
     jumps the reader to that ayah** (same surah navigate-in-place, or
     open the other chapter if the hit is elsewhere), so the concordance
     is a way to *read through* the root, not only look up a number.
5. **Related forms** — the other frequency-ordered lemma / POS analyses under
   this root, excluding the form already explained above. Five appear before
   a quiet show-more line; the section disappears when there are no others.
6. **Classical lexicon (Lane)** — when a bundled entry exists for the root:
   Form labels, spaced senses, and quieter source citations, with a preview
   that unfolds to the whole article. Lane's bibliographic line and the
   required Perseus credit live in the section ⓘ, not under the entry. The
   section ends with a quiet browser exit to the full Lane page
   (`arabiclexicon.hawramani.com`).
7. **Learn more online** — location-aware browser shortcuts for readers who
   want to continue beyond the bundled data:
   - QAC's segment-by-segment grammar page for the exact `(surah:ayah:word)`.
   - QAC's Quran dictionary for the exact root (using QAC's Buckwalter query).
   - The full ayah on Quran.com for translations, recitation, and tafsir.
8. **Attribution** — a quiet line naming the Quranic Arabic Corpus and
   linking to `http://corpus.quran.com` (required by the data terms).

The viewer does not fetch these pages or depend on them to open. Links are
optional exits to the browser; all bundled morphology and concordance remain
offline-first.

Particles and other words with no triliteral root still open the surface:
show the lemma / POS explanation and omit the root-concordance block
rather than failing the gesture.

The web and Android surfaces share this order and one-column hierarchy. The
held word is the only hero-sized element; root/form facts stay within a narrow
prose measure, while chapter rows may use the full interaction measure. Major
sections are separated by whitespace, never rules, cards, or containers.

## Concordance model

The concordance answers three reader questions in order:

| Question | What the UI shows |
|---|---|
| How often? | Total occurrence count for this root across the whole Quran |
| Where? | Each hit as `(surah, ayah [, word position])`, grouped by chapter |
| Can I go there? | Tap a hit → dismiss the ink bleed → reader focuses that ayah |

Jump behaviour:

- **Same surah** — close the overlay and ask the existing focus path
  (`ReaderFocusController` / ayah selector semantics) to land on that ayah,
  with the target word available for highlight/selection if useful.
- **Other surah** — close the overlay, open that chapter at the target
  ayah (same "open surah at ayah" path Home already uses), so the reader
  never nests a second bleed or a modal route.
- **Way back** — after a jump to a different ayah (same or other chapter),
  an **opaque floating capsule** appears above the paper stack (centred near
  the bottom; above the player bar while the reader is open, and above the
  chapter-list floating transport on the cover). It is the stadium twin of
  the return-to-ayah roundel: paper fill, embossed gilt rim, and the same
  drawn reed-pen arrow (pointing back), with chapter name · `surah:ayah`
  as quiet ink inside. Both ornaments share `FloatingPaperControl` for
  entrance / exit (full-height slide + fade) and bottom inset. Hosted in
  `MainActivity`, not inside the reader sheet, so closing the reader or
  returning to chapter selection leaves it in view. The whole capsule is
  one tap target (returns there); there is no separate dismiss control —
  the first hand scroll, drag, or paper-stack page turn after the jump
  settles arms a **30-second** countdown that then clears it (the
  programmatic settle itself does not start the timer). The ornamented
  return-to-ayah control yields to this capsule while it is visible.
  Ink-bleed overlays (Root Viewer / Lab / chooser) temporarily cover it.
- Closing the reader sheet (paper-stack back) does not reopen the lexicon.

Occurrence list ordering: Quranic order (surah, then ayah, then word
position). Counts and lists are precomputed at DB build time where
practical so the overlay opens without a heavy scan.

## Lexicon source

**Quranic Arabic Corpus (QAC) v0.4** — Kais Dukes / University of Leeds,
maintained in association with the quran.com team.
[corpus.quran.com](http://corpus.quran.com)

| Why this one | Detail |
|---|---|
| Recognition | The standard open morphology / root dictionary for the Quran |
| Content | Per-location `ROOT`, `LEM` (lemma), `POS`, and morphological features; online Quran Dictionary sense groupings |
| Redistribution | May be used in an application if the source is clearly indicated and a link to `http://corpus.quran.com` is provided; include the copyright notice with substantial portions |
| Offline fit | Ships inside `quran.db` via `tools/build_db.py` — no network at read time |

**English Wiktionary Arabic** (via kaikki.org / wiktextract) supplies modern
lemma-keyed English senses in a separate `dictionary.db` (~1 MB, QAC-lemma
subset only). License is CC-BY-SA / GFDL — credit lives in the Lemma
section ⓘ. Soft-miss when a lemma has no Wiktionary entry.

**Lane's Arabic-English Lexicon** (Perseus TEI → `lexicon.db`) is the classical
root article shown lower on the page. QAC remains the morphology + concordance
layer; WBW glosses remain the token-level English.

Terms reminder (paraphrased from the QAC download notice): use in an app
is allowed with clear attribution and a link back to the project; the
upstream annotation file itself is distributed verbatim (do not silently
alter the source file — derive tables in the pipeline).

## Data pipeline

Morphology stays a **build-time** concern (architecture invariant #2).
`tools/build_db.py` fetches / caches the QAC morphology resource, maps it
onto the app's canonical word segmentation (space-split Uthmani — same
canon as WBW and timings), and writes derived tables into `quran.db`.

Shipped schema (written by `tools/build_db.py`):

```sql
word_morphology (
  surah_id, ayah_number, position,   -- joins words
  root TEXT,                         -- Arabic radicals, e.g. كتب
  lemma TEXT,
  pos TEXT,
  features TEXT                      -- compact remaining tags
)

roots (
  root TEXT PRIMARY KEY,
  occurrence_count INTEGER NOT NULL
)

root_occurrences (
  root TEXT NOT NULL,
  surah_id INTEGER NOT NULL,
  ayah_number INTEGER NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (root, surah_id, ayah_number, position)
)
```

Dictionary senses ship separately (`tools/build_dictionary_db.py` →
`dictionary.db`):

```sql
lemma_entries (
  lemma TEXT PRIMARY KEY,   -- QAC spelling
  word TEXT NOT NULL,      -- Wiktionary headword matched
  payload TEXT NOT NULL     -- JSON: [{pos, glosses: string[]}, ...]
)
```

Mapping notes:

- QAC locations are `chapter:verse:word` and may annotate **sub-word**
  segments (prefix + stem). Collapse those segments onto our single
  space-split `words.position` the same way other sources are aligned —
  validate and log mismatches; never invent repair logic in the app.
- Words without a `ROOT` (many particles, some proper names) still get a
  morphology row when lemma/POS exist; concordance simply has nothing to
  list for a root.
- **Any DB content change bumps `QuranDatabase.DB_FILE_NAME`**
  (`quran-vN.db`) so upgrades replace the cached extract (invariant #1).

Runtime: `QuranRepository` exposes typed queries (word morphology, root
summary + occurrences). No network. HighlightEngine is untouched.

## Entry, exit, and developer mode

### Default (everyone)

| Gesture | Result |
|---|---|
| Long-press a word in the reader | Root Word Viewer blooms in on that word |
| Tap the speaker next to the word | Plays **only** that word (timing clip); no-op without timings |
| Back / close control on the viewer | Hole opens; return to the same reader page |
| Tap a concordance hit | Viewer closes; reader jumps to that ayah / chapter |

Word tap (short press) keeps today's behaviour: play/seek from that word.
Only the **hold** opens the lexicon.

Opening the viewer pauses an active chapter recitation. Closing it restores the
same ayah position, speed, and repeat settings (full chapter queue) and resumes
only when playback was active before the viewer opened. If the chapter was only
paused on the player, exit still rebuilds the full queue without autoplay so
Play advances past the current verse. Concordance jumps do not resume the old
location. The word speaker hard-stops at the word end on a full-chapter
playlist (never a dead-end queue ending on that ayah); the saved reading
session is restored on exit.

### Developer mode

Developer mode is unlocked the existing Settings way (repeated taps on the
logo) and must be **persisted** in settings so the reader can honour it —
today's unlock is ephemeral and dies when Settings leaves composition.

When developer mode is **on**:

- Word long-press offers a choice, still in ink-bleed language (not a
  Material dialog): open the **Root Word Viewer** or open the
  **Timings Lab** on that word.
- Timings Lab remains reachable from Settings (logo long-press and / or a
  line in the developer section).

When developer mode is **off**:

- Timings Lab is **not** reachable — not from word long-press, not from
  Settings. The Lab is a developer workbench; default readers only ever
  see the Root Word Viewer from a hold.

See [TIMINGS_LAB.md](TIMINGS_LAB.md) for Lab behaviour once open; this doc
owns the entry gate.

## Non-goals (for v1)

- Full classical dictionary articles or multi-paragraph tafsīr.
- Editing morphology or contributing corpus tags from the app.
- Replacing the per-word gloss row in the reader (the gloss stays; the
  viewer goes deeper on demand).
- Network lookup at open time.

## Implementation sketch (when coding starts)

```
Reader onWordLongClick
  ├─ !developerMode → openRootViewer(surah, ayah, position, origin)
  └─  developerMode → ink chooser → Root Viewer | Timings Lab

Reader PaperPage / web reader .sheet
  InkRevealOverlay(rootVisible) → RootViewerScreen   // on the sheet
  InkRevealOverlay(chooser)     → WordHoldChooser    // on the sheet
MainActivity (stack-level)
  InkRevealOverlay(labVisible)  → TimingsLabScreen   // also from Settings
```

Suggested modules: `ui/rootviewer/` for the Compose surface; repository
queries beside existing word loading; pipeline work only in
`tools/build_db.py`. Keep `HighlightEngine` pure and unchanged.

## Docs to keep in sync when this ships

| Doc | Update |
|---|---|
| [DESIGN.md](DESIGN.md) | Ink bleed now also names Root Viewer (+ Lab, notification) |
| [ARCHITECTURE.md](ARCHITECTURE.md) | New source + tables; overlay surfaces |
| [TIMINGS_LAB.md](TIMINGS_LAB.md) | Entry is developer-gated; default hold is Root Viewer |
| Settings attributions | QAC credit + link |
| This file | Behaviour / schema if they drift from the sketch |
