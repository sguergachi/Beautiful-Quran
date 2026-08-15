# Design language

The design brief, in the owner's words: *one sheet of paper, calm and pure,
butter smooth.* Everything below serves that sentence.

**The metaphor is PAPER.** This is the single governing idea, and every
decision answers to it. The app is not a screen showing pages — it *is* a
sheet of paper. Nothing floats above the paper, nothing is layered on top of
it, nothing casts a shadow onto it. When something new must be said, the
paper itself changes: ink spreads across the sheet, the words settle in, and
the same sheet now carries the new message. There are no dialogs, no cards,
no trays — only paper, and ink on paper.

## The sheet

The whole app is **four flat sheets** — Bookmarks, Chapters, Reader, Settings — viewed
one at a time. Navigation glides the next sheet in from the side (a
quarter-width slide softened with a fade, 380 ms); nothing stacks, nothing
floats, nothing casts a shadow.

In **Mushaf** layout (Settings → Reading) the Reader sheet is a full-screen
leaf with **no frame** — iBooks, not a gilt box. The paper runs to the
edges and the text block is the only composed thing on it: a thin running
head (chapter name in quiet gold, chapters and settings at the fore-edges),
the text well, and a folio line of type. Nothing rules, borders, or seals
the page; the ornament kit stays on the closed cover. The margins are a
book's, not a chrome's — a single fore-edge margin plus an edge gutter for
the circled ayah marks' overhang, so the revelation is the widest thing on
the screen and the fitted glyphs are as large as that width allows.
A short page (al-Fātiḥah) sits in the middle of the well; a full
Madinah page fills it. A swipe turns the whole leaf (running head, text,
folio) the way iBooks turns a page.
Transport is a quiet line of ink on the paper *under* the page.
Play does not hide the status bar or collapse that inset — the page
stays put.
The width fit measures against a slightly narrower page than the one that
draws it: the fit measures one concatenated run while the line draws one
`Text` per word, and that slack is what keeps the longest line off the
edge.
QCF lines are the printed Madinah lines: one size, even
15-line leading, leftover width shared between words
(no U+0020).
Horizontal swipe turns the 604 Madinah pages; the outer stack no longer
steals that gesture. The ayah rail is scroll-only. A chapter-list tap opens
the reader on that chapter's first page. Scroll layout keeps the original
vertical verse sheet.

Hard rules:

- **No borders, no dividers, no cards, no elevation.** Hierarchy comes from
  spacing, size, and ink strength (text alpha) only.
- **Nothing floats.** No FABs, no snackbars, no modal sheets, no dialogs.
  Anything that would traditionally float becomes a line *in* the page
  (error messages), its own sheet (Settings), or an **ink bleed** that
  turns the current sheet into the message (system prompts — see below).
  The deliberate exceptions are quiet paper ornaments hosted by the shared
  `FloatingPaperControl` (same enter / exit slide+fade and **10 dp** bottom
  inset): the reader's **return-to-ayah roundel** and the stack-level
  **Back-to capsule** (opaque stadium with the same gilt rim and drawn
  qalam arrow). The cover sheet's **floating playback control** reuses that
  same motion and inset over the chapter list while a verse is loaded. One
  vertical rhythm when the paper stack turns between cover and reader.
- **Edges dissolve.** Scrolling content fades out at the top and bottom of
  every sheet — ink fading off the page, not content clipped by a boundary.
- **Taps have no ripple.** Touch feedback is the content's own motion
  (a word lighting, a page turning), never Material ink splash.

### Contextual teaching

Feature education stays with the feature it explains. It never takes the full
screen, floats in a card, or dims the whole sheet. The feature's own mark stays
visible; unused paper away from it becomes a clean royal-green vellum field.
This is an **inverse paper spotlight**: pigment covers the area
being defocused while a soft clear window remains around the live feature;
it is never a dark scrim or whole-screen opacity change. A short lesson
takes the quiet reading space beside that window. One simple geometric leader
connects lesson to mark, typography does the rest, and one quiet text action
dismisses it. `ContextualFeatureTip` is the shared primitive. Its placement is
not limited to four screen edges: the caller supplies the spotlight point plus
an angle and a fractional distance along the ray to the first paper edge.
That ray locates the lesson body in unused space and becomes the shader, blur,
and interaction axis. Cardinal directions are ordinary parameter values;
diagonal compositions use the same rendering path.

The first use is the bookmark-note gesture. After the first ribbon unfurls,
the reader first glides that ayah onto its reading line. **Royal green then
bleeds in from the paper edge opposite the ribbon**, holds solid across half
the physical screen from top to bottom, then tapers toward the ruby cloth as a
continuous progressive wash. The selected **02 progressive vellum** study is
the target: a stable pigment body runs into a broad nonlinear alpha veil while
the live scripture remains readable through that transition. One unbounded
diffusion profile replaces separate body and feather boundaries, so there is
no iso-opacity ridge to read as an edge. Paper fibres vary pigment absorption,
and a restrained reservoir gathers behind the lesson copy. Android 13+
draws the independently tapered pigment contours in one runtime-shader pass,
then records each visible ayah into its own GPU layer and crossfades it into a
softly blurred version only through the feather; Android 11–12 keeps a smooth
gradient fallback.
The effect never captures the virtualized list as one layer, so the real verse
and ruby ribbon remain present beneath the translucent veil. Low-amplitude,
domain-warped pigment pooling, fibre absorption, and paper tooth continue from
the body through the transition, becoming most legible in the translucent wash,
with no visible slices, bands, scallops, or drawn contour. The ribbon remains
fully exposed on its own side. Parchment type settles partly into that feather: “Add a note —
Press and hold this ribbon,” while a compact area preserved from the spill
uses the reader theme's own paper color as its dismissal button at the
thumb-reachable bottom of the teaching side. An independent soft pigment
contour sits behind that paper cutout without changing the lesson reservoir or
moving its target-relative typography. It never draws a ring or separate
container around the action. Dismissal withdraws the
bleed and returns the ayah in place; no other verse or chrome leaves the reader.
Low-contrast rings breathe around the live ribbon itself—no target dot,
pointer glyph, or competing accent—and never sits at the bleed's feather.
The body-side half-plane absorbs gestures; the spotlight-side half-plane stays
attached to the feature below. For the bookmark lesson, a vertical drag there
dismisses the lesson and continues scrolling the paper. Holding the live ribbon
opens its note editor and completes the lesson; a short tap cannot accidentally
remove the bookmark while the lesson is teaching that hold.

