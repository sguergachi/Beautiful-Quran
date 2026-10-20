// Promo Studio editor. The preview draws the same scene as compose.py from
// the same geometry, properties and edit (anim.py is mirrored below);
// "Exact frame" shows the compositor's own frame with motion blur.
"use strict";

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const api = async (path, opts = {}) => {
  const r = await fetch(path, { headers: { "Content-Type": "application/json" }, ...opts });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j.error) throw new Error(j.error || r.statusText);
  return j;
};
const escapeHTML = (v) => String(v).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const clone = (o) => JSON.parse(JSON.stringify(o));
const r3 = (v) => Math.round(v * 1000) / 1000;
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };

const st = {
  meta: null, specs: [], name: null, raw: null, spec: null, geo: null, plate: null, tl: null,
  t: 0, playing: false, sel: null, pps: 60, collapsed: {}, undo: [], redo: [], coalesce: null,
  session: 0, saved: null, saveChain: Promise.resolve(), openSeq: 0, geoSeq: 0, plateSeq: 0, clipboard: null, loop: false,
  saveTimer: null, plateTimer: null, lastTakeKey: "", lastLook: "", wall0: 0, t0: 0,
};
const els = {
  stage: $("#stage"), ctx: $("#stage").getContext("2d"), screen: $("#screenVid"), plate: $("#plateVid"),
  audio: $("#audio"), frame: $("#frameImg"), exact: $("#exact"), inner: $("#tlinner"), scroll: $("#tlscroll"),
};
let LABEL_W = +(localStorage.getItem("promo.labelw") || 132);   // timeline label column, resizable

// ------------------------------------------------------------------ spec model (mirrors spec.py)

function merge(base, over) {
  const out = clone(base);
  for (const [k, v] of Object.entries(over || {})) {
    if (v && typeof v === "object" && !Array.isArray(v) && out[k] && typeof out[k] === "object" && !Array.isArray(out[k])) out[k] = merge(out[k], v);
    else out[k] = clone(v);
  }
  return out;
}
function resolve(raw) {
  const s = merge(st.meta.defaults, raw);
  if (s.phone.color === "auto") s.phone.color = st.meta.themes[s.app.theme].phone;
  s.edit = s.edit.filter((p) => p.out - p.in > 1e-3);
  if (!s.edit.length) s.edit = [{ in: 0, out: 1 }];
  const tr = {};
  for (const [k, v] of Object.entries(s.tracks || {})) if (v && v.length) tr[k] = [...v].sort((a, b) => a.t - b.t);
  s.tracks = tr;
  s.duration = Math.round(s.edit.reduce((a, p) => a + (p.out - p.in), 0) * 1e6) / 1e6;
  return s;
}
const takeKey = (s) => [s.app.reciter, s.app.surah, s.app.ayah, s.app.theme, s.app.layout, s.app.view, s.app.text_size].join("|");
function plateLook(s) {
  const blurs = [+s.props.bg_blur, ...(s.tracks.bg_blur || []).map((k) => +k.v)];
  return JSON.stringify([s.background, s.aspect, Math.max(2, Math.min(...blurs))]);
}
function getPath(o, path) { return path.split(".").reduce((a, k) => (a == null ? a : a[k]), o); }
function setPath(o, path, v) {
  const ks = path.split("."); let cur = o;
  for (const k of ks.slice(0, -1)) { if (cur[k] == null || typeof cur[k] !== "object") cur[k] = {}; cur = cur[k]; }
  cur[ks[ks.length - 1]] = v;
}

// ------------------------------------------------------------------ time (mirrors anim.py)

function ease(name, u) {
  u = clamp(u, 0, 1);
  switch (name) {
    case "linear": return u;
    case "hold": return u < 1 ? 0 : 1;
    case "inOutSine": return 0.5 - 0.5 * Math.cos(Math.PI * u);
    case "outCubic": return 1 - (1 - u) ** 3;
    case "inCubic": return u ** 3;
    case "outExpo": return u >= 1 ? 1 : 1 - 2 ** (-10 * u);
    default: return u < 0.5 ? 4 * u ** 3 : 1 - (-2 * u + 2) ** 3 / 2;
  }
}
function valueAt(name, t, s = st.spec) {
  const keys = s.tracks[name];
  if (!keys || !keys.length) return +s.props[name];
  if (t <= keys[0].t) return +keys[0].v;
  for (let i = 0; i < keys.length - 1; i++) {
    const a = keys[i], b = keys[i + 1];
    if (a.t <= t && t <= b.t) {
      const e = ease(b.ease || "inOutCubic", (t - a.t) / Math.max(1e-6, b.t - a.t));
      if (st.meta.props[name].log && a.v > 0 && b.v > 0) return Math.exp(Math.log(a.v) + (Math.log(b.v) - Math.log(a.v)) * e);
      return +a.v + (b.v - a.v) * e;
    }
  }
  return +keys[keys.length - 1].v;
}
function stateAt(t) { const o = {}; for (const k in st.meta.props) o[k] = valueAt(k, t); return o; }
function sourceTime(t, s = st.spec) {
  t = Math.max(0, t);
  let acc = 0;
  for (let i = 0; i < s.edit.length; i++) {
    const p = s.edit[i], n = p.out - p.in;
    if (t < acc + n) return [p.in + (t - acc), i];
    acc += n;
  }
  const last = s.edit[s.edit.length - 1];
  return [last.out, s.edit.length - 1];
}
function pieceStart(i, s = st.spec) { let acc = 0; for (let j = 0; j < i; j++) acc += s.edit[j].out - s.edit[j].in; return acc; }
function videoTimes(a0, a1, s = st.spec) {
  const out = []; let acc = 0;
  for (const p of s.edit) {
    const a = Math.max(p.in, a0), b = Math.min(p.out, a1);
    if (b > a) out.push([acc + a - p.in, acc + b - p.in]);
    acc += p.out - p.in;
  }
  return out;
}
const MIN_PIECE = 0.05;
const frameT = () => 1 / (st.spec.fps || 60);

// ------------------------------------------------------------------ geometry (mirrors compose.Renderer)

function rot(deg) { const r = deg * Math.PI / 180; return [Math.cos(r), Math.sin(r)]; }
function phoneMatrix(S) {   // canvas px -> output px, as [a b c d e f] for ctx.transform
  const g = st.geo.g, W = st.geo.W, H = st.geo.H;
  const sc = (st.spec.phone.height * H / g.bh) * S.phone_scale, z = S.zoom;
  const P0 = [W / 2 + S.phone_x * W, H / 2 + S.phone_y * H], C = [S.pan_x * W, S.pan_y * H];
  const [c, s] = rot(S.rotate), [ct, stt] = rot(S.rotate + S.phone_tilt);
  const L = [z * sc * ct, -z * sc * stt, z * sc * stt, z * sc * ct];       // [[L00 L01][L10 L11]]
  const vx = z * (P0[0] - C[0]), vy = z * (P0[1] - C[1]);
  const C0 = [g.W / 2, g.H / 2];
  const tx = c * vx - s * vy - (L[0] * C0[0] + L[1] * C0[1]) + W / 2;
  const ty = s * vx + c * vy - (L[2] * C0[0] + L[3] * C0[1]) + H / 2;
  return [L[0], L[2], L[1], L[3], tx, ty];
}
function bgMatrix(S) {
  const W = st.geo.W, H = st.geo.H, p = +st.spec.background.parallax;
  const zb = 1 + (S.zoom - 1) * p;
  const sh = [(S.pan_x * W - W / 2) * p, (S.pan_y * H - H / 2) * p];
  const [c, s] = rot(S.rotate * p);
  const pk = st.plate.k, Bc = [st.plate.w / 2, st.plate.h / 2], L = zb / pk;
  const vx = zb * (-Bc[0] / pk - sh[0]), vy = zb * (-Bc[1] / pk - sh[1]);
  return [c * L, s * L, -s * L, c * L, c * vx - s * vy + W / 2, s * vx + c * vy + H / 2];
}
// world point (frame-normalised) of a point on the phone screen, at time t
function screenPointWorld(fx, fy, t) {
  const S = stateAt(t), g = st.geo.g, W = st.geo.W, H = st.geo.H;
  const sc = (st.spec.phone.height * H / g.bh) * S.phone_scale;
  const [c, s] = rot(S.phone_tilt);
  const dx = g.sx0 + fx * g.sw - g.W / 2, dy = g.sy0 + fy * g.sh - g.H / 2;
  return [(W / 2 + S.phone_x * W + sc * (c * dx - s * dy)) / W, (H / 2 + S.phone_y * H + sc * (s * dx + c * dy)) / H];
}
// zoom at which the phone's screen spans ~94% of the frame width
function fitZoom(t) {
  const S = stateAt(t), g = st.geo.g, W = st.geo.W, H = st.geo.H;
  const sw = g.sw * (st.spec.phone.height * H / g.bh) * S.phone_scale;
  return +clamp((W * 0.94) / sw, 1, 4).toFixed(3);
}

