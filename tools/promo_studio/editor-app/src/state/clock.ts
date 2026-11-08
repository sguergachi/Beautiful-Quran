// The playhead and the media it drives. Time lives outside React: the
// preview, the playhead and the timecode subscribe and update at frame rate
// without re-rendering the app; React reads it through useTime() at a
// gentler rate.
import { useEffect, useState } from "react";
import { clamp, soundGain, sourceTime } from "../lib/model.ts";
import type { Spec } from "../lib/types.ts";
import { sound } from "./sound.ts";

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

// A small clock difference is normal between native video and the timeline.
// Seeking it away replays/skips scroll frames inside an otherwise smooth phone
// move. Converge quietly on muted video; cuts, scrubs and large stalls still
// seek exactly. The take's audio keeps its original speed and pitch.
function followRecording(t: number, exact: boolean) {
  const v = media.screen, error = t - v.currentTime;
  if (!exact && v.seeking) return;
  let rate = 1;
  if (exact || Math.abs(error) > 0.3) {
    if (Math.abs(error) > 0.001) seekMedia(v, t);
  } else if (Math.abs(error) > 0.015) {
    rate = clamp(1 + error * 0.5, 0.96, 1.04);
  }
  if (Math.abs(v.playbackRate - rate) > 0.001) v.playbackRate = rate;
}
// The background loops on its own while playing: a seek there decodes from a
// keyframe and blanks the plate for frames, so drift under a second is eased
// away with the rate instead. Paused, scrubbed or jumped, it lands exactly.
function followPlate(t: number, exact: boolean) {
  const v = media.plate, d = v.duration;
  let error = t - v.currentTime;
  if (d && Number.isFinite(d)) error -= d * Math.round(error / d);     // the loop wraps
  let rate = 1;
  if (exact || !d || !Number.isFinite(d)) {
    if (Math.abs(error) > 0.001) seekMedia(v, t);
  } else if (v.seeking) {
    return;
  } else if (Math.abs(error) > 1) {
    seekMedia(v, t);
  } else if (Math.abs(error) > 0.04) {
    rate = clamp(1 + error * 0.5, 0.9, 1.1);
  }
  if (Math.abs(v.playbackRate - rate) > 0.001) v.playbackRate = rate;
}
// While Web Audio plays the decoded sound, the <audio> element stays silent and still.
const bufferedSound = () => clock.playing && sound.playing;
for (const m of [media.screen, media.plate, media.audio]) {
  m.addEventListener("loadeddata", () => {
    clock.sync(true);
    if (clock.playing && !clock._priming && (m === media.plate || (clock._piece >= 0 && !(m === media.audio && bufferedSound())))) m.play().catch(() => {});
    clock.emitMedia();
  });
  m.addEventListener("seeked", () => {
    const t = want.get(m);
    if (t != null) { want.delete(m); m.currentTime = t; }
    clock.emitMedia();
  });
}

type Ctx = { spec: Spec | null; hasClip: boolean; hasPlate: boolean; offset: number; onset: number; audio?: string | null };
let ctx: () => Ctx = () => ({ spec: null, hasClip: false, hasPlate: false, offset: 0, onset: 0 });