The ayah rail is the second use. On the first settled chapter opening, its live
collapsed stack stays clear at the selected edge while the same vellum occupies
the unused opposite paper. “Find any ayah — Tap, or drag up and down on this rail” sits
across from the spotlight. A tap blooms the existing scrub wheel; a vertical drag
blooms it while selecting an ayah. Either dismisses the lesson. A horizontal edge
swipe stays available to Back; an ordinary page drag or **Got it** also puts the
lesson away. It never opens over active recitation,
chapter-flight motion, or before the initial reading position has settled.

The guide system remains disabled by default while this language is being
refined. Settings → Developer → **Contextual feature guides** enables and
rearms every lesson for its next eligible moment. **Replay feature guides**
provides an explicit reset while also enabling the gate; lessons then appear
on the next chapter opening or newly added bookmark. Neither control touches
bookmarks or notes, and leaving developer mode disables guide presentation
again.
The ten visual studies and selected 02 target live in
`docs/design-studies/contextual-guide/`.
Implementation rules and the required live-target verification checklist live
in [CONTEXTUAL_GUIDES.md](CONTEXTUAL_GUIDES.md).

> **Fixed 2026-07-24: the repeat picker.** It used to be the one live violation
> of the two rules above — a stock `androidx.compose.ui.window.Dialog` with a
> `Surface`, `RadioButton`s and `TextButton`s. It is now
> `ui/reader/RepeatSheet.kt`, hosted by `ReaderScreen` inside the shared
> `InkRevealOverlay`: ink bleeds from the player bar's repeat control and the
> reader sheet becomes the question. Selection is the ink-brush circle plus ink
> strength, Done is one quiet text line, and the margins dismiss. The ayah
> wheels are the same `SearchDialWheel` the cover sheet's search uses — a
> compact centred block, so a range reads as one phrase — and they unfold
> **directly under the choice that asked for them**, pushing the rest of the
> list down, so the numbers belong to that line rather than trailing the sheet.
> Neither dial carries a caption: each writes itself out **on the reading line**,
> so it reads straight across — "67 to 120", and "5 to 9" beside the distance
> that produced it.
>
> Those two dials are not the same instrument, because the reader does not
> arrive with the same knowledge. For a range they have both ayah numbers, so
> both figures are wheels. For "from this ayah" they have *a distance* — "about
> four ayahs on" — and specifically **not** the ayah numbers, so the sides
> swap roles: the **dial on the right takes the distance**, and the **label on
> the left is derived** — the whole range it lands on, "5 to 9", written in the
> same grammar the range dial above it uses and moving as the wheel turns. The
> label leads because it is the answer being checked; the wheel follows because
> it is the question being asked. Asking for what the reader can know and
> writing out what they cannot is the whole design of that line. One word,
> **"ayahs"**, sits over the dial to name what it counts — the only column
> heading left on the sheet, and it earns its place because that wheel is the
> one figure the reading line does not explain. The label's column is measured
> from the widest range *that surah* can spell out, so the dial never shifts as
> the figures gain a digit, and a fifteen-ayah surah never reserves room for
> "286 to 286".
>
> Nothing is drawn under the wheel: no band, no plate, no wash. The reading line
> is the row the numbers fade *towards* — rows dissolve into the sheet as they
> leave the centre, and the centred figure holds full ink in the accent while
> its neighbours sit faint. That is the same "hierarchy from ink and spacing
> only" rule the rest of the page obeys, and it is what keeps the dial part of
> the paper instead of a control resting on it. For that dissolve to work,
> `SearchDialWheel` takes the paper it is standing on as `fadeColor`: this sheet
> is painted in `background`, and fading to `surface` here turned the wheel's
> own dissolve into a visible plate over its top and bottom rows.

### The selection marks (`ui/theme/BrushMarks.kt`, `ui/theme/InkSpot.kt`)

Material's radio buttons, checkboxes and segmented containers are all forbidden
above, so the app draws its own marks. They live in one place and **any surface
may use them** — they are not Settings-only, which is where they started.

| Use | Component |
|---|---|
| Pick one of a few short options, side by side | `InkCircledChoiceRow` |
| Pick one of several longer options, stacked | `InkCircledChoiceColumn` |
| On / off | `InkCheck` — an empty ring that a brush check paints into |
| A quiet "this one" dot | `InkDisc` |
| Circle something that is *not* a plain text choice | `rememberInkBrushCircle` + `Modifier.inkBrushCircleTarget` / `Modifier.inkBrushCircleMark` |
| A short tool or icon strip | `InkSpotChoiceRow` / `InkSpotChoice` — circular vellum ink-drop. Effect alone: `Modifier.inkSpotHighlight` |
| Raw geometry (custom canvases) | `inkBrushCirclePath`, `inkBrushCheckPath` |
| Touch feedback on a pick / toggle | `View.paperSelectHaptic()`, `View.paperToggleHaptic(turningOn)` |

The ink-spot is a circular drop of ink on vellum: a round pooled
centre, a fibre rim, and a capillary halo that fades before the box
edge so nothing clips. It spreads in 170 ms. On Android 13+ the same
progressive-vellum pigment the contextual guides use (hash, fibre,
tooth, glaze) *is* the stain; older platforms keep three soft circles.

The circle is a filled calligraphic stroke on an oval centreline: it overshoots
its own join at both ends and bows outward on entry / inward on exit, so the tips
cross like a real hand's loop instead of closing into a ring. Pressure varies
along the stroke (`attack`, `releaseStart`, `bodyAmp`), and it **paints itself**
over `paintMs` rather than appearing.

`BrushCircleParams` / `BrushCheckParams` carry the knobs. Callers that don't care
get the shipped baseline by default; only Settings → Developer's brush lab passes
its own, and `SHIPPED_BRUSH_REVISION` / `SHIPPED_CHECK_REVISION` force the lab to
reseed when a baseline changes.

**The two platforms are locked to each other by test, not by good intentions.**
`BrushMarksTest` pins all 16 circle knobs, all 15 check knobs and both shipped
revisions to the exact values `web/src/ui/kit/__tests__/brushMark.test.ts` and
`brushCheck.test.ts` pin. Changing a baseline value fails on both sides until
both are changed — which is the point. Retuning is a two-file job, always.

The geometry is split the way `OrnamentGenerator` splits its ornaments: the pure
half (`inkBrushCircleOutline` / `inkBrushCheckOutline`) returns a `BrushOutline`
— the two edges of the filled ribbon as plain `Offset` lists — and `toPath()` is
the only Android-bound step, holding no logic. That is what makes the marks
JVM-testable at all: `androidx.compose.ui.graphics.Path` wraps
`android.graphics.Path`, which is stubbed in unit tests.

