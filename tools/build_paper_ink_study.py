"""Build the four authored stroke maps for the native Al-Fatihah 1:1 study.

Requires numpy, scipy and Pillow with RAQM. Assets are committed; no generation
or stroke inference runs inside the Android app.
"""
import json
import sqlite3
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, gaussian_filter
from scipy.interpolate import PchipInterpolator

ROOT = Path(__file__).resolve().parents[1]
SS = 4
ASSETS = ROOT / 'app/src/main/assets/paper-ink-study'
FONT = ROOT / 'app/src/main/res/font/uthmanic_hafs_v22.ttf'


def build(text, strokes, position):
    font_path = FONT
    letter_count = max(row[0] for row in strokes) + 1
    reference = ImageFont.truetype(str(font_path), 160)
    box = reference.getbbox(text, direction='rtl')
    W, H = box[2] - box[0] + 16, box[3] - box[1] + 16
    image = Image.new('L', (W * SS, H * SS))
    font = ImageFont.truetype(str(font_path), 160 * SS)
    ImageDraw.Draw(image).text(((8 - box[0]) * SS, (8 - box[1]) * SS), text, font=font, fill=255, direction='rtl')
    alpha = np.array(image) / 255

    route = []
    for letter in range(letter_count):
        gestures = []
        for owner, controls in strokes:
            if owner != letter:
                continue
            pts = np.array(controls, dtype=float)
            distances = np.r_[0, np.cumsum(np.hypot(*np.diff(pts[:, :2], axis=0).T))]
            steps = np.linspace(0, distances[-1], max(3, int(distances[-1] * 3)))
            samples = PchipInterpolator(distances, pts, axis=0)(steps)
            tangent = np.gradient(samples[:, :2], axis=0)
            heading = np.unwrap(np.arctan2(tangent[:, 1], tangent[:, 0]))
            bend = np.clip(np.abs(np.gradient(heading, steps)) * 8, 0, 1)
            effort = np.r_[0, np.cumsum(np.diff(steps) * (1 + .8 * (bend[1:] + bend[:-1]) / 2))]
            load = (.6 + .22 * bend + .08 * samples[:, 2] / 24 +
                    .12 * np.exp(-steps / 6) + .10 * np.exp(-(steps[-1] - steps) / 4))
            gestures.append((samples, effort, load))
        total = sum(effort[-1] + 7 for samples, effort, load in gestures)
        cursor = 0
        for samples, effort, load in gestures:
            # Lift travel is represented in the route, but it deposits no paint.
            if route:
                route.append([(letter + cursor / total) / letter_count, *samples[0], 0, 0])
            cursor += 7
            for i, (x, y, width) in enumerate(samples):
                t = (letter + (cursor + effort[i]) / total) / letter_count
                route.append([t, x, y, width, 1, load[i]])
            cursor += effort[-1]

    arrival = np.full(alpha.shape, np.inf)
    nearest = np.full(alpha.shape, np.inf)
    closest_time = np.zeros(alpha.shape)
    closest_load = np.full(alpha.shape, .6)
    nib_track = np.zeros(alpha.shape)
    angle = np.deg2rad(70)
    cos, sin = np.cos(angle), np.sin(angle)
    for t, x, y, width, down, load in route:
        if not down:
            continue
        radius = 25
        x0, x1 = max(0, int((x - radius) * SS)), min(W * SS, int((x + radius) * SS) + 1)
        y0, y1 = max(0, int((y - radius) * SS)), min(H * SS, int((y + radius) * SS) + 1)
        yy, xx = np.mgrid[y0:y1, x0:x1] / SS
        dx, dy = xx - x, yy - y
        u, v = dx * cos + dy * sin, -dx * sin + dy * cos
        # A broad cut with a rounded microscopic edge, not a circular paint brush.
        score = np.maximum(np.abs(u) - width / 2, np.abs(v) - 1.6)
        region = np.s_[y0:y1, x0:x1]
        arrival[region] = np.where(score <= 0, np.minimum(arrival[region], t), arrival[region])
        better = score < nearest[region]
        closest_time[region] = np.where(better, t, closest_time[region])
        closest_load[region] = np.where(better, load, closest_load[region])
        nib_track[region] = np.where(better, u, nib_track[region])
        nearest[region] = np.minimum(nearest[region], score)

    # The hand is illustrative, while the font remains authoritative. A contour
    # sliver outside a stamp inherits its nearest gesture, never a vertical band.
    miss = ~np.isfinite(arrival)
    arrival[miss] = closest_time[miss]
    arrival = np.minimum(arrival, .985)
    ink = alpha > .5
    distance = (distance_transform_edt(ink) - distance_transform_edt(~ink)) / SS
    distance -= np.sign(distance) / (2 * SS)
    edge = (alpha > 0) & (alpha < 1)
    distance[edge] = (alpha[edge] - .5) / SS

    # Slower turns and contact/lift deposits leave deeper pools. Fine tracks
    # follow the nib across the authored path, while filtered normals stay smooth.
    deposit = np.clip(closest_load, .35, .98) * (.98 + .02 * np.cos(nib_track * 1.3))
    film = (1 - np.exp(-np.maximum(0, distance) / 1.5)) * deposit
    gy, gx = np.gradient(gaussian_filter(film * .3, 4))
    nx, ny = -gx * SS, -gy * SS
    length = np.sqrt(nx * nx + ny * ny + 1)
    # Opaque maps avoid Android premultiplying data channels. Two arrival bytes
    # preserve the small contact edge without the old 8-bit temporal terraces.
    times = np.round(arrival * 65535).astype('uint16')
    normal = np.stack([128 + nx / length * 127, 128 + ny / length * 127, film * 255, np.full(alpha.shape, 255)], axis=-1).astype('uint8')
    encoded = np.stack([times >> 8, times & 255, np.clip(128 + distance * 16, 0, 255), np.full(alpha.shape, 255)], axis=-1).astype('uint8')
    normal = np.array(Image.fromarray(normal).resize((W, H), Image.Resampling.LANCZOS))
    Image.fromarray(encoded).save(ASSETS / f'word-{position}-field.png', optimize=True)
    Image.fromarray(normal).save(ASSETS / f'word-{position}-normal.png', optimize=True)
    return {'position': position, 'arabic': text, 'width': W, 'height': H, 'arrivalBits': 16,
            'baseline': reference.getmetrics()[0] + 8 - box[1],
            'nibCoverage': round(float((ink & ~miss).sum() / ink.sum()), 4)}


if __name__ == '__main__':
    ASSETS.mkdir(parents=True, exist_ok=True)
    strokes = json.loads((ROOT / 'tools/paper_ink_study/strokes.json').read_text())
    db = sqlite3.connect(ROOT / 'data/quran.db')
    words = db.execute('select position,arabic from words where surah_id=1 and ayah_number=1 order by position').fetchall()
    manifest = [build(text, strokes[str(position)], position) for position, text in words]
    (ASSETS / 'words.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(manifest, ensure_ascii=False))
