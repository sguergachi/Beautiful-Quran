## Fastest thing to try right now
Open the Ink Lab, set **`washFeather` 1.6 → 0.5** and **`pacedFeather` 1.19 → 0.5**, play an ayah. That one change is most of the complaint. Everything below explains why, and what to do after you've seen it.

---

## Why it reads as a wipe now

**1. The feather is wider than the word, so there is no edge — only a crossfade.**
`InkWashFeather = 1.6` (Fade.kt:476) means the gradient spans 1.6× the word's width. Smootherstep's max slope is 15/8, so alpha traverses resting→1 over ≈0.85 × word width. On an 8-letter word, ~7 letters are mid-transition on every frame, each offset by a sliver of phase. That is the definition of a whole-word opacity crossfade with a directional bias. Your own comments say it out loud — Fade.kt:77-80 ("closer to a whole-word breath than a moving edge"), docs/ink-fade.js:4 ("deliberately a whole-word breath"). The complaint is that admission surfacing. Note the V2 path already escapes this: `letterFeather = (2/n).coerceIn(0.35, 0.9)` (InkEngine.kt:431) is ~2 letters wide. **The 1.6 default is the outlier, not the design.**

**2. Iso-alpha contours are perfectly straight vertical lines.**
All four wash brushes are `Brush.horizontalGradient` over a rect (Fade.kt:85-97, 282-294, 370-382). Alpha is a function of x alone. A perfectly straight, perfectly smooth boundary translating at constant speed *is* the visual signature of a wipe transition. Softening it produces a soft wipe, not ink — the brain reads straightness as machine, not liquid.

**3. The alpha profile is symmetric and monotonic, so there's no wet edge.**
Real wash is asymmetric: a dense advancing rim, then a long diffuse tail behind it. `inkSmootherstep` is symmetric (zero slope both ends) and strictly monotonic resting→1, so density is *lowest* at the front and keeps rising for 0.8w behind it. Ink reads the opposite way. Nothing marks the front as an event.

**4. The reveal is alpha on a flat paper rect — mid-wash is uniform grey.**
`InkReveal` paints `paper.copy(alpha = 1 - glyphAlpha)` (Fade.kt:267). At mid-progress, a glyph is black ink at ~50% over cream = a neutral grey. That is a dissolve. Diluted ink isn't grey: it's warmer, browner, lower-contrast, and it *loses hairlines and diacritics first* while the stems hold. Uniform alpha degrades every part of the glyph equally.

**5. Zero vertical structure — the front ignores Arabic geometry.**
Horizontal-only gradients mean the dot of a bāʾ and the bowl of the same bāʾ change at identical alpha at identical instants. Alif/lām stems, harakat, and descending final ن/ي all cross the front simultaneously. Ink would hit the baseline stroke first and creep up stems and out to marks a beat later.

**6. 9 piecewise-linear stops over a 1.6w span, 8-bit, over flat cream.**
`InkProfileStops = 9` (Fade.kt:452) with linear interpolation between stops reduces your carefully-chosen quintic to 8 straight segments spread over ~1.6 word widths, and the per-step alpha delta lands right in banding territory on a flat paper field. Reads "CSS gradient", not "pigment".

**Not the problem — don't chase these:** mask head vs. perceived front is correctly compensated (`maskProgressForLetterFront`, InkEngine.kt:445). Bleed/clip geometry is correct. The DstIn/SrcIn layer mechanics are sound.

---

## What real ink wash should look like

1. **Localized front, ~1–2 letters wide.** You can point at where the ink is *right now*. Not 7 letters of simultaneous transition.
2. **Asymmetric density: crisp arrival, long soak behind.** The front lands quickly at near-full density; the tail behind it settles slowly over ~1 letter. The current symmetric curve fades in *and* out equally, which is what makes it read as fade.
3. **Irregular boundary.** The front deviates from vertical by a fraction of an x-height, low-frequency, and varies per word (paper fibre). Seeded per word — never per frame, or it shimmers.
4. **Dilution is a hue path, not an alpha path.** The transition zone reads warm/brown/low-contrast, settling to full black ink. Fine detail (diacritics, hairlines) arrives last.
5. **Once soaked, density holds.** No continued brightening behind the front. Anything still getting darker 0.8 word-widths back is a fade, not a soak.

---

## Recommended changes, ranked

