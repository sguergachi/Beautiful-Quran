# Paper ink

On the **Paper** theme (Android 13+), a recited word is inked as wet
calligraphic ink rather than washed in: a front moves along the baseline in
reading order and lays a fine wet line down the core of every stroke it
passes, each stroke swells out to its edges, the fresh ink stands slightly
heavier and glossier, and then dries back to the page's ink over the glimmer's
dry-down (`glintFadeMs`). The letter ahead fades in so the edge is feathered.
English-only Scroll and the English leaf lead by exactly one printed letter,
stopping at the current word boundary. Scroll splits multi-word glosses into
printed-word fields on the same timing owner's clock, including repeats and
rewind evaporation; the next word receives no anticipatory fade. These word
bounds are cached with the paragraph. Arabic retains the Ink Lab lead dial.
An English word's lead starts at the unread strength and grows on that word's
own clock over its first letter slot. Enabling the full preview at progress
zero would flash part of its first letter as each word or gloss owner begins.
This entry ramp affects only the preview: the wet fill, timing windows,
continuous prefix, repeats and evaporation keep their existing clocks.
A repeat is laid in terracotta, held through its chain, and released to
charcoal by evaporation: thin edges clear first and thicker stroke interiors
linger, with a soft front over a 1,800 ms release. The dry black
underprint stays complete throughout; the orange film and any remaining wet
spread fade away together. If the first coat is still wet when the repeat
starts, its width, dark pigment and reflection remain ahead of the orange
front. That coat continues its existing dry-down independently; the repeat
replaces it as its own soft edge arrives. A pause within the chain still
holds its orange.
It is the Paper theme's counterpart of the Nightfall / Royal Green
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
| Clock | `InkMotion` | Letter progress (`paperSweepProgress`), wetness (`paperWetness`), repeat (`paperRepeatProgress` / `repeatAlpha`), and `paperAhead` for the lead fade. No clocks of its own. |

The first coat has a separate wetness state (`paperUnderWetness`) on the same
glimmer dry-down. Starting a repeat wets the new coat and leaves the original
clock running. The study keeps the same separation with its first-pass clock.
Both shaders reuse the existing material calculation for this underprint;
there is no extra pooling weight when the two coats overlap. An already dry
word keeps the ordinary charcoal underprint.

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
  Untouched shaped words use a single dim cover: adding a zero-progress reveal
  would square that faint alpha, then brighten still-unread English words when
  their shared timing owner enters the shader. The ordinary wash, liquid field,
  and missing-field fallback keep the same unread strength. Partial residuals
  and rewind evaporation retain their own soft reveals. Outgoing verse handoff
  can soften laid ink; untouched words keep their faint floor throughout it.
  On the English leaf, the base cover and liquid field share each printed
  word's character progress. A phrase-wide pixel cover cannot replace that
  word when its wet field retires. Shared-owner wetness never activates words
  ahead of the edge; one next word is warmed without drawing it. Seek recess
  applies to the prefix present on entry, so newly washed words do not flash
  back under the verse cover while it is still lifting.
  Published Translation Mushaf with an active steady-reading plan gives the
  fallback cover a feather of `1 / printedWordLength`, approximately one mean
  character advance at its zero cover padding. This shortens the broad
  whole-word entrance/settling breath while retaining the zero-progress unread
  floor. It changes fallback edge width, not the shader's per-letter lead-entry
  or arrival field. Glyph widths and punctuation still vary. Cards, Scroll and
  source-overlap/repeat fallback verses retain the default cover feather.
  Reordered English phrases keep material freshness on each printed word's
  presentation window, across Arabic owner boundaries. Pause/resume cannot
  re-wet a settled word. A generation/reciter-scoped 850 ms cosmetic departure
  releases old untouched words after a rewind even when the phrase's first
  Arabic owner stays recited; re-entered words immediately use the new wash.
  Replacing printed ink clears its captured pixel support completely before
  drawing the shader. A fractional erase mask would leave old antialiased ink
  beneath the new glyph and darken its edges. The visible glyph keeps its
  original fractional coverage, so clearing the old paint does not harden it.
- **A word is live** while it is active, still washing, wet, holding or
  releasing orange, or is the next word fading in (`InkMotion.paperInkLive`).
  Everything else is the surface's ordinary static ink.
