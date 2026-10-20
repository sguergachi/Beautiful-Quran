# Promo studio

Short intro videos of the app, made of three things: a real recording of
the Android app (sound included), a Pixel 10 model, and a slow blurred
background. A timeline cuts the recording and keyframes the camera, the
phone and the background. Make as many as you like; each video is one JSON
spec in `specs/`.

```bash
cd tools/promo_studio
python3 studio.py emu boot            # the capture emulator (6 cores, see below)
python3 studio.py emu install         # app-release.apk; first launch downloads timings
python3 studio.py serve               # editor at http://127.0.0.1:8765/
```

For another device on the same LAN, run `python3 studio.py serve --host 0.0.0.0`
and open `http://<this computer's LAN IP>:8765/`. The default bind remains localhost.

In the editor: pick or create a video on the left, set what the app plays
(Recording tab) and the look (Look tab), press **Record take**, then edit on
the timeline and **Export MP4**. Every change is undoable (Ctrl+Z /
Ctrl+Shift+Z) and saved as you go. Exports land in
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
| `anim.py` | Keyframe evaluation and the edit's time mapping (the editor mirrors it). |
| `phone.py` | Draws the Pixel 10 front (aluminium rim, glass, punch-hole, keys) at the take's resolution. |
| `library.py`, `backgrounds.json` | Background footage (Pexels) and the blurred, seamless plates made from it. |
| `timeline.py` | Verse and repeat times in video seconds, from the app's own timing data. |
| `server.py`, `editor-app/` | Local Python API and the React + TypeScript + Tailwind editor. |
| `emus.py`, `report.py` | Shared emulator launcher and structured job progress. |
| `spec.py` | Spec defaults and validation. |
| `test_studio.py`, `editor-app/tests/` | Offline regressions: spec migration, Python/TypeScript geometry parity, edit operations, playback lifecycle, cuts, audio, persistence, job scheduling and cancellation. |

## Running and checking the editor

`studio.py serve` installs frontend dependencies if needed, builds changed
sources, and serves everything on localhost. Node 22.18+ is needed for the
TypeScript tests. During frontend development, run the Python server and
`npm --prefix editor-app run dev` (Vite proxies API/media requests to it).
`serve --no-build` serves the last production build.

From the repository root:

```bash
python3 -m unittest discover -s tools/promo_studio -p 'test_*.py'
npm --prefix tools/promo_studio/editor-app test
npm --prefix tools/promo_studio/editor-app run build
```

[Performance measurements and implementation notes](../../docs/PROMO_STUDIO_PERFORMANCE.md)
cover preview cadence, API latency and export throughput. Exports default to
four background workers with bounded FFmpeg pools, preserving full output
resolution and motion blur. The CLI's `--workers` can override that budget.

Long work runs in separate processes. **Jobs** shows stages, live progress,
logs, cancellation and completed video links. Captures count recorded
seconds; exports count rendered frames. Jobs use a snapshot of the edit you
submitted, so later changes apply to the next job. The server persists its
queue and reattaches running jobs after a restart; a failed job leaves the
editor available. Cancelling a waiting job leaves a running take alone.

Drag the separators to resize the video list, preview/timeline and inspector;
double-click to reset. Preferred pane sizes persist across visits and fit the
available window. At 1,000 CSS pixels wide or less, the video list and inspector
open one at a time over the canvas; their top-bar buttons or the backdrop close
them. Toolbars wrap, and a tall timeline leaves room for the preview and playback
controls. Narrow inspector fields stack their labels above the controls.
The ruler scrubs
with pointer capture and scrolls at its edges. Shift-drag selects several
keys, which can move, copy, paste or delete together. **?** lists shortcuts.

[Studio UI review](../../docs/PROMO_STUDIO_UI_REVIEW.md) records the clipping
fixes and the desktop, narrow-window and zoom-equivalent review matrix.

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

