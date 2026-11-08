// The preview: the exported scene, live. Drag to pan, scroll to zoom,
// Shift-scroll to rotate, Alt to move / scale the phone. Changes key the
// property at the playhead once it is animated (store.setProp).
import { useEffect, useRef, useState } from "react";
import { track } from "../lib/drag.ts";
import { sourceTime, stateAt } from "../lib/model.ts";
import { phoneMatrix, snapPanToPhone, snapPhoneToCentre } from "../lib/geometry.ts";
import { previewContext } from "../lib/inspector.ts";
import { oldTakeWarning } from "../lib/build.ts";
import { GLPreview } from "../lib/glpreview.ts";
import { Preview } from "../lib/render.ts";
import { clock, media } from "../state/clock.ts";
import { cancelJob, endGesture, hideExact, inspect, record, select, setProp, setScreenNeed, setUI, useStore } from "../state/store.ts";
import { Button, Icon, Progress } from "./ui.tsx";

const SNAP_PX = 12;   // screen px within which Shift snaps the phone to a centre line

export function Stage() {
  const wrap = useRef<HTMLDivElement>(null), canvas = useRef<HTMLCanvasElement>(null);
  const scene = useRef<HTMLDivElement>(null), phone = useRef<HTMLDivElement>(null), screen = useRef<HTMLDivElement>(null);
  const [preview] = useState(() => new Preview());
  const spec = useStore((s) => s.spec), geo = useStore((s) => s.geo), plate = useStore((s) => s.plate);
  const meta = useStore((s) => s.meta), exact = useStore((s) => s.exact);
  const oldTake = oldTakeWarning(geo?.build);
  // The recording in flight for this video: the pill below turns into its status.
  const capture = useStore((s) => s.jobs.find((j) => j.kind === "capture" && j.name === s.name && (j.state === "running" || j.state === "queued")));
  const box = useRef({ w: 0, h: 0 });
  // Shift while dragging: the phone's centre snaps to the frame's centre lines.
  // null = no guides; else which of the two lines the phone is snapped to.
  const [guides, setGuides] = useState<{ v: boolean; h: boolean } | null>(null);

  // The scene draws on the GPU in one pass (lib/glpreview.ts): every layer
  // mipmapped at its exact sub-pixel place, so slow camera moves neither
  // shimmer nor wiggle the phone against the background. Without WebGL2 the
  // canvas draws the background and the browser's compositor the phone.
  const gl = useRef<GLPreview | null | undefined>(undefined);
  useEffect(() => {
    gl.current = GLPreview.create(canvas.current!, () => paint());
    if (gl.current) { phone.current!.style.display = "none"; return; }
    screen.current!.append(media.screen);
    phone.current!.append(media.frame);
    Object.assign(media.screen.style, { width: "100%", height: "100%", display: "block" });
    Object.assign(media.frame.style, { position: "absolute", inset: "0", width: "100%", height: "100%" });
    return () => { media.screen.remove(); media.frame.remove(); };
  }, []);

  // size the canvas to fit its pane at the output's aspect
  useEffect(() => {
    const el = wrap.current!;
    const fit = () => {
      const g = useStore.getState().geo, c = canvas.current;
      if (!g || !c) return;
      const pad = 24, k = Math.max(0.05, Math.min((el.clientWidth - pad) / g.W, (el.clientHeight - pad) / g.H));
      const dpr = devicePixelRatio || 1, w = Math.round(g.W * k), h = Math.round(g.H * k);
      if (w === box.current.w && h === box.current.h) return;
      box.current = { w, h };
      c.style.width = w + "px"; c.style.height = h + "px";
      scene.current!.style.width = w + "px"; scene.current!.style.height = h + "px";
      c.width = Math.round(w * dpr); c.height = Math.round(h * dpr);
      paint();
    };
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    fit();
    return () => ro.disconnect();
  }, [geo?.W, geo?.H]);

  // redraw on every clock tick, media frame and state change, batched per frame
  const raf = useRef(0);
  const paint = () => {
    if (raf.current) return;
    raf.current = requestAnimationFrame(() => {
      raf.current = 0;
      const s = useStore.getState(), c = canvas.current;
      if (!c || !s.spec || !s.geo || !s.meta) return;
      const S = stateAt(s.spec, s.meta.props, clock.t), scale = box.current.w / s.geo.W;
      const recording = sourceTime(s.spec, clock.t)[1] >= 0;
      const scene = {
        spec: s.spec, geo: s.geo, plate: s.plate, S, recording,
        themeBg: s.meta.themes[s.spec.app.theme]?.bg ?? "#111", screen: media.screen, plateVideo: media.plate, frame: media.frame,
      };
      // how tall the phone's screen gets on this stage, in device px: picks the picture's source
      const most = (n: string) => Math.max(+(s.spec!.props[n] ?? s.meta!.props[n]?.default ?? 1), ...(s.spec!.tracks[n] || []).map((k) => +k.v));
      setScreenNeed(s.geo.g.sh * (s.spec.phone.height * s.geo.H / s.geo.g.bh) * most("zoom") * most("phone_scale") * (c.height / s.geo.H));
      if (gl.current) return gl.current.draw(scene);
      if (gl.current === undefined) return;            // the renderer is chosen on mount
      phone.current!.style.visibility = recording ? "visible" : "hidden";
      phone.current!.style.transform = `matrix(${phoneMatrix(s.spec, s.geo, S).map((v) => v * scale).join(",")})`;
      screen.current!.style.background = scene.themeBg;
      preview.draw(c.getContext("2d", { alpha: false })!, { ...scene, nativePhone: true });
    });
  };
  useEffect(() => {
    const offs = [clock.subscribe(paint), clock.onMedia(paint)];
    const onLoad = () => paint();
    for (const m of [media.screen, media.plate]) m.addEventListener("loadeddata", onLoad);
    media.frame.addEventListener("load", onLoad);
    return () => { offs.forEach((f) => f()); for (const m of [media.screen, media.plate]) m.removeEventListener("loadeddata", onLoad); media.frame.removeEventListener("load", onLoad); };
  }, []);
  useEffect(paint, [spec, geo, plate, meta]);

  const onPointerDown = (e: React.PointerEvent) => {
    const s = useStore.getState();
    if (e.button !== 0 || !s.spec || !s.geo || !s.meta) return;
    e.preventDefault(); clock.pause(); hideExact(); select(null);
    const rect = canvas.current!.getBoundingClientRect(), outPerPx = s.geo.W / rect.width;
    const S0 = stateAt(s.spec, s.meta.props, clock.t), x0 = e.clientX, y0 = e.clientY, phone = e.altKey;
    const tag = `drag:${phone ? "phone" : "cam"}:${performance.now()}`;
    let moved = false;
    const r = (S0.rotate * Math.PI) / 180, c = Math.cos(-r), sn = Math.sin(-r);
    // Shift snaps the phone's centre to the frame's centre lines, whether the
    // drag moves the phone itself (Alt) or pans the camera under it.
    let last = { x: x0, y: y0, shift: e.shiftKey };
    const step = () => {
      const sx = (last.x - x0) * outPerPx, sy = (last.y - y0) * outPerPx;
      const dx = c * sx - sn * sy, dy = sn * sx + c * sy, W = s.geo!.W, H = s.geo!.H, thr = SNAP_PX * outPerPx;
      let snap: { snapX: boolean; snapY: boolean } | null = null;
      if (phone) {
        let px = S0.phone_x + dx / (S0.zoom * W), py = S0.phone_y + dy / (S0.zoom * H);
        if (last.shift) { const q = snapPhoneToCentre(s.geo!, S0, px, py, thr); px = q.x; py = q.y; snap = q; }
        setProp("phone_x", px, tag); setProp("phone_y", py, tag);
      } else {
        let nx = S0.pan_x - dx / (S0.zoom * W), ny = S0.pan_y - dy / (S0.zoom * H);
        if (last.shift) { const q = snapPanToPhone(s.geo!, S0, nx, ny, thr); nx = q.x; ny = q.y; snap = q; }
        setProp("pan_x", nx, tag); setProp("pan_y", ny, tag);
      }
      const g = snap ? { v: snap.snapX, h: snap.snapY } : null;
      setGuides((o) => (o === g || (o && g && o.v === g.v && o.h === g.h) ? o : g));
    };
    // pressing or releasing Shift mid-drag applies at once, without a mouse move
    const onKey = (k: KeyboardEvent) => { if (k.key === "Shift" && moved && k.shiftKey !== last.shift) { last = { ...last, shift: k.shiftKey }; step(); } };
    window.addEventListener("keydown", onKey); window.addEventListener("keyup", onKey);
    track(e, canvas.current!, (m) => {
      if (!moved && Math.hypot(m.clientX - x0, m.clientY - y0) < 3) return;
      moved = true;
      last = { x: m.clientX, y: m.clientY, shift: m.shiftKey };
      step();
    }, (up) => {
      window.removeEventListener("keydown", onKey); window.removeEventListener("keyup", onKey);
      setGuides(null);
      endGesture(); if (up.type === "pointercancel") return;
      inspect(moved ? (phone ? "phone-motion" : "camera") : sourceTime(s.spec!, clock.t)[1] < 0 ? "background-look" : previewContext(s.spec!, s.geo!, S0, (x0 - rect.left) * outPerPx, (y0 - rect.top) * outPerPx), !moved || innerWidth > 1000);
    }, { cursor: "grab" });
  };
  // a non-passive wheel listener: the page must not scroll under the preview
  useEffect(() => {
    const c = canvas.current!;
    let timer: number | undefined;
    const onWheel = (e: WheelEvent) => {
      const s = useStore.getState();
      if (!s.spec || !s.meta) return;
      e.preventDefault(); clock.pause(); hideExact();
      const S = stateAt(s.spec, s.meta.props, clock.t), f = Math.exp(-e.deltaY * 0.0015);
      if (e.altKey) setProp("phone_scale", S.phone_scale * f, "wheel-phone");
      else if (e.shiftKey) setProp("rotate", S.rotate + (e.deltaY > 0 ? 0.5 : -0.5), "wheel-rot");
      else setProp("zoom", S.zoom * f, "wheel-zoom");
      const target = e.altKey ? "phone-motion" : "camera";
      if (s.inspector !== target) inspect(target, false);
      clearTimeout(timer);
      timer = window.setTimeout(() => {
        if (useStore.getState().inspector === target && !document.body.classList.contains("dragging")) inspect(target);
      }, 220);
    };
    c.addEventListener("wheel", onWheel, { passive: false });
    return () => { clearTimeout(timer); c.removeEventListener("wheel", onWheel); };
  }, []);

  return (
    <div ref={wrap} onPointerDown={(e) => { if (e.button === 0 && e.target === e.currentTarget && spec) inspect("canvas", true); }} className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden bg-[#0c0b0a]">
      <div ref={scene} className="relative shrink-0 overflow-hidden rounded-sm shadow-[0_0_0_1px_var(--color-line)]">
        <canvas ref={canvas} onPointerDown={onPointerDown} className="block cursor-grab"
          title="Click phone / background: appearance · Drag: pan (hold Shift to snap the phone to the centre) · Scroll: zoom · Shift-scroll: rotate · Alt-drag / Alt-scroll: move / scale the phone (Shift snaps too)" />
        <div ref={phone} className="pointer-events-none absolute left-0 top-0 origin-top-left" style={{ width: geo?.g.W, height: geo?.g.H, visibility: geo ? "visible" : "hidden" }}>
          <div ref={screen} className="absolute overflow-hidden" style={{ left: geo?.g.sx0, top: geo?.g.sy0, width: geo?.g.sw, height: geo?.g.sh, borderRadius: geo?.g.screen_r }} />
        </div>
      </div>
      {guides && (
        <div className="pointer-events-none absolute" style={{ width: box.current.w, height: box.current.h }}>
          <span className={`absolute inset-y-0 left-1/2 w-px -translate-x-1/2 ${guides.v ? "bg-gold" : "bg-gold/35"}`} />
          <span className={`absolute inset-x-0 top-1/2 h-px -translate-y-1/2 ${guides.h ? "bg-gold" : "bg-gold/35"}`} />
        </div>
      )}
      {oldTake && !capture && (
        <div role="status" title={`${oldTake}\n${geo!.build!.changes.map((c) => "· " + c).join("\n")}`}
          className="absolute left-1/2 top-3 flex max-w-[calc(100%-24px)] -translate-x-1/2 items-center gap-2 rounded-full border border-gold/50 bg-panel/95 py-1 pl-3 pr-1 text-[12px] shadow-lg">
          <span className="size-1.5 shrink-0 rounded-full bg-gold" />
          <span className="truncate text-ink">Take is from an older app</span>
          <Button size="sm" variant="ghost" onClick={() => { setUI({ tab: "recording", hideR: false }); void record(null, true); }}>Record again</Button>
        </div>
      )}
      {exact && (
        <img src={exact} alt="exact frame" onClick={hideExact} style={{ width: box.current.w, height: box.current.h }}
          className="absolute cursor-pointer rounded-sm outline outline-2 outline-gold" title="The exporter's own frame (with motion blur) · click to hide" />
      )}
      {spec && geo && capture && (
        <div role="status" className="absolute bottom-3 left-1/2 w-[min(420px,calc(100%-24px))] -translate-x-1/2 rounded-lg border border-orange/40 bg-panel/95 px-4 py-2.5 shadow-xl">
          <div className="flex items-center gap-2">
            <span className="size-2 shrink-0 rounded-full bg-orange animate-pulse-soft" />
            <span className="font-semibold">{capture.state === "queued" ? "Waiting for the emulator" : "Recording a take"}</span>
            {capture.serial && <span className="text-faint">on {capture.serial}</span>}
            <div className="flex-1" />
            {capture.state === "running" && <span className="num text-[12px] text-mute">{Math.round(capture.progress * 100)}%</span>}
            <Button size="sm" variant="ghost" onClick={() => cancelJob(capture.id)}>Cancel</Button>
          </div>
          <div className="mt-2"><Progress value={capture.progress} tone="gold" indeterminate={capture.state === "queued"} /></div>
          <p className="num mt-1.5 break-words text-[12px] text-mute">{capture.msg}</p>
        </div>
      )}
      {spec && geo && !geo.clip && !capture && (
        <div className="absolute bottom-3 left-1/2 flex w-max max-w-[calc(100%-24px)] -translate-x-1/2 flex-wrap items-center justify-center gap-3 rounded-lg border border-line bg-panel/95 px-4 py-2.5 text-center shadow-xl">
          <span className="text-mute">This video has no take yet.</span>
          <Button variant="primary" size="sm" onClick={() => { setUI({ tab: "recording", hideR: false }); void record(null, false); }}>{Icon.rec} Record a take</Button>
        </div>
      )}
      {spec && !geo && <p role="status" className="absolute rounded bg-panel/95 px-3 py-2 text-mute">Loading take…</p>}
      {!spec && <p className="px-3 text-center text-faint">Pick a video on the left, or make a new one.</p>}
    </div>
  );
}
