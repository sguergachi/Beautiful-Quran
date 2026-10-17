#!/usr/bin/env python3
"""Place a real app frame onto a photoreal plate's green phone screen.

    composite_screen.py PLATE.png SCREEN.png OUT.png [--width 1080] [--debug DBG.png]

The plate's phone screen is flat chroma green (docs/KEY_ART.md). The keyer:

1. Soft alpha from chroma distance (Cb/Cr) to the plate's own key colour,
   so the key follows the exact green the model painted.
2. Screen geometry from robust per-side line fits on the key's contour,
   ignoring where the thumb bites the edge; the app frame is warped onto the
   intersected corners, so the screen's own rounded glass stays the mask.
3. Edge unmixing: a partially green pixel is solved back to its foreground
   colour (thumb skin, bezel black) before the app goes underneath. That is
   what removes the fringe a hard threshold leaves.
4. Despill on the remaining foreground near the screen, then grain and a
   hair of softening so the screen has the photograph's texture.
"""
import sys

import cv2
import numpy as np


def key_colour(img):
    f = img.astype(np.float32)
    g_ex = f[..., 1] - np.maximum(f[..., 0], f[..., 2])
    core = g_ex > np.percentile(g_ex, 99.0) * 0.85
    return np.median(f[core], axis=0)


def chroma_alpha(img, key, lo=18.0, hi=58.0):
    """1 on screen green, 0 on anything else, soft across the transition."""
    ycc = cv2.cvtColor(img, cv2.COLOR_BGR2YCrCb).astype(np.float32)
    k = cv2.cvtColor(key.reshape(1, 1, 3).astype(np.uint8), cv2.COLOR_BGR2YCrCb).astype(np.float32)[0, 0]
    d = np.hypot(ycc[..., 1] - k[1], ycc[..., 2] - k[2])
    return np.clip((hi - d) / (hi - lo), 0, 1)


def _intersect(l1, l2):
    (p, r), (q, s) = l1, l2
    t = np.linalg.solve(np.array([r, -s]).T, q - p)
    return p + t[0] * r


