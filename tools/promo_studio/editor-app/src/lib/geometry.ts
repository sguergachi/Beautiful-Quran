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

export function invert(m: M): M {
  const [a, b, c, d, e, f] = m, det = a * d - b * c;
  return [d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det];
}

export function apply(m: M, x: number, y: number): [number, number] {
  return [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
}
