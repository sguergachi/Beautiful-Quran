# Fresh-ink glimmer

The Nightfall and Royal Green readers give newly formed words a brief
white-gold glimmer. It should feel as though the ink catches light while it is
still wet: a restrained luminous tint inside the glyphs and a soft halo
immediately outside their outline. This is not a spotlight, radial bloom, or
rectangular glow layer.

This document is the canonical behavior and rendering specification for the
glimmer on Android and web. Repeat-chain timing and orange ink are documented
in [REPEAT_HIGHLIGHTING.md](REPEAT_HIGHLIGHTING.md); the shared word-state and
motion policy lives in [INK_ENGINE.md](INK_ENGINE.md).

## When it appears

The word half of the gate is:

```text
state == Active
```

The theme half requires the white-gold glimmer accent (`#F8E9BE`) provided by
Nightfall and Royal Green. Paper does not define that accent, so it does not
glimmer.

This produces three deliberate cases:

| Event | Glimmer? | Reason |
|---|---:|---|
| A genuinely new word becomes active | Yes | Fresh ink is forming for the first time. |
| A word becomes active because the reciter repeats it | Yes, every repeat event | The repeated utterance is a new performance event even though its base ink is already revealed. |
| A previously recited word becomes active after a seek / word tap | Yes | Replay re-runs the ink wash; the sheen rides that wash so the word reads as being recited again. |

There is no seek/replay suppression: tapping a word must restart the directional
ink animation, so being Active *is* the whole word-side gate. (Android once had a
`startRevealed(previous, current)` predicate for this; it had degenerated to a
constant `false` — sampling jitter is filtered upstream by `HighlightClock` — and
was removed along with its Compose wrapper. Web matches that collapse:
`glinting(state) = state == Active`.)

## Motion and layer order

The glimmer has no independent sweep. It rides the active word's existing
directional wash, with the same duration, easing, direction, and feather:

1. The normal base ink remains the source of legibility.
2. During a repeat, the glimmer itself uses the dark terracotta repeat ink;
   white gold remains exclusive to first-pass words.
3. A glyph-shaped white-gold halo forms behind the visible ink.
4. A restrained white-gold tint forms inside the glyphs above the other ink.
5. At the end of the word wash, tint and halo reach their peak together.
6. When the voice moves on, the extra glimmer recedes over `glintFadeMs` while
   the identical terracotta repeat ink remains intact underneath.

The glimmer's colour is latched when it forms and held for its full rendered
lifetime. Chain release may change a repeat word back to a normal recited state
while its glimmer is still fading; that state change must not recolour the
drying shimmer white-gold. Conversely, when a single word moves directly from
its first utterance into a repeat, its formed white-gold glimmer must not snap
to terracotta or disappear. It recedes with the incoming directional repeat
wash, so the orange ink visibly replaces the drying gold. A repeat word whose
glimmer forms as a separate event starts terracotta as usual. Once replaced,
the first-pass glimmer stays suppressed through chain release; it must not
reappear above and hide the orange ink's `repeatFadeOutMs` dry-down.

The result should read as light forming with the word, peaking when the word is
complete, then drying away. It must never replace the soft leading edge of the
karaoke wash or turn the word into a whole-opacity pop.

### Tarjīʿ resonance

**Tarjīʿ (ترجيع)** is the repeated reverberation of the voice on a single
held note — the pulsing a reciter carries into a long madd or waqf sustain.
The ḥadīth of Ibn Mughaffal (Bukhārī 5048) describes the Prophet's ﷺ
recitation with exactly this word: يُرَجِّعُ — "his voice reverberated".

The glint **listens for it directly**. `VoiceTapAudioProcessor` mirrors the
player's own PCM (no mic permission, no Visualizer — which also means it
works on every output route and emulator), and `playback/Tarji` — a pure,
unit-tested DSP core — tracks a **single held note** (voiced, pitch-stable
≥ ~0.4 s) and scans its amplitude envelope for an oscillation in the tarjīʿ
band (~3–9.5 Hz). The moment the reverberation is detected, the shimmer
starts — on any held note, mid-word or verse-closing — and rides the
*measured* oscillation itself, phase-lead-compensated, so the gold swells
and rests exactly with the reciter's voice (`InkEngine.GLINT_RESONANCE_DEPTH`
~42 %). Detection ramps in and out under an attack/release envelope, so no
edge ever pops.

