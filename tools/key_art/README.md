# Key art tools

Pipeline for the *Rain carriage* Reels and the product-page clips. The art
direction, research and rules are in [docs/KEY_ART.md](../../docs/KEY_ART.md).

| File | Role |
|---|---|
| `vclock.js` | Injected before the reader boots: virtual `performance.now`, timers, `requestAnimationFrame`, Web Animations and media playback. |
| `capture.mjs` | Drives the built web reader in headless Chrome, one PNG per 1/30 s, and logs every audio segment. |
| `mix_audio.py` | Rebuilds the recitation at the logged times over synthesised rain, rumble and rail joints. |
| `train_window.html` | The scene: WebGL world and glass, 2D phone and hand around the captured frame. |
| `render_scene.mjs` | Steps the scene over a capture and encodes it with ffmpeg. |
| `make_reel.py` | One Reel from one capture: cues from the log, mix, render, cover still. |

```bash
# 0. once: playwright for node (any recent version; uses system Chrome)
npm i --prefix /tmp/bq-key-art playwright
ln -s /tmp/bq-key-art/node_modules tools/key_art/node_modules   # git-ignored

# 1. serve the reader
(cd web && npm run build && npx vite preview --port 4317 --strictPort) &

# 2. capture Ar-Rahman 1-10 (basmalah + 10 segments) in the variant's theme
node tools/key_art/capture.mjs --hash 55:1 --theme dark --out ~/.cache/bq-key-art/rahman-dark \
  --seconds 80 --until-segment 10 --tail 3

# 3. EveryAyah audio for the same segments
mkdir -p /tmp/audio && for a in 001001 0550{01..09} 055010; do
  curl -sfLo /tmp/audio/$a.mp3 https://everyayah.com/data/Alafasy_128kbps/$a.mp3; done

# 4. render
python3 tools/key_art/make_reel.py nightfall_line ~/.cache/bq-key-art/rahman-dark /tmp/audio ~/Videos/key-art
```

Variants and their reader themes: `paper_dawn` ← `light`, `nightfall_line`
← `dark`, `royal_valley` ← `royal_green`.

Notes:

- Keep frames on disk (`~/.cache/bq-key-art`), never in `/tmp`: it is a
  shared 16 GB tmpfs here, and a Paper capture alone is about 4 GB of PNGs
  (the paper grain barely compresses).
- The first capture of a profile downloads the Quran data (about two
  minutes). Later runs reuse the persistent profile.
- Paper (`light`) captures render about 5× slower than the dark themes in
  headless Chrome; they are still frame-exact.
- Capture frames are 1082 × 2402 (412 × 915 CSS px at 2.625 DPR); the
  website clips crop them to 1080 × 2400.
