# Promo studio

Short intro videos of the app: a real recording of the Android app, sound
included, inside a Pixel 10 frame, over a slow blurred landscape, with eased
camera moves (push-ins, pull-backs, punch-ins) and a few words of text. Make
as many as you like; each video is one JSON spec in `specs/`.

```bash
cd tools/promo_studio
python3 studio.py emu boot            # the capture emulator (6 cores, see below)
python3 studio.py emu install         # app-release.apk; first launch downloads timings
python3 studio.py serve               # editor at http://127.0.0.1:8765/
```

In the editor: pick or create a video on the left, set what the app plays
(App tab) and the look (Look tab), press **Record take**, then add camera
keys on the timeline and **Export MP4**. Exports land in
`~/Videos/beautiful-quran-promo/` (`BQ_PROMO_OUT`); takes, plates and
downloads are cached in `~/.cache/bq-promo/` (`BQ_PROMO_CACHE`), never `/tmp`.

Without the editor:

```bash
python3 studio.py list                     # every spec, and whether it has a take / video
python3 studio.py make 03-baqarah-alafasy-royal-repeat   # record if needed, then export
python3 studio.py capture all --force      # re-record every take
python3 studio.py render all               # export every video
python3 studio.py still SPEC 12.5 f.png    # one exact frame
python3 timeline.py SPEC                   # when each verse and repeat happens in the video
```

## The pieces

| File | Role |
|---|---|
| `capture.py` | Drives the app on the emulator (reciter, theme, layout, view, text size) and records picture **and** sound with scrcpy. |
| `compose.py` | The compositor: each frame is a pure function of (spec, t). Used by export, stills and the editor's exact frame. |
| `phone.py` | Draws the Pixel 10 front (aluminium rim, glass, punch-hole, keys) at the take's resolution. |
| `library.py`, `backgrounds.json` | Background footage (Pexels) and the blurred, seamless plates made from it. |
| `timeline.py` | Verse and repeat times in video seconds, from the app's own timing data. |
| `server.py`, `editor/` | The editor. |
| `spec.py` | Spec defaults and validation. |

## Takes: the real app, its real sound

A take is the Android app playing, recorded at 1× by
[scrcpy](https://github.com/Genymobile/scrcpy) with
`--audio-source=playback` (Android's AudioPlaybackCapture). Picture and
sound share the device clock in one file, so the ink and the voice can't
drift; nothing is laid in from EveryAyah afterwards. (`--audio-source=output`
records about 37 dB too quiet on the emulator.) The emulator still runs
`-no-audio`: playback capture taps the guest mixer, so nothing plays through
the host's speakers.

How a take starts matters:

- The verse is opened first with the deep link (no `play`), the page
  settles, the recording starts, and only then does playback begin. The
  video keeps 1 s of the still page before the first sound (`lead`).
- Playback starts from the app's own controls, not `?play=true`: that deep
  link reloads the reader, which crossfades and re-scrolls the page right as
  the recitation starts. Ayah 1 (and the Mushaf) use the bar's Play, which
  plays the basmalah first; other verses tap the verse's first word, because
  after a deep link the bar's Play still points at ayah 1.
- The recitation's start is found in the recording's own audio.
- The status-bar clock is hidden (the emulator draws it in a broken face).

Smoothness comes from the emulator. The pool's 3-core slots render the
reader at about 44 fps; the capture emulator (`studio.py emu boot`: the
pool's golden snapshot, read-only, `-cores 6`, port 5650) records at
55–58 fps at 1080×2400. scrcpy writes a frame only when the screen changes;
the take is resampled to 60 fps by repeating frames, never by inventing them.
On this host only one emulator can run at a time (a second one segfaults on
boot), so `scripts/emu.sh down` first if a pool slot is up.

Takes are keyed by what they record (reciter, chapter, ayah, theme, layout,
view, text size), so specs that differ only in camera, background or text
share one take.

## What the app shows off

- **Repeats.** When a reciter repeats words, the repeated pass inks in orange
  (`timeline.py` and the editor's Repeats track show when). They need the
  runtime timings the app downloads on first launch: after `emu install`,
  give it that launch online.
- **Tarjīʿ.** Held, pulsing notes make the active word's glimmer shimmer.
  Nightfall and Royal green only (Paper has no glint colour). Good moments:
  the close of 1:7 (Hani and Alafasy), Hani 2:16's closer, Hani 4:145.
- Good repeat verses, from the live data: Alafasy 2:14, 55:48, 80:37, 79:26,
  83:17; Hani 2:16, 55:26 (the whole verse twice), 55:27, 95:6, 67:22.

## Camera and look

Camera keys: `t` (video seconds), `zoom` (1 = the whole phone), `x`, `y` (the
point on the phone screen the camera centres on, 0–1), `rot` (degrees) and
`ease` (how the move into this key is paced; `hold` is a cut). The active
verse sits around `y` 0.28–0.34 on the scroll. Fast moves get real motion
blur (`motion_blur`: shutter as a share of the frame interval).

The background follows the camera by `parallax`, so a push-in deepens the
blur behind the phone. Footage is from Pexels (free licence); a spec can use
any image or video as `file:/abs/path`, or a flat `color:#RRGGBB`.

Captions use Cormorant Garamond italic (the product page's voice), the end
card the app icon and Junicode. The brief in [docs/KEY_ART.md](../../docs/KEY_ART.md)
applies: the phone screen is the only paper, no music under the recitation,
never a hexagram, text light.
