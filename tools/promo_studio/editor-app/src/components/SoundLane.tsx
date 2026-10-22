// The Sound lane: the take's waveform through the edit, shaped by the fades,
// with a handle at the inner end of each fade. The fade in starts with the
// first sound (the take opens on a silent second), as the export does.
import { useMemo } from "react";
import { fadeAt, pieceStart } from "../lib/model.ts";
import type { Spec, Wave } from "../lib/types.ts";

const H = 34, MID = H / 2, AMP = MID - 3, TOP = 4, BOTTOM = H - 3;

// One filled outline per piece: the loudest column under each step, mirrored.
function wavePaths(spec: Spec, wave: Wave, pps: number, onset: number) {
  const step = Math.max(2, (spec.duration * pps) / 3000);
  const full: string[] = [], faded: string[] = [];
  spec.edit.forEach((p, i) => {
    const a = pieceStart(spec, i), x0 = a * pps, x1 = (a + p.out - p.in) * pps;
    const top: [number, number, number][] = [];
    for (let x = x0; x < x1 + step / 2; x += step) {
      const xe = Math.min(x, x1), t0 = p.in + (xe - x0) / pps, t1 = t0 + step / pps;
      let v = 0;
      for (let j = Math.floor(t0 * wave.rate), e = Math.min(wave.peaks.length, Math.ceil(t1 * wave.rate)); j < e; j++) v = Math.max(v, wave.peaks[j]);
      top.push([xe, (v / 255) * AMP, fadeAt(spec, xe / pps, onset)]);
    }
    if (top.length < 2) return;
    const back = [...top].reverse();
    const outline = (k: (c: [number, number, number]) => number) =>
      "M" + top.map((c) => `${c[0].toFixed(1)} ${(MID - c[1] * k(c)).toFixed(1)}`).join("L") +
      "L" + back.map((c) => `${c[0].toFixed(1)} ${(MID + c[1] * k(c)).toFixed(1)}`).join("L") + "Z";
    full.push(outline(() => 1));
    faded.push(outline((c) => c[2]));
  });
  return { full: full.join(""), faded: faded.join("") };
}

// The fade envelope as a line from silence (bottom) to full level (top).
function envelope(spec: Spec, pps: number, onset: number) {
  const d = spec.duration, ts = new Set([0, d]);
  const add = (a: number, b: number) => { for (let k = 0; k <= 24; k++) ts.add(Math.min(d, Math.max(0, a + ((b - a) * k) / 24))); };
  add(onset, onset + spec.audio.fade_in);
  add(d - spec.audio.fade_out, d);
  const pts = [...ts].sort((a, b) => a - b).map((t) => `${(t * pps).toFixed(1)} ${(BOTTOM - (BOTTOM - TOP) * fadeAt(spec, t, onset)).toFixed(1)}`);
  return "M" + pts.join("L");
}

interface Props {
  spec: Spec; wave: Wave | null; pps: number; onset: number;
  onFadeDown: (e: React.PointerEvent, side: "in" | "out") => void;   // a double press removes the fade
}

export function SoundLane({ spec, wave, pps, onset, onFadeDown }: Props) {
  const w = spec.duration * pps, { fade_in: fi, fade_out: fo } = spec.audio;
  // keyed on what the drawing reads (spec is a fresh object after every edit)
  const paths = useMemo(() => (wave ? wavePaths(spec, wave, pps, onset) : null), [wave, pps, onset, spec.duration, JSON.stringify(spec.edit), spec.audio.fade_in, spec.audio.fade_out]);
  const env = useMemo(() => envelope(spec, pps, onset), [pps, onset, spec.duration, spec.audio.fade_in, spec.audio.fade_out]);
  const handles: ["in" | "out", number, string][] = [
    ["in", Math.min(spec.duration, onset + fi), fi > 0 ? `Fade in ${fi.toFixed(2)} s from the first sound` : "Fade in: none"],
    ["out", Math.max(0, spec.duration - fo), fo > 0 ? `Fade out ${fo.toFixed(2)} s` : "Fade out: none"],
  ];
  return (
    <>
      <svg data-lanebg className="pointer-events-none absolute inset-0" width={w} height={H}>
        {paths && <path d={paths.full} fill="#6f8fb5" fillOpacity={0.18} />}
        {paths && <path d={paths.faded} fill="#8fb3e8" fillOpacity={0.75} />}
        {!paths && <line x1={0} x2={w} y1={MID} y2={MID} stroke="#8fb3e8" strokeOpacity={0.25} />}
        <path d={env} fill="none" stroke="#caa547" strokeWidth={1.25} strokeOpacity={0.9} />
        {spec.edit.slice(1).map((_, i) => <line key={i} x1={pieceStart(spec, i + 1) * pps} x2={pieceStart(spec, i + 1) * pps} y1={0} y2={H} stroke="#6a8ab0" strokeDasharray="3 3" />)}
      </svg>
      {handles.map(([side, t, label]) => (
        <div key={side} data-key onPointerDown={(e) => onFadeDown(e, side)}
          title={`${label} · drag to change (Alt: no rounding) · double-click to remove`}
          className="fade-handle group absolute top-0 z-10 -ml-[6px] flex h-full w-3 cursor-ew-resize justify-center" style={{ left: t * pps }} data-side={side}>
          <div className="mt-px size-[9px] rotate-45 rounded-[1px] border border-[#fff3cc] bg-gold shadow-[0_0_0_2px_#0006] transition-transform duration-150 ease-out group-hover:scale-125" />
        </div>
      ))}
    </>
  );
}
