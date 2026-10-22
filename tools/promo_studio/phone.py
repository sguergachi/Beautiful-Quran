#!/usr/bin/env python3
"""A Pixel 10 front, drawn procedurally around a screen of any resolution.

    phone.py OUT_DIR [--screen 1080x2400] [--color obsidian]

Writes frame-<color>.png (RGBA: aluminium rim, black glass bezel, punch-hole
camera and side keys; the screen area is transparent) and screen-mask.png
(the display's rounded corners, antialiased). Proportions follow the Pixel 10
(72.0 x 152.8 mm body, 6.3" 20:9 display): the screen sets the scale, so
the body is always drawn at the take's own resolution and the compositor
never upsamples the frame relative to the screen.

The frame is drawn rather than taken from device art so it has no licence
strings attached and so every colour shares one lighting model.
"""
import argparse
import os

import numpy as np
from PIL import Image

# Pixel 10 colours (rim tone, rim highlight). The glass bezel is always black.
COLORS = {
    "obsidian": ((0.17, 0.17, 0.18), (0.62, 0.63, 0.65)),
    "frost": ((0.80, 0.83, 0.86), (0.98, 0.99, 1.00)),
    "indigo": ((0.20, 0.24, 0.38), (0.62, 0.68, 0.86)),
    "lemongrass": ((0.72, 0.76, 0.52), (0.95, 0.97, 0.82)),
    "porcelain": ((0.86, 0.82, 0.76), (1.00, 0.98, 0.94)),
}

BODY_MM = (72.0, 152.8)
SCREEN_W_MM = 65.7          # 6.3" at 20:9
BODY_R_MM = 10.4            # body corner radius
RIM_MM = 1.15               # aluminium band visible from the front
HOLE_MM = 3.0               # punch-hole diameter
HOLE_TOP_MM = 2.55          # punch-hole centre below the screen's top edge
KEY_OUT_MM = 0.55           # how far the side keys stand proud of the rim
KEYS_MM = ((38.0, 49.0), (58.0, 82.0))  # power, volume: (top, bottom) from the body top


def rounded_box_sdf(xx, yy, cx, cy, hw, hh, r):
    """Signed distance to a rounded rectangle (negative inside)."""
    qx = np.abs(xx - cx) - (hw - r)
    qy = np.abs(yy - cy) - (hh - r)
    outside = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0))
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - r


def geometry(sw, sh):
    k = sw / SCREEN_W_MM                      # px per mm
    bw, bh = BODY_MM[0] * k, BODY_MM[1] * k
    # the body must hold the screen; tall takes (e.g. 1080x2424) stretch it
    bh = max(bh, sh + 2 * (bw - sw) / 2 * 1.05)
    margin = int(np.ceil(KEY_OUT_MM * k + 4))
    W, H = int(np.ceil(bw)) + 2 * margin, int(np.ceil(bh)) + 2 * margin
    cx, cy = W / 2, H / 2
    body_r = BODY_R_MM * k
    sx0, sy0 = cx - sw / 2, cy - sh / 2
    screen_r = max(8.0, body_r - (bw - sw) / 2)
    return dict(k=k, W=W, H=H, cx=cx, cy=cy, bw=bw, bh=bh, body_r=body_r, sx0=sx0, sy0=sy0,
                sw=sw, sh=sh, screen_r=screen_r, margin=margin)