Smoothness comes from the emulator. The **Emulators** panel lists running
instances with their core count, app state, current job and a live screen.
Start another, prepare its app, then choose it in **Recording** (or leave
**Any free emulator** selected). Captures queue per emulator and shared take;
exports run one at a time. Recording and export do not overlap on the host,
so exports cannot steal frame time from a take.
Use six cores and record one take at a time for the smoothest animation.
Parallel takes work, but compete for the host's CPU and GPU.

Capture instances reuse the pool's golden AVD and snapshot, through
`scripts/emu.sh capture PORT` with `EMU_CORES=6`. The existing boot lock,
GPU setup and RAM guard apply; no extra AVD is created. Each read-only boot
needs **Prepare app** once. Stop spare instances in the panel when done.

scrcpy writes a frame only when the screen changes. Take metadata separates
average capture rate (including still holds), median active cadence, and
the 90th-percentile active frame interval; gaps of 200 ms or more are still
holds. These describe recorded cadence, not a promise of 60 unique frames
per second. The output is conformed to 60 fps by repeating frames. The
recorder closes with SIGTERM so SDL finalizes the file even when the studio
was launched in the background with SIGINT ignored.

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

## The timeline

- **Recording** track: the take, cut into pieces that play back to back.
  **S** (or ✂ Split) splits at the playhead, **Delete** removes the selected
  piece, dragging a piece's edges trims it. The sound is cut with the
  picture (8 ms fades at each cut, so no clicks). In the spec:
  `"edit": [{"in": 0.0, "out": 12.4}, {"in": 15.0, "out": 21.0}]`, in take
  seconds; the take opens 1 s before the recitation.
- **Verses** and **Repeats**: where each verse and each repeated word falls,
  mapped through your cuts. Click one to jump there.
- **Property lanes**, one per property, in three groups (click a group to
  fold it; folded, its keys share one lane):

  | Group | Properties |
  |---|---|
  | Camera | Zoom (1 = whole frame), Pan X / Pan Y (the point of the frame the camera centres on, 0–1), Rotate |
  | Phone | Phone X / Phone Y (offset, shares of the frame), Phone scale, Phone tilt |
  | Background | Blur (output pixels), Brightness (− towards black, + towards white) |

  A property with no keys is one value for the whole video. ◆ adds or
  removes a key at the playhead; once a property has a key, changing it (in
  the Animate tab or on the picture) keys it at the playhead. Drag keys to
  retime them (they snap to the playhead, other keys, cuts and verse starts;
  hold Alt not to snap); double-click a lane to add a key there. A selected
  key has a time, a value and an ease (`hold` is a cut); [ and ] jump
  between keys. In the spec: `"tracks": {"zoom": [{"t": 0, "v": 2.2},
  {"t": 2.5, "v": 1, "ease": "inOutCubic"}]}` and static values in `props`.

On the picture: drag to pan, scroll to zoom, Shift-scroll to rotate, Alt-drag
and Alt-scroll to move and scale the phone. **Key camera** (K) keys zoom and
pan at the playhead; **Push in** / **Pull back** / **Punch in** key a move
to the active verse (zoomed so the screen spans the frame's width) and back.
Ctrl-scroll zooms the timeline; **Fit** shows the whole video; drag the
divider above the timeline to trade picture for lanes. Ctrl+C / Ctrl+V copy
the selected key and paste it at the playhead. **? Shortcuts** (or `?`) lists
every key. Edits save as you go (Ctrl+S saves now); exports, stills and
switching videos save first, and a failed save is shown and retried from the
save status.

Zoom and phone scale ease in log space, so a 1 → 4 zoom feels even. Fast
moves get real motion blur (`motion_blur`: the shutter as a share of the
frame interval). The background follows the camera by `parallax`. Its blur
is baked into a small plate at the least blur the video uses; more is added
per frame, so blur can be keyed.

Footage is from Pexels (free licence); a spec can use any image or video as
`file:/abs/path`, or a flat `color:#RRGGBB`. The brief in
[docs/KEY_ART.md](../../docs/KEY_ART.md) applies: the phone screen is the only
paper, no music under the recitation, never a hexagram.

Specs written before the timeline (with `camera`, `captions`, `end_card`,
`duration`) are migrated when loaded: the framing is kept, the text is dropped.
