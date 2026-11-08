// Scene geometry, mirroring compose.Renderer.phone_matrix / bg_matrix.
// Matrices are [a b c d e f] in canvas order (ctx.transform(a, b, c, d, e, f)).
import type { Geo, Plate, Spec, State } from "./types.ts";

export type M = [number, number, number, number, number, number];

const rot = (deg: number): [number, number] => { const r = (deg * Math.PI) / 180; return [Math.cos(r), Math.sin(r)]; };

// Phone canvas px -> output px. World: w = P0 + sc·Rtilt·(p − C0);
// camera: out = z·Rcam·(w − C) + O.
export function phoneMatrix(spec: Spec, geo: Pick<Geo, "W" | "H" | "g">, S: State): M {
  const { g, W, H } = geo;
  const sc = ((spec.phone.height * H) / g.bh) * S.phone_scale, z = S.zoom;
  const P0 = [W / 2 + S.phone_x * W, H / 2 + S.phone_y * H], C = [S.pan_x * W, S.pan_y * H];
  const [c, s] = rot(S.rotate), [ct, st] = rot(S.rotate + S.phone_tilt);
  const L = [z * sc * ct, -z * sc * st, z * sc * st, z * sc * ct];     // [[L00 L01][L10 L11]]
  const vx = z * (P0[0] - C[0]), vy = z * (P0[1] - C[1]);
  const C0 = [g.W / 2, g.H / 2];
  const tx = c * vx - s * vy - (L[0] * C0[0] + L[1] * C0[1]) + W / 2;
  const ty = s * vx + c * vy - (L[2] * C0[0] + L[3] * C0[1]) + H / 2;
  return [L[0], L[2], L[1], L[3], tx, ty];
}

// Plate px -> output px; the background follows the camera by `parallax`.
export function bgMatrix(spec: Spec, geo: Pick<Geo, "W" | "H">, plate: Pick<Plate, "w" | "h" | "k">, S: State): M {
  const { W, H } = geo, p = +spec.background.parallax;
  const zb = 1 + (S.zoom - 1) * p;
  const sh = [(S.pan_x * W - W / 2) * p, (S.pan_y * H - H / 2) * p];
  const [c, s] = rot(S.rotate * p);
  const pk = plate.k, Bc = [plate.w / 2, plate.h / 2], L = zb / pk;
  const vx = zb * (-Bc[0] / pk - sh[0]), vy = zb * (-Bc[1] / pk - sh[1]);
  return [c * L, s * L, -s * L, c * L, c * vx - s * vy + W / 2, s * vx + c * vy + H / 2];
}

// Frame-normalised world point of a point on the phone screen (fx, fy in 0..1).
export function screenPointWorld(spec: Spec, geo: Pick<Geo, "W" | "H" | "g">, S: State, fx: number, fy: number): [number, number] {
  const { g, W, H } = geo;
  const sc = ((spec.phone.height * H) / g.bh) * S.phone_scale;
  const [c, s] = rot(S.phone_tilt);
  const dx = g.sx0 + fx * g.sw - g.W / 2, dy = g.sy0 + fy * g.sh - g.H / 2;
  return [(W / 2 + S.phone_x * W + sc * (c * dx - s * dy)) / W, (H / 2 + S.phone_y * H + sc * (s * dx + c * dy)) / H];
}

// Zoom at which the phone's screen spans ~94% of the frame's width.
export function fitZoom(spec: Spec, geo: Pick<Geo, "W" | "H" | "g">, S: State): number {
  const { g, W, H } = geo;
  const sw = g.sw * ((spec.phone.height * H) / g.bh) * S.phone_scale;
  return +Math.min(4, Math.max(1, (W * 0.94) / sw)).toFixed(3);
}

// Where the phone's centre lands in the frame for a given phone_x / phone_y:
// out = O + z·Rcam·(P0 − C), so the tilt, the phone's size and its screen
// offset do not move it.
export function phoneCentre(geo: Pick<Geo, "W" | "H">, S: State, px: number, py: number, panX = S.pan_x, panY = S.pan_y): [number, number] {
  const { W, H } = geo, [c, s] = rot(S.rotate);
  const vx = S.zoom * (W / 2 + px * W - panX * W), vy = S.zoom * (H / 2 + py * H - panY * H);
  return [W / 2 + c * vx - s * vy, H / 2 + s * vx + c * vy];
}

// Snap a dragged phone's centre to the frame's vertical and horizontal centre
// lines. Each axis snaps on its own once within `thr` output px of its line,
// measured in the frame (not the camera's rotated space), so the guides the
// editor draws are the lines it snaps to. Returns the adjusted phone_x / phone_y.
export function snapPhoneToCentre(geo: Pick<Geo, "W" | "H">, S: State, px: number, py: number, thr: number) {
  const { W, H } = geo, [cx, cy] = phoneCentre(geo, S, px, py);
  const ox = W / 2 - cx, oy = H / 2 - cy;
  const snapX = Math.abs(ox) < thr, snapY = Math.abs(oy) < thr;
  const ux = snapX ? ox : 0, uy = snapY ? oy : 0, [c, s] = rot(S.rotate);
  // frame delta -> world delta: Rcam⁻¹ · u / z
  return { x: px + (c * ux + s * uy) / (S.zoom * W), y: py + (-s * ux + c * uy) / (S.zoom * H), snapX, snapY };
}

// The pan that puts the phone's centre at `frac` of the frame across (axis "x",
// 0 = left edge) or down (axis "y") while it keeps its place on the other
// axis. With the camera rotated the two pans are coupled, so both are returned.
export function panToPlacePhone(geo: Pick<Geo, "W" | "H">, S: State, axis: "x" | "y", frac: number) {
  const { W, H } = geo, [cx, cy] = phoneCentre(geo, S, S.phone_x, S.phone_y), [c, s] = rot(S.rotate);
  const dx = (axis === "x" ? frac * W : cx) - W / 2, dy = (axis === "y" ? frac * H : cy) - H / 2;
  // frame offset -> camera space: Rcam⁻¹ · d
  const vx = c * dx + s * dy, vy = -s * dx + c * dy;
  return { x: 0.5 + S.phone_x - vx / (S.zoom * W), y: 0.5 + S.phone_y - vy / (S.zoom * H) };
}

// The same snap for a plain drag, which pans the camera: the phone's centre is
// brought onto the frame's centre lines by adjusting pan_x / pan_y instead of
// the phone's own position. Returns the adjusted pan.
export function snapPanToPhone(geo: Pick<Geo, "W" | "H">, S: State, panX: number, panY: number, thr: number) {
  const { W, H } = geo, [cx, cy] = phoneCentre(geo, S, S.phone_x, S.phone_y, panX, panY);
  const ox = W / 2 - cx, oy = H / 2 - cy;
  const snapX = Math.abs(ox) < thr, snapY = Math.abs(oy) < thr;
  const ux = snapX ? ox : 0, uy = snapY ? oy : 0, [c, s] = rot(S.rotate);
  // moving the camera by +p moves everything by -p, so the world delta flips sign
  return { x: panX - (c * ux + s * uy) / (S.zoom * W), y: panY - (-s * ux + c * uy) / (S.zoom * H), snapX, snapY };
}

export function invert(m: M): M {
  const [a, b, c, d, e, f] = m, det = a * d - b * c;
  return [d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det];
}

export function apply(m: M, x: number, y: number): [number, number] {
  return [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
}
