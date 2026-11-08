// The live preview on the GPU: the scene compose.Renderer exports, drawn in
// one WebGL2 pass per frame with the same matrices the canvas path uses.
//
// Why not the browser's own video layers: the phone's screen recording is
// 1440 × 3200 and lands a few hundred pixels tall, and a compositor shrinks
// video with plain bilinear filtering, so its text shimmers and crawls as the
// camera eases; a separate DOM layer for the phone is also snapped and
// composited apart from the canvas behind it, so the two wiggle against each
// other. Here every layer is sampled through mipmaps (trilinear, anisotropic)
// at its exact sub-pixel place, in the same pass, every frame. A video is
// uploaded only when it presents a new frame, and a background that is still
// seeking keeps its last frame instead of flashing.
import { bgMatrix, invert, phoneMatrix, type M } from "./geometry.ts";
import type { PhoneGeometry } from "./types.ts";
import type { Scene } from "./render.ts";

const VS = `#version 300 es
in vec2 a_unit;
uniform vec4 u_rect;     // x, y, w, h in the layer's own px
uniform mat3 u_m;        // layer px -> frame px
uniform vec2 u_out;      // frame size
out vec2 v_src;
void main() {
  vec2 s = u_rect.xy + a_unit * u_rect.zw;
  v_src = s;
  vec2 o = (u_m * vec3(s, 1.0)).xy / u_out * 2.0 - 1.0;
  gl_Position = vec4(o.x, -o.y, 0.0, 1.0);
}`;

// Background: plate (or its blurred, padded copy) through the inverse camera,
// then brightness towards white / black and the vignette, as compose.py does.
const FS_BG = `#version 300 es
precision highp float;
in vec2 v_src;
uniform sampler2D u_tex;
uniform bool u_has;
uniform mat3 u_inv;      // frame px -> plate px
uniform vec4 u_box;      // texture origin (plate px) and size
uniform vec3 u_theme;
uniform float u_bright, u_vig;
uniform vec2 u_out;
out vec4 o;
void main() {
  vec3 c = u_theme;
  if (u_has) {
    vec2 p = (u_inv * vec3(v_src, 1.0)).xy;
    c = texture(u_tex, (p - u_box.xy) / u_box.zw).rgb;
  }
  float b = u_bright;
  c = b > 0.0 ? mix(c, vec3(1.0), min(1.0, b)) : mix(c, vec3(0.0), min(1.0, -b));
  vec2 q = 2.0 * v_src / u_out - 1.0;
  c *= 1.0 - u_vig * min(1.0, dot(q, q) / 2.0);
  o = vec4(c, 1.0);
}`;

// A textured layer (premultiplied), optionally clipped to a rounded rect with
// a one-pixel analytic edge, optionally flat colour.
const FS_LAYER = `#version 300 es
precision highp float;
in vec2 v_src;
uniform sampler2D u_tex;
uniform vec4 u_rect;
uniform float u_alpha, u_radius;
uniform int u_mode;      // 0 texture, 1 texture clipped, 2 flat clipped
uniform vec3 u_flat;
out vec4 o;
float rr(vec2 p, vec2 h, float r) { vec2 d = abs(p) - h + r; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r; }
void main() {
  vec2 uv = (v_src - u_rect.xy) / u_rect.zw;
  vec4 c = u_mode == 2 ? vec4(u_flat, 1.0) : texture(u_tex, uv);
  if (u_mode > 0) {
    float d = rr(v_src - (u_rect.xy + u_rect.zw * 0.5), u_rect.zw * 0.5, u_radius);
    c *= clamp(0.5 - d / max(fwidth(d), 1e-4), 0.0, 1.0);
  }
  o = c * u_alpha;
}`;

// One direction of a Gaussian, two texels per tap (linear sampling).
const FS_BLUR = `#version 300 es
precision highp float;
in vec2 v_src;
uniform sampler2D u_tex;
uniform vec2 u_dir;      // one texel along the blur, in source uv
uniform vec4 u_map;      // dest px -> source uv: uv = (v_src + map.xy) * map.zw
uniform float u_w[64];
uniform float u_o[64];
uniform int u_n;
out vec4 o;
void main() {
  vec2 uv = (v_src + u_map.xy) * u_map.zw;
  vec4 c = texture(u_tex, uv) * u_w[0];
  for (int i = 1; i < 64; i++) {
    if (i >= u_n) break;
    c += (texture(u_tex, uv + u_dir * u_o[i]) + texture(u_tex, uv - u_dir * u_o[i])) * u_w[i];
  }
  o = c;
}`;

