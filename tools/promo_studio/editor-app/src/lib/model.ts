// The spec model and time, mirroring spec.py (defaults, resolve) and anim.py
// (keyframes, the edit). Pure functions: the store, the preview and the tests
// all use these, and test_studio.py checks them against Python.
import type { Ease, Key, Meta, PropDef, RawSpec, Spec, State } from "./types.ts";

export const clamp = (v: number, a: number, b: number) => Math.min(b, Math.max(a, v));
export const r3 = (v: number) => Math.round(v * 1000) / 1000;
export const clone = <T,>(o: T): T => JSON.parse(JSON.stringify(o));

export function merge(base: RawSpec, over: RawSpec | undefined): RawSpec {
  const out = clone(base);
  for (const [k, v] of Object.entries(over || {})) {
    if (v && typeof v === "object" && !Array.isArray(v) && out[k] && typeof out[k] === "object" && !Array.isArray(out[k])) out[k] = merge(out[k], v);
    else out[k] = clone(v);
  }
  return out;
}

export function resolve(raw: RawSpec, meta: Pick<Meta, "defaults" | "themes">): Spec {
  const s = merge(meta.defaults, raw) as Spec;
  if (s.phone.color === "auto") s.phone.color = meta.themes[s.app.theme]?.phone ?? "obsidian";
  s.edit = s.edit.filter((p) => p.out - p.in > 1e-3);
  if (!s.edit.length) s.edit = [{ in: 0, out: 1 }];
  const tracks: Record<string, Key[]> = {};
  for (const [k, v] of Object.entries(s.tracks || {})) if (v && v.length) tracks[k] = [...v].sort((a, b) => a.t - b.t);
  s.tracks = tracks;
  s.duration = Math.round(s.edit.reduce((a, p) => a + (p.out - p.in), 0) * 1e6) / 1e6;
  return s;
}

export function ease(name: Ease | undefined, u: number): number {
  u = clamp(u, 0, 1);
  switch (name) {
    case "linear": return u;
    case "hold": return u < 1 ? 0 : 1;
    case "inOutSine": return 0.5 - 0.5 * Math.cos(Math.PI * u);
    case "outCubic": return 1 - (1 - u) ** 3;
    case "inCubic": return u ** 3;
    case "outExpo": return u >= 1 ? 1 : 1 - 2 ** (-10 * u);
    default: return u < 0.5 ? 4 * u ** 3 : 1 - (-2 * u + 2) ** 3 / 2;
  }
}

// A track's value before its first key is that key's, after its last the
// last's; in between, the right-hand key's ease paces the move. No keys: the
// static value in props.
export function valueAt(s: Spec, props: Record<string, PropDef>, name: string, t: number): number {
  const keys = s.tracks[name];
  if (!keys || !keys.length) return +s.props[name];
  if (t <= keys[0].t) return +keys[0].v;
  for (let i = 0; i < keys.length - 1; i++) {
    const a = keys[i], b = keys[i + 1];
    if (a.t <= t && t <= b.t) {
      const e = ease(b.ease, (t - a.t) / Math.max(1e-6, b.t - a.t));
      if (props[name]?.log && a.v > 0 && b.v > 0) return Math.exp(Math.log(a.v) + (Math.log(b.v) - Math.log(a.v)) * e);
      return +a.v + (b.v - a.v) * e;
    }
  }
  return +keys[keys.length - 1].v;
}

export function stateAt(s: Spec, props: Record<string, PropDef>, t: number): State {
  const o: State = {};
  for (const k in props) o[k] = valueAt(s, props, k, t);
  return o;
}

// Video time -> [take time, piece index], through the edit.
export function sourceTime(s: Spec, t: number): [number, number] {
  t = Math.max(0, t);
  let acc = 0;
  for (let i = 0; i < s.edit.length; i++) {
    const p = s.edit[i], n = p.out - p.in;
    if (t < acc + n) return [p.in + (t - acc), i];
    acc += n;
  }
  const last = s.edit[s.edit.length - 1];
  return [last.out, s.edit.length - 1];
}

export function pieceStart(s: Spec, i: number): number {
  let acc = 0;
  for (let j = 0; j < i; j++) acc += s.edit[j].out - s.edit[j].in;
  return acc;
}

// A take span -> the video spans it appears in (a span can be cut or repeated).
export function videoTimes(s: Spec, a0: number, a1: number): [number, number][] {
  const out: [number, number][] = [];
  let acc = 0;
  for (const p of s.edit) {
    const a = Math.max(p.in, a0), b = Math.min(p.out, a1);
    if (b > a) out.push([acc + a - p.in, acc + b - p.in]);
    acc += p.out - p.in;
  }
  return out;
}

// The take opens on `lead` silent seconds, so the fade in starts with the
// first sound, not the video (compose.audio_filter / anim.sound_onset).
export function soundOnset(s: Spec, lead: number): number {
  if (s.app?.intro?.play === false) return 0;
  let acc = 0;
  for (const p of s.edit) {
    if (p.out > lead) return acc + Math.max(0, lead - p.in);
    acc += p.out - p.in;
  }
  return 0;
}

// The fades alone, 0..1 at video time t, shaped like ffmpeg's linear afade.
export function fadeAt(s: Spec, t: number, onset: number): number {
  const d = s.duration, fi = Math.min(s.audio.fade_in, d - onset), fo = Math.min(s.audio.fade_out, d);
  let k = 1;
  if (s.audio.fade_in > 0 && fi > 0) k *= clamp((t - onset) / fi, 0, 1);
  if (fo > 0) k *= clamp((d - t) / fo, 0, 1);
  return k;
}

// Linear gain the preview applies: the spec's gain and its fades.
export const soundGain = (s: Spec, t: number, onset: number) => 10 ** ((s.audio.gain_db || 0) / 20) * fadeAt(s, t, onset);

export const takeKey = (s: Spec) => [s.app.reciter, s.app.surah, s.app.ayah, s.app.theme, s.app.layout, s.app.view, s.app.text_size,
  s.app.repeat, s.app.repeat === "range" ? s.app.repeat_to : null, JSON.stringify(s.app.intro)].join("|");

// Everything baked into the background plate: a change means a new plate.
export function plateLook(s: Spec): string {
  const blurs = [+s.props.bg_blur, ...(s.tracks.bg_blur || []).map((k) => +k.v)];
  return JSON.stringify([s.background, s.aspect, Math.max(0, Math.min(...blurs))]);
}

export function fmtTime(t: number): string {
  const m = Math.floor(t / 60), sec = t - m * 60;
  return m ? `${m}:${sec.toFixed(2).padStart(5, "0")}` : sec.toFixed(2);
}

export function parseTime(text: string): number {
  const m = String(text).trim().match(/^(?:(\d+):)?(\d+(?:\.\d*)?)$/);
  return m ? (+(m[1] || 0)) * 60 + +m[2] : NaN;
}
