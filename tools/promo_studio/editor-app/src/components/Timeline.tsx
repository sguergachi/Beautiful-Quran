// The timeline: the recording's pieces, where verses and repeats fall, and a
// keyframe lane per property. Every drag is captured on the scroll box (it
// survives re-renders) and is one undo step.
import { memo, useEffect, useRef, useState } from "react";
import * as E from "../lib/edit.ts";
import { track } from "../lib/drag.ts";
import { clamp, pieceStart, r3, sourceTime, valueAt, videoTimes } from "../lib/model.ts";
import type { Ease, Key, PropDef, Spec } from "../lib/types.ts";
import { clock, useTime } from "../state/clock.ts";
import {
  copy, deleteSelection, endGesture, mutate, paste, select, selectAll, selectKeys, selKeys, setEase, setUI, split,
  toggleKey, useStore,
} from "../state/store.ts";
import { openMenu, type MenuItem } from "./Menu.tsx";
import { Button, Icon } from "./ui.tsx";

const EASE_LABEL: Record<Ease, string> = {
  inOutCubic: "Ease in & out", inOutSine: "Gentle in & out", outCubic: "Ease out", inCubic: "Ease in",
  outExpo: "Fast out, long settle", linear: "Linear", hold: "Hold (cut)",
};
const EASE_COLOR: Record<Ease, string> = {
  inOutCubic: "#88c0ac", inOutSine: "#9fd6c2", outCubic: "#b9d38a", inCubic: "#79a7a0", outExpo: "#b9d38a", linear: "#8fb3e8", hold: "#e07a68",
};