A free-running carrier at ~5.5 Hz (`GLINT_RESONANCE_SINE` ~22 %) remains as
a gentle floor for **steady** holds inside the waqf window
(`TajweedPacing.Curve.inWaqfHold(t)`, from the closing word's first long
hold park through the end of the sweep), cross-fading out as the voice
signal locks on. The gate hard-closes at handoff: the dry-down dissolve
after the voice moves on is never modulated, and repeat terracotta glint is
never modulated either.

## Visual target

The halo is a slight, realistically blurred extension of the glyph silhouette:

- white gold, not saturated yellow or white;
- visible against Nightfall, but subordinate to the text;
- tight enough that neighbouring words do not share a light field;
- soft at the outside edge, with no hard stroke;
- strongest only at the completed-word peak;
- fully receded with the glimmer fade.

Do not implement the glimmer with a radial gradient, ellipse, background,
rounded rectangle, box shadow, or a blurred rectangular element. Those
techniques create a spotlight or reveal the word's layout bounds during the
glint. The blur must originate from the glyph shape itself.

## Android rendering

Android has two text paths, and both preserve their existing shaping strategy.

### Per-word text

`HighlightLayeredText` renders a duplicate `Text` behind the ink with a
glyph-derived `Shadow`, nearly transparent fill, and the configured halo color,
strength, and blur. The shared per-word `InkMotion` owns glimmer formation and
dry-down; `layeredGlintHalo` applies its alpha at draw time. A second duplicate
above the normal/repeat ink carries the
directionally masked white-gold tint.

Each duplicate keeps its natural text measurement inside a match-parent wrapper.
Do not force the duplicate `Text` itself to `matchParentSize`: exact constraints
can reshape or place Arabic a pixel differently from the base and can clip
overhanging marks while the wash mask is active. The wrapper is layout-neutral,
so mounting or removing a glimmer cannot move the word or its neighbours. Base
and duplicate words use single-line `TextOverflow.Visible`, and glimmer alpha is
applied in an expanded draw layer instead of a word-sized `graphicsLayer`; both
are required for terminal strokes that extend past their advance width.

The halo is drawn inside an offscreen layer expanded by 14 dp on every side.
That bleed is intentional: the shadow may paint outside the word's measured
bounds, and restricting the layer to those bounds creates a visible box edge.

### Shaped Arabic and English lines

`ShapedWordBloom.ColorReveal` keeps the paragraph's existing shaping and line
breaks. `addShapedInkMotionBlooms` maps the same `InkMotion` used by layered
words onto the English/Hafs ranges; it owns no clocks or release logic.
Compose's range path encloses a selection; it is not the outline of
the glyphs and must never be painted as the halo. The renderer clips the
already-laid-out paragraph to that range, extracts its glyph alpha into a
small cached mask, and blurs that mask. The crisp tint still uses the existing
directional color-reveal mask.

The cache is local to the laid-out line and keeps only the most recent masks,
so a draw frame only recolours one small bitmap while the glimmer animates.
The extracted mask includes the blur's own expansion, preventing the halo from
clipping into a rectangle even at the Ink Lab's maximum radius.

Never replace the shaped path with a word-sized radial field or alpha-dim the
Hafs glyphs. Arabic glyphs stay opaque; the established paper-cover wash remains
responsible for reveal fidelity.

## Web rendering

English `WordUnit` mounts two temporary duplicates while a glimmer is active:

- the crisp `.word-glint-overlay`, revealed by the same directional mask as
  the ink wash;
- `.word-glint-halo`, containing the same text with transparent fill and two
  small `text-shadow` radii that follow the glyph silhouette.

Arabic `WordUnit` and `HafsWord` mount only the transparent-fill halo. Chromium
can rasterize a second Hafs fill differently at an overhanging terminal, making
the original ink look trimmed until that tint fades. The base Arabic glyph is
therefore the only filled glyph on web. `runGlintWashIn` grows the halo opacity
with the wash; English also masks in its crisp overlay. `runGlintFadeOut`
recedes the mounted layers over `glintFadeMs` and the component unmounts them
when finished.

The halo element deliberately has no `background`, `box-shadow`, or `filter`.
The Arabic halo is an enlarged wrapper containing an unmasked glyph child.
Equal negative inset and padding keep the child aligned with the base word
while giving Hafs marks and left-terminal strokes room to paint beyond their
layout advance. It remains beneath the existing paper cover and fades up as
that proven ink wash reveals it. English keeps the glimmer mask because its ink
reveal masks the glyph directly rather than using an Arabic paper cover. The
shipped web halo uses two shadows: 36% at `0.055em` and 18% at `0.15em`; English
also uses a 62% glint tint. These values are fixed on web; the live controls
below tune the Android renderer only.

