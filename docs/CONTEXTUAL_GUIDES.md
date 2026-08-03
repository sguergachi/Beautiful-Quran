# Contextual guides

Contextual guides teach a feature by letting the reader use that feature in
place. They are a temporary change in the ink on the current paper, not a new
sheet, dialog, card, tooltip, or modal layer.

## The contract

The spotlighted feature is real and interactive. A guide that says “Press and
drag this rail” must let that press open and drag the actual rail. A guide that
says “Press and hold this ribbon” must let that hold open the actual note
editor. The reader must not dismiss the lesson and repeat the gesture.

These rules are non-negotiable:

1. Keep the real target mounted, visible, enabled, and semantically unchanged.
2. Let the taught gesture reach the target for its complete down/move-or-hold/up
   sequence.
3. Perform the real feature action first, then persist lesson completion.
4. Prevent unrelated or destructive target actions while teaching when needed.
   For example, the bookmark lesson permits hold but makes a short tap a no-op
   so the new bookmark cannot be removed mid-instruction.
5. Absorb gestures only on the teaching-body side. The spotlight side remains
   live paper.

`ContextualFeatureTip` owns sibling pointer sharing, so every use gets live
spotlight passthrough by default. It must be the feature wrapper's outer emitted
layout; use its constraint-scoped spotlight/action providers instead of wrapping
it in another `BoxWithConstraints`. Pointer sharing is scoped to layout siblings,
so an extra host layout silently disconnects the target below. Do not add a
full-screen consuming modifier above it, disable the target while the guide is
open, or draw a fake copy of the target inside the guide.

## Anatomy

A guide has four parts:

- The existing feature target, measured in root space.
- A royal-green progressive-vellum field placed in unused paper opposite it.
- Short title and instruction copy describing one concrete gesture.
- A paper-cutout **Got it** action for explicit dismissal.

`ContextualTipPlacement` expresses the body as an angle and distance from the
spotlight. The same vector drives typography, pigment, taper, blur, and the
interactive half-plane, so left, right, top, bottom, and diagonal placements use
one rendering path.

The guide action uses `ownedQuietClickable`. It claims only its own gesture at
the initial pointer pass so a control underneath cannot steal **Got it**. The
rest of the spotlight side continues to the feature below.

## Adding a guide

### 1. Define its persisted moment

Add an `EducationMoment` with a versioned preference key in
`SettingsRepository`. Increment the key suffix when the lesson meaning or
eligibility changes enough that existing readers should see it again.

Keep all guides behind Developer → **Contextual feature guides** until the
guide system is intentionally enabled for readers. Developer replay must rearm
the moment without changing feature data.

### 2. Choose the trigger

Trigger once, at a settled moment when the target is present and usable. Do not
show a guide during recitation, navigation flight, editing, or another ink
surface. Store the target identity separately from the open flag so scrolling
and asynchronous repository updates cannot retarget the lesson.

### 3. Measure the real target

Report the target's live root-space center from its existing composable with
`onGloballyPositioned` or a narrow target-specific callback. Do not estimate a
row center or duplicate the target in the overlay. Wait for a finite measured
position before showing the guide.

### 4. Compose the lesson

Create a small feature wrapper such as `BookmarkNoteTip` or `AyahRailTip` that:

- selects the spotlight center and body angle;
- supplies brief title/body copy;
- uses the reader's actual paper and ink for the dismiss cutout;
- uses `ContextualPulseMark` only as a quiet visual breath around the real
  target; and
- fills the reader bounds with `ContextualFeatureTip`.

Do not add shadows, elevation, cards, outlines, modal scrims, or a second paper
surface. Follow the progressive-vellum construction in `DESIGN.md` and the
selected `docs/design-studies/contextual-guide/02-progressive-vellum.webp`
reference.

### 5. Wire target success

Keep the target's interactive gate independent from guide visibility. In the
target's existing success callback:

1. perform or open the real feature;
2. call the matching `dismiss…Tip()` persistence method; and
3. clear the local guide-open state.

Dismiss on success, not merely on pointer down. A cancelled drag or hold must
not mark the lesson complete.

Other exits may include **Got it**, Back, or ordinary page scrolling. These are
dismissals only; they do not simulate feature success.

### 6. Preserve reader overlay lifecycle

Include both visible and still-rendering states in the reader's ink-overlay
union. Closing animations must continue blocking stack gestures until their
last frame. Keep developer controls usable for tuning without placing ordinary
reader controls above the guide action.

### 7. Document and verify

Update this document or the feature document when behavior changes. Verify on a
device or emulator; a successful build cannot prove pointer routing.

## Required verification

For every guide, exercise this checklist:

- Rearm the exact education preference and reach the real first-use trigger.
- Confirm the target stays visible and is the original feature, not a copy.
- Perform the exact gesture named in the copy while the guide is fully visible.
- Confirm the feature responds during that same gesture.
- Confirm successful target use dismisses and persists the lesson.
- Confirm **Got it** works even where its bounds overlap another control, and
  that the control underneath does not activate.
- Confirm the teaching-body side blocks unrelated taps.
- Confirm Back and page-scroll dismissal behave as documented.
- Confirm the interaction works on both physical sides when the target side is
  configurable.
- Confirm guides remain disabled by default outside the developer gate.

For the existing lessons, the acceptance actions are:

- Bookmark note: hold the spotlighted ruby ribbon; the guide withdraws and the
  inline note editor opens focused. A short tap does not unbookmark it.
- Ayah rail: press and drag the spotlighted collapsed rail; the guide withdraws
  while the real scrub wheel blooms under the finger.

## Common failures

- **The guide describes an inert feature.** A guide-specific `interactive =
  false` gate disabled its own target. Remove that dependency or allow only the
  taught action.
- **The guide disappears but the feature does nothing.** The page received the
  drag instead of the target. Verify the live target's real state while the
  pointer is still down; dismissal alone is not evidence.
- **Got it does nothing over another control.** Use `ownedQuietClickable`; do
  not use an ordinary shared-path click detector for guide actions.
- **The target works only after dismissal.** The overlay is consuming the
  spotlight half or another higher-z input layer excludes the target from hit
  testing.
- **The target jumps away.** Its position was guessed or tied to a recycled
  list item instead of the measured target identity.

## Reference implementations

- `ui/theme/ContextualFeatureTip.kt` — shared placement, vellum, interaction
  boundary, and dismiss action.
- `ui/reader/BookmarkNoteTip.kt` — target-relative bookmark lesson.
- `ui/reader/AyahRailTip.kt` — configurable left/right rail lesson.
- `ui/reader/ReaderScreen.kt` — trigger ownership, real target callbacks,
  completion persistence, Back/scroll dismissal, and overlay lifecycle.
