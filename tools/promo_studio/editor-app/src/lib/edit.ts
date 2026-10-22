// Edits as pure functions on a draft of the raw spec. The store wraps each in
// an undo step (store.mutate); the tests call them directly.
import { clamp, pieceStart, r3, sourceTime, stateAt, valueAt } from "./model.ts";
import { fitZoom, screenPointWorld } from "./geometry.ts";
import type { Ease, Geo, Key, PropDef, RawSpec, Spec } from "./types.ts";

export const MIN_PIECE = 0.05;
export interface Ctx { spec: Spec; props: Record<string, PropDef>; fps: number }
export interface KeyRef { prop: string; t: number }
export interface Clip { prop: string; key: Key; dt: number }

const half = (c: Ctx) => 0.5 / (c.fps || 60) + 1e-6;
export const sameKey = (a: KeyRef, b: KeyRef) => a.prop === b.prop && Math.abs(a.t - b.t) < 1e-6;

export function track(raw: RawSpec, name: string): Key[] {
  raw.tracks = raw.tracks || {};
  return (raw.tracks[name] = raw.tracks[name] || []);
}
export const animated = (raw: RawSpec, name: string) => !!(raw.tracks?.[name] || []).length;
export function findKey(raw: RawSpec, ref: KeyRef): Key | undefined {
  return (raw.tracks?.[ref.prop] || []).find((k: Key) => Math.abs(k.t - ref.t) < 1e-6);
}
export function keyIndexNear(raw: RawSpec, c: Ctx, name: string, t: number): number {
  return (raw.tracks?.[name] || []).findIndex((k: Key) => Math.abs(k.t - t) < half(c));
}
function dropEmpty(raw: RawSpec, name: string, lastValue: number) {
  // the last key gone: the property keeps that value for the whole video
  if (raw.tracks?.[name] && !raw.tracks[name].length) {
    delete raw.tracks[name];
    raw.props = raw.props || {};
    raw.props[name] = lastValue;
  }
}

// Set a property at time t: a key there once it is animated, else its static value.
export function setProp(raw: RawSpec, c: Ctx, name: string, v: number, t: number) {
  const p = c.props[name];
  v = r3(clamp(v, p.min, p.max));
  if (animated(raw, name)) {
    const keys = track(raw, name), i = keyIndexNear(raw, c, name, t);
    if (i >= 0) keys[i].v = v;
    else { keys.push({ t: r3(t), v }); keys.sort((a, b) => a.t - b.t); }
  } else {
    raw.props = raw.props || {};
    raw.props[name] = v;
  }
}

// Add a key at t (holding the current value), or remove the one there. Returns the key added.
export function toggleKey(raw: RawSpec, c: Ctx, name: string, t: number): KeyRef | null {
  const keys = track(raw, name), i = keyIndexNear(raw, c, name, t);
  if (i >= 0) {
    const [k] = keys.splice(i, 1);
    dropEmpty(raw, name, k.v);
    return null;
  }
  keys.push({ t: r3(t), v: r3(valueAt(c.spec, c.props, name, t)) });
  keys.sort((a, b) => a.t - b.t);
  return { prop: name, t: r3(t) };
}

export function keyAll(raw: RawSpec, c: Ctx, names: string[], at: number, vals: Record<string, number> = {}, ease?: Ease) {
  for (const n of names) {
    const keys = track(raw, n), i = keys.findIndex((k) => Math.abs(k.t - at) < half(c));
    const k: Key = { t: r3(at), v: r3(vals[n] ?? valueAt(c.spec, c.props, n, at)) };
    if (ease) k.ease = ease;
    if (i >= 0) keys[i] = { ...keys[i], ...k }; else keys.push(k);
    keys.sort((a, b) => a.t - b.t);
  }
}

// Move keys by dt seconds, kept in [0, duration]; refuses (returns null) a step
// that would land any of them on another key of its track.
export function moveKeys(raw: RawSpec, c: Ctx, refs: KeyRef[], dt: number, origin?: KeyRef[]): KeyRef[] | null {
  const from = origin ?? refs;
  const moving = refs.map((r, i) => ({ prop: r.prop, key: findKey(raw, r), t0: from[i].t })).filter((m) => m.key) as { prop: string; key: Key; t0: number }[];
  if (!moving.length) return null;
  const lo = -Math.min(...moving.map((m) => m.t0)), hi = c.spec.duration - Math.max(...moving.map((m) => m.t0));
  const d = clamp(dt, lo, hi);
  const clash = moving.some((m) => track(raw, m.prop).some((x) => !moving.some((o) => o.key === x) && Math.abs(x.t - (m.t0 + d)) < half(c)));
  if (clash) return null;
  for (const m of moving) m.key.t = r3(m.t0 + d);
  for (const p of new Set(moving.map((m) => m.prop))) track(raw, p).sort((a, b) => a.t - b.t);
  return moving.map((m) => ({ prop: m.prop, t: m.key.t }));
}