- **Soft leading edge (AGENTS.md #7).** The front keeps a feathered edge and
  the lead fade; there is no whole-word pop.
- **Letter pacing uses letter positions.** The ink field supplies its own
  feather, so first and repeat passes use the tajweed curve before
  `Curve.forWash` extends travel for the paper-cover mask. Feeding that
  extended travel into the ink leaves a verse-closing letter behind during
  its hold. Both positions follow the same captured media window, including
  pauses, residuals, and wasl carry-in.
- **Connected openings continue one deposit.** During a nūn/tanwīn donor's
  tail, the next opening receives a gradually increasing fraction of its
  first letter slot, with the donor's wetness. The active word captures that
  same letter edge and continues it on the media clock. The wider cover-wash
  handoff is kept separately; reusing it for liquid ink jumps the opening
  forward. Cover-wash wasl overlays draw only in the fallback, so they cannot
  darken a letter on top of the liquid front. Regression: Alafasy 64:12
  `فَإِن → تَوَلَّيۡتُمۡ`, plus short and wrapped connections.
- **No climbing.** The swell is fed column by column along the baseline;
  leaning stems are fed from their baseline foot.
- **Rewind evaporates the forward ink.** When a rewind or earlier-word tap
  makes a visible word unread, its last deposit dissolves in place over 850 ms
  toward the ordinary faded ink. A partially filled word keeps its exact fill
  during that dissolve; the mask does not wipe backward. The selected word
  immediately follows its new media window. Re-entering a dissolving word
  cancels the departure, and initially unread words never flash full ink.
  Word-play retains every word before the tap, including while its stream
  buffers or its display clock settles behind the requested position. The
  tap publishes before playback starts and completes any old prefix wash;
  only the words ahead of the new target become unread.
  The verse just recited dissolves the same way as the next verse begins,
  over 1.8 s (`VERSE_DEPARTURE_MS`). The departing ink must be the settled
  deposit: the word's state reads unread a moment before the evaporation
  hears of the change, and recording that frame as "the ink being left" made
  the shader paint the faded ink at once, whatever the duration.
  `PaperInkEvaporation.sample` ignores an unread deposit while it still
  believes the word is read.
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

Pooling also takes a gentle optical correction below a 48 dp font:
`min(fontDp / 48, 1)^0.25`. At 24 dp this removes about 16% of the added wet
weight, and at 12 dp about 29%, while larger type keeps its existing relative
weight. The calculation uses dp so density alone cannot change the effect.
The reader and study use the same rule, including the study's smaller English
face. Only the added wet spread changes; dry glyphs and letter timing do not.

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

The extra wet spread has a restrained paper tooth: a smooth, fixed irregularity
in font coordinates, capped at 60% of the added width and 0.6 px at the
160 px reference font, whichever is smaller. At 80 px type and stock 0.28
pooling the maximum displacement is about 0.294 device pixels. This is five
times the earlier 0.12-reference-pixel amplitude, which the user found imperceptible.
It affects
only the pooled fringe, follows the same pattern on both coats, and vanishes
with wetness. Dry glyph coverage, arrival fields and the eight-tap antialiasing
stay intact. The study uses the same variation at its reference size.
Grain is evaluated only where it can change the pooled contour's antialiasing
band; the stroke interior and empty paper skip it. No extra texture is needed.

### Fibre fringe

Paper fibres shape the wet contact line. It is **on by default** (Ink Lab →
Ink → **Fibre fringe**); off draws the plain paper tooth above. Ink Lab
schema 9 moves saves from before the switch to on, because "off" was then the
experiment's default rather than a choice; an "off" saved since is kept.
Switching applies to the next frame. No word needs regenerating.

**The paper.** `PaperInkSubstrate` defines one seamless 720 × 720 tile in px
of the 160 px reference font: a single wicking byte per px, shipped
precomputed as `assets/paper-ink/paper.a8` (518 KB) and read once per process
on the paper-ink worker (words drawn before it lands use the plain tooth).
`PaperInkSubstrateTest` fails if the asset drifts from the generator;
regenerate it with `python3 tools/paper_ink_substrate.py --write`. The
shader derives pigment density as the same fibre's smoothstep of wicking. Broad pools, long curved fibres and a weak
finer grain each run on an integer rotation of the tile, (2,1), (3,1) and
(1,2), under a gentle warp. Every lattice period divides the tile along its
rotated axes, so it wraps without a seam, and none of the grains lines up
with the pixel grid (the first straight-fibre prototype read as digital).

**Each word reads its own patch.** The shader samples the tile at
`p / unit + fibreOffset`, repeating, once per pixel and only within a fibre's
reach of a contour; all eight AA taps share it, because the finest fibre spans
several pixels. The offset comes from the word's
`PaperInk.Source.placement`: the host paragraph's text and the word's start in
it for shaped text, and (surah, ayah, word position) for word nodes and the
Mushaf, so a word bleeds the same way each time scrolling recreates its node.
Without it every recurring word (English "the", "and"; ٱللَّهِ) bled in
the same shape whenever two were wet at once. Placement never enters the
field cache key: identical words still share one generated field.

**Soak, creep and dry.** At each existing AA tap the contact line spreads by
`min(weight × mix(0.45, 1.8, wick), reach) × filmWet × creep`:

- `reach` is the plain tooth's own envelope, `weight + min(0.6 weight,
  0.6 unit)`, so small type keeps the optical attenuation `weight` carries
  and the shader's early-out support is unchanged.
- `creep` grows from 0.4 to 1 with the square root of the deposit's age over
  about two letters: paper wicks quickly as ink lands, then slows. A word's
  last letters finish creeping as its film begins to dry.
- Only coverage beyond the original glyph receives `mix(0.5, 1, density)`
  pigment, tapering to 35% at the outer contact line instead of ending each
  fibre in a flat tooth.
- While the film dries, pigment gathers at the contact line for a moment (a
  coffee ring). `dryingRing` rises over the first third of the dry-down and
  is gone before the end. It thickens the fringe's outer margin and holds wet
  pigment along the glyph's own edge within two reference px.

Original coverage is a floor, so no glyph is eroded. The ring is zero while
wet and once dry. The unchanged loading/entry, repeat-undercoat and evaporation
masks compose this material once. Exact dry, zero-pooling and unread
endpoints, one-letter English entry, normals, reflection, timing and semantic
owners are preserved. The fringe is an artistic transient film: real absorbed
pigment would not retract to the pristine font on drying. The baked study
activity keeps its existing reference material.

**Cost.** One 518 KB alpha tile per process, outside the 32 MiB word cache,
read in about 5 ms; per-word fields carry no material bitmap. At most one
paper read per pixel. See [PERFORMANCE.md](PERFORMANCE.md) for the trace.

`tools/compare_paper_ink_fringe.py OUTPUT` saves aligned, unamplified paints
of the fringe, the plain tooth, a coverage-only control and a smooth control
through the fill and the dry-down, including the same word at a second
placement and at 24 px. It renders the production shader with the shipped
tile (`tools/paper_ink_substrate.py`), at pooling 0.28 with the
optical correction at density 3/fontScale 1. These are software paints, not
Android captures.

## Tuning

Ink Lab → **Ink**: **Paper ink** (on), **Flowing ink** (off = Inside out),
**Fibre fringe** (on), **Ink pooling** 0.28, **Reflection** 0.5, **Lead fade** 0.5
(one Arabic preview letter), and **Dry-down ms** 1,000. English retains its
fixed one-letter preview within the current word. Dry-down edits the same
saved value as Sweep → **Glitter time ms**, shared by glimmer and Paper ink.
The controls apply live, persist across restarts, and participate in Reset and
Copy values. Saved profiles using the previous stock pooling of 0.25 adopt
0.28 once; custom pooling values remain. The Softness control and its saved
setting have been removed; live readers use the fixed focused reflection.
Playback timing is unchanged. Paper's repeat ink is the study's terracotta
`#C25F3B`; the shader dries it toward warm paper.

