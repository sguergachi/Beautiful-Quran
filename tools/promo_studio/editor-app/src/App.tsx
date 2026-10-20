import { useEffect } from "react";
import { Toasts, TopBar, VideoList } from "./components/Chrome.tsx";
import { Inspector } from "./components/Inspector.tsx";
import { MenuHost } from "./components/Menu.tsx";
import { EmulatorsPanel, HelpPanel, JobsPanel } from "./components/Panels.tsx";
import { Stage } from "./components/Stage.tsx";
import { Timeline } from "./components/Timeline.tsx";
import { Transport } from "./components/Transport.tsx";
import { track } from "./lib/drag.ts";
import { clamp, videoTimes } from "./lib/model.ts";
import { landmarks } from "./lib/edit.ts";
import { clock } from "./state/clock.ts";
import {
  copy, deleteSelection, init, keyCamera, nudge, openPanel, paste, redo, save, select, selectAll, selKeys, setUI, split,
  undo, useStore,
} from "./state/store.ts";

// A divider between panes. `axis` x resizes a width, y the timeline height.
function Divider({ axis, onDrag, onReset, className = "" }: { axis: "x" | "y"; onDrag: (d: number) => void; onReset: () => void; className?: string }) {
  return (
    <div
      onDoubleClick={onReset}
      onPointerDown={(e) => {
        if (e.button !== 0) return;
        e.preventDefault();
        const el = e.currentTarget as HTMLElement, start = axis === "x" ? e.clientX : e.clientY;
        el.dataset.on = "1";
        track(e, el, (m) => onDrag((axis === "x" ? m.clientX : m.clientY) - start), () => { delete el.dataset.on; }, { cursor: axis === "x" ? "col" : "row" });
      }}
      title="Drag to resize · double-click to reset"
      className={`group relative z-20 shrink-0 ${axis === "x" ? "w-px cursor-col-resize bg-line" : "h-px cursor-row-resize bg-line"} ${className}`}>
      <div className={`absolute ${axis === "x" ? "inset-y-0 -left-[3px] w-[7px]" : "inset-x-0 -top-[3px] h-[7px]"} group-hover:bg-gold/50 group-data-[on]:bg-gold`} />
    </div>
  );
}

