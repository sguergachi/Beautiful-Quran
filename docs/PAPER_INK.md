# Paper ink

On the **Paper** theme (Android 13+), a recited word is inked as wet
calligraphic ink rather than washed in: a front moves along the baseline in
reading order and lays a fine wet line down the core of every stroke it
passes, each stroke swells out to its edges, the fresh ink stands slightly
heavier and glossier, and then dries back to the page's ink over the glimmer's
dry-down (`glintFadeMs`). The letter ahead fades in so the edge is feathered.
A repeat is laid in terracotta, held through its chain, and released to
charcoal. It is the Paper theme's counterpart of the Nightfall / Royal Green
glimmer ([GLIMMER.md](GLIMMER.md)), designed and tuned in the Paper ink study
(Settings → Developer → Paper ink study; GLIMMER.md "Paper ink experiment").

Nightfall and Royal Green are unchanged. Android 11–12 and Ink Lab "Paper ink"
off keep the paper-cover wash.

## One pipeline, every surface

All reader surfaces draw through the same code; a surface only says where a
word's glyphs are.

| Piece | Where | Job |
|---|---|---|
| Source | `PaperInk.Source` | One word laid out **alone** in the surface's own font and style, and the host position of that layout's origin. |
| Field generator | `PaperInkField` (pure Kotlin) | From the word's 2× glyph mask: signed outline distance, the swelling-channel arrival for both spread methods, the liquid film normal. Port of `tools/build_paper_ink_study.py`. |
| Cache | `PaperInk.word` | Captures the mask on the UI thread (rationed to 3 words a frame), generates on one background thread, keeps ~32 MB of words keyed by layout and sub-pixel phase. |
| Draw | `PaperInk.drawWord` + `assets/paper-ink/ink.agsl` | Erases the word's printed pixels from the surface's layer, then draws the ink. |
| Clock | `InkMotion` | Progress (tajweed-paced), wetness (`paperWetness`), repeat (`repeatProgress` / `repeatAlpha`), and `paperAhead` for the lead fade. No clocks of its own. |

Surface adapters:

- **Shared lines** — scroll Arabic-only and English-only paragraphs, the
  mushaf's fallback lines, the English leaf: `ShapedWordBloom.PaperInk` inside
  `Modifier.shapedWordBloom`. The source is `WordInkCache`'s lone layout, the
  same one the orange tint already trusts. Built by `buildShapedBlooms` (via
  `WordInkPalette.paperInk`) and `addEnglishPaperInk`.
- **A word per node** — scroll gloss tiles (`HighlightLayeredText`) and the
  mushaf's QCF words (`MushafQcfWord`): `PaperInkNode.paperInkBase`. The node's
  own text is the word; while live, the node draws the ink instead of its wash
  chain, and its orange overlay stands aside (`hiddenWhilePaperInk`). QCF
  glyph codes carry no letters, so the Hafs word's letter count is passed in.

The basmalah sites (drawable and QCF glyph) keep their wash for now.

## Rules the pipeline keeps

