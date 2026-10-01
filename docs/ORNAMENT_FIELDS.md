# Harmonious ornament fields

The field is the ground of the illumination. It must read as a coherent weave
at a glance and yield attention to the rosette or the words. These rules apply
to the shared generator, including Ornaments Lab, covers and chapter headers
on Android and web.

## Research and interpretation

[Owen Jones, *The Grammar of Ornament*, propositions 3–9](https://www.readingdesign.org/grammar-of-ornament)
connects repose with fitness, proportion and harmony, and places the general
form before its subdivisions and enrichment. His geometric construction and
simple-unit principles support deriving details from a single repeat rather
than picking unrelated embellishments. This is a design precedent, not a
claim that his nineteenth-century theory is a universal measure of beauty.

The [V&A's Islamic tile guide](https://www.vam.ac.uk/articles/design-and-make-your-own-islamic-tile-and-printed-pattern)
shows a thirteenth-century Iranian eight-point star-and-cross tessellation,
and explains repetition and reflection symmetry. We use that structure as a
precedent for a continuous, balanced ground; our linework is an interpretation,
not a reproduction of that tile panel.

## Generator rules

1. **One readable structure.** Prefer the outer silhouette of the khatam:
   an eight-point star outlined by sixteen vertices, with none of the internal
   crossing lines. Retain the two-square khatam as a less frequent woven
   alternative. Both preserve the same eight outer tips.
2. **Connected detail.** A square knot at each lattice corner meets the four
   nearest diagonal star tips exactly. Its half-extent is `0.5 − 0.5/√2`.
   Octagonal knots are excluded: their extra edges intersect the stars and
   introduce small competing compartments.
3. **Enrichment follows simplicity.** A small diamond may enrich an outlined
   star, but never the already subdivided two-square khatam. Its radius is
   14–20% of the star's outer radius. The three recipes are open outline,
   outline with a centre, and woven khatam. There is no per-tile randomness.
4. **Equal visual density.** Sum the perimeter of every stroke in one repeat,
   then choose `cellWidthDp = spacing × lineLength / 4.5`, where spacing is
   64–80 dp. Total line length per unit of ground area therefore stays between
   0.05625 and 0.0703125 dp⁻¹ at a constant stroke width. Richer recipes get
   larger repeats instead of becoming darker fields. These bounds are our
   visual tuning, not historical proportions from the sources.
5. **Symmetry and continuity.** Keep fourfold reflection/rotation symmetry,
   the cardinal tips on the cell-edge midpoints, and corner knots on the
   diagonal tips. No random displacement, rotation or incomplete ray fragments.
6. **The field remains subordinate.** Retain the existing whisper ink,
   embossing and edge dissolves. Do not compensate for crowded geometry just
   by lowering opacity: solve its construction and spacing first.

The outline's inner radius is `0.5 / (cos(π/8) + sin(π/8))`, the intersection
radius of the two original squares. All construction uses the same unit cell.
The four field RNG draws remain in their original position, and both platforms
consume the same stream. A saved seed is still deterministic, but its field
now follows these rules; its medallion, seal and border remain identical.

Tests pin the three recipes, line-density budget, rotational symmetry,
edge/corner contacts and known-answer fields on both platforms. The variety
check counts point-count signatures rather than stroke counts: the enriched
outline and woven khatam both have three strokes but different structures.