Because the mark is derived from each child's own measured bounds rather than the
container's, the same code loops a word in a `Row` and a stacked choice in a
`Column`. That is also why neither container stretches its children to the full
sheet width: `InkCircledChoiceColumn` sets every line to the measured width of
its longest label, so each mark in the stack shares one width and keeps a
hand-drawn ~4:1 loop. Stretched to the sheet, the same stroke came out 10:1 — two
parallel rules with caps, a lozenge rather than a loop. The width is *measured*
from the label text rather than taken from the column's intrinsics so that
`expanded` — the slot each choice can unfold beneath itself, and which is free to
be wider than the labels — can never change the size of the circle.

### Turning the sheet

Sheets turn with a horizontal swipe, handled by one stack-level detector
(`paperStackDrag` in `MainActivity.kt`). A finger stroke is never perfectly
flat, so the detector and the sheets' own vertical scrolling share the
gesture by these rules:

- **The dominant axis wins, decided early.** The moment total travel crosses
  the system touch slop — the same distance at which a scrolling list locks —
  the gesture commits to one axis: horizontal (with a slight vertical bias,
  `|dx| > |dy| × 1.1`, since reading is the primary activity) turns the
  page; otherwise the sheet's content scrolls. Deciding any later means
  vertical scroll claims nearly every swipe and page turns feel dead.
- **Children claim first.** The detector watches the Main pointer pass and
  backs off the instant a child control consumes the gesture, so sliders and
  scrolling lists keep working inside a sheet. A gesture that opened
  vertical over a static area can still become a page turn on a clear,
  sustained horizontal pull.
- **A short pull resistance** (~14 dp, capped at 4% of the sheet width)
  is subtracted from the drag so the sheet leans before it travels — paper
  has weight — but the sheet starts following the finger almost immediately.
- **One turn per gesture.** Releasing past 15% of the width, or flinging at
  0.30 screen-widths/s, commits the turn; a gesture never advances more than
  one sheet. The thresholds live beside the layer constants in
  `MainActivity.kt` and are the tuning knobs for swipe eagerness.

Vertical chapter boundaries in the reader are soft stops too. A scroll or
fling may arrive at the opening header or next-chapter footer, but it never
turns that leftover motion into a chapter jump. The reader must begin a fresh
pull from the boundary (or tap its explicit control) to change chapters. Pull
progress follows the finger in both directions, remains cancellable even after
filling completely, and commits only when released while still fully filled.

## The ink bleed

When the app must present something the system would normally raise as a
floating dialog or overlay sheet, it never floats a box. Instead **the sheet
you are already on becomes the message.** Ink bleeds outward from a point of
origin (the play control, a held word, …) as a soft-edged circle, soaking
**that sheet** (not a full-screen layer above the paper stack), and when it
settles the same surface reads as the new content. Closing opens a hole back
to whatever sat beneath — no push, no stack, no window.

While an ink bleed is entering, open, or receding, paper-stack swipes are
disabled. The gesture gate follows the overlay's rendered lifetime rather than
only its requested visibility, so a page cannot turn underneath a closing wash.

The shared composable is `InkRevealOverlay` (`ui/theme/InkReveal.kt`). Surfaces
that use it today / by design:

| Surface | Origin | What settles after the bloom |
|---|---|---|
| Repeat range | Player-bar repeat control | The repeat-mode / range question |
| [Root Word Viewer](ROOT_VIEWER.md) | Long-pressed word | Root, form, and concordance for that word |
| [Timings Lab](TIMINGS_LAB.md) | Long-pressed word (developer mode only) | The timing workbench |
| Ornaments Lab | Settings → Developer (developer mode only) | The procedural ornament generator workbench — explore/design/save seeds |
| [Share Send page](SHARE.md) | Share host / gather mode (not on player bar) | Ordered selection + text/image share (video later) |

The spreading ink *is* the paper metaphor here, not a tap ripple (taps still
never ripple — see Motion). Media-session playback controls do **not** need
`POST_NOTIFICATIONS` (Android exempts media sessions), so play never raises a
permission bleed.

## Ink

The word-by-word follow-along experience is the core value of the app. This is
not ornamental polish; it is the product. Every reading mode that follows
recitation must preserve a timed, animated word-by-word ink transition. A
renderer may change how it implements that transition to protect Arabic
shaping, glyph quality, or performance, but it may not degrade the experience
to static highlighting, whole-ayah highlighting, background blocks, or a
non-animated state change.

Highlighting is carried by **the letters themselves** — the Apple Music
lyrics treatment, never a background block:

| State | Ink |
|---|---|
| Plain (no recitation) | 100 % |
| Upcoming (in the active ayah) | 30 % |
| **Active (being recited)** | letters ink in one by one (below) |
| Recited | settles to 80 % over 450 ms |
| Non-active ayahs during playback | word-level Upcoming ink in every mode (already faint before handoff; no block-alpha brightening) |
| Ayah number mark (`﴿N﴾`) | Upcoming ink while the verse is recessed; fades to full over 400 ms when that verse is in focus (gloss, English, and Arabic-only) |

The active word doesn't switch on — it is *written*: a soft alpha band
sweeps across the glyphs (right-to-left for Arabic, left-to-right for the
English lyric), each letter fading from 35 % to full ink as the band passes.
The sweep is timed to the word's own timing segment — exactly the span the
reciter dwells on it, corrected for playback speed — so the last letter
finishes as the voice moves on. It is drawn as a one-word offscreen mask
whose progress is read only at draw time; nothing recomposes per frame
(`Modifier.letterFadeIn` in `ui/theme/Fade.kt`).

If a specialized scripture renderer cannot safely use the per-letter mask
because it harms glyph outlines or mark placement, the fallback is still an
animated word-timed ink fade from upcoming ink to full ink. The fallback must
remain synchronized to the current word's timing segment and must be treated as
a temporary renderer-specific compromise, not a relaxation of the product
principle.

While reciting, the top bar, reciter name, repeat, and speed fade to 8 % over
~520 ms (Material ease-in-out), leaving the words plus play/pause and
rewind/forward controls on the page. Pause, and it breathes back. Non-active
verses recess to upcoming ink over ~400 ms.

## Color

Two themes, both "paper":

