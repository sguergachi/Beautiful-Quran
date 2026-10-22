// Slide-over panels: Emulators, Jobs, Shortcuts (Export is in ExportPanel.tsx).
import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { closeMenu } from "./Menu.tsx";
import type { Emulator, Job } from "../lib/types.ts";
import {
  bootEmulator, cancelJob, openPanel, pollJobs, prepareEmulator, stopEmulator, useStore,
} from "../state/store.ts";
import { api } from "../lib/api.ts";
import { CaptureCard, LiveScreen, emulatorTone } from "./Inspector.tsx";
import { Button, Dot, Icon, Kbd, Progress, Select } from "./ui.tsx";

export function Drawer({ title, children, onClose, width = 420, right }: { title: string; children: ReactNode; onClose: () => void; width?: number; right?: ReactNode }) {
  const ref = useRef<HTMLElement>(null), previous = useRef(document.activeElement as HTMLElement | null);
  useLayoutEffect(() => {
    closeMenu();
    ref.current?.querySelector<HTMLButtonElement>("[data-drawer-close]")?.focus();
    return () => { requestAnimationFrame(() => { if (document.activeElement === document.body && previous.current?.isConnected) previous.current.focus({ preventScroll: true }); }); };
  }, []);
  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-black/30" onPointerDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <aside ref={ref} role="dialog" aria-modal="true" aria-label={title} style={{ width }}
        onKeyDown={(e) => {
          if (e.key !== "Tab") return;
          const items = [...ref.current!.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')].filter((el) => el.getClientRects().length);
          const first = items[0], last = items.at(-1);
          if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
          else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
        }}
        className="drawer flex h-full max-w-full min-w-0 flex-col border-l border-line bg-panel shadow-2xl shadow-black/60">
        <div className="flex h-12 shrink-0 items-center gap-2 border-b border-line px-4">
          <h2 className="text-[15px] font-semibold">{title}</h2>
          <div className="flex-1" />
          {right}
          <Button data-drawer-close size="icon" variant="ghost" onClick={onClose} title="Close (Esc)">{Icon.x}</Button>
        </div>
        <div className="min-h-0 min-w-0 flex-1 overflow-y-auto break-words p-4">{children}</div>
      </aside>
    </div>
  );
}

function statusOf(e: Emulator): { text: string; tone: ReturnType<typeof emulatorTone> } {
  if (e.kind === "pool") return { text: "Managed by the test pool", tone: "faint" };
  if (e.error) return { text: "Not responding", tone: "danger" };
  if (!e.booted) return { text: "Booting", tone: "faint" };
  if (e.job) return { text: `${e.job.title}: ${e.job.msg}`, tone: "orange" };
  if (!e.app) return { text: "Needs the app", tone: "gold" };
  return { text: "Ready to record", tone: "green" };
}