**R1 — Cut the feather, derive it from letter count. (Do this first.)**
*Where:* `Tuning.washFeather` / `Tuning.pacedFeather` (InkEngine.kt:104, 119) → ~0.45–0.6; better, route V1 through the existing `InkEngine.letterFeather(letterCount)` so the edge is ~2 letters regardless of word length.
*Feel:* an actual visible travelling front. This alone converts "fade" → "wash".
*Overdo risk:* below ~0.25 it becomes a hard peel and trips invariant #7. Zero code risk — it's a live Lab knob, audition it in 30 seconds.

**R2 — Make the alpha profile asymmetric: steep toe, long shoulder.**
*Where:* one shared profile function beside `inkSmootherstep` (Fade.kt:484), consumed by all three stop builders (`washColors` ×2, `paperColors`).
*Feel:* the front becomes an arrival event with a soak trailing it. Also buys most of the perceptual "diffusion deceleration" without touching timing.
*Note:* with a paper *cover* you can't overshoot past full ink (the glyph underneath is already opaque), so asymmetry is the achievable version. True wet-edge overshoot would need a separate multiplied ink rim at the front — worth prototyping only after R1+R2 land.
*Overdo risk:* too steep a toe and you're back to a peel; keep the 10–90% band ≥ 1 letter.

**R3 — Break the straight vertical front with seeded bands.**
*Where:* `shapedWordBloom` InkReveal, inside `lineBounds.forEach` (Fade.kt:270-307) — split `cover` into 3–4 stacked horizontal bands, offset each band's `head` by a deterministic hash of (range start, band index).
*Feel:* the front stops looking machined; reads as fibre.
*Cost:* 3–4 drawRects instead of 1, no shader, no asset.
*Overdo risk:* >0.25 letter of offset looks torn/glitchy; >6 bands is wasted draw calls; the seed must be per-word, never per-frame.

**R4 — Tint the transition zone toward diluted ink.**
*Where:* `InkReveal`'s `paperColors` (Fade.kt:263-268) — interpolate the cover colour from `bloom.paper` toward a warm dilute tone across the feather, with the token living in `QuranAccents` beside `repeatInk` so hue stays theme-owned.
*Feel:* mid-wash stops looking like 50% opacity and starts looking like thin ink.
*Overdo risk:* visible sepia haze across the page — keep chroma low and confined to the transition zone.

**R5 — More stops + dither.**
*Where:* `InkProfileStops` 9 → 17 (Fade.kt:452, and web `INK_PROFILE_STOPS`). If banding persists on flat cream, add ~1/255 ordered dither.
*Feel:* kills the "digital gradient" tell. Essentially free.

---

## Android + web parity

- **`1.6` is duplicated in three places** — Fade.kt:476, `web/src/ui/theme/Fade.ts:7`, `web/src/ui/reader/InkEngine.ts:48`. R1 on Android alone desyncs web immediately.
- **R2 changes the curve two tests pin**: `app/src/test/.../InkWashAlphaTest.kt` and `web/src/ui/theme/__tests__/Fade.test.ts` will both fail and need re-baselining together.
- **R3 is the real parity hazard.** Web builds masks as `linear-gradient` strings quantized to 48 steps and cached by `progress|resting|rtl|feather` (`web/src/render/inkWash.ts:19-56`). Per-word seeded jitter makes the key per-word and blows the cache. Fold the seed into a small fixed set (~8 variants) and use stacked `mask-image` layers with `mask-size: 100% 25%`, or web stays on the straight front and drifts from Android.
- **R4** needs a matching token in `web/src/ui/theme/styles.css`.
- **Marketing page** (`docs/ink-fade.js`) hard-codes the whole-word-breath assumption in its comments and fallback. Once the feather drops, that page no longer matches the app.

---

## Status
- **R1 landed** — `InkWashFeather` / paced defaults at 0.5 / 0.45 (Android + web).
- **R2 landed** — shared `inkWashProfile` = `smootherstep(√t)` on all wash stop builders + `inkWashAlpha` (Android + web). Symmetric `inkSmootherstep` kept for glint / whole-word breath only.
- **R3 landed** — 4 seeded horizontal fibre bands stagger the wash head (Android draw + web stacked `mask-image`; seed folded to 8 cache variants).
- **R4 landed** — `QuranAccents.diluteInk` warms mid-feather paper cover (Android `paperWashColors`); CSS `--dilute-ink` token on web.
- **R5 landed** — `InkProfileStops` / `INK_PROFILE_STOPS` 9 → 17.
- **Next:** tune dilute chroma / band jitter by eye; wet-edge overshoot rim only if still flat.
