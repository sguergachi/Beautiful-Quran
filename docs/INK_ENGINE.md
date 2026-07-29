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
easy part — the highlight's *feel* lives in the motion policy (400 ms
tween-vs-snap rules, sweep entry and residual rules, repeat wash timing, the
1.6× feather), which was still scattered across five `remember*` helpers. So
InkEngine owns that too, as data rather than as animation code:

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
  same curve and feather, and uses the measured sweep with `repeatSweepMs` as
  its soft minimum. All gated on `Tuning.tajweedPacing` — see
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
  [GLIMMER.md](GLIMMER.md) is the canonical cross-platform rendering,
  tuning, and visual-verification specification.
- **Basmalah preface**: `prefaceState(isActive, dimmed)` /
  `prefaceWashProgress(positionMs, durationMs, segments)` for the surah-header
  basmalah VectorDrawable (Active during the Al-Fatihah 1:1 lead-in clip, with
  an RTL `letterFadeIn` wash; Upcoming while recessed; Plain at rest). With the
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
- **`InkEngine.Tuning`**: every feel knob in one data class — upcoming alpha,
  ink/mark fade durations, recess, sweep clamps, repeat sweep/fade-out and
  repeat ink strength, glint tint, glitter time, halo strength/blur, wash
  feather, sweep easing, and tajweed pacing. `InkEngine.tuning` is
  snapshot-backed (`mutableStateOf`), so release builds read constants while
  the Ink Lab can retune a live session.
- **Sync knobs, deliberately outside `Tuning`**: `highlightLeadMs` (default 114),
  `fadeLeadMs` (default 500) and `outputLatencyOverrideMs` (null = use the route
  preset). These move *when* things fire rather than how the ink feels, so they
  stay out of the data class that **Copy values** transcribes — but they persist
  with it via `InkLabStore`. `fadeLeadMs` is how far the ayah focus/recess target
  runs ahead of the audio; `highlightLeadMs` advances word ink only. Both start
  from the same latency-corrected heard position, a contract enforced in
  `ReaderViewModel`; the highlight lead is suppressed during encoded silence
  before the first word — see
  [OUTPUT_LATENCY.md](OUTPUT_LATENCY.md). `focusEngineEnabled` is a session-only
  lab freeze and is never persisted.
- **Renderers consume `InkEngine.Word`.** `AyahBlock` derives each ayah's ink
  list once (the single `InkEngine.word(...)` call site) and passes it into all
  three branches (`WordUnit`, `ResponsiveEnglishAyah`, `ResponsiveHafsAyah`);
  none of them re-derive highlight semantics. The Compose animation helpers
  stayed in ReaderComponents.kt but read every **highlight** duration, alpha and
  easing from `InkEngine.tuning` — no literal ink-tuning values remain. Literals
  for motion that is *not* the highlight still live locally (e.g. the block fade
  when the ayah-selector rail obscures the page); those are not `Tuning`'s job.
