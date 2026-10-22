// The preview: the exported scene, live. Drag to pan, scroll to zoom,
// Shift-scroll to rotate, Alt to move / scale the phone. Changes key the
// property at the playhead once it is animated (store.setProp).
import { useEffect, useRef, useState } from "react";
import { track } from "../lib/drag.ts";
import { stateAt } from "../lib/model.ts";
import { phoneMatrix } from "../lib/geometry.ts";
import { Preview } from "../lib/render.ts";
import { clock, media } from "../state/clock.ts";
import { endGesture, hideExact, setProp, setUI, useStore } from "../state/store.ts";
import { Button, Icon } from "./ui.tsx";

export function Stage() {
  const wrap = useRef<HTMLDivElement>(null), canvas = useRef<HTMLCanvasElement>(null);
  const scene = useRef<HTMLDivElement>(null), phone = useRef<HTMLDivElement>(null), screen = useRef<HTMLDivElement>(null);
  const [preview] = useState(() => new Preview());
  const spec = useStore((s) => s.spec), geo = useStore((s) => s.geo), plate = useStore((s) => s.plate);
  const meta = useStore((s) => s.meta), exact = useStore((s) => s.exact);
  const box = useRef({ w: 0, h: 0 });

  // Keep the video in the browser's video compositor. Copying a full 1080 ×
  // 2400 video through Canvas every tick dominated preview time on CPU decode.
  useEffect(() => {
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
      phone.current!.style.transform = `matrix(${phoneMatrix(s.spec, s.geo, S).map((v) => v * scale).join(",")})`;
      screen.current!.style.background = s.meta.themes[s.spec.app.theme]?.bg ?? "#111";
      preview.draw(c.getContext("2d", { alpha: false, desynchronized: true })!, {
        spec: s.spec, geo: s.geo, plate: s.plate, S, nativePhone: true,
        themeBg: s.meta.themes[s.spec.app.theme]?.bg ?? "#111", screen: media.screen, plateVideo: media.plate, frame: media.frame,
      });
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
    e.preventDefault(); clock.pause(); hideExact();
    const rect = canvas.current!.getBoundingClientRect(), outPerPx = s.geo.W / rect.width;
    const S0 = stateAt(s.spec, s.meta.props, clock.t), x0 = e.clientX, y0 = e.clientY, phone = e.altKey;
    const tag = `drag:${phone ? "phone" : "cam"}:${performance.now()}`;
    const r = (S0.rotate * Math.PI) / 180, c = Math.cos(-r), sn = Math.sin(-r);
    track(e, canvas.current!, (m) => {
      const sx = (m.clientX - x0) * outPerPx, sy = (m.clientY - y0) * outPerPx;
      const dx = c * sx - sn * sy, dy = sn * sx + c * sy, W = s.geo!.W, H = s.geo!.H;
      if (phone) { setProp("phone_x", S0.phone_x + dx / (S0.zoom * W), tag); setProp("phone_y", S0.phone_y + dy / (S0.zoom * H), tag); }
      else { setProp("pan_x", S0.pan_x - dx / (S0.zoom * W), tag); setProp("pan_y", S0.pan_y - dy / (S0.zoom * H), tag); }
    }, () => endGesture(), { cursor: "grab" });
  };
  // a non-passive wheel listener: the page must not scroll under the preview
  useEffect(() => {
    const c = canvas.current!;
    const onWheel = (e: WheelEvent) => {
      const s = useStore.getState();
      if (!s.spec || !s.meta) return;
      e.preventDefault(); clock.pause(); hideExact();
      const S = stateAt(s.spec, s.meta.props, clock.t), f = Math.exp(-e.deltaY * 0.0015);
      if (e.altKey) setProp("phone_scale", S.phone_scale * f, "wheel-phone");
      else if (e.shiftKey) setProp("rotate", S.rotate + (e.deltaY > 0 ? 0.5 : -0.5), "wheel-rot");
      else setProp("zoom", S.zoom * f, "wheel-zoom");
    };
    c.addEventListener("wheel", onWheel, { passive: false });
    return () => c.removeEventListener("wheel", onWheel);
  }, []);

  return (
    <div ref={wrap} className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden bg-[#0c0b0a]">
      <div ref={scene} className="relative shrink-0 overflow-hidden rounded-sm shadow-[0_0_0_1px_var(--color-line)]">
        <canvas ref={canvas} onPointerDown={onPointerDown} className="block cursor-grab"
          title="Drag: pan · Scroll: zoom · Shift-scroll: rotate · Alt-drag / Alt-scroll: move / scale the phone" />
        <div ref={phone} className="pointer-events-none absolute left-0 top-0 origin-top-left" style={{ width: geo?.g.W, height: geo?.g.H, visibility: geo ? "visible" : "hidden" }}>
          <div ref={screen} className="absolute overflow-hidden" style={{ left: geo?.g.sx0, top: geo?.g.sy0, width: geo?.g.sw, height: geo?.g.sh, borderRadius: geo?.g.screen_r }} />
        </div>
      </div>
      {exact && (
        <img src={exact} alt="exact frame" onClick={hideExact} style={{ width: box.current.w, height: box.current.h }}
          className="absolute cursor-pointer rounded-sm outline outline-2 outline-gold" title="The exporter's own frame (with motion blur) · click to hide" />
      )}
      {spec && geo && !geo.clip && (
        <div className="absolute bottom-3 left-1/2 flex w-max max-w-[calc(100%-24px)] -translate-x-1/2 flex-wrap items-center justify-center gap-3 rounded-lg border border-line bg-panel/95 px-4 py-2.5 text-center shadow-xl">
          <span className="text-mute">This video has no take yet.</span>
          <Button variant="primary" size="sm" onClick={() => setUI({ tab: "recording", hideR: false })}>{Icon.rec} Record a take</Button>
        </div>
      )}
      {spec && !geo && <p role="status" className="absolute rounded bg-panel/95 px-3 py-2 text-mute">Loading take…</p>}
      {!spec && <p className="px-3 text-center text-faint">Pick a video on the left, or make a new one.</p>}
    </div>
  );
}
