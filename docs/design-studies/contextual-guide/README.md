# Contextual guide ink studies

These ten generated references explore one interaction: royal-green ink enters
the existing reader paper to teach the bookmark-note hold gesture. They are
design studies, not runtime assets.

| Study | Prompt direction |
|---|---|
| [01 — Capillary absorption](01-capillary-absorption.webp) | Wet-on-wet pigment soaking into warm paper, with the lesson-side wash reaching toward the live ribbon. |
| [02 — Progressive vellum](02-progressive-vellum.webp) | Clean, high-end density zones blended into one optical transition. |
| [03 — Sumi diffusion](03-sumi-diffusion.webp) | Museum-catalog sumi restraint with microscopic fiber softness. |
| [04 — Tonal fog](04-tonal-fog.webp) | Nearly textureless cinematic falloff with a low-frequency taper. |
| [05 — Progressive focus](05-progressive-focus.webp) | Underlying scripture progressively loses contrast and focus inside the wash. |
| [06 — Wet shoulder](06-wet-shoulder.webp) | A dark pigment shoulder followed by a long translucent absorption tail. |
| [07 — Wax resist](07-wax-resist.webp) | The dismiss action as untouched paper that liquid ink flowed around. |
| [08 — Layered alpha blur](08-layered-alpha-blur.webp) | A wide alpha-matte transition that veils the page before resolving near the ribbon. |
| [09 — Nonlinear density](09-nonlinear-density.webp) | Ultra-clean density held dark, then dissolved through a long nonlinear curve. |
| [10 — Hybrid wash](10-hybrid-wash.webp) | Physical ink absorption combined with progressive-focus hierarchy. |

## Shared generation prompt

> Portrait Quran reader with a ruby bookmark ribbon at the far right. Royal
> green fills the left side, then progressively diffuses through slightly more
> than half the screen toward the ribbon. Set “Add a note” and “Press and hold
> this ribbon.” in refined parchment serif type. “Got it” is an understated
> island of the actual theme paper at bottom-left. Keep one integrated paper
> surface. Avoid cards, shadows, glass, hard contours, waves, scallops,
> splatters, stripes, banding, gray seams, and extra text.

Each table row supplied the named material/rendering delta to this base prompt.
The implemented direction targets **02 — Progressive vellum**: a clean pigment
body, broad nonlinear alpha veil, and progressive blur of the sampled scripture.
Subtle multi-scale vellum grain is confined to the transition; it must never
become a drawn or repeating edge.

## Research references

- [Progressive blur with an alpha mask](https://devslovecoffee.com/blog/making-apple-progressive-blur-on-web): use layered depth/opacity rather than one oversized blur.
- [Negative space](https://ixdf.org/literature/topics/negative-space): unmarked space can be the active figure, which informed the theme-paper dismiss clearing.
- [High-flow pigment on paper](https://www.melissaellenfink.com/blog/my-favorite-series-2-high-flow-acrylics): density pooling plus a feathery translucent tail.
- [Android AGSL RuntimeShader brushes](https://developer.android.com/develop/ui/compose/graphics/draw/brush): one per-pixel field avoids sampled strips and gradient seams.

Generated with the built-in image-generation tool on 2026-08-01.
