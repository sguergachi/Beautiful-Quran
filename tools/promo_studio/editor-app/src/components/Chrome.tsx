// The app's frame: top bar, the video list, toasts.
import { useState } from "react";
import { exportLabel } from "../lib/exporting.ts";
import type { SpecStatus } from "../lib/types.ts";
import {
  createVideo, deleteVideo, exportOptions, exportVideo, mutate, open, openPanel, redo, renameVideo, save, setUI, undo, useStore,
} from "../state/store.ts";
import { openMenu } from "./Menu.tsx";
import { Button, Dot, Icon, Segmented } from "./ui.tsx";

export function TopBar() {
  const { spec, meta, undo: u, redo: r, saveState, saveError, jobs, emus, ui, name, panel } = useStore();
  const live = jobs.filter((j) => j.state === "running" || j.state === "queued");
  const ready = emus.filter((e) => e.ready).length, busy = emus.filter((e) => e.job).length;
  const progress = live.length ? live.reduce((a, j) => a + j.progress, 0) / live.length : 0;
  const renderJob = live.find((j) => j.kind === "render" && j.name === name);
  const setAspect = (v: string) => mutate((raw) => { raw.aspect = v; });
  return (
    <header className="studio-header flex min-h-12 shrink-0 flex-wrap items-center gap-2 border-b border-line bg-panel px-2.5 py-2">
      <Button size="icon" variant="ghost" active={!ui.hideL} onClick={() => setUI({ hideL: !ui.hideL })} title="Videos (Ctrl B)">{Icon.side}</Button>
      <div className="studio-brand mr-2 flex shrink-0 items-center gap-2">
        <img src="/media/icon.png" alt="" className="size-6 rounded-md" />
        <span className="font-display text-[17px] tracking-tight">Promo Studio</span>
      </div>
      <div className="studio-header-divider h-6 w-px bg-line" />
      {spec && (
        <>
          <span className="studio-title ml-1 min-w-0 max-w-[340px] truncate text-[13.5px] text-ink" title={spec.title || name || ""}>{spec.title || name}</span>
          {meta && <div className="studio-aspects"><Segmented size="sm" value={spec.aspect} options={Object.keys(meta.aspects).map((a) => [a, a])} onChange={setAspect} /></div>}
          <div className="ml-1 flex">
            <Button size="icon" variant="ghost" disabled={!u.length} onClick={undo} title="Undo (Ctrl Z)">{Icon.undo}</Button>
            <Button size="icon" variant="ghost" disabled={!r.length} onClick={redo} title="Redo (Ctrl Shift Z)">{Icon.redo}</Button>
          </div>
          <button onClick={() => save()} title={saveError ?? "Saved to specs/ as you edit (Ctrl S saves now)"}
            className={`rounded px-1.5 py-0.5 text-[11.5px] ${saveState === "error" ? "bg-danger/15 text-danger" : "text-faint hover:text-mute"}`}>
            {saveState === "saved" ? "Saved" : saveState === "saving" ? "Saving…" : saveState === "dirty" ? "Editing…" : "Not saved · retry"}
          </button>
        </>
      )}
      <div className="flex-1" />
      <Button variant="subtle" onClick={() => openPanel("emulators")} title="Emulators for recording takes">
        {Icon.phone}
        <span>Emulators</span>
        <span className="emulator-summary flex items-center gap-1 text-[11.5px] text-mute">
          {emus.length ? <><Dot tone={busy ? "orange" : ready ? "green" : "gold"} />{ready} ready{busy ? ` · ${busy} busy` : ""}</> : <><Dot tone="faint" />none</>}
        </span>
      </Button>
      <Button variant="subtle" onClick={() => openPanel("jobs")} title="Exports and recordings">
        {Icon.jobs}
        <span>Jobs</span>
        {live.length > 0 && (
          <span className="flex items-center gap-1.5 text-[11.5px] text-orange">
            <span className="job-progress h-1.5 w-10 overflow-hidden rounded-full bg-line"><span className="block h-full bg-orange transition-[width]" style={{ width: `${progress * 100}%` }} /></span>
            {live.length}
          </span>
        )}
      </Button>
      <Button variant="ghost" size="icon" onClick={() => openPanel("help")} title="Shortcuts (?)">?</Button>
      <Button variant="primary" disabled={!spec} active={panel === "export"} onClick={() => openPanel("export")} title="Format, size and quality, then export (Ctrl E)">
        {Icon.exportIcon}{renderJob ? `Exporting ${Math.round(renderJob.progress * 100)}%` : "Export…"}
      </Button>
      <Button size="icon" variant="ghost" active={!ui.hideR} onClick={() => setUI({ hideR: !ui.hideR })} title="Inspector (Ctrl .)">{Icon.sideR}</Button>
    </header>
  );
}