- **Paper**: warm sepia off-white `#FAF3E8`, ink `#1C1B18`,
  deep green `#0E5C4A` as the single interactive accent,
  muted gold `#B8901C` reserved for Quranic ornament (ayah marks, surah
  numbers, the ۞).
- **Nightfall**: near-black charcoal `#0A0B0C`, parchment ink `#E8E2D5`,
  soft green `#7FB8A4`, warmer gold `#D9B44A`, deep repeat orange `#E06A18`.
  Nightfall and Royal Green share a **fresh-ink glint** `#F8E9BE`: a newly
  read word's ink lands white-gold — riding the same letter sweep, never a
  separate motion — and dries back to parchment over 1 s. A glyph-shaped halo
  makes it feel lit from within at the peak, then recedes with the tint. It is
  not a spotlight: no radial field, background glow, box shadow, or clipped
  word rectangle. See [GLIMMER.md](GLIMMER.md).
- **Royal green**: deep green `#062C24`, parchment ink `#E8E2D5`,
  soft green `#7FB8A4`, warmer gold `#D9B44A`, same deep repeat orange and
  fresh-ink glint as Nightfall.

Gold never marks interaction; green never decorates. One accent each.

**Ruby** `#B3122F` (paper) / `#D64358` (nightfall + royal green) is the one
deliberate third hue, and it belongs to saved-verse ink: the
[bookmark ribbon](#bookmark-ribbon) and recolored bars on the ayah selector
rail that show where marks fall in the chapter. It is walled off from gold
(ornament) and green (interaction) precisely so "my marks" can never be
misread as decoration or as a control — a bookmark is the reader's own ink,
not the app's.

## Type

- **Arabic**: KFGQPC HAFS Uthmanic Script — the King Fahd Complex reference
  typeface — at 30 sp base with 1.9 em leading (Uthmani diacritics need air).
- **English text face**: EB Garamond, bundled (regular, medium, semibold,
  true italic; latin + latin-ext merged so transliteration diacritics — ḥ, ā,
  ū — never fall back to a system face). Kerning, ligatures, and old-style
  figures on in running text (`'kern', 'liga', 'onum'`).
- **Display face**: Cormorant Garamond semibold for surah titles and
  headlines, where its tall fine-stroked capitals can breathe.
- **The reader's hand**: Cormorant Garamond *Italic* at weight 500 for verse
  notes, and nothing else — the chancery cursive Renaissance scribes wrote
  marginal glosses in, and the hand italic type was cut from. It is
  deliberately not EB Garamond Italic: the app's prose is EB Garamond, so its
  own italic reads as **emphasis** — the same voice leaning — instead of a
  second person writing on the page. This is a narrow, recorded exception to
  Cormorant's display-only rule; the 500 weight plus the note's 62 % ink keep
  the fine strokes from going wispy at note size. The roman Cormorant stays
  display-only. See [ANNOTATIONS.md](ANNOTATIONS.md).
- **English lyric mode**: EB Garamond regular, 22 sp with 1.5 em leading.
  It is one flush-left, ragged-right inline paragraph — never a flex/flow row
  of padded word tiles. Natural font spaces, kerning, common ligatures, and
  old-style figures remain intact across the 45–75-character target measure;
  the word spans exist only for karaoke paint and interaction. Web uses
  `text-wrap: pretty` as a progressive enhancement and never justifies or
  hyphenates this word-addressable text. Its trailing Hafs verse mark is about
  0.78 em. Web uses a calibrated paint lift for its browser font metrics;
  Compose shares the English prose baseline because Android reports different
  metrics for the same Hafs ornament.
- **English gloss punctuation**: a display-only period closes each ayah.
  Existing source punctuation is preserved, but capitalization never creates
  an inferred sentence boundary. This policy never mutates the database text
  or the timing/search identity of a word.
- **Translations**: EB Garamond, 17 sp, 26 sp leading, at 66 % ink.
- **UI text**: the same serif at small sizes with letterspacing and reduced
  alpha; labels never compete with scripture. Nothing in the app is sans.
- Ayah markers are typographic — gold `﴿٧﴾` ornate brackets in the Hafs
  face — not drawn ornaments.

Reference points: **Unread** (iOS RSS reader) for chrome-free typographic
lists and reading view; **Apple Music lyrics** for the word illumination and
the recede-while-playing behavior. Running-text rules follow Bringhurst's
*Elements of Typographic Style* (45–75 character measure, natural word space,
ragged-right setting, leading proportional to type size) and WCAG's text
spacing / visual-presentation guidance.

### Information surfaces: learned rules

Lexicons, search results, bookmarks, and settings are quieter than scripture,
but they still need a decisive composition. "Calm" must not become faint,
empty, or ambiguous. Apply these rules before polishing individual elements:

- **Answer first.** The first viewport carries the reason the surface opened.
  A held-word view must reveal the word, root, form, and occurrence summary
  before asking the reader to scroll. Ceremony belongs to the cover and surah
  opening; an information surface earns space through comprehension.
- **One reading spine.** A centred opening may introduce the subject once;
  everything after it returns to one primary left edge, with one deliberate
  inset for owned detail. A layout with separate label, Arabic, list, and
  metadata axes makes related facts look unrelated. RTL controls Arabic
  shaping and reading order, never the page alignment: Arabic elements are
  bidi-isolated and intrinsically sized on the same content spine.
- **Proximity expresses meaning.** Space is not decoration. Use 4–8 px/dp
  inside one fact, 12–16 inside a row, 24 between related subgroups, and about
  40 between major reader questions. Avoid viewport-height gaps: they grow
  without adding meaning and push useful content below the fold. Parent gaps
  own rhythm; arbitrary child margins do not.
- **Use a small, explicit type scale.** The held subject is the only hero.
  Section titles organise, Arabic forms and chapter names carry the content,
  and labels/counts remain subordinate. Do not create a new near-equal size
  for every fact. Arabic needs generous line height for marks and optical
  checking with heavily vocalised words, not only simple test strings.
- **Quiet is still legible.** Essential explanation uses confident secondary
  ink, not several layers of muted colour plus transparency. Reserve the
  faintest ink for genuinely optional metadata. Colour keeps one meaning:
  gold is Quranic reference/ornament, green is action, and neither replaces a
  textual current, selected, or expanded state.
- **Remove implementation language.** Readers need "This root occurs 36
  times," not "Root annotated 36 times" or "five corpus analyses." Show a
  count once, where it changes understanding or predicts what an action will
  reveal. Do not repeat the current item inside a related-items list.
- **Design against the largest real record.** A five-row mock-up is not proof.
  Validate with roots spanning thousands of occurrences, tens of analyses,
  and most of the 114 chapters. Progressive disclosure limits initial work,
  keeps only one dense group open, preserves Quranic order, and always keeps
  the reader's current context in view.
- **Affordance without chrome.** A whole row may be the tap target, but its
  state still needs information scent: a compact count, disclosure mark,
  visible focus, 44 px/dp target, and content motion. No ripple, container,
  or divider is required. Nested content aligns beneath its owning label, not
  merely some number of pixels from the sheet edge.
- **Responsive means stable relationships.** Phones and wide screens share
  the same hierarchy and semantic indents. Do not invent desktop columns for
  facts that are read sequentially. Change measure, padding, and wrapping;
  do not change what belongs together.
- **Break a rule narrowly and record why.** An exception must solve a named
  reading problem, remain local, and be impossible to mistake for a reusable
  decorative habit. The root viewer's major-heading rule below anchors the eye
  above noisy reference data; it does not license borders between rows or
  around content.

Review information surfaces with real text at a narrow phone and a wide paper
sheet. Check the first-viewport content budget, repeated left edges, RTL/LTR
isolation, touch and keyboard affordances, contrast, long labels, and expanded
states. If a relationship is unclear, fix order, proximity, or alignment
before adding decoration.

### Root lexicon typography

The Root Word Viewer is an information page, not a ceremonial chapter
opening. It uses one centred held word, then one left-hand reading spine. Its
interactive measure is at most 40 rem / 640 dp and prose is at most 34 rem /
544 dp. Vertical space follows semantic proximity: 4–8 px/dp within one fact,
12–16 within a row, 24 between related subgroups, and 40 between major reader
questions. Viewport-height spacing is forbidden here; it turns tall screens
into empty stages and pushes the answer below the fold.

Arabic and English occupy separate bidi-isolated elements. The Hafs scale is
44–52 for the held word, 32 for radicals, and 24 for forms and occurrences;
English uses 24 Cormorant section headings, 17 EB Garamond body/actions, and
12–14 labels/metadata. Gold identifies Quran references, green identifies
actions, and neither substitutes for a textual current/open state.

The lexicon's two major headings — Occurrences and Related forms — may carry
a 1 px/dp rule at 9% parchment ink from the title to the right edge of the
content measure. It is part of the heading lockup, not a border or row
divider: it never reaches the sheet edge, never appears between rows, and
never uses gold.

Classical lexicon (Lane) is still one reading spine, never a card grid: Form
labels sit as quiet Cormorant lines with ~28 dp before each new Form and
~12 dp between senses inside one Form — no bullet marks. Parenthetical source
marks (`(S, K)`, `(Msb,)`), editorial tags (`(tropical:)`), and bare
`see …` cross-refs drop to ~38% ink so the English gloss can be scanned. Do
not invent sense boundaries the source never marked.

## Ornament

Traditional, geometric, and nearly invisible — ornament whispers, never
speaks. All of it is drawn procedurally (`ui/theme/Ornament.kt`), never an
image, so it is crisp at any density and nearly free to render.

- **Khatam geometry.** The vocabulary throughout is the classical star:
  {n/k} star polygons (8/10/12/16-fold; gcd(n, k) > 1 yields the interlaced
  polygons — the khatam is {8/2}) and Hankin's polygons-in-contact method
  (rays from tile-edge midpoints at a sampled contact angle) — the same
  figures that generate star-and-cross tessellation in traditional
  tilework.
- **The generating machine.** Ornament on both the entrance cover and the
  open book is not fixed: a seeded generator
  (`ui/theme/ornament/OrnamentGenerator.kt`, mirrored line-for-line in
  `web/src/ui/theme/ornamentGenerator.ts` over an identical mulberry32
  stream) composes the geometry from that vocabulary. The cover's medallion,
  corner seals, border frieze, and leather field all grow from one random
  seed per launch. The **surah header is seeded per chapter** instead — one
  `chapterOrnamentSeed(chapterNumber, ayahCount)` grows both the rosette
  *and* the field tooled behind it (`ChapterOrnament`/`generateChapterOrnament`),
  so the header's whole ornament is this chapter's own, not a rosette
  sitting on a pattern every chapter shares. The seed folds the chapter's
  verse count (the dominant term, so a chapter's length reads as its
  ornament's fingerprint — chapters of similar length grow kin-looking
  rosettes) with its number (so all 114 chapters render distinctly even
  though only 77 of them have a verse count no other chapter shares); the
  same chapter always regrows the same ornament. Border friezes (cover
  only) come from five band grammars of tooled bindings: a zigzag lattice
  with pearls in its diamonds, a two-strand cable with pearl eyes, a Hankin
  strip, a nested lozenge chain, and a khatam chain of small eight-fold
  stars linked by diamonds. On the cover, the **medallion and field build
  in real time** — strokes ink themselves in along their length across
  ~3.4 s, field first, then medallion outside-in — so the illumination is
  drawn before the reader's eyes, not stamped; the **border and corner
  seals are static**, complete from the very first frame, the binding's
  tooling rather than illumination. The **surah header's rosette and field
  are likewise static** — fixed page typography, not a ceremony. One
  absolute rule, enforced everywhere this generator draws: no composition
  may read as an occult compound — star indices that decompose into
  triangles ({12/4} → hexagram) or into interlaced pentagrams
  ({10/4} → two {5/2} pentacles stacked), 6-fold seals, and 6-fold field
  tilings are all excluded by construction and guarded by tests on both
  platforms.
- **Gilding.** Gold is never a flat color. Gilded elements (the surah
  rosette, ayah number marks, the home mark) carry a three-stop leaf
  gradient (deep bronze → bright gilt → deep bronze). On the reader, the
  gradient's lighting axis tilts with page scroll, so light appears to catch
  the leaf as the sheet moves — computed at draw time only, animating
  exclusively on scroll frames.
- **Embossing.** Ornament is pressed into the paper: each figure is drawn
  with a dark copy nudged to the lower-right and a light copy to the
  upper-left beneath its face — relief under a top-left light, subtle enough
  to be felt more than seen.
- **The weave.** Behind each surah opening, that chapter's own generated
  Hankin field at ~4 % ink, embossed, dissolving into the page at its
  edges — grown from the same seed as the rosette sitting on it (see "The
  generating machine" above), not a fixed pattern every chapter shares.
- **Basmalah.** Every surah except Al-Fatihah (where it *is* ayah 1) and
  At-Tawbah (which has none) opens with the basmalah as traditional Naskh
  manuscript calligraphy beneath the chapter title — ink on the page, not a
  numbered ayah and not ornament. It is its **own list item** (above ayah 1)
  so the focus engine can home, place, and return onto it the same way it
  does for any verse while the lead-in plays. The VectorDrawable
  (`basmalah_naskh`) is adapted from Wikimedia Commons File:Basmala.svg
  (Baba66, CC BY-SA 3.0). It is an InkEngine **calligraphy render path**: an
  RTL `letterFadeIn` wash advances across the SVG **on the clip's own word
  timings** — each word owns the band of artwork its glyphs cover, paced inside
  it by tajweed — so the ink reaches ٱللَّهِ as the reciter says "-llāhi" and
  settles as the closing madd of ٱلرَّحِيمِ ends, not at a fixed fraction of the
  file (`BasmalahWash`); Upcoming while another ayah is recited; Plain at rest. Starting playback from ayah 1 (or
  tapping the calligraphy) prepends Al-Fatihah 1:1 audio before the first ayah;
  word taps skip the lead-in.
- **Restraint rule:** ornament appears in exactly three places on the open
  *scroll* reader — the surah header (rosette + weave), ayah number marks, and
  the home title mark. Mushaf layout carries **no ornament at all** — no
  rule, no band, no corner seals: the printed line and its circled ayah
  marks are the whole page, and every pixel the frame used to hold is text.
  A compact chapter rosette still opens a new surah. Never lavish — the closed
  book is still the only place allowed to carry full binding gold. Mushaf
  **page breaks** in scroll layout are not ornament: a whisper-gold hairline
  with small page numbers (Western left, Arabic-Indic right) marks Madinah
  page boundaries in the continuous scroll — functional wayfinding only. The
  mushaf text is KFGQPC Uthmanic Hafs v22, one RTL run per Madinah line,
  word-gap justified to the page width — not QCF page fonts (those fought
  the ink wash).

## The entrance

The paper metaphor begins before the first sheet: a cold start opens on the
**closed mushaf** (`ui/entrance/EntranceCover` on Android and web), and chapter
selection is what the reader finds when its cover turns. The ceremony has
three moments:

1. **Arrival.** The system splash *is* the board: the same deep-green
   leather (`CoverLeatherCenter` / `@color/cover_leather`) with no app
   icon, held on screen until Compose lays out the closed mushaf, then
   handed off with no second fade.    Cold start composes the cover first; chrome and this launch's ornament
   are pre-built in `onCreate`, and the paper stack mounts under the board
   only after two cover frames (not at splash handoff) so ViewModel init
   cannot jank first paint. Safe-area insets (system bars ignoring
   visibility ∪ cutout) and screen-corner radii come from
   `WindowManager.currentWindowMetrics`, so the gilt frame is correct on
   the first Compose frame and splash hands off without waiting for
   Compose's inset mirror. Fixed across themes — a bound book
   keeps its own boards (`Theme.kt`'s `Cover*` values) — framed in a
   doubled gilt rule (`MushafCoverFrame`) with the generated border
   frieze running between the rules — each side is a railed channel
   fitting a whole number of pattern periods, and its mouth tapers onto
   the corner seal's petal tip (each seal wears a four-petal ogee bezel
   whose tips aim down the band axes), so border and corner ornaments
   are one continuous piece of geometry. The frame, border, and seals
   are the binding's tooling and render complete on the very first
   frame — no wash, no fade. Tooled *inside* that frame, this launch's
   generated Hankin field (whisper ink) and the generated medallion
   (`GeneratedMedallion`) with the title **القرآن الكريم** beneath it
   in the Hafs hand, leafed in gold, ink themselves onto the leather in
   real time as the ceremony arrives — the illumination, drawn before
   the reader's eyes.
   The frame's inset and corner radii are derived from the display's
   rounded corners (`WindowInsets.getRoundedCorner`, via
   `coverFrameGeometry`) so the gilt rule is concentric with the phone's
   silhouette — the cover reads as cut for that screen, not a fixed square
   border floating inside it.
2. **The isti'adha.** After a brief hold, **أعوذ بالله من الشيطان الرجيم**
   fades in as text — Arabic letter-washed onto the cover, English italic
   beneath — a quiet pause before reading begins. No audio: the du'a is
   written, not recited.
3. **The opening.** After the du'a settles, the board swings open on its
   **left** hinge — the free edge comes **toward the reader** (out of the
   screen, facing them — not into the page) and travels right→left — slower
   and heavier than a page (1150 ms vs 460 ms), with the flip stems pitched
   slightly down (`PageTurnSounds.playCoverOpen`), revealing the chapter
   list beneath. Both platforms use a negative Y rotation for the outward
   open (`rotationY(-95°)` on Android, `rotateY(-95deg)` on web) — positive
   folds the free edge into the page. Android keeps a close
   `cameraDistance` (8× density) so the free edge foreshortens toward the
   reader; a far camera flattens the swing into a 2D squash. On web the cover
   matches the paper-sheet column (`--sheet-half`), with corner ornaments
   kept square.

(The web entrance is also the loading screen: the cover is up from the first
paint while `quran.db` streams in, with progress inked onto the leather; the
du'a wash and hinge open wait until the book is ready. Frame insets, corner
seals, medallion, and type are derived from the live board width × height via
a 48-unit modular grid (`coverLayout`) so ornaments stay pressed seals — not
fixed pinpricks — across phone and sheet-column boards.)

A tap anywhere (or back) opens the cover at once; the ceremony never holds a
reader hostage, and it plays only once per session (`rememberSaveable`), so
rotations and process restores land straight on the sheets. The title letter
wash runs once on arrival (both Arabic and English from the right); skip
cancels the in-flight moment without replaying it. The status bar is hidden
for the ceremony so the leather reads full-bleed, and restored when the
cover leaves. The hinge turn is the motion-rule counterpart of the bookmark
ribbon: the cover is the only other physical *object* in the app, and its
one rotation is the page-turn gesture the stack already speaks, at board
weight.

## Motion

- Everything ≤ 400 ms except the deliberate slow moves: chrome recede
  (520 ms), verse ink recess (400 ms), ayah dim (600 ms), and the
  hand-initiated ayah-jump scroll
  (≈280 ms nearby → a full **1000 ms** for a ~200-verse jump — a fast
  decelerating rush across a distance-scaled stretch of verses, truncated
  so it never waits on a surah-length trajectory).
- Motion is always a fade, a slide, or both; nothing bounces, scales, or
  spins. The bookmark ribbon (below) is the nearest thing to an exception — it
  unfurls with a gravity drop, a soft overshoot, and a settling flutter — the
  only physical *object* on the sheet rather than ink in it, and the only
  motion allowed a touch of whimsy.
- Auto-scroll keeps the active ayah in the upper third and yields instantly
  to the reader's hand; the return-to-ayah roundel (gilt corolla + painted
  qalam arrow) offers the way back, pointing up or down toward the verse.
- On the chapter list, when a verse is loaded (playing or paused mid-session),
  the floating playback control slides up with the same fade + vertical
  motion as the reader's return-to-ayah / Back-to ornaments
  (`FloatingPaperControl`), using the shared **10 dp** bottom inset. The
  current chapter and ayah form a compact quiet-green stadium with a right
  arrow: tapping it returns to that verse in the reader. The
  enter/exit is also tied to the paper-stack page turn: returning to chapter
  selection plays the entrance; leaving for the reader plays the exit. A
  quiet Close in the corner dismisses the session (stops playback) so the
  bar exits the same way. The list's soft bottom dissolve sits **just above
  the float** (`verticalFadingEdges` `bottomInset`), not stretched through
  it — same paper edge as the reader above its embedded `PlayerBar`.
  Opening the reader replaces the float with that bar.

## Bookmark ribbon

Part of each verse block — not a floating overlay that tracks the list. The
selector answers *where do I go*; the ribbon answers *what did I mark*. The
ayah rail also carries ruby for saved verses: on the collapsed stack the
nearest existing bar is recolored (no extra mark), and on the expanded wheel
bookmarked ticks and numbers tint ruby so marks stay visible while scrubbing.
Gold still means dial focus — a bookmarked ayah under the finger is gold, not
ruby. The ribbon lives in the block's outer margin on the edge **opposite**
the ayah selector (the selector side is a setting), and both ribbon and rail
marks obey the same chrome rules: they fade with the rest of the chrome and
vanish entirely while reciting.

The wheel also carries mushaf wayfinding in the faintest ink: where a new page
opens, a tiny diamond and the page number trail the tick's ayah number, fading
with focus and edge distance like every other mark on the rail.

- **Unified with the verse.** The ribbon is composed inside `AyahBlock`, so its
  height *is* the block's height (Arabic, gloss, and translation together). It
  cannot drift, lag, or sit mid-block — the tip begins 24 dp below the block
  edge, aligned with that verse's first line of ink, left or right.
- **Every verse carries a tip; a saved verse carries the whole ribbon.** An
  idle verse shows only the short, opaque playback-ink outlined swallowtail
  **tip** beside the first line — the same monochrome ink as the play button,
  but empty so
  ruby fill remains exclusive to saved verses. (On web, that tip stays hidden until the verse is hovered,
  then click to unfurl.) Bookmark it and that tip grows nearly the **full
  vertical length** of the block at full ink, stopping **48 dp** above the next
  verse's tip so consecutive ribbons never touch.
- **Marking is where you tap.** A tap on the verse's ribbon margin marks (or
  unmarks) *that* verse — the tip on the verse you are reading burns a little
  brighter so the affordance finds your eye. On an exposed saved ribbon,
  press and hold opens that verse's note; unsaved tips do not offer notes.