export function EmulatorsPanel() {
  const { emus, booting, emusLoaded, meta } = useStore();
  const [cores, setCores] = useState("6");
  return (
    <Drawer title="Emulators" onClose={() => openPanel(null)} width={460}
      right={<Button size="sm" variant="primary" onClick={() => bootEmulator(+cores)}>{Icon.plus} Start one</Button>}>
      <p className="mb-4 text-[12.5px] leading-relaxed text-mute">
        Takes are recorded on Android emulators, picture and sound together. Each one runs the real app; several can record in parallel.
        A new emulator needs the app once (<em>Prepare</em>): it installs it and lets it download the recitation timings.
        Record one take at a time for the smoothest ink; parallel captures share the host's CPU and GPU.
      </p>
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-line bg-panel2 p-2.5">
        <span className="text-mute">New emulators get</span>
        <div className="w-28"><Select value={cores} options={[["4", "4 cores"], ["6", "6 cores"], ["8", "8 cores"]]} onChange={setCores} /></div>
        <span className="text-[11.5px] text-faint">6 recommended for a single capture</span>
      </div>
      {!meta?.apk_exists && (
        <p className="mb-4 rounded border border-gold/40 bg-gold/10 p-2.5 text-[12px] text-gold">No release APK at {meta?.apk}. Build one (<code>./gradlew assembleRelease</code>) before preparing an emulator.</p>
      )}
      {booting.map((j) => <JobRow key={j.id} job={j} />)}
      {emusLoaded && !emus.length && !booting.length && (
        <div className="rounded-lg border border-dashed border-line2 p-6 text-center text-mute">
          No emulator is running.<br />
          <Button className="mt-3" variant="primary" onClick={() => bootEmulator(+cores)}>{Icon.plus} Start an emulator</Button>
        </div>
      )}
      <div className="space-y-3">
        {emus.map((e) => {
          const st = statusOf(e);
          return (
            <div key={e.serial} className="flex gap-3 rounded-lg border border-line bg-panel2 p-3">
              {e.booted ? <LiveScreen serial={e.serial} width={70} every={e.job ? 1000 : 2500} /> : <div className="h-[155px] w-[70px] rounded-md border border-line bg-black" />}
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">{e.serial}</span>
                  <span className="rounded bg-raised px-1.5 text-[10.5px] text-mute">{e.kind === "pool" ? "test pool" : "capture"}</span>
                </div>
                <p className="mt-0.5 text-[11.5px] text-faint">{[e.cores && `${e.cores} cores`, e.size].filter(Boolean).join(" · ")}{e.kind === "pool" && " · fewer cores: ~44 fps takes"}</p>
                <p className="mt-2 flex items-start gap-1.5 text-[12.5px]"><span className="mt-1"><Dot tone={st.tone} /></span><span className={`min-w-0 ${st.tone === "orange" ? "text-ink" : "text-mute"}`}>{st.text}</span></p>
                {e.job && <div className="mt-2"><Progress value={e.job.progress} indeterminate={e.job.state === "queued"} /></div>}
                {e.kind === "capture" && <div className="mt-3 flex flex-wrap gap-1.5">
                  {e.booted && !e.app && !e.job && <Button size="sm" variant="primary" disabled={!meta?.apk_exists} onClick={() => prepareEmulator(e.serial)}>Prepare app</Button>}
                  {e.booted && e.app && !e.job && <Button size="sm" variant="ghost" onClick={() => prepareEmulator(e.serial)} title="Reinstall the current APK">Update app</Button>}
                  {e.job && <Button size="sm" variant="ghost" onClick={() => cancelJob(e.job!.id)}>Cancel {e.job.title.toLowerCase()}</Button>}
                  {!e.job && <Button size="sm" variant="ghost" onClick={() => { if (confirm(`Stop ${e.serial}?`)) stopEmulator(e.serial); }}>Stop</Button>}
                </div>}
              </div>
            </div>
          );
        })}
      </div>
    </Drawer>
  );
}

function JobRow({ job }: { job: Job }) {
  const [open, setOpen] = useState(false);
  const [full, setFull] = useState<string[] | null>(null);
  const live = job.state === "running" || job.state === "queued";
  const tone = job.state === "error" ? "danger" : job.state === "done" ? "green" : job.state === "cancelled" ? "faint" : "orange";
  const took = job.ended && job.started ? `${Math.round(job.ended - job.started)} s` : "";
  if (job.kind === "capture" && live) return <CaptureCard job={job} compact />;
  return (
    <div className="mb-2.5 rounded-lg border border-line bg-panel2 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Dot tone={tone} />
        <span className="font-semibold">{job.title}</span>
        <span className="min-w-0 break-words text-mute">{job.name}</span>
        <div className="flex-1" />
        {live ? <Button size="sm" variant="ghost" onClick={() => cancelJob(job.id)}>Cancel</Button>
          : <span className="text-[11px] text-faint">{job.state}{took && ` · ${took}`}</span>}
      </div>
      {live && <div className="mt-2"><Progress value={job.progress} indeterminate={job.state === "queued"} /></div>}
      <p className={`num mt-1.5 break-words text-[12px] ${job.state === "error" ? "text-danger" : "text-mute"}`}>{job.state === "error" ? job.error : job.msg}</p>
      <div className="mt-1.5 flex gap-3 text-[11.5px]">
        {job.kind === "render" && job.state === "done" && job.export_exists === false && <span className="text-faint">Export deleted or moved</span>}
        {job.kind === "render" && job.state === "done" && job.export_exists !== false && typeof job.result === "string" && <>
          <a className="text-gold hover:underline" target="_blank" href={`/media/out/${job.result.split("/").pop()}?${job.ended}`}>Open the video ↗</a>
          <a className="text-mute hover:text-ink" href={`/media/out/${job.result.split("/").pop()}?download=1&${job.ended}`}>Download</a>
        </>}
        <button className="text-faint hover:text-ink" onClick={async () => { if (!open) setFull((await api.job(job.id)).log); setOpen(!open); }}>{open ? "Hide log" : "Log"}</button>
      </div>
      {open && <pre className="mt-2 max-h-56 overflow-auto whitespace-pre-wrap rounded bg-black/40 p-2 text-[11px] text-faint">{(full || job.log).join("\n") || "(empty)"}</pre>}
    </div>
  );
}

