# Key art: the rain carriage

Marketing art for Beautiful Quran, starting with the Instagram Reel series
*Rain carriage*. [DESIGN.md](DESIGN.md) governs the app; this file governs
everything we show *around* the app. The app is never redrawn for marketing:
every screen in key art is a real recording of the Android app (see
[Producing the assets](#producing-the-assets)).

## The brief, in one sentence

*You are on a train in the rain, the world is a soft blur of water and light,
and the only sharp thing in it is the page in your hand, inking itself in
time with the reciter.*

Everything below serves that sentence. If a choice makes the phone less
calm, less sharp, or less central, it is wrong.

## The niche we are speaking to

Not "Islamic content" in general. The Reel sits where three living aesthetics
overlap, and borrows the grammar each one has already taught its audience:

1. **Rain-window ambience (the lofi lineage).** The window is the most
   recognisable frame in ambient video. Lofi Girl's study window made
   "someone quietly doing one thing by a window" a whole genre, and
   rain-on-glass is its most-loved weather. The audience knows how to watch
   it: unhurried, often muted, sometimes for hours. We take the frame, the
   rain, and the patience. **We do not take the beat** (see Sound).
2. **Transit melancholy (Makoto Shinkai's trains).** Rain seen through train
   glass, with light breaking through it, is one of anime's most-quoted
   images. In those films rain carries longing, and light through rain
   carries hope. We take the refracting droplets, the step-printed passing
   world, the crepuscular break in the cloud, and the feeling that the
   journey matters more than where it ends.
3. **Muslim slow living ("quiet life", soft deen content).** Faith content is
   shifting from preaching to presence: a calm routine, an understated
   object, a private moment of remembrance. Quiet luxury rather than loud
   aspiration. The audience wants to *be* the person in the frame, not to
   hear about them. We take the first-person hands, the knit sleeve, the
   unbranded calm, and the lack of a face.

**Reels grammar we are using deliberately:**

- **Muted-first, text-light.** Ambient, low-voiceover Reels are outperforming
  talking heads in many niches. The Reel must work with the sound off: the
  ink wash *is* the subtitle.
- **POV without the label.** First-person framing, no "POV:" sticker. The
  viewer's hands are your hands.
- **One unbroken take.** No cuts, no zooms, no hook cards. Calm is the hook.
  The first frame already shows a word being written.

Research notes and their limits: trade blogs on 2026 Reels trends agree on
muted/ambient and unlabelled POV formats, but no source measures a "Quran +
rain" or "train-window" trend directly. The long-running genre of
recitation with rain ambience on YouTube, and Lofi Girl's window, are the
strongest evidence. Treat the niche as a hypothesis to test across the three
variants, not a fact. Measure saves and shares, not likes.

## How the brand survives outside the paper

The app's rule is *one sheet of paper, calm and pure*. In key art:

- **The phone screen is the only paper.** Everything else is water, glass,
  light, cloth and skin. Nothing drawn in the scene borrows the paper
  palette, so the reader is always the brightest calm thing in the frame.
- **Ink fidelity is a brand asset.** The wash on screen is the real
  reciter-timed wash: soft directional bloom with a visible faded leading
  edge. Never fake it, speed it up, or crop the active line out.
- **Gold and green carry over as light, not as decoration.** Royal green
  becomes the valley; gilt becomes the sun-break or a lamp. There is no
  ornament overlay, frame, sticker or logo bug on the video. The name appears
  once, in the caption and the end card.
- **Never a hexagram.** No six-pointed star in any light, raindrop
  arrangement or bokeh shape (same rule as the ornament generator). Bokeh
  is circular.
- **No faces, no people in the view.** Only the viewer's own hand, in a
  sleeve. Modest, ungendered, warm.

## Sound

- **The recitation leads**: Mishary Rashid Alafasy, the app's default
  reciter, the same EveryAyah recordings and order the app plays (basmalah,
  then 55:1–10), placed to the frame where the capture played them.
- **Ambience, only where the picture has weather**: the rain carriage pieces
  may carry rain on glass and carriage rumble (synthesised or royalty-free),
  never music. The intro and mockups are recitation alone.
- **No music, no lofi beat.** Much of the audience avoids instruments under
  Quran. A beat would also fight the reciter's own rhythm, which the ink is
  following. The rain is the texture.
- Mix: recitation about −16 LUFS integrated with peaks under −1 dBTP;
  any ambience 14–18 dB beneath it.

## Why Ar-Rahman 1–10

The opening of Ar-Rahman names the things the window shows. The view is
staged to answer the verses, never to illustrate them literally:

| Verse | Meaning | What the window does |
|---|---|---|
| 55:1–4 | The Most Merciful taught the Quran, created man, taught him speech | Rain, the carriage, the page: the human scale |
| 55:5 | The sun and the moon by precise calculation | The light-break: sun (dawn, valley) or moon (night) |
| 55:6 | The stars and the trees prostrate | The line passes through trees; night shows the first stars |
| 55:7–9 | He raised the heaven and set the balance | The cloud opens upward; the horizon levels |
| 55:10 | The earth, laid out for all creatures | The widest view: sea, valley or the lit plain |

The light-break is timed to land on 55:5 in every variant.

## The Reel (final cut): 18.5 s

Short attention spans: no intro, no title card. Frame 1 is the app, already
moving, with the recitation already sounding. The goal is for viewers to
think "I've never seen that — what app is that?". 1080 × 1920, 60 fps,
full-resolution takes, voice unbroken (basmalah, 55:1–3).

| Time | Shot | Text |
|---|---|---|
| 0.0 | Full-bleed app, Royal green: the chapter's ornament band, and beneath it the basmalah writing itself in white-gold; slow push | *Every word, written as it is recited.* (low, on a scrim) |
| 6.0 (55:1) | Cut to the phone, Royal green | |
| 8.3 | **Theme flip mid-word** to Paper: the page changes, the ink keeps writing | *Word by word, with meaning.* |
| 10.6 (55:2) | Flip to Nightfall | |
| 12.9 | The Mushaf page, Nightfall, gold banner and verse marks | *Or the whole page, as printed.* |
| 15.4 | End card while 55:3 is recited: icon, *Beautiful Quran*, FREE · ANDROID & WEB, link in bio | |

`reel/src/cut.tsx`. The longer 31.5 s edit (`reel.tsx`, opening on the
chapter header) remains for slower placements.

## The intro Reel: "Every word, as it is recited"

31.5 s, 1080 × 1920, 60 fps, recorded at full device resolution. The
recitation (basmalah, 55:1–4) runs unbroken; every cut lands on a verse
boundary, because all takes share one timeline (see the tools README). It
opens on the chapter header, the app's most ornate moment.

| Time | Verse | Shot | Caption |
|---|---|---|---|
| 0.0–3.0 | silence | Full-bleed chapter header, Royal green: tooled weave field, gold rosette, سورة الرحمن, slow push toward the rosette | none |
| 3.0–9.0 | basmalah | The recitation begins; the app's own scroll carries the page to the basmalah, which writes itself in white-gold | none |
| 9.0 | 55:1 | Cut to the phone, Royal green, settling from 1.10× | *Every word, as it is recited.* |
| 13.6 | 55:2 | Paper phone on paper | *Its meaning, word by word.* |
| 18.3 | 55:3 | Mushaf layout, Nightfall: the printed page with Ar-Rahman's gold banner | *Or the whole page, as printed.* |
| 23.8 | 55:4 | Royal green, slow push | *Free and open source.* |
| 28.3–31.5 | tail | End card: icon, *Beautiful Quran*, ANDROID · WEB · LINK IN BIO | |

Captions are Cormorant Garamond italic (the product page's voice); the end
card uses Junicode, the app's English face. Captions sit above the phone,
clear of Instagram's top bar; the active verse always sits in the screen's
upper third, clear of the caption and action rail at the bottom.

**Rules learned making it:**

- Every screen is the Android app. Web-reader footage and redrawn
  screens were rejected.
- No stylised or vector hands. A hand on screen must be photographic.
- Smoothness is part of fidelity: wash footage is captured slowed and
  conformed (≥ 60 distinct frames per second of recitation), never upsampled.
  Android's animation scales follow the capture speed, so scrolls, fades
  and the glint's drying keep their real pace.
- Show the app at its most beautiful: open on the chapter header, and
  include the Mushaf page, not only the scroll reader.

## Device mockups

Full-length (basmalah + 55:1–10, 69 s), one theme each: the phone
centred on its theme's ground, a quiet italic wordmark beneath. For
posting as-is, or as source for shorter cuts.

## The rain carriage (photoreal, in progress)

The rain-window concept above, made photoreal: an image model renders a
first-person hand holding a phone with a flat chroma-green screen in a
rainy train window. `composite_screen.py` keys that screen, fits its edges
and lays the real take in, with edge unmixing and skin-aware despill (the
models paint green light onto the fingers). Three plates exist (dawn coast,
night countryside, mountain valley). Moving versions need a video model
(Runway's Veo/Seedance/Kling: a paid plan) or free stock footage of a
green-screen phone (Pexels), which `composite_video.py` tracks frame by
frame. The palettes per variant:

| | **A · Paper dawn** | **B · Nightfall line** | **C · Royal valley** |
|---|---|---|---|
| App theme | Paper | Nightfall | Royal green |
| Hour | Grey morning rain, breaking to pale gold | Blue hour into night | Late afternoon under storm cloud |
| The view | Misted coast; the sea opens under the break | Wet countryside, distant town lights, the moon | Deep green mountain valley; sun shafts on the valley floor |
| Sleeve | Oat knit | Charcoal knit | Moss knit |

## Promo videos (the studio)

Short intro videos in the Handheld-feature style (a phone floating over a
soft-focus landscape, the camera easing in and out) are made with
[tools/promo_studio](../tools/promo_studio/README.md): an editor and CLI
that record the Android app **with its own sound** on an emulator, frame it
in a Pixel 10 over blurred Pexels footage, and export MP4s. A timeline cuts
the recording and keyframes the camera, the phone and the background; there
is no text. The React/Tailwind editor has resizable panes, undo/redo,
multi-key editing and a draggable playhead. Recording and export jobs show
live stages and progress, survive a server restart, and can be cancelled.
The emulator panel starts and prepares additional instances of the shared
golden AVD; use one six-core capture at a time for the smoothest ink.
Ten starter specs cover Alafasy and Hani across the three
themes, including reciter repeats and tarjīʿ.

## Deliverables

| Asset | Spec |
|---|---|
| Reel, final cut | 1080 × 1920, 60 fps, H.264, AAC 48 kHz, −16 LUFS, 18.5 s |
| Long Reel | same, 31.5 s, opens on the chapter header |
| Mockups ×3 | 1080 × 1920, 60 fps, 69 s, one per theme |
| Product page hero | Al-Fatihah, Paper, 1080 × 2400, 30 fps, about 1 MB |
| Product page theme loops ×3 | Al-Fatihah opening, 540 × 1200, 13 s, about 150 KB |
| Rain carriage plates ×3 | 9:16 stills with a green screen, for compositing |

## Producing the assets

All tooling, with commands, is in
[tools/key_art/README.md](../tools/key_art/README.md): emulator takes
slowed to 0.25×/0.5× (a capture-only speed patch), onset detection,
conforming to 1× at 60 fps, and the edit in Revideo (MIT). Rendered
videos are not committed except the product-page clips in `docs/`.