// ------------------------------------------------------------------ drawing

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}
const bgCanvas = document.createElement("canvas");
const bgCtx = bgCanvas.getContext("2d");
const tileCanvas = document.createElement("canvas"), tileCtx = tileCanvas.getContext("2d");
let bgCacheKey = "";
const vigCanvas = document.createElement("canvas");
let vigKey = "";
function vignette(W, H, strength) {
  const key = [W, H, strength].join("|");
  if (key !== vigKey) {
    vigKey = key; vigCanvas.width = W; vigCanvas.height = H;
    const ctx = vigCanvas.getContext("2d"), img = ctx.createImageData(W, H);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const r2 = Math.min(1, ((2 * x / W - 1) ** 2 + (2 * y / H - 1) ** 2) / 2);
      img.data[(y * W + x) * 4 + 3] = Math.round(255 * strength * r2);
    }
    ctx.putImageData(img, 0, 0);
  }
  return vigCanvas;
}
function draw() {
  const ctx = els.ctx;
  if (!st.geo || !st.spec) return;
  const { W, H } = st.geo, s = st.spec, g = st.geo.g;
  const ps = els.stage.width / W;
  const S = stateAt(st.t);
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.filter = "none"; ctx.globalAlpha = 1;
  ctx.fillStyle = st.meta.themes[s.app.theme].bg; ctx.fillRect(0, 0, els.stage.width, els.stage.height);
  // background: the plate holds the least blur; the rest is added here, as the exporter does
  if (st.plate && els.plate.readyState >= 2) {
    const { w, h, k } = st.plate;
    const extra = Math.sqrt(Math.max(0, S.bg_blur ** 2 - st.plate.blur ** 2)) * k;
    // Filter in plate space before applying the camera transform. Reflected
    // tiles provide the same edge extension as BORDER_REFLECT in compose.py.
    const matrix = bgMatrix(S), [a, b, c, d, e, f] = matrix, det = a * d - b * c;
    const points = [[0, 0], [W, 0], [0, H], [W, H]].map(([x, y]) => [(d * (x - e) - c * (y - f)) / det, (-b * (x - e) + a * (y - f)) / det]);
    const edge = Math.max(0, ...points.flatMap(([x, y]) => [-x, x - w, -y, y - h]));
    const pad = Math.ceil(Math.max(w, h, edge + extra * 4) / 64) * 64;
    const bw = w + 2 * pad, bh = h + 2 * pad;
    if (bgCanvas.width !== bw || bgCanvas.height !== bh) { bgCanvas.width = bw; bgCanvas.height = bh; }
    const cacheKey = [els.plate.src, els.plate.currentTime, extra.toFixed(3), bw, bh].join("|");
    if (cacheKey !== bgCacheKey) {
      bgCacheKey = cacheKey;
      if (tileCanvas.width !== bw || tileCanvas.height !== bh) { tileCanvas.width = bw; tileCanvas.height = bh; }
      for (let iy = -Math.ceil(pad / h); iy <= Math.ceil(pad / h); iy++) {
        for (let ix = -Math.ceil(pad / w); ix <= Math.ceil(pad / w); ix++) {
          tileCtx.save(); tileCtx.translate(pad + ix * w + (Math.abs(ix % 2) ? w : 0), pad + iy * h + (Math.abs(iy % 2) ? h : 0));
          tileCtx.scale(Math.abs(ix % 2) ? -1 : 1, Math.abs(iy % 2) ? -1 : 1); tileCtx.drawImage(els.plate, 0, 0, w, h); tileCtx.restore();
        }
      }
      bgCtx.clearRect(0, 0, bw, bh);
      bgCtx.filter = extra > 0.3 ? `blur(${extra}px)` : "none";
      bgCtx.drawImage(tileCanvas, 0, 0);
    }
    ctx.save(); ctx.setTransform(ps, 0, 0, ps, 0, 0); ctx.transform(...matrix);
    ctx.drawImage(bgCanvas, -pad, -pad); ctx.restore();
  }
  ctx.setTransform(ps, 0, 0, ps, 0, 0);
  const b = S.bg_brightness;
  if (b) { ctx.globalAlpha = Math.min(1, Math.abs(b)); ctx.fillStyle = b > 0 ? "#fff" : "#000"; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1; }
  const vg = +s.background.vignette;
  if (vg > 0) ctx.drawImage(vignette(els.stage.width, els.stage.height, vg), 0, 0, W, H);
  const m = phoneMatrix(S);
  const shd = +s.phone.shadow;
  if (shd > 0) {
    ctx.save(); ctx.setTransform(ps, 0, 0, ps, 0, 0);
    ctx.translate(0, H * 0.022 * S.zoom * S.phone_scale); ctx.transform(...m);
    ctx.filter = `blur(${g.H * 0.035 * Math.hypot(m[0], m[1]) * ps}px)`;
    ctx.globalAlpha = shd; ctx.fillStyle = "#000";
    roundRect(ctx, g.W / 2 - g.bw / 2, g.H / 2 - g.bh / 2, g.bw, g.bh, g.body_r); ctx.fill();
    ctx.restore();
  }
  ctx.save(); ctx.setTransform(ps, 0, 0, ps, 0, 0); ctx.transform(...m);
  ctx.save(); roundRect(ctx, g.sx0, g.sy0, g.sw, g.sh, g.screen_r); ctx.clip();
  if (st.geo.clip && els.screen.readyState >= 2) ctx.drawImage(els.screen, g.sx0, g.sy0, g.sw, g.sh);
  else {
    ctx.fillStyle = st.meta.themes[s.app.theme].bg; ctx.fillRect(g.sx0, g.sy0, g.sw, g.sh);
    ctx.fillStyle = "#a49c8c"; ctx.font = `${g.sw * 0.05}px system-ui`; ctx.textAlign = "center";
    ctx.fillText(st.geo.clip ? "loading take…" : "no take yet: Record take", g.sx0 + g.sw / 2, g.sy0 + g.sh / 2);
  }
  ctx.restore();
  if (els.frame.complete && els.frame.naturalWidth) ctx.drawImage(els.frame, 0, 0, g.W, g.H);
  ctx.restore();
  $("#time").textContent = `${st.t.toFixed(2)} / ${s.duration.toFixed(2)}`;
  placePlayhead(); refreshValues();
}
function sizeStage() {
  if (!st.geo) return;
  const wrapEl = $("#stageWrap"), { W, H } = st.geo;
  const k = Math.max(0.05, Math.min((wrapEl.clientWidth - 20) / W, (wrapEl.clientHeight - 50) / H));
  const dpr = window.devicePixelRatio || 1;
  els.stage.style.width = W * k + "px"; els.stage.style.height = H * k + "px";
  els.stage.width = Math.round(W * k * dpr); els.stage.height = Math.round(H * k * dpr);
  els.exact.style.width = els.stage.style.width; els.exact.style.height = els.stage.style.height;
  draw();
}

// ------------------------------------------------------------------ playback

function plateTime(t) { const d = els.plate.duration || 1; return ((+(st.spec.background.offset || 0) + t) % d + d) % d; }
// A media element seeks asynchronously, and asking again before it lands
// restarts the decode: a fast scrub would show nothing until it stopped.
// Keep only the latest target and apply it when the current seek lands.
function seekMedia(m, t) {
  if (m.seeking) { m._want = t; return; }
  m._want = null;
  m.currentTime = t;
}
function seekLanded(m) { if (m._want != null) { const t = m._want; m._want = null; m.currentTime = t; } }
function syncMedia(force) {
  if (!st.spec) return;
  const [src, piece] = sourceTime(Math.min(st.t, Math.max(0, st.spec.duration - frameT())));
  force = force || syncMedia.piece !== piece; syncMedia.piece = piece;
  const tol = force ? 0.001 : 0.07;
  if (st.geo && st.geo.clip && Math.abs(els.screen.currentTime - src) > tol) seekMedia(els.screen, src);
  if (st.geo && st.geo.audio && Math.abs(els.audio.currentTime - src) > (force ? 0.001 : 0.09)) seekMedia(els.audio, src);
  const pt = plateTime(st.t);
  if (st.plate && (force || Math.abs(els.plate.currentTime - pt) > 0.09)) seekMedia(els.plate, pt);
}
function seek(t) { if (!st.spec) return; st.t = clamp(t, 0, st.spec.duration); st.wall0 = performance.now(); st.t0 = st.t; st.coalesce = null; hideExact(); syncMedia(true); draw(); }
function play() {
  if (!st.spec || !st.geo) return;
  if (st.playing) return pause();
  if (st.t >= st.spec.duration - 0.05) st.t = 0;
  st.playing = true; $("#play").textContent = "❚❚"; hideExact();
  syncMedia(true);
  st.wall0 = performance.now(); st.t0 = st.t;
  if (st.geo.clip) els.screen.play().catch(() => {});
  if (st.plate) els.plate.play().catch(() => {});
  if (st.geo.audio) els.audio.play().catch(() => {});
  requestAnimationFrame(tick);
}
function pause() { st.playing = false; $("#play").textContent = "▶"; els.screen.pause(); els.plate.pause(); els.audio.pause(); draw(); }
function tick() {
  if (!st.playing) return;
  // wall clock leads; the take's picture and sound follow it through the cuts
  st.t = st.t0 + (performance.now() - st.wall0) / 1000;
  if (st.t >= st.spec.duration) {
    if (!st.loop) { st.t = st.spec.duration; pause(); return; }
    st.t = 0; st.t0 = 0; st.wall0 = performance.now(); syncMedia(true);
  }
  syncMedia(false);
  draw(); followPlayhead(); requestAnimationFrame(tick);
}
function toggleLoop() { st.loop = !st.loop; $("#loopBtn").classList.toggle("on", st.loop); }
// Verse starts and cuts, in video time: what ↑ / ↓ step between.
function landmarks() {
  const out = [0, st.spec.duration];
  if (st.tl) for (const v of st.tl.verses) for (const [a] of videoTimes(v.t0, v.t1)) out.push(a);
  for (let i = 1; i < st.spec.edit.length; i++) out.push(pieceStart(i));
  return [...new Set(out.map(r3))].sort((a, b) => a - b);
}
function jumpLandmark(dir) {
  const ts = landmarks();
  const next = dir > 0 ? ts.find((x) => x > st.t + 1e-3) : [...ts].reverse().find((x) => x < st.t - 1e-3);
  if (next != null) { pause(); seek(next); followPlayhead(true); }
}
function parseTime(text) {
  const m = String(text).trim().match(/^(?:(\d+):)?(\d+(?:\.\d*)?)$/);
  return m ? (+(m[1] || 0)) * 60 + +m[2] : NaN;
}
function editTime() {
  if (!st.spec || $("#timeInput")) return;
  const span = $("#time"), input = el("input"); input.id = "timeInput"; input.value = st.t.toFixed(2);
  span.replaceWith(input); input.focus(); input.select();
  let done = false;
  const finish = (apply) => {
    if (done) return; done = true;
    if (apply) { const t = parseTime(input.value); if (Number.isFinite(t)) { pause(); seek(t); followPlayhead(true); } else note("type seconds (12.5) or minutes:seconds (0:12.5)"); }
    input.replaceWith(span); draw();
  };
  input.onkeydown = (e) => { if (e.key === "Enter") finish(true); else if (e.key === "Escape") { e.stopPropagation(); finish(false); } };
  input.onblur = () => finish(true);
}

// ------------------------------------------------------------------ exact frame

function hideExact() { els.exact.classList.add("hidden"); }
async function showExact() {
  if (!st.geo?.clip) return note("record a take first");
  pause();
  if (!await save()) return;
  const session = st.session, at = st.t, body = snapshot();
  $("#exactBtn").disabled = true;
  try {
    await new Promise((res, rej) => { els.exact.onload = res; els.exact.onerror = rej; els.exact.src = `/api/still/${st.name}?t=${st.t.toFixed(3)}&scale=0.5&_=${Date.now()}`; });
    if (session === st.session && at === st.t && body === snapshot()) els.exact.classList.remove("hidden");
  } catch { note("exact frame failed (see the terminal)"); }
  $("#exactBtn").disabled = false;
}
function note(msg, ms = 2600) {
  const n = $("#stageNote"); n.textContent = msg; n.classList.remove("hidden");
  clearTimeout(note.t); note.t = setTimeout(() => n.classList.add("hidden"), ms);
}

// ------------------------------------------------------------------ history

function snapshot() { return JSON.stringify(st.raw); }
// Run a change as one undo step. Changes with the same `coalesce` key in quick
// succession (a slider drag, a scroll-zoom) fold into a single step.
function mutate(fn, coalesce = null) {
  if (!st.raw) return;
  if (st.playing) pause(); hideExact();
  const before = snapshot(), now = performance.now();
  fn();
  if (snapshot() === before) return;
  const join = coalesce && st.coalesce && st.coalesce.key === coalesce && (coalesce.startsWith("drag-") || now - st.coalesce.at < 900);
  if (!join) { st.undo.push(before); if (st.undo.length > 200) st.undo.shift(); st.redo = []; }
  st.coalesce = coalesce ? { key: coalesce, at: now } : null;
  changed();
}
function undo() {
  if (!st.undo.length) return;
  pause();
  st.redo.push(snapshot()); st.raw = JSON.parse(st.undo.pop()); st.coalesce = null; st.sel = null;
  changed(); buildPanes();
}
function redo() {
  if (!st.redo.length) return;
  pause();
  st.undo.push(snapshot()); st.raw = JSON.parse(st.redo.pop()); st.coalesce = null; st.sel = null;
  changed(); buildPanes();
}
function histButtons() { $("#undo").disabled = !st.undo.length; $("#redo").disabled = !st.redo.length; }

// ------------------------------------------------------------------ properties & keys