export function Timeline() {
  const spec = useStore((s) => s.spec), meta = useStore((s) => s.meta), tl = useStore((s) => s.tl);
  const sel = useStore((s) => s.sel), ui = useStore((s) => s.ui), clipboard = useStore((s) => s.clipboard);
  const box = useRef<HTMLDivElement>(null), inner = useRef<HTMLDivElement>(null);
  const playhead = useRef<HTMLDivElement>(null), head = useRef<HTMLDivElement>(null);
  const hover = useRef<HTMLDivElement>(null), hoverT = useRef<HTMLDivElement>(null);
  const [marquee, setMarquee] = useState<{ x: number; y: number; w: number; h: number } | null>(null);
  const { pps, labelW } = ui;

  const timeAt = (clientX: number) => (clientX - inner.current!.getBoundingClientRect().left - labelW) / pps;
  const edgeOpt = () => ({ box: box.current!, left: labelW });

  // playhead and auto-follow, outside React renders
  useEffect(() => {
    const place = (t: number) => {
      if (!box.current) return;
      const x = t * pps;
      if (playhead.current) { playhead.current.style.transform = `translateX(${labelW + x}px)`; playhead.current.style.visibility = x < box.current!.scrollLeft - 1 ? "hidden" : "visible"; }
      if (head.current) head.current.style.transform = `translateX(${x}px)`;
      const b = box.current;
      if (b && clock.playing) {
        const px = labelW + x;
        if (px > b.scrollLeft + b.clientWidth - 40 || px < b.scrollLeft + labelW) b.scrollLeft = Math.max(0, px - labelW - 40);
      }
    };
    place(clock.t);
    const off = clock.subscribe(place);
    const b = box.current, onScroll = () => place(clock.t);
    b?.addEventListener("scroll", onScroll);
    return () => { off(); b?.removeEventListener("scroll", onScroll); };
  }, [pps, labelW, spec?.duration]);

  // ctrl / pinch wheel zooms around the pointer
  useEffect(() => {
    const b = box.current;
    if (!b) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      const t = timeAt(e.clientX), rect = b.getBoundingClientRect();
      const next = clamp(useStore.getState().ui.pps * Math.exp(-e.deltaY * 0.002), 8, 500);
      setUI({ pps: next });
      requestAnimationFrame(() => { b.scrollLeft = Math.max(0, t * next - (e.clientX - rect.left - labelW)); });
    };
    b.addEventListener("wheel", onWheel, { passive: false });
    return () => b.removeEventListener("wheel", onWheel);
  });

  if (!spec || !meta) return <div className="flex-1 bg-panel" />;
  const dur = spec.duration, laneW = Math.max(240, dur * pps + 48);

  const snapT = (t: number, exclude: E.KeyRef[] = [], noPlayhead = false, playhead = clock.t) => {
    const c = [0, dur];
    if (!noPlayhead) c.push(playhead);
    for (const [n, keys] of Object.entries(spec.tracks)) for (const k of keys) if (!exclude.some((x) => x.prop === n && Math.abs(x.t - k.t) < 1e-6)) c.push(k.t);
    for (let i = 1; i < spec.edit.length; i++) c.push(pieceStart(spec, i));
    for (const v of tl?.verses || []) for (const [a] of videoTimes(spec, v.t0, v.t1)) c.push(a);
    let best = t, bd = 7 / pps;
    for (const x of c) if (Math.abs(x - t) < bd) { bd = Math.abs(x - t); best = x; }
    return best;
  };

  // ---- gestures
  const scrub = (e: React.PointerEvent, at?: number) => {
    if (e.button !== 0) return;
    e.preventDefault(); e.stopPropagation();
    const was = clock.playing;
    clock.pause();
    if (useStore.getState().sel) select(null);
    const go = (ev: { clientX: number; shiftKey: boolean }, t?: number) => clock.seek(ev.shiftKey ? snapT(t ?? timeAt(ev.clientX), [], true) : t ?? timeAt(ev.clientX));
    go(e, at);
    track(e, box.current!, (m) => go(m), () => { if (was) clock.play(); }, { cursor: "ew", edge: edgeOpt() });
  };
  const laneDown = (e: React.PointerEvent) => {
    if (e.button !== 0) return;
    if (!e.shiftKey) return scrub(e);
    e.preventDefault();
    const base = e.ctrlKey || e.metaKey ? selKeys() : [];
    const r0 = inner.current!.getBoundingClientRect(), x0 = e.clientX - r0.left, y0 = e.clientY - r0.top;
    let last = { x: x0, y: y0, w: 0, h: 0 };
    track(e, box.current!, (m) => {
      const r = inner.current!.getBoundingClientRect(), x1 = m.clientX - r.left, y1 = m.clientY - r.top;
      last = { x: Math.min(x0, x1), y: Math.min(y0, y1), w: Math.abs(x1 - x0), h: Math.abs(y1 - y0) };
      setMarquee(last);
    }, () => {
      setMarquee(null);
      const ta = (last.x - labelW) / pps, tb = (last.x + last.w - labelW) / pps, items = [...base];
      for (const row of inner.current!.querySelectorAll<HTMLElement>("[data-lane]")) {
        const top = row.offsetTop, bottom = top + row.offsetHeight;
        if (bottom < last.y || top > last.y + last.h) continue;
        for (const p of (row.dataset.lane || "").split(",")) for (const k of spec.tracks[p] || [])
          if (k.t >= ta - 1e-6 && k.t <= tb + 1e-6 && !items.some((x) => x.prop === p && Math.abs(x.t - k.t) < 1e-6)) items.push({ prop: p, t: k.t });
      }
      selectKeys(items);
    }, { edge: edgeOpt() });
  };
  const keyDown = (e: React.PointerEvent, prop: string, k: Key) => {
    if (e.button !== 0) return;
    e.preventDefault(); e.stopPropagation(); clock.pause(); endGesture();
    const ref = { prop, t: k.t }, current = selKeys();
    if (e.shiftKey || e.ctrlKey || e.metaKey) {
      const i = current.findIndex((x) => E.sameKey(x, ref));
      selectKeys(i >= 0 ? current.filter((_, j) => j !== i) : [ref, ...current]);
      return;
    }
    let items = current.some((x) => E.sameKey(x, ref)) ? [ref, ...current.filter((x) => !E.sameKey(x, ref))] : [ref];
    selectKeys(items);
    const origin = items.map((x) => ({ ...x })), x0 = e.clientX, start = timeAt(e.clientX), playhead = clock.t;
    const tag = `drag:keys:${performance.now()}`;
    let moved = false;
    track(e, box.current!, (m) => {
      if (!moved && Math.abs(m.clientX - x0) < 3) return;
      moved = true;
      let t = origin[0].t + timeAt(m.clientX) - start;
      if (!m.altKey) t = snapT(t, origin, false, playhead);
      let next: E.KeyRef[] | null = null;
      mutate((raw, c) => { next = E.moveKeys(raw, c, items, t - origin[0].t, origin); }, tag);
      if (next) { items = next; selectKeys(next); clock.seek((next as E.KeyRef[])[0].t); }
    }, () => { endGesture(); if (!moved) clock.seek(k.t); }, { cursor: "ew", edge: edgeOpt() });
  };
  const pieceDown = (e: React.PointerEvent, i: number, side: "in" | "out" | null) => {
    if (e.button !== 0) return;
    e.preventDefault(); e.stopPropagation(); clock.pause(); endGesture();
    select({ type: "piece", i });
    const p = spec.edit[i];
    if (!side) { clock.seek(clamp(timeAt(e.clientX), pieceStart(spec, i), pieceStart(spec, i) + p.out - p.in)); setUI({ tab: "recording", hideR: false }); return; }
    const v0 = side === "in" ? p.in : p.out, start = timeAt(e.clientX), tag = `drag:trim:${performance.now()}`;
    track(e, box.current!, (m) => {
      const v = v0 + timeAt(m.clientX) - start;
      mutate((raw) => E.trimPiece(raw, i, side, v, tl?.take), tag);
      const s = useStore.getState().spec!, q = s.edit[i];
      // show the frame at the edge being trimmed
      if (q) clock.seek(pieceStart(s, i) + (side === "in" ? 0 : q.out - q.in - 1e-3));
    }, () => endGesture(), { cursor: "ew" });
  };
  const resizeLabels = (e: React.PointerEvent) => {
    e.preventDefault(); e.stopPropagation();
    const x0 = e.clientX, w0 = labelW;
    track(e, box.current!, (m) => setUI({ labelW: Math.round(clamp(w0 + m.clientX - x0, 96, 280)) }), undefined, { cursor: "col" });
  };
  const onHover = (e: React.PointerEvent) => {
    if (!hover.current || !hoverT.current || !inner.current) return;
    const t = timeAt(e.clientX), show = !e.buttons && t >= 0 && t <= dur && !(e.target as HTMLElement).closest("[data-label]");
    hover.current.style.display = hoverT.current.style.display = show ? "block" : "none";
    if (!show) return;
    hover.current.style.transform = `translateX(${labelW + t * pps}px)`;
    hoverT.current.style.transform = `translateX(${t * pps + 6}px)`;
    hoverT.current.textContent = t.toFixed(2) + "s";
  };

  // ---- menus
  const keyMenu = (e: React.MouseEvent, prop: string, k: Key) => {
    const ref = { prop, t: k.t }, cur = selKeys();
    if (!cur.some((x) => E.sameKey(x, ref))) selectKeys([ref]);
    const n = Math.max(1, selKeys().length), ease = k.ease || "inOutCubic";
    openMenu(e, [
      { head: n > 1 ? `${n} keys · ease into them` : "Ease into this key" },
      ...meta.eases.map((x) => ({ label: EASE_LABEL[x], checked: x === ease, run: () => setEase(x) })),
      "-",
      { label: "Go to key", run: () => clock.seek(k.t) },
      { label: n > 1 ? `Copy ${n} keys` : "Copy", hint: "Ctrl C", run: copy },
      { label: n > 1 ? `Delete ${n} keys` : "Delete", hint: "Del", danger: true, run: deleteSelection },
    ]);
  };
  const pieceMenu = (e: React.MouseEvent, i: number) => {
    select({ type: "piece", i });
    const t = timeAt(e.clientX);
    openMenu(e, [
      { label: "Split here", run: () => split(t) },
      { label: "Split at the playhead", hint: "S", run: () => split() },
      "-",
      { label: "Delete piece", danger: true, disabled: spec.edit.length <= 1, run: deleteSelection },
    ]);
  };
  const laneMenu = (e: React.MouseEvent, prop: string | null) => {
    const t = clamp(timeAt(e.clientX), 0, dur);
    const items: MenuItem[] = [];
    if (prop) items.push({ label: `Add a ${meta.props[prop].label} key here`, run: () => { mutate((raw, c) => E.keyAll(raw, c, [prop], t)); selectKeys([{ prop, t: r3(t) }]); } });
    items.push({ label: "Paste here", hint: "Ctrl V", disabled: !clipboard, run: () => paste(t) }, { label: "Select all keys", hint: "Ctrl A", run: selectAll });
    openMenu(e, items);
  };

  const groupMap: Record<string, string[]> = {};
  for (const [n, p] of Object.entries(meta.props)) (groupMap[p.group] = groupMap[p.group] || []).push(n);
  const groups = Object.entries(groupMap);
  const selItems = sel?.type === "keys" ? sel.items : [];
  const isSel = (prop: string, t: number) => selItems.some((x) => x.prop === prop && Math.abs(x.t - t) < 1e-6);
  const takeLen = tl?.take ?? null;

  const step = pps >= 120 ? 0.5 : pps >= 50 ? 1 : pps >= 20 ? 2 : pps >= 10 ? 5 : 10;
  const ticks: number[] = [];
  for (let t = 0; t <= dur + 1e-6; t += step) ticks.push(+t.toFixed(3));

  const nSel = selItems.length;
  const selText = nSel > 1 ? `${nSel} keys` : nSel === 1 ? `${meta.props[selItems[0].prop].label} · ${selItems[0].t.toFixed(2)}s` : sel?.type === "piece" ? `Piece ${sel.i + 1}` : "";

  return (
    <div className="flex h-full min-h-0 flex-col bg-panel">
      <div className="flex h-10 shrink-0 items-center gap-1.5 border-b border-line px-3">
        <Button size="sm" onClick={() => split()} title="Split the recording at the playhead (S)">{Icon.scissors} Split</Button>
        <Button size="sm" variant="danger" disabled={!sel} onClick={deleteSelection} title="Delete the selection (Del)">{Icon.trash} Delete</Button>
        <div className="mx-1 h-5 w-px bg-line" />
        <Button size="sm" variant="ghost" onClick={() => jumpKey(spec, -1)} title="Previous key ([)">◂ ◆</Button>
        <Button size="sm" variant="ghost" onClick={() => jumpKey(spec, 1)} title="Next key (])">◆ ▸</Button>
        <span className="ml-2 truncate text-[11.5px] text-mute">{selText}</span>
        <div className="flex-1" />
        <span className="text-[11px] text-faint">Zoom</span>
        <input type="range" min={8} max={500} step={1} value={pps} className="w-28" onChange={(e) => setUI({ pps: +e.target.value })} title="Timeline zoom (Ctrl-scroll)" />
        <Button size="sm" variant="ghost" onClick={() => setUI({ pps: clamp((box.current!.clientWidth - labelW - 60) / Math.max(1, dur), 8, 500) })}>Fit</Button>
      </div>
      <div ref={box} className="relative min-h-0 flex-1 overflow-auto select-none" onPointerMove={onHover}
        onPointerLeave={() => { if (hover.current) hover.current.style.display = "none"; if (hoverT.current) hoverT.current.style.display = "none"; }}>
        <div ref={inner} className="relative" style={{ width: labelW + laneW, minWidth: "100%" }}>
          {/* ruler */}
          <div className="sticky top-0 z-30 flex h-6 border-b border-line bg-panel">
            <div data-label className="sticky left-0 z-40 flex shrink-0 items-center border-r border-line bg-panel px-2 text-[11px] text-faint" style={{ width: labelW }}>
              time
              <div onPointerDown={resizeLabels} className="absolute -right-1 top-0 bottom-0 z-50 w-2 cursor-col-resize hover:bg-gold/60" title="Drag to resize the labels" />
            </div>
            <div className="relative shrink-0 cursor-ew-resize" style={{ width: laneW }} onPointerDown={(e) => scrub(e)}>
              {ticks.map((t, i) => {
                const major = step >= 2 || i % 2 === 0;
                return <div key={t} className={`absolute border-l border-line pl-1 text-[10.5px] text-faint ${major ? "top-0 h-full" : "top-[70%] h-[30%]"}`} style={{ left: t * pps }}>{major ? `${Number.isInteger(t) ? t : t.toFixed(1)}s` : ""}</div>;
              })}
              <div ref={head} onPointerDown={(e) => scrub(e)} className="ph-head absolute top-0 -ml-[6px] h-[13px] w-[13px] bg-gold" style={{ left: 0 }} title="Drag to scrub" />
              <div ref={hoverT} className="pointer-events-none absolute top-[3px] hidden rounded bg-black/80 px-1 text-[10px] text-ink" />
            </div>
          </div>
          {/* recording */}
          <LaneRow labelW={labelW} laneW={laneW} scrub={scrub} tall label={<span>Recording</span>} lane="">
            {spec.edit.map((p, i) => {
              const a = pieceStart(spec, i), w = Math.max(4, (p.out - p.in) * pps), over = takeLen != null && p.out > takeLen + 0.05;
              const on = sel?.type === "piece" && sel.i === i;
              return (
                <div key={i} onPointerDown={(e) => pieceDown(e, i, null)} onContextMenu={(e) => pieceMenu(e, i)}
                  title={`take ${p.in.toFixed(2)}–${p.out.toFixed(2)} s · drag an edge to trim · right-click to split or delete${over ? " · past the end of the take: record a longer take" : ""}`}
                  className={`absolute top-[3px] bottom-[3px] cursor-pointer overflow-hidden whitespace-nowrap rounded-[5px] border px-2 py-0.5 text-[10.5px] ${over ? "stripes border-danger" : "border-[#4b6280] bg-[#2b3747] text-[#dce8f8] hover:border-blue"} ${on ? "outline outline-2 outline-blue" : ""}`}
                  style={{ left: a * pps, width: w }}>
                  {p.in.toFixed(2)}–{p.out.toFixed(2)}s
                  <div onPointerDown={(e) => pieceDown(e, i, "in")} className="absolute inset-y-0 left-0 w-2 cursor-ew-resize bg-white/10 hover:bg-white/30" />
                  <div onPointerDown={(e) => pieceDown(e, i, "out")} className="absolute inset-y-0 right-0 w-2 cursor-ew-resize bg-white/10 hover:bg-white/30" />
                </div>
              );
            })}
            {spec.edit.slice(1).map((_, i) => <div key={"c" + i} className="pointer-events-none absolute inset-y-0 border-l border-dashed border-[#6a8ab0]" style={{ left: pieceStart(spec, i + 1) * pps }} />)}
          </LaneRow>
          {/* references */}
          <LaneRow labelW={labelW} laneW={laneW} scrub={scrub} label="Verses">
            {(tl?.verses || []).flatMap((v, vi) => videoTimes(spec, v.t0, v.t1).map(([a, b], j) => (
              <div key={`${vi}-${j}`} onPointerDown={(e) => scrub(e, a)} title={`${v.label} · click to jump · drag to scrub`}
                className={`absolute top-1 bottom-1 cursor-pointer overflow-hidden whitespace-nowrap rounded border px-1.5 text-[10.5px] ${v.label === "basmalah" ? "border-[#5a4c2c] bg-[#3a3324] text-[#e9d9ae]" : "border-[#3f5a50] bg-[#26332e] text-[#cfe5dc]"}`}
                style={{ left: a * pps, width: Math.max(2, (b - a) * pps) }}>{v.label}</div>
            )))}
          </LaneRow>
          <LaneRow labelW={labelW} laneW={laneW} scrub={scrub} label="Repeats">
            {mergeRepeats(tl?.repeats || []).flatMap((r, ri) => videoTimes(spec, r.t0, r.t1).map(([a, b], j) => (
              <div key={`${ri}-${j}`} onPointerDown={(e) => scrub(e, Math.max(0, a - 0.5))} title={`${r.verse}: the reciter repeats · click to jump (0.5 s before)`}
                className="absolute top-1 bottom-1 cursor-pointer overflow-hidden rounded border border-orange bg-[#5a3420] px-1.5 text-[10.5px] text-[#ffd2b8]"
                style={{ left: a * pps, width: Math.max(2, (b - a) * pps) }}>repeat</div>
            )))}
          </LaneRow>
          {/* property lanes */}
          {groups.map(([group, names]) => {
            const collapsed = !!ui.collapsed[group], nAnim = names.filter((n) => spec.tracks[n]?.length).length;
            return (
              <div key={group}>
                <LaneRow labelW={labelW} laneW={laneW} scrub={scrub} className="bg-[#1d1c18]" lane={collapsed ? names.join(",") : ""} onDown={laneDown} onMenu={(e) => laneMenu(e, null)}
                  label={<button className="flex w-full items-center gap-1.5 text-left font-semibold text-ink" onClick={() => setUI({ collapsed: { ...ui.collapsed, [group]: !collapsed } })}>
                    <span className="text-faint">{collapsed ? "▸" : "▾"}</span>{group}<span className="ml-auto font-normal text-faint">{nAnim ? `${nAnim} animated` : ""}</span></button>}>
                  {collapsed && names.flatMap((n) => (spec.tracks[n] || []).map((k) => <Diamond key={n + k.t} k={k} on={isSel(n, k.t)} pps={pps} label={meta.props[n].label} onDown={(e) => keyDown(e, n, k)} onMenu={(e) => keyMenu(e, n, k)} />))}
                </LaneRow>
                {!collapsed && names.map((n) => (
                  <LaneRow labelW={labelW} laneW={laneW} scrub={scrub} key={n} lane={n} onDown={laneDown} onMenu={(e) => laneMenu(e, n)}
                    laneProps={{ onDoubleClick: (e) => { if ((e.target as HTMLElement).closest("[data-key]")) return; const raw = timeAt(e.clientX), t = clamp(e.altKey ? raw : snapT(raw), 0, dur); mutate((d, c) => E.keyAll(d, c, [n], t)); selectKeys([{ prop: n, t: r3(t) }]); clock.seek(t); } }}
                    label={<LaneLabel name={n} def={meta.props[n]} spec={spec} />}>
                    {spec.tracks[n]?.length ? <Curve spec={spec} name={n} props={meta.props} pps={pps} /> : null}
                    {(spec.tracks[n] || []).map((k) => <Diamond key={k.t} k={k} on={isSel(n, k.t)} pps={pps} label={meta.props[n].label} onDown={(e) => keyDown(e, n, k)} onMenu={(e) => keyMenu(e, n, k)} />)}
                  </LaneRow>
                ))}
              </div>
            );
          })}
          {/* the line lets clicks through to keys and trim handles under it; grab it by its head in the ruler */}
          <div ref={playhead} className="pointer-events-none absolute top-0 bottom-0 z-[25] -ml-px w-0.5 bg-gold" style={{ left: 0 }} />
          <div ref={hover} className="pointer-events-none absolute top-0 bottom-0 z-[24] hidden w-px bg-white/20" style={{ left: 0 }} />
          {marquee && <div className="pointer-events-none absolute z-[26] border border-blue bg-blue/15" style={{ left: marquee.x, top: marquee.y, width: marquee.w, height: marquee.h }} />}
        </div>
      </div>
    </div>
  );
}

