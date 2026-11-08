// The take's sound as decoded PCM in Web Audio. Playback is scheduled
// sample-exact against the edit (one buffer source per piece, started at its
// video time), so nothing seeks, buffers or drifts while it plays, and the
// sound card's clock is the timeline's master: the picture follows what is
// heard. Without Web Audio (or before the decode lands) the clock falls back
// to the <audio> element.
import type { Spec } from "../lib/types.ts";

/** One scheduled stretch: `dur` seconds of take audio from `offset`, heard at `when` (context seconds). */
export interface Slot { when: number; offset: number; dur: number }

/** The buffer sources a play from timeline `t` needs, with `t` heard at context time `at`. Pure, for tests. */
export function schedule(spec: Pick<Spec, "edit">, t: number, at: number, length: number): Slot[] {
  const out: Slot[] = [];
  let acc = 0;
  for (const p of spec.edit) {
    acc += p.gap || 0;
    const a = acc, n = p.out - p.in;
    acc += n;
    if (acc <= t + 1e-9) continue;                   // already over
    const from = Math.max(a, t), offset = p.in + (from - a);
    const dur = Math.min(a + n - from, length - offset);
    if (dur > 1e-4) out.push({ when: at + (from - t), offset, dur });
  }
  return out;
}

class Sound {
  ctx: AudioContext | null = null;
  gain: GainNode | null = null;
  buffer: AudioBuffer | null = null;
  private url = "";
  private sources: AudioBufferSourceNode[] = [];
  private anchor: { ctx: number; t: number } | null = null;     // timeline `t` is heard at context time `ctx`
  private ready = new Set<() => void>();

  /** The audio graph, created once (suspended until a gesture resumes it). */
  context(): AudioContext | null {
    if (this.ctx) return this.ctx;
    const AC = (globalThis as { AudioContext?: typeof AudioContext }).AudioContext;
    if (!AC) return null;
    try {
      this.ctx = new AC({ latencyHint: "playback" });
      this.gain = this.ctx.createGain();
      this.gain.connect(this.ctx.destination);
    } catch { this.ctx = this.gain = null; }
    return this.ctx;
  }

  /** Fetch and decode the take's sound once per URL; the element plays meanwhile. */
  async load(url: string | null) {
    if (url === this.url) return;
    this.url = url ?? "";
    this.buffer = null;
    this.stop();
    if (!url) return;
    const ctx = this.context();
    if (!ctx) return;
    try {
      const data = await (await fetch(url)).arrayBuffer();
      if (this.url !== url) return;
      const buf = await ctx.decodeAudioData(data);
      if (this.url !== url) return;
      this.buffer = buf;
      for (const f of this.ready) f();
    } catch { /* the element keeps playing the sound */ }
  }
  onReady(fn: () => void) { this.ready.add(fn); return () => { this.ready.delete(fn); }; }
  has(url: string | null) { return !!this.buffer && !!url && url === this.url; }

  resume() {
    const ctx = this.context();
    if (ctx?.state === "suspended") ctx.resume().catch(() => {});
  }

  /** Start the edit so it is heard in step with a picture now at timeline `t`: the sound is
   *  scheduled from where that picture will be once it reaches the speakers. Returns that
   *  timeline time and the performance.now() at which it is heard. */
  startFrom(spec: Pick<Spec, "edit">, t: number): { t: number; at: number } {
    this.stop();
    const ctx = this.ctx!, buf = this.buffer!;
    // A short lead lets the sources start exactly on time instead of late and clipped.
    const at = ctx.currentTime + 0.06, heardAt = this.perfAt(at);
    const from = t + Math.max(0, heardAt - performance.now()) / 1000;
    for (const s of schedule(spec, from, at, buf.duration)) {
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.connect(this.gain!);
      src.start(s.when, s.offset, s.dur);
      this.sources.push(src);
    }
    this.anchor = { ctx: at, t: from };
    return { t: from, at: heardAt };
  }

  stop() {
    for (const s of this.sources) { try { s.stop(); } catch { /* never started */ } s.disconnect(); }
    this.sources = [];
    this.anchor = null;
  }

  get playing() { return !!this.anchor; }

  /** performance.now() when context time `ctxTime` reaches the speakers. */
  perfAt(ctxTime: number): number {
    const ctx = this.ctx!, ts = ctx.getOutputTimestamp?.();
    if (ts && ts.contextTime && ts.performanceTime) return ts.performanceTime + (ctxTime - ts.contextTime) * 1000;
    const latency = (ctx.outputLatency || 0) + (ctx.baseLatency || 0);
    return performance.now() + (ctxTime - ctx.currentTime + latency) * 1000;
  }

  /** The timeline time being heard right now, or null when not playing. */
  heard(): number | null {
    if (!this.anchor) return null;
    return this.anchor.t + (performance.now() - this.perfAt(this.anchor.ctx)) / 1000;
  }

  setGain(g: number, jump: boolean) {
    if (!this.ctx || !this.gain) return;
    const at = this.ctx.currentTime, p = this.gain.gain;
    p.cancelScheduledValues(at);
    if (jump || g === 0) p.setValueAtTime(g, at);
    else p.setTargetAtTime(g, at, 0.012);           // per-frame steps without zipper noise
  }
  level() { return this.gain ? this.gain.gain.value : null; }
}

export const sound = new Sound();