function trackOf(name) { st.raw.tracks = st.raw.tracks || {}; return (st.raw.tracks[name] = st.raw.tracks[name] || []); }
function keyIndexAt(name, t) {
  const keys = (st.raw.tracks || {})[name] || [];
  return keys.findIndex((k) => Math.abs(k.t - t) < frameT() / 2 + 1e-6);
}
const animated = (name) => !!((st.raw.tracks || {})[name] || []).length;
function findKey(prop, t) { return ((st.raw.tracks || {})[prop] || []).find((k) => Math.abs(k.t - t) < 1e-6); }
// Set a property at the playhead: a key there when it is animated, else its static value.
function setProp(name, v, coalesce) {
  const p = st.meta.props[name];
  if (!Number.isFinite(+v)) return;
  v = r3(clamp(+v, p.min, p.max));
  mutate(() => {
    if (animated(name)) {
      const i = keyIndexAt(name, st.t);
      if (i >= 0) trackOf(name)[i].v = v;
      else { trackOf(name).push({ t: r3(st.t), v }); trackOf(name).sort((a, b) => a.t - b.t); }
    } else { st.raw.props = st.raw.props || {}; st.raw.props[name] = v; }
  }, coalesce || "prop:" + name);
}
function toggleKey(name) {
  mutate(() => {
    const i = keyIndexAt(name, st.t);
    if (i >= 0) {
      const v = trackOf(name)[i].v;
      trackOf(name).splice(i, 1);
      if (!trackOf(name).length) { delete st.raw.tracks[name]; (st.raw.props = st.raw.props || {})[name] = v; }
      st.sel = null;
    } else {
      const v = r3(valueAt(name, st.t));
      trackOf(name).push({ t: r3(st.t), v }); trackOf(name).sort((a, b) => a.t - b.t);
      selectKeys([{ prop: name, t: r3(st.t) }]);
    }
  });
  buildPanes();
}
function keyAll(names, at = st.t, vals = {}, easeName) {
  for (const n of names) {
    const v = r3(vals[n] ?? valueAt(n, at));
    const keys = trackOf(n);
    const i = keys.findIndex((k) => Math.abs(k.t - at) < frameT() / 2 + 1e-6);
    const k = { t: r3(at), v };
    if (easeName) k.ease = easeName;
    if (i >= 0) keys[i] = { ...keys[i], ...k }; else keys.push(k);
    keys.sort((a, b) => a.t - b.t);
  }
}
// The selection is one recording piece, or keys: a primary key (the one the
// inspector edits) plus any more picked with Shift/Ctrl-click or a marquee.
function selItems() { return st.sel && st.sel.type === "key" ? [{ prop: st.sel.prop, t: st.sel.t }, ...(st.sel.more || [])] : []; }
function isSel(prop, t) { return selItems().some((x) => x.prop === prop && Math.abs(x.t - t) < 1e-6); }
function selectKeys(items) { st.sel = items.length ? { type: "key", prop: items[0].prop, t: items[0].t, more: items.slice(1) } : null; }
function toggleInSel(prop, t) {
  const items = selItems(), i = items.findIndex((x) => x.prop === prop && Math.abs(x.t - t) < 1e-6);
  if (i >= 0) items.splice(i, 1); else items.unshift({ prop, t });
  selectKeys(items);
}
function selectAllKeys() {
  const items = [];
  for (const [prop, keys] of Object.entries(st.spec.tracks)) for (const k of keys) items.push({ prop, t: k.t });
  selectKeys(items); buildTimeline(); buildPanes();
  if (items.length) note(`${items.length} key${items.length > 1 ? "s" : ""} selected`);
}
function selKey() {
  if (!st.sel || st.sel.type !== "key") return null;
  const keys = (st.raw.tracks || {})[st.sel.prop] || [];
  const i = keys.findIndex((k) => Math.abs(k.t - st.sel.t) < 1e-6);
  return i >= 0 ? { keys, i, k: keys[i] } : null;
}
function setEase(easeName) {
  const items = selItems(); if (!items.length) return;
  mutate(() => { for (const { prop, t } of items) { const k = findKey(prop, t); if (!k) continue; if (easeName === "inOutCubic") delete k.ease; else k.ease = easeName; } });
  buildPanes();
}
// Move every selected key by dt seconds, unless one would land on another key of its track.
function moveSelected(dt) {
  const moving = selItems().map(({ prop, t }) => ({ prop, key: findKey(prop, t) })).filter((m) => m.key);
  if (!moving.length) return;
  const lo = -Math.min(...moving.map((m) => m.key.t)), hi = st.spec.duration - Math.max(...moving.map((m) => m.key.t));
  dt = clamp(dt, lo, hi);
  if (Math.abs(dt) < 1e-6) return;
  const clash = moving.some((m) => trackOf(m.prop).some((x) => !moving.some((o) => o.key === x) && Math.abs(x.t - (m.key.t + dt)) < frameT() / 2));
  if (clash) return note("a key is in the way");
  mutate(() => {
    for (const m of moving) m.key.t = r3(m.key.t + dt);
    for (const m of moving) trackOf(m.prop).sort((a, b) => a.t - b.t);
    selectKeys(moving.map((m) => ({ prop: m.prop, t: m.key.t })));
  }, "nudge");
  seek(st.sel.t); buildPanes();
}
function preset(kind) {
  if (!st.spec) return;
  const t = st.t, d = 0.9, cam = ["zoom", "pan_x", "pan_y"];
  mutate(() => {
    keyAll(cam, t);
    if (kind === "push" || kind === "punch") {
      const z = fitZoom(t), [px, py] = screenPointWorld(0.5, 0.32, t);
      const at = Math.min(st.spec.duration, kind === "push" ? t + d : t + frameT());
      keyAll(cam, at, { zoom: z, pan_x: px, pan_y: py }, kind === "push" ? "inOutCubic" : "hold");
    } else {
      const [px, py] = screenPointWorld(0.5, 0.5, t);
      keyAll(cam, Math.min(st.spec.duration, t + d), { zoom: 1, pan_x: px, pan_y: py }, "inOutCubic");
    }
  });
  buildPanes();
}
function deleteSelection() {
  if (!st.sel) return;
  pause();
  if (st.sel.type === "key") {
    const items = selItems();
    mutate(() => {
      for (const { prop, t } of items) {
        const keys = (st.raw.tracks || {})[prop]; if (!keys) continue;
        const i = keys.findIndex((k) => Math.abs(k.t - t) < 1e-6); if (i < 0) continue;
        const [k] = keys.splice(i, 1);
        // the last key gone: the property keeps that value for the whole video
        if (!keys.length) { delete st.raw.tracks[prop]; (st.raw.props = st.raw.props || {})[prop] = k.v; }
      }
      st.sel = null;
    });
  } else if (st.sel.type === "piece") {
    if (st.spec.edit.length <= 1) return note("the video needs at least one piece");
    mutate(() => { st.raw.edit.splice(st.sel.i, 1); st.sel = null; });
  }
  buildPanes();
}
function jumpKey(dir) {
  if (!st.spec) return;
  const names = st.sel && st.sel.type === "key" ? [...new Set(selItems().map((x) => x.prop))] : Object.keys(st.meta.props);
  const ts = [...new Set(names.flatMap((n) => (st.spec.tracks[n] || []).map((k) => k.t)))].sort((a, b) => a - b);
  const next = dir > 0 ? ts.find((x) => x > st.t + 1e-3) : [...ts].reverse().find((x) => x < st.t - 1e-3);
  if (next != null) { seek(next); followPlayhead(true); }
}

// ------------------------------------------------------------------ edit (the recording)

function splitAt(t = st.t) {
  if (!st.spec) return;
  const [src, i] = sourceTime(t);
  const p = st.raw.edit[i];
  if (!p || src - p.in < MIN_PIECE || p.out - src < MIN_PIECE) return note("move the playhead inside a piece to split it");
  mutate(() => { st.raw.edit.splice(i, 1, { in: p.in, out: r3(src) }, { in: r3(src), out: p.out }); st.sel = { type: "piece", i: i + 1 }; });
  buildPanes();
}
function resetEdit() {
  if (!st.spec) return;
  if (st.spec.edit.length > 1 && !confirm("Replace all cuts with the whole take? You can undo this.")) return;
  const len = st.tl && st.tl.take ? st.tl.take : Math.max(...st.spec.edit.map((p) => p.out));
  mutate(() => { st.raw.edit = [{ in: 0, out: r3(len) }]; st.sel = null; });
  buildPanes();
}

// ------------------------------------------------------------------ timeline

