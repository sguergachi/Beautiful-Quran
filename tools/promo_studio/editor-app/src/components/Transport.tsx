// One row for playback, edit actions, camera shortcuts and timeline zoom.
import { useEffect, useRef, useState, type MouseEvent } from "react";
import { fmtTime, parseTime } from "../lib/model.ts";
import { clock, usePlaying } from "../state/clock.ts";
import { deleteSelection, keyCamera, preset, revealInspector, setUI, showExact, split, toast, useStore } from "../state/store.ts";
import { openMenu, type MenuItem } from "./Menu.tsx";
import { Button, Icon } from "./ui.tsx";

function Timecode() {
  const ref = useRef<HTMLSpanElement>(null);
  const duration = useStore((s) => s.spec?.duration ?? 0);
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    const show = (t: number) => { if (ref.current) ref.current.textContent = fmtTime(t); };
    show(clock.t);
    return clock.subscribe(show);
  }, [editing]);
  if (editing) {
    return (
      <input autoFocus defaultValue={clock.t.toFixed(2)} aria-label="Playhead time" className="toolbar-timecode num rounded border border-gold bg-panel2 px-1.5"
        onFocus={(e) => e.target.select()}
        onBlur={(e) => { const t = parseTime(e.target.value); if (Number.isFinite(t)) { clock.pause(); clock.seek(t); } else toast("Type seconds (12.5) or minutes:seconds (0:12.5)"); setEditing(false); }}
        onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); if (e.key === "Escape") { e.stopPropagation(); setEditing(false); } }} />
    );
  }
  return (
    <button onClick={() => setEditing(true)} title={`Playhead / ${fmtTime(duration)} · click to type a time`}
      className="toolbar-timecode num flex items-center gap-1 rounded px-1.5 text-ink hover:bg-panel2">
      <span ref={ref} className="text-[13px]" />
      <span className="toolbar-duration text-faint">/ {fmtTime(duration)}</span>
    </button>
  );
}

function KeyJump({ direction }: { direction: -1 | 1 }) {
  return <svg viewBox="0 0 16 16" className="size-4" fill="currentColor" aria-hidden="true">
    {direction === -1 ? <path d="m1 8 4-4v8zm6 0 4-4 4 4-4 4z" /> : <path d="m1 8 4-4 4 4-4 4zm10-4 4 4-4 4z" />}
  </svg>;
}

