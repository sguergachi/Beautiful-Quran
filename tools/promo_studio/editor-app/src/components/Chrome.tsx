// The app's frame: top bar, the video list, toasts.
import { useRef, useState } from "react";
import { exportLabel } from "../lib/exporting.ts";
import { copyTitle, searchText, videoSlug } from "../lib/videos.ts";
import type { SpecStatus } from "../lib/types.ts";
import {
  createVideo, deleteVideo, exportOptions, exportVideo, mutate, open, openPanel, redo, renameVideo, revealInspector, save, setUI, undo, useStore,
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
            <span className="job-progress h-1.5 w-10 overflow-hidden rounded-full bg-line"><span className="block h-full w-full origin-left bg-orange transition-transform duration-500 ease-linear" style={{ transform: `scaleX(${Math.max(0, Math.min(1, progress))})` }} /></span>
            {live.length}
          </span>
        )}
      </Button>
      <Button variant="ghost" size="icon" onClick={() => openPanel("help")} title="Shortcuts (?)">?</Button>
      <Button variant="primary" disabled={!spec} active={panel === "export"} onClick={() => openPanel("export")} title="Format, size and quality, then export (Ctrl E)">
        {Icon.exportIcon}{renderJob ? `Exporting ${Math.round(renderJob.progress * 100)}%` : "Export…"}
      </Button>
      <Button size="icon" variant="ghost" active={!ui.hideR} onClick={() => ui.hideR ? revealInspector() : setUI({ hideR: true })} title="Inspector · inspect the current selection (Ctrl .)">{Icon.sideR}</Button>
    </header>
  );
}

type VideoAction = { kind: "new" } | { kind: "copy" | "rename" | "delete"; source: SpecStatus };

function VideoItem({ s, active, onAction }: { s: SpecStatus; active: boolean; onAction: (action: VideoAction) => void }) {
  const reciter = s.app ? useStore.getState().meta?.reciters[s.app.reciter]?.split(" ").pop() : "";
  const menu = (e: React.MouseEvent) => openMenu(e, [
    { label: "Open", run: () => open(s.name) },
    { label: "Duplicate…", run: () => onAction({ kind: "copy", source: s }) },
    { label: "Rename…", run: () => onAction({ kind: "rename", source: s }) },
    ...(s.exports?.length ? [{ label: "Open the latest export ↗", run: () => window.open(`/media/out/${s.exports![0].file}?${s.exports![0].mtime}`, "_blank") }] : []),
    { label: `Export · ${exportLabel({ aspect: s.aspect ?? "1:1", fps: s.fps ?? 60 }, exportOptions(), useStore.getState().meta!.aspects)}`, disabled: !s.take || s.take_short, run: () => exportVideo(s.name) },
    { label: "Export settings…", run: () => { if (s.name !== useStore.getState().name) open(s.name); openPanel("export"); } },
    "-",
    { label: "Delete video…", danger: true, run: () => onAction({ kind: "delete", source: s }) },
  ], e.currentTarget as HTMLElement);
  return (
    <li data-video={s.name} aria-current={active ? "true" : undefined} onClick={() => open(s.name)} onContextMenu={menu} tabIndex={0} onKeyDown={(e) => { if (e.target === e.currentTarget && e.key === "Enter") open(s.name); }}
      className={`group cursor-pointer rounded-lg px-2.5 py-2 ${active ? "bg-raised ring-1 ring-line2" : "hover:bg-panel2"}`}>
      <div className="flex items-center gap-1.5">
        <span className="min-w-0 flex-1 truncate font-medium" title={s.title || s.name}>{s.title || s.name}</span>
        <button onClick={(e) => { e.stopPropagation(); menu(e); }} title="Video actions" aria-label={`Actions for ${s.title || s.name}`} className="grid size-7 shrink-0 place-items-center rounded text-faint hover:bg-raised hover:text-ink">{Icon.dots}</button>
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

function VideoForm({ action, onClose }: { action: VideoAction; onClose: (done?: boolean) => void }) {
  const specs = useStore((s) => s.specs), source = action.kind === "new" ? null : action.source;
  const label = { new: "New video", copy: "Duplicate video", rename: "Rename video", delete: "Delete video" }[action.kind];
  const [title, setTitle] = useState(action.kind === "copy" ? copyTitle(source!.title || source!.name, specs.map((s) => s.name)) : source?.title || source?.name || "");
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  const name = videoSlug(title), deleting = action.kind === "delete";
  const issue = action.kind === "new" || action.kind === "copy"
    ? title.trim() && (!name ? "Include a letter or number in the title." : specs.some((s) => s.name === name) ? "That file name is already in use. Choose another title." : "") : "";
  return <form aria-label={label} data-project-form className="mx-2 mb-2 shrink-0 rounded-lg border border-gold/30 bg-panel2 p-2.5"
    onKeyDown={(e) => { if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); if (!busy) onClose(); } }}
    onSubmit={async (e) => {
      e.preventDefault(); if (busy || issue || (!deleting && !title.trim())) return;
      setBusy(true); setError("");
      try {
        if (deleting) await deleteVideo(source!.name);
        else if (action.kind === "rename") await renameVideo(source!.name, title);
        else await createVideo(title, source?.name);
        onClose(true);
      } catch (err) { setError((err as Error).message); setBusy(false); }
    }}>
    <h3 className="mb-2 font-medium">{label}</h3>
    {deleting ? <p className="mb-3 break-words text-[12px] leading-relaxed text-mute">Remove “{source!.title || source!.name}” from Videos? Its take and exported files stay on disk.</p> : <>
      <label className="text-[11px] text-mute">Video title
        <input autoFocus value={title} disabled={busy} onFocus={(e) => e.currentTarget.select()} onChange={(e) => { setTitle(e.target.value); setError(""); }}
          className="mt-1 h-8 w-full min-w-0 rounded border border-line bg-panel px-2 text-[13px] text-ink outline-none focus:border-gold/70" />
      </label>
      {action.kind !== "rename" && <p className="mt-1 break-all text-[11px] text-faint">{name ? `${name}.json` : "A file name is created from the title."}</p>}
    </>}
    {(issue || error) && <p role="alert" className="mt-2 break-words text-[12px] text-danger">{issue || error}</p>}
    <div className="mt-3 flex flex-wrap gap-2">
      <Button type="button" size="sm" autoFocus={deleting} disabled={busy} onClick={() => onClose()}>Cancel</Button>
      <Button type="submit" size="sm" variant={deleting ? "danger" : "primary"} disabled={busy || !!issue || (!deleting && !title.trim())}>
        {busy ? "Working…" : { new: "Create", copy: "Create copy", rename: "Save title", delete: "Delete video" }[action.kind]}
      </Button>
    </div>
  </form>;
}

