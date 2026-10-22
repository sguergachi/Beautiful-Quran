import { useEffect, useRef } from "react";
import { sourceTime } from "../lib/model.ts";
import { clock, media } from "../state/clock.ts";
import { useStore } from "../state/store.ts";

/** A whole-phone view for appearance edits, sharing the stage's decoded take. */
export function PhoneLookPreview() {
  const canvas = useRef<HTMLCanvasElement>(null), refresh = useRef(() => {});
  const geo = useStore((s) => s.geo), phone = useStore((s) => s.spec?.phone);
  const theme = useStore((s) => s.spec?.app.theme), meta = useStore((s) => s.meta);

  useEffect(() => {
    const c = canvas.current!, ctx = c.getContext("2d", { alpha: false })!;
    let raf = 0, visible = false, last = -Infinity;
    const paint = () => {
      raf = 0;
      if (!visible || document.hidden) return;
      const { spec, geo, meta } = useStore.getState();
      const w = c.clientWidth, h = c.clientHeight, dpr = Math.min(devicePixelRatio || 1, 1.5);
      if (!w || !h) return;
      const cw = Math.round(w * dpr), ch = Math.round(h * dpr);
      if (c.width !== cw || c.height !== ch) { c.width = cw; c.height = ch; }
      ctx.setTransform(cw / w, 0, 0, ch / h, 0, 0);
      ctx.fillStyle = "#39352f"; ctx.fillRect(0, 0, w, h);
      if (!spec || !geo) return;
      last = performance.now();
      const { g } = geo;
      // Reserve room for the largest base size and its shadow. Camera crops
      // stay on the main stage; the entire bezel remains available here.
      const k = Math.min((w - 48) / g.W, (h - 48) / g.H) * spec.phone.height / 1.2;
      const x = (w - g.W * k) / 2, y = (h - g.H * k) / 2;
      const path = (px: number, py: number, pw: number, ph: number, r: number) => {
        ctx.beginPath(); ctx.roundRect(x + px * k, y + py * k, pw * k, ph * k, r * k);
      };
      ctx.save();
      ctx.shadowColor = `rgba(0,0,0,${spec.phone.shadow})`;
      ctx.shadowBlur = g.H * k * 0.035 * dpr;
      ctx.shadowOffsetY = g.H * k * 0.022 * dpr;
      ctx.fillStyle = "#111";
      path((g.W - g.bw) / 2, (g.H - g.bh) / 2, g.bw, g.bh, g.body_r); ctx.fill();
      ctx.restore();
      ctx.save();
      path(g.sx0, g.sy0, g.sw, g.sh, g.screen_r); ctx.clip();
      ctx.fillStyle = meta?.themes[spec.app.theme]?.bg ?? "#111";
      ctx.fillRect(x + g.sx0 * k, y + g.sy0 * k, g.sw * k, g.sh * k);
      const matches = (src: string, path: string) => src === new URL(path, location.href).href;
      const gap = sourceTime(spec, clock.t)[1] < 0;
      if (!gap && geo.clip && matches(media.screen.currentSrc, geo.clip) && media.screen.readyState >= 2) {
        ctx.drawImage(media.screen, x + g.sx0 * k, y + g.sy0 * k, g.sw * k, g.sh * k);
      } else {
        ctx.fillStyle = "#a49c8c"; ctx.font = "10px system-ui"; ctx.textAlign = "center";
        ctx.fillText(gap ? "Gap in recording" : geo.clip ? "Loading…" : "No take yet", w / 2, h / 2);
      }
      ctx.restore();
      if (media.frame.complete && media.frame.naturalWidth && matches(media.frame.src, geo.frame)) {
        ctx.drawImage(media.frame, x, y, g.W * k, g.H * k);
      }
    };
    const schedule = () => { if (visible && !document.hidden && !raf) raf = requestAnimationFrame(paint); };
    refresh.current = schedule;
    const resize = new ResizeObserver(schedule); resize.observe(c);
    const intersection = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; if (visible) schedule(); });
    intersection.observe(c);
    // No extra video element/decoder, full-resolution scratch image, or polling
    // loop. Offscreen previews do no work; visible playback is capped at 8 fps.
    const off = clock.subscribe(() => { if (!clock.playing || performance.now() - last >= 125) schedule(); });
    const offMedia = clock.onMedia(schedule);
    media.frame.addEventListener("load", schedule);
    document.addEventListener("visibilitychange", schedule);
    return () => {
      refresh.current = () => {};
      off(); offMedia(); resize.disconnect(); intersection.disconnect(); cancelAnimationFrame(raf);
      media.frame.removeEventListener("load", schedule);
      document.removeEventListener("visibilitychange", schedule);
    };
  }, []);
  useEffect(() => refresh.current(), [geo, phone, theme, meta]);

  return (
    <figure className="mb-4">
      <canvas ref={canvas} role="img" aria-label="Phone appearance preview" className="block h-[280px] w-full rounded-md bg-[#39352f]" />
      <figcaption className="mt-1.5 text-[11px] text-faint">Appearance before camera moves</figcaption>
    </figure>
  );
}
