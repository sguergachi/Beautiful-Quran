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
- `tools/test_paper_ink_study.py`, `tools/test_paper_ink_shader.py` — the
  reference geometry and the study shader.

## Not yet

- Web: follows in its own PR (WebGL per word), as AGENTS.md #7 requires.
- Basmalah sites.
- The Paper ink study still renders its baked maps rather than this pipeline.
