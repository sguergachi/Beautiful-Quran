# Geometric fields with filigree

The field combines a legible geometric repeat with finer foliate enrichment.
Geometry must look composed; filigree rewards a closer look without obscuring
that structure. Seeds choose visibly different pattern families, not only
slightly different proportions of one motif. A bare grid is too dull, arbitrary overlays are too noisy, and
standalone decorative loops lose the intended Islamic character.

## Research and interpretation

The [Met's geometric ornament essay](https://www.metmuseum.org/essays/geometric-patterns-in-islamic-art)
describes repetition, interlace, unity and order, and the combination of
geometric and other ornament. The [V&A's Iranian star-and-cross tile panel](https://www.vam.ac.uk/articles/design-and-make-your-own-islamic-tile-and-printed-pattern)
illustrates a repeat of eight-pointed stars and complementary cross spaces,
with finer decoration contained by the tiles.

The [al-Zanjani Qur'an folio, dated 1137](https://www.metmuseum.org/art/collection/search/453372)
combines geometric interlace and foliate scrolls. This is a useful precedent
for geometry and delicate secondary ornament sharing one composition.
Our field is an interpretation of that relationship, not a facsimile or a
claim to reproduce a particular historical pattern.

## Generator rules

1. **Choose a composed family.** `star-and-cross` pairs eight-pointed star
   compartments with scrolling filigree. `octagonal garden` uses regular
   octagonal enclosures and an eight-petal flower with nested leaf veins.
   `lozenge rosettes` alternates diamond compartments with four foliate sprays
   and smaller eight-pointed corner rosettes. The families have distinct
   geometric frames and interior layouts; never independently shuffle their
   parts. Their names describe our generator recipes, not historical styles.
   All primary frames contact neighbours at the shared edge midpoints.
2. **Give the geometry a crafted edge.** A second contour follows the
   primary frame at a restrained radial inset of 0.042–0.052 cell units.
   Both contours
   use a 1 dp rule; all interior filigree uses a 0.55 dp hairline. Every field
   renderer honors this distinction, including the Lab.
3. **Compose the interior.** A small central eight-pointed star anchors each
   family’s sprays or petals. Star-and-cross repeats eight mirrored pairs of
   curling stems with narrow leaf accents. The garden repeats eight nested
   leaf petals; lozenges repeat four mirrored scroll pairs. Quarter motifs
   join across tile boundaries to complete the secondary corner ornaments.
4. **Protect the spaces.** Filigree stays at least 0.02 cell units from the
   inner frame contour. It cannot cross itself, adjacent sprays or the frame.
   The centre stays open. No independently chosen square knots, grids or
   scattered centre marks are added.
5. **Vary the family and its proportions.** The first draw chooses one of
   three families and its frame inset; the other three choose curl or vein
   reach, leaf reach and spacing. Whole-repeat reflection and quarter-turn
   symmetry remain exact. There is no per-tile randomness.
6. **Balance visible ink.** Sum closed and open path lengths, weighting rules
   by 1 and hairlines by 0.55. Set `cellWidthDp = spacing × inkLength / target`,
   with spacing 148–168 dp and targets 11.5, 11 and 9 respectively for the
   three families. This keeps weighted line density bounded while
   leaving the motifs large enough to read at phone scale. These dimensions
   are visually tuned, not historical measurements from the sources.
7. **Keep page ornament subordinate.** The existing whisper ink, embossing
   and edge dissolves keep the field below the medallion and the words.
   Paths are cached outside drawing; weight separation uses two cached paths
   on Android rather than one draw call for every spray or repeat.

Android and web consume the same four RNG draws in their original position.
Saved seeds remain deterministic; medallions, seals and borders keep their
existing seeded output. The Lab displays the generated family and can search
for any of the three patterns alongside a medallion fold.

Tests require all three families in both cover and chapter seed samples,
and distinct primary frame geometries (16, 8 and 4 vertices). They also cover
reflection and quarter-turn balance, attachment to the central star, frame clearance, cell-edge contacts, weighted line density and absence
of accidental intersections across 400 seeds, including closing edges. Known
answers pin Android/web parity and the reported seed `132614421`. Geometric
edges are intentionally straight; the former all-curved short-segment check
is replaced by compartment clearance and the retained no-crossing checks.
