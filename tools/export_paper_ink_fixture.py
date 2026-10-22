"""Export a parity fixture for the Kotlin paper ink field generator.

The Python study builder is the reference implementation. This rasterizes one
real Hafs word at a small size with 2x supersampling (the app's setting), runs
the reference swelling channels and liquid surface, and writes inputs and
outputs to app/src/test/resources/paper-ink/. `PaperInkFieldTest` checks the
Kotlin port against it. Run: python3 tools/export_paper_ink_fixture.py
"""
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt

import build_paper_ink_study as B

OUT = B.ROOT / 'app/src/test/resources/paper-ink'
SS = 2
FONT_PX = 40


def export(name, text):
    B.SS = SS
    font = ImageFont.truetype(str(B.ARABIC_FONT), FONT_PX)
    box = font.getbbox(text, direction='rtl')
    w, h = box[2] - box[0] + 8, box[3] - box[1] + 8
    image = Image.new('L', (w * SS, h * SS))
    ImageDraw.Draw(image).text(((4 - box[0]) * SS, (4 - box[1]) * SS), text,
                               font=ImageFont.truetype(str(B.ARABIC_FONT), FONT_PX * SS), fill=255, direction='rtl')
    alpha = np.array(image) / 255
    ink = alpha > .5
    distance = (distance_transform_edt(ink) - distance_transform_edt(~ink)) / SS
    distance -= np.sign(distance) / (2 * SS)
    edge = (alpha > 0) & (alpha < 1)
    distance[edge] = (alpha[edge] - .5) / SS
    # Evenly spaced letter centres keep the fixture independent of HarfBuzz.
    xs = np.nonzero(ink)[1]
    letters = 4
    centers = list(np.linspace(xs.max(), xs.min(), letters + 2)[1:-1])
    baseline = (4 - box[1] + font.getmetrics()[0]) * SS
    unit = FONT_PX / 160
    duration = 2.8 / (letters + 1.8)
    waves = [B.swelling_channels(ink, distance, centers, True, flowing, unit, baseline) for flowing in (False, True)]
    fields = [.12 * duration + (1 - .24 * duration) * wave for wave in waves]
    interior = np.maximum(0, distance)
    film = interior / (interior + 3 * unit) * 3 * unit
    gy, gx = np.gradient(B.gaussian_filter(film, SS * 2 * unit))
    nx, ny = -gx * SS, -gy * SS
    length = np.sqrt(nx * nx + ny * ny + 1)
    meta = dict(width=int(alpha.shape[1]), height=int(alpha.shape[0]), scale=SS, fontPx=FONT_PX,
                centers=[float(c) for c in centers], baseline=float(baseline), rtl=True, letterDuration=duration)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f'{name}.json').write_text(json.dumps(meta, indent=1) + '\n')
    arrays = [alpha, fields[0], fields[1], distance, nx / length, ny / length]
    with open(OUT / f'{name}.bin', 'wb') as f:
        for array in arrays:
            f.write(struct.pack(f'<{array.size}f', *array.astype(np.float32).ravel()))
    print(name, alpha.shape)


if __name__ == '__main__':
    export('allah', 'ٱللَّهِ')
    export('alrahman', 'ٱلرَّحۡمَٰنِ')