- **The unfurl.** On mark, the tip *spills down the block* with a gravity drop
  (slow peel, then accelerates), a traveling cloth wave, a soft overshoot past
  the block bottom, then a spring settle and a single underdamped flutter.
  Taller verses take proportionally longer so the speed reads the same.
  Unmarking gathers the strip back into the tip.

Implementation: `ui/reader/VerseBookmarkRibbon.kt`, drawn per verse inside
`AyahBlock` (`ReaderComponents.kt`). Bookmarks persist in their own
SharedPreferences store (`data/BookmarkRepository.kt`), never in the read-only
`quran.db`.

Once a newly marked verse returns to Chapters, the same physical ribbon used
inside its verse block unfurls beside the title and runs to just above the page
bottom, then remains exposed on the left edge. Its drawing stays fully unfurled
while a separate navigation target opens Bookmarks, so tapping it—or
pulling right from Chapters—never plays a retract before the Bookmarks sheet
slides in from the left above it. This is a real page in the paper stack, not an
overlay.

### Chapters ribbon geometry

The long Chapters ribbon is a structural part of the chapter document, not a
decoration laid on top of it. Its visible ruby cloth begins beside the title,
while its touch target remains available without making the cloth itself a
wide, blank-looking control. Android permanently reserves a 28 dp Home lane,
even before the first bookmark exists, so the chapter composition never moves
when the ribbon appears. The lane matches the opposite page gutter exactly:
7.5 dp of outer air, a 13 dp ribbon, then 7.5 dp of inner air. Its 28 dp target
is a narrow Home-only exception to the usual 44 dp minimum; verse ribbons and
the Bookmarks index keep their full 44 dp targets and 11 dp cloth.