const tx = (t) => t * st.pps;
function laneWidth() { return Math.max(200, tx(st.spec.duration) + 40); }
// Video time under a pointer. Measured against the timeline's inner box,
// which is never rebuilt, so it stays right while rows are redrawn mid-drag.
function timeAt(clientX) { return (clientX - els.inner.getBoundingClientRect().left - LABEL_W) / st.pps; }
function row(cls, labelHTML) {
  const r = el("div", "tl-row " + (cls || ""));
  const lab = el("div", "tl-label", labelHTML);
  const lane = el("div", "tl-lane"); lane.style.width = laneWidth() + "px";
  r.append(lab, lane); els.inner.appendChild(r);
  return { r, lab, lane };
}
function groupProps(g) { return Object.keys(st.meta.props).filter((n) => st.meta.props[n].group === g); }
// exclude: keys being dragged ([{prop, t}]); noPlayhead: when the playhead itself moves
function snapT(t, exclude = [], noPlayhead = false) {
  const cands = [0, st.spec.duration];
  if (!noPlayhead) cands.push(st.t);
  for (const n in st.spec.tracks) for (const k of st.spec.tracks[n]) if (!exclude.some((x) => x.prop === n && Math.abs(x.t - k.t) < 1e-6)) cands.push(k.t);
  for (let i = 1; i < st.spec.edit.length; i++) cands.push(pieceStart(i));
  if (st.tl) for (const v of st.tl.verses) for (const [a] of videoTimes(v.t0, v.t1)) cands.push(a);
  let best = t, bd = 7 / st.pps;
  for (const c of cands) if (Math.abs(c - t) < bd) { bd = Math.abs(c - t); best = c; }
  return best;
}
let timelineFrame = null;
function scheduleTimeline() {
  if (timelineFrame == null) timelineFrame = requestAnimationFrame(() => { timelineFrame = null; buildTimeline(); });
}
// Follow one pointer until release, captured on an element that survives
// redraws (the timeline's scroll box, a divider, the stage): releasing
// outside the window still ends the drag. edge: scroll the timeline when the
// pointer nears its sides, and keep scrolling while it stays there.
function track(ev, host, move, up, { edge = false, cursor = "" } = {}) {
  host.setPointerCapture(ev.pointerId);
  document.body.classList.add("dragging"); if (cursor) document.body.classList.add(cursor);
  let last = ev, timer = null;
  const nudge = () => {
    const r = els.scroll.getBoundingClientRect(), left = r.left + LABEL_W + 30, right = r.right - 30;
    const d = last.clientX < left ? last.clientX - left : last.clientX > right ? last.clientX - right : 0;
    if (!d) return false;
    els.scroll.scrollLeft += d * 0.5; move(last); return true;
  };
  const mv = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    last = e; move(e);
    if (edge && !timer && nudge()) timer = setInterval(() => { if (!nudge()) { clearInterval(timer); timer = null; } }, 30);
  };
  const end = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    clearInterval(timer);
    host.removeEventListener("pointermove", mv); host.removeEventListener("pointerup", end); host.removeEventListener("pointercancel", end);
    if (host.hasPointerCapture(ev.pointerId)) host.releasePointerCapture(ev.pointerId);
    document.body.classList.remove("dragging"); if (cursor) document.body.classList.remove(cursor);
    if (up) up(e);
  };
  host.addEventListener("pointermove", mv); host.addEventListener("pointerup", end); host.addEventListener("pointercancel", end);
}
function selectionText() {
  const n = selItems().length;
  if (n > 1) return `${n} keys`;
  if (n === 1) return `${st.meta.props[st.sel.prop].label} · ${st.sel.t.toFixed(2)}s`;
  return st.sel?.type === "piece" ? `Piece ${st.sel.i + 1}` : "No selection";
}
function buildTimeline() {
  if (!st.spec) return;
  closeCtx();
  $("#delSel").disabled = !st.sel;
  $("#selection").textContent = selectionText();
  const sl = els.scroll.scrollLeft, stp = els.scroll.scrollTop;
  els.inner.innerHTML = "";
  els.inner.style.width = LABEL_W + laneWidth() + "px";
  const dur = st.spec.duration;
  // ruler (sticks to the top while the lanes scroll)
  const ru = row("tl-ruler", '<span class="nm">time</span><i class="lw" title="Drag to resize the labels"></i>');
  const step = st.pps >= 120 ? 0.5 : st.pps >= 50 ? 1 : st.pps >= 20 ? 2 : 5;
  for (let t = 0; t <= dur + 1e-6; t += step) {
    const major = Math.abs(t / (step * 2) - Math.round(t / (step * 2))) < 1e-6 || step >= 2;
    const d = el("div", "tick" + (major ? "" : " minor"), major ? (Number.isInteger(t) ? t : t.toFixed(1)) + "s" : "");
    d.style.left = tx(t) + "px"; ru.lane.appendChild(d);
  }
  const head = el("div"); head.id = "phHead"; head.title = "Drag to scrub"; ru.lane.appendChild(head);
  const ht = el("div"); ht.id = "hoverTime"; ru.lane.appendChild(ht);
  ru.lane.addEventListener("pointerdown", scrubFrom);
  $(".lw", ru.lab).addEventListener("pointerdown", resizeLabels);
  // recording pieces
  const rec = row("tall", '<span class="nm">Recording</span>');
  const takeLen = st.tl && st.tl.take;
  st.spec.edit.forEach((p, i) => {
    const a = pieceStart(i), b = a + (p.out - p.in);
    const d = el("div", "piece" + (st.sel && st.sel.type === "piece" && st.sel.i === i ? " on" : "") + (takeLen && p.out > takeLen + 0.05 ? " over" : ""),
      `${p.in.toFixed(2)}–${p.out.toFixed(2)}s<i class="h l"></i><i class="h r"></i>`);
    d.style.left = tx(a) + "px"; d.style.width = Math.max(4, tx(b - a)) + "px";
    d.title = `take ${p.in.toFixed(2)}–${p.out.toFixed(2)}s · drag the edges to trim · S splits at the playhead · right-click for more` + (takeLen && p.out > takeLen ? " · past the end of the take: re-record longer" : "");
    d.addEventListener("pointerdown", (ev) => dragPiece(ev, i));
    d.addEventListener("contextmenu", (ev) => pieceMenu(ev, i));
    rec.lane.appendChild(d);
    if (i > 0) { const c = el("div", "cut"); c.style.left = tx(a) + "px"; rec.lane.appendChild(c); }
  });
  rec.lane.addEventListener("pointerdown", (e) => { if (e.target === rec.lane) scrubFrom(e); });
  // reference tracks
  const vr = row("", '<span class="nm">Verses</span>');
  const rp = row("", '<span class="nm">Repeats</span>');
  if (st.tl) {
    for (const v of st.tl.verses) for (const [a, b] of videoTimes(v.t0, v.t1)) {
      const d = el("div", "seg verse" + (v.label === "basmalah" ? " basm" : ""), v.label);
      d.style.left = tx(a) + "px"; d.style.width = Math.max(2, tx(b - a)) + "px"; d.title = `${v.label} · click to jump · drag to scrub`;
      d.addEventListener("pointerdown", (e) => { e.stopPropagation(); scrubFrom(e, a); }); vr.lane.appendChild(d);
    }
    const spans = [];
    for (const r of st.tl.repeats) { const l = spans[spans.length - 1]; if (l && l.verse === r.verse && r.t0 - l.t1 < 0.15) l.t1 = r.t1; else spans.push({ ...r }); }
    for (const r of spans) for (const [a, b] of videoTimes(r.t0, r.t1)) {
      const d = el("div", "seg rep", "repeat"); d.style.left = tx(a) + "px"; d.style.width = Math.max(2, tx(b - a)) + "px";
      d.title = `${r.verse} repeat · click to jump (0.5 s before)`; d.addEventListener("pointerdown", (e) => { e.stopPropagation(); scrubFrom(e, Math.max(0, a - 0.5)); }); rp.lane.appendChild(d);
    }
  }
  for (const r of [vr, rp]) r.lane.addEventListener("pointerdown", (e) => { if (e.target === r.lane) scrubFrom(e); });
  // property lanes, by group
  const groups = {};
  for (const [n, p] of Object.entries(st.meta.props)) (groups[p.group] = groups[p.group] || []).push(n);
  for (const [gname, names] of Object.entries(groups)) {
    const nAnim = names.filter(animated).length;
    const gr = row("group", `<span class="nm">${st.collapsed[gname] ? "▸" : "▾"} ${gname}</span><span class="vv">${nAnim ? nAnim + " animated" : ""}</span>`);
    gr.r.dataset.group = gname;
    gr.lab.onclick = () => { st.collapsed[gname] = !st.collapsed[gname]; buildTimeline(); };
    gr.lane.addEventListener("pointerdown", (e) => { if (e.target === gr.lane) laneDown(e); });
    gr.lane.addEventListener("contextmenu", (e) => { if (e.target === gr.lane) laneMenu(e, null); });
    if (st.collapsed[gname]) {   // collapsed: every key of the group on one lane
      for (const n of names) for (const k of st.spec.tracks[n] || []) addDiamond(gr.lane, n, k);
      continue;
    }
    for (const n of names) {
      const p = st.meta.props[n];
      const lr = row("tl-prop", `<button class="kb" title="Add / remove a key at the playhead">◆</button><span class="nm">${p.label}</span><span class="vv"></span>`);
      lr.r.dataset.prop = n;
      $(".kb", lr.lab).onclick = (e) => { e.stopPropagation(); toggleKey(n); };
      lr.lab.onclick = () => { showTab("animate"); };
      const keys = st.spec.tracks[n] || [];
      if (keys.length) drawCurve(lr.lane, n);
      for (const k of keys) addDiamond(lr.lane, n, k);
      lr.lane.addEventListener("pointerdown", (e) => { if (e.target === lr.lane || e.target.closest(".curve")) laneDown(e); });
      lr.lane.addEventListener("contextmenu", (e) => { if (!e.target.closest(".key")) laneMenu(e, n); });
      lr.lane.addEventListener("dblclick", (e) => {
        if (e.target.closest(".key")) return;
        const raw = timeAt(e.clientX), t = clamp(e.altKey ? raw : snapT(raw), 0, dur);
        mutate(() => { keyAll([n], t); selectKeys([{ prop: n, t: r3(t) }]); }); seek(t); buildPanes();
      });
    }
  }
  const ph = el("div"); ph.id = "playhead"; ph.title = "Drag to scrub"; ph.addEventListener("pointerdown", scrubFrom);
  const hl = el("div"); hl.id = "hoverLine";
  els.inner.append(ph, hl);
  els.scroll.scrollLeft = sl; els.scroll.scrollTop = stp;
  placePlayhead(); refreshValues();
}
function drawCurve(lane, n) {
  const w = tx(st.spec.duration), h = 24;
  const keys = st.spec.tracks[n];
  const vs = keys.map((k) => +k.v), lo = Math.min(...vs), hi = Math.max(...vs);
  if (hi - lo < 1e-9) return;
  let d = "";
  for (let x = 0; x <= w; x += Math.max(3, w / 1500)) {
    const v = valueAt(n, x / st.pps), y = h - 4 - ((v - lo) / (hi - lo)) * (h - 8);
    d += (x ? "L" : "M") + x.toFixed(1) + " " + y.toFixed(1);
  }
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "curve"); svg.setAttribute("width", w); svg.setAttribute("height", h);
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path"); path.setAttribute("d", d);
  svg.appendChild(path); lane.appendChild(svg);
}
function addDiamond(lane, n, k) {
  const ease = k.ease || "inOutCubic";
  const d = el("div", "key e-" + ease + (isSel(n, k.t) ? " on" : "") + (ease === "hold" ? " hold" : ""));
  d.style.left = tx(k.t) + "px";
  d.title = `${st.meta.props[n].label} = ${(+k.v).toFixed(3)} at ${(+k.t).toFixed(2)}s · ease ${ease}\nDrag to move · Shift/Ctrl-click to add to the selection · right-click for ease`;
  d.addEventListener("pointerdown", (ev) => dragKey(ev, n, k));
  d.addEventListener("contextmenu", (ev) => keyMenu(ev, n, k));
  lane.appendChild(d);
}
function placePlayhead() {
  const ph = $("#playhead"), head = $("#phHead"); if (!ph || !st.spec) return;
  ph.style.left = LABEL_W + tx(st.t) + "px";
  if (head) head.style.left = tx(st.t) + "px";
  ph.style.visibility = tx(st.t) < els.scroll.scrollLeft - 1 ? "hidden" : "visible";
}
function followPlayhead(center) {
  const x = LABEL_W + tx(st.t), sc = els.scroll, vis = sc.clientWidth;
  if (center || x < sc.scrollLeft + LABEL_W + 20 || x > sc.scrollLeft + vis - 30) sc.scrollLeft = Math.max(0, x - LABEL_W - (center ? (vis - LABEL_W) / 2 : 40));
}
function refreshValues() {
  if (!st.spec) return;
  for (const r of $$(".tl-row.tl-prop")) {
    const n = r.dataset.prop, v = valueAt(n, st.t), kb = $(".kb", r);
    $(".vv", r).textContent = fmt(n, v);
    kb.classList.toggle("anim", animated(n)); kb.classList.toggle("here", keyIndexAt(n, st.t) >= 0);
  }
  for (const row_ of $$(".pane .prop[data-prop]")) {
    const n = row_.dataset.prop, v = valueAt(n, st.t);
    const rng = $("input[type=range]", row_), num = $("input[type=number]", row_), kb = $(".kb", row_);
    if (document.activeElement !== rng) rng.value = v;
    if (document.activeElement !== num) num.value = +v.toFixed(3);
    kb.classList.toggle("anim", animated(n)); kb.classList.toggle("here", keyIndexAt(n, st.t) >= 0);
  }
}
function fmt(n, v) { return Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2); }
// Drag the playhead (from the ruler, the playhead line, a verse or an empty
// lane). `at`: start there instead of under the pointer (a verse's start).
// Playback pauses while scrubbing and resumes on release.
function scrubFrom(ev, at = null) {
  if (ev.button !== 0) return;
  ev.preventDefault(); ev.stopPropagation(); closeCtx();
  const was = st.playing; pause();
  if (st.sel) { st.sel = null; buildTimeline(); buildPanes(); }
  els.scroll.classList.add("scrubbing");
  const go = (e, t) => { if (e.shiftKey) t = snapT(t, [], true); seek(t); };
  go(ev, at ?? timeAt(ev.clientX));
  track(ev, els.scroll, (e) => go(e, timeAt(e.clientX)), () => { els.scroll.classList.remove("scrubbing"); if (was) play(); }, { edge: true, cursor: "ew" });
}
// An empty lane: Shift-drag draws a selection box (Shift+Ctrl adds to the
// selection); a plain drag scrubs.
function laneDown(ev) {
  if (ev.button !== 0) return;
  if (!ev.shiftKey) return scrubFrom(ev);
  ev.preventDefault(); closeCtx();
  const base = ev.ctrlKey || ev.metaKey ? selItems() : [];
  const r0 = els.inner.getBoundingClientRect(), x0 = ev.clientX - r0.left, y0 = ev.clientY - r0.top;
  const box = el("div", "marquee"); els.inner.appendChild(box);
  const draw_ = (e) => {
    const r = els.inner.getBoundingClientRect(), x1 = e.clientX - r.left, y1 = e.clientY - r.top;
    Object.assign(box.style, { left: Math.min(x0, x1) + "px", top: Math.min(y0, y1) + "px", width: Math.abs(x1 - x0) + "px", height: Math.abs(y1 - y0) + "px" });
  };
  draw_(ev);
  track(ev, els.scroll, draw_, () => {
    const b = box.getBoundingClientRect(); box.remove();
    const ta = timeAt(b.left), tb = timeAt(b.right), items = [...base];
    for (const r of $$(".tl-row.tl-prop, .tl-row.group", els.inner)) {
      const rr = r.getBoundingClientRect();
      if (rr.bottom < b.top || rr.top > b.bottom) continue;
      const props = r.dataset.prop ? [r.dataset.prop] : st.collapsed[r.dataset.group] ? groupProps(r.dataset.group) : [];
      for (const p of props) for (const k of st.spec.tracks[p] || []) if (k.t >= ta - 1e-6 && k.t <= tb + 1e-6 && !items.some((x) => x.prop === p && Math.abs(x.t - k.t) < 1e-6)) items.push({ prop: p, t: k.t });
    }
    selectKeys(items); buildTimeline(); buildPanes();
    if (items.length) showTab("animate");
  }, { edge: true });
}
// Drag keys: the grabbed one plus the rest of the selection, keeping their
// spacing. Shift/Ctrl-click toggles a key in the selection instead.
function dragKey(ev, n, k) {
  if (ev.button !== 0) return;
  ev.preventDefault(); ev.stopPropagation(); closeCtx(); pause(); hideExact(); st.coalesce = null;
  if (ev.shiftKey || ev.ctrlKey || ev.metaKey) { toggleInSel(n, k.t); buildTimeline(); buildPanes(); return; }
  if (!isSel(n, k.t)) selectKeys([{ prop: n, t: k.t }]);
  else selectKeys([{ prop: n, t: k.t }, ...selItems().filter((x) => !(x.prop === n && Math.abs(x.t - k.t) < 1e-6))]);
  const moving = selItems().map(({ prop, t }) => ({ prop, key: findKey(prop, t), t0: t })).filter((m) => m.key);
  if (!moving.length) return;
  const lead = moving[0], x0 = ev.clientX, start = timeAt(ev.clientX);
  const lo = -Math.min(...moving.map((m) => m.t0)), hi = st.spec.duration - Math.max(...moving.map((m) => m.t0));
  let moved = false;
  track(ev, els.scroll, (e) => {
    if (!moved && Math.abs(e.clientX - x0) < 3) return;
    if (!moved) { st.undo.push(snapshot()); if (st.undo.length > 200) st.undo.shift(); st.redo = []; moved = true; }
    let t = lead.t0 + timeAt(e.clientX) - start;
    if (!e.altKey) t = snapT(t, moving.map((m) => ({ prop: m.prop, t: m.key.t })));
    const d = clamp(t - lead.t0, lo, hi);
    // refuse a step that would land a key on another key of its track
    if (moving.some((m) => trackOf(m.prop).some((x) => !moving.some((o) => o.key === x) && Math.abs(x.t - (m.t0 + d)) < frameT() / 2))) return;
    for (const m of moving) m.key.t = r3(m.t0 + d);
    for (const p of new Set(moving.map((m) => m.prop))) trackOf(p).sort((a, b) => a.t - b.t);
    selectKeys(moving.map((m) => ({ prop: m.prop, t: m.key.t })));
    st.spec = resolve(st.raw); st.t = lead.key.t; syncMedia(true); scheduleTimeline(); draw();
  }, () => {
    if (moved) { st.coalesce = null; changed(); } else { seek(lead.key.t); buildTimeline(); }
    buildPanes(); showTab("animate");
  }, { edge: true, cursor: "ew" });
}
function dragPiece(ev, i) {
  if (ev.button !== 0) return;
  ev.preventDefault(); ev.stopPropagation(); closeCtx(); pause(); hideExact(); st.coalesce = null;
  st.sel = { type: "piece", i };
  const p = st.raw.edit[i], mode = ev.target.classList.contains("l") ? "l" : ev.target.classList.contains("r") ? "r" : null;
  if (!mode) { seek(clamp(timeAt(ev.clientX), pieceStart(i), pieceStart(i) + p.out - p.in)); buildTimeline(); buildPanes(); showTab("recording"); return; }
  const minLength = Math.min(MIN_PIECE, p.out - p.in);
  const in0 = p.in, out0 = p.out, start = timeAt(ev.clientX), max = st.tl && st.tl.take ? st.tl.take : 1e9;
  let moved = false;
  track(ev, els.scroll, (e) => {
    const dt = timeAt(e.clientX) - start;
    if (!moved && Math.abs(dt * st.pps) < 2) return;
    if (!moved) { st.undo.push(snapshot()); if (st.undo.length > 200) st.undo.shift(); st.redo = []; moved = true; }
    if (mode === "l") p.in = clamp(r3(in0 + dt), 0, p.out - minLength);
    else p.out = clamp(r3(out0 + dt), p.in + minLength, Math.max(p.in + minLength, max));
    st.spec = resolve(st.raw);
    // show the frame at the edge being trimmed
    st.t = clamp(pieceStart(i) + (mode === "l" ? 0 : p.out - p.in - 1e-3), 0, st.spec.duration);
    syncMedia(true); scheduleTimeline(); draw();
  }, () => { if (moved) changed(); buildPanes(); }, { cursor: "ew" });
}
function resizeLabels(ev) {
  if (ev.button !== 0) return;
  ev.preventDefault(); ev.stopPropagation();
  const x0 = ev.clientX, w0 = LABEL_W, host = els.scroll;
  track(ev, host, (e) => {
    LABEL_W = Math.round(clamp(w0 + e.clientX - x0, 90, 260));
    document.documentElement.style.setProperty("--label", LABEL_W + "px");
    scheduleTimeline();
  }, () => localStorage.setItem("promo.labelw", LABEL_W), { cursor: "col-resize" });
}
// Where the pointer is on the timeline, and its time, while nothing is held.
function hoverGuide(e) {
  const hl = $("#hoverLine"), ht = $("#hoverTime");
  if (!hl || !ht || !st.spec) return;
  const t = timeAt(e.clientX), show = !e.buttons && t >= 0 && t <= st.spec.duration && !e.target.closest(".tl-label");
  hl.style.display = ht.style.display = show ? "block" : "none";
  if (!show) return;
  hl.style.left = LABEL_W + tx(t) + "px"; ht.style.left = tx(t) + "px"; ht.textContent = t.toFixed(2) + "s";
}