- **No word is skipped (AGENTS.md #10).** Until a word's field exists it draws
  its surface's ordinary blooms. A word that began washing in that fallback
  (past 12% of its wash, or wet) finishes there instead of changing hands
  mid-letter.
- **No pop at hand-over.** The shader's resting base is the word's own glyph
  coverage captured at device resolution, and its colours are the theme's:
  a dried word equals the printed glyph, and an unread word equals the
  reader's upcoming ink (`InkEngine.State.Upcoming.inkAlpha()`), to within
  8-bit rounding. A word therefore leaves the pipeline the frame it is static.
- **A word is live** while it is active, still washing, wet, holding or
  releasing orange, or is the next word fading in (`InkMotion.paperInkLive`).
  Everything else is the surface's ordinary static ink.
- **Soft leading edge (AGENTS.md #7).** The front keeps a feathered edge and
  the lead fade; there is no whole-word pop.
- **No climbing.** The swell is fed column by column along the baseline;
  leaning stems are fed from their baseline foot.
- **Rewind evaporates the forward ink.** When a rewind or earlier-word tap
  makes a visible word unread, its last deposit dissolves in place over 850 ms
  toward the ordinary faded ink. A partially filled word keeps its exact fill
  during that dissolve; the mask does not wipe backward. The selected word
  immediately follows its new media window. Re-entering a dissolving word
  cancels the departure, and initially unread words never flash full ink.
  Reciter repeats retain their high-water prefix and keep the orange chain.
  The clock is read only while drawing. English leaf words retain their own
  positions within the Arabic timing band, and a missing liquid field keeps a
  fading paper-cover fallback.

## Geometry

The swelling channel (see also GLIMMER.md):

1. Letter slots: each letter in text order (`TajweedPacing.letterOffsets` for
   Arabic, letters and digits for English) gets an equal share of the sweep at
   the x where the layout drew it; a Gaussian of 0.45 letter widths smooths
   the slot map.
2. Feed: each ink pixel takes the sweep at its column, or at its baseline foot
   (a vertical-preferring ink path; the projection fades past a stem's lean).
3. Swell: rims lag the core by `lag / letters × (1 − depth / local half-width)^shape`
   — Inside out 1.6 / 0.7, Flowing ink 1 / 1.
4. Paper takes its nearest ink's arrival; the word window is
   `0.12d + (1 − 0.24d) × wave` with `d = 2.8 / (letters + 1.8)`.

Textures: `field` at 2× (R Inside out, G Flowing, B distance as 128 + 16 px),
`normals` at 1× (R/G film normal, B exact glyph coverage). Shader constants
were tuned at a 160 px font; `unit = fontPx / 160` scales them.

The reader's wet outline keeps the source font's fractional edge coverage.
`PaperInkOutline` reconstructs contour segments between neighbouring coverage
samples, then measures distance to those segments around the nearest boundary
cell. This avoids the small bumps made by expanding a thresholded bitmap.
The film normals use the reconstructed outline too. This work happens once on
the background generator, with the same textures and cache limit. Arrival
fields keep their existing depth solver, so letter timing and spread stay
unchanged. The study's baked 4× maps are unchanged.

Wet ink uses eight stratified subpixel samples, with a distinct position along
both axes in each sample and a coverage filter scaled to the sample area.
The earlier 2×2 grid had only two horizontal positions and missed narrow
reflections on nearly vertical alifs. The distributed pattern antialiases the
expanded outline, swelling front and focused reflection together. Each sample
keeps the original glyph coverage at the pixel centre, so the printed base
stays crisp. Fully dry words use
the original single-sample path, preserving exact glyph coverage and the
handoff to static text. Empty paper beyond the wet spread is skipped. The
study uses the same sampling scheme; no additional textures or mask generation
are needed.

## Tuning

Ink Lab → Sweep: **Paper ink** (on), **Flowing ink** (off = Inside out),
**Ink pooling** 0.25, **Reflection** 0.5, **Softness** 0, **Lead fade** 0.5
(one letter). **Glitter time ms** is the dry-down for both the glimmer and the
paper ink. Paper's repeat ink is the study's terracotta `#C25F3B`; the shader
dries it toward warm paper.

## Tests

- `PaperInkFieldTest` — the Kotlin generator against the Python reference on
  real Hafs words (`tools/export_paper_ink_fixture.py`), plus upright stems,
  leaning stems, symmetric swell and letter slots.
- `PaperInkOutlineTest` — fractional circles and angled strokes against
  independent analytic shapes, plus saddle corners and uniform masks.
- `tools/test_paper_ink_outline.py` — the pictured 6:150 word 16 against a
  freshly rasterized 16× font outline at four reading sizes and both pooling
  settings. This tests geometry independently of the low-resolution field.
  `tools/paper_ink_outline.py` is the contour reference used by the Kotlin
  parity fixtures and production-shader tests.
- `tools/test_paper_ink_study.py`, `tools/test_paper_ink_shader.py` — the
  reference geometry and the study shader.
- `tools/test_paper_ink_antialiasing.py` — direct Skia renders of the production
  shader on Arabic and English at reading sizes. Compares wet coverage against
  an 8× pixel-area reference in both modes and passes, at default/full pooling;
  checks exact dry, held-repeat and unread glyph coverage. The pictured alif
  in أُمِرُوٓاْ is also checked at three sizes, fractional screen positions and
  three fill phases against the previous four-tap pattern and an 8× reference.
- `PaperInkEvaporationTest` — unread entry, settled and partial/repeat
  departures, interrupted fades, re-entry and retained reciter repeats. The
  Skia gate also checks that evaporating first/repeat ink reaches the exact
  unread colour and coverage without moving its captured fill.

## Not yet

- Web: follows in its own PR (WebGL per word), as AGENTS.md #7 requires.
- Basmalah sites.
- The Paper ink study still renders its baked maps rather than this pipeline.