export function JobsPanel() {
  const jobs = useStore((s) => s.jobs);
  const live = jobs.filter((j) => j.state === "running" || j.state === "queued"), done = jobs.filter((j) => !live.includes(j));
  return (
    <Drawer title="Jobs" onClose={() => openPanel(null)}
      right={done.length ? <Button size="sm" variant="ghost" onClick={() => api.clearJobs().then(pollJobs)}>Clear finished</Button> : null}>
      {!jobs.length && <p className="text-mute">Nothing running. Exports and recordings show up here, with their progress and logs.</p>}
      {live.length > 0 && <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-mute">Running</h3>}
      {live.map((j) => <JobRow key={j.id} job={j} />)}
      {done.length > 0 && <h3 className="mb-2 mt-4 text-[11px] font-semibold uppercase tracking-wider text-mute">Finished</h3>}
      {done.map((j) => <JobRow key={j.id} job={j} />)}
    </Drawer>
  );
}

const SHORTCUTS: [string, string][] = [
  ["Space", "Play / pause"], ["L", "Loop"], ["← / →", "One frame (Shift: one second)"], ["↑ / ↓", "Previous / next verse or cut"],
  ["Home / End", "Start / end"], ["Click the timecode", "Type a time"], ["Drag the ruler or playhead", "Scrub (Shift snaps)"],
  ["S", "Split the recording at the playhead"], ["Delete", "Delete the selection"], ["K", "Key the camera"], ["[ / ]", "Previous / next key"],
  ["Shift / Ctrl-click a key", "Add to / remove from the selection"], ["Shift-drag a lane", "Select keys in a box (with Ctrl: add)"],
  [", / .", "Nudge selected keys a frame (Shift: ten)"], ["Ctrl A · C · V", "Select all keys · copy · paste at the playhead"],
  ["Ctrl Z · Ctrl Shift Z", "Undo · redo"], ["Ctrl S", "Save now"], ["Ctrl E", "Export"], ["Ctrl B · Ctrl .", "Hide / show the videos · the inspector"],
  ["Drag · scroll the picture", "Pan · zoom the camera"], ["Shift-scroll the picture", "Rotate"], ["Alt-drag · Alt-scroll", "Move · scale the phone"],
  ["Alt while dragging a key", "No snapping"], ["Double-click a lane", "Add a key there"], ["Right-click", "Ease, copy, split, delete"],
  ["Ctrl-scroll the timeline", "Zoom around the pointer"], ["Drag a divider", "Resize panes (double-click: reset)"],
];
export function HelpPanel() {
  return (
    <Drawer title="Shortcuts" onClose={() => openPanel(null)} width={460}>
      <dl className="shortcuts grid grid-cols-[minmax(0,180px)_minmax(0,1fr)] gap-x-3 gap-y-2">
        {SHORTCUTS.map(([k, v]) => <div key={k} className="contents"><dt><Kbd>{k}</Kbd></dt><dd className="text-mute">{v}</dd></div>)}
      </dl>
      <p className="mt-5 text-[12px] leading-relaxed text-faint">Keys stay in video time when you cut the recording. A key's ease shapes the move into it. Edits save as you go; every one can be undone.</p>
    </Drawer>
  );
}
