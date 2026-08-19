# Timings Lab

A Musixmatch-style, WYSIWYG editor for the word-level timing / repeat data
that drives the follow-along highlight. It exists because the bundled timings
come from open datasets that are imperfect: individual ayahs can be audibly
off, repeats are only ear-verified for two reciters (see
[REPEAT_HIGHLIGHTING.md](REPEAT_HIGHLIGHTING.md)), and there was no way to
ship a fix without regenerating the whole DB. The Lab closes that loop:
notice a mistimed word while reading → open the Lab (developer mode) →
fix it in seconds → the reader is corrected immediately → submit the
correction upstream when convenient.

Whole-ayah drift caused by silence encoded at the beginning of an everyayah
MP3 is handled systematically outside the Lab. `tools/detect_audio_onsets.py`
measures the first sustained voice sample. Repeat-aware qdc rows are translated
as a whole onto the exact MP3 clock by the median matching quran-align
boundary; only the first wash is then clamped to the voice onset, leaving every
later valid boundary and repeat unchanged. A row whose second word also
predates voice is instead shifted uniformly. The onset is also stored
separately as immutable MP3 metadata. The repository median-rebases older Lab edits against
the current bundled row, so every word keeps its correction rather than fixing
only the opening wash. Override schema 2 records a clock version per row:
unversioned schema-1 rows migrate once at read time and are written straight
back, so the reader, the Lab and an exported patch all describe the same marks.
Every newly saved Lab row keeps its intentional word boundaries and receives
only the opening voice floor.

> **Entry is developer-only.** Default readers long-press a word to open the
> [Root Word Viewer](ROOT_VIEWER.md), not the Lab. See *Where it lives*
> below.

## The one design rule

**The editor is the reader.** The center of the Lab is the actual `AyahBlock`
the reader renders — same connected script / word-gloss modes, same letter
fade, same orange repeat wash, same fonts and font scale — live-driven by the
*edited* segments through the same `HighlightEngine`. You never look at
numbers to judge a correction; you watch the real fade land on the real
recitation, exactly as the reader will show it.

Everything else follows from that rule. There is no segments table, no
sliders, no start/end forms. Two verbs cover every correction:

### Verb 1 — Re-sync (record a tap pass, like Musixmatch)

Hit **Re-sync**. The ayah restarts (at 1×, ¾× or ½× — marks are stored in
true audio time either way) and you tap each word at the moment the reciter
begins it. Each tap drops that word's start mark at the playhead and the word
inks in under your finger — because taps *are* segments and the highlight is
driven from segments, the karaoke fade follows your taps in real time.

* **Repeats need no special mode.** When the reciter re-recites an earlier
  phrase, tap those words again as they happen. A mark whose word position is
  ≤ the furthest word already marked *is* a repeat backtrack — the same
  encoding the DB uses — and the words take the orange wash immediately.
* **Slip a tap?** `Undo` removes the last mark and rewinds to just before it;
  `↺ 4s` rewinds four seconds and clears the marks you overran, so you re-tap
  just that stretch.
* Taps are compensated ~100 ms (scaled by playback speed) for finger
  reaction latency; the Adjust slide bar catches anything the compensation misses.
* When the audio ends (or you hit **Done**) the pass is saved and the ayah
  replays from the top so you immediately verify your work — Musixmatch's
  "check your sync" step, automatic.

Ends are derived, not tapped: each mark's end is the next mark's start, and
the last mark ends at the audio duration. That matches the reader's hold
behaviour (a word stays lit until the next begins).

### Verb 2 — Adjust (select a word, slide its start)

For a word that's slightly off there's no need to re-tap the ayah. **Tap the
word** (on the verse) **or its marker** (on the timeline): the Lab selects
that mark, seeks ~0.8 s before it and plays, and reveals the **slide bar**.

* **Slide to adjust.** Drag the bar left/right to move *only* that marker's
  start — nothing else shifts. The moment you grab it the timeline **zooms in**
  around the marker and *holds* there while you work (Apple-timeline style —
  it never flickers in and out as you pause or change speed); it eases back to
  the full ayah only a beat after you let go. When the slide settles the Lab
  **re-auditions** from just before the new start, so every adjustment is
  judged by ear.