type Prog = { p: WebGLProgram; u: Record<string, WebGLUniformLocation | null> };
type Tex = { t: WebGLTexture; w: number; h: number; key: string; mips: boolean };

const mat3 = (m: M) => new Float32Array([m[0], m[1], 0, m[2], m[3], 0, m[4], m[5], 1]);
const hexRgb = (hex: string): [number, number, number] => {
  const h = hex.replace("#", ""), n = h.length === 3 ? h.split("").map((x) => x + x).join("") : h.padEnd(6, "0");
  return [parseInt(n.slice(0, 2), 16) / 255, parseInt(n.slice(2, 4), 16) / 255, parseInt(n.slice(4, 6), 16) / 255];
};

/** Gaussian weights for sigma, folded into linear-sampled taps (centre + pairs). */
export function gaussTaps(sigma: number): { w: number[]; o: number[] } {
  const r = Math.min(126, Math.ceil(sigma * 3)), k: number[] = [];
  for (let i = 0; i <= r; i++) k.push(Math.exp(-(i * i) / (2 * sigma * sigma)));
  const sum = k[0] + 2 * k.slice(1).reduce((a, b) => a + b, 0);
  const w = [k[0] / sum], o = [0];
  for (let i = 1; i <= r; i += 2) {
    const a = k[i] / sum, b = (i + 1 <= r ? k[i + 1] : 0) / sum;
    w.push(a + b); o.push(a + b > 0 ? (i * a + (i + 1) * b) / (a + b) : i);
  }
  return { w, o };
}

export class GLPreview {
  private gl: WebGL2RenderingContext;
  private bg!: Prog; private layer!: Prog; private blur!: Prog;
  private vao!: WebGLVertexArrayObject;
  private tex = new Map<string, Tex>();
  private fbo: { a: WebGLFramebuffer; b: WebGLFramebuffer; ta: WebGLTexture; tb: WebGLTexture; w: number; h: number; key: string } | null = null;
  private aniso = 0;
  private fresh = new WeakMap<HTMLVideoElement, boolean>();     // a new frame waits to be uploaded
  private watched = new WeakSet<HTMLVideoElement>();
  private lastTime = new WeakMap<HTMLVideoElement, number>();
  private uploads = new Map<string, number>();                 // per texture: how many frames it has had
  private shadow = document.createElement("canvas");
  private shadowKey = "";
  lost = false;

  static create(canvas: HTMLCanvasElement, invalidate: () => void): GLPreview | null {
    try {
      const gl = canvas.getContext("webgl2", { alpha: false, antialias: false, premultipliedAlpha: true, preserveDrawingBuffer: false, powerPreference: "high-performance" });
      return gl ? new GLPreview(gl, canvas, invalidate) : null;
    } catch { return null; }
  }

  private invalidate: () => void;
  private constructor(gl: WebGL2RenderingContext, canvas: HTMLCanvasElement, invalidate: () => void) {
    this.gl = gl;
    this.invalidate = invalidate;
    this.init();
    canvas.addEventListener("webglcontextlost", (e) => { e.preventDefault(); this.lost = true; });
    canvas.addEventListener("webglcontextrestored", () => { this.tex.clear(); this.fbo = null; this.shadowKey = ""; this.init(); this.lost = false; this.invalidate(); });
  }