## Tests

- `PaperInkFieldTest` — the Kotlin generator against the Python reference on
  real Hafs words (`tools/export_paper_ink_fixture.py`), plus upright stems,
  leaning stems, symmetric swell and letter slots.
- `PaperInkOutlineTest` — fractional circles and angled strokes against
  independent analytic shapes, plus saddle corners and uniform masks.
- `PaperInkPoolingTest` — gentle relief at smaller sizes, density-independent
  physical weight, and a proportional pooling dial.
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
  Repeat-release checks cover thin edges clearing before thicker interiors,
  a monotone fade, complete underprint, and exact dry ink even when the
  departing film is still wet. The study shader has the same endpoint gate.
  English entry checks compare the first active frame to the unread word,
  then require a continuous soft fill in both spread methods, including
  partial undercoats and interrupted evaporation. Disabling the entry ramp
  reproduces the leading-letter flash in the same production shader.
  Organic pooling is compared with a smooth control and a bounded spread
  envelope; dry, zero-pooling, unread and first-entry pixels remain exact.
  Stock-pooling Arabic and English controls also require at least three times
  the edge-coverage RMS contrast of the superseded faint version. This locks
  paint strength, not a universal perceptual threshold.
  The opt-in fringe also exercises entry, repeat undercoats, evaporation and
  pixel-area reconstruction; bounds tests require preserved base coverage,
  connected support, deterministic output and a genuine density ablation.
- `PaperInkSubstrateTest` — deterministic opaque data, correlated preferences,
  interpolation continuity and invalid-scale rejection. Store/Copy and English
  warm-fragment tests preserve the material choice without changing clocks.
- `PaperInkEvaporationTest` — unread entry, settled and partial/repeat
  departures, interrupted fades, re-entry and retained reciter repeats. The
  Skia gate also checks that evaporating first/repeat ink reaches the exact
  unread colour and coverage without moving its captured fill.
- `PaperInkMotionTest`, `TajweedPacingTest` — the closing nūn of 7:31 receives
  its letter position during the final hold on first and repeat passes. The
  Skia gate checks complete, continuous coverage without retained ink.

## Not yet

- Web: follows in its own PR (WebGL per word), as AGENTS.md #7 requires.
- Basmalah sites.
- The Paper ink study still renders its baked maps rather than this pipeline.
