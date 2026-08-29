# Verse actions: bookmark · note · share

**Status: four entry designs in Developer for in-app A/B.** Export
(text/image) is shipped; the shipped reader still has **no** share entry
(`ShareUxVariant.OFF`). Settings → Developer → **Verse share** toggles
Colophon, Lift, Seal, and Action line — exclusive, paper checkmarks.

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
3. **Share starts with one verse already selected** — gold wash + ordinal `١`.
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
  - selects *that* verse (`١` + soft gold-yellow wash under the ayah block)
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
- Selected: soft feathered **gold-yellow paper wash** under the full ayah
  block (primary signal); gold Arabic-Indic ordinal in the outer margin
  (secondary)
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

- Soft, feathered gold-yellow tint on paper — ink soak, not a hard rectangle
- No border, elevation, or Material ripple
- Works on Paper / Nightfall / Royal (theme tokens, not fixed hex that
  only looks right on one theme)
- Must remain visible while scrolling

## Explicit non-goals (this rework)

- Material selection mode (checkboxes, floating CAB, scrim)
- Bottom sheet / tray of selected verses (easy to violate paper)
- Range drag in the margin (interesting later; not primary)
- Forcing bookmark or note through multi-select
- Putting Gather back on the idle player bar

## In-app test designs

Developer → **Verse share** (off by default; requires developer mode):

| Toggle | Entry | Then |
|---|---|---|
| **Colophon** | Tap `﴿N﴾` → **Share** under the verse | Share selects that ayah, gold wash, ribbon |
| **Lift** | Long-press the verse body | Immediate gather; root-viewer hold is displaced |
| **Seal** | Tap `﴿N﴾` → **Share** beside the mark | Same gather as colophon |
| **Action line** | Tap `﴿N﴾` → player bar becomes **Cancel · Share** | Share selects that ayah |

All four then share: tap more verses to add/drop (wash + margin ordinal),
ribbon `Cancel · N · Text · Image` (no Send page on the happy path), back
dismisses prompt or leaves gather.

Policy lives in `share/ShareUx.kt` (pure, JVM-tested). Do not invent
entry rules in `ReaderScreen`.

## Implementation sketch (later)

Shipped for A/B, not locked as G1:

1. Gold wash on `AyahBlock` when `gatherOrdinal != null` or share-prompted
2. Share ribbon composable replacing `PlayerBar` when gathering (and on
   action-line prompt)
3. Four entries behind `Settings.shareUxVariant`
4. Ribbon Text / Image → existing `shareAsText` / `shareAsImage`
5. #519 stays: idle transport has no Gather
6. Happy path skips Send; chooser completion leaves gather
7. Visual QA on Paper + Nightfall + multi-verse toggle — pick a winner,
   then delete the other three

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
