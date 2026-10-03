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
   geometric frames and interior layouts. Cover recipes keep these parts
   together. Chapter recipes combine only the fitted, exhaustively checked
   placements below; arbitrary overlays remain forbidden. Their names describe our generator recipes, not historical styles.
   All primary frames contact neighbours at the shared edge midpoints.
2. **Give the geometry a crafted edge.** A second contour follows the
   primary frame at a restrained radial inset of 0.042–0.052 cell units.
   Both contours
   use a 0.6 dp rule; all interior filigree uses a 0.33 dp hairline. Every field
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
   with spacing 48–56 dp and targets 11.5, 11 and 9 respectively for the
   three cover families. Chapters use `min(58, max(spacing, spacing × inkLength / 11.5))`
   so sparse recipes retain the texture scale and dense ones cannot exceed
   the existing ink-density ceiling. This keeps relative line density bounded. Repeats are
   about 48–51 dp for representative seeds, fitting roughly eight across a
   phone-width field so the ornament reads as a continuous texture. Rules
   and hairlines are both thinned to 60% of their former widths to preserve
   delicacy at this scale. Each live field rounds its width / preferred repeat
   to the nearest even count (at least two), then divides the available width
   by that count. Repeats fit exactly across the field and stay square; height
   clips the continuous texture rather than stretching motifs. Android uses
   its drawing bounds; web and Lab observe their own field element on resize.
   These dimensions are visually tuned, not historical measurements from the
   sources.
7. **Keep page ornament subordinate.** The existing whisper ink, embossing
   and edge dissolves keep the field below the medallion and the words.
   Chapter-opening ink and emboss are softened by 25%, with a top
   40 dp top dissolve inside a 64 dp head, leaving 24 dp of full pattern above
   the medallion. The 48 dp foot before a basmalah (56 dp otherwise) leaves
   16–24 dp of full pattern below the metadata before a 32 dp bottom dissolve.
   Opus’s screenshot review informed these proportions: the field surrounds
   the content, and neither fade washes through the title lockup.
   Paths are cached outside drawing; weight separation uses two cached paths
   on Android rather than one draw call for every spray or repeat.

## Chapter identities

A random seed alone did not give chapters visibly different structures.
With Opus 5.5's design input, chapter number now assigns a structural recipe:
recover zero-based `c = floorMod(seed - 1, 114)`, then choose frame `c % 4`,
interior `c % 7` and corner `c % 5`. These pairwise-coprime counts yield 140
unique combinations; the first 114 never collide. Consecutive chapters differ
on all three axes. Ayah count still varies the safe proportions, not the
chapter's structural identity.

- Four frames: star-and-cross, octagon, lozenge and chamfered square lattice.
- Seven interiors: eight scroll pairs, eight veined petals, four scroll pairs,
  four veined petals, alternating long petals and short diagonal scrolls,
  four geometric palmettes with diagonal leaf buds, and a geometric star medallion with leaf buds.
- Five corner treatments: floret, star-and-floret, diagonal leaf, diamond bud,
  and a four-pearl cluster.

Diamond frames shorten diagonal sprays about their heart attachment to 55%
of the normal reach. The alternating recipe also shortens its diagonal
scrolls. Chamfered frames shrink corner details to 60%; their edges meet
neighbours only at midpoint tips, avoiding double-inked shared edges. Diamond
buds and star florets retain clear gaps from their enclosing lines. These
fixed geometric fits replace random part shuffling; the frame-clearance and no-crossing
requirements still apply to every composition. No runtime fit search is used.

Chapter medallions also receive distinct structural recipes: ten permitted
(fold, star-index) pairs times three secondary motifs times four cores yield
120 combinations. The fourth core is a diamond. Sixteen-fold primary stars
use only indices 2, 3 and 4, keeping denser folds from dominating the catalogue.
`(c × 37) % 120` selects them injectively for all 114 chapters. The existing occult-compound exclusions and separated zones remain.

Android and web preserve the cover's four field RNG draws and its existing
seeded output, including its medallion, seals and border. Chapter ornaments
intentionally change. The Lab previews the chapter recipe for its current
seed; its family/fold search continues to explore cover recipes.

Tests require the three cover families and four chapter frames,
and distinct primary frame geometries (16, 8 and 4 vertices). They also cover
reflection and quarter-turn balance, attachment to the central star, frame clearance, cell-edge contacts, weighted line density and absence
of accidental intersections across 400 seeds, including closing edges. Width-fitting tests cover narrow, phone and wide
fields, including the even-count rounding boundary. Known
answers pin Android/web parity, five chapter layouts and the reported cover
seed `132614421`. All 114 chapters at three sample ayah counts and their
actual verse counts must have distinct geometry after coarse 1/16-cell quantization, separately for their fields and
medallions. They also pass bounds, symmetry, 0.02 frame clearance, density,
no-crossing, hexagram and pentagram checks. Clearance includes all corner
ornament against both frame contours; preferred texture size cannot exceed
58 dp. Geometric edges are intentionally straight; the former all-curved short-segment check
is replaced by compartment clearance and the retained no-crossing checks.