// ------------------------------------------------------------------ menus

function closeCtx() { const m = $("#ctx"); if (m) m.remove(); }
function ctxMenu(ev, items) {
  ev.preventDefault(); ev.stopPropagation(); closeCtx();
  const m = el("div"); m.id = "ctx";
  for (const it of items) {
    if (it === "-") { m.append(el("div", "sep")); continue; }
    if (it.head) { m.append(el("div", "hd", it.head)); continue; }
    const b = el("button", (it.danger ? "danger" : "") + (it.on ? " on" : ""), it.label);
    if (it.disabled) b.disabled = true;
    b.onclick = () => { closeCtx(); it.run(); };
    m.append(b);
  }
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = Math.min(ev.clientX, innerWidth - r.width - 6) + "px";
  m.style.top = Math.min(ev.clientY, innerHeight - r.height - 6) + "px";
}
const EASE_LABEL = { inOutCubic: "Ease in & out", inOutSine: "Gentle in & out", outCubic: "Ease out", inCubic: "Ease in", outExpo: "Fast out, long settle", linear: "Linear", hold: "Hold (cut)" };
function keyMenu(ev, n, k) {
  if (!isSel(n, k.t)) selectKeys([{ prop: n, t: k.t }]);
  buildTimeline(); buildPanes();
  const cur = k.ease || "inOutCubic", count = selItems().length;
  ctxMenu(ev, [
    { head: count > 1 ? `${count} keys · ease into them` : "Ease into this key" },
    ...st.meta.eases.map((e) => ({ label: EASE_LABEL[e] || e, on: e === cur, run: () => setEase(e) })),
    "-",
    { label: "Go to key", run: () => { seek(k.t); followPlayhead(true); } },
    { label: count > 1 ? `Copy ${count} keys` : "Copy", run: copyKey },
    { label: count > 1 ? `Delete ${count} keys` : "Delete", danger: true, run: deleteSelection },
  ]);
}
function pieceMenu(ev, i) {
  st.sel = { type: "piece", i }; buildTimeline(); buildPanes();
  const t = timeAt(ev.clientX);
  ctxMenu(ev, [
    { label: "Split here", run: () => splitAt(t) },
    { label: "Split at the playhead", run: () => splitAt() },
    "-",
    { label: "Delete piece", danger: true, disabled: st.spec.edit.length <= 1, run: deleteSelection },
  ]);
}
function laneMenu(ev, n) {
  const t = clamp(timeAt(ev.clientX), 0, st.spec.duration);
  ctxMenu(ev, [
    ...(n ? [{ label: `Add a ${st.meta.props[n].label} key here`, run: () => { mutate(() => { keyAll([n], t); selectKeys([{ prop: n, t: r3(t) }]); }); seek(t); buildPanes(); } }] : []),
    { label: "Paste here", disabled: !st.clipboard, run: () => pasteKey(t) },
    { label: "Select all keys", run: selectAllKeys },
  ]);
}

// ------------------------------------------------------------------ stage gestures (auto-key)

function stageDrag(ev) {
  if (ev.button !== 0) return;
  if (!st.geo || !st.spec) return;
  ev.preventDefault(); pause(); hideExact(); closeCtx(); st.coalesce = null;
  els.stage.classList.add("drag");
  const rect = els.stage.getBoundingClientRect(), outPerPx = st.geo.W / rect.width;
  const S0 = stateAt(st.t), x0 = ev.clientX, y0 = ev.clientY, phone = ev.altKey;
  const tag = (phone ? "drag-phone:" : "drag-cam:") + performance.now();
  track(ev, els.stage, (e) => {
    const sx = (e.clientX - x0) * outPerPx, sy = (e.clientY - y0) * outPerPx;
    const [c, r] = rot(-S0.rotate), dx = c * sx - r * sy, dy = r * sx + c * sy;
    if (phone) {
      setProp("phone_x", S0.phone_x + dx / (S0.zoom * st.geo.W), tag);
      setProp("phone_y", S0.phone_y + dy / (S0.zoom * st.geo.H), tag);
    } else {
      // dragging the picture right moves the camera left
      setProp("pan_x", S0.pan_x - dx / (S0.zoom * st.geo.W), tag);
      setProp("pan_y", S0.pan_y - dy / (S0.zoom * st.geo.H), tag);
    }
  }, () => { st.coalesce = null; els.stage.classList.remove("drag"); buildTimeline(); });
}
function stageWheel(ev) {
  if (!st.geo || !st.spec) return;
  ev.preventDefault(); pause(); hideExact();
  const S = stateAt(st.t), f = Math.exp(-ev.deltaY * 0.0015);
  if (ev.altKey) setProp("phone_scale", S.phone_scale * f, "wheel-phone");
  else if (ev.shiftKey) setProp("rotate", S.rotate + (ev.deltaY > 0 ? 0.5 : -0.5), "wheel-rot");
  else setProp("zoom", S.zoom * f, "wheel-zoom");
  clearTimeout(stageWheel.t); stageWheel.t = setTimeout(buildTimeline, 200);
}

