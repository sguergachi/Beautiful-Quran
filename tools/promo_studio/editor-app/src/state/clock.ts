// The playhead and the media it drives. Time lives outside React: the
// preview, the playhead and the timecode subscribe and update at frame rate
// without re-rendering the app; React reads it through useTime() at a
// gentler rate.
import { useEffect, useState } from "react";
import { clamp, soundGain, sourceTime } from "../lib/model.ts";
import type { Spec } from "../lib/types.ts";

type Listener = (t: number) => void;

function makeVideo(loop = false) {
  const v = document.createElement("video");
  v.muted = true; v.playsInline = true; v.preload = "auto"; v.loop = loop; v.crossOrigin = "anonymous";
  return v;
}

export const media = {
  screen: makeVideo(),
  plate: makeVideo(true),
  audio: new Audio(),
  frame: new Image(),
};
media.audio.preload = "auto";

// A media element seeks asynchronously; asking again before it lands restarts
// the decode, so a fast scrub would show nothing until it stopped. Keep only
// the latest target and apply it when the current seek lands.
const want = new WeakMap<HTMLMediaElement, number>();
function seekMedia(m: HTMLMediaElement, t: number) {
  if (!Number.isFinite(t)) return;
  if (m.seeking) { want.set(m, t); return; }
  want.delete(m);
  m.currentTime = t;
}
for (const m of [media.screen, media.plate, media.audio]) {
  m.addEventListener("loadeddata", () => {
    clock.sync(true);
    if (clock.playing) m.play().catch(() => {});
    clock.emitMedia();
  });
  m.addEventListener("seeked", () => {
    const t = want.get(m);
    if (t != null) { want.delete(m); m.currentTime = t; }
    clock.emitMedia();
  });
}

let ctx: () => { spec: Spec | null; hasClip: boolean; hasPlate: boolean; offset: number; onset: number } =
  () => ({ spec: null, hasClip: false, hasPlate: false, offset: 0, onset: 0 });

// The take's sound runs through a gain node so the preview hears the spec's
// gain and fades as the export will (a media element's volume stops at 1).
// The graph starts on the first play, inside the user's gesture.
let audioCtx: AudioContext | null = null, gainNode: GainNode | null = null;
function startSound() {
  if (!audioCtx) {
    try {
      audioCtx = new AudioContext();
      gainNode = audioCtx.createGain();
      audioCtx.createMediaElementSource(media.audio).connect(gainNode).connect(audioCtx.destination);
    } catch { audioCtx = gainNode = null; }
  }
  if (audioCtx?.state === "suspended") audioCtx.resume().catch(() => {});
}
function setGain(t: number, jump: boolean) {
  const { spec, onset } = ctx();
  if (!spec) return;
  const g = soundGain(spec, t, onset);
  if (!audioCtx || !gainNode) { media.audio.volume = clamp(g, 0, 1); return; }
  const at = audioCtx.currentTime, p = gainNode.gain;
  p.cancelScheduledValues(at);
  if (jump) p.setValueAtTime(g, at);
  else p.setTargetAtTime(g, at, 0.012);   // per-frame steps without zipper noise
}

export const clock = {
  t: 0,
  playing: false,
  loop: false,
  _listeners: new Set<Listener>(),
  _media: new Set<() => void>(),
  _state: new Set<() => void>(),
  _wall0: 0,
  _t0: 0,
  _piece: -1,
  _raf: 0,

  bind(get: typeof ctx) { ctx = get; },
  subscribe(fn: Listener) { this._listeners.add(fn); return () => { this._listeners.delete(fn); }; },
  onMedia(fn: () => void) { this._media.add(fn); return () => { this._media.delete(fn); }; },
  onState(fn: () => void) { this._state.add(fn); return () => { this._state.delete(fn); }; },
  emit() { for (const f of this._listeners) f(this.t); },
  emitMedia() { for (const f of this._media) f(); },
  emitState() { for (const f of this._state) f(); },

  duration() { return ctx().spec?.duration ?? 0; },
  plateTime(t: number) {
    const d = media.plate.duration || 1;
    return (((ctx().offset + t) % d) + d) % d;
  },
  sync(force: boolean) {
    const { spec, hasClip, hasPlate } = ctx();
    if (!spec) return;
    const fps = spec.fps || 60;
    const [src, piece] = sourceTime(spec, Math.min(this.t, Math.max(0, spec.duration - 1 / fps)));
    force = force || piece !== this._piece;
    this._piece = piece;
    if (hasClip) {
      if (Math.abs(media.screen.currentTime - src) > (force ? 0.001 : 0.07)) seekMedia(media.screen, src);
      if (Math.abs(media.audio.currentTime - src) > (force ? 0.001 : 0.09)) seekMedia(media.audio, src);
    }
    if (hasPlate) {
      const pt = this.plateTime(this.t);
      if (force || Math.abs(media.plate.currentTime - pt) > 0.09) seekMedia(media.plate, pt);
    }
  },
  seek(t: number) {
    this.t = clamp(t, 0, this.duration());
    this._wall0 = performance.now(); this._t0 = this.t;
    this.sync(true);
    if (this.playing) setGain(this.t, true);
    this.emit();
  },
  play() {
    const { spec, hasClip, hasPlate } = ctx();
    if (!spec || this.playing) return;
    if (this.t >= spec.duration - 0.05) this.t = 0;
    this.playing = true;
    startSound();
    setGain(this.t, true);
    this.sync(true);
    this._wall0 = performance.now(); this._t0 = this.t;
    if (hasClip) { media.screen.play().catch(() => {}); media.audio.play().catch(() => {}); }
    if (hasPlate) media.plate.play().catch(() => {});
    this.emitState();
    this._raf = requestAnimationFrame(() => this._tick());
  },
  pause() {
    if (!this.playing) return;
    this.playing = false;
    cancelAnimationFrame(this._raf);
    media.screen.pause(); media.audio.pause(); media.plate.pause();
    this.emitState(); this.emit();
  },
  toggle() { this.playing ? this.pause() : this.play(); },
  /** The preview's sound level now: the gain node's, or the element volume without Web Audio. */
  gain() { return gainNode ? gainNode.gain.value : media.audio.volume; },
  setLoop(on: boolean) { this.loop = on; this.emitState(); },
  _tick() {
    if (!this.playing) return;
    // the wall clock leads; the take's picture and sound follow it through the cuts
    this.t = this._t0 + (performance.now() - this._wall0) / 1000;
    const d = this.duration();
    if (this.t >= d) {
      if (!this.loop) { this.t = d; this.pause(); return; }
      this.t = 0; this._t0 = 0; this._wall0 = performance.now(); this.sync(true); setGain(0, true);
    }
    this.sync(false);
    setGain(this.t, false);
    this.emit();
    this._raf = requestAnimationFrame(() => this._tick());
  },
};

// React: the playhead time, re-rendering at most every `ms` milliseconds.
export function useTime(ms = 80): number {
  const [t, setT] = useState(clock.t);
  useEffect(() => {
    let last = 0, timer: number | undefined;
    const off = clock.subscribe((now) => {
      const at = performance.now();
      if (at - last >= ms) { last = at; setT(now); }
      else { clearTimeout(timer); timer = window.setTimeout(() => { last = performance.now(); setT(clock.t); }, ms); }
    });
    return () => { off(); clearTimeout(timer); };
  }, [ms]);
  return t;
}

export function usePlaying(): { playing: boolean; loop: boolean } {
  const [s, setS] = useState({ playing: clock.playing, loop: clock.loop });
  useEffect(() => clock.onState(() => setS({ playing: clock.playing, loop: clock.loop })), []);
  return s;
}