### Web Hafs clipping: what to trust

Matching `getBoundingClientRect()` values prove layout alignment, not identical
font paint. Hafs terminals and marks can extend beyond their advance width, and
Chromium may rasterize the same Arabic text differently once a duplicate is
masked, faded, or placed on its own compositing surface. Increasing the
duplicate's padding can hide one example without making that second fill safe.

A decisive diagnostic is the lifetime of the defect: if the missing terminal
returns exactly when the glimmer layer unmounts, the base glyph is intact and
the temporary layer is the problem. Inspect the real failing word while the
effect is active; do not infer glyph paint bounds from a synthetic box or a
resting frame.

The web contract is therefore:

- Arabic has one authoritative filled glyph—the base ink layer.
- Glimmer may duplicate its shape only with transparent fill and `text-shadow`.
- The halo wrapper may be oversized, but it must not own an Arabic reveal mask.
- The existing paper-cover wash remains the only directional Arabic boundary.
- Hover rules must target the direct base child so they cannot recolor the halo.

For regression review, use overhanging left terminals such as `رَبِّ` and
`مَٰلِكِ`. Compare formation, peak, fade, and the frame after unmount; confirm
there is no filled `.hafs-glint-overlay`, no box edge, and no shift or missing
ink at any point.

## Ink Lab controls

Enable developer mode in Settings, turn on **Ink Lab overlay**, start a
Nightfall recitation, and expand **Ink Lab**. These controls are Android-only
auditioning values that persist on device until **Reset** (so multi-session
tuning does not start from zero each launch). **Reset** restores the shipped
defaults and **Copy values** puts a paste-ready `InkEngine.Tuning(…)` constructor
on the clipboard (also written to Logcat under tag `InkLab`). Turn **Focus** off
(next to Copy values) to freeze auto-home and word-band follow while you pan
and inspect ink; the toggle is session-only and not part of `Tuning`.

| Slider | `InkEngine.Tuning` | Shipped value | Range | Effect |
|---|---|---:|---:|---|
| Repeat ink | `repeatInkAlpha` | 1.0 | 0.2–1 | Peak strength of the orange repeat overlay (and search-hit flash). Hue stays theme-owned (`QuranAccents.repeatInk`). |
| Glitter time ms | `glintFadeMs` | 1000 ms | 100–2400 ms | How long tint and halo recede after the word stops glimmering. |
| Glint tint | `glintTintAlpha` | 0.62 | 0–1 | Peak strength of the crisp white-gold ink tint. |
| Halo strength | `glintGlowAlpha` | 0.49 | 0–1 | Peak opacity of the blurred outline. |
| Halo blur | `glintGlowRadius` | 10 | 0–10 | Renderer blur radius around the glyph outline; it is not a word-relative radial size. |
| Waqf glint shimmer (Tajweed tab) | `glintResonance` | on | toggle | Soft white-gold breath on long verse-closing parks (voice energy + free carrier). |
| Resonance strength (Tajweed tab) | `glintResonanceDepth` | 0.42 | 0–1 | How hard the gold swings with the held note; scales the free-running floor with it. |

The scalar maps to Compose `Shadow.blurRadius` for per-word text and to dp for
the shaped-path `BlurMaskFilter`; use the visual result, not physical units, as
the tuning contract.

Tune strength before blur. A large blur can look like ambient fog even at low
opacity; a small blur at high opacity can look like a hard outline. Judge the
peak and the fade, not only a paused frame.

## Review and visual verification

Check a real Arabic word in Nightfall at three points:

| Moment | What to verify |
|---|---|
| Formation, about 35% | Light is beginning to follow the formed glyphs; unrevealed space has no rectangular or radial field. |
| Peak, 100% | The halo is visible but tight, the tint is white gold, and neighbouring words remain separate. |
| Fade, about 20% remaining | Tint and halo recede together without a lingering box edge or sudden cutoff. |

Also verify a first-pass word, a multi-word repeat chain, a same-word repeat,
Arabic + gloss, English lyric, and Arabic-only Hafs rendering. Inspect the web
halo's computed styles: `background`, `box-shadow`, and `filter` must remain
absent. On Android, test the maximum Ink Lab blur as an artifact stress case,
then return to the shipped defaults for aesthetic review.

Run the normal gates after a change:

```bash
./gradlew testDebugUnitTest
cd web
npm test -- --run
npm run build
```

The automated engine tests prove the first-pass/repeat/seek policy. They do not
prove paint quality; screenshots at formation, peak, and fade remain required
for any halo rendering change.