// The take's sound runs through one gain node so the preview hears the spec's
// gain and fades as the export will (a media element's volume stops at 1):
// the decoded buffer when it is ready, the <audio> element until then. The
// graph resumes on the first play, inside the user's gesture.
let elementRouted = false;
function startSound() {
  const ac = sound.context();
  if (ac && !elementRouted) {
    try { ac.createMediaElementSource(media.audio).connect(sound.gain!); elementRouted = true; } catch { /* plays direct */ }
  }
  sound.resume();
}
function setGain(t: number, jump: boolean) {
  const { spec, onset } = ctx();
  if (!spec) return;
  const g = soundGain(spec, t, onset);
  if (!sound.gain) { media.audio.volume = clamp(g, 0, 1); return; }
  sound.setGain(g, jump);
}
const canBuffer = () => { const c = ctx(); return c.hasClip && sound.has(c.audio ?? null); };
// The pieces as a key: an edit while playing reschedules the sound, a key drag does not.
const editKey = (spec: Spec | null) => spec ? JSON.stringify(spec.edit) : "";
// A take that cannot play through its next moments yet (or whose sound is
// still decoding) holds the playhead briefly, so playback starts clean.
const PRIME_MS = 2500;

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
  _go: 0,            // bumps on every play/pause: a primed start from an older press is dropped
  _edit: "",         // the edit the sound was scheduled for
  _hold: false,      // hold the playhead at its start until the sound is heard
  _priming: false,   // play pressed, media not started yet: nothing may start on its own

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
    // Into the next piece where it carries straight on (a split, no gap) the
    // recording keeps playing; only a real jump in the take seeks.
    const prev = this._piece, next = spec.edit[piece], last = spec.edit[prev];
    const flows = prev >= 0 && piece === prev + 1 && !!next && !!last && !next.gap && Math.abs(next.in - last.out) < 1e-3;
    force = force || (piece !== prev && !flows);
    this._piece = piece;
    const buffered = bufferedSound();
    if (hasClip && piece >= 0) {
      followRecording(src, force || !this.playing);
      if (!buffered && Math.abs(media.audio.currentTime - src) > (force ? 0.001 : 0.25)) seekMedia(media.audio, src);
      if (this.playing && force && !this._priming) {
        if (media.screen.paused) media.screen.play().catch(() => {});
        if (!buffered && media.audio.paused) media.audio.play().catch(() => {});
      }
    } else if (piece < 0) {
      media.screen.pause(); media.audio.pause();
      media.screen.playbackRate = 1;
    }
    if (hasPlate) followPlate(this.plateTime(this.t), force || !this.playing);
  },
  seek(t: number) {
    this.t = clamp(t, 0, this.duration());
    this._wall0 = performance.now(); this._t0 = this.t;
    if (this.playing) { this._prime(); this.emit(); return; }      // land the picture, then let the sound join it
    this.sync(true);
    this.emit();
  },
  play() {
    const { spec } = ctx();
    if (!spec || this.playing) return;
    if (this.t >= spec.duration - 0.05) this.t = 0;
    this.playing = true;
    startSound();
    this.emitState();
    this._prime();
  },
  // Playback starts with the picture: the recording is landed on the playhead
  // and set playing (a cold decoder can take most of a second to deliver its
  // first frames), the playhead holds meanwhile, and once frames flow the
  // sound is scheduled to be heard exactly with the frame on screen. Nothing
  // starts out of step, so nothing has to seek to catch up.
  _prime() {
    const go = ++this._go;
    cancelAnimationFrame(this._raf);
    this._priming = true;
    sound.stop(); media.audio.pause(); media.screen.pause();
    this.sync(true);
    const { hasClip } = ctx();
    const picture = hasClip && this._piece >= 0, mock = media.screen.readyState === undefined;    // tests drive bare media stand-ins
    // first the sound's decode (the picture waits on the playhead), then the picture's first frames
    const heardReady = () => !hasClip || canBuffer() || !sound.ctx || !ctx().audio;
    let src0 = media.screen.currentTime, rolling = false;
    const roll = () => { rolling = true; src0 = media.screen.currentTime; if (picture) media.screen.play().catch(() => {}); };
    const flowing = () => !picture || mock || ((media.screen.readyState ?? 4) >= 3 && !media.screen.seeking && !media.screen.paused &&
      media.screen.currentTime > src0 + 0.02);
    const begin = () => { if (go === this._go && this.playing) this._begin(picture, src0); };
    if (heardReady()) { roll(); if (flowing()) return begin(); }
    const until = performance.now() + PRIME_MS;
    const wait = () => {
      if (go !== this._go || !this.playing) return;
      const late = performance.now() > until;
      if (!rolling && (heardReady() || late)) roll();
      if (rolling && (flowing() || late)) return begin();
      setTimeout(wait, 8);
    };
    setTimeout(wait, 8);
  },
  _begin(picture: boolean, src0: number) {
    const { spec, hasPlate } = ctx();
    this._priming = false;
    if (!spec) return;
    // where the picture actually is now: the playhead joins it
    if (picture && !media.screen.paused && media.screen.currentTime > src0) this.t = clamp(this.t + (media.screen.currentTime - src0), 0, this.duration());
    this._edit = editKey(spec);
    const joined = canBuffer() ? sound.startFrom(spec, this.t) : null;
    if (joined) {
      // the sound is scheduled for where the picture will be when it is heard; until then the playhead runs on with the picture
      this._t0 = joined.t; this._wall0 = joined.at; this._hold = !picture;
      media.audio.pause();
    } else {
      this._t0 = this.t; this._wall0 = performance.now(); this._hold = false;
      if (picture) { this.sync(true); if (media.audio.paused) media.audio.play().catch(() => {}); }
    }
    setGain(this.t, true);
    if (hasPlate) media.plate.play().catch(() => {});
    this._raf = requestAnimationFrame(() => this._tick());
  },
  /** Reschedule the decoded sound from the playhead without restarting the picture (a loop, an edit while playing). */
  _rejoin() {
    const { spec } = ctx();
    if (!spec || !canBuffer()) { sound.stop(); this._t0 = this.t; this._wall0 = performance.now(); return; }
    this._edit = editKey(spec);
    const j = sound.startFrom(spec, this.t);
    this._t0 = j.t; this._wall0 = j.at;
  },
  pause() {
    if (!this.playing) return;
    this.playing = false;
    this._priming = false;
    this._go++;
    cancelAnimationFrame(this._raf);
    sound.stop();
    media.screen.pause(); media.audio.pause(); media.plate.pause();
    media.screen.playbackRate = 1; media.plate.playbackRate = 1;
    this.emitState(); this.emit();
  },
  toggle() { this.playing ? this.pause() : this.play(); },
  /** The preview's sound level now: the gain node's, or the element volume without Web Audio. */
  gain() { return sound.level() ?? media.audio.volume; },
  setLoop(on: boolean) { this.loop = on; this.emitState(); },
  _tick() {
    if (!this.playing) return;
    // The playhead runs on the wall clock, eased onto the sound card's clock
    // while the decoded sound plays: picture and sound never part, and
    // nothing seeks to rejoin them. (A start without picture holds until the
    // sound is heard.)
    const now = performance.now();
    this.t = this._t0 + (this._hold ? Math.max(0, now - this._wall0) : now - this._wall0) / 1000;
    const heard = sound.heard();
    if (heard != null && now >= this._wall0) {
      const err = heard - this.t;
      if (Math.abs(err) > 0.1) { this._wall0 = now - (heard - this._t0) * 1000; this.t = heard; }
      else { this._wall0 -= err * 50; this.t += err * 0.05; }
    }
    const d = this.duration();
    if (this.t >= d) {
      if (!this.loop) { this.t = d; this.pause(); return; }
      this.t = 0; this.sync(true); this._rejoin(); this._hold = false;
      setGain(0, true);
    } else if (sound.playing && editKey(ctx().spec) !== this._edit) {
      this._rejoin();                         // the edit changed under the playhead
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

// For diagnosing playback from the browser console (and the playback harness).
if (typeof window !== "undefined") (window as unknown as { __promo: unknown }).__promo = { clock, media, sound };
