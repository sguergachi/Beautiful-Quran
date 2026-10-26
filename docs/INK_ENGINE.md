# InkEngine

The design (and now implementation) that unifies the reader's word-by-word
visual effect without turning it into a custom text renderer.

**Status: implemented.** The policy layer lives in
`app/src/main/java/com/beautifulquran/ui/reader/InkEngine.kt`; see
"As implemented" at the end of this document for what shipped and how the
developer-mode Ink Lab exposes the tuning live. The sections between here and
there are the original proposal, kept as the design record.

## Goal

Make the highlight look easy to polish across Arabic, English, and Arabic-only
Hafs modes while keeping the codebase thin.

The desired composition is:

```text
Focus engine      -> which ayah has the reader's attention
HighlightEngine   -> which word the reciter is on
InkEngine         -> how each word's ink should behave
Renderers         -> how each mode draws text safely
```

Each layer should return simple data to the next layer. None of the engines
should own more than one job.

## Current tension

The current system has the right major pieces:

- `FocusEngine` owns verse placement and scroll focus.
- `HighlightEngine` owns audio timing, active word lookup, repeat detection,
  and high-water state.
- Reader composables draw Arabic, English, and Arabic-only text.

The problem is that renderers also know too much about visual highlight policy:
upcoming alpha, active/recited state, repeat-chain participation, sweep timing,
secondary-line fade, and ayah mark fade are related decisions but are spread
through the reader UI.

That makes the effect harder to tune. A small polish change can require hunting
through multiple rendering paths.

## Proposed answer

Add one small reader-ui layer:

```text
app/src/main/java/com/beautifulquran/ui/reader/InkEngine.kt
```

`InkEngine` should be the single place that converts focus + highlight timing
into per-word visual behavior.

It should answer:

> Given this word, the active word, and whether this ayah is focused, how should
> the word's ink behave?

A deliberately small shape:

```kotlin
object InkEngine {
    enum class State {
        Plain,
        Upcoming,
        Active,
        Recited,
    }

    data class Word(
        val state: State,
        val repeat: Boolean,
    )

    fun word(
        position: Int,
        activeWord: ActiveWord?,
        isActiveAyah: Boolean,
        dimmed: Boolean,
    ): Word

    fun sweepMs(
        activeWord: ActiveWord?,
        playbackSpeed: Float,
    ): Int?
}
```

The exact names can change during implementation, but the shape should stay
small: one object, one word state, one word result, a few helper functions.

## Composition

### Focus engine

Owns ayah-level attention:

- focused ayah
- dimmed ayah
- programmatic scroll/follow behavior
- verse geometry and placement

It should not know about word alpha, repeat wash, or text masks.

### HighlightEngine

Owns recitation timing:

- active word lookup
- active segment duration
- repeat-aware backtracking
- high-water position
- repeat start

It should stay pure and renderer-agnostic. No Compose, no colors, no alpha, no
masking.

### InkEngine

Owns visual word policy:

- plain/upcoming/active/recited state
- whether the word participates in repeat wash
- sweep duration clamps
- shared alpha constants
- secondary gloss/transliteration fade policy
- ayah mark fade policy, if useful

It also firmly owns the *motion policy* — the tween-versus-snap rules, the
start-revealed flicker rule, repeat wash timing, feather width — because that
is where the highlight's feel actually lives. It may expose small Compose
animation helpers if that reduces reader code, but it should not become a
rendering framework.

### Renderers

Own actual text drawing:

- Arabic gloss renderer can use layered word `Text`.
- English renderer can use layered word or phrase `Text`.
- Arabic-only Hafs renderer should keep the shaped full-ayah text path and draw
  overlays/masks against that layout.

Renderers should consume `InkEngine.Word`; they should not re-derive highlight
semantics themselves.

## Options considered

### Option 1: Do nothing

Keep the current renderer-local logic.

**Pros**

- No immediate code churn.
- Existing behavior remains untouched.

**Cons**

- Polish knobs stay scattered.
- Arabic, English, and Hafs modes can drift over time.
- Reader composables keep mixing layout with highlight policy.

This is acceptable short-term, but it does not solve the tuning problem.

### Option 2: Thin InkEngine policy layer

Add `InkEngine` as a small state/policy layer between `HighlightEngine` and the
renderers.

**Pros**

- One place to tune the highlight feel.
- Keeps `HighlightEngine` pure.
- Keeps Arabic-only rendering specialized and safe.
- Low conceptual cost: `FocusEngine`, `HighlightEngine`, `InkEngine` each answer
  one question.
- Can be implemented with little code movement and little/no visual change.

**Cons**

- Adds one more named layer.
- Requires touching all reader rendering branches to pass `InkEngine.Word`.

This is the recommended approach.

### Option 3: Full InkEngine renderer

Move all animation, masking, wash drawing, and mode-specific text rendering into
one large InkEngine.

**Pros**

