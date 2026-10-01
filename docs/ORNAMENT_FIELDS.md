# Flowing ornament fields

The field is a quiet vegetal ground for the illumination. Its structure is
curved: adding flowers to a straight lattice does not remove the lattice's
visual noise. These rules apply to covers, chapter headers and Ornaments Lab
on Android and web.

## Research and interpretation

The [Met's vegetal ornament essay](https://www.metmuseum.org/essays/vegetal-patterns-in-islamic-art)
places abstract plant ornament in Islamic manuscripts, textiles and buildings,
and includes wavy-vine examples. It supports a vegetal field alongside the
geometric medallion; geometric stars need not be the language of every surface.
Our stems and motifs are an interpretation, not a reproduction of one object.

[Owen Jones, *The Grammar of Ornament*, propositions 6–12](https://www.readingdesign.org/grammar-of-ornament)
provides three useful construction principles: detail grows from a parent
stem, curved junctions share tangents, and the general form precedes enrichment.
These are design precedents, not universal measurements of beauty.

## Generator rules

1. **Curves form the structure.** Four cubic ogee arcs connect the cell-edge
   midpoints. No straight star, square knot, diamond heart or polygon scaffold
   is drawn in the field. The geometric medallions, seals and borders retain
   their own vocabulary.
2. **Detail grows from the stem.** Four rotated branches share the stem's edge
   roots and tangent axes. Choose one coherent motif for the entire repeat:
   a tapered leaf, an open curling tendril, or a lobed palmette. There are no
   detached flowers or centre marks.
3. **Avoid crossing and crowding.** Fit each branch along the stem by scaling
   its local horizontal reach by 1.2 and its vertical excursion by 0.6.
   Sampled branch and stem segments must not cross. Leave the broad central
   areas empty; the rosette and words provide the page's stronger detail.
4. **Balance the ground.** Sum actual path lengths (without closing open
   tendrils). Set `cellWidthDp = spacing × lineLength / 5.5`, with spacing
   96–116 dp. Line density stays between 0.047414 and 0.057292 dp⁻¹ at a
   constant stroke width. These are visually tuned bounds, not historical
   proportions from the sources.
5. **Repeat with continuity.** Stem contacts lie exactly on the four shared
   cell-edge midpoints. Branch positions have quarter-turn symmetry. The
   entire repeat may have either handedness; a leaf or scroll is not forced
   to have reflection symmetry. No individual tile is displaced or rotated.
6. **Preserve the softness.** Sample cubic arcs with the shared Bézier helper.
   Web field SVGs retain four decimal places in cell coordinates. No long
   ruler-like segments, loose ray fragments or fine polygon compartments.
   Existing whisper ink, embossing and edge dissolves keep the field below
   the medallion and the words.

The four field RNG draws remain in their original position, and both platforms
consume the same stream. Saved seeds are deterministic under this grammar;
medallions, seals and borders remain identical. The Lab offers leaf, scroll
and palmette filters, reflecting the geometry it actually draws.

Tests cover attachment, cell-edge contacts, short curve segments, quarter-turn
symmetry, line density and absence of intersections across 400 seeds. The
reported seed `132614421` is a cross-platform known-answer fixture. The former
star-edge and reflection assertions protected the straight lattice; the new
checks preserve continuity and balance while allowing flowing handed motifs.
