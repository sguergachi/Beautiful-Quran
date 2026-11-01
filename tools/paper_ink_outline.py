"""Fractional-coverage contour reconstruction used by the Android wet outline.

The arrival/depth solver remains in build_paper_ink_study.py. This reference
measures distance to the half-coverage contour before the wet stroke expands,
so the antialiasing of the source font is not reduced to a binary staircase.
"""
import numpy as np
from scipy.ndimage import distance_transform_edt


def outline_distance(alpha, scale, fallback):
    h, w = alpha.shape
    corners = np.stack((alpha[:-1, :-1], alpha[:-1, 1:], alpha[1:, 1:], alpha[1:, :-1]), axis=-1)
    boundary = np.zeros((h, w), dtype=bool)
    boundary[:-1, :-1] = (corners.min(-1) < .5) & (corners.max(-1) >= .5)
    if not boundary.any():
        return fallback
    segments = np.zeros((h, w, 2, 4))
    counts = np.zeros((h, w), dtype='uint8')
    coords = [(0, 0), (1, 0), (1, 1), (0, 1)]
    for y, x in zip(*np.where(boundary)):
        values = corners[y, x]
        points = []
        for edge in range(4):
            nxt = (edge + 1) % 4
            if (values[edge] >= .5) == (values[nxt] >= .5):
                continue
            t = (.5 - values[edge]) / (values[nxt] - values[edge])
            points.append((x + coords[edge][0] + t * (coords[nxt][0] - coords[edge][0]),
                           y + coords[edge][1] + t * (coords[nxt][1] - coords[edge][1])))
        if len(points) == 4 and (values.mean() >= .5) != (values[0] >= .5):
            points = [points[i] for i in (0, 3, 1, 2)]
        counts[y, x] = len(points) // 2
        segments[y, x, :counts[y, x]] = np.array(points).reshape(-1, 4)
    sy, sx = distance_transform_edt(~boundary, return_distances=False, return_indices=True)
    yy, xx = np.indices((h, w))
    best = np.full((h, w), np.inf)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            cy, cx = np.clip(sy + dy, 0, h - 2), np.clip(sx + dx, 0, w - 2)
            for i in range(2):
                line = segments[cy, cx, i]
                vx, vy = line[:, :, 2] - line[:, :, 0], line[:, :, 3] - line[:, :, 1]
                px, py = xx - line[:, :, 0], yy - line[:, :, 1]
                length = vx * vx + vy * vy
                t = np.clip(np.divide(px * vx + py * vy, length, out=np.zeros_like(length), where=length > 0), 0, 1)
                d = (px - t * vx) ** 2 + (py - t * vy) ** 2
                best = np.minimum(best, np.where(counts[cy, cx] > i, d, np.inf))
    return np.sqrt(best) / scale * np.where(alpha >= .5, 1, -1)
