// The live preview: the same scene compose.Renderer exports, drawn on a
// canvas. Motion blur is left to the exporter ("Exact frame" shows it).
import { bgMatrix, invert, phoneMatrix } from "./geometry.ts";
import type { Geo, Plate, Spec, State } from "./types.ts";

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

export interface Scene {
  spec: Spec; geo: Geo; plate: Plate | null; S: State; themeBg: string;
  screen: HTMLVideoElement; plateVideo: HTMLVideoElement; frame: HTMLImageElement;
}

export class Preview {
  private bg = document.createElement("canvas");
  private tiles = document.createElement("canvas");
  private vig = document.createElement("canvas");
  private bgKey = "";
  private vigKey = "";

  // Vignette as the exporter multiplies it: (1 − v·r²), r = distance from the
  // centre normalised to the corner.
  private vignette(w: number, h: number, v: number) {
    const key = `${w}|${h}|${v}`;
    if (key !== this.vigKey) {
      this.vigKey = key;
      this.vig.width = w; this.vig.height = h;
      const c = this.vig.getContext("2d")!, img = c.createImageData(w, h);
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const r2 = Math.min(1, ((2 * x / w - 1) ** 2 + (2 * y / h - 1) ** 2) / 2);
        img.data[(y * w + x) * 4 + 3] = Math.round(255 * v * r2);
      }
      c.putImageData(img, 0, 0);
    }
    return this.vig;
  }

  draw(ctx: CanvasRenderingContext2D, sc: Scene) {
    const { spec, geo, plate, S } = sc, { W, H, g } = geo;
    const cw = ctx.canvas.width, ch = ctx.canvas.height, ps = cw / W;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.filter = "none"; ctx.globalAlpha = 1;
    ctx.fillStyle = sc.themeBg; ctx.fillRect(0, 0, cw, ch);
    // background: the plate holds the least blur; the rest is added here, in
    // plate space, before the camera transform, as the exporter does
    if (plate && sc.plateVideo.readyState >= 2) {
      const { w, h, k } = plate;
      const extra = Math.sqrt(Math.max(0, S.bg_blur ** 2 - plate.blur ** 2)) * k;
      const m = bgMatrix(spec, geo, plate, S), inv = invert(m);
      const corners = [[0, 0], [W, 0], [0, H], [W, H]].map(([x, y]) => [inv[0] * x + inv[2] * y + inv[4], inv[1] * x + inv[3] * y + inv[5]]);
      const edge = Math.max(0, ...corners.flatMap(([x, y]) => [-x, x - w, -y, y - h]));
      const pad = Math.ceil(Math.max(w, h, edge + extra * 4) / 64) * 64;
      const bw = w + 2 * pad, bh = h + 2 * pad;
      const key = [sc.plateVideo.src, sc.plateVideo.currentTime.toFixed(3), extra.toFixed(2), bw, bh].join("|");
      if (key !== this.bgKey) {
        this.bgKey = key;
        if (this.tiles.width !== bw || this.tiles.height !== bh) { this.tiles.width = bw; this.tiles.height = bh; this.bg.width = bw; this.bg.height = bh; }
        const tc = this.tiles.getContext("2d")!;
        // reflected tiles: the same edge extension as BORDER_REFLECT in compose.py
        const nx = Math.ceil(pad / w), ny = Math.ceil(pad / h);
        for (let iy = -ny; iy <= ny; iy++) for (let ix = -nx; ix <= nx; ix++) {
          const fx = Math.abs(ix % 2) === 1, fy = Math.abs(iy % 2) === 1;
          tc.save();
          tc.translate(pad + ix * w + (fx ? w : 0), pad + iy * h + (fy ? h : 0));
          tc.scale(fx ? -1 : 1, fy ? -1 : 1);
          tc.drawImage(sc.plateVideo, 0, 0, w, h);
          tc.restore();
        }
        const bc = this.bg.getContext("2d")!;
        bc.clearRect(0, 0, bw, bh);
        bc.filter = extra > 0.3 ? `blur(${extra}px)` : "none";
        bc.drawImage(this.tiles, 0, 0);
        bc.filter = "none";
      }
      ctx.save();
      ctx.setTransform(ps, 0, 0, ps, 0, 0); ctx.transform(...m);
      ctx.drawImage(this.bg, -pad, -pad);
      ctx.restore();
    }
    ctx.setTransform(ps, 0, 0, ps, 0, 0);
    const b = S.bg_brightness;           // towards white / towards black, like compose.py
    if (b) { ctx.globalAlpha = Math.min(1, Math.abs(b)); ctx.fillStyle = b > 0 ? "#fff" : "#000"; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1; }
    const vg = +spec.background.vignette;
    if (vg > 0) ctx.drawImage(this.vignette(Math.max(1, Math.round(cw / 2)), Math.max(1, Math.round(ch / 2)), vg), 0, 0, W, H);
    const m = phoneMatrix(spec, geo, S);
    const shd = +spec.phone.shadow;
    if (shd > 0) {
      ctx.save();
      ctx.setTransform(ps, 0, 0, ps, 0, 0);
      ctx.translate(0, H * 0.022 * S.zoom * S.phone_scale); ctx.transform(...m);
      ctx.filter = `blur(${g.H * 0.035 * Math.hypot(m[0], m[1]) * ps}px)`;
      ctx.globalAlpha = shd; ctx.fillStyle = "#000";
      roundRect(ctx, g.W / 2 - g.bw / 2, g.H / 2 - g.bh / 2, g.bw, g.bh, g.body_r); ctx.fill();
      ctx.restore();
    }
    ctx.save();
    ctx.setTransform(ps, 0, 0, ps, 0, 0); ctx.transform(...m);
    ctx.save();
    roundRect(ctx, g.sx0, g.sy0, g.sw, g.sh, g.screen_r); ctx.clip();
    if (geo.clip && sc.screen.readyState >= 2) ctx.drawImage(sc.screen, g.sx0, g.sy0, g.sw, g.sh);
    else {
      ctx.fillStyle = sc.themeBg; ctx.fillRect(g.sx0, g.sy0, g.sw, g.sh);
      ctx.fillStyle = "#a49c8c"; ctx.font = `500 ${g.sw * 0.045}px system-ui`; ctx.textAlign = "center";
      ctx.fillText(geo.clip ? "Loading the take…" : "No take yet", g.sx0 + g.sw / 2, g.sy0 + g.sh / 2);
    }
    ctx.restore();
    if (sc.frame.complete && sc.frame.naturalWidth) ctx.drawImage(sc.frame, 0, 0, g.W, g.H);
    ctx.restore();
  }
}