def fit_quad(alpha):
    """Corners (tl, tr, br, bl) where the screen's straight edges meet."""
    m = (alpha > 0.5).astype(np.uint8)
    _, labels, stats, _ = cv2.connectedComponentsWithStats(m)
    m = (labels == 1 + np.argmax(stats[1:, cv2.CC_STAT_AREA])).astype(np.uint8)
    cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    c = max(cnts, key=cv2.contourArea).reshape(-1, 2).astype(np.float32)
    hull = cv2.convexHull(c).reshape(-1, 2)
    approx, eps = hull, 2.0
    while len(approx) > 4 and eps < 400:
        approx = cv2.approxPolyDP(hull.reshape(-1, 1, 2), eps, True).reshape(-1, 2)
        eps *= 1.3
    s, d = approx.sum(1), approx[:, 0] - approx[:, 1]
    corners = np.array([approx[np.argmin(s)], approx[np.argmax(d)], approx[np.argmax(s)], approx[np.argmin(d)]], np.float32)
    lines = []
    for i in range(4):
        a, b = corners[i], corners[(i + 1) % 4]
        L = np.linalg.norm(b - a)
        u = (b - a) / L
        n = np.array([-u[1], u[0]])
        along, off = (c - a) @ u, (c - a) @ n
        # the straight middle of the side, away from the rounded corners
        pts = c[(along > 0.15 * L) & (along < 0.85 * L) & (np.abs(off) < 8)]
        line = (a, u)
        for _ in range(4):           # drop points the thumb pulls inward, refit
            if len(pts) < 20:
                break
            vx, vy, x0, y0 = cv2.fitLine(pts, cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
            line = (np.array([x0, y0]), np.array([vx, vy]))
            nn = np.array([-vy, vx])
            res = (pts - line[0]) @ nn
            pts = pts[np.abs(res - np.median(res)) < 1.5]
        lines.append(line)
    return np.array([_intersect(lines[(i - 1) % 4], lines[i]) for i in range(4)], np.float32)


def composite(plate, screen, alpha, quad, key, soften=0.5, grain=2.5, seed=1):
    h, w = plate.shape[:2]
    sh, sw = screen.shape[:2]
    src = np.array([[0, 0], [sw, 0], [sw, sh], [0, sh]], np.float32)
    Hm = cv2.getPerspectiveTransform(src, quad)
    app = cv2.warpPerspective(screen, Hm, (w, h), flags=cv2.INTER_AREA).astype(np.float32)
    if soften:
        app = cv2.GaussianBlur(app, (0, 0), soften)
    inside = cv2.warpPerspective(np.full((sh, sw), 255, np.uint8), Hm, (w, h))
    inside = cv2.dilate(inside, np.ones((5, 5), np.uint8)).astype(np.float32) / 255
    a = (alpha * inside)[..., None]
    P = plate.astype(np.float32)
    # unmix: plate = a*key + (1-a)*fg  ->  fg
    fg = np.clip((P - a * key.reshape(1, 1, 3)) / np.clip(1 - a, 0.04, 1), 0, 255)
    fg = np.where(a < 0.02, P, fg)
    # despill near the screen: green may not exceed the other channels
    # The model also paints green light onto the fingers, so within reach of
    # the screen green is held to what skin allows (R > G > B), fading out.
    reach = cv2.GaussianBlur(cv2.dilate(inside, np.ones((61, 61), np.uint8)), (0, 0), 25)[..., None]
    B, G, R = fg[..., 0:1], fg[..., 1:2], fg[..., 2:3]
    warm = np.clip((R - B) / 40, 0, 1)                 # skin and wood, not the blue night
    cap = np.where(warm > 0, R * 0.84 + B * 0.14 + 2, np.maximum(R, B) * 1.03 + 2)
    fg[..., 1:2] = G - np.clip(G - cap, 0, None) * reach
    # The cast is also a yellow shift (blue gone), so pull the chroma of warm
    # pixels close to the screen toward the same hand's skin further away.
    ins8 = (inside > 0.5).astype(np.uint8)
    dist = cv2.distanceTransform(1 - ins8, cv2.DIST_L2, 5)
    lab = cv2.cvtColor(np.clip(fg, 0, 255).astype(np.uint8), cv2.COLOR_BGR2LAB).astype(np.float32)
    warm1 = warm[..., 0] > 0.5
    ring = warm1 & (dist > 70) & (dist < 220)
    if ring.sum() > 500:
        target = np.median(lab[ring][:, 1:], axis=0)
        zone = (np.clip(1 - (dist - 10) / 60, 0, 1) * warm[..., 0])[..., None]
        lab[..., 1:] += (target - lab[..., 1:]) * 0.75 * zone
        fixed = cv2.cvtColor(np.clip(lab, 0, 255).astype(np.uint8), cv2.COLOR_LAB2BGR).astype(np.float32)
        fg = np.where(zone > 0, fixed, fg)
    out = fg * (1 - a) + app * a
    noise = cv2.GaussianBlur(np.random.default_rng(seed).normal(0, grain, (h, w)).astype(np.float32), (0, 0), 0.6)
    out += noise[..., None] * a
    return np.clip(out, 0, 255).astype(np.uint8)


def main():
    plate_p, screen_p, out_p = sys.argv[1:4]
    width = int(sys.argv[sys.argv.index("--width") + 1]) if "--width" in sys.argv else 1080
    plate = cv2.imread(plate_p)
    plate = cv2.resize(plate, (width, round(plate.shape[0] * width / plate.shape[1])), interpolation=cv2.INTER_AREA)
    screen = cv2.imread(screen_p)
    key = key_colour(plate)
    alpha = chroma_alpha(plate, key)
    quad = fit_quad(alpha)
    cv2.imwrite(out_p, composite(plate, screen, alpha, quad, key))
    if "--debug" in sys.argv:
        dbg = plate.copy()
        cv2.polylines(dbg, [np.round(quad).astype(np.int32)], True, (0, 0, 255), 1, cv2.LINE_AA)
        cv2.imwrite(sys.argv[sys.argv.index("--debug") + 1], dbg)
    print("key", key.round(1).tolist(), "quad", quad.round(1).tolist())


if __name__ == "__main__":
    main()