def render(sw, sh, color):
    g = geometry(sw, sh)
    k, W, H, cx, cy = g["k"], g["W"], g["H"], g["cx"], g["cy"]
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32) + 0.5
    d_body = rounded_box_sdf(xx, yy, cx, cy, g["bw"] / 2, g["bh"] / 2, g["body_r"])
    d_scr = rounded_box_sdf(xx, yy, cx, cy, sw / 2, sh / 2, g["screen_r"])
    base, hi = (np.array(c, np.float32) for c in COLORS[color])
    rim = RIM_MM * k

    # Rim: a rounded aluminium edge. Across the band the surface turns from
    # facing outward (outer edge) to facing the viewer (inner edge).
    gx, gy = np.gradient(d_body)
    gn = np.hypot(gx, gy) + 1e-6
    nx, ny = gx / gn, gy / gn
    p = np.clip(-d_body / rim, 0, 1)
    theta = (1 - p) * (np.pi / 2) * 0.92
    n3 = np.stack([nx * np.sin(theta), ny * np.sin(theta), np.cos(theta)], -1)
    L = np.array([-0.45, -0.62, 0.64], np.float32); L /= np.linalg.norm(L)
    lam = np.clip(n3 @ L, 0, 1)
    V = np.array([0, 0, 1], np.float32)
    Hh = (L + V) / np.linalg.norm(L + V)
    spec = np.clip(n3 @ Hh, 0, 1) ** 40
    metal = base[None, None] * (0.55 + 0.6 * lam[..., None]) + hi[None, None] * (0.55 * spec[..., None])
    # brushed grain, very faint
    rng = np.random.default_rng(7)
    grain = (rng.standard_normal((H, 1)).astype(np.float32) * 0.012)
    metal = np.clip(metal + grain[..., None], 0, 1)

    # Glass: near-black, a hair lighter at its chamfer so it reads as glass.
    glass = np.full((H, W, 3), 0.018, np.float32)
    chamfer = np.exp(-((d_body + rim + 1.2) / 1.1) ** 2)[..., None]
    glass = glass + chamfer * 0.10

    in_rim = np.clip(0.5 - d_body, 0, 1)                     # inside the body
    in_glass = np.clip(0.5 - (d_body + rim), 0, 1)           # inside the rim
    rgb = metal * (in_rim - in_glass)[..., None] + glass * in_glass[..., None]
    alpha = in_rim.copy()

    # Side keys on the right edge, standing proud of the rim.
    for top, bot in KEYS_MM:
        y0 = g["cy"] - g["bh"] / 2 + top * k
        y1 = g["cy"] - g["bh"] / 2 + bot * k
        kx = cx + g["bw"] / 2
        d_key = rounded_box_sdf(xx, yy, kx, (y0 + y1) / 2, KEY_OUT_MM * k + 1.5, (y1 - y0) / 2, KEY_OUT_MM * k)
        a_key = np.clip(0.5 - d_key, 0, 1) * (1 - in_rim)
        # the keys face away from the light (top-left): darker than the rim
        key_rgb = base[None, None] * 0.8 + hi[None, None] * 0.12
        rgb = rgb * (1 - a_key[..., None]) + key_rgb * a_key[..., None]
        alpha = np.maximum(alpha, a_key)

    # The screen is a hole in the frame (the compositor fills it).
    hole = np.clip(0.5 - d_scr, 0, 1)
    alpha = alpha * (1 - hole)

    # Punch-hole camera, drawn over the screen.
    hx, hy = cx, g["sy0"] + HOLE_TOP_MM * k
    r_hole = HOLE_MM * k / 2
    d_cam = np.hypot(xx - hx, yy - hy) - r_hole
    a_cam = np.clip(0.5 - d_cam, 0, 1)
    lens = np.exp(-((np.hypot(xx - hx + r_hole * 0.25, yy - hy + r_hole * 0.25)) / (r_hole * 0.35)) ** 2)
    cam_rgb = np.full((H, W, 3), 0.012, np.float32) + lens[..., None] * np.array([0.05, 0.06, 0.10], np.float32)
    ring = np.exp(-((d_cam + 1.0) / 0.9) ** 2)[..., None] * 0.07
    cam_rgb = cam_rgb + ring
    rgb = rgb * (1 - a_cam[..., None]) + cam_rgb * a_cam[..., None]
    alpha = np.maximum(alpha, a_cam)

    # premultiplied -> straight alpha
    safe = np.maximum(alpha, 1e-6)[..., None]
    out = np.where(alpha[..., None] > 0, rgb / safe, 0)
    frame = np.dstack([np.clip(out, 0, 1), alpha])
    mask = np.clip(0.5 - d_scr, 0, 1)
    return g, (frame * 255 + 0.5).astype(np.uint8), (mask * 255 + 0.5).astype(np.uint8)


def ensure(out_dir, sw, sh, color):
    """Cached frame + mask for this screen size and colour; returns paths and geometry."""
    os.makedirs(out_dir, exist_ok=True)
    fp = os.path.join(out_dir, f"frame-{color}-{sw}x{sh}.png")
    mp = os.path.join(out_dir, f"screen-mask-{sw}x{sh}.png")
    g = geometry(sw, sh)
    if not (os.path.exists(fp) and os.path.exists(mp)):
        g, frame, mask = render(sw, sh, color)
        Image.fromarray(frame, "RGBA").save(fp)
        Image.fromarray(mask, "L").save(mp)
    return fp, mp, g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("--screen", default="1080x2400")
    ap.add_argument("--color", default="obsidian", choices=sorted(COLORS))
    a = ap.parse_args()
    sw, sh = map(int, a.screen.split("x"))
    fp, mp, g = ensure(a.out_dir, sw, sh, a.color)
    print(fp, mp, f"{g['W']}x{g['H']}")


if __name__ == "__main__":
    main()