- Everything visually related appears to live in one place.
- The name sounds complete.

**Cons**

- Likely becomes a dense mixed-responsibility file.
- Risks hiding important Arabic-only shaping constraints.
- Encourages generic renderer abstractions that add code without improving the
  product.
- Pulls the project toward a custom text engine rabbit hole.

This is not recommended.

### Option 4: Generic renderer interface

Introduce something like `HighlightRenderer` / `InkRenderer` with separate
implementations for Arabic, English, and Hafs.

**Pros**

- Looks tidy from far away.
- Mode-specific renderers get explicit names.

**Cons**

- Adds indirection and more files.
- Does not remove the hard part: Arabic-only must draw against shaped ayah text.
- Makes simple Compose code feel framework-like.

This is not recommended unless the renderers grow much larger than they are
today.

## Recommended architecture

```text
FocusEngine
    pure / controller-backed scroll focus
    outputs ayah attention

HighlightEngine
    pure timing engine
    outputs ActiveWord timing facts

InkEngine
    reader-ui policy layer
    outputs InkEngine.Word for each word

ReaderComponents
    mode-specific layout and text drawing
    consumes InkEngine.Word

Fade.kt
    low-level draw modifiers and wash/mask primitives
```

`Fade.kt` should generally keep the draw primitives (`letterFadeIn`, shaped
bloom/mask drawing). `InkEngine` should decide what should happen; draw
modifiers should decide how to draw it.

## Implementation plan

### Step 1: Extract word ink state

Create `InkEngine.kt` and move the renderer-local word state logic into it.

Expected behavior: no visual change.

Tasks:

- Add `InkEngine.State`.
- Add `InkEngine.Word`.
- Add `InkEngine.word(...)`.
- Move active/upcoming/recited/repeat-chain derivation out of `AyahBlock`.
- Compute each ayah's word ink list once.
- Pass `InkEngine.Word` into Arabic, English, and Arabic-only branches.

### Step 2: Centralize tuning constants

Move shared visual constants and small policy functions under `InkEngine`.

Expected behavior: no intentional visual change.

Candidates:

- upcoming alpha
- full ink alpha
- min/max sweep duration
- repeat wash duration
- default repeat sweep duration
- secondary text fade
- ayah mark fade duration

Keep constants private unless another file genuinely needs them.

### Step 3: Keep drawing primitives where they belong

Leave low-level drawing in `Fade.kt` unless moving a helper clearly makes the
code shorter and easier to read.

Avoid moving all Canvas/mask code into `InkEngine.kt` just because it is
visually related.

### Step 4: Optional tests

Add JVM tests for `InkEngine.word(...)` if the extracted logic is non-trivial.

Useful cases:

- inactive ayah words are plain or upcoming according to current behavior
- active word is active
- previous words are recited
- future words are upcoming
- repeat chain marks the correct words as `repeat = true`
- high-water keeps already-recited words lit during repeat

Do not UI-test animation unless a specific regression requires it.

## Non-goals

InkEngine should not:

- replace `HighlightEngine`
- know about SQLite or timing data loading
- own scroll/focus behavior
- own Arabic shaping
- own `FlowRow` or `LazyColumn` layout
- become a custom glyph/text engine
- introduce renderer interfaces by default
- create a broad animation DSL

If implementation starts needing many files, interfaces, or renderer classes,
that is a signal to stop and simplify.

## Design principle

Unify the rule, not the renderer.

Arabic, English, and Arabic-only should share the same ink decision:

```text
Upcoming / Active / Recited / Repeat
```

But they do not need to share the same drawing mechanism. Arabic-only text has
real shaping constraints, and preserving that quality matters more than making
the code look uniformly abstract.

## Review questions

Before implementation, reviewers should answer:

1. Is `InkEngine` small enough to justify the new layer?
2. Does the proposal keep `HighlightEngine` pure?
3. Does Arabic-only keep its shaped text path?
4. Are polish knobs easier to find after this change?
5. Are we avoiding renderer interfaces and custom text-engine work until there
   is a concrete need?

If the answer to any of these is no, reduce the scope.

## As implemented

`ui/reader/InkEngine.kt` is a single object, per Option 2, with one amendment
to the proposed shape: the review found that the four-state derivation is the
easy part — the highlight's *feel* lives in the motion policy (520 ms
tween-vs-snap rules, sweep entry and residual rules, repeat wash timing, the
1.6× feather). `InkEngine` owns that policy as data, while one per-word
`InkMotion` owns its Compose runtime:

- **Pure word policy** (JVM-tested in `InkEngineTest`):
  `wordState(position, activeWord, isActiveAyah, dimmed)` (including the
  high-water rule), `inRepeatChain(position, activeWord)`, the bundled
  `word(...) → InkEngine.Word(state, repeat)`, and
  `sweepMs(activeWord, speed)`, which clamps the karaoke hold into
  `minSweepFloorMs()..maxSweepMs` — short holds scale **up** so a tiny word
  still shows a wash (`minSweepMs + highlightLeadMs`), which is why the wash
  can outlive `Active` (see the sweep lifecycle below). The highlight lead
  already starts word ink early; that early budget lengthens short and wasl
  washes instead of leaving idle full ink before the voice.