// ------------------------------------------------------------------ inspector

function field(label, path, type, opts = {}) {
  const w = el("label", "field"); w.append(el("span", null, label));
  const cur = opts.get ? opts.get() : (getPath(st.raw, path) ?? getPath(st.spec, path));
  const set = (v) => { if (typeof v === "number" && !Number.isFinite(v)) return; mutate(() => { if (opts.set) opts.set(v); else setPath(st.raw, path, v); }, opts.coalesce ? "f:" + path : null); };
  let input;
  if (type === "select") {
    input = el("select");
    for (const [v, t] of opts.options) { const o = el("option", null, t); o.value = v; if (String(v) === String(cur)) o.selected = true; input.appendChild(o); }
    input.onchange = () => { set(opts.num ? +input.value : input.value); if (opts.after) opts.after(); };
  } else if (type === "range") {
    const box = el("div", "rng");
    input = el("input"); Object.assign(input, { type: "range", min: opts.min, max: opts.max, step: opts.step, value: cur });
    const num = el("input"); Object.assign(num, { type: "number", min: opts.min, max: opts.max, step: opts.step, value: cur });
    input.oninput = () => { num.value = input.value; mutate(() => setPath(st.raw, path, +input.value), "f:" + path); };
    num.onchange = () => { input.value = num.value; mutate(() => setPath(st.raw, path, +num.value)); };
    box.append(input, num); w.appendChild(box); return w;
  } else {
    input = el("input"); input.type = type; input.value = cur ?? "";
    if (opts.step) input.step = opts.step; if (opts.min != null) input.min = opts.min;
    input.onchange = () => { set(type === "number" ? +input.value : input.value); if (opts.after) opts.after(); };
  }
  w.appendChild(input); return w;
}
const h3 = (t) => el("h3", null, t);
const noteEl = (t) => el("p", "note", t);

