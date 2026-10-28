"""Save unamplified, aligned material comparisons from the production shader.

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
    renderer.effects['coverage_only'] = skia.RuntimeEffect.MakeForShader(
        ink.SOURCE.replace('mix(0.5, 1.0, fibre.g)', '1.0'))
    paper = np.array([250, 243, 232], dtype=float)
    variants = {'current': ('aa', 0.), 'coupled': ('aa', 1.),
                'coverage_only': ('coverage_only', 1.), 'smooth': ('plain', 0.)}
    phases = [-1., 0., .12, .3, .5, .7, .88, 1.]
    assets, records = {}, []
    for name, text, font, size, rtl, letters in (
            ('Arabic', 'أُمِرُوٓاْ', ink.geometry.ARABIC_FONT, 90, True, 5),
            ('Marks', 'ٱلرَّحۡمَٰنِ', ink.geometry.ARABIC_FONT, 80, True, 6),
            ('English', 'Merciful', ink.geometry.ENGLISH_FONT, 64, False, 8)):
        word = ink.word(text, font, size, rtl, letters)
        # Include the app's optical correction at density 3, fontScale 1.
        weight = .28 * 3.5 * word['unit'] * min(size / 3 / 48, 1.) ** .25
        assets[name] = {}
        for variant, (effect, fibre) in variants.items():
            frames = []
            for index, phase in enumerate(phases):
                rgba = renderer.render(word, effect, weight=weight, fibreFringe=fibre,
                                       progress=phase, retained=0., leadEntry=float(not rtl))
                rgb = np.rint(rgba[:, :, :3] + paper * (1 - rgba[:, :, 3:4] / 255))
                image = Image.fromarray(rgb.clip(0, 255).astype('uint8'))
                image.save(args.output / f'{name.lower()}-{variant}-{index}.png')
                buffer = io.BytesIO()
                image.save(buffer, format='PNG')
                frames.append('data:image/png;base64,' + base64.b64encode(buffer.getvalue()).decode())
            assets[name][variant] = frames
            alpha = rgba[:, :, 3].astype(float)
            records.append(dict(name=name, text=text, font_px=size, variant=variant,
                                weight_px=weight, integrated_alpha=float(alpha.sum() / 255)))
    payload = json.dumps(assets, separators=(',', ':'))
    html = '''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:transparent;color:var(--foreground);font:14px/1.5 var(--font-sans,system-ui)}p{margin:0 0 10px}.controls{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}button{font:inherit;padding:7px 10px;border:0;border-radius:var(--radius);background:var(--secondary);color:var(--foreground);cursor:pointer}button[aria-pressed=true]{background:var(--accent);color:var(--accent-foreground)}.paper{padding:16px;background:#faf3e8;border-radius:var(--radius);color:#34322e;margin:10px 0;overflow:auto}.row{display:flex;align-items:center;gap:20px;min-height:165px}.row img{display:block;flex:none}.name{width:75px;flex:none;font-size:12px}.muted{color:var(--muted-foreground);font-size:12px}input{vertical-align:middle}</style></head><body>
<p><strong>Fibre fringe · experimental Ink Lab comparison</strong></p>
<p>Switch the aligned paints. The coupled version varies the added wet margin and pigment body together; the original glyph remains intact.</p>
<div class="controls"><button data-v="current">Current effect</button><button data-v="coupled" aria-pressed="true">Fibre fringe</button><button data-v="coverage_only">Fringe without density</button><button data-v="smooth">Smooth control</button></div>
<p><label>Fill <input id="phase" type="range" min="0" max="7" value="7"> <span id="value">1.00</span></label></p>
<div class="paper" id="paper"></div>
<p class="muted">Unamplified software renders of the production shader, pooling 0.28, reflection 0.5. Arabic 90/80px; English 64px. Optical correction: density 3, fontScale 1. Images retain their intrinsic pixel size; physical reading size depends on your display and zoom. Native appearance, GPU cost and preference remain unverified. The lab switch is off by default. Study activity uses its existing reference material.</p>
<script>const assets=ASSETS,phases=PHASES;let variant='coupled';const paper=document.querySelector('#paper');for(const name of Object.keys(assets)){const row=document.createElement('div');row.className='row';const label=document.createElement('span');label.className='name';label.textContent=name;const img=document.createElement('img');img.id=name;img.alt=name+' ink comparison';row.append(label,img);paper.append(row)}function update(){const p=+document.querySelector('#phase').value;for(const name of Object.keys(assets))document.getElementById(name).src=assets[name][variant][p];document.querySelector('#value').textContent=phases[p].toFixed(2);document.querySelectorAll('[data-v]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.v===variant)))}document.querySelectorAll('[data-v]').forEach(b=>b.onclick=()=>{variant=b.dataset.v;update()});document.querySelector('#phase').oninput=update;update();</script></body></html>'''
    (args.output / 'comparison.html').write_text(html.replace('ASSETS', payload).replace('PHASES', json.dumps(phases)))
    metadata = dict(shader_sha256=hashlib.sha256(ink.SOURCE.encode()).hexdigest(),
                    substrate_sha256=hashlib.sha256((ink.ROOT / 'app/src/main/java/com/beautifulquran/ui/theme/paperink/PaperInkSubstrate.kt').read_bytes()).hexdigest(),
                    native=False, phases=phases, records=records)
    (args.output / 'metrics.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(args.output / 'comparison.html')


if __name__ == '__main__':
    main()