On Android, that lane is fixed to the viewport: scrolling the chapter list
must never move the ribbon. It begins beside, and slightly below the top of,
the single Cormorant “Beautiful Quran” masthead and keeps a small, optically
balanced paper gutter on both sides of the ruby cloth. The Chapters masthead
and settings rosette remain fixed above the scrolling chapter document,
matching the web composition. The masthead does not repeat the Arabic app
name; Arabic remains where it identifies Quranic content. The ribbon folds
softly over the top page edge with two restrained rounded corners, matching
the web renderer without turning its cap into a pill.

There is one content rule beside that lane. On a bookmarked Chapters page,
the title, search field, and gold chapter-number column all begin on it. The
surah name may form a second, inner reading column, but no heading may drift
back to the paper edge simply because it is larger. This alignment is checked
as a relationship, not as unrelated platform padding values: web and Android
may use their own layout primitives, yet must preserve the same visible
anchors. Keep the gold chapter number close to its transliterated name; the
number lane must not read as an empty decorative gutter.

The Android and web search pills own an even 16 dp/px internal inset on both
ends; icon and text spacing is independent of the chapter-number grid.
Continue listening is the one full-bleed row in the scrolling chapter
document: its quiet green wash and tap target reach both sheet edges, while
its bilingual text remains on the same inner English and Arabic rails as the
chapter rows. Both platforms keep the 28 dp/px outer lane, 26 dp/px number
column, and 4 dp/px name gap; the fixed masthead pairs its title with the same
30 dp/px gilded rosette Settings affordance.