function buildPanes() {
  if (!st.raw) return;
  const m = st.meta;
  // Animate
  let p = $(".pane[data-pane=animate]"); p.innerHTML = "";
  let group = null;
  for (const [n, d] of Object.entries(m.props)) {
    if (d.group !== group) { group = d.group; p.append(h3(group)); }
    const r = el("div", "prop"); r.dataset.prop = n;
    const kb = el("button", "kb", "◆"); kb.title = "Add / remove a key at the playhead"; kb.onclick = () => toggleKey(n);
    const box = el("div", "rng");
    const rng = el("input"); Object.assign(rng, { type: "range", min: d.min, max: d.max, step: d.step });
    const num = el("input"); Object.assign(num, { type: "number", min: d.min, max: d.max, step: d.step });
    rng.oninput = () => { num.value = rng.value; setProp(n, rng.value); };
    num.onchange = () => setProp(n, num.value, "num:" + n + performance.now());
    rng.onchange = () => { st.coalesce = null; buildTimeline(); };
    box.append(rng, num); r.append(kb, el("span", null, d.label), box); p.append(r);
  }
  p.append(noteEl("◆ adds or removes a key at the playhead. Once a property has a key, changing it (here or on the picture) keys it at the playhead; with none, it is one value for the whole video."));
  const sk = selKey(), count = selItems().length;
  if (count > 1) {
    const card = el("div", "card");
    const eases = new Set(selItems().map(({ prop, t }) => (findKey(prop, t) || {}).ease || "inOutCubic"));
    card.append(el("h3", null, `${count} keys selected`),
      field("Ease into them", "", "select", { options: [...(eases.size > 1 ? [["", "(mixed)"]] : []), ...m.eases.map((e) => [e, EASE_LABEL[e] || e])], get: () => (eases.size > 1 ? "" : [...eases][0]), set: () => {}, after: () => {} }));
    $("select", card).onchange = (e) => { if (e.target.value) setEase(e.target.value); };
    const row_ = el("div", "row");
    const del = el("button", "danger", `Delete ${count} keys`); del.onclick = deleteSelection;
    const cp = el("button", "ghost", "Copy"); cp.onclick = copyKey;
    row_.append(del, cp); card.append(row_, noteEl("Drag any of them on the timeline to move them together; , and . nudge them a frame."));
    p.append(card);
  } else if (sk) {
    const card = el("div", "card");
    const d = m.props[st.sel.prop];
    card.append(el("h3", null, `Key · ${d.label}`),
      field("Time (s)", "", "number", { step: 0.01, get: () => sk.k.t, set: (v) => { const t = r3(clamp(v, 0, st.spec.duration)); if (sk.keys.some((k) => k !== sk.k && Math.abs(k.t - t) < frameT() / 2)) return note("there is already a key here"); sk.k.t = t; st.sel.t = sk.k.t; sk.keys.sort((a, b) => a.t - b.t); }, after: () => { seek(sk.k.t); buildPanes(); } }),
      field("Value", "", "number", { step: d.step, get: () => sk.k.v, set: (v) => { sk.k.v = r3(clamp(v, d.min, d.max)); } }),
      field("Ease into it", "", "select", { options: m.eases.map((e) => [e, EASE_LABEL[e] || e]), get: () => sk.k.ease || "inOutCubic", set: (v) => { if (v === "inOutCubic") delete sk.k.ease; else sk.k.ease = v; } }));
    const row_ = el("div", "row");
    const del = el("button", "danger", "Delete key"); del.onclick = deleteSelection;
    const clr = el("button", "ghost", "Clear track"); clr.title = "Remove every key of this property; it keeps the value at the playhead";
    clr.onclick = () => { const n = st.sel.prop, v = r3(valueAt(n, st.t)); mutate(() => { delete st.raw.tracks[n]; (st.raw.props = st.raw.props || {})[n] = v; st.sel = null; }); buildPanes(); };
    row_.append(del, clr); card.append(row_); p.append(card);
  }
  // Recording
  p = $(".pane[data-pane=recording]"); p.innerHTML = "";
  const surahs = m.surahs.map((s) => [s.id, `${s.id}. ${s.name} (${s.ayahs})`]);
  p.append(h3("What the app plays"),
    field("Reciter", "app.reciter", "select", { options: Object.entries(m.reciters) }),
    field("Chapter", "app.surah", "select", { options: surahs, num: true }),
    field("From ayah", "app.ayah", "number", { min: 1 }),
    noteEl("Ayah 1 opens on the chapter header and plays the basmalah first."),
    field("Theme", "app.theme", "select", { options: Object.keys(m.themes).map((t) => [t, t]) }),
    noteEl("Tarjīʿ glimmer shows on Nightfall and Royal green only."),
    field("Layout", "app.layout", "select", { options: m.layouts.map((l) => [l, l]) }),
    field("View", "app.view", "select", { options: m.views.map((v) => [v, v]) }),
    field("Text size", "app.text_size", "select", { options: [["default", "Default"], ["max", "Largest"]] }),
    field("Record (s)", "app.seconds", "number", { min: 5, step: 1 }),
    noteEl(st.tl && st.tl.take ? `Take: ${st.tl.take.toFixed(1)} s (opens 1 s before the recitation). Recorded at 1× with the emulator's own sound.` : "No take yet: press Record take."));
  p.append(h3("Edit"));
  const list = el("div", "pieces");
  list.append(el("div", "pc note", "<span>#</span><span>in (take s)</span><span>out (take s)</span><span></span>"));
  st.raw.edit.forEach((pc, i) => {
    const r = el("div", "pc" + (st.sel && st.sel.type === "piece" && st.sel.i === i ? " on" : ""));
    const a = el("input"); a.type = "number"; a.step = 0.01; a.value = pc.in;
    const b = el("input"); b.type = "number"; b.step = 0.01; b.value = pc.out;
    a.onchange = () => { mutate(() => { pc.in = clamp(r3(+a.value), 0, pc.out - Math.min(MIN_PIECE, pc.out - pc.in)); }); buildPanes(); }
    b.onchange = () => { mutate(() => { pc.out = Math.max(pc.in + Math.min(MIN_PIECE, pc.out - pc.in), r3(+b.value)); }); buildPanes(); }
    const x = el("button", "x danger", "✕"); x.title = "Delete this piece";
    x.onclick = () => { st.sel = { type: "piece", i }; deleteSelection(); };
    r.append(el("span", null, String(i + 1)), a, b, x); list.append(r);
  });
  const rowb = el("div", "row");
  const sp = el("button", null, "✂ Split at playhead"); sp.onclick = () => splitAt();
  const rs = el("button", "ghost", "Whole take"); rs.title = "One piece: the whole take"; rs.onclick = resetEdit;
  rowb.append(sp, rs);
  p.append(list, rowb, noteEl(`Video: ${st.spec.duration.toFixed(2)} s from ${st.spec.edit.length} piece${st.spec.edit.length > 1 ? "s" : ""}. The sound is cut with the picture. Keys stay where they are in video time.`));
  // Look
  p = $(".pane[data-pane=look]"); p.innerHTML = "";
  p.append(h3("Video"),
    field("Title", "title", "text"),
    field("Aspect", "aspect", "select", { options: Object.keys(m.aspects).map((a) => [a, `${a}  (${m.aspects[a].join("×")})`]) }),
    field("Frame rate", "fps", "select", { options: [[60, "60 fps"], [30, "30 fps"]], num: true }),
    field("Motion blur", "motion_blur", "range", { min: 0, max: 2, step: 0.05 }),
    h3("Background"));
  const grid = el("div", "bggrid");
  for (const [id, b] of Object.entries(m.backgrounds)) {
    const btn = el("button", id === st.spec.background.id ? "on" : "", `<img src="${b.thumb}" loading="lazy" alt=""><span>${id}</span>`);
    btn.title = `${b.title} · suits ${b.suits.join(", ")}`;
    btn.onclick = () => { mutate(() => setPath(st.raw, "background.id", id)); buildPanes(); };
    grid.appendChild(btn);
  }
  p.append(grid,
    field("Saturation", "background.saturation", "range", { min: 0, max: 1.6, step: 0.05 }),
    field("Motion speed", "background.speed", "range", { min: 0.1, max: 1.5, step: 0.05 }),
    field("Parallax", "background.parallax", "range", { min: 0, max: 1, step: 0.01 }),
    field("Vignette", "background.vignette", "range", { min: 0, max: 0.8, step: 0.01 }),
    noteEl("Blur and brightness are on the Animate tab, so they can be keyed. Footage from Pexels (free licence); any image or video works as \"file:/path\" in the spec."),
    h3("Phone"),
    field("Colour", "phone.color", "select", { options: [["auto", "Match theme"], ...m.phones.map((c) => [c, c[0].toUpperCase() + c.slice(1)])], get: () => st.raw.phone?.color ?? "auto" }),
    field("Base size", "phone.height", "range", { min: 0.4, max: 1.2, step: 0.01 }),
    field("Shadow", "phone.shadow", "range", { min: 0, max: 1, step: 0.01 }),
    h3("Sound (the emulator's own recording)"),
    field("Gain (dB)", "audio.gain_db", "range", { min: -12, max: 12, step: 0.5 }),
    field("Fade in (s)", "audio.fade_in", "range", { min: 0, max: 3, step: 0.1 }),
    field("Fade out (s)", "audio.fade_out", "range", { min: 0, max: 4, step: 0.1 }),
    noteEl("Loudness is normalised to −16 LUFS on export."));
  refreshValues();
}
function showTab(name) {
  $$("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.tab === name));
  $$(".pane").forEach((p) => p.classList.toggle("on", p.dataset.pane === name));
}

// ------------------------------------------------------------------ saving & loading

function changed() {
  st.spec = resolve(st.raw);
  st.t = clamp(st.t, 0, st.spec.duration);
  hideExact(); scheduleTimeline(); draw(); histButtons();
  $("#saveState").textContent = "editing…";
  clearTimeout(st.saveTimer); st.saveTimer = setTimeout(save, 450);
  if (plateLook(st.spec) !== st.lastLook) { clearTimeout(st.plateTimer); st.plateTimer = setTimeout(loadPlate, 400); }
}
async function save() {
  clearTimeout(st.saveTimer);
  if (!st.raw) return true;
  const name = st.name, body = snapshot(), session = st.session;
  const task = st.saveChain.catch(() => {}).then(async () => {
    if (session === st.session && st.saved === body) {   // e.g. undone back to what is on disk
      if (body === snapshot()) $("#saveState").textContent = "saved";
      return;
    }
    const r = await api(`/api/spec/${name}`, { method: "PUT", body });
    const i = st.specs.findIndex((x) => x.name === name);
    if (i >= 0) st.specs[i] = r.status;
    if (session !== st.session) return;
    st.saved = body;
    $("#saveState").textContent = body === snapshot() ? "saved" : "editing…";
    renderList(); updateActions();
    if (body === snapshot() && (!st.geo || takeKey(st.spec) !== st.lastTakeKey || !st.geo.frame.includes(st.spec.phone.color) || `${st.geo.W}x${st.geo.H}` !== st.meta.aspects[st.spec.aspect].join("x"))) await loadGeometry();
  });
  st.saveChain = task;
  try { await task; return true; }
  catch (e) { if (session === st.session) $("#saveState").textContent = "not saved: " + e.message; return false; }
}
async function loadGeometry() {
  const name = st.name, session = st.session, seq = ++st.geoSeq, spec = st.spec;
  const [geo, tl] = await Promise.all([api(`/api/geometry/${name}`), api(`/api/timeline/${name}`).catch(() => null)]);
  if (session !== st.session || seq !== st.geoSeq || takeKey(spec) !== takeKey(st.spec) || spec.aspect !== st.spec.aspect || spec.phone.color !== st.spec.phone.color) return;
  st.geo = geo; st.tl = tl; st.lastTakeKey = takeKey(spec);
  els.frame.src = geo.frame;
  if (geo.clip) { if (!els.screen.src.endsWith(geo.clip)) { els.screen.src = geo.clip; els.audio.src = geo.audio; } }
  else { els.screen.removeAttribute("src"); els.audio.removeAttribute("src"); els.screen.load(); els.audio.load(); }
  syncMedia.piece = null;
  sizeStage(); buildTimeline(); buildPanes(); updateActions(); seek(st.t);
}
async function loadPlate() {
  if (!st.raw) return;
  const look = plateLook(st.spec), session = st.session, seq = ++st.plateSeq;
  note("preparing background…", 1500);
  try {
    const plate = await api(`/api/plate/${st.name}`, { method: "POST", body: snapshot() });
    if (session !== st.session || seq !== st.plateSeq || look !== plateLook(st.spec)) return;
    st.plate = plate; st.lastLook = look;
    els.plate.onloadeddata = () => { if (session === st.session) { els.plate.currentTime = plateTime(st.t); draw(); } };
    els.plate.src = plate.url;
  } catch (e) { if (session === st.session) note("background: " + e.message); }
}
async function open(name) {
  const seq = ++st.openSeq;
  pause();
  if (!await save()) return note("Save failed; staying on this video. Click the save status to retry.", 5000);
  try {
    const r = await api(`/api/spec/${name}`);
    if (seq !== st.openSeq) return;
    clearTimeout(st.saveTimer); clearTimeout(st.plateTimer);
    st.session++; st.geoSeq++; st.plateSeq++;
    $("#panes").inert = true;
    st.name = name; st.sel = null; st.t = 0; st.undo = []; st.redo = []; st.coalesce = null;
    st.raw = r.raw; st.spec = resolve(st.raw); st.raw.edit = clone(st.spec.edit);
    st.saved = snapshot(); st.geo = null; st.plate = null; st.tl = null; st.lastLook = "";
    localStorage.setItem("promo.last", name);
    renderList(); histButtons();
    await loadGeometry();
    $("#panes").inert = false;
    if (seq !== st.openSeq) return;
    fitTimeline();
    await loadPlate();
    if (seq === st.openSeq) $("#saveState").textContent = st.saved === snapshot() ? "saved" : "editing…";
  } catch (e) { $("#panes").inert = false; note("Could not open video: " + e.message, 5000); }
}
function fitTimeline() {
  if (!st.spec) return;
  const w = els.scroll.clientWidth - LABEL_W - 50;
  st.pps = clamp(w / Math.max(1, st.spec.duration), 10, 400); $("#pps").value = st.pps; buildTimeline();
}
function renderList() {
  const ul = $("#specList"); ul.innerHTML = "";
  for (const s of st.specs) {
    const li = el("li", s.name === st.name ? "on" : "");
    if (s.error) li.innerHTML = `<div class="t">${escapeHTML(s.name)}</div><div class="s">⚠ ${escapeHTML(s.error)}</div>`;
    else {
      const rec = st.meta.reciters[s.app.reciter].split(" ").pop();
      li.innerHTML = `<div class="t">${escapeHTML(s.title || s.name)}</div><div class="s"><span>${rec}</span><span>${s.app.surah}:${s.app.ayah}</span><span>${s.app.theme}</span><span>${s.aspect}</span><span>${(s.duration || 0).toFixed(1)}s</span></div>
        <div class="s"><span><i class="dot ${s.take ? (s.take_short ? "short" : "ok") : ""}"></i>${s.take_short ? "take too short" : "take"}</span><span><i class="dot ${s.video ? "vid" : ""}"></i>video</span></div>`;
    }
    li.onclick = () => open(s.name);
    li.tabIndex = 0; li.setAttribute("aria-current", String(s.name === st.name));
    li.onkeydown = (e) => { if (e.key === "Enter") { e.preventDefault(); open(s.name); } };
    ul.appendChild(li);
  }
}
async function refreshList() { st.specs = await api("/api/specs"); renderList(); }
function updateActions() {
  const have = !!(st.geo && st.geo.clip);
  for (const id of ["play", "split", "keyCam", "presetPush", "presetPull", "presetPunch", "recordBtn", "rerecordBtn", "dupSpec", "delSpec"]) $("#" + id).disabled = !st.raw;
  $("#exactBtn").disabled = !have;
  $("#recordBtn").classList.toggle("hidden", have);
  $("#rerecordBtn").classList.toggle("hidden", !have);
  $("#exportBtn").disabled = !have;
  const s = st.specs.find((x) => x.name === st.name), a = $("#openVideo");
  if (s && s.video) { a.href = `/media/out/${st.name}.mp4?${s.video_mtime}`; a.classList.remove("hidden"); } else a.classList.add("hidden");
}

// ------------------------------------------------------------------ jobs & emulator

async function startJob(path, body = {}) {
  try { if (!await save()) throw new Error("Save failed; job not started"); await api(path, { method: "POST", body: JSON.stringify(body) }); pollJobs(); } catch (e) { note(e.message, 4000); }
}
let jobsTimer = null;
async function pollJobs() {
  clearTimeout(jobsTimer);
  const jobs = await api("/api/jobs").catch(() => []);
  const box = $("#jobs"); box.innerHTML = "";
  let active = false;
  for (const j of jobs.slice(0, 8)) {
    const pct = Math.round((j.progress || 0) * 100);
    const link = j.state === "done" && j.kind === "render" ? ` · <a target="_blank" href="/media/out/${j.name}.mp4?${Date.now()}">open ↗</a>` : "";
    const d = el("div", "job " + j.state, `<div><b class="k">${j.kind}</b> ${j.name} — ${j.state}${j.state === "running" && j.kind === "render" ? ` ${pct}%` : ""}${link}</div>
      ${j.state === "running" ? `<div class="bar"><i style="width:${j.kind === "render" ? pct : 100}%"></i></div>` : ""}
      ${j.log.length ? `<pre>${j.log.slice(-4).join("\n").replace(/</g, "&lt;")}</pre>` : ""}`);
    box.appendChild(d);
    if (j.state === "running" || j.state === "queued") active = true;
    if (j.state === "done" && !pollJobs.seen.has(j.id)) {
      pollJobs.seen.add(j.id);
      await refreshList();
      if (j.name === st.name) { if (j.kind === "capture") await loadGeometry(); updateActions(); }
    }
  }
  jobsTimer = setTimeout(pollJobs, active ? 1000 : 5000);
}
pollJobs.seen = new Set();
async function pollEmu() {
  const e = await api("/api/emu").catch(() => ({ up: false, serial: "?" }));
  const p = $("#emu"); p.textContent = `${e.serial} ${e.up ? "ready" : "off"}`; p.classList.toggle("up", e.up);
  $("#emuBoot").classList.toggle("hidden", e.up);
  setTimeout(pollEmu, 8000);
}

function copyKey() {
  const items = selItems().map(({ prop, t }) => ({ prop, key: findKey(prop, t) })).filter((x) => x.key);
  if (!items.length) return note("Select keys to copy");
  const t0 = Math.min(...items.map((x) => x.key.t));
  st.clipboard = items.map((x) => ({ prop: x.prop, key: clone(x.key), dt: x.key.t - t0 }));
  note(`Copied ${items.length} key${items.length > 1 ? "s" : ""}`);
}
// Paste at the playhead (or `at`), keeping the copied keys' spacing.
function pasteKey(at = st.t) {
  if (!st.clipboard || !st.raw) return;
  const placed = [];
  mutate(() => {
    for (const c of st.clipboard) {
      const t = r3(clamp(at + c.dt, 0, st.spec.duration)), keys = trackOf(c.prop), i = keyIndexAt(c.prop, t), k = { ...c.key, t };
      if (i >= 0) keys[i] = k; else keys.push(k);
      keys.sort((a, b) => a.t - b.t); placed.push({ prop: c.prop, t });
    }
    selectKeys(placed);
  });
  buildPanes(); showTab("animate");
}
function toggleHelp() {
  const help = $("#shortcutHelp"); help.classList.toggle("hidden");
  $("#helpBtn").setAttribute("aria-expanded", String(!help.classList.contains("hidden")));
}

// ------------------------------------------------------------------ panes

// Side panes resize from their dividers and fold away (Ctrl+B / Ctrl+.);
// the timeline resizes from the divider above it. All of it is remembered.
const PANE_DEFAULTS = { left: 240, right: 330 };
const panes = {
  left: +(localStorage.getItem("promo.leftw") || PANE_DEFAULTS.left), right: +(localStorage.getItem("promo.rightw") || PANE_DEFAULTS.right),
  hideL: localStorage.getItem("promo.hideL") === "1", hideR: localStorage.getItem("promo.hideR") === "1",
};
function applyPanes() {
  const root = document.documentElement.style;
  root.setProperty("--left-w", (panes.hideL ? 0 : panes.left) + "px");
  root.setProperty("--right-w", (panes.hideR ? 0 : panes.right) + "px");
  $("#left").classList.toggle("gone", panes.hideL); $("#right").classList.toggle("gone", panes.hideR);
  $("#toggleLeft").classList.toggle("on", !panes.hideL); $("#toggleRight").classList.toggle("on", !panes.hideR);
  localStorage.setItem("promo.leftw", panes.left); localStorage.setItem("promo.rightw", panes.right);
  localStorage.setItem("promo.hideL", panes.hideL ? "1" : "0"); localStorage.setItem("promo.hideR", panes.hideR ? "1" : "0");
  sizeStage();
}
function dragPane(ev, side) {
  if (ev.button !== 0) return;
  ev.preventDefault();
  const bar = ev.currentTarget, x0 = ev.clientX, w0 = side === "left" ? (panes.hideL ? 0 : panes.left) : (panes.hideR ? 0 : panes.right);
  bar.classList.add("on");
  track(ev, bar, (e) => {
    const w = side === "left" ? w0 + e.clientX - x0 : w0 - (e.clientX - x0);
    const hide = w < 120;                       // drag it nearly shut to fold it away
    if (side === "left") { panes.hideL = hide; if (!hide) panes.left = Math.round(clamp(w, 170, 460)); }
    else { panes.hideR = hide; if (!hide) panes.right = Math.round(clamp(w, 260, 560)); }
    applyPanes();
  }, () => bar.classList.remove("on"), { cursor: "col-resize" });
}
function setTimelineHeight(h) {
  if (h == null) { els.scroll.style.height = ""; localStorage.removeItem("promo.tlh"); }
  else { h = clamp(h, 110, innerHeight * 0.7); els.scroll.style.height = h + "px"; localStorage.setItem("promo.tlh", h); }
  sizeStage();
}

// ------------------------------------------------------------------ wiring

async function init() {
  st.meta = await api("/api/meta");
  await refreshList();
  const last = localStorage.getItem("promo.last");
  const first = st.specs.find((s) => s.name === last) || st.specs[0];
  if (first) await open(first.name);
  pollJobs(); pollEmu();
}
document.documentElement.style.setProperty("--label", LABEL_W + "px");
if (localStorage.getItem("promo.tlh")) els.scroll.style.height = localStorage.getItem("promo.tlh") + "px";
applyPanes();
$("#play").onclick = play;
$("#loopBtn").onclick = toggleLoop;
$("#time").onclick = editTime;
$("#undo").onclick = undo;
$("#redo").onclick = redo;
$("#keyCam").onclick = () => { if (!st.spec) return; mutate(() => keyAll(["zoom", "pan_x", "pan_y"])); buildPanes(); };
$("#presetPush").onclick = () => preset("push");
$("#presetPull").onclick = () => preset("pull");
$("#presetPunch").onclick = () => preset("punch");
$("#exactBtn").onclick = showExact;
$("#split").onclick = () => splitAt();
$("#delSel").onclick = deleteSelection;
$("#prevKey").onclick = () => jumpKey(-1);
$("#nextKey").onclick = () => jumpKey(1);
$("#fit").onclick = fitTimeline;
$("#pps").oninput = (e) => { st.pps = +e.target.value; buildTimeline(); followPlayhead(true); };
$("#toggleLeft").onclick = () => { panes.hideL = !panes.hideL; applyPanes(); };
$("#toggleRight").onclick = () => { panes.hideR = !panes.hideR; applyPanes(); };
$("#splitL").addEventListener("pointerdown", (e) => dragPane(e, "left"));
$("#splitR").addEventListener("pointerdown", (e) => dragPane(e, "right"));
$("#splitL").addEventListener("dblclick", () => { panes.left = PANE_DEFAULTS.left; panes.hideL = false; applyPanes(); });
$("#splitR").addEventListener("dblclick", () => { panes.right = PANE_DEFAULTS.right; panes.hideR = false; applyPanes(); });
$("#tlResize").addEventListener("pointerdown", (ev) => {
  if (ev.button !== 0) return;
  ev.preventDefault();
  const bar = ev.currentTarget, y0 = ev.clientY, h0 = els.scroll.getBoundingClientRect().height;
  bar.classList.add("on");
  track(ev, bar, (e) => setTimelineHeight(h0 + (y0 - e.clientY)), () => bar.classList.remove("on"), { cursor: "row-resize" });
});
$("#tlResize").addEventListener("dblclick", () => setTimelineHeight(null));
els.scroll.addEventListener("wheel", (e) => {
  if (!e.ctrlKey) return;               // ctrl/pinch-wheel zooms the timeline around the cursor
  e.preventDefault();
  const t = timeAt(e.clientX), rect = els.scroll.getBoundingClientRect();
  st.pps = clamp(st.pps * Math.exp(-e.deltaY * 0.002), 10, 400); $("#pps").value = st.pps;
  buildTimeline(); els.scroll.scrollLeft = Math.max(0, t * st.pps - (e.clientX - rect.left - LABEL_W));
}, { passive: false });
els.scroll.addEventListener("scroll", () => { placePlayhead(); closeCtx(); });
els.scroll.addEventListener("pointermove", hoverGuide);
els.scroll.addEventListener("pointerleave", () => { for (const id of ["hoverLine", "hoverTime"]) { const x = document.getElementById(id); if (x) x.style.display = "none"; } });
els.stage.addEventListener("pointerdown", stageDrag);
els.stage.addEventListener("wheel", stageWheel, { passive: false });
$$("#tabs button").forEach((b) => (b.onclick = () => showTab(b.dataset.tab)));
$("#recordBtn").onclick = () => startJob(`/api/capture/${st.name}`);
$("#rerecordBtn").onclick = () => { if (confirm("Record this take again? The current take is replaced.")) startJob(`/api/capture/${st.name}`, { force: true }); };
$("#exportBtn").onclick = () => startJob(`/api/render/${st.name}`);
$("#exportAll").onclick = async () => { for (const s of st.specs) if (s.take) await startJob(`/api/render/${s.name}`); };
$("#emuBoot").onclick = () => startJob("/api/emu/boot");
$("#newSpec").onclick = async () => {
  const n = prompt("Name for the new video (letters, digits, - and _):", "new-video"); if (!n) return;
  try { await api(`/api/new/${n}`, { method: "POST" }); await refreshList(); await open(n); } catch (e) { note(e.message); }
};
$("#dupSpec").onclick = async () => {
  if (!st.name) return; const n = prompt("Name for the copy:", st.name + "-2"); if (!n) return;
  try { if (!await save()) return; await api(`/api/copy/${st.name}/${n}`, { method: "POST" }); await refreshList(); await open(n); } catch (e) { note(e.message); }
};
$("#delSpec").onclick = async () => {
  if (!st.name || !confirm(`Delete the spec "${st.name}"? (Takes and exported videos stay on disk.)`)) return;
  pause();
  if (!await save()) return;
  try {
    await api(`/api/spec/${st.name}`, { method: "DELETE" });
    clearTimeout(st.saveTimer); clearTimeout(st.plateTimer); st.session++; st.openSeq++; st.geoSeq++; st.plateSeq++;
    st.raw = st.spec = st.geo = st.plate = st.tl = st.sel = null; st.name = null; st.undo = []; st.redo = [];
    await refreshList(); histButtons(); updateActions();
    els.inner.innerHTML = ""; $$(".pane").forEach((p) => p.innerHTML = "");
    els.ctx.clearRect(0, 0, els.stage.width, els.stage.height); $("#saveState").textContent = "—"; $("#time").textContent = "0.00 / 0.00";
    if (st.specs[0]) await open(st.specs[0].name);
  } catch (e) { note(e.message); }
};
addEventListener("resize", sizeStage);
$("#helpBtn").onclick = toggleHelp;
$("#closeHelp").onclick = toggleHelp;
$("#saveState").onclick = save;
addEventListener("pointerdown", (e) => { if (!e.target.closest("#ctx")) closeCtx(); }, true);
addEventListener("blur", closeCtx);
addEventListener("beforeunload", (e) => {
  if (st.raw && snapshot() !== st.saved) { e.preventDefault(); e.returnValue = ""; }
});
addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if ($("#ctx")) return closeCtx();
    $("#shortcutHelp").classList.add("hidden"); $("#helpBtn").setAttribute("aria-expanded", "false");
    st.sel = null; buildTimeline(); buildPanes(); return;
  }
  // Text fields keep their own keys (typing, caret undo); sliders, dropdowns
  // and checkboxes don't, so app shortcuts like Ctrl+Z still work after a drag.
  const typing = e.target.closest("textarea, [contenteditable=true], input:not([type=range]):not([type=checkbox]):not([type=radio]):not([type=button])");
  const mod = e.ctrlKey || e.metaKey, key = e.key.toLowerCase();
  if (typing || (e.target.closest("select, input") && !mod)) return;
  if (mod && key === "s") { e.preventDefault(); save(); return; }
  if (mod && key === "b") { e.preventDefault(); panes.hideL = !panes.hideL; applyPanes(); return; }
  if (mod && key === ".") { e.preventDefault(); panes.hideR = !panes.hideR; applyPanes(); return; }
  if (!st.spec) return;
  if (mod && key === "z") { e.preventDefault(); return e.shiftKey ? redo() : undo(); }
  if (mod && key === "y") { e.preventDefault(); return redo(); }
  if (mod && key === "a") { e.preventDefault(); return selectAllKeys(); }
  if (mod && key === "c") { if (selItems().length) { e.preventDefault(); copyKey(); } return; }
  if (mod && key === "v") { if (st.clipboard) { e.preventDefault(); pasteKey(); } return; }
  if (mod || e.altKey) return;
  if (e.code === "Space") { e.preventDefault(); if (!e.repeat) play(); }
  else if (key === "l") toggleLoop();
  else if (key === "k") { mutate(() => keyAll(["zoom", "pan_x", "pan_y"])); buildPanes(); }
  else if (key === "s") { e.preventDefault(); if (!e.repeat) splitAt(); }
  else if (e.key === "Delete" || e.key === "Backspace") { e.preventDefault(); if (!e.repeat) deleteSelection(); }
  else if (e.key === "[") jumpKey(-1);
  else if (e.key === "]") jumpKey(1);
  else if (e.key === "," || e.key === "<") moveSelected(-(e.shiftKey ? 10 : 1) * frameT());
  else if (e.key === "." || e.key === ">") moveSelected((e.shiftKey ? 10 : 1) * frameT());
  else if (e.key === "ArrowUp") { e.preventDefault(); jumpLandmark(-1); }
  else if (e.key === "ArrowDown") { e.preventDefault(); jumpLandmark(1); }
  else if (e.key === "Home") { e.preventDefault(); seek(0); followPlayhead(true); }
  else if (e.key === "End") { e.preventDefault(); seek(st.spec.duration); followPlayhead(true); }
  else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
    e.preventDefault(); pause(); seek(st.t + (e.key === "ArrowLeft" ? -1 : 1) * (e.shiftKey ? 1 : frameT())); followPlayhead();
  }
  else if (e.key === "?") { e.preventDefault(); toggleHelp(); }
});
els.screen.addEventListener("seeked", () => { seekLanded(els.screen); draw(); });
els.audio.addEventListener("seeked", () => seekLanded(els.audio));
els.plate.addEventListener("seeked", () => { seekLanded(els.plate); bgCacheKey = ""; draw(); });
els.frame.addEventListener("load", draw);
init().catch((e) => note("Could not load the studio: " + e.message, 10000));