interface RowProps {
  labelW: number; laneW: number; scrub: (e: React.PointerEvent) => void;
  label: React.ReactNode; children?: React.ReactNode; lane?: string; tall?: boolean; className?: string;
  onDown?: (e: React.PointerEvent) => void; onMenu?: (e: React.MouseEvent) => void; laneProps?: React.HTMLAttributes<HTMLDivElement>;
}
// One track: a sticky label and its lane. An empty spot of the lane scrubs
// (or, for keyframe lanes, Shift-drags a selection box).
function LaneRow({ labelW, laneW, scrub, label, children, lane, tall, className = "", onDown, onMenu, laneProps }: RowProps) {
  const bare = (e: React.SyntheticEvent) => e.target === e.currentTarget || (e.target as HTMLElement).dataset.lanebg != null;
  return (
    <div className={`flex border-b border-[#2a2822] ${tall ? "h-[34px]" : "h-[26px]"} ${className}`} data-lane={lane}>
      <div data-label className="sticky left-0 z-20 flex shrink-0 items-center gap-1 overflow-hidden whitespace-nowrap border-r border-line bg-panel px-2 text-[11.5px] text-mute" style={{ width: labelW }}>{label}</div>
      <div className="relative shrink-0" style={{ width: laneW }}
        onPointerDown={(e) => { if (bare(e)) (onDown ?? scrub)(e); }}
        onContextMenu={(e) => { if (onMenu && bare(e)) onMenu(e); }} {...laneProps}>
        {children}
      </div>
    </div>
  );
}