export function deleteKeys(raw: RawSpec, refs: KeyRef[]) {
  for (const r of refs) {
    const keys = raw.tracks?.[r.prop];
    if (!keys) continue;
    const i = keys.findIndex((k: Key) => Math.abs(k.t - r.t) < 1e-6);
    if (i < 0) continue;
    const [k] = keys.splice(i, 1);
    dropEmpty(raw, r.prop, k.v);
  }
}

export function setEase(raw: RawSpec, refs: KeyRef[], ease: Ease) {
  for (const r of refs) {
    const k = findKey(raw, r);
    if (!k) continue;
    if (ease === "inOutCubic") delete k.ease; else k.ease = ease;
  }
}

export function clearTrack(raw: RawSpec, c: Ctx, name: string, t: number) {
  const v = r3(valueAt(c.spec, c.props, name, t));
  if (raw.tracks) delete raw.tracks[name];
  raw.props = raw.props || {};
  raw.props[name] = v;
}

export function copyKeys(raw: RawSpec, refs: KeyRef[]): Clip[] {
  const items = refs.map((r) => ({ prop: r.prop, key: findKey(raw, r) })).filter((x) => x.key) as { prop: string; key: Key }[];
  if (!items.length) return [];
  const t0 = Math.min(...items.map((x) => x.key.t));
  return items.map((x) => ({ prop: x.prop, key: { ...x.key }, dt: x.key.t - t0 }));
}

// Paste at `at`, keeping the copied keys' spacing; replaces keys already there.
export function pasteKeys(raw: RawSpec, c: Ctx, clip: Clip[], at: number): KeyRef[] {
  const placed: KeyRef[] = [];
  for (const cl of clip) {
    const t = r3(clamp(at + cl.dt, 0, c.spec.duration)), keys = track(raw, cl.prop);
    const i = keys.findIndex((k) => Math.abs(k.t - t) < half(c)), k = { ...cl.key, t };
    if (i >= 0) keys[i] = k; else keys.push(k);
    keys.sort((a, b) => a.t - b.t);
    placed.push({ prop: cl.prop, t });
  }
  return placed;
}

// Camera moves to the active verse (screen y≈0.32) and back, keyed at t.
export function preset(raw: RawSpec, c: Ctx, geo: Geo, kind: "push" | "pull" | "punch", t: number) {
  const cam = ["zoom", "pan_x", "pan_y"], S = stateAt(c.spec, c.props, t), d = 0.9;
  keyAll(raw, c, cam, t);
  if (kind === "pull") {
    const [px, py] = screenPointWorld(c.spec, geo, S, 0.5, 0.5);
    keyAll(raw, c, cam, Math.min(c.spec.duration, t + d), { zoom: 1, pan_x: px, pan_y: py }, "inOutCubic");
  } else {
    const z = fitZoom(c.spec, geo, S), [px, py] = screenPointWorld(c.spec, geo, S, 0.5, 0.32);
    const at = Math.min(c.spec.duration, kind === "push" ? t + d : t + 1 / (c.fps || 60));
    keyAll(raw, c, cam, at, { zoom: z, pan_x: px, pan_y: py }, kind === "push" ? "inOutCubic" : "hold");
  }
}

// Recording edit ---------------------------------------------------------

export function splitAt(raw: RawSpec, c: Ctx, t: number): number | null {
  const [src, i] = sourceTime(c.spec, t);
  const p = raw.edit[i];
  if (!p || src - p.in < MIN_PIECE || p.out - src < MIN_PIECE) return null;
  raw.edit.splice(i, 1, { ...p, out: r3(src) }, { in: r3(src), out: p.out });
  return i + 1;
}

export function deletePiece(raw: RawSpec, i: number): boolean {
  if (raw.edit.length <= 1) return false;
  raw.edit.splice(i, 1);
  return true;
}

// A left trim leaves a gap, preserving the clip's end and everything after
// it. Extending left fills that gap, stopping before the preceding clip.
export function trimPiece(raw: RawSpec, i: number, side: "in" | "out", value: number, take?: number | null) {
  if (!Number.isFinite(value)) return;
  const p = raw.edit[i], minLength = Math.min(MIN_PIECE, p.out - p.in);
  if (side === "in") {
    const gap = p.gap || 0, next = clamp(r3(value), Math.max(0, p.in - gap), p.out - minLength);
    p.gap = r3(gap + next - p.in);
    p.in = next;
    if (!p.gap) delete p.gap;
  }
  else p.out = clamp(r3(value), p.in + minLength, Math.max(p.in + minLength, take ?? 1e9));
}

export function landmarks(spec: Spec, verses: { t0: number; t1: number }[] | undefined, videoTimes: (a: number, b: number) => [number, number][]): number[] {
  const out = [0, spec.duration];
  for (const v of verses || []) for (const [a] of videoTimes(v.t0, v.t1)) out.push(a);
  for (let i = 0; i < spec.edit.length; i++) {
    const start = pieceStart(spec, i);
    out.push(start, start + spec.edit[i].out - spec.edit[i].in);
  }
  return [...new Set(out.map(r3))].sort((a, b) => a - b);
}
