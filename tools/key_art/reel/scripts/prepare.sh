#!/bin/bash
# Prepare the Revideo edit project: deps, two local fixes, brand assets, audio.
# Conformed clips (conform_take.py) go in reel/public/clips/ — see ../README.md.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(git rev-parse --show-toplevel)
npm install --no-audit --no-fund
# npm here blocks install scripts: make the bundled ffmpeg/ffprobe executable
chmod u+x node_modules/@ffmpeg-installer/linux-x64/ffmpeg node_modules/@ffprobe-installer/linux-x64/ffprobe
# Revideo 0.11 forces --single-process, which current Chrome refuses to launch with
sed -i "s/    if (!args.includes('--single-process')) {/    if (false \&\& !args.includes('--single-process')) {/" \
  node_modules/@revideo/renderer/lib/server/render-video.js
mkdir -p public/fonts public/audio public/clips
cp "$REPO"/web/public/fonts/{cormorant_garamond_italic.ttf,cormorant_garamond_semibold.ttf,junicode_variable.woff2} public/fonts/
cp "$REPO"/docs/play_store_icon.png public/icon.png
# The recitation, as the app plays it: EveryAyah files back to back, 0.6 s lead
A=$(mktemp -d)
for f in 001001 055001 055002 055003 055004 055005 055006 055007 055008 055009 055010; do
  curl -sfL -o "$A/$f.mp3" "https://everyayah.com/data/Alafasy_128kbps/$f.mp3"
done
cat_files() { local IFS='|'; echo "concat:$*"; }
ffmpeg -v error -y -i "$(cat_files $A/001001.mp3 $A/055001.mp3 $A/055002.mp3 $A/055003.mp3 $A/055004.mp3)" \
  -af "adelay=3000|3000,apad=whole_dur=31.5,afade=t=out:st=29.9:d=1.6,loudnorm=I=-16:TP=-1.5:LRA=11" \
  -ar 48000 -ac 2 -t 31.5 public/audio/reel.wav
ffmpeg -v error -y -i "$(cat_files $A/001001.mp3 $A/0550{01..09}.mp3 $A/055010.mp3)" \
  -af "adelay=600|600,apad=whole_dur=68.9,afade=t=out:st=67.6:d=1.3,loudnorm=I=-16:TP=-1.5:LRA=11" \
  -ar 48000 -ac 2 -t 68.9 public/audio/mockup.wav
rm -rf "$A"
echo "ready: put conformed clips in public/clips/, then node render.mjs ./src/project.ts reel.mp4"
