# Connected Arabic Rendering

This note defines what "connected Arabic script" should mean in the Arabic-only
reader and how to build word-by-word recitation highlighting without breaking
Quranic shaping.

## Problem

The Arabic-only view currently tries to combine two goals:

- render each ayah as continuous Quranic Arabic, not separated word chips;
- fade/highlight the active word using the same recitation timing engine as the
  word-by-word view.

The second goal is non-negotiable. The app's core experience is not merely
"show which word is active"; it is the timed word-by-word fade that makes the
recitation feel written onto the page. Arabic-only rendering work may change
the technical mechanism of the fade, but it must not remove the animated
word-timed transition, replace it with static coloring, or promote an
artifact-free but lifeless renderer as acceptable.

The naive implementation conflicts with Arabic shaping:

- Rendering one `Text` per word preserves per-word animation, but it prevents
  the ayah from being shaped as one visual line/paragraph.
- Rendering one `Text` for the whole ayah preserves the paragraph, but per-word
  color/alpha spans can create artifacts or can cause the renderer to split the
  text into style runs.
- Semi-transparent glyphs are especially bad with Quranic marks and ligatures:
  overlapping strokes, connectors, and marks visually accumulate and make the
  "faded" text look dirty. The faded state must be an opaque color, not alpha.

## Update: one ink-wash bloom across every mode

The shipping Arabic-only / no-gloss view (`ResponsiveHafsAyah`, the single-`Text`
Hafs renderer) spreads the active word's reveal on the **same** ink-wash curve as
the word-by-word modes, without splitting the shaped run and without painting
onto neighbouring words:

- The ink-wash shape lives in one place — `letterFadeIn` / `shapedWordBloom` in
  `ui/theme/Fade.kt` (smootherstep feather, wash head travelling the word plus
  1.6× its width). Gloss mode applies `letterFadeIn` directly on each `WordUnit`.