function VideoItem({ s, active }: { s: SpecStatus; active: boolean }) {
  const reciter = s.app ? useStore.getState().meta?.reciters[s.app.reciter]?.split(" ").pop() : "";
  const menu = (e: React.MouseEvent) => openMenu(e, [
    { label: "Open", run: () => open(s.name) },
    { label: "Duplicate", run: () => { const t = prompt("Name for the copy:", `${s.name}-2`); if (t) createVideo(t, s.name); } },
    { label: "Rename", run: () => { const t = prompt("New name:", s.name); if (t) renameVideo(s.name, t); } },
    ...(s.exports?.length ? [{ label: "Open the latest export ↗", run: () => window.open(`/media/out/${s.exports![0].file}?${s.exports![0].mtime}`, "_blank") }] : []),
    { label: `Export · ${exportLabel({ aspect: s.aspect ?? "1:1", fps: s.fps ?? 60 }, exportOptions(), useStore.getState().meta!.aspects)}`, disabled: !s.take || s.take_short, run: () => exportVideo(s.name) },
    { label: "Export settings…", run: () => { if (s.name !== useStore.getState().name) open(s.name); openPanel("export"); } },
    "-",
    { label: "Delete…", danger: true, run: () => { if (confirm(`Delete "${s.name}"? Its take and exported video stay on disk.`)) deleteVideo(s.name); } },
  ]);
  return (
    <li onClick={() => open(s.name)} onContextMenu={menu} tabIndex={0} onKeyDown={(e) => { if (e.key === "Enter") open(s.name); }}
      className={`group cursor-pointer rounded-lg px-2.5 py-2 ${active ? "bg-raised ring-1 ring-line2" : "hover:bg-panel2"}`}>
      <div className="flex items-center gap-1.5">
        <span className="min-w-0 flex-1 truncate font-medium" title={s.title || s.name}>{s.title || s.name}</span>
        <button onClick={(e) => { e.stopPropagation(); menu(e); }} title="Video actions" className="shrink-0 text-faint hover:text-ink">{Icon.dots}</button>
      </div>
      {s.error ? <p className="mt-0.5 text-[11px] text-danger">⚠ {s.error}</p> : (
        <div className="mt-0.5 flex flex-wrap gap-x-2 text-[11px] text-mute">
          <span>{reciter} {s.app?.surah}:{s.app?.ayah}</span><span>{s.app?.theme}</span><span>{s.aspect}</span><span className="num">{(s.duration ?? 0).toFixed(1)}s</span>
        </div>
      )}
      <div className="mt-1 flex flex-wrap gap-x-3 text-[11px] text-faint">
        <span className="flex items-center gap-1"><Dot tone={s.take ? (s.take_short ? "orange" : "green") : "faint"} />{s.take_short ? "take too short" : s.take ? "take" : "no take"}</span>
        <span className="flex items-center gap-1"><Dot tone={s.video ? "gold" : "faint"} />{s.video ? "exported" : "not exported"}</span>
      </div>
    </li>
  );
}

export function VideoList() {
  const specs = useStore((s) => s.specs), name = useStore((s) => s.name);
  const [q, setQ] = useState("");
  const shown = specs.filter((s) => !q || `${s.name} ${s.title} ${s.app?.theme} ${s.app?.reciter}`.toLowerCase().includes(q.toLowerCase()));
  return (
    <div className="flex h-full min-h-0 flex-col bg-panel">
      <div className="flex shrink-0 items-center gap-2 p-2.5">
        <div className="relative min-w-0 flex-1">
          <span className="pointer-events-none absolute left-2 top-2 text-faint">{Icon.search}</span>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Videos"
            className="h-8 w-full rounded-md border border-line bg-panel2 pl-7 pr-2 outline-none focus:border-gold/60" />
        </div>
        <Button size="icon" title="New video" onClick={() => { const t = prompt("Name for the new video:"); if (t) createVideo(t); }}>{Icon.plus}</Button>
      </div>
      <ul className="min-h-0 flex-1 space-y-0.5 overflow-y-auto px-2 pb-2">
        {shown.map((s) => <VideoItem key={s.name} s={s} active={s.name === name} />)}
        {!shown.length && <li className="p-3 text-faint">{specs.length ? "No match." : "No videos yet."}</li>}
      </ul>
      <div className="shrink-0 border-t border-line p-2.5">
        <Button size="sm" variant="ghost" className="!h-auto min-h-7 w-full justify-center !whitespace-normal text-center" onClick={() => openPanel("export")} title="Choose the format, then export every video with a take">
          <span className="shrink-0">{Icon.exportIcon}</span>
          <span>Export videos…</span>
        </Button>
      </div>
    </div>
  );
}

export function Toasts() {
  const toasts = useStore((s) => s.toasts);
  return (
    <div className="fixed bottom-4 left-1/2 z-50 flex max-h-[calc(100dvh-2rem)] w-max max-w-[calc(100vw-2rem)] -translate-x-1/2 flex-col items-center gap-2 overflow-y-auto">
      {toasts.map((t) => (
        <div key={t.id} role={t.kind === "error" ? "alert" : "status"} className={`animate-toast flex max-w-full shrink-0 items-start gap-3 rounded-lg border px-3.5 py-2 text-[12.5px] shadow-xl shadow-black/50 ${t.kind === "error" ? "border-danger/50 bg-[#2b1a17] text-[#f3c4ba]" : t.kind === "ok" ? "border-green/40 bg-[#172520] text-[#cfe9de]" : "border-line2 bg-raised text-ink"}`}>
          <div className="max-h-40 min-w-0 max-w-[480px] overflow-y-auto break-words">{t.msg}</div>
          <button title="Dismiss message" className="shrink-0 text-mute hover:text-ink" onClick={() => useStore.setState((s) => ({ toasts: s.toasts.filter((x) => x.id !== t.id) }))}>{Icon.x}</button>
        </div>
      ))}
    </div>
  );
}
