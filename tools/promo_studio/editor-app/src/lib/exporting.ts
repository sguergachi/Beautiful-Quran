// Export settings in the editor: the file an export writes and a rough size.
// Mirrors formats.py (filename, frame_size, describe).
import type { ExportMeta, ExportOptions, Spec } from "./types.ts";

export function frameSize(s: Pick<Spec, "aspect">, aspects: Record<string, [number, number]>, scale: number): [number, number] {
  const [W, H] = aspects[s.aspect] ?? [1080, 1080];
  return [Math.round((W * scale) / 2) * 2, Math.round((H * scale) / 2) * 2];
}

/** The fps an export renders at: the video's own unless overridden; GIFs use whole-centisecond rates. */
export function exportFps(o: ExportOptions, spec: Pick<Spec, "fps">, m: ExportMeta): number {
  if (o.format === "gif") return m.gif_fps.includes(o.fps ?? 0) ? o.fps! : 25;
  return o.fps ?? spec.fps;
}

export function exportFilename(name: string, spec: Pick<Spec, "aspect" | "fps">, o: ExportOptions, m: ExportMeta, aspects: Record<string, [number, number]>): string {
  const [W, H] = frameSize(spec, aspects, o.scale);
  let suffix = Math.abs(o.scale - 1) > 1e-3 ? `-${Math.min(W, H)}p` : "";
  if (o.fps && o.fps !== spec.fps && o.format !== "gif") suffix += `-${o.fps}fps`;
  return `${name}${suffix}.${m.formats[o.format].ext}`;
}

/** "HEVC · 720p · 30 fps", like formats.describe. */
export function exportLabel(spec: Pick<Spec, "aspect" | "fps">, o: ExportOptions, aspects: Record<string, [number, number]>): string {
  const [W, H] = frameSize(spec, aspects, o.scale);
  const name = { h264: "H.264", hevc: "HEVC", av1: "AV1", vp9: "WebM", prores: "ProRes", gif: "GIF" }[o.format];
  return [name, `${Math.min(W, H)}p`, ...(o.fps && o.fps !== spec.fps ? [`${o.fps} fps`] : [])].join(" · ");
}

/** Rough bytes: measured bitrates for a typical promo, scaled by pixels and frame rate. */
export function estimateBytes(spec: Pick<Spec, "aspect" | "fps" | "duration">, o: ExportOptions, m: ExportMeta, aspects: Record<string, [number, number]>): number {
  const [W, H] = frameSize(spec, aspects, o.scale), f = m.formats[o.format], fps = exportFps(o, spec, m);
  const px = (W * H) / (1080 * 1080);
  let kbps: number;
  if (f.mbps) kbps = f.mbps[o.quality] * 1000 * px * (fps / 60);
  else if (f.kbs) kbps = f.kbs * 8 * ((W * H) / (540 * 540)) * (fps / 25) * f.rate![o.quality];
  else kbps = m.h264_high_kbps * f.rate![o.quality] * px ** 0.8 * (fps / 60) ** 0.35;   // fewer frames each carry more change
  const audio = !f.audio || !o.audio_kbps ? 0 : f.audio === "pcm" ? 1536 : Math.min(o.audio_kbps, f.audio === "opus" ? 256 : 320);
  return ((kbps + audio) * 1000 * spec.duration) / 8;
}

export function fmtBytes(b: number): string {
  if (b >= 1e9) return `${(b / 1e9).toFixed(1)} GB`;
  if (b >= 1e6) return `${(b / 1e6).toFixed(b >= 1e8 ? 0 : 1)} MB`;
  return `${Math.max(1, Math.round(b / 1e3))} kB`;
}

export const scaleLabel = (scale: number) =>
  Math.abs(scale - 1) < 1e-3 ? "1×" : Math.abs(scale - 2) < 1e-3 ? "2×" : Math.abs(scale - 4 / 3) < 1e-3 ? "1⅓×" : Math.abs(scale - 2 / 3) < 1e-3 ? "⅔" : Math.abs(scale - 0.5) < 1e-3 ? "½" : Math.abs(scale - 1 / 3) < 1e-3 ? "⅓" : `${scale}×`;