- `ResponsiveHafsAyah` cannot use per-glyph `SpanStyle`s: Uthmanic Hafs joining
  and ligatures break when a word is split into separate colour runs (the
  "font flip" fixed in #133). It also cannot apply `letterFadeIn` to the whole
  ayah — that would wash every word. A separate overlay `Text` of one word is
  also wrong: the isolated string re-shapes and no longer matches the ayah's
  Hafs glyphs. And `drawText(color=…)` does **not** override existing span
  colours — so painting over a transparent active span stays invisible.
- Instead the base ayah keeps one contiguous colour span per word. First-pass
  active is full ink; `shapedWordBloom` covers that word's
  `getPathForRange` with paper and pulls the cover back on the `letterFadeIn`
  curve (CoverWithPaper). Correct harfs, directional bloom, no neighbour rect
  bleed. Upcoming dim is also draw-phase paper cover at full strength — the
  same cover while the ayah is recessed (`dimmed`) and while words are
  Upcoming — so unread ink does not change on ayah handoff. Never animate
  that cover up from 0 (that briefly showed the whole unread ayah at full ink).
- Repeat (orange) re-draws the same shaped glyphs, `SrcIn`-tints them orange,
  then applies the DstIn wash from resting 0 with dissolving `layerAlpha` —
  matching gloss mode's orange overlay without solid orange rectangles.

## What The Sources Say

Quran text sources expose multiple rendering families, and they are not
interchangeable:

- QUL lists Quran text as word-by-word, ayah-by-ayah, and page-by-page, with
  separate script families including Madani, QPC Hafs, Digital Khatt, and QPC
  glyph versions.
- QUL's QPC Hafs ayah-by-ayah resource is Unicode text that must be rendered
  with the QPC Hafs font.
- QUL's Digital Khatt v2 ayah-by-ayah resource is also Unicode text with a
  specialized `digitalkhatt-v2` font.
- QUL's glyph-based documentation says QPC glyph fonts represent each Quranic
  word as a unique glyph, and page-based QPC V2 uses 604 fonts, one per Madinah
  Mushaf page.
- Quran Foundation's font guide draws the same split: QPC Hafs is the simple
  Unicode path; QCF V2 is the glyph-based path for pixel-perfect Mushaf
  rendering and requires page/font handling.
- Arabic shaping is not just character drawing. A shaper applies contextual
  forms, required ligatures, optional ligatures, and mark positioning. HarfBuzz
  describes the shaped output as positioned glyphs with cluster mapping, not as
  a one-to-one character drawing operation.

## Important Clarification

Arabic letters normally connect inside words, not across spaces between words.
So if the desired result is "all words literally connected to each other across
the ayah", that is not normal Arabic text. What readers recognize as the
Madinah Mushaf look is usually not inter-word joining; it is high-quality
Quranic word shaping, spacing, and page-specific handwritten glyphs.

For our product language:

- **Connected Unicode ayah** means one `Text` paragraph using Unicode Quran text
  and a Quran font. Words are separated by spaces, but shaping within each word
  and mark placement are handled by the text engine.
- **Mushaf glyph ayah** means rendering QCF/QPC glyph codes where each Quranic
  word is a single font glyph. This is closest to Quran.com/Madinah Mushaf
  appearance.

## Current Status

**The QCF Mushaf renderer was built and then removed. Everything below the
"Recommended Direction" heading is a plan or a rejected design, not a
description of the app.** Verified against the tree 2026-07-24.

What actually ships in Arabic-only mode:

- One bundled Arabic face: `KFGQPC HAFS Uthmanic Script`
  (`res/font/hafs_uthmanic.ttf`, `HafsFontFamily` in `ui/theme/Type.kt`). No QCF
  page fonts are bundled.
- `ResponsiveHafsAyah` (`ui/reader/ReaderComponents.kt`) builds **one
  `AnnotatedString` per ayah** from `ayah.words[].arabic`, with one contiguous
  colour span per word — per-glyph spans break Uthmanic joining (#133).
- Highlight is the **soft directional ink wash**, not colour animation:
  `Modifier.shapedWordBloom` paints draw-phase paper covers and washes over the
  already-shaped run (`UpcomingDim` / `InkReveal` / `ColorReveal`). Glyphs stay
  full-opacity ink under a paper cover; see AGENTS.md invariant 7 and
  docs/INK_ENGINE.md.

What remains of the QCF work:

- **Data only.** `tools/build_db.py` still imports Mushaf page JSON from
  `zonetecde/mushaf-layout`, and the `words` table still carries `qcf_v2`,
  `qcf_page`, `qcf_line`, `qcf_span_end`. `QuranRepository.surahContent` still
  selects them into `Word`. **No UI reads any of those four fields** — they are
  dormant columns kept so the renderer could be revived without a data rebuild.
- `scripts/fetch_qcf_v2_fonts.sh` still exists but nothing depends on it, and
  the fonts it fetches are deliberately not committed.
- Removal history: `430da8dc` / #97 "Remove QCF Mushaf renderer and unbundle
  page fonts", then `3ed33b68` / #98 "Remove ArabicRenderMode toggle, always use
  ResponsiveHafs". The rejection reason is unchanged from the notes below — the
  glyph-span approach fought the ink wash and the font swap rearranged text
  mid-scroll.

Why the plan is kept: the connected-script goal was never achieved, only
deferred. If it is revisited, the data pipeline half is already done.

## Recommended Direction

For Arabic-only mode, stop trying to solve this with Compose styling over the
current Unicode ayah text. The accepted target is a modern Quran rendering
pipeline:

1. Continue with QCF V2 as the first implemented renderer.
2. Validate the result with screenshots against Quran.com/Madinah Mushaf.
3. Keep Digital Khatt v2 as a fallback candidate if QCF V2 page-font handling
   proves too heavy or visually unsuitable in Compose.
4. Resolve font/data licensing and offline packaging before release.

The likely target is QCF V2: each Quranic word is a single glyph, which maps
naturally to the existing word timing data. Digital Khatt v2 may be a more
modern Unicode-based option and should be evaluated before committing to the
final asset pipeline.

## High-Quality Implementation Plan

### Goal

Arabic-only mode should look like a real digital Mushaf/connected Quran text
renderer while preserving the app's signature word-timed follow-along behavior.
The renderer must:

- use Quran-specific script data and matching fonts;
- preserve authentic Quranic glyph shapes, spacing, and diacritics;
- map every rendered visual word back to the existing timing position;
- avoid semi-transparent Quran glyphs;
- keep animation smooth and typographically respectful.

### Phase 1: Build A Rendering Spike

Create a small isolated renderer spike before touching the production reader:

- Add a temporary debug preview screen or JVM/Android screenshot harness that
  renders the same 8-12 ayahs through candidate pipelines.
- Include short ayahs, long wrapping ayahs, ayahs crossing Mushaf pages, and
  examples with dense marks.
- Use fixed test cases, at minimum:
  - `1:1-7`
  - `23:2-3`
  - `73:4`
  - one long ayah that wraps on phone width
  - one ayah that crosses a page boundary
- Capture screenshots in light, dark, and royal green themes.

Acceptance criteria:

- The text must visibly match a Quran/Mushaf rendering, not generic Arabic
  words in a paragraph.
- Diacritics must not collide or drift.
- Words must map cleanly to timing positions.
- The animation must remain word-timed and visibly animated. It should be at
  least as pleasant as the current word-by-word Arabic view before it is
  considered for production.

Status: partially bypassed. The implementation went directly into the
Arabic-only branch, but the same screenshot acceptance criteria still apply
before treating it as release-ready.

### Phase 2: Evaluate Script/Font Candidates

Evaluate two candidates with real screenshots.

#### Candidate A: QCF/QPC V2 Glyphs

Use QUL's QPC V2 glyph word-by-word or ayah-by-ayah script and QPC V2 page
fonts.

Expected shape:

- Best match for Madinah Mushaf/Quran.com-style visual rendering.
- Each Quranic word is represented as a specialized glyph code.
- Page number determines which font is used.

Known costs:

- Requires packaging/loading many page fonts.
- Ayahs crossing pages need multiple font runs.
- Layout should become page/line-aware over time.

This is the likely final direction if the requirement is "looks like a real
Mushaf".

#### Candidate B: Digital Khatt V2

Use Digital Khatt v2 word-by-word or ayah-by-ayah script with the Digital Khatt
v2 font.

Expected shape:

- Modern Unicode-oriented Quranic font pipeline.
- Fewer font-management problems than QCF page fonts.
- Potentially better fit for responsive ayah-by-ayah layout.

Known risks:

- May not match the exact Madinah Mushaf/Quran.com look as closely as QCF V2.
- Word-timed animation may still require text-run styling or overlay work,
  depending on how the script maps to visual words.

This should be selected only if screenshots prove it gives the desired Quranic
appearance with a simpler implementation.

Decision gate:

- Do not proceed based on theory. Pick the renderer only after side-by-side
  screenshots in the app's actual Compose UI.

Status: QCF/QPC V2 selected for first implementation because it maps a visual
glyph token to Quran words and best matches the Mushaf-style target. Digital
Khatt v2 remains documented as the alternate candidate.

### Phase 3: Extend The Data Pipeline

After choosing the candidate, update `tools/build_db.py` and the SQLite schema.

For QCF/QPC V2, add:

- `words.qcf_v2_code TEXT NOT NULL DEFAULT ''`
- `words.qcf_v2_page INTEGER NOT NULL DEFAULT 0`
- `words.qcf_v2_line INTEGER NOT NULL DEFAULT 0`
- optionally `ayahs.qcf_v2_text TEXT`
- optionally `ayahs.qcf_v2_pages TEXT` for page-crossing ayahs

For Digital Khatt v2, add:

- `words.digital_khatt_v2 TEXT NOT NULL DEFAULT ''`
- optionally `ayahs.digital_khatt_v2_text TEXT`

Pipeline validation:

- Word count from the chosen Quran script must equal existing canonical word
  count for every ayah.
- Every timed word position must have a render token.
- Page/font metadata must be present for QCF V2.
- Build must fail on mismatches unless an explicit, logged exception exists.
- Add a small JSON/SQLite fixture test that validates representative ayahs.

Status: implemented for QCF V2.

- Added `qcf_v2`, `qcf_page`, `qcf_line`, and `qcf_span_end`.
- Rebuilt `data/quran.db`.
- Validated all 6,236 ayahs against QCF V2 layout data.
- Handled known many-to-many segmentation cases such as `لَّوۡمَا`,
  `بَعْدَ مَا`, and `إِلْ يَاسِينَ`.

### Phase 4: Font Packaging

For QCF/QPC V2:

- Download page fonts from the chosen source and pin their versions.
- Prefer assets over `res/font` if 604 files become awkward for Android
  resources.
- Implement a small `QuranFontProvider` that loads and caches typefaces by
  Mushaf page.
- Keep the current Hafs font for the existing word-by-word Arabic mode until
  the new renderer fully replaces it.

For Digital Khatt v2:

- Bundle the Digital Khatt v2 font.
- Confirm Android/Compose handles the font's shaping and variation settings
  correctly.
- Add a fallback path only for unsupported devices if testing finds issues.

Status: implemented as bundled QCF V2 font loading.

- Added `QcfFontProvider`.
- Added `scripts/fetch_qcf_v2_fonts.sh` to prefetch all 604 page fonts into
  `app/src/main/assets/qcf-v2-fonts`.
- Fonts are copied from assets into `noBackupFilesDir/qcf-v2-fonts` before
  loading with `Typeface.createFromFile`.
- Arabic-only no-gloss mode preloads the current surah's required page fonts
  before rendering QCF text.
- QCF glyphs do not fall back to Hafs Arabic text inside the visible list,
  avoiding font-swap reflow.

### Phase 5: Production Renderer

Build a new renderer component rather than continuing to mutate `AyahBlock`:

- `MushafArabicAyahText`
- input: `Ayah`, active word position, sweep duration, font scale, theme colors;
- output: a complete Arabic-only ayah rendering.

For QCF V2:

- Render one visual word per glyph token.
- Group words by page/font.
- Preserve page order and RTL flow.
- Use `FlowRow` only if it still matches the Mushaf visual goal; otherwise
  switch to page/line-aware layout using QCF line metadata.
- Treat each glyph as the timed visual word.

For Digital Khatt v2:

- Prefer one full ayah text run if word timing can be mapped without breaking
  shaping.
- If per-word rendering is required, verify that Digital Khatt still looks
  correct with word-level text units before selecting it.

Status: initial production renderer wired into Arabic-only no-gloss mode.

- Existing `AyahBlock` delegates that branch to QCF line text runs.
- One rendered QCF token can cover multiple timing word positions via
  `qcf_span_end`.
- Continuation canonical words with no render token are represented by the
  previous visual span.
- Each QCF line is one `Text` with an `AnnotatedString`; word-by-word fade is
  expressed as span color animation, not as separate word components.

### Phase 6: Animation Design

Do not reuse alpha on Quran glyphs.

Accepted states:

- Upcoming: flat opaque faded ink color.
- Active: full ink arriving on the current visual word.
- Recited: full ink or a slightly settled opaque color.
- Non-active ayahs: block-level dim is acceptable because it affects the whole
  ayah uniformly, not overlapping glyph internals.

For QCF V2, start with the robust version:

- whole-word color animation from faded opaque ink to full ink;
- duration = active timing segment adjusted by playback speed;
- easing = `InkSweepEasing`.

Only add directional clipping after the base glyph renderer is visually
approved. Since each QCF word is a single glyph, directional clipping is a
secondary polish pass, not the core implementation.

For Digital Khatt v2:

- use flat color transitions first;
- avoid per-character or per-span alpha;
- consider overlay clipping only if screenshots prove it looks good.

Status: implemented baseline.

- Upcoming QCF glyphs use an opaque faded ink color composited over the page
  background.
- Active glyphs animate to full ink with `InkSweepEasing`.
- No alpha is applied to individual QCF glyph text.
- Quran stop/pause signs are preserved because they remain embedded in each
  QCF glyph token inside the line-level text run.

### Phase 7: Interaction And Sync

Keep the existing timing engine:

- `HighlightEngine` continues to emit word position.
- Timing data remains keyed by `(surah, ayah, wordPosition)`.
- Renderer maps `wordPosition` to QCF/Digital Khatt render token.

Interactions:

- Tap a rendered word seeks to the timing segment if hit testing is reliable.
- Until reliable hit testing exists, keep ayah-level tap behavior in
  Arabic-only mode.
- Preserve active-word bring-into-view only after the new renderer exposes
  stable word bounds.

Status: implemented with existing sync.

- `HighlightEngine` still emits canonical word positions.
- QCF tokens use `position..qcfSpanEnd` to determine active/recited/upcoming
  state.
- Tap-to-word seek is preserved for rendered QCF tokens.

### Phase 8: QA And Acceptance

Required checks before merging production renderer:

- Screenshot comparison for selected ayahs across all themes.
- Phone-width and tablet-width screenshots.
- Playback test: active word advances correctly for Alafasy and Husary.
- Repeat/range repeat still works.
- No semi-transparent Quran text glyphs in active ayah rendering.
- No fallback font boxes or missing glyphs.
- No visible font swap during scroll/playback.
- APK size increase is measured and documented.

Definition of done:

- Arabic-only mode clearly uses the selected Quran script/font pipeline.
- The result looks materially closer to Quran.com/Madinah Mushaf than the
  current Unicode Hafs paragraph.
- Word timing remains accurate.
- Animation is smooth, restrained, and not worse than the current word-by-word
  Arabic view.

Status: pending visual QA.

## Rejected Short-Term Architecture

### Data

This approach was tested conceptually and partially implemented, then rejected
after visual review. It is retained here so we do not repeat the same mistake.

No database change required.

Use the existing fields:

- `ayah.text` for the full Unicode ayah string;
- `ayah.words[position].arabic` only for mapping timing position to word range;
- timing segments remain keyed by word position.

The active word range should be found in the full `ayah.text` by tokenizing on
Unicode whitespace. Do not reconstruct the displayed Arabic string from
`ayah.words`; the displayed string must be `ayah.text`.

### Rendering

Create a dedicated composable, e.g. `ConnectedArabicAyahText`.

Render three logical pieces:

1. Base ayah:
   - `Text(text = ayah.text + ayah mark)`
   - color = `fadedInk`, where `fadedInk` is `onBackground` composited over
     `background` at the desired faded strength;
   - no alpha and no per-word spans.

2. Recited words:
   - Optional. If we want recited words to stay full ink, draw the same full
     ayah text in full ink, clipped to the union of all word bounds whose
     position is less than the active word.
   - If this is too much clipping work, leave recited words full only after the
     current word advances by changing the base color policy later.

3. Active word:
   - Draw the same full ayah text again in full ink.
   - Clip the overlay to the active word bounding rectangle.
   - Animate a right-to-left reveal rectangle across that bounding rectangle
     using `InkSweepEasing` and the active word's timing duration.

The key is that every layer is a full shaped ayah paragraph. Clipping happens
after layout/drawing; it does not change the text run, font run, or shaping
input.

### Why Layered Clipping

Layered clipping looked acceptable on paper:

- faded text is a flat opaque color, so connectors and marks do not accumulate;
- the active word should appear to be written in with the same acceleration
  curve as the word-by-word view;
- no per-frame recomposition is required if the animation progress is read in
  the draw phase;
- the shaped text is not split into separate word composables or styled spans.

In practice, the motion looked worse than the original fade and still did not
produce the desired connected Quran script appearance.

### Word Bounds

Use `onTextLayout` / `TextLayoutResult` to map character offsets to bounds:

- Build `List<IntRange>` for word character ranges in `ayah.text`.
- For the active word range, get bounding boxes for the characters in that
  range and union them by line.
- A word can wrap, so represent bounds as one rectangle per line, not one global
  rectangle.
- For RTL reveal, animate the clip from the right edge toward the left edge for
  each rectangle.

This gives us a reliable overlay target without rendering words separately.

### Ayah Mark

Keep the ayah marker as part of the full text only if it uses the same font
without disrupting layout. If the mark needs gold styling, render it as a
separate trailing element or a separate overlay after the text. Do not introduce
per-word spans into the Quranic text just to color the marker.

## Long-Term Architecture: QCF V2

### Data Pipeline

Extend `tools/build_db.py` to import a glyph script source:

- source: QUL QPC V2 glyphs or Quran Foundation `code_v2` word fields;
- columns to add:
  - `words.qcf_v2_code TEXT`;
  - `words.qcf_page INTEGER`;
  - optionally `words.qcf_line INTEGER` for future page-aware layout;
  - optionally `ayahs.qcf_pages TEXT` for ayahs crossing page boundaries.

Validation:

- Word counts must match existing `words.position`.
- Every timing word position must have a glyph code.
- Log or fail on ayahs where QCF word count differs from our canonical timing
  segmentation.

### Fonts

QCF V2 is page-based. The renderer must load the font for each word's page.
This is different from the current single `hafs_uthmanic.ttf`.

Practical options:

- Bundle all needed QCF V2 page fonts in `res/font` or assets.
- If Android resource limits or APK size are an issue, package fonts as assets
  and load/cache `Typeface` by page.
- Segment an ayah into page runs when an ayah crosses a page boundary.

### Rendering

Render each QCF word glyph as the text unit for timing, but avoid chip-like
layout:

- One line/paragraph component per ayah.
- Words are glyph codes, not Unicode Arabic words.
- Use page font per run.
- Use flat opaque faded color.
- For the active word, a whole-glyph color tween is simple and robust.
- For a directional "ink spread", draw base faded glyphs and overlay full glyphs
  clipped by the active word's bounding rectangle. Because the glyph is a whole
  handwritten word, this is a visual reveal of the glyph, not a per-letter
  Unicode reveal.

## Implementation Checklist

Rejected short-term:

- Do not proceed with the layered clipping approach unless a later prototype
  proves it visually in screenshots first.

Future modern renderer:

- Evaluate QCF V2 against Digital Khatt v2 with real rendered screenshots.
- Add QCF V2 glyph data to the database.
- Add QCF page font packaging/loading.
- Build a QCF renderer that handles ayah page runs.
- Compare screenshots against Quran.com/QUL preview for several pages.
- Decide whether active QCF words use whole-glyph color tween or clipped
  directional reveal.

## Open Questions

- Do we want **QPC Hafs Unicode connected ayah** or **QCF V2 Mushaf glyph ayah**
  as the final Arabic-only look?
- Is the desired active effect a whole-word color fade, or a directional clipped
  reveal across the word shape?
- Are we willing to ship page-based fonts for a Mushaf-accurate renderer?
- Should Arabic-only mode preserve free-flowing responsive ayah layout, or move
  toward page/line-aware Mushaf layout?

## References

- QUL Quran text formats: https://qul.tarteel.ai/docs/quran-text
- QUL QPC Hafs ayah-by-ayah Unicode script:
  https://qul.tarteel.ai/resources/quran-script/86
- QUL Digital Khatt v2 ayah-by-ayah Unicode script:
  https://qul.tarteel.ai/resources/quran-script/85
- QUL glyph-based font model:
  https://qul.tarteel.ai/docs/glyph-based
- QUL QPC V2 page font:
  https://qul.tarteel.ai/resources/font/249
- Quran Foundation font rendering guide:
  https://api-docs.quran.foundation/docs/tutorials/fonts/font-rendering/
- Microsoft Arabic OpenType shaping overview:
  https://learn.microsoft.com/en-us/typography/script-development/arabic
- HarfBuzz shaping overview:
  https://harfbuzz.github.io/shaping-and-shape-plans.html
