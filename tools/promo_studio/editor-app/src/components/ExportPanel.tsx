// Export: the file to write (format, size, frame rate, quality, sound), a
// size estimate, and this video's earlier exports. The settings are the
// editor's, remembered between sessions, and apply to every export.
import type { ExportFormat, ExportOptions, ExportQuality } from "../lib/types.ts";
import { estimateBytes, exportFilename, exportFps, fmtBytes, frameSize, scaleLabel } from "../lib/exporting.ts";
import { cancelJob, exportOptions, exportVideo, openPanel, setExport, useStore } from "../state/store.ts";
import { Drawer } from "./Panels.tsx";
import { Button, Field, Icon, Progress, Segmented, Select } from "./ui.tsx";

const PRESETS: [string, string, Partial<ExportOptions>][] = [
  ["Social", "Instagram, X, WhatsApp, YouTube", { format: "h264", quality: "high", scale: 1, fps: null, audio_kbps: 256 }],
  ["Small", "720p, 30 fps: messages and email", { format: "h264", quality: "balanced", scale: 2 / 3, fps: 30, audio_kbps: 128 }],
  ["Web", "AV1: the smallest good-looking file", { format: "av1", quality: "high", scale: 1, fps: null, audio_kbps: 192 }],
  ["Edit master", "ProRes 422 HQ with PCM sound", { format: "prores", quality: "best", scale: 1, fps: null, audio_kbps: 256 }],
  ["GIF", "540p, 25 fps, silent loop", { format: "gif", quality: "balanced", scale: 0.5, fps: 25, audio_kbps: 0 }],
];
const QUALITY: Record<ExportQuality, string> = { best: "Best", high: "High", balanced: "Balanced", small: "Small" };

const same = (o: ExportOptions, p: Partial<ExportOptions>) =>
  (Object.keys(p) as (keyof ExportOptions)[]).every((k) => (k === "scale" ? Math.abs(o.scale - (p.scale ?? 0)) < 1e-3 : o[k] === p[k]));

function ago(mtime: number) {
  const s = Date.now() / 1000 - mtime;
  return s < 90 ? "just now" : s < 5400 ? `${Math.round(s / 60)} min ago` : s < 129600 ? `${Math.round(s / 3600)} h ago` : `${Math.round(s / 86400)} d ago`;
}