The ribbon's drawing and navigation are deliberately separate. Reuse
`VerseBookmarkRibbon` for the cloth; put its Home/Chapters navigation on an
adjacent quiet target (28 dp on Android Home, 44 px on web). That keeps the
ribbon fully unfurled while the Bookmarks sheet opens, prevents a mark/unmark
animation from being borrowed as navigation feedback, and leaves the shared
component authoritative for its own visual language. Do not create another
bookmark icon, a second ribbon shape, or a bespoke animated substitute.

### Chapters bookmark treatment

Android and web developer settings compare two quiet placements without
changing the bookmark language: a top-bound ribbon and a typographic *Saved
passages* line whose number-column mark is the same ruby cloth. The web ribbon
is 96 px tall; Android begins at the physical screen edge and carries its 96 dp
body below the status-bar inset so the tip clears the title. Both appear only
when a bookmark exists, open the same Bookmarks sheet, and reuse each
platform's `VerseBookmarkRibbon`; verse-level ribbons in the Reader never
change.

The selector is visible only in developer mode, but its persisted choice stays
active if developer mode is hidden again. The inline treatment is omitted
during active search so results remain answer-first. Its second line reports
the live bookmark count as “1 saved ayah” or “12 saved ayahs.” The top-bound
treatment retains the fixed 28 dp Home lane and separate
quiet navigation target, so switching treatments never moves the chapter document
or turns cloth animation into navigation feedback.

