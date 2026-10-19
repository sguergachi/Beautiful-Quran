"""Build contour and smooth liquid-film maps for the three-verse ink-wash study.

Requires numpy, scipy, uharfbuzz and Pillow with RAQM. Assets are committed;
nothing is shaped or rasterized per frame. No pen routes are inferred.
"""
import json
import re
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
ARABIC_FONT = ROOT / 'app/src/main/res/font/uthmanic_hafs_v22.ttf'
ENGLISH_FONT = ROOT / 'app/src/main/res/font/junicode_variable.ttf'


def ink_graph(ink, depth):
    """Connected stroke paths favour wide passages; paper is never a shortcut."""
    count = int(ink.sum())
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
    return coo_matrix((costs, (sources, targets)), shape=(count, count)).tocsr()


def wicking_basins(ink, depth, anchors):
    """Grow letter interiors along ink paths from separate deep pools, without an x sweep."""
    x = np.nonzero(ink)[1]
    nearest = np.argmin(np.abs(x[:, None] - np.array([a[1] for a in anchors])), axis=1)
    seeds, letters = [], []
    for index in np.unique(nearest):
        candidates = np.flatnonzero(nearest == index)
        seed = candidates[np.argmax(depth[ink][candidates])]
        seeds.append(seed)
        letters.append(anchors[index][0])
    travel, _, sources = dijkstra(ink_graph(ink, depth), directed=False, indices=seeds,
                                  min_only=True, return_predecessors=True)
    assert np.isfinite(travel).all()
    lookup = np.full(int(ink.sum()), -1)
    lookup[seeds] = letters
    owners = lookup[sources]
    assert (owners >= 0).all()
    for letter in letters:
        pixels = owners == letter
        travel[pixels] /= max(1, travel[pixels].max())
    return owners, travel


def reservoir_growth(ink, depth):
    """Merge a few broad deposits along connected ink, then soak out to its edge.

    This is a baked capillary cue, not stroke order or a fluid simulation.
    Wide interiors carry ink faster; a distant reservoir can start before
    the first reaches it, without revealing the whole medial axis at once.
    """
    graph = ink_graph(ink, depth)
    interior = np.maximum(.01, depth[ink])
    core = interior >= interior.max() * .55
    yy, xx = np.nonzero(ink)
    # A fixed, broad loading variation breaks ties on equally deep stems.
    loading = 1 + .12 * np.sin(xx / (SS * 7) + yy / (SS * 11))
    first = int(np.argmax(interior * loading))
    paths = [dijkstra(graph, directed=False, indices=first)]
    assert np.isfinite(paths[0]).all()
    radius = max(1, interior.max() * SS)
    # Small dots stay a single deposit; long/broad letters get up to three.
    extent = paths[0][core].max()
    count = 1 + int(extent > radius * 4) + int(extent > radius * 10)
    for _ in range(1, count):
        nearest = np.min(paths, axis=0)
        seed = int(np.argmax(np.where(core, nearest * loading, -1)))
        paths.append(dijkstra(graph, directed=False, indices=seed))
    scale = max(1, paths[0].max())
    # All pools are supplied early, with small delays within the letter's
    # own bloom. Taking the minimum joins fronts without a boundary seam.
    travel = np.min([path / scale + i * .075 for i, path in enumerate(paths)], axis=0)
    travel /= max(.001, travel.max())
    edge = 1 - (interior / interior.max()) ** .65
    return .72 * travel ** .72 + .28 * edge


def letter_anchors(advances, plain_advances, owner, ink, rtl):
    """Split font ligatures into approximate letter basins; keep their printed outline intact."""
    anchors = {}
    clusters = sorted(advances)
    for index, cluster in enumerate(clusters):
        pixels = ink & (owner == cluster)
        if not pixels.any():
            continue
        next_cluster = clusters[index + 1] if index + 1 < len(clusters) else float('inf')
        letters = sorted((k for k, width in plain_advances.items()
                          if cluster <= k < next_cluster and width > 0), reverse=rtl)
        assert letters, cluster
        x = np.nonzero(pixels)[1]
        left, width = x.min(), max(1, np.ptp(x))
        total = sum(plain_advances[k] for k in letters)
        cursor = 0
        anchors[cluster] = []
        for letter in letters:
            span = plain_advances[letter] / total
            anchors[cluster].append((letter, left + width * (cursor + span / 2)))
            cursor += span
    return anchors


