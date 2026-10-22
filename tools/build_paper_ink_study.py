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
from scipy.ndimage import distance_transform_edt, gaussian_filter, gaussian_filter1d

ROOT = Path(__file__).resolve().parents[1]
SS = 4
ASSETS = ROOT / 'app/src/main/assets/paper-ink-study'
# Slot-map smoothing, in mean letter widths.
LETTER_SMOOTHING = .45
# Front shape in display pixels at the Arabic size: (meniscus lead, fibre amplitude).
INSIDE_OUT_FRONT = (6, 1)
FLOWING_FRONT = (3, 0)
ARABIC_FONT = ROOT / 'app/src/main/res/font/uthmanic_hafs_v22.ttf'
ENGLISH_FONT = ROOT / 'app/src/main/res/font/junicode_variable.ttf'


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


def baseline_wave(ink, depth, centers, rtl, flowing=False, unit=1.):
    """Sweep one liquid front along the baseline in reading order.

    Arrival depends on horizontal position, so tall letters, loops and marks
    fill as the front passes beneath them; it never rises up a stroke or runs
    diagonally. Three refinements make the front read as ink, not a wipe:

    * Letter pacing: each letter (centers, in reading order) gets an equal
      time slot, the slots TajweedPacing already assumes. Long flourishes are
      crossed briskly and dense clusters slowly; a Gaussian over the slot map
      keeps speed changes smooth and monotone.
    * Meniscus: a stroke's core leads its rims by a fixed few pixels, so the
      front is a rounded tongue down each stroke rather than a ruler edge.
    * Paper fibre (Inside out only): static, horizontally stretched fibre
      noise breaks the leading edge up by a pixel or two, like wicking.
    Pixel offsets are converted to phase through the local sweep speed, so the
    front's shape is the same on wide and narrow letters.
    """
    x = np.nonzero(ink)[1]
    left, right = x.min(), x.max()
    start, end = (right, left) if rtl else (left, right)
    columns = np.arange(ink.shape[1], dtype=float)
    count = len(centers)
    knots = np.array([start, *centers, end], dtype=float)
    slots = np.array([0, *((np.arange(count) + .5) / count), 1])
    order = np.argsort(knots, kind='stable')
    sweep = gaussian_filter1d(np.interp(columns, knots[order], slots[order]),
                              LETTER_SMOOTHING * (right - left) / count, mode='nearest')
    sweep = (sweep - sweep[start]) / (sweep[end] - sweep[start])
    speed = np.abs(np.gradient(sweep))
    arrival = np.broadcast_to(sweep, ink.shape).copy()
    interior = np.maximum(0, depth)
    core = interior / (interior + unit)
    meniscus, fibre = FLOWING_FRONT if flowing else INSIDE_OUT_FRONT
    arrival += meniscus * SS * unit * (1 - core) * speed
    if fibre:
        noise = gaussian_filter(np.random.default_rng(0).standard_normal(ink.shape),
                                (.6 * SS * unit, 5 * SS * unit))
        arrival += fibre * SS * unit * noise / noise.std() * speed
    low, high = arrival[ink].min(), arrival[ink].max()
    return np.clip((arrival - low) / (high - low), 0, 1)


def filling_field(text, ink, distance, box, ascent, font_path, font_size, flowing=True):
    """Shape letter clusters for pacing, then sweep the ink along the baseline."""
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
    # Letter count keeps the existing feather duration and full word window.
    duration = 2.8 / (sum(map(len, anchors.values())) + 1.8)
    rtl = buf.direction == 'rtl'
    centers = sorted((x for group in anchors.values() for _, x in group), reverse=rtl)
    wave = baseline_wave(ink, distance, centers, rtl, flowing, font_size / 160)
    # Defined on paper too, so wet spread beyond the contour shares its column's arrival.
    return .12 * duration + (1 - .24 * duration) * wave, duration


def liquid_surface(distance, font_size):
    """Keep film depth and curvature equivalent across the two font raster sizes."""
    unit = font_size / 160
    interior = np.maximum(0, distance)
    film = interior / (interior + 3 * unit)
    gy, gx = np.gradient(gaussian_filter(film * 3 * unit, SS * 2 * unit))
    nx, ny = -gx * SS, -gy * SS
    length = np.sqrt(nx * nx + ny * ny + 1)
    return np.rint(np.stack([128 + nx / length * 127, 128 + ny / length * 127,
                            film * 255, np.full(distance.shape, 255)], axis=-1)).astype('uint8')


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
    normal = liquid_surface(distance, font_size)
    # Opaque data avoids premultiplication. B carries signed contour distance.
    field = np.zeros((*alpha.shape, 4), dtype='uint8')
    bloom, duration = filling_field(text, ink, distance, box, reference.getmetrics()[0], font_path, font_size)
    interior, _ = filling_field(text, ink, distance, box, reference.getmetrics()[0], font_path, font_size,
                               flowing=False)
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