The bookmark index is a compact bilingual concordance, shared by Android and
web. It uses one centered column (560 dp / 36 rem maximum) and a fixed 40 dp/px
inner content spine from the outer title rule. The gold number belongs to the
outer index lane, the English chapter name begins on the inner spine, and the
isolated RTL Arabic name ends just before the fixed trailing disclosure lane.
Verse copy balances that spine with an equal trailing gutter so its reading
measure remains centered. Entries then stack Arabic at 24/36, translation at 17/25, and
metadata at 14/20; the title is the only display-sized element. Spacing, not
rules or containers, separates sections.

The Bookmarks header contains only its title and the Chapters return action;
the total marked-verse count is deliberately omitted.

Within the fixed outer lane, the visible ruby edge keeps its 2 dp/px optical
inset while the narrower gold chapter number begins 4 dp/px from the
title/search rule. Those offsets are taken from the lane's right-hand air:
they never widen the 44 dp/px ribbon target or change the page measure. Search
text, chapter names, verse copy, metadata, and disclosures all begin on the
same 40 dp/px inner spine; the Arabic chapter name reaches a fixed rule before
the shared 20 dp/px chevron and its 16 dp/px air, while Arabic verse copy
retains the sheet's outer rule below it.

The index follows one vertical rhythm on both platforms: 24 dp/px from title
to search and from search to the first chapter label, 32 dp/px before later
chapter groups, 12 dp/px below a chapter header, and 8 dp/px between Arabic
verse copy and its translation. The fixed-height ayah line supplies its own
breathing room, so no extra spacer sits between translation and metadata.

### Bookmark index alignment lessons

- **Name anchors before assigning padding.** This surface has four: the outer
  title rule, the 44 dp/px index target, the 40 dp/px inner reading spine, and
  the fixed trailing disclosure lane. Each value expresses one relationship.
  Scattered child padding recreates almost-aligned edges that fail as soon as
  the viewport or platform changes.
- **Visible ink and interaction geometry are separate.** The ribbon keeps a
  44 dp/px target while its narrow cloth sits optically inside it. Moving the
  cloth or chapter numeral must not move the target, widen the gutter, or push
  the reading spine. The shared ribbon component remains the authority for the
  cloth; page layout owns only its placement.
- **Optical equality is not numeric equality.** A wide ruby strip and a small
  gold numeral do not look centered at the same inset. Their 2 and 4 dp/px
  offsets are intentionally different, but both are measured inside the same
  lane. Tune the marked element, not the whole grid.
- **Bilingual alignment needs both edges.** English search text, chapter names,
  translation, and metadata begin on the inner left spine. Arabic chapter
  names end before the aligned disclosure lane while verse copy keeps the
  sheet's far-right rule. RTL direction shapes and orders Arabic; it does not
  place the element on the page.
- **Measure rhythm between visible ink, not only boxes.** A 52 dp/px search
  field and a 44 dp/px metadata target contain internal air. Center text inside
  those targets, then judge the visible title-to-search, search-to-section,
  section-to-verse, and translation-to-reference gaps. Adding a spacer beside
  an already centered target doubles the intended breathing room.
- **A section should establish context once.** The chapter header owns the
  chapter number and bilingual name. A bookmark row repeats only its ayah
  number; repeating the chapter name or full reference weakens hierarchy and
  makes a one-result section feel busier than it is.
- **Verify relationships with real narrow data.** Check x-coordinates for the
  outer rule and inner spine, right edges for Arabic, and ink-to-ink vertical
  gaps with a wrapped translation and a vocalized Arabic verse. Web and Compose
  may use different primitives, but these measured relationships must match.

Results remain in Quranic order and can be searched by reference, chapter
name, or verse text. Tapping a chapter header folds or unfolds its complete
section. The shared disclosure chevron points down to open and up to fold;
its fixed trailing column keeps every chapter row aligned, with the Arabic
name ending on the neighboring rule. A chapter initially shows
five marked verses; its green inline disclosure reveals the remainder, while
an active search temporarily unfolds every matching chapter so no result is
hidden. The section header establishes the chapter once; an individual result
shows only its ayah number. The small ruby strip beside a result opens an
inline Keep / Remove confirmation in that ayah line's fixed-height space before
changing the mark, so the page does not jump. Keep is green, Remove is quiet
ink, ayah numbers are gold, and ruby remains exclusive to the physical ribbon.
An empty ayah line offers a quiet green *Add note* action; existing italic note
text edits in place when tapped, and holding the exposed ribbon opens the same
chromeless editor. Tapping the verse copy returns to it in the reader. The long
Chapters ribbon is navigation only and never retracts when tapped.

## Reading modes

- **Arabic & English** — Arabic flows right-to-left, the English gloss under
  each word; optional transliteration; the flowing translation below.
- **English** — the gloss becomes the lyric line itself, flowing
  left-to-right and lighting word-by-word on the same timings.
  Web page breaks centre one Western folio number between equal visible gold
  rules; the mirrored Arabic-Indic number is reserved for Arabic modes.