* **Reset** returns *just this word* to its bundled default — its adjusted
  mark(s) are replaced by the shipped one(s), every other word left as you set
  it. Greyed out until the word actually differs from the default. (Whole-ayah
  reset still lives in the ⋯ menu.)
* **＋ Add repeat** — stamps a second mark for the selected word at the
  playhead and selects it, so you immediately slide it to where the reciter
  re-recites it. The rest of the ayah is untouched. A repeat pass **floats
  free** — the slide bar places it anywhere in the ayah, even across other
  marks, since it's an extra occurrence rather than part of the ordered
  first-pass sequence (first-pass marks stay clamped between their neighbours).
  A mark whose word position is ≤ the furthest word already reached *is* a
  repeat backtrack (the DB's own encoding), so it takes the orange wash
  automatically.
* **Delete** — removes a spurious pass (e.g. an alignment-noise repeat).
* If the word was recited more than once, **pass chips** (`6.4s`, `10.6s ·
  repeat`) pick which pass you're adjusting.

The timeline above the transport shows every mark — **gold for first-pass,
orange for repeats** — the selected mark enlarged with a handle, and the live
playhead. When nothing is being adjusted, tap a marker to select it or tap
elsewhere to scrub.

### No save button

Edits persist automatically — on finishing a re-sync, after a slide settles,
when you change ayah, and when you leave the Lab. The override store is tiny
and atomic, so there is nothing to lose and nothing to remember. `Reset ayah
to bundled` (overflow menu) reverts to the shipped DB row; `Clear all
corrections` empties the store.

## Where it lives

The Lab is **not a page in the paper stack** — it is a contrasting workbench
that **blooms in over whatever is open** (usually the reader) as an expanding
ink spot, the same ink-bleed language as the notification prompt, and closes
by opening a hole back to the exact page it came from. Its palette is always
**Royal Green** (and **Nightfall** under the Royal Green theme itself, so the
two never coincide) with the dark accent set — that contrast is what makes the
bloom read, since the surface would otherwise share the reader's own colours.
The Lab is reachable **only while developer mode is enabled** (persisted
Settings unlock — repeated taps on the logo). When developer mode is off,
word long-press opens the [Root Word Viewer](ROOT_VIEWER.md) instead and
no Settings path exposes the Lab.

* **From the reader** (developer mode on): long-press any word → an
  ink-bleed chooser offers *Root word* or *Timings Lab*. Choosing the Lab
  blooms it in on that exact (surah, ayah) with **the pressed word already
  selected and auditioning**, so the fix loop starts without another tap.
  Back (or the ▾ chevron) closes it back onto the same reader page, which
  already reflects the correction — the reader re-pulls fused timings the
  moment the override store changes.
* **From Settings** (developer mode on): long-press the logo, or a
  developer-section line → *Timings Lab* rises over Settings on the
  last-read ayah.
* The Lab edits the reciter currently selected in Settings and uses the
  shared `PlayerController`/`PlaybackService` (one-item playlist, ayah loop
  on by default in Listen mode), so caching, audio focus and speed behave
  exactly as in the reader.
* Reciters with no bundled timings at all (e.g. As-Sudais) work too — the
  Lab starts from zero marks and a re-sync pass creates the ayah's timings
  from scratch.

## Data model

### On-device override store

`filesDir/timing-overrides.json`, written atomically (tmp + rename). One
entry per touched `(reciterId, surahId, ayah)` holding the **whole**
replacement segments list — not a diff — in the exact wire shape the DB uses:
`[position_1based, start_ms, end_ms]`, sorted by `start_ms`, positions may
backtrack to encode repeats.

### Repository fusion

`QuranRepository.timings(reciterId, surahId)` is the single point where
timings leave the DB; overrides are fused there, so the reader and the Lab
read the same corrected numbers with no extra wiring:

```
db timings + audio onset  ─┐
TimingOverrides[key]      ─┴─►  Map<ayah, List<Segment>>  ─►  HighlightEngine
                                (edit wins; whole row rebased to MP3 clock)
```

The Lab's live preview additionally runs `HighlightEngine` directly over its
in-memory working copy, so you see edits *before* they're persisted.

## Screen anatomy

```
┌──────────────────────────────────────────────┐
│ ▾   Al-Baqarah · ‹ Ayah 14 ›          ⋯      │  header: close (lowers the
│         Mishary Alafasy · edited             │  sheet), reciter, overflow
├──────────────────────────────────────────────┤
│                                              │
│          ← the real AyahBlock →              │  live karaoke preview;
│    (reader rendering, live highlight,        │  tap word = select (Listen)
│     orange repeats, translation, …)          │  tap word = drop mark (Rec)
│                                              │
├──────────────────────────────────────────────┤
│  ──┼────╵──╵───╵────◆───╵──╵───────────────  │  timeline: gold/orange marks
│                                              │  + playhead, zooms on adjust
│  [ ◀  slide to adjust  ▶ ]                    │  slide bar (when selected):
│  الٓمٓ 6.4s   ＋ Add repeat   Reset   Delete   │  word · start · repeat ·
│                                              │  reset-to-default · delete
├──────────────────────────────────────────────┤
│  ▶   ⟲   1×                      [● Re-sync] │  transport: play, restart,
│                                              │  speed + record pill
│  "Slide to adjust · zooms in while you work" │  contextual hint line
│  This ayah · 3 on device · Submit this · all │  pending-corrections ribbon
└──────────────────────────────────────────────┘
```

While recording, the transport swaps to `[■ Done] [↺ 4s] [⌫ Undo]` with a
mark counter, and the hint line reads "Tap each word the moment it's
recited — tap earlier words again for repeats."

## Getting corrections upstream

The device is where corrections are *made*; GitHub is how they *travel*.
Free, no backend, no auth beyond the GitHub account:

1. **Submit this** (ribbon when the open ayah is corrected, or overflow
   *Submit this ayah*) builds a one-edit patch for the open verse only.
   **Submit all** / *Submit all corrections (N)* includes every on-device
   override. Both open a pre-filled `github.com/…/issues/new` deep-link:
   a human-readable summary, a verification checklist, and the patch as a
   fenced ```json``` block. **Copy this ayah patch** / **Copy all patch JSON**
   are the clipboard fallbacks (and cover very large patches that exceed
   URL limits). Prefer one-ayah submits when iterating verse-by-verse.
2. **Maintainer / agent: fix systematically first, verify with a unit test.**
   Do **not** paste every Lab issue straight into `tools/timing_overrides/`.
   Agent checklist (mandatory): [AGENTS.md — Landing Timings Lab / GitHub
   timing patches](../AGENTS.md#landing-timings-lab--github-timing-patches).

   Before classifying, **diff the Lab positions against raw qdc**
   (`tools/.cache/qdc_<id>.json`) and against the row **after**
   `clean_qdc_artifacts` and **after** `timing_repairs` — the shipped DB may
   already be wrong because a `drop` repair flattened a real re-say (#570).

   | Class | Where to fix | Unit test |
   |---|---|---|
   | Structural qdc noise (forward spikes, strays, split slivers, non-contiguous / gap phantoms) | `clean_qdc_artifacts` in `tools/build_db.py` | Add `tools/timing_patch_cases/<id>.json` — broken `input_*` + expected `expected_*` from the patch; run `python3 tools/test_build_db.py` |
   | Topology cannot distinguish a false loop from a genuine repeat | narrow typed operation under `tools/timing_corrections/` | `pipeline: timing_correction` case |
   | Drop repair that flattens a real span-repeat | `apply_timing_repairs` span-protect (and regenerate repairs) | `pipeline: erases_span_repeat` case in `timing_patch_cases/` |
   | Repair flattens a peer same-word re-say while fixing elsewhere | per-position `preserve_peer_repeats` | `pipeline: preserve_peer_repeats` case |
   | Restore invents a flush same-word pair (gap < 300 ms) | `collapse_invented_flush_repeats` in `apply_timing_repairs` | `pipeline: invented_flush_restore` case |
   | qdc has 1..n+1 because QAC glued ما (وَمَالِيَ) | `fold_qdc_fused_ma` in `adjust_qdc_segments` | `pipeline: adjust_qdc_segments` case |
   | Repeat-vs-split / CTC | `tools/timing_repairs/` generator | `~/qasr` tests + rebuild repairs |
   | Boundary displacement without a topology change | weighted qdc / quran-align evidence, then a surgical `kind: "boundary"` repair | `pipeline: boundary_repair` focused case |
   | Incomplete row or unsafe MP3 clock | source/class fix; finalizer completes, falls back, or withholds | completion/physics checks in `tools/test_build_db.py` |

   The patch case **is** the verification for systematic fixes: the Lab/GitHub
   payload supplies the expected shape; the cleaner must reproduce it. See
   [tools/timing_patch_cases/README.md](../tools/timing_patch_cases/README.md)
   and [tools/timing_overrides/README.md](../tools/timing_overrides/README.md).

   **Anti-pattern:** saving the issue fenced JSON under `timing_overrides/`
   without classifying. That was the first #570 attempt; #571 fixed the class
   (gap phantoms + span-protect) and deleted the override.
3. A JSON may be placed in `tools/timing_overrides/` temporarily to reproduce
   the report, but CI rejects committed one-off overrides. Pacing validation
   uses the actual karaoke window (`start_ms` to the next `start_ms`) and
   compares normalized word length. Candidate boundaries are checked against
   the bundled/CTC row and independent quran-align timing after removing the
   per-ayah median clock offset. Quran-align has weight 2 and the bundled row
   weight 1; ≤250 ms supports a boundary and >500 ms conflicts. Timestamps are
   never averaged, and one-pass evidence never judges repeat backtracks.
4. Typed corrections run before generated structural evidence. Repairs are
   rebased onto the latest source row. Only changed
   topology and its immediate neighbours use the repair clock; equal spans
   retain current source timings. Span protection rejects repairs that flatten
   a multi-word re-recitation; peer same-word re-says are restored per position
   so unrelated repairs still apply. Boundary repairs replace only their listed,
   uniquely occurring positions.
5. Run `python3 tools/test_build_db.py`, rebuild `quran.db`, bump
   `DB_FILE_NAME`, and commit the systematic code, regression case, and DB.
   Delete any local override JSON first.

The patch JSON shape (also the shape `tools/timing_overrides/*.json` accepts):

```json
{
  "schema": 1,
  "device": "Google/Pixel 8",
  "appVersion": "0.1",
  "edits": [
    {
      "reciterId": 1,
      "reciterSlug": "Alafasy_128kbps",
      "surahId": 2,
      "ayah": 14,
      "segments": [[7, 6400, 8212], [8, 8212, 9016]]
    }
  ]
}
```

## Layering & files

```
timingslab/
    TimingOverrides.kt      override store (load/save/clear), StateFlow<Map>
    TimingsLabViewModel.kt  Listen/Record state machine, live ActiveWord flow,
                            tap marks, nudges, auto-save, undo/rewind
    TimingsLabScreen.kt     header + AyahBlock stage + zoomable timeline + slide bar +
                            transport (paper styled, quietClickable, no ripple)
    TimingsPatch.kt         overrides → GitHub issue deep-link / clipboard
data/QuranRepository.kt     timings() fuses overrides over the DB
ui/reader/ReaderComponents  AyahBlock — reused as-is for the live preview
tools/build_db.py           cleaner + span-protect + repair rebase + validation
tools/timing_patch_cases/   unit-test fixtures: Lab/GitHub patches → pipeline expectations
tools/test_build_db.py      runs every timing_patch_cases/*.json (no network)
tools/timing_overrides/     local reproduction scratch; empty in commits
```

## Conventions kept

* No ripple / Material ink — `quietClickable`, content answers with motion.
* No new dependencies.
* Editing is fully offline; only Submit touches the network (it just opens a
  URL).
* The bundled `quran.db` stays read-only on device; corrections live in the
  override store until they come back bundled in the next DB.

## Non-goals (intentionally)

* No waveform rendering — the slide bar's audition loop ("hear it, watch it,
  nudge it") replaces visual waveform picking at these ayah lengths.
* No editing of ayah text / gloss / reciter metadata — build-time data.
* No multi-ayah batch view — corrections are per-ayah by nature; ‹ › steps
  between neighbours quickly.
* No automatic round-trip — corrections return to devices inside the next
  bundled DB, not via sync.