function useShortcuts() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const s = useStore.getState();
      if (e.key === "Escape") { if (s.panel) return openPanel(null); select(null); return; }
      // text fields keep their keys; sliders and dropdowns don't, so Ctrl+Z still undoes after a drag
      const t = e.target as HTMLElement;
      const typing = t.closest("textarea, [contenteditable=true], input:not([type=range]):not([type=checkbox]):not([type=radio])");
      const mod = e.ctrlKey || e.metaKey, key = e.key.toLowerCase();
      if (typing || (t.closest("select, input") && !mod)) return;
      if (mod && key === "s") { e.preventDefault(); save(); return; }
      if (mod && key === "b") { e.preventDefault(); setUI({ hideL: !s.ui.hideL }); return; }
      if (mod && key === ".") { e.preventDefault(); setUI({ hideR: !s.ui.hideR }); return; }
      if (!s.spec) return;
      if (mod && key === "z") { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
      if (mod && key === "y") { e.preventDefault(); redo(); return; }
      if (mod && key === "a") { e.preventDefault(); selectAll(); return; }
      if (mod && key === "c") { if (selKeys().length) { e.preventDefault(); copy(); } return; }
      if (mod && key === "v") { if (s.clipboard) { e.preventDefault(); paste(); } return; }
      if (mod || e.altKey) return;
      const step = (sec: number) => { e.preventDefault(); clock.pause(); clock.seek(clock.t + sec); };
      const fps = s.spec.fps || 60;
      switch (e.key) {
        case " ": e.preventDefault(); if (!e.repeat) clock.toggle(); break;
        case "l": case "L": clock.setLoop(!clock.loop); break;
        case "k": case "K": keyCamera(); break;
        case "s": case "S": e.preventDefault(); if (!e.repeat) split(); break;
        case "Delete": case "Backspace": e.preventDefault(); if (!e.repeat) deleteSelection(); break;
        case "ArrowLeft": step(-(e.shiftKey ? 1 : 1 / fps)); break;
        case "ArrowRight": step(e.shiftKey ? 1 : 1 / fps); break;
        case "ArrowUp": case "ArrowDown": {
          e.preventDefault();
          const ts = landmarks(s.spec, s.tl?.verses, (a, b) => videoTimes(s.spec!, a, b));
          const next = e.key === "ArrowDown" ? ts.find((x) => x > clock.t + 1e-3) : [...ts].reverse().find((x) => x < clock.t - 1e-3);
          if (next != null) { clock.pause(); clock.seek(next); }
          break;
        }
        case "Home": e.preventDefault(); clock.seek(0); break;
        case "End": e.preventDefault(); clock.seek(s.spec.duration); break;
        case "[": case "]": {
          const items = selKeys(), names = items.length ? [...new Set(items.map((x) => x.prop))] : Object.keys(s.spec.tracks);
          const ts = [...new Set(names.flatMap((n) => (s.spec!.tracks[n] || []).map((k) => k.t)))].sort((a, b) => a - b);
          const next = e.key === "]" ? ts.find((x) => x > clock.t + 1e-3) : [...ts].reverse().find((x) => x < clock.t - 1e-3);
          if (next != null) clock.seek(next);
          break;
        }
        case ",": case "<": nudge(-(e.shiftKey ? 10 : 1)); break;
        case ".": case ">": nudge(e.shiftKey ? 10 : 1); break;
        case "?": openPanel("help"); break;
      }
    };
    addEventListener("keydown", onKey);
    const beforeUnload = (e: BeforeUnloadEvent) => { if (useStore.getState().saveState === "dirty" || useStore.getState().saveState === "saving") { save(); e.preventDefault(); } };
    addEventListener("beforeunload", beforeUnload);
    return () => { removeEventListener("keydown", onKey); removeEventListener("beforeunload", beforeUnload); };
  }, []);
}

export function App() {
  const ui = useStore((s) => s.ui), panel = useStore((s) => s.panel), fatal = useStore((s) => s.fatal);
  useShortcuts();
  useEffect(() => { init(); }, []);
  if (fatal) {
    return (
      <div className="grid h-full place-items-center p-8 text-center">
        <div><p className="mb-2 text-lg">The studio could not start</p><p className="text-mute">{fatal}</p></div>
      </div>
    );
  }
  const w0 = { left: ui.leftW, right: ui.rightW, tl: ui.tlH };
  return (
    <div className="flex h-full flex-col">
      <TopBar />
      <div className="flex min-h-0 flex-1">
        {!ui.hideL && (
          <>
            <div style={{ width: ui.leftW }} className="min-h-0 shrink-0"><VideoList /></div>
            <Divider axis="x" onReset={() => setUI({ leftW: 248 })}
              onDrag={(d) => { const w = w0.left + d; if (w < 140) setUI({ hideL: true, leftW: 248 }); else setUI({ leftW: Math.round(clamp(w, 190, 460)) }); }} />
          </>
        )}
        <main className="flex min-w-0 flex-1 flex-col">
          <Stage />
          <Transport />
          <Divider axis="y" onReset={() => setUI({ tlH: 260 })} onDrag={(d) => setUI({ tlH: Math.round(clamp(w0.tl - d, 120, innerHeight * 0.72)) })} />
          <div style={{ height: ui.tlH }} className="min-h-0 shrink-0"><Timeline /></div>
        </main>
        {!ui.hideR && (
          <>
            <Divider axis="x" onReset={() => setUI({ rightW: 340 })}
              onDrag={(d) => { const w = w0.right - d; if (w < 200) setUI({ hideR: true, rightW: 340 }); else setUI({ rightW: Math.round(clamp(w, 280, 560)) }); }} />
            <div style={{ width: ui.rightW }} className="min-h-0 shrink-0"><Inspector /></div>
          </>
        )}
      </div>
      {panel === "emulators" && <EmulatorsPanel />}
      {panel === "jobs" && <JobsPanel />}
      {panel === "help" && <HelpPanel />}
      <MenuHost />
      <Toasts />
    </div>
  );
}