- **Pure tajweed policy**: `pacing(arabic, activeWord, isAyahFinal, prev)`
  returns the `TajweedPacing.Curve` for the active word or null for the plain
  sweep; `connection(prevArabic, arabic)` resolves the cross-word wasl rule
  without accelerating the donor word;
  `pacedFeather()` is the paced word's edge width. Repeat ink captures that
  same curve and feather, but its live clock is the raw spoken dwell from
  `repeatDwellMs` with no visual minimum; `repeatSweepMs` is only the fallback
  when no active timing clock exists. All gated on `Tuning.tajweedPacing` — see
  [TAJWEED_PACING.md](TAJWEED_PACING.md).
- **`glinting(state)`** — the first-gloss glint rule:
  Active words wear the white-gold sheen (including seek/replay and
  repeat events), which then dissolves back to plain recited ink over
  `Tuning.glintFadeMs` (1 s). The glint is theme-gated by
  `QuranAccents.glintInk` (null = off; Nightfall and Royal Green)
  and rides the active letter or repeat wash — same progress, same feather —
  as a tinted overlay (`letterFadeIn` on word units, `ColorReveal` on shaped
  lines). Android adds a restrained white-gold halo outside those glyph
  outlines; web uses two glyph-shaped layers: a lightly blurred outline and a
  restrained luminous ink core. Both grow with the wash, peak as the word
  forms, and recede with the glint. The sheen stays legible against dark pages
  without becoming a hard or whole-word glow, adding no new motion—only a
  warm light that cools as the ink dries.
  Tarjīʿ modulates only that glint layer: the signed vocal envelope maps once
  from trough to crest. Its sign must not be folded with `abs`, which would
  double the visual frequency and brighten the quiet half of the vibration.
  The base/repeat wash position is never part of this modulation.
  [GLIMMER.md](GLIMMER.md) is the canonical cross-platform rendering,
  tuning, and visual-verification specification.
- **Stream wait**: while the spinner is up, the whole page — the recited tail,
  the due verse and the opening basmalah included — waits under Upcoming paper
  (`ReaderScreen.kt` holds `STREAM_WAIT_RELEASE_HOLD_MS`). The *wait is the
  buffering itself*, not a question of whether anything has been spoken
  (user rule): the recited tail from before a pause or an earlier seek stays
  lit only while playback goes on — the moment the download holds the voice,
  it goes under the paper like the rest. Mid-recitation stalls and
  item-boundary joins dim the lit words exactly as a fresh play does. The
  fade is one synchronized action the moment buffering begins (no engage
  delay), and releases only after `STREAM_WAIT_RELEASE_HOLD_MS` of a carried
  voice, so a starving stream's brief ready windows cannot strobe the page
  between download bursts.
  Web parity: `data-stream-wait` CSS covers in `styles.css`.
  Android word-play is an explicit exception: the tapped verse keeps its
  continuous recited prefix, including during the buffer and release hold.
  `WordTapInk` publishes the same immediate target for word tiles and book
  pages, waits for a matching poll, then retains that origin within the
  acknowledged verse, reciter and requested place (`positionIntentId`). A
  later play, seek or automatic verse repeat cannot inherit it. MediaController
  can report the immediate seek and its session acknowledgement as separate
  ink generations; the same landing within the same request rebases the
  prefix onto the new generation. A different landing still releases it.
  The tapped word still waits for its own media clock;
  words after it retain the ordinary Upcoming treatment.
  In Scroll, other verses above and below fade from the start request,
  including during a word tap's own loading. Their ink, secondary text and
  marks share the chrome's 520 ms Material ease-in-out fade on Android and
  web. Existing default Ink Lab fades migrate to that duration; custom
  auditions persist. Scroll ignores a stale fade-led
  focus during that wait. Mushaf keeps earlier visible verses inked on the
  current page, including preceding chapters sharing the leaf. Arabic and
  English leaves cover later verses while the current verse carries the wash
  and retains its ink through its closing hold and silent tail. Outgoing wet
  ink keeps its drying clocks over the completed ink. A seek keeps earlier
  verses inked, covers later ones, and waits for the selected verse's new
  generation. Manually browsed leaves keep their ordinary resting ink.
  Web uses the same cumulative page policy in `mushafInk` and starts Scroll's
  `data-reciting` recess with the word request, before audio becomes ready.
  `WordInkPublication` writes that target synchronously before issuing the
  player command; it does not wait for a `combine` collector while playback
  can already recess the page. `InkEngine` treats the saved tap position as
  a minimum completed prefix even if the acknowledged display clock is
  temporarily earlier. Those words complete any old residual wash and lift
  their lyric alpha immediately. Natural handoffs still finish their own
  soft residuals, and genuine repeats can lay orange over that full base.
  Confirmation gets two uninterrupted seconds after buffering clears; a
  download or another buffer burst cannot consume that grace and erase the
  prefix just as the voice arrives. An unconfirmed seek still expires after
  that ready period.