function jumpKey(spec: Spec, dir: number) {
  const items = selKeys(), names = items.length ? [...new Set(items.map((x) => x.prop))] : Object.keys(spec.tracks);
  const ts = [...new Set(names.flatMap((n) => (spec.tracks[n] || []).map((k) => k.t)))].sort((a, b) => a - b);
  const next = dir > 0 ? ts.find((x) => x > clock.t + 1e-3) : [...ts].reverse().find((x) => x < clock.t - 1e-3);
  if (next != null) clock.seek(next);
}

function mergeRepeats(reps: { verse: string; t0: number; t1: number }[]) {
  const out: { verse: string; t0: number; t1: number }[] = [];
  for (const r of reps) { const l = out[out.length - 1]; if (l && l.verse === r.verse && r.t0 - l.t1 < 0.15) l.t1 = r.t1; else out.push({ ...r }); }
  return out;
}

function Diamond({ k, on, pps, label, onDown, onMenu }: { k: Key; on: boolean; pps: number; label: string; onDown: (e: React.PointerEvent) => void; onMenu: (e: React.MouseEvent) => void }) {
  const ease = (k.ease || "inOutCubic") as Ease;
  return (
    <div data-key onPointerDown={onDown} onContextMenu={onMenu}
      title={`${label} = ${(+k.v).toFixed(3)} at ${k.t.toFixed(2)} s · ${EASE_LABEL[ease]}\nDrag to move · Shift-click to add to the selection · right-click for ease`}
      className={`key-diamond absolute top-1/2 z-10 cursor-ew-resize border transition-shadow hover:shadow-[0_0_0_4px_#88c0ac44] ${on ? "border-[#fff3cc] shadow-[0_0_0_3px_#caa54766]" : "border-white/70"}`}
      style={{ left: k.t * pps, background: on ? "#caa547" : EASE_COLOR[ease] }} />
  );
}

