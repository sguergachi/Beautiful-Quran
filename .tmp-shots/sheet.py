#!/usr/bin/env python3
"""Contact sheet for generated medallions, from the Kotlin dump."""
import json
import sys

from PIL import Image, ImageDraw

src, out = sys.argv[1], sys.argv[2]
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 8
data = json.load(open(src))

CELL = 260
SS = 2  # supersample
rows = (len(data) + cols - 1) // cols
sheet = Image.new("RGB", (cols * CELL, rows * CELL), (12, 48, 39))

GOLD = (208, 172, 96)
for idx, m in enumerate(data):
    tile = Image.new("RGB", (CELL * SS, CELL * SS), (12, 48, 39))
    d = ImageDraw.Draw(tile)
    S = CELL * SS * 0.92
    off = CELL * SS * 0.04

    def xy(p):
        return (off + p[0] * S, off + p[1] * S)

    for s in m["strokes"]:
        pts = [xy(p) for p in s["pts"]]
        if s["closed"]:
            pts = pts + [pts[0]]
        d.line(pts, fill=GOLD, width=(3 if s["rule"] else 2) * SS, joint="curve")
    for dot in m["dots"]:
        cx, cy = xy(dot)
        r = dot[2] * S
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GOLD)
    tile = tile.resize((CELL, CELL), Image.LANCZOS)
    ImageDraw.Draw(tile).text((6, 4), f'{m["seed"]}/{m["fold"]}', fill=(230, 230, 230))
    sheet.paste(tile, ((idx % cols) * CELL, (idx // cols) * CELL))

sheet.save(out)
print(out, sheet.size)