- **Basmalah preface**: `prefaceState(isActive, dimmed)` /
  `prefaceWashProgress(positionMs, durationMs, segments)` for the surah-header
  basmalah VectorDrawable (Active during the Al-Fatihah 1:1 lead-in clip, with
  an RTL `letterFadeIn` wash; Upcoming while recessed; Plain at rest; `dimmed`
  beats `isActive` so the stream wait covers the preface too). With the
  clip's word `segments` the wash is locked to the voice by
  [`BasmalahWash`](../app/src/main/java/com/beautifulquran/domain/BasmalahWash.kt):
  each of the four words owns the **band of artwork its glyphs cover** (the
  composition is not proportional to time — the elongated sīn gives بِسۡمِ over
  half the width for a ~0.5 s syllable) and is paced inside that band by
  tajweed, including the madd ʿāriḍ of the closing ٱلرَّحِيمِ, which is over
  half the clip. `prefaceRampProgress` is the fallback when those timings are
  missing or are not the plain four words: the old clip-clock ramp settling at
  `PREFACE_WASH_SETTLE_FRACTION`. The web port has the bands but not yet the
  letter pacing (no `TajweedPacing` on web).
- **Preface feather** (`prefaceFeather()` / `BasmalahWash.MAX_FEATHER`): the
  wash gradient runs one feather *ahead* of its solid front, so with the
  verse-word feather (1.6× the **word**) a four-word-wide artwork was faintly
  inked end to end by 38 % of the clip — the wash read as a crossfade and looked
  finished while the reciter was still on ٱللَّهِ. The preface caps `washFeather`
  so the far end is first touched exactly as ٱلرَّحِيمِ begins; the Ink Lab can
  still sharpen it, never widen past that.
- **The closing word ends with the clip** (`BasmalahWash.settleMs` /
  `onRowClock`), not with its own mark, because **late ink beats early ink**: a
  wash that completes while the reciter is still sustaining ٱلرَّحِيمِ reads as
  broken, one that finishes into the file's closing silence reads as calm. Rows
  that stop marking early (Minshawi by 185 ms, then As-Sudais, Ash-Shuraym and
  Alafasy by 150 ms) extend into the clip — capped at the closer's own marked
  length so a long tail of room tone cannot make the ink crawl. A row that ends
  *after* its MP3 (Hani Ar-Rifai overruns by 945 ms) is fitted inside the
  measured duration, keeping its onset. Measured settle: +11 to +711 ms after the
  voice, never before it.
- **`InkEngine.Tuning`**: every feel knob in one data class — upcoming alpha,
  ink/mark fade durations, recess, sweep clamps, repeat sweep/fade-out and
  repeat ink strength, glint tint, glitter time, halo strength/blur, wash
  feather, sweep easing, and tajweed pacing. `InkEngine.tuning` is
  snapshot-backed (`mutableStateOf`), so release builds read constants while
  the Ink Lab can retune a live session.