const Curve = memo(function Curve({ spec, name, props, pps }: { spec: Spec; name: string; props: Record<string, PropDef>; pps: number }) {
  const w = spec.duration * pps, h = 26, keys = spec.tracks[name];
  const vs = keys.map((k) => +k.v), lo = Math.min(...vs), hi = Math.max(...vs);
  if (hi - lo < 1e-9) return null;
  let d = "";
  for (let x = 0; x <= w; x += Math.max(3, w / 1500)) {
    const v = valueAt(spec, props, name, x / pps), y = h - 5 - ((v - lo) / (hi - lo)) * (h - 10);
    d += (x ? "L" : "M") + x.toFixed(1) + " " + y.toFixed(1);
  }
  return <svg data-lanebg className="pointer-events-none absolute inset-0" width={w} height={h}><path d={d} fill="none" stroke="#88c0ac66" strokeWidth={1.5} /></svg>;
}, (a, b) => a.pps === b.pps && a.spec.duration === b.spec.duration && a.spec.tracks[a.name] === b.spec.tracks[b.name]);

function LaneLabel({ name, def, spec }: { name: string; def: PropDef; spec: Spec }) {
  const t = useTime(100), meta = useStore((s) => s.meta)!;
  const keys = spec.tracks[name] || [], here = keys.some((k) => Math.abs(k.t - t) < 0.5 / (spec.fps || 60) + 1e-6);
  const v = valueAt(spec, meta.props, name, t);
  return (
    <>
      <button onClick={(e) => { e.stopPropagation(); toggleKey(name); }} title="Add / remove a key at the playhead"
        className={`grid size-5 place-items-center rounded border text-[11px] ${here ? "border-gold/50 text-gold" : keys.length ? "border-transparent text-green" : "border-transparent text-faint hover:text-mute"}`}>◆</button>
      <span className="flex-1 truncate">{def.label}</span>
      <span className="num text-[10.5px] text-faint">{Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2)}</span>
    </>
  );
}

// for the video-time → take-time readout in tooltips elsewhere
export const takeTimeAt = (spec: Spec, t: number) => sourceTime(spec, t)[0];
