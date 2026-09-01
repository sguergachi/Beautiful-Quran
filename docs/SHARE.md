# Sharing verses

**Status: PR1 + PR2 shipped (gather + text + full-ink image).** Video remains
proposed. **Entry UX** is four Developer-gated designs (colophon / lift /
seal / action line) — see [VERSE_ACTIONS.md](VERSE_ACTIONS.md). Production
entry is still **off** (`ShareUxVariant.OFF`): nothing on the page starts
gather. This file is the design record for *gather mode* and the export
pipeline: **text**, **image**, and later **video that carries the ink**.

## Why it exists

The signature moment of this app is a verse arriving word by word under the
reciter's voice. Today that moment is trapped inside the app: a reader who
wants to send 2:255 to someone can only describe it. Sharing should send the
*moment*, not a screenshot of it — the same soft directional wash, the same
paper, the same recitation.

Two shapes of intent, one mechanism:

- **"Send me that verse."** One ayah, as text, in a chat.
- **"Make me something."** Several ayahs — often *not* consecutive, and not
  in mushaf order — recited over the ink, as a video to post.

The second is why selection is an ordered list and not a range.

## Gather mode

Gathering is a **mode of the reader sheet**, not a new sheet. The page keeps
its layout; it grows ordinals in the margin.

- **Enter** via `ShareViewModel.enterShare(surah, ayah)` (verse-first: that
  ayah is already `١`; pauses recitation — the mode owns the tap). The idle
  player bar does **not** host a Gather control
  ([#519](https://github.com/sguergachi/Beautiful-Quran-/pull/519)). Four
  discoverable entry designs (Icon / Reveal / Hold / Mark) live behind
  Settings → Developer → Verse share ([VERSE_ACTIONS.md](VERSE_ACTIONS.md)).
- **Pick** by tapping a verse (word or ayah). Its ordinal sits in the
  bookmark swallowtail nub — same strip, first-line inset, ribbon
  width, and nub height — in Western digits (1 2 3), Garamond ink.
  The ribbon is hidden while gathering. Tap again to drop it; the rest
  renumber.
- **Order is tap order.** Tapping 2:255, then 112:1, then 2:1 gathers exactly
  that sequence. No ranges, no sorting, no "from / to" pickers. Cap:
  `SHARE_SELECTION_MAX` (20).
- **Selection outlives the page.** `List<AyahRef>` is held in
  `ShareViewModel` (activity scope), so turning to another chapter keeps the
  list intact.
- **Leave** with system back (drops the selection) or
  `ShareViewModel.exitGather()`. **Commit** via
  `onGatherControlClick()` when the list is non-empty (opens Send).

While gathering, word taps do not seek, word long-press does not open the
Root Viewer, and the bookmark ribbon (including its note hold) is inactive.
The mode owns the tap — interactions are *replaced*, not stacked.

**Mode chrome (visual QA):** gather ordinals use 14 sp Garamond
Western digits, centered in the 11×14 dp bookmark nub (not gold, not
Arabic-Indic) — the same height as the swallowtail icon.
Selection wash and gather-bar takeover are planned — see
[VERSE_ACTIONS.md](VERSE_ACTIONS.md).

### Entry redesign

Player-bar Gather was removed as a dual-purpose control. The product plan is
**verse-first share** (one verse checked on enter; multi-select extends the
same mode), documented in [VERSE_ACTIONS.md](VERSE_ACTIONS.md).

## The Send page

Committing **ink-bleeds into the Send page** — the same `InkRevealOverlay`
primitive as the Root Viewer (see [DESIGN.md](DESIGN.md), "The ink bleed").
Owned by `ShareHost` so MainActivity does not grow another cluster of overlay
booleans. (G1 may skip Send for the happy path — [VERSE_ACTIONS.md](VERSE_ACTIONS.md).)

It carries:

1. The gathered verses **in order**, each with ordinal, Arabic preview
   (Hafs), reference, and a gold **×** to remove (no drag-reorder yet).
2. Outputs: **Share as text** · **Share as image** (video later).
3. Quiet error line if load/render fails.

Theme / aspect / reciter toggles and drag-reorder stay deferred.

The only floating surface in the whole flow is Android's own `ACTION_SEND`
chooser at the last hop. That is the OS, not our paper.

**Back:** Send open → close Send (selection kept, still gathering). Gathering
with Send closed → drop selection and leave gather mode.

## The three outputs

### Text — shipped (PR1)

A pure formatter (`VerseTextComposer`) turns the ordered selection into
plain text: Arabic line, optional translation, and a reference footer
(`al-Baqarah 2:255`). Pure in, pure out, JVM-tested — no Android types.
Handoff is `ACTION_SEND` + `EXTRA_TEXT` (no file).

### Image — shipped (PR2)

One PNG of a **paper sheet with verses at rest in full ink** — not a
screenshot of the live reader, not the wash yet:

1. Thin `ShareImageCard` composable (Hafs + translation + gold reference
   footer + faint **Beautiful Quran**). Fixed **Paper** theme so shares stay
   readable parchment regardless of the reader's night/royal mode.
2. `ShareImageRenderer` hosts each **verse strip** offscreen at 1080 ×
   wrap-height, software-draws a bitmap per strip, and **stitches** them.
   The gold chapter footer (`al-Baqarah 2:1–N`) is composed on the last
   strip so it cannot be dropped. The GPU never rasterises the full
   gather. **Not** full `ReaderScreen` (no LazyColumn / playback / gestures).
3. `ShareFiles` writes PNG under `cacheDir/share/`, exposes a `FileProvider`
   URI (`${applicationId}.share`), keeps the newest few files.
4. `ACTION_SEND` `image/png` + `FLAG_GRANT_READ_URI_PERMISSION`.

**Wash fidelity (for PR3):** full-ink stills do not exercise the faded leading
edge. `WashEdgeProbe` is the pure sampler that will reject hard peels once
wash frames are rendered offscreen — unit-tested on synthetic gradients now.

### Video — the ink, exported — not yet

Not a screen recording. Deterministic offscreen export. Reuse the PR2 attach
path; add a synthetic clock and codecs only after wash frames pass
`WashEdgeProbe`. First silent video prototype should stay bounded (e.g.
720×1280 @ 30 fps, capped duration).

Original pipeline notes (for later PRs):

1. **Timeline.** Word timings from `quran.db`, concatenated in gathered order
   with a breath gap between verses.
2. **Frames.** Narrow scripture renderer under a custom `MonotonicFrameClock`.
3. **Encode.** `HardwareRenderer` / `RenderNode` → `MediaCodec` surface →
   AVC → `MediaMuxer`.
4. **Audio.** Separate design spike: stage complete per-ayah files without
   poking `PlaybackService`'s private `SimpleCache`.
5. **Progress is ink** (throttled; a plain line is sufficient initially).

## The footer mark

Image (and later video) carries a quiet footer: the reference in gold
(`al-Baqarah 2:255`, or a same-surah range / multi-surah join) with
**Beautiful Quran** beneath it in faint ink. A long gather makes a tall,
higher-resolution sheet — verses are not cropped to a phone-screen height. Text shares
carry the reference only (no app watermark in the chat body).

## Shape of the code

```text
share/AyahRef.kt                 AyahRef + toggle/ordinals pure helpers
share/ShareUx.kt                 four entry designs + pure gesture policy
share/VerseTextComposer.kt       text formatting — pure, JVM-tested
share/ShareFiles.kt              cacheDir/share + FileProvider URI
share/ShareImageRenderer.kt      offscreen ComposeView → Bitmap
share/WashEdgeProbe.kt           soft-edge assertion (JVM-tested)
ui/share/ShareImageCard.kt       fixed Paper full-ink card
ui/share/ShareViewModel.kt       selection + prompt + text/image export state
ui/share/ShareHost.kt            BackHandler + InkRevealOverlay + chooser
ui/share/ShareRibbon.kt          replaces PlayerBar while sharing
ui/share/ShareComposeSheet.kt    Send page (list + text + image)
res/xml/share_paths.xml          FileProvider paths
```

Reader integration: ordinals + tap ownership while gathering; player bar no
longer hosts Gather (#519). Entry/chrome rework: [VERSE_ACTIONS.md](VERSE_ACTIONS.md).

## Phasing

| Phase | Ships | Status |
|---|---|---|
| 1 | Gather mode + text share | **shipped** |
| 2 | Full-ink image export + FileProvider + wash probe | **shipped** |
| 2b | Verse-first share UX — four Developer entry designs + gold wash + share ribbon | **in-app A/B** ([VERSE_ACTIONS.md](VERSE_ACTIONS.md)); production still OFF |
| 3 | Bounded silent ink video | after 2b (or parallel once entry exists) |
| 4 | Audio staging + mux | after silent video is stable |
| 5 | Web parity | later |

Each phase ships something usable on its own.

## Risks

- **Offscreen wash fidelity.** Full-ink PNGs ship without wash masks.
  PR3 must prove `Fade.kt` blend layers on the same offscreen path and pass
  `WashEdgeProbe` on 0.25 / 0.5 / 0.75 frames before any codec.
- **Selection recomposition.** Ordinals are a `Map<AyahRef, Int>` derived once
  per selection change; ayah blocks read only their own ordinal.
- **Codec / GPU budget.** Start bounded (720p, 30 fps, cap duration) before
  1080×1920 multi-minute exports.
- **Audio cache is not an export API.** `SimpleCache` is private to
  `PlaybackService`. Audio export needs an explicit staging boundary.
- **Long selections.** Gather cap 20. Each verse is its own 1080-wide
  strip; strips are stitched. Do not rasterise the whole wrap as one
  view — HWUI aborts the process.

## Non-goals

- No accounts, no upload, no link-sharing service. Text is `EXTRA_TEXT`; image
  is a file + `ACTION_SEND` (invariant 6: offline-first, no backend).
- No editing surface — no font pickers, no colour pickers, no stickers.
- No range selection. Ordered taps are the model.
- No drag-reorder or multi-theme matrix yet (image is fixed Paper).
- No hosting full `ReaderScreen` offscreen for image/video.