/** The same commands stay reachable when the central pane gets narrow. */
export function EditorToolbar({ pps, selection, onJumpKey }: { pps: number; selection: string; onJumpKey: (direction: -1 | 1) => void }) {
  const { playing, loop } = usePlaying();
  const hasClip = useStore((s) => !!s.geo?.clip), hasSpec = useStore((s) => !!s.spec);
  const selected = useStore((s) => !!s.sel), fit = useStore((s) => s.ui.fit);
  const cameraItems: MenuItem[] = [
    { label: "Key camera", hint: "K", run: keyCamera, disabled: !hasSpec },
    { label: "Push in", run: () => preset("push"), disabled: !hasSpec },
    { label: "Pull back", run: () => preset("pull"), disabled: !hasSpec },
    { label: "Punch in", run: () => preset("punch"), disabled: !hasSpec },
    "-", { label: "Exact frame", run: showExact, disabled: !hasClip },
  ];
  const tools: MenuItem[] = [
    { label: selected ? "Inspect selection" : "Inspector", run: revealInspector }, "-",
    { head: "Edit" }, { label: "Split at playhead", hint: "S", run: () => split(), disabled: !hasSpec },
    { label: "Delete selection", hint: "Del", run: deleteSelection, disabled: !selected, danger: true },
    { label: "Previous key", hint: "[", run: () => onJumpKey(-1) },
    { label: "Next key", hint: "]", run: () => onJumpKey(1) }, "-", { head: "Camera" }, ...cameraItems,
  ];
  const menu = (e: MouseEvent<HTMLButtonElement>, items: MenuItem[]) => {
    const rect = e.currentTarget.getBoundingClientRect();
    openMenu({ clientX: rect.left, clientY: rect.bottom + 6, preventDefault: () => e.preventDefault(), stopPropagation: () => e.stopPropagation() }, items, e.currentTarget);
  };
  return <div className="editor-toolbar transport timeline-toolbar" role="group" aria-label="Editor controls">
    <div className="toolbar-row">
      <div className="toolbar-group" role="group" aria-label="Playback">
        <Button size="icon" variant="primary" disabled={!hasSpec} onClick={() => clock.toggle()} aria-label={playing ? "Pause" : "Play"} title="Play / pause (Space)">{playing ? Icon.pause : Icon.play}</Button>
        <Button size="icon" variant="ghost" active={loop} aria-pressed={loop} aria-label="Loop" onClick={() => clock.setLoop(!loop)} title="Loop (L)">{Icon.loop}</Button>
        <Timecode />
      </div>
      <div className="toolbar-group toolbar-edit toolbar-separated" role="group" aria-label="Edit">
        <Button size="sm" onClick={() => split()} aria-label="Split at playhead" title="Split the recording at the playhead (S)">{Icon.scissors}<span className="toolbar-label">Split</span></Button>
        <Button size="sm" variant="ghost" disabled={!selected} onClick={deleteSelection} aria-label="Delete selection" title="Delete the selection (Del)">{Icon.trash}<span className="toolbar-wide-label">Delete</span></Button>
        <Button size="icon" variant="ghost" onClick={() => onJumpKey(-1)} aria-label="Previous key" title="Previous key ([)"><KeyJump direction={-1} /></Button>
        <Button size="icon" variant="ghost" onClick={() => onJumpKey(1)} aria-label="Next key" title="Next key (])"><KeyJump direction={1} /></Button>
      </div>
      <div className="toolbar-group toolbar-camera toolbar-separated" role="group" aria-label="Camera">
        <Button size="sm" className="toolbar-direct-key" disabled={!hasSpec} onClick={keyCamera} title="Key zoom and pan at the playhead (K)">{Icon.key} Key</Button>
        <Button size="sm" className="toolbar-camera-menu" aria-haspopup="menu" onClick={(e) => menu(e, cameraItems)}>Camera <span aria-hidden="true">▾</span></Button>
        <div className="toolbar-group toolbar-camera-full">
          <Button size="sm" disabled={!hasSpec} onClick={() => preset("push")} title="Ease in on the active verse over 0.9 s">Push in</Button>
          <Button size="sm" disabled={!hasSpec} onClick={() => preset("pull")} title="Ease back to the whole phone over 0.9 s">Pull back</Button>
          <Button size="sm" disabled={!hasSpec} onClick={() => preset("punch")} title="Cut straight to a close-up">Punch in</Button>
          <Button size="sm" variant="ghost" disabled={!hasClip} onClick={showExact} title="Show the exporter's own frame, with motion blur">Exact frame</Button>
        </div>
      </div>
      <Button size="sm" className="toolbar-tools" aria-haspopup="menu" onClick={(e) => menu(e, tools)}>Tools <span aria-hidden="true">▾</span></Button>
      {selection && <button className="toolbar-selection text-[11.5px] text-mute hover:text-ink" onClick={revealInspector} title={`Inspect ${selection}`} aria-label="Inspect selection">{selection}</button>}
      <div className="toolbar-spacer" />
      <div className="toolbar-group toolbar-zoom toolbar-separated" role="group" aria-label="Timeline zoom">
        <label className="toolbar-zoom-slider"><span className="toolbar-zoom-label text-[11px] text-faint">Zoom</span><input type="range" min={8} max={500} step={1} value={pps} aria-label="Timeline zoom" onChange={(e) => setUI({ pps: +e.target.value })} title="Timeline zoom (Ctrl-scroll)" /></label>
        <Button size="sm" variant="ghost" active={fit} aria-pressed={fit} title="Keep the whole edit visible as it changes; manual zoom turns Fit off" onClick={() => setUI({ fit: !fit })}>Fit</Button>
      </div>
    </div>
  </div>;
}
