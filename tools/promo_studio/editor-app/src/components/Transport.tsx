// Play / loop, the timecode (click to type a time), camera shortcuts.
import { useEffect, useRef, useState } from "react";
import { fmtTime, parseTime } from "../lib/model.ts";
import { clock, usePlaying } from "../state/clock.ts";
import { keyCamera, preset, showExact, toast, useStore } from "../state/store.ts";
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
      <input autoFocus defaultValue={clock.t.toFixed(2)} className="num h-7 w-24 rounded border border-gold bg-panel2 px-1.5"
        onFocus={(e) => e.target.select()}
        onBlur={(e) => { const t = parseTime(e.target.value); if (Number.isFinite(t)) { clock.pause(); clock.seek(t); } else toast("Type seconds (12.5) or minutes:seconds (0:12.5)"); setEditing(false); }}
        onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); if (e.key === "Escape") { e.stopPropagation(); setEditing(false); } }} />
    );
  }
  return (
    <button onClick={() => setEditing(true)} title="Click to type a time"
      className="num flex h-7 items-baseline gap-1 rounded px-1.5 text-ink hover:bg-panel2">
      <span ref={ref} className="text-[14px]" />
      <span className="text-faint">/ {fmtTime(duration)}</span>
    </button>
  );
}

export function Transport() {
  const { playing, loop } = usePlaying();
  const hasClip = useStore((s) => !!s.geo?.clip), hasSpec = useStore((s) => !!s.spec);
  return (
    <div className="transport flex shrink-0 flex-wrap items-center gap-1.5 border-t border-line bg-panel px-3 py-1.5">
      <div className="flex items-center gap-1.5">
      <Button size="icon" variant="primary" disabled={!hasSpec} onClick={() => clock.toggle()} title="Play / pause (Space)">{playing ? Icon.pause : Icon.play}</Button>
      <Button size="icon" variant="ghost" active={loop} onClick={() => clock.setLoop(!loop)} title="Loop (L)">{Icon.loop}</Button>
      <Timecode />
      </div>
      <div className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-1.5">
      <Button size="sm" disabled={!hasSpec} onClick={keyCamera} title="Key zoom and pan at the playhead (K)">{Icon.key} Key</Button>
      <Button size="sm" disabled={!hasSpec} onClick={() => preset("push")} title="Ease in on the active verse over 0.9 s">Push in</Button>
      <Button size="sm" disabled={!hasSpec} onClick={() => preset("pull")} title="Ease back to the whole phone over 0.9 s">Pull back</Button>
      <Button size="sm" disabled={!hasSpec} onClick={() => preset("punch")} title="Cut straight to a close-up">Punch in</Button>
      <div className="mx-1 h-5 w-px bg-line" />
      <Button size="sm" variant="ghost" disabled={!hasClip} onClick={showExact} title="Show the exporter's own frame, with motion blur">Exact frame</Button>
      </div>
    </div>
  );
}
