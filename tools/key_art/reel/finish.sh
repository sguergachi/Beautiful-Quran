#!/bin/bash
# finish.sh MASTER.mov AUDIO.wav OUT.mp4 - delivery encode (Instagram-safe H.264 High, 60 fps)
set -euo pipefail
ffmpeg -v error -y -i "$1" -i "$2" -map 0:v -map 1:a -c:v libx264 -preset slow -crf 15 -profile:v high \
  -pix_fmt yuv420p -r 60 -maxrate 25M -bufsize 50M -c:a aac -b:a 256k -ar 48000 -shortest -movflags +faststart "$3"
ffprobe -v error -show_entries format=duration,bit_rate -of csv=p=0 "$3"