- **Draw primitives stayed in `ui/theme/Fade.kt`** per Step 3; the feather and
  the reveal fraction became parameters (`letterFadeIn` / `shapedWordBloom` take
  `feather`; `letterFadeIn` and `ShapedWordBloom.ColorReveal` take
  `revealFraction`, which the wasl prefix uses to stop the mask short) so the
  theme layer stays independent of the reader package. `shapedWordBloom` runs its
  whole bloom list in the draw phase, so what each bloom kind may derive there is
  a performance contract, not a style preference — see
  [PERFORMANCE.md](PERFORMANCE.md#1-draw-phase-only-animations-zero-recomposition-fades).

### The sweep lifecycle

The letter sweep is the one piece of motion whose *lifecycle* is subtle enough to
be worth stating, because three separate requirements pull against each other.
It lives in `rememberLetterSweep` (ReaderComponents.kt) with its decisions in
pure, `InkEngineTest`-covered helpers.

1. **The `Animatable` outlives `Active`.** `sweepMs` can floor a short hold up to
   `minSweepFloorMs()` (`minSweepMs + highlightLeadMs`), so the wash may still
   be running when the word turns Recited.
   `finishResidual` (true *only* for Active→Recited) lets it finish rather than
   snap. Leaving Active for Upcoming/Plain instead — a seek, a recess — abandons
   the residual immediately, because finishing toward full ink and then dimming
   back would flash.
2. **A persistent `Animatable` means the next word inherits progress 1.** The
   draw phase can read it before the effect's `snapTo(0f)` lands, which showed as
   a one-frame full-ink flash. `sweepEntryAction(…)` classifies each composition
   as `Arm` / `Keep` / `Clear`. On `Arm`, a **MutableState** `applied` flag is
   set false in `SideEffect` so the draw phase shows 0; `LaunchedEffect`
   `snapTo(0f)` then sets `applied` true so the wash can run. Unmasking must
   be State-driven: `activeWord` only recomposes the tree **once per word**
   (`distinctUntilChanged`). A composition-only Boolean captured in
   `derivedStateOf` stayed true for the whole Active span and killed every
   wash after the first word (the first often got a free extra recompose from
   player startup). Re-Arm still remasks via `SideEffect` even when
   `(active, activation)` keys match a prior pass. If Active ends before the
   reset, the residual only rewinds from the idle full-ink ceiling
   (`residualSweepAnchor`) — a mid-wash value is never snapped back to unread.
3. **The entry snapshot must survive the entry.** Duration, curve, feather, and
   wasl `revealStart` are captured at `Arm` and held for the whole sweep, so
   retuning tajweed or speed mid-word cannot remap a half-finished wash (which
   read as "resetting and playing again"). The residual needs that snapshot after
   the word is no longer Active, which is why it lives in a tracker rather than
   the effect's closure — committed in a `SideEffect`, never written during
   composition. The captured curve is released only once the residual finishes:
   it is what maps the linear clock to wash position, so dropping it mid-wash
   would jump the edge. The wasl edge is latched the same way
   (`effectiveRevealStart`) so Active→Recited cannot drop an already-bloomed
   prefix back to unread for a frame.

A **wasl continuation** (`waslWashProgress` / `waslContinuationStart` /
`continuedSweepProgress`) is the fourth case: the prior word's freed tail runs
the first segment of this word's ordinary ink wash (same feather), and the
sweep starts from the edge actually reached instead of 0. A donor too short
for the wasl speed ceiling carries an unfinished edge into the active word
rather than forcing its opening letter complete at handoff — see
[TAJWEED_PACING.md](TAJWEED_PACING.md). Continued progress is applied inside
`rememberLetterSweep` (not at each draw site) so residual and glint share one
latched edge.

**Short wasl donors** (`مَن يَشْرِى`, `مِن رَّبِّكُم`, …) need a **speed
ceiling** on that early bloom so the next opening does not race: details and
constants in
[TAJWEED_PACING.md — Short wasl donors](TAJWEED_PACING.md#short-wasl-donors--speed-ceiling).
Long donors are capped too: `Tuning.waslHandoff` limits pre-activation ink so
a wrapped connection such as `بِمَعۡرُوفٖۚ → وَلَا` cannot present the next
line's opening fully formed. Ink Lab exposes it as **Wasl pre-ink**.

### Ink Lab

Settings → Developer (triple-tap the logo) → **Ink Lab overlay** floats a
collapsible slider panel over the reader (`ui/reader/InkLabPanel.kt`), bound
directly to `InkEngine.tuning`: upcoming ink, fade timings, sweep clamps,
repeat timings, **Repeat ink** strength, the fresh-ink **Glitter time**,
**Glint tint**, **Halo strength**, and **Halo blur** controls, wash feather,
and the experimental tajweed hold (see
[TAJWEED_PACING.md](TAJWEED_PACING.md)). There are enough knobs now that the
panel is split into **Ink / Sweep / Repeat / Tajweed** tabs — quiet ink names
with a hairline under the live one, no tab-bar chrome. The bottom action row
holds **Reset**, **Copy values**, and a session-only **Focus** freeze
(`InkEngine.focusEngineEnabled`) that parks playback auto-home and word-band
follow so the page can be panned while auditioning ink; it is not part of
`Tuning` and Reset does not touch it. Lab numbers (Tuning + Highlight knobs)
persist on device via `InkLabStore` across process restarts so multi-session
auditioning keeps the last dial positions; **Reset** clears the store and
restores shipped defaults (so future default changes apply cleanly). Focus
is never persisted. **Copy values** still puts a paste-ready
`InkEngine.Tuning(…)` constructor on the clipboard (and Logcat tag `InkLab`)
so a tuned feel can be transcribed into the defaults in InkEngine.kt. Every
slider uses a **log track** (equal thumb travel ≈ equal ratios when the range
starts above zero; more precision near the floor when it includes zero) so
small values stay fine-grained while large ends stay reachable. Slider
meanings, defaults, ranges, and the halo artifact stress check are documented
in [GLIMMER.md](GLIMMER.md#ink-lab-controls).