  private init() {
    const gl = this.gl;
    const ext = gl.getExtension("EXT_texture_filter_anisotropic");
    this.aniso = ext ? Math.min(8, gl.getParameter(ext.MAX_TEXTURE_MAX_ANISOTROPY_EXT)) : 0;
    this.bg = this.program(FS_BG, ["u_rect", "u_m", "u_out", "u_tex", "u_has", "u_inv", "u_box", "u_theme", "u_bright", "u_vig"]);
    this.layer = this.program(FS_LAYER, ["u_rect", "u_m", "u_out", "u_tex", "u_alpha", "u_radius", "u_mode", "u_flat"]);
    this.blur = this.program(FS_BLUR, ["u_rect", "u_m", "u_out", "u_tex", "u_dir", "u_map", "u_w", "u_o", "u_n"]);
    this.vao = gl.createVertexArray()!;
    gl.bindVertexArray(this.vao);
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
    for (const pr of [this.bg, this.layer, this.blur]) {
      const loc = gl.getAttribLocation(pr.p, "a_unit");
      if (loc >= 0) { gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0); }
    }
  }

  private program(fs: string, names: string[]): Prog {
    const gl = this.gl, sh = (type: number, src: string) => {
      const s = gl.createShader(type)!;
      gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) || "shader");
      return s;
    };
    const p = gl.createProgram()!;
    gl.attachShader(p, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, "a_unit");
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p) || "link");
    const u: Prog["u"] = {};
    for (const n of names) u[n] = gl.getUniformLocation(p, n);
    return { p, u };
  }

  /** Mark a video's texture stale whenever it presents a frame (once per element). */
  private watch(v: HTMLVideoElement) {
    if (this.watched.has(v)) return;
    this.watched.add(v);
    const rvfc = (v as HTMLVideoElement & { requestVideoFrameCallback?: (cb: () => void) => number }).requestVideoFrameCallback;
    if (rvfc) {
      const again = () => { this.fresh.set(v, true); this.invalidate(); rvfc.call(v, again); };
      rvfc.call(v, again);
    }
    for (const ev of ["seeked", "loadeddata", "emptied"]) v.addEventListener(ev, () => { this.fresh.set(v, true); this.invalidate(); });
    this.fresh.set(v, true);
  }
  private videoChanged(v: HTMLVideoElement): boolean {
    if (typeof (v as { requestVideoFrameCallback?: unknown }).requestVideoFrameCallback === "function") { const f = !!this.fresh.get(v); this.fresh.set(v, false); return f; }
    const t = v.currentTime, last = this.lastTime.get(v);
    this.lastTime.set(v, t);
    const f = !!this.fresh.get(v); this.fresh.set(v, false);
    return f || t !== last || !v.paused;
  }

  /** Upload a source into a named texture; keeps the old pixels when the source has none yet. */
  private upload(name: string, src: TexImageSource, w: number, h: number, key: string, opts: { mips: boolean; wrap: number; premul: boolean }) {
    const gl = this.gl;
    let t = this.tex.get(name);
    if (!t) {
      t = { t: gl.createTexture()!, w: 0, h: 0, key: "", mips: opts.mips };
      this.tex.set(name, t);
    }
    gl.bindTexture(gl.TEXTURE_2D, t.t);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, opts.premul);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    if (t.w !== w || t.h !== h) {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
      t.w = w; t.h = h;
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, opts.wrap);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, opts.wrap);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, opts.mips ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
      if (opts.mips && this.aniso) gl.texParameterf(gl.TEXTURE_2D, 0x84FE /* TEXTURE_MAX_ANISOTROPY_EXT */, this.aniso);
    } else {
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, src);
    }
    if (opts.mips) gl.generateMipmap(gl.TEXTURE_2D);
    t.key = key;
    this.uploads.set(name, (this.uploads.get(name) ?? 0) + 1);
    return t;
  }

  private video(name: string, v: HTMLVideoElement, wrap: number, mips: boolean): Tex | null {
    this.watch(v);
    const t = this.tex.get(name), key = v.currentSrc || v.src;
    const ready = v.readyState >= 2 && v.videoWidth > 0;
    if (!ready) return t ?? null;          // still seeking or loading: hold the last frame rather than flash
    if (t && t.key === key && !this.videoChanged(v)) return t;
    try { return this.upload(name, v, v.videoWidth, v.videoHeight, key, { mips, wrap, premul: false }); }
    catch { return t ?? null; }
  }

  private image(name: string, img: HTMLImageElement | HTMLCanvasElement, key: string, mips: boolean): Tex | null {
    const t = this.tex.get(name);
    if (t && t.key === key) return t;
    const w = img instanceof HTMLImageElement ? img.naturalWidth : img.width, h = img instanceof HTMLImageElement ? img.naturalHeight : img.height;
    if (!w || !h || (img instanceof HTMLImageElement && !img.complete)) return t ?? null;
    return this.upload(name, img, w, h, key, { mips, wrap: this.gl.CLAMP_TO_EDGE, premul: true });
  }

  // The phone's soft shadow: blurred once at quarter size in phone space, as the canvas path does.
  private phoneShadow(g: PhoneGeometry) {
    const q = 4, blur = g.H * 0.035 / q, pad = Math.ceil(blur * 4);
    const key = [g.W, g.H, g.bw, g.bh, g.body_r].join("|");
    if (key !== this.shadowKey) {
      this.shadowKey = key;
      this.shadow.width = Math.ceil(g.W / q + 2 * pad);
      this.shadow.height = Math.ceil(g.H / q + 2 * pad);
      const c = this.shadow.getContext("2d")!;
      c.clearRect(0, 0, this.shadow.width, this.shadow.height);
      c.filter = `blur(${blur}px)`; c.fillStyle = "#000";
      const x = pad + (g.W - g.bw) / (2 * q), y = pad + (g.H - g.bh) / (2 * q), w = g.bw / q, h = g.bh / q, r = g.body_r / q;
      c.beginPath(); c.roundRect(x, y, w, h, r); c.fill();
    }
    return { tex: this.image("shadow", this.shadow, key, false), q, pad, w: this.shadow.width, h: this.shadow.height };
  }

  // Extra background blur, at plate resolution, into a padded texture: the
  // plate's reflected edges (BORDER_REFLECT) feed the kernel as compose.py does.
  private blurred(plate: Tex, pw: number, ph: number, pad: number, sigma: number) {
    const gl = this.gl, w = pw + 2 * pad, h = ph + 2 * pad;
    if (!this.fbo || this.fbo.w !== w || this.fbo.h !== h) {
      const mk = () => {
        const t = gl.createTexture()!;
        gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
        const f = gl.createFramebuffer()!;
        gl.bindFramebuffer(gl.FRAMEBUFFER, f);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
        return { t, f };
      };
      if (this.fbo) { gl.deleteTexture(this.fbo.ta); gl.deleteTexture(this.fbo.tb); gl.deleteFramebuffer(this.fbo.a); gl.deleteFramebuffer(this.fbo.b); }
      const A = mk(), B = mk();
      this.fbo = { a: A.f, b: B.f, ta: A.t, tb: B.t, w, h, key: "" };
    }
    const key = `${plate.key}|${this.uploads.get("plate") ?? 0}|${sigma.toFixed(2)}|${pad}`;
    if (this.fbo.key === key) return this.fbo.tb;
    this.fbo.key = key;
    const { w: wt, o } = gaussTaps(sigma), b = this.blur;
    gl.useProgram(b.p);
    gl.disable(gl.BLEND);
    gl.viewport(0, 0, w, h);
    gl.uniform2f(b.u.u_out, w, h);
    gl.uniformMatrix3fv(b.u.u_m, false, mat3([1, 0, 0, 1, 0, 0]));
    gl.uniform4f(b.u.u_rect, 0, 0, w, h);
    gl.uniform1fv(b.u.u_w, new Float32Array(64).fill(0).map((_, i) => wt[i] ?? 0));
    gl.uniform1fv(b.u.u_o, new Float32Array(64).fill(0).map((_, i) => o[i] ?? 0));
    gl.uniform1i(b.u.u_n, wt.length);
    gl.activeTexture(gl.TEXTURE0); gl.uniform1i(b.u.u_tex, 0);
    // horizontal: from the plate (mirrored) into A; padded px -> plate uv
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo.a);
    gl.bindTexture(gl.TEXTURE_2D, plate.t);
    gl.uniform4f(b.u.u_map, -pad, -pad, 1 / pw, 1 / ph);
    gl.uniform2f(b.u.u_dir, 1 / pw, 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    // vertical: A into B
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo.b);
    gl.bindTexture(gl.TEXTURE_2D, this.fbo.ta);
    gl.uniform4f(b.u.u_map, 0, 0, 1 / w, 1 / h);
    gl.uniform2f(b.u.u_dir, 0, 1 / h);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return this.fbo.tb;
  }
  draw(sc: Scene) {
    if (this.lost) return;
    const gl = this.gl, { spec, geo, plate, S } = sc, { W, H, g } = geo;
    const cw = gl.canvas.width, ch = gl.canvas.height;
    gl.bindVertexArray(this.vao);

    // background (its blurred copy is redrawn only when the plate shows a new frame)
    let plateTex: Tex | null = null, box = [0, 0, 1, 1], blurTex: WebGLTexture | null = null;
    if (plate) plateTex = this.video("plate", sc.plateVideo, gl.MIRRORED_REPEAT, false);
    const m = plate ? bgMatrix(spec, geo, plate, S) : null;
    if (plateTex && plate && m) {
      const extra = Math.sqrt(Math.max(0, S.bg_blur ** 2 - plate.blur ** 2)) * plate.k;
      box = [0, 0, plate.w, plate.h];
      if (extra > 0.3) {
        const inv = invert(m);
        const corners = [[0, 0], [W, 0], [0, H], [W, H]].map(([x, y]) => [inv[0] * x + inv[2] * y + inv[4], inv[1] * x + inv[3] * y + inv[5]]);
        const edge = Math.max(0, ...corners.flatMap(([x, y]) => [-x, x - plate.w, -y, y - plate.h]));
        const pad = Math.ceil((edge + extra * 4 + 2) / 64) * 64;
        blurTex = this.blurred(plateTex, plate.w, plate.h, pad, extra);
        box = [-pad, -pad, plate.w + 2 * pad, plate.h + 2 * pad];
      }
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, cw, ch);
    gl.disable(gl.BLEND);
    const bp = this.bg;
    gl.useProgram(bp.p);
    gl.uniform2f(bp.u.u_out, W, H);
    gl.uniformMatrix3fv(bp.u.u_m, false, mat3([1, 0, 0, 1, 0, 0]));
    gl.uniform4f(bp.u.u_rect, 0, 0, W, H);
    gl.uniform1i(bp.u.u_has, plateTex && m ? 1 : 0);
    gl.uniformMatrix3fv(bp.u.u_inv, false, mat3(m ? invert(m) : [1, 0, 0, 1, 0, 0]));
    gl.uniform4f(bp.u.u_box, box[0], box[1], box[2], box[3]);
    gl.uniform3fv(bp.u.u_theme, hexRgb(sc.themeBg));
    gl.uniform1f(bp.u.u_bright, S.bg_brightness || 0);
    gl.uniform1f(bp.u.u_vig, +spec.background.vignette || 0);
    gl.activeTexture(gl.TEXTURE0); gl.uniform1i(bp.u.u_tex, 0);
    gl.bindTexture(gl.TEXTURE_2D, blurTex ?? plateTex?.t ?? null);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    if (sc.recording === false) return;

    // phone: shadow, screen, frame, premultiplied over the background
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    const lp = this.layer, pm = phoneMatrix(spec, geo, S);
    gl.useProgram(lp.p);
    gl.uniform2f(lp.u.u_out, W, H);
    gl.uniform1i(lp.u.u_tex, 0);
    const layer = (tex: WebGLTexture | null, rect: number[], mat: M, mode: number, alpha = 1, radius = 0, flat: [number, number, number] = [0, 0, 0]) => {
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.uniformMatrix3fv(lp.u.u_m, false, mat3(mat));
      gl.uniform4f(lp.u.u_rect, rect[0], rect[1], rect[2], rect[3]);
      gl.uniform1f(lp.u.u_alpha, alpha); gl.uniform1f(lp.u.u_radius, radius);
      gl.uniform1i(lp.u.u_mode, mode); gl.uniform3fv(lp.u.u_flat, flat);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
    const shd = +spec.phone.shadow;
    if (shd > 0) {
      const s = this.phoneShadow(g), dy = H * 0.022 * S.zoom * S.phone_scale;
      if (s.tex) layer(s.tex.t, [-s.pad * s.q, -s.pad * s.q, s.w * s.q, s.h * s.q], [pm[0], pm[1], pm[2], pm[3], pm[4], pm[5] + dy], 0, shd);
    }
    const screen = geo.clip ? this.video("screen", sc.screen, gl.CLAMP_TO_EDGE, true) : null;
    const rect = [g.sx0, g.sy0, g.sw, g.sh];
    if (screen) layer(screen.t, rect, pm, 1, 1, g.screen_r);
    else layer(null, rect, pm, 2, 1, g.screen_r, hexRgb(sc.themeBg));
    const frame = sc.frame.complete && sc.frame.naturalWidth ? this.image("frame", sc.frame, sc.frame.src, true) : null;
    if (frame) layer(frame.t, [0, 0, g.W, g.H], pm, 0);
  }
}