export function VideoList() {
  const specs = useStore((s) => s.specs), name = useStore((s) => s.name), meta = useStore((s) => s.meta);
  const [q, setQ] = useState(""), [action, setAction] = useState<VideoAction | null>(null);
  const returnTo = useRef<HTMLElement | null>(null);
  const begin = (next: VideoAction) => { returnTo.current = document.activeElement as HTMLElement; setAction(next); };
  const close = (done = false) => {
    const previous = returnTo.current;
    setAction(null); if (done) setQ("");
    requestAnimationFrame(() => {
      if (document.activeElement !== document.body) return;
      const active = document.querySelector<HTMLElement>('[data-video][aria-current="true"]');
      (done ? active : previous?.isConnected ? previous : null)?.focus({ preventScroll: true });
      if (done) active?.scrollIntoView({ block: "nearest" });
    });
  };
  const query = searchText(q.trim());
  const shown = specs.filter((s) => !query || searchText(`${s.name} ${s.title || ""} ${s.app?.theme || ""} ${s.app?.surah || ""} ${meta?.reciters[s.app?.reciter ?? ""] || ""} ${meta?.surahs.find((v) => v.id === s.app?.surah)?.name || ""}`).includes(query));
  return (
    <div className="flex h-full min-h-0 flex-col bg-panel">
      <div className="flex shrink-0 items-center gap-2 p-2.5">
        <div className="relative min-w-0 flex-1">
          <span className="pointer-events-none absolute left-2 top-2 text-faint">{Icon.search}</span>
          <input aria-label="Search videos" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search videos"
            className="h-8 w-full rounded-md border border-line bg-panel2 pl-7 pr-7 outline-none focus:border-gold/60" />
          {q && <button title="Clear search" className="absolute right-1 top-1 grid size-6 place-items-center text-mute hover:text-ink" onClick={(e) => { setQ(""); e.currentTarget.parentElement?.querySelector("input")?.focus(); }}>{Icon.x}</button>}
        </div>
        <Button size="icon" title="New video" active={action?.kind === "new"} onClick={() => begin({ kind: "new" })}>{Icon.plus}</Button>
      </div>
      {action && <VideoForm key={`${action.kind}:${action.kind === "new" ? "" : action.source.name}`} action={action} onClose={close} />}
      <ul className="min-h-0 flex-1 space-y-0.5 overflow-y-auto px-2 pb-2">
        {shown.map((s) => <VideoItem key={s.name} s={s} active={s.name === name} onAction={begin} />)}
        {!shown.length && <li className="p-3 text-[12px] text-faint">{specs.length ? "No videos match. Try a title, chapter, reciter or theme." : "Create your first video with + above."}</li>}
      </ul>
      <div className="shrink-0 border-t border-line p-2.5">
        <p role="status" className="mb-1 text-center text-[11px] text-faint">{query ? `${shown.length} of ${specs.length}` : specs.length} video{specs.length === 1 ? "" : "s"}</p>
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