- **Sync knobs, deliberately outside `Tuning`**: `highlightLeadMs` (default 0),
  `fadeLeadMs` (default 500), `scrollLeadMs` (default 100 — the camera's own
  lead, deliberately separate from the ink's; see `FocusEngine.playbackFocusTarget`)
  and `outputLatencyOverrideMs` (null = use Media3’s presentation clock;
  a manual value adds wall-time delay, scaled to media time by playback speed). These move *when* things fire rather than how the ink feels, so they
  stay out of the data class that **Copy values** transcribes — but they persist
  with it via `InkLabStore`. Schema 4 brings saved scroll leads to 100 ms once,
  preserving the other knobs; later scroll-lead auditions persist normally.
  `fadeLeadMs` is how far the ayah focus/recess target
  runs ahead of the audio; a nonzero Ink Lab `highlightLeadMs` advances word ink
  only. Both start
  from the same latency-corrected heard position, a contract enforced in
  `ReaderViewModel`; the highlight lead is suppressed during encoded silence
  before the first word — see
  [OUTPUT_LATENCY.md](OUTPUT_LATENCY.md). The lead prepares ink only: scrolling
  follows the actual Media3 ayah boundary, so the next verse does not move into
  focus before it is heard. A word tap waits for that boundary to name the
  tapped ayah before homing — the previous item must not steal the camera
  during the seek. `focusEngineEnabled` is a session-only lab freeze
  and is never persisted.
- **One motion lifecycle, two paint adapters.** `AyahBlock` derives the ayah's
  `InkEngine.Word` list once (the single `InkEngine.word(...)` call site), then
  builds one aligned `InkMotion` per word. `InkMotion` exclusively owns base
  sweep entry/residual, wasl handoff, repeat sweep + independent release,
  captured
  feather/pacing, lyric alpha, and glint dry-down. The render branches receive
  those motions as paint-only inputs: `HighlightLayeredText` adapts them to the
  Arabic + gloss word layers, while `addShapedInkMotionBlooms` adapts the same
  motion to the continuous English paragraph and shaped Hafs ayah. No renderer
  creates an `Animatable`, `SideEffect`, ordered gate, or word-motion lifecycle.
  The two shaped modes still keep independent text construction because their
  typography differs; only their motion-to-bloom adapter is shared.
- **Paper scroll handoff.** The shaped Arabic/English cover moves on one clock:
  `recessMs` is `CHROME_RECEDE_MS`, so every verse leaving full ink — the page
  at rest when play begins, and the verse giving up the lyric line as the wash
  advances — eases its paper cover back over the dry ink with the chrome instead
  of snapping every word to Upcoming. A verse that has only ever waited, or that
  mounts during playback, keeps the Upcoming floor from its first frame
  (no-full-ink-flash guard), and the outgoing verse keeps that floor under ink
  it never read. Layered gloss already makes the same handoff through its
  draw-phase word-alpha tween.
- **Mushaf progression is page-local.** The leaf carrying the voice begins
  beneath the Upcoming paper cover; its active word uses the same motion pack
  as the scrolling reader, completed ayahs retain full ink, and later ayahs use
  a motionless recess pack. Back retains full ink before the selected verse,
  including while paused before its first word; the selected verse and those
  after it still wait for the wash. Between word polls, Media3's playing verse
  supplies the ink frontier ahead of fade-led focus. A missing or stale focus
  used to re-cover completed or outgoing verses, or uncover future verses at a
  repeat, so the first-word cover gate still holds the incoming verse until its
  timing arrives. The first-word gate remembers the heard
  playback generation, so a seek or repeat of the same verse waits anew;
  an optimistic tap seed cannot establish that the new voice has arrived.
  Web publishes its ink activation even with no active word and applies the
  same generation rule. Reproducible timing, ownership and page-cut checks
  are recorded in [ENGLISH_ALIGNMENT_COVERAGE.md](ENGLISH_ALIGNMENT_COVERAGE.md).
  English ownership also reads the loaded words' vocalized QAC lemmas. Shared
  phrase cards retain their lexical heads; equal repeated heads can cross
  clauses when their Arabic lemma is identical. Heads can also cross
  print order. Unclaimed shared-phrase text stays before the following Arabic
  anchor, so a resolved subject cannot strand a preceding verb in that next
  word. Different meanings keep the
  sentence guard. Scoped gloss variants and reciprocal adjacent head cues
  preserve noun/verb ownership. Recovering a head missing from a grammatical
  card also requires the actual Arabic word to corroborate the lemma.
  Latin accent folding affects matching keys, preserving printed glyphs.
  “Clear evidence/evidences” stays with its single source meaning (24:1
  bayyināt: word 7); distinct literal adjective and noun cards retain their
  separate owners. Bayyina/mubīn heads resolve repeated “clear” phrases.
  An unclaimed grammar tail stays with its closing sentence between adjacent
  Arabic anchors: 24:4 “ever after” finishes together on word 16. Explicit
  matches and earlier owners remain intact; unmatched content is not absorbed.
  A literal grammar-only gloss outranks sentence closure; ambiguous copies
  keep their existing contextual owner (23:83 “before”).
  A gendered offender expanded as a woman/man recognizes its source card
  only when no literal gender or printed offender head competes. This keeps
  the opening phrase of 24:2 active on Arabic word 1. Its expanded offender
  description travels with that head; flog/lash cues keep the action separate
  from the later lashes noun.
  Full semantic certification requires a separate reviewed
  reference; timing/frame counts do not supply those labels.
  Voice ownership and follow retain the last heard word's
  location through the silent tail, so a carried verse does not jump back to
  its opening leaf when the live word becomes null. That location is released
  on a playlist change or a new seek/repeat generation; the wash still reads
  only the live word. A tap can pin the wash's activation to its optimistic
  seed; location retention checks the heard word's original playback generation
  instead. Media3 publishes the verse and discontinuity generation together,
  so the next verse never briefly inherits the outgoing verse's opening leaf.
  Finishing a carried verse, its silent tail, and the next verse beginning on
  the same leaf must all name that leaf; there is no turn back to the completed
  verse's opening leaf. Word-to-leaf lookup uses the same English alignment for
  follow, voice ownership, and tap-hold release, including when the animated
  Back/Next transport is present. A delayed follow turn rechecks the playlist
  ayah and seek/repeat generation after its font wait, and is discarded if a
  word arrives during that wait.
  A leaf reached by hand stays fully readable, even
  while playback continues elsewhere. A leaf follow is about to turn onto
  waits under Upcoming paper *before* the paper moves, so the turn never
  reveals a finished page; once the voice arrives the usual wash fills it.
  Catch-up must not rewind onto the spoken leaf during that wait — the last
  word is still being said, and pulling back is the bounce of next, previous,
  next again.
  Only the current pager leaf owns a tap;
  a hold then blocks auto-follow (including the last-word lead turn) until the
  seek's word is on that leaf, so a slow seek cannot yank the paper to the
  verse that was playing before the tap. On a carried English verse, arrival
  uses the word's aligned progress through the verse, just as voice ownership
  does. Checking only the verse's opening leaf left the tail leaf held after
  the seek arrived: the final null word then re-covered the completed verse
  and rebuilt its entry fade at the next verse. The tapped leaf itself waits
  under Upcoming paper until that word arrives — a dialed page is fully inked, and
  without the cover the wash has nothing to fill. The tapped ayah is Active as
  soon as the word is known, even before the pager names the leaf, so the
  letter wash is not stuck under a blanket Upcoming cover. The seed is not
  treated as voice arrival — that dropped the cover and swapped the pack off
  Active, disposing its captured wash. The UI activation remains pinned for
  that tapped word; V2 separately tracks the actual player generation and
  acknowledges the provisional window without inventing voice arrival.
  The Active pack belongs to the leaf that has the word, not to
  `pageOwnsVoice` / `waitingForVoice`: those both drop at play-start and
  the `when` that swaps packs disposes its motion history, which read as the
  wash running for a beat then vanishing.
  The chapter-opening
  basmalah is part of
  that sequence: tapping it starts its own wash before the first ayah. At the
  playlist handoff, the first ayah stays Upcoming until its first word timing
  arrives; Media3 advancing the item a poll tick earlier must never flash the
  whole ayah full before its wash. QCF words read the current pack through one
  cached state so every word sees its entry mask immediately. If a newly
  published Active pack reaches an older recess-only modifier before
  recomposition attaches the wash, that modifier holds Upcoming alpha for the
  topology-transition frame. The new Active pack begins with that same recess
  cover and eases it away, so the ayah and its number fade into selection
  instead of popping; future words remain at Upcoming ink and the active word
  retains its directional wash.
- Every **highlight** duration, alpha and easing still comes from
  `InkEngine.tuning` — no literal ink-tuning values remain. Motion outside the
  word highlight stays local (for example the block fade while the ayah-selector
  rail obscures the page); those values are not `Tuning`'s job.
- **Draw primitives stayed in `ui/theme/Fade.kt`** per Step 3; the feather and
  the reveal fraction became parameters (`letterFadeIn` / `shapedWordBloom` take
  `feather`; `letterFadeIn` and `ShapedWordBloom.ColorReveal` take
  `revealFraction`, which the wasl prefix uses to stop the mask short) so the
  theme layer stays independent of the reader package. `shapedWordBloom` runs its
  whole bloom list in the draw phase, so what each bloom kind may derive there is
  a performance contract, not a style preference — see
  [PERFORMANCE.md](PERFORMANCE.md#1-draw-phase-only-animations-zero-recomposition-fades).
  Unread covers (`UpcomingDim` / `InkReveal`) punch the glyph layer (`DstOut`)
  so the wash reveals the paper already there — the page, or a gather stain —
  instead of painting a cream rectangle of page colour over it.

### The sweep lifecycle

HighlightEngine V2 owns speech time. `rememberInkMotions` remains the single
motion owner for scroll and mushaf, delegating to `HighlightMediaMotions`.
`HighlightWash.transition` captures an occurrence window; drawing projects the
shared `HighlightTimeline` through that window. The replaced speech-time
`Animatable` owners (`rememberLetterSweep` and `rememberRepeatWash`) are gone.

- Active entry captures duration, tajweed curve, feather, and wasl carry once.
  Mid-word tuning cannot replay the wash. The visual floor is converted from
  wall milliseconds to media duration at entry speed; English Scroll also
  bounds it by the voiced end below.
- Active→Recited retains that word's independent residual window; Upcoming or
  Plain abandons it. Pauses and buffering freeze residuals as well as active ink.
  Speed changes alter the shared media clock instead of restarting animation.
- A tap is provisional until a matching player generation arrives. Its first
  draw is zero, without waiting for an effect to snap an idle animation down.
  If another tap skips past an unconfirmed target, that target becomes fully
  recited with the rest of the prefix; a provisional zero is never retained
  as a spoken residual.
- A consecutive same-word repeat starts another orange occurrence. Base ink
  remains laid; orange waits for the base edge. Repeats never form a queue.
- English predecessor gating preserves a continuous prefix while each window
  stays anchored to speech, so short words cannot accumulate queued delay.
  English Scroll starts from the eased hold-duration window, including the
  first-pass minimum and maximum, then finishes its reveal up to **100 wall
  milliseconds earlier**. A positive occurrence-specific `spokenMs` then caps
  that window at the actual voiced end. The karaoke hold includes silence up
  to the next onset; subtracting 100 ms from that hold alone can still leave
  the edge moving through most of a pause. The onset stays fixed and completed
  ink holds through the remaining silence. The soft-edge minimum is retained
  wherever it fits before the voiced end; it cannot extend into that silence.
  Repeats use their own voiced span and are never stretched to meet the floor.
  Missing or zero voiced metadata retains the earlier hold-based fallback.
  `EnglishRevealTiming` applies the policy before the shared window converts
  speed once. Android and web use the same policy. Paper ink consumes that
  progress; its one-letter lead stays inside each printed word. English leaf
  semantic windows and Arabic pacing retain their existing policies.
- Completed residuals stop observing frame updates at the next word boundary.
  Repeat completion is observed as a structurally compared Boolean; frame
  progress itself is read in drawing, avoiding frame-driven composition.

Wasl still uses `waslWashProgress` / `waslContinuationStart` /
`continuedSweepProgress`, the same captured feather, and the reached handoff
edge. Its clock is now the outgoing occurrence's media window. `LetterSweep`
also exposes `plainProgress` without the Arabic letter map or wasl carry;
English never claims an Arabic held letter as its own pacing.

**English leaf pacing.** `EnglishWashClock` reads the same calibrated V2
media frame as Arabic. There is no second audio anchor. Pause and buffering
freeze it; speed, seeks, reciter ownership and timing replacement retain the
shared frame contract.

Published translation uses one steady reading clock through each safe forward
verse. `EnglishPhrase` still closes reliable content anchors into reordered
ranges, while semantic taps, holds and repeat labels stay in the alignment.
These ranges partition geometry; they no longer give every word a separate
velocity or stop at an Arabic owner's end. The user clarified on October 25
that connected speech should carry constant paced movement.

The later clarification prioritizes the finish of a reliable literal word
match. `EnglishReadingPace` joins compatible printed word ends to that source
word's voiced end, preferring 100 **media** ms earlier where feasible (100 wall
ms at 1x, 50 at 2x). Its monotone cubic keeps a positive shared velocity at
interior joins rather than easing every word to rest. Literal provenance comes
from the existing verified owner before spans coalesce; synonyms, grammar,
translator additions and partial printed tokens cannot establish a deadline.
The last printed endpoint of an expanded source unit is considered once.

The warp is identity through full rendered word units in reordered phrases and
the protected terminal residual, including punctuation. An exact word inside
those regions cannot force earlier English through a rush. Ordering, gate,
quiet and velocity conflicts get one retry at the available voiced end, then
an explicit rejection. A heuristic admission range of .25–4 times the current
per-verse pace excludes near-stalls and bursts; this bound does not certify
perceptual steadiness. No admitted anchors leaves the current reading clock
unchanged. See the [word-finish report](quality-reviews/ENGLISH_WORD_FINISHES_2026-10-25.md).

`EnglishReadingFlow` sums the union of **all** source segments in the run,
including owners swallowed by the English order, then subtracts the normalized,
clipped measured quiet once. Its cumulative audible fraction passes through
the cached word-finish warp, then into visible character slots.
`EnglishReadingExtent` gives whitespace
zero travel time; the next word still begins at zero progress. This removes a
glyph-free dwell without changing the printed spacing or audio endpoints. The
extent is disabled when the source has whitespace that the leaf normalizes;
that case retains the continuous character projection and records
`normalized_spacing`. Source gaps and measured silence hold the exact fractional edge reached
at their start, including inside an inversion; neither punctuation nor a word
boundary snaps that edge. Resuming continues at the same rate. The existing
first-voice gate and terminal fence bound the run. No live integrator or extra
calibration is involved.

`EnglishReadingWindow` projects this same clock into each geometry span.
Printed finish times, material cooling and follow invert the shared mapping.
The inverse reverses extent, pace and audible clock in that order.
Inverse targets are quantized to integer media milliseconds before choosing a
pause's earliest finish or latest start. Rounded endpoints are diagnostics and
must never re-anchor or clip the default runtime projection.

Word-card English (`phrases == null`) retains its actual linear legacy windows,
including the pause before the next owner. Ambiguous source partitions retain
the complete legacy occurrence bands (`sourceOverlapFallbacks`); verses with
backward/same-word source repeats keep their reviewed occurrence policy too.
Zero audible time or unusable source windows have explicit reading-flow fallback
reasons. The 24:1 repeat and 24:2 terminal-tail/carried-cut guards remain.

Measured quiet currently comes from the existing Hani asset, attached by actual
audio ayah and reciter to `EnglishWashClock`; other voices use known timing gaps.
This is limited pause evidence, not a claim that timing segments detect every
acoustic stop. See the [steady-reading report](quality-reviews/ENGLISH_READING_FLOW_2026-10-25.md).

The published steady-reading path also gives its fallback cover a feather one
mean printed character wide instead of the generic whole-word feather. Its
zero-progress unread floor and word-local soft entry remain. The Paper shader's
lead-entry field is unchanged; matching edge width does not make their material
kinetics identical. Cards, Scroll and occurrence fallback verses keep their
existing covers. The [visible-transition report](quality-reviews/ENGLISH_TRANSLATION_TRANSITIONS_2026-10-25.md)
records the same-layout comparison and its sampling limits.

The renderer and carried-leaf follow consume the same cached whole-verse plan.
`EnglishLeafVerse.fragmentWashProgress` projects its edge through exact letter
anchors; page cuts do not restart the clock. Base covers and paper material use
the same printed-word progress. Freshness follows each printed exposure window
rather than the phrase's first Arabic motion; settled words keep cooling across
pause/resume. First-pass glimmer follows the presentation edge and its ending
motion's dry-down, while semantic repeat tint stays clipped to the laid prefix.
A generation-scoped cosmetic 850 ms departure releases untouched old words on
a mid-phrase rewind, including a seek within the same Arabic word. It does not
change the audio clock. The existing prefix on entry still rises through seek
recess; newly washed words remain laid.

Follow previews 500 ms at the current playback speed using this same plan.
Re-says locate their semantic word rather than the retained forward prefix.
The earlier `washSpans` projection remains a legacy occurrence fallback and
ownership diagnostic; its no-late-meaning assertion does not certify the new
readable presentation's word timing.

**Short wasl donors** (`مَن يَشْرِى`, `مِن رَّبِّكُم`, …) need a **speed
ceiling** on that early bloom so the next opening does not race: details and
constants in
[TAJWEED_PACING.md — Short wasl donors](TAJWEED_PACING.md#short-wasl-donors--speed-ceiling).
Long donors are capped too: `Tuning.waslHandoff` limits pre-activation ink so
a wrapped connection such as `بِمَعۡرُوفٖۚ → وَلَا` cannot present the next
line's opening fully formed. Ink Lab exposes it as **Wasl pre-ink**.

### Ink Lab

Developer mode (triple-tap the Settings logo) adds a playbar toggle that opens
or closes the inline Ink Lab controls (`ui/reader/InkLabPanel.kt`) in place of
the reciter name. Its tabs, sliders and actions expand the measured playbar,
so the reading column clears the taller bar automatically. The panel is bound
directly to `InkEngine.tuning`: upcoming ink, fade timings, sweep clamps,
repeat timings, **Repeat ink** strength, the fresh-ink **Glitter time**,
**Glint tint**, **Halo strength**, and **Halo blur** controls, wash feather,
and the experimental tajweed hold (see
[TAJWEED_PACING.md](TAJWEED_PACING.md)). There are enough knobs now that the
panel is split into **Ink / Sweep / Repeat / Tajweed / Tarjīʿ / Guide / Sync**
tabs —
quiet ink names with a hairline under the live one, no tab-bar chrome. Guide
exposes the contextual lesson's body edge, feather width, fade softness,
progressive-blur radius/strength, vellum grain, and vertical taper. Each guide
supplies a spotlight plus a body angle/distance; the GPU field keeps that
lesson contour independent from a localized contour behind the untouched-paper
action. The bottom action row
holds **Reset**, **Copy values**, and a session-only **Focus** freeze
(`InkEngine.focusEngineEnabled`) that parks playback auto-home and word-band
follow so the page can be panned while auditioning ink; it is not part of
`Tuning` and Reset does not touch it. Lab numbers (Tuning + Highlight knobs)
persist on device via `InkLabStore` across process restarts so multi-session
auditioning keeps the last dial positions; **Reset** clears the store and
restores shipped defaults (so future default changes apply cleanly). Focus
is never persisted. **Copy values** still puts a paste-ready
`InkEngine.Tuning(…)` constructor plus the contextual guide profile on the
clipboard (and Logcat tag `InkLab`) so tuned values can be promoted into their
shipped defaults. Every
slider uses a **log track** (equal thumb travel ≈ equal ratios when the range
starts above zero; more precision near the floor when it includes zero) so
small values stay fine-grained while large ends stay reachable. Slider
meanings, defaults, ranges, and the halo artifact stress check are documented
in [GLIMMER.md](GLIMMER.md#ink-lab-controls).

The **Ink** tab starts with the Paper ink appearance controls: Paper ink and
Flowing ink switches, pooling, reflection, Arabic lead fade, and
**Dry-down ms**. Dry-down edits the same persisted `glintFadeMs` as Sweep's
Glitter time, so it also adjusts glimmer decay. English keeps its fixed
one-letter preview. These controls reuse the existing tuning, persistence,
Copy values and Reset paths. Pooling defaults to 0.28, including a one-time
migration from the previous stock 0.25; custom values remain. The Softness
control and saved setting are retired. Pacing is unchanged.
