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
3. A glyph-shaped white-gold halo forms behind the visible ink **on the same
   directional wash as the tint** — during the bloom, not only after it.
4. A restrained white-gold tint forms inside the glyphs above the other ink,
   also wash-masked letter by letter.
5. Tint and halo peak together when the wash completes; tarjīʿ may already be
   pulsing the revealed portion mid-hold while the wash is still open.
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
≥ ~0.4 s) and scans its amplitude envelope for a periodic oscillation in the
tarjīʿ band (~1.5–10 Hz, tunable up to 50 Hz via the Ink Lab's
**Tarjīʿ max rate**). The pulse only answers on words carrying a **strong
tajweed hold of their own** — a long madd, a ghunnah (the shadda نّ of
ٱلنَّارِ), or the verse-closing waqf (`TajweedPacing.Curve.hasStrongHold`;
a wasl entry alone sustains the previous word's nūn and never qualifies) —
and starts the moment the reverberation is detected there.

The **wet-ink glint always rides the wash** for the whole Active word —
mid-bloom and long waqf parks included. **Tarjīʿ turns it on and off**: the
glimmer itself extinguishes at pulse troughs and lights with the crests —
the voice's reverberation is the glimmer. The attack/release ramp
(`tremoloGain`) blends the transitions so no detection edge pops.

The shimmer is the **build to the climax**: it engages as the hold's
reverberation starts (the hold gate is short, ~300 ms, so the build-up
shows before the peak) and rides the whole crescent. A **deep-AM
fallback** keeps it locked through the irregular crescendo: real builds
(Alafasy 1:7) pulse unevenly and their envelope autocorrelation collapses
to ~0.1–0.3 at the loudest point, so a strong amplitude modulation
(≥ ~6% at an in-band rate, ceiling-cheat guard applied) is treated as
self-evidently vibrating; the autocorr still rejects shallow noise swells.
The pulse rate is **tracked with lag hysteresis**, so it stays locked to
the voice's period instead of flapping across the autocorr's broad peak.

The effect stops the moment the voice releases. The detector tracks the
hold's envelope with a fast level EMA; once it sits below ~0.52 of the
hold's own peak for two hops, the climactic reverberation is over. The
shimmer **settles with the voice**: its strength follows the envelope's
remaining intensity — full while the swell is strong (≥ 0.75 of the
hold's peak) and fading as the voice dies toward the gate — so the word's
end reads as the effect drying, never as a full-strength pulse past the
climax. The dry-down is ~60 ms and the pulse signal is gain-damped, so the
decaying tail after a waqf hold never flickers, and a deep vibrato never
trips its own gate (the troughs stay above the gate). Mid-hold detection
lulls are bridged by the slower release so the hold breathes without
blinking.

**Tarjīʿ** is that **on/off plus a brightness crest**: full-wave
`|tremolo|` raises a peak that boosts tint/halo colour
(`GLINT_RESONANCE_PEAK_BOOST`) while troughs drop the layer to zero at full
depth. Depth scales both (Ink Lab **Pulse depth**; a non-zero
`GLINT_RESONANCE_TROUGH_FLOOR` leaves residual sheen for a softer breathe).
Idle / no detection → peak 0, full sheen (no tell that a pulse is coming).
First-pass white-gold and **repeat terracotta** both take the same gate. A
per-frame sampler on the Active strong-hold word keeps the pulse updating
after the wash park freezes its Animatable.

The pulse is **delayed to the reader's clock** — the tap sits at the audio
sink's input, so the signal is led forward by everything downstream before
it is read out: the route preset (the same one the highlight clock
subtracts) and the sink's own AudioTrack buffer (measured live via
`AudioSink.getAudioTrackBufferSizeUs`, typically 40–100 ms and much more
on emulators — the term that used to leave the shimmer a quarter-second
ahead of the voice). That lands the shimmer on the playback head
`positionMs` tracks — the same reference the word ink rides — so the pulse
is in lockstep with the wash, not trailing the visible word. Wall-time
components scale by playback speed; the Sonic resampler's own buffer (only
present off 1×) does not. The Ink Lab's **Ear delay ms** nudges the last
device-specific millimetre on top (add it back when a route genuinely
lags the audible vibration). One analysis hop is exactly 20 ms of
*content* at any source rate (44.1 kHz decimates to 8820 Hz → 176
samples), so the delay, the rate read, and the phase lead are all in true
content time. The live detector readout reports the applied total
(`ear +N ms`) for diagnosis.

**No reverberation, no pulse**: a steady hold without an audible pulse —
even a long verse-closing waqf — keeps still gold. The gate also hard-closes
at handoff: the dry-down dissolve after the voice moves on is never
modulated.

The modulation is **pure alpha** — the reveal edge never moves mid-animation,
so the bloom can never appear to restart. The halo forms only with the
directional wash (`smootherstep(glintProgress)`); there is no whole-word
formation floor when resonance engages.

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
| Glint tint | `glintTintAlpha` | 0.88 | 0–1 | Always-on wet-ink tint (must read over parchment mid-wash). |
| Halo strength | `glintGlowAlpha` | 0.78 | 0–1 | Always-on halo; tarjīʿ peaks boost further. |
| Halo blur | `glintGlowRadius` | 10 | 0–10 | Renderer blur radius around the glyph outline; it is not a word-relative radial size. |
| Tarjīʿ (Tajweed tab) | `glintResonance` | on | toggle | Turns the wet-ink glimmer on and off with detected tarjīʿ (first-pass gold and repeat terracotta). |
| Pulse depth (Tajweed tab) | `glintResonanceDepth` | 1.0 | 0–1 | How deeply tarjīʿ troughs extinguish the glimmer (1 = full on/off with the voice). |
| Ear delay ms (Tajweed tab) | `tarjiEarDelayMs` | 0 | 0–200 | Extra delay so the pulse lands on the ear, on top of the route preset + measured sink buffer + output path. |

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
