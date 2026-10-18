"""Build contour and smooth liquid-film maps for the three-verse ink-wash study.

Requires numpy, scipy, uharfbuzz and Pillow with RAQM. Assets are committed;
nothing is shaped or rasterized per frame. No pen routes are inferred.
"""
import json
import sqlite3
from pathlib import Path

import numpy as np
import uharfbuzz as hb
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, find_objects, gaussian_filter, label
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import dijkstra

ROOT = Path(__file__).resolve().parents[1]
SS = 4
ASSETS = ROOT / 'app/src/main/assets/paper-ink-study'
FONT = ROOT / 'app/src/main/res/font/uthmanic_hafs_v22.ttf'


def wicking_time(ink, depth):
    """Bake travel from a deep ink pool along the connected stroke, favouring wider passages."""
    count = int(ink.sum())
    if count == 1:
        return np.zeros(1)
    ids = np.full(ink.shape, -1)
    ids[ink] = np.arange(count)
    speed = np.sqrt(np.maximum(0, depth[ink]) + .25)
    sources, targets, costs = [], [], []
    h, w = ink.shape
    for dy, dx in ((0, 1), (1, 0), (1, 1), (1, -1)):
        a = ids[:h - dy, max(0, -dx):w - max(0, dx)]
        b = ids[dy:, max(0, dx):w - max(0, -dx)]
        valid = (a >= 0) & (b >= 0)
        sources.extend(a[valid])
        targets.extend(b[valid])
        costs.extend(np.hypot(dx, dy) * 2 / (speed[a[valid]] + speed[b[valid]]))
    graph = coo_matrix((costs, (sources, targets)), shape=(count, count)).tocsr()
    travel = dijkstra(graph, directed=False, indices=int(np.argmax(depth[ink])))
    assert np.isfinite(travel).all()
    return travel / max(1, travel.max())


def filling_field(text, ink, distance, box, ascent):
    """Schedule shaped letter clusters, then wick from their interiors to the contour."""
    font = hb.Font(hb.Face(FONT.read_bytes()))
    font.scale = (160 * SS * 64, 160 * SS * 64)
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf)
    yy, xx = np.indices(ink.shape)
    owner = np.zeros(ink.shape, dtype=int)
    closest = np.full(ink.shape, np.inf)
    advances = {}
    cursor = 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        end = cursor + pos.x_advance / 64
        first, last = advances.get(info.cluster, (cursor, end))
        advances[info.cluster] = min(first, cursor), max(last, end)
        ext = font.get_glyph_extents(info.codepoint)
        if ext is not None and ext.width and ext.height:
            x = (8 - box[0]) * SS + cursor + (pos.x_offset + ext.x_bearing) / 64
            y = (8 - box[1] + ascent) * SS - (pos.y_offset + ext.y_bearing) / 64
            right, bottom = x + ext.width / 64, y - ext.height / 64
            # The printed font remains authoritative; boxes only associate ink with its cluster.
            dx = np.maximum(0, np.maximum(x - xx, xx - right))
            dy = np.maximum(0, np.maximum(y - yy, yy - bottom))
            score = dx * dx + dy * dy + .02 * ((xx - (x + right) / 2) ** 2 + (yy - (y + bottom) / 2) ** 2)
            nearer = score < closest
            owner[nearer] = info.cluster
            closest[nearer] = score[nearer]
        cursor = end
    inside_out = np.zeros(ink.shape)
    for cluster, (first, last) in advances.items():
        phase = 1 - (first + last) / (2 * cursor)
        pieces, _ = label(ink & (owner == cluster), np.ones((3, 3)))
        for i, region in enumerate(find_objects(pieces), 1):
            if region is None:
                continue
            pixels = pieces[region] == i
            depth = np.clip(distance[region] / max(.25, distance[region][pixels].max()), 0, 1)
            travel = wicking_time(pixels, distance[region])
            growth = .6 * (1 - depth[pixels] ** .65) + .4 * travel ** .55
            inside_out[region][pixels] = .62 * phase + .38 * growth
    nearest = tuple(distance_transform_edt(~ink, return_distances=False, return_indices=True))
    # Extrapolate into wet spread and soften joins without blurring the printed contour.
    return gaussian_filter(inside_out[nearest], SS * .65)


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
    gy, gx = np.gradient(gaussian_filter(film * 3, SS * 2))
    nx, ny = -gx * SS, -gy * SS
    length = np.sqrt(nx * nx + ny * ny + 1)
    normal = np.rint(np.stack([128 + nx / length * 127, 128 + ny / length * 127,
                              film * 255, np.full(alpha.shape, 255)], axis=-1)).astype('uint8')
    # Opaque data avoids premultiplication. B carries signed contour distance.
    field = np.zeros((*alpha.shape, 4), dtype='uint8')
    bloom = filling_field(text, ink, distance, box, reference.getmetrics()[0])
    field[:, :, 1] = np.round(bloom * 255)
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
