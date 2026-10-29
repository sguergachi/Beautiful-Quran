"""Save unamplified, aligned fibre-fringe comparisons from the production shader.

Run with the Skia test environment:
  python tools/compare_paper_ink_fringe.py /path/to/artifacts
Requires the same dependencies as test_paper_ink_antialiasing.py and kotlinc.
These are software paints, not Android captures or perceptual certification.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image
import skia

import test_paper_ink_antialiasing as ink


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    ink.PaperInkAntialiasingTest.setUpClass()
    renderer = ink.PaperInkAntialiasingTest()
    renderer.effects['coverage_only'] = skia.RuntimeEffect.MakeForShader(ink.SOURCE.replace(ink.DENSITY_CALL, '1.0'))
    paper = np.array([250, 243, 232], dtype=float)
    variants = {'fringe': ('aa', 1.), 'tooth': ('aa', 0.),
                'coverage_only': ('coverage_only', 1.), 'smooth': ('plain', 0.)}
    # Fill positions while wet, then the dry-down of the finished word.
    frames_at = [dict(progress=p, wet=1.) for p in (-1., 0., .12, .3, .5, .7, .88, 1.)] + \
                [dict(progress=1., wet=w) for w in (.8, .6, .45, .3, .15, 0.)]
    labels = [f'fill {f["progress"]:.2f}' if f['wet'] == 1. else f'drying {1 - f["wet"]:.2f}' for f in frames_at]
    assets, records = {}, []

    def encode(rgba):
        rgb = np.rint(rgba[:, :, :3] + paper * (1 - rgba[:, :, 3:4] / 255))
        image = Image.fromarray(rgb.clip(0, 255).astype('uint8'))
        buffer = io.BytesIO()
        image.save(buffer, format='PNG')
        return image, 'data:image/png;base64,' + base64.b64encode(buffer.getvalue()).decode()

    for name, text, font, size, rtl, letters, offset in (
            ('Arabic', 'أُمِرُوٓاْ', ink.geometry.ARABIC_FONT, 90, True, 5, (0., 0.)),
            ('Marks', 'ٱلرَّحۡمَٰنِ', ink.geometry.ARABIC_FONT, 80, True, 6, (0., 0.)),
            ('English', 'Merciful', ink.geometry.ENGLISH_FONT, 64, False, 8, (0., 0.)),
            ('Again', 'Merciful', ink.geometry.ENGLISH_FONT, 64, False, 8, (313., 97.)),
            ('Small', 'Merciful', ink.geometry.ENGLISH_FONT, 24, False, 8, (0., 0.))):
        word = ink.word(text, font, size, rtl, letters)
        # Include the app's optical correction at density 3, fontScale 1.
        weight = .28 * 3.5 * word['unit'] * min(size / 3 / 48, 1.) ** .25
        assets[name] = {}
        for variant, (effect, fibre) in variants.items():
            frames = []
            for index, at in enumerate(frames_at):
                rgba = renderer.render(word, effect, weight=weight, fibreFringe=fibre, retained=0.,
                                       leadEntry=float(not rtl), fibreOffset=list(offset), **at)
                image, uri = encode(rgba)
                image.save(args.output / f'{name.lower()}-{variant}-{index}.png')
                frames.append(uri)
            assets[name][variant] = frames
            alpha = rgba[:, :, 3].astype(float)
            records.append(dict(name=name, text=text, font_px=size, variant=variant, fibre_offset=offset,
                                weight_px=weight, integrated_alpha=float(alpha.sum() / 255)))
    payload = json.dumps(assets, separators=(',', ':'))
    html = '''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:transparent;color:var(--foreground);font:14px/1.5 var(--font-sans,system-ui)}p{margin:0 0 10px}.controls{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}button{font:inherit;padding:7px 10px;border:0;border-radius:var(--radius);background:var(--secondary);color:var(--foreground);cursor:pointer}button[aria-pressed=true]{background:var(--accent);color:var(--accent-foreground)}.paper{padding:16px;background:#faf3e8;border-radius:var(--radius);color:#34322e;margin:10px 0;overflow:auto}.row{display:flex;align-items:center;gap:20px;min-height:90px}.row img{display:block;flex:none}.name{width:75px;flex:none;font-size:12px}.muted{color:var(--muted-foreground);font-size:12px}input{vertical-align:middle;width:min(420px,70vw)}</style></head><body>
<p><strong>Fibre fringe</strong></p>
<p>Drag through the fill, then the dry-down. "Again" is the same word as "English" at another place on the page: it reads different paper.</p>
<div class="controls"><button data-v="fringe" aria-pressed="true">Fibre fringe</button><button data-v="tooth">Plain paper tooth</button><button data-v="coverage_only">Fringe without density</button><button data-v="smooth">Smooth control</button></div>
<p><label><input id="phase" type="range" min="0" max="MAX" value="7"> <span id="value"></span></label></p>
<div class="paper" id="paper"></div>
<p class="muted">Unamplified software renders of the production shader and paper tile, pooling 0.28, reflection 0.5. Arabic 90/80px; English 64 and 24px. Optical correction: density 3, fontScale 1. Images keep their intrinsic pixel size; physical reading size depends on your display and zoom. These are not Android captures.</p>
<script>const assets=ASSETS,labels=LABELS;let variant='fringe';const paper=document.querySelector('#paper');for(const name of Object.keys(assets)){const row=document.createElement('div');row.className='row';const label=document.createElement('span');label.className='name';label.textContent=name;const img=document.createElement('img');img.id=name;img.alt=name+' ink comparison';row.append(label,img);paper.append(row)}function update(){const p=+document.querySelector('#phase').value;for(const name of Object.keys(assets))document.getElementById(name).src=assets[name][variant][p];document.querySelector('#value').textContent=labels[p];document.querySelectorAll('[data-v]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.v===variant)))}document.querySelectorAll('[data-v]').forEach(b=>b.onclick=()=>{variant=b.dataset.v;update()});document.querySelector('#phase').oninput=update;update();</script></body></html>'''
    (args.output / 'comparison.html').write_text(html.replace('ASSETS', payload).replace('LABELS', json.dumps(labels))
                                               .replace('MAX', str(len(frames_at) - 1)))
    metadata = dict(shader_sha256=hashlib.sha256(ink.SOURCE.encode()).hexdigest(),
                    substrate_sha256=hashlib.sha256((ink.ROOT / 'app/src/main/java/com/beautifulquran/ui/theme/paperink/PaperInkSubstrate.kt').read_bytes()).hexdigest(),
                    native=False, frames=frames_at, records=records)
    (args.output / 'metrics.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(args.output / 'comparison.html')


if __name__ == '__main__':
    main()
