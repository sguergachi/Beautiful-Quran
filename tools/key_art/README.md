# Key art tools

Pipeline for the Instagram intro, the device mockups and the product-page
clips. Direction, rules and the edit's shot list are in
[docs/KEY_ART.md](../../docs/KEY_ART.md).

Everything on screen is the **real Android app**, recorded on an emulator.

| File | Role |
|---|---|
| `capture-speeds.patch` | Capture-only build change: adds 0.25× and 0.5× to the reader's speed control. Never committed to the app. |
| `android_take.py` | Drives the emulator by text/content-desc (theme, text size, speed, clean status bar) and records a take. |
| `find_onset.py` | Finds where playback started in a take (the transport glyph flips ▶ → ⏸). |
| `conform_take.py` | Speeds a slow take back to 1×, resamples to 60 fps, removes the lit speed label, and starts every clip 0.6 s before the recitation. |
| `reel/` | The edit, in [Revideo](https://github.com/redotvideo/revideo) (MIT, headless Motion Canvas): `reel.tsx` (29 s intro) and `mockup.tsx` (full-length device mockup). |
| `composite_screen.py`, `composite_video.py` | Put a take onto a green-screen phone in a photo or stock clip (chroma alpha, edge unmixing, skin-aware despill, tracked corners). |

## Why slow takes

The emulator can't render the reader at 60 fps, and the wash must never be
steppy. The app's ink follows the audio clock, so a take recorded at 0.25×
contains four times as many distinct wash frames per second of recitation.
`conform_take.py` speeds it back up. The recitation is not taken from the
emulator: the edit lays in the same EveryAyah files the app played, back to
back (Android plays them gapless), from the detected onset. Every conformed
clip of one recitation shares a timeline, so the edit can cut between themes
on any verse boundary with no audio edit.

## Recording

```bash
# capture build (temp worktree, never pushed)
git worktree add --detach ~/.cache/bq-key-art/capture-build origin/master
git -C ~/.cache/bq-key-art/capture-build apply "$PWD/tools/key_art/capture-speeds.patch"
(cd ~/.cache/bq-key-art/capture-build && ./gradlew assembleRelease)

S=$(scripts/emu.sh lease "key art"); export ANDROID_SERIAL=$S
scripts/emu.sh run --apk ~/.cache/bq-key-art/capture-build/app/build/outputs/apk/release/app-release.apk
adb shell wm size 720x1600; adb shell wm density 280      # the phone in frame is ~690 px wide
K=tools/key_art
python3 $K/android_take.py prep
python3 $K/android_take.py theme "Royal green"            # Paper | Nightfall | Royal green
python3 $K/android_take.py speed 1;  python3 $K/android_take.py ref ~/.cache/bq-key-art/takes/royal-ref.png
python3 $K/android_take.py speed 0.25
python3 $K/android_take.py record ~/.cache/bq-key-art/takes/royal.mp4 55 1 120
python3 $K/android_take.py restore; scripts/emu.sh release "$S"
```

A 0.25× take of 120 s holds 29 s of recitation (the intro); full-length
mockups use 0.5× (`record … 142`, screenrecord's limit is 180 s).

## The edit

```bash
tools/key_art/reel/scripts/prepare.sh       # deps, 2 local fixes, fonts, audio
python3 tools/key_art/conform_take.py TAKE.mp4 REF.png tools/key_art/reel/public/clips/royal.mp4 \
  --speed 0.25 --dur 29.5                   # also paper, night, macro (large text)
cd tools/key_art/reel
node render.mjs ./src/project.ts reel.mp4
node render.mjs ./src/project-mockup.ts mockup-royal.mp4 '{"theme":"royal","clip":"/clips/m-royal.mp4","dur":68.9}'
```

Notes:

- Keep takes and frames in `~/.cache/bq-key-art`, never `/tmp`: here it is
  a shared 16 GB tmpfs.
- Host load matters: another agent's Gradle build halved one take's frame
  rate. 0.25× tolerates that; check cadence with
  `ffprobe -show_entries frame=pts_time`.
- Product-page clips are conformed at 30 fps and encoded small (hero CRF 30,
  theme loops 540×1200 CRF 27).
