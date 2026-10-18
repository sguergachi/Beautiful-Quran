"""Build contour and smooth liquid-film maps for the three-verse ink-wash study.

Requires numpy, scipy and Pillow with RAQM. Assets are committed; nothing is
rasterized per frame. The field carries inside-out and bottom-up filling coordinates, with no stroke routes.
"""
import json
import sqlite3
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, gaussian_filter

ROOT = Path(__file__).resolve().parents[1]
SS = 4
ASSETS = ROOT / 'app/src/main/assets/paper-ink-study'
FONT = ROOT / 'app/src/main/res/font/uthmanic_hafs_v22.ttf'


def build(ayah, position, text):
    reference = ImageFont.truetype(str(FONT), 160)
    box = reference.getbbox(text, direction='rtl')
    w, h = box[2] - box[0] + 16, box[3] - box[1] + 16
    image = Image.new('L', (w * SS, h * SS))
    font = ImageFont.truetype(str(FONT), 160 * SS)
    ImageDraw.Draw(image).text(((8 - box[0]) * SS, (8 - box[1]) * SS), text,
                              font=font, fill=255, direction='rtl')
    alpha = np.array(image) / 255
    ink = alpha > .5
    distance = (distance_transform_edt(ink) - distance_transform_edt(~ink)) / SS
    distance -= np.sign(distance) / (2 * SS)
    edge = (alpha > 0) & (alpha < 1)
    distance[edge] = (alpha[edge] - .5) / SS
    # A rounded meniscus continues across broad strokes instead of flattening at the edge.
    interior = np.maximum(0, distance)
    film = interior / (interior + 3)
    gy, gx = np.gradient(gaussian_filter(film * 3, SS))
    nx, ny = -gx * SS, -gy * SS
    length = np.sqrt(nx * nx + ny * ny + 1)
    normal = np.rint(np.stack([128 + nx / length * 127, 128 + ny / length * 127,
                              film * 255, np.full(alpha.shape, 255)], axis=-1)).astype('uint8')
    # Opaque data avoids premultiplication. B carries signed contour distance.
    field = np.zeros((*alpha.shape, 4), dtype='uint8')
    yy = np.arange(h * SS)[:, None]
    rows = np.where(ink.any(axis=1))[0]
    rise = np.clip((rows[-1] - yy) / max(1, rows[-1] - rows[0]), 0, 1)
    depth = np.clip(distance / max(1, distance.max()), 0, 1)
    field[:, :, 0] = np.round(rise * 255)
    field[:, :, 1] = np.round((1 - depth) * 255)
    field[:, :, 2] = np.clip(128 + distance * 16, 0, 255)
    field[:, :, 3] = 255
    key = f'{ayah}-{position}'
    Image.fromarray(field).save(ASSETS / f'word-{key}-field.png', optimize=True)
    Image.fromarray(normal).resize((w, h), Image.Resampling.LANCZOS).save(
        ASSETS / f'word-{key}-normal.png', optimize=True)
    return dict(ayah=ayah, position=position, arabic=text, width=w, height=h,
                baseline=reference.getmetrics()[0] + 8 - box[1], maxDepth=float(distance.max()))


if __name__ == '__main__':
    ASSETS.mkdir(parents=True, exist_ok=True)
    for pattern in ('word-*-field.png', 'word-*-normal.png'):
        for old in ASSETS.glob(pattern):
            old.unlink()
    with sqlite3.connect(ROOT / 'data/quran.db') as db:
        words = db.execute('select ayah_number,position,arabic from words '
                           'where surah_id=1 and ayah_number<=3 '
                           'order by ayah_number,position').fetchall()
    manifest = [build(*word) for word in words]
    (ASSETS / 'words.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(manifest, ensure_ascii=False))