export function ExportPanel() {
  const meta = useStore((s) => s.meta)!, spec = useStore((s) => s.spec), name = useStore((s) => s.name);
  const specs = useStore((s) => s.specs), jobs = useStore((s) => s.jobs), geo = useStore((s) => s.geo);
  useStore((s) => s.exportOpts);                      // re-render on changes
  const o = exportOptions(), m = meta.export, f = m.formats[o.format];
  const status = specs.find((s) => s.name === name);
  const ready = specs.filter((s) => s.take && !s.take_short);
  const live = jobs.filter((j) => j.kind === "render" && (j.state === "running" || j.state === "queued"));
  const mine = live.find((j) => j.name === name);
  const pick = (format: ExportFormat) => setExport({
    format,
    ...(format === "gif" ? { fps: m.gif_fps.includes(o.fps ?? 0) ? o.fps : 25, scale: o.scale > 0.5 ? 0.5 : o.scale, audio_kbps: 0 }
      : { fps: o.format === "gif" ? null : o.fps, audio_kbps: format === "vp9" ? Math.min(256, o.audio_kbps || 256) : o.audio_kbps || 256 }),
  });

  if (!spec || !name) {
    return <Drawer title="Export" onClose={() => openPanel(null)} width={460}><p className="text-mute">Open a video to export it.</p></Drawer>;
  }
  const [W, H] = frameSize(spec, meta.aspects, o.scale), fps = exportFps(o, spec, m);
  const file = exportFilename(name, spec, o, m, meta.aspects);
  const existing = status?.exports?.find((e) => e.file === file);
  const bytes = estimateBytes(spec, o, m, meta.aspects);
  const noTake = !geo?.clip, short = !!status?.take_short;
  const qualityHint = f.q_names ? `ProRes ${f.q_names[o.quality]}` : f.q_label === "colours" ? `${f.q[o.quality]} colours` : `${f.label.split(" · ")[1] ?? f.label} ${f.q_label} ${f.q[o.quality]}`;
  const fpsOptions: [string, string][] = o.format === "gif"
    ? m.gif_fps.map((x) => [String(x), `${x}`])
    : [["video", `${spec.fps} (video)`], ...m.fps.filter((x) => x !== spec.fps).sort((a, b) => b - a).map((x): [string, string] => [String(x), String(x)])];
  const soundOptions: [number, string][] = !f.audio ? [[0, "None: GIFs are silent"]]
    : f.audio === "pcm" ? [[256, "PCM, 16-bit 48 kHz"], [0, "No sound"]]
    : [...m.audio_kbps.filter((k) => k && (f.audio !== "opus" || k <= 256)).sort((a, b) => b - a).map((k): [number, string] => [k, `${f.audio === "opus" ? "Opus" : "AAC"} ${k} kbps`]), [0, "No sound"]];

  return (
    <Drawer title="Export" onClose={() => openPanel(null)} width={480}>
      <div className="mb-4 flex flex-wrap gap-1.5">
        {PRESETS.map(([label, hint, p]) => (
          <button key={label} title={hint} onClick={() => setExport(p)}
            className={`rounded-full border px-2.5 py-1 text-[12px] ${same(o, p) ? "border-gold/70 bg-gold/15 text-ink" : "border-line text-mute hover:border-line2 hover:text-ink"}`}>{label}</button>
        ))}
      </div>

      <Field label="Format" hint={f.hint}>
        <Select value={o.format} onChange={(v) => pick(v as ExportFormat)} options={(Object.keys(m.formats) as ExportFormat[]).map((k) => [k, m.formats[k].label])} />
      </Field>
      <Field label="Resolution">
        <Select value={String(m.scales.find((x) => Math.abs(x - o.scale) < 1e-3) ?? o.scale)} onChange={(v) => setExport({ scale: +v })}
          options={m.scales.map((x): [string, string] => { const [w, h] = frameSize(spec, meta.aspects, x); return [String(x), `${w} × ${h}  (${scaleLabel(x)})`]; })} />
      </Field>
      <Field label="Frame rate">
        <Segmented size="sm" value={o.format === "gif" ? String(fps) : o.fps == null || o.fps === spec.fps ? "video" : String(o.fps)}
          onChange={(v) => setExport({ fps: v === "video" ? null : +v })} options={fpsOptions} />
      </Field>
      <Field label="Quality" hint={qualityHint}>
        <Segmented size="sm" value={o.quality} onChange={(quality) => setExport({ quality })} options={m.qualities.map((q) => [q, QUALITY[q]])} />
      </Field>
      <Field label="Sound" hint={f.audio && o.audio_kbps ? "The take's own sound, cut with the picture and normalised to −16 LUFS." : undefined}>
        <Select value={f.audio ? (f.audio === "pcm" && o.audio_kbps ? 256 : o.audio_kbps) : 0} onChange={(v) => setExport({ audio_kbps: +v })} disabled={!f.audio} options={soundOptions} />
      </Field>

      <div className="mt-4 rounded-lg border border-line bg-panel2 p-3">
        <div className="flex flex-wrap items-baseline gap-x-2">
          <span className="num text-[15px] text-ink" title="A rough estimate: busy motion makes bigger files">≈ {fmtBytes(bytes)}</span>
          <span className="num text-[12px] text-mute">{W} × {H} · {fps} fps · {spec.duration.toFixed(1)} s{f.audio && o.audio_kbps ? "" : " · silent"}</span>
        </div>
        <p className="mt-1.5 break-all text-[11.5px] text-faint">{meta.out_dir}/<span className="text-mute">{file}</span></p>
        {existing && <p className="mt-1 text-[11.5px] text-gold">Replaces the copy exported {ago(existing.mtime)} ({fmtBytes(existing.bytes)}).</p>}
        {(noTake || short) && <p className="mt-1.5 text-[12px] text-orange">{noTake ? "Record a take before exporting." : "The edit runs past the take: record the full range first."}</p>}
        {mine ? (
          <div className="mt-3">
            <div className="mb-1 flex items-center gap-2 text-[12px]"><span className="min-w-0 flex-1 truncate text-ink">{mine.title}</span>
              <Button size="sm" variant="ghost" onClick={() => cancelJob(mine.id)}>Cancel</Button></div>
            <Progress value={mine.progress} indeterminate={mine.state === "queued"} />
            <p className="num mt-1 text-[11.5px] text-mute">{mine.msg}</p>
          </div>
        ) : (
          <div className="mt-3 flex flex-wrap gap-2">
            <Button variant="primary" disabled={noTake || short} onClick={() => exportVideo()}>{Icon.exportIcon} Export</Button>
            <Button variant="ghost" disabled={!ready.length} title={ready.map((s) => s.title || s.name).join("\n")}
              onClick={() => { for (const s of ready) exportVideo(s.name); }}>Export all {ready.length} with a take</Button>
          </div>
        )}
        {live.length > (mine ? 1 : 0) && <p className="mt-2 text-[11.5px] text-faint">{live.length - (mine ? 1 : 0)} other export{live.length - (mine ? 1 : 0) > 1 ? "s" : ""} in the queue; one runs at a time.</p>}
      </div>

      {!!status?.exports?.length && (
        <>
          <h3 className="mb-2 mt-5 text-[11px] font-semibold uppercase tracking-wider text-mute">Exports of this video</h3>
          <ul className="space-y-1.5">
            {status.exports.map((e) => (
              <li key={e.file} className={`flex flex-wrap items-center gap-x-3 gap-y-0.5 rounded-md border px-2.5 py-1.5 text-[12px] ${e.file === file ? "border-gold/40" : "border-line"}`}>
                <span className="min-w-0 flex-1 break-all text-ink">{e.file}</span>
                <span className="num text-faint">{fmtBytes(e.bytes)} · {ago(e.mtime)}</span>
                <a className="text-gold hover:underline" target="_blank" href={`/media/out/${e.file}?${e.mtime}`}>Open ↗</a>
                <a className="text-mute hover:text-ink" href={`/media/out/${e.file}?download=1&${e.mtime}`}>Download</a>
              </li>
            ))}
          </ul>
        </>
      )}
    </Drawer>
  );
}