def filling_field(text, ink, distance, box, ascent, font_path, font_size, merging=True):
    """Schedule shaped letter clusters, then wick from their interiors to the contour."""
    font = hb.Font(hb.Face(font_path.read_bytes()))
    font.scale = (font_size * SS * 64, font_size * SS * 64)
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
    # Font substitutions can merge an entire word (including basm and lillah).
    # Shape a second measurement run to recover letter advance shares only;
    # it never supplies the contour or the displayed glyphs.
    plain = hb.Buffer()
    plain.add_str(text)
    plain.guess_segment_properties()
    hb.shape(font, plain, {"calt": False, "liga": False, "rlig": False, "clig": False})
    plain_advances = {}
    for info, pos in zip(plain.glyph_infos, plain.glyph_positions):
        plain_advances[info.cluster] = plain_advances.get(info.cluster, 0) + pos.x_advance
    anchors = letter_anchors(advances, plain_advances, owner, ink, buf.direction == 'rtl')
    ordered = sorted((a for group in anchors.values() for a in group),
                     key=lambda a: -a[1] if buf.direction == 'rtl' else a[1])
    # Stagger letters, overlapping roughly three blooms. Direction belongs to
    # these starts, never to pixels within a letter.
    duration = 2.8 / (len(ordered) + 1.8)
    onsets = {letter: i / (len(ordered) + 1.8) for i, (letter, _) in enumerate(ordered)}
    inside_out = np.zeros(ink.shape)
    filled_letters = set()
    for cluster, pools in anchors.items():
        pieces, _ = label(ink & (owner == cluster), np.ones((3, 3)))
        for i, region in enumerate(find_objects(pieces), 1):
            if region is None:
                continue
            pixels = pieces[region] == i
            x = np.nonzero(pixels)[1] + region[1].start
            local = [(letter, center - region[1].start) for letter, center in pools
                     if x.min() <= center <= x.max()]
            if not local:
                letter, center = min(pools, key=lambda a: abs(a[1] - x.mean()))
                local = [(letter, center - region[1].start)]
            owners, travel = wicking_basins(pixels, distance[region], local)
            filled_letters.update(owners)
            growth = np.empty(int(pixels.sum()))
            for letter in np.unique(owners):
                basin = owners == letter
                if merging:
                    letter_ink = np.zeros(pixels.shape, dtype=bool)
                    letter_ink[pixels] = basin
                    growth[basin] = reservoir_growth(letter_ink, distance[region])
                else:
                    depth = np.clip(distance[region][pixels][basin] /
                                    max(.25, distance[region][pixels][basin].max()), 0, 1)
                    growth[basin] = .7 * (1 - depth ** .65) + .3 * travel[basin] ** .55
            starts = np.array([onsets[letter] for letter in owners])
            inside_out[region][pixels] = starts + duration * (.12 + .76 * growth)
    assert filled_letters == onsets.keys(), (text, filled_letters, onsets.keys())
    nearest = tuple(distance_transform_edt(~ink, return_distances=False, return_indices=True))
    # Extrapolate into wet spread and soften joins without blurring the printed contour.
    return gaussian_filter(inside_out[nearest], SS * .65), duration


def build(ayah, position, text, english=False):
    font_path, font_size, direction = (ENGLISH_FONT, 80, 'ltr') if english else (ARABIC_FONT, 160, 'rtl')
    reference = ImageFont.truetype(str(font_path), font_size)
    box = reference.getbbox(text, direction=direction)
    w, h = box[2] - box[0] + 16, box[3] - box[1] + 16
    image = Image.new('L', (w * SS, h * SS))
    font = ImageFont.truetype(str(font_path), font_size * SS)
    ImageDraw.Draw(image).text(((8 - box[0]) * SS, (8 - box[1]) * SS), text,
                              font=font, fill=255, direction=direction)
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
    bloom, duration = filling_field(text, ink, distance, box, reference.getmetrics()[0], font_path, font_size)
    interior, _ = filling_field(text, ink, distance, box, reference.getmetrics()[0], font_path, font_size,
                               merging=False)
    field[:, :, 0] = np.round(interior * 255)
    field[:, :, 1] = np.round(bloom * 255)
    field[:, :, 2] = np.clip(128 + distance * 16, 0, 255)
    field[:, :, 3] = 255
    key = f'{"en-" if english else ""}{ayah}-{position}'
    Image.fromarray(field).save(ASSETS / f'word-{key}-field.png', optimize=True)
    Image.fromarray(normal).resize((w, h), Image.Resampling.LANCZOS).save(
        ASSETS / f'word-{key}-normal.png', optimize=True)
    return dict(ayah=ayah, position=position, **({'text': text} if english else {'arabic': text}), width=w, height=h,
                baseline=reference.getmetrics()[0] + 8 - box[1], maxDepth=float(distance.max()), letterDuration=duration)


if __name__ == '__main__':
    ASSETS.mkdir(parents=True, exist_ok=True)
    for pattern in ('word-*-field.png', 'word-*-normal.png'):
        for old in ASSETS.glob(pattern):
            old.unlink()
    with sqlite3.connect(ROOT / 'data/quran.db') as db:
        words = db.execute('select ayah_number,position,arabic from words '
                           'where surah_id=1 and ayah_number<=3 '
                           'order by ayah_number,position').fetchall()
        verses = db.execute('select ayah_number,translation_en from ayahs '
                            'where surah_id=1 and ayah_number<=3 order by ayah_number').fetchall()
    manifest = [build(*word) for word in words]
    (ASSETS / 'words.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    english = []
    for ayah, text in verses:
        for position, match in enumerate(re.finditer(r'\S+', text), 1):
            english.append(dict(build(ayah, position, match[0], english=True),
                                start=match.start(), end=match.end()))
    (ASSETS / 'english.json').write_text(json.dumps(english, ensure_ascii=False, indent=2) + '\n')
    print(f'Built {len(manifest)} Arabic and {len(english)} English word maps.')
