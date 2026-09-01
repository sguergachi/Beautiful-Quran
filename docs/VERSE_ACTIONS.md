# Verse actions: bookmark · note · share

**Status: Mark UX is the product.** Tap `﴿N﴾` to gather that verse; tap more
verses to add or drop them. Export (text/image) is shipped. Icon / Reveal /
Hold A/B toggles are gone.

This document records the product decision for how three **different**
actions on a verse coexist without cluttering reading or violating the
paper metaphor. It supersedes the dual-purpose player-bar Gather control
(removed in #519) and the multi-step gather-first share flow as the
**target UX**. The export pipeline is unchanged — see [SHARE.md](SHARE.md).

Related: [DESIGN.md](DESIGN.md) (paper rules), [ANNOTATIONS.md](ANNOTATIONS.md)
(notes), [SHARE.md](SHARE.md) (export pipeline).

## The three actions

| Action | Nature | Multi-verse? | Status today |
|---|---|---|---|
| **Bookmark** | Personal mark — ruby ribbon in outer margin | No | Works well (one tap) |
| **Note** | Write under *this* verse (ḥāshiya) | No | Long-press `﴿N﴾` |
| **Share** | Export text / image (video later) | Yes — start with one, add more | Gather mode + Send page; entry removed from player bar |

They are **not** three buttons on one toolbar. They are three intents with
different shapes. Unification is **conceptual** (everything is “about this
verse”), not “one gesture runs every feature.”

## Diagnosis of the old share UX

| Pain | Cause |
|---|---|
| “Can’t unselect” | Toggle existed; state nearly invisible (tiny ordinal only) |
| Selection not obvious | No gold wash under the ayah block |
| Too many steps | Enter → pick → **same control again** → Send → Text/Image → OS |
| Dual-purpose button | List icon = enter *and* commit |
| Clunky bar | Playback transport stayed up during gather |

Player-bar Gather was removed (#519). This doc defines what replaces it.

## Product insight (locked)

> Start by acting on **one** verse. Entering share **checks off that verse**.
> Multi-select is an **extension of the same mode**, not a separate
> “empty gather then pick many then choose action” ritual.

So share is **single-verse first**; multi-select is free, not mandatory.

## Design constraints (non-negotiable)

From [DESIGN.md](DESIGN.md):

- No dialogs, cards, FABs, snackbars, elevation, ripples, borders
- Hierarchy = spacing, size, ink strength, gold accents
- New UI becomes the paper (ink bleed) or a line in the page
- Playback transport must not grow a second “mode” button that means two things

## Alternatives considered

Explored with independent passes (Codex, Claude, product synthesis). Full
catalog below; **recommended path is G1**.

### Codex

| # | Name | Sketch | Cost |
|---|---|---|---|
| C1 | **Ayah Colophon** | Tap `﴿N﴾` → **Mark · Write · Share** under the verse | M |
| C2 | **Living Margin** | Long-press margin → three verbs in the margin lane | L |
| C3 | **Verse Action Line** | Tap `﴿N﴾` → player bar rewrites to Bookmark · Note · Share | S |

Codex ranked **C1 first**: verse identity first, then verbs; Share starts with
that verse selected; multi is extension. Cost of C1 is bookmark becoming two
taps unless the ribbon stays as a shortcut.

### Claude

| # | Name | Sketch | Cost |
|---|---|---|---|
| L1 | **Ayah Seal** | Press seal → three glyphs; share then multi-adds via seals | M |
| L2 | **Lift to Select** | Long-press body → gold wash + foot verbs; multi = keep tapping | M |
| L3 | **Current-Verse Rail** | Tap verse = current; rail shows three lines | M–L |

Claude ranked **L2 first**: single-lift encodes “verse in hand”; multi is the
same gesture continued; bar strip reused for verbs; ribbon + seal long-press
kept as shortcuts. Guardrail: foot verbs must read as **ink**, not a Material
contextual action bar.

### Synthesis (product)

| # | Name | Sketch | Cost |
|---|---|---|---|
| G1 | **Verse first, verbs second** | Keep ribbon/note; share is verse-first mode with auto-check | M |
| G2 | **Three inks, three places** | Ribbon / seal / Share-only colophon; no shared menu | S–M |
| G3 | **One lift for all three** | Strict L2; all three verbs through body long-press | M–L |

## Decision: G1 — “Verse first, verbs second”

Blends **Codex Colophon** (verse identity → Share) with **Claude Lift** (bar
becomes share tools; multi is extension) without taxing bookmark.

### Principles

1. **Bookmark stays the ruby ribbon** — already paper-perfect; do not force
   Mark through a two-tap menu.
2. **Note stays a saved-ribbon hold** — optional colophon **Write** later; do
   not require “lift mode” to annotate or give the gold ayah mark a control.
3. **Share starts with one verse already selected** — gold wash + Western ordinal `1`.
4. **Multi-select is the same mode** — tap more verses; tap again to unselect.
5. **No dual-purpose control** — never one button for enter *and* commit.
6. **During share, the player bar is replaced** by a gather ribbon, not
   cluttered with transport + share mixed.

### Happy paths (target)

| Intent | Steps |
|---|---|
| Bookmark | Tap ribbon |
| Note | Long-press `﴿N﴾` → write |
| Share one | Tap `﴿N﴾` → **Share** → **Text** *or* **Image** |
| Share many | Tap `﴿N﴾` → **Share** → tap more (gold wash) → **Text** *or* **Image** |

### Interaction detail

**Enter share**

- Primary: short-tap `﴿N﴾` reveals a quiet colophon line under that verse:
  **Share** (and optionally **Write**). Tapping **Share**:
  - pauses playback
  - selects *that* verse (`1` + green vellum ink blot under the ayah)
  - replaces the player bar with the **share ribbon**
- Optional power entry: long-press **verse body** (not seal) jumps straight
  into share-select with that verse checked. Only if body vs seal long-press
  remains clean in practice.

**Share ribbon (replaces PlayerBar while sharing)**

```text
  Cancel  ·  N  ·  Text  ·  Image
```

- `Text` / `Image` are faint until `N ≥ 1`, then full gold/ink strength
- `Cancel` or system back drops selection and restores transport
- Completing share restores transport

**Select / unselect**

- Tap any verse to toggle membership while in share mode
- Selected: a pale gold vellum wash spreads under that ayah (primary
  signal) — same gold token as the ayah marks, fibre on the rim,
  paper gutters so neighbours do not fuse. Western ordinal in Garamond
  ink, centered in the bookmark swallowtail nub (secondary). Chrome
  never uses Arabic-Indic digits.
- Unselected: wash recedes — that *is* the unselect feedback
- Ordinals renumber when a verse is dropped

**What share mode does *not* do**

- Does not batch-bookmark or batch-note
- Does not use the transport row for dual-purpose icons
- Does not require an intermediate “empty gather” before the first verse

### Send page

With G1, the intermediate Send list is **optional**:

- Happy path: Text / Image from the share ribbon go straight to existing
  export pipelines ([SHARE.md](SHARE.md) `VerseTextComposer`,
  `ShareImageRenderer`, OS chooser).
- Keep Send (ink-bleed reorder / remove) only if multi-verse review proves
  necessary after shipping the wash + ribbon. Default plan: **no Send page
  for v1 of this rework**.

### Visual rules for the wash

- Pale even gold wash per verse (`inkSpotHighlight(fillBox = true)`
  with `LocalQuranAccents.gold` at ~26% alpha). Rounded rectangle,
  fibre on the rim only, type stays readable. Not a pooled blob, not
  an oval. Size and opacity interpolate together (fade in on mark,
  fade out on unmark).
- Recitation wash punches through the blot: unread words fade into the
  stain. Never paint a cream rectangle of page paper over the soak.
- No border, elevation, or Material ripple
- Works on Paper / Nightfall / Royal (page ink, not a fixed hex)
- Must remain visible while scrolling

## Explicit non-goals (this rework)

- Material selection mode (checkboxes, floating CAB, scrim)
- Bottom sheet / tray of selected verses (easy to violate paper)
- Range drag in the margin (interesting later; not primary)
- Forcing bookmark or note through multi-select
- Putting Gather back on the idle player bar

## Entry: Mark UX

Tap `﴿N﴾` to share that verse. While gathering, tap a verse (or its
ordinal) to add or drop it. Word long-press stays Root Viewer; notes stay
on the bookmark ribbon. Idle transport has no Share / Gather control
(#519).

Marking uses the paper toggle haptic: a confirm click on, a lighter clock
tick off. Same family as Settings checks and the ayah rail.

The gather bar is not a copy of play. One row, two jobs: Close at the
start (leave), Text and Image a tight pair at the end (send). The count
lives on the verses; the empty middle is paper. No Send page on the happy
path; back leaves gather.

Policy lives in `share/ShareUx.kt` (pure, JVM-tested). Do not invent
entry rules in `ReaderScreen`.

Icon / Reveal / Hold were in-app A/B designs and have been deleted.

## Implementation

1. Gold wash on `AyahBlock` when `gatherOrdinal != null`
2. Share ribbon composable replacing `PlayerBar` when gathering
3. Tap `﴿N﴾` enters gather with that verse already selected
4. Ribbon Text / Image → existing `shareAsText` / `shareAsImage`
5. #519 stays: idle transport has no Gather
6. Happy path skips Send; chooser completion leaves gather

Reuse: `ShareViewModel` selection list, ordinals, text/image exporters,
`ShareHost` / FileProvider. Change the **entry and chrome**, not the export
pipeline first.

## Ranking (for the record)

| Rank | Path | Why |
|---|---|---|
| 1 | **G1 Verse first, verbs second** | Matches insight; keeps best existing gestures; fewest ethos risks |
| 2 | Codex C1 full Colophon | Clean teaching; costs bookmark one-tap unless ribbon kept |
| 3 | Claude L2 Lift to Select | Strong for share; heavier if applied to all three actions |

## Open questions (non-blocking)

1. Optional body long-press as power entry into share-select?
2. Colophon under verse: **Share** only, or **Write · Share**?
3. After first ship, do multi-verse users need a Send review page?

Default answers if implementing without further input: (1) no until needed,
(2) **Share** only, (3) no until proven.

## History

- Gather + text (PR1) and full-ink image (PR2) shipped; see [SHARE.md](SHARE.md)
- Player-bar Gather removed (#519) as dual-purpose chrome
- UX review (player takeover, gold wash, fewer steps) → this document
- Implementation of G1 not yet scheduled
