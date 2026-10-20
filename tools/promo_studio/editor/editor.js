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
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const clone = (o) => JSON.parse(JSON.stringify(o));
const r3 = (v) => Math.round(v * 1000) / 1000;
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };

const st = {
  meta: null, specs: [], name: null, raw: null, spec: null, geo: null, plate: null, tl: null,
  t: 0, playing: false, sel: null, pps: 60, collapsed: {}, undo: [], redo: [], coalesce: null,
  saveTimer: null, plateTimer: null, lastTakeKey: "", lastLook: "", wall0: 0, t0: 0,
};
const els = {
  stage: $("#stage"), ctx: $("#stage").getContext("2d"), screen: $("#screenVid"), plate: $("#plateVid"),
  audio: $("#audio"), frame: $("#frameImg"), exact: $("#exact"), inner: $("#tlinner"), scroll: $("#tlscroll"),
};
const LABEL_W = 132;

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
  s.duration = s.edit.reduce((a, p) => a + (p.out - p.in), 0);
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
    ctx.save();
    const extra = Math.sqrt(Math.max(0, S.bg_blur ** 2 - st.plate.blur ** 2));
    if (extra > 0.3) ctx.filter = `blur(${extra * ps}px)`;
    ctx.setTransform(ps, 0, 0, ps, 0, 0); ctx.transform(...bgMatrix(S));
    ctx.drawImage(els.plate, -st.plate.w * 0.08, -st.plate.h * 0.08, st.plate.w * 1.16, st.plate.h * 1.16);
    ctx.restore();
  }
  ctx.setTransform(ps, 0, 0, ps, 0, 0);
  const b = S.bg_brightness;
  if (b) { ctx.globalAlpha = Math.min(1, Math.abs(b)); ctx.fillStyle = b > 0 ? "#fff" : "#000"; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1; }
  const vg = +s.background.vignette;
  if (vg > 0) {
    const grad = ctx.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, Math.hypot(W, H) / 2);
    grad.addColorStop(0, "rgba(0,0,0,0)"); grad.addColorStop(1, `rgba(0,0,0,${vg})`);
    ctx.fillStyle = grad; ctx.fillRect(0, 0, W, H);
  }
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
  const k = Math.max(0.05, Math.min((wrapEl.clientWidth - 20) / W, (wrapEl.clientHeight - 26) / H));
  const dpr = window.devicePixelRatio || 1;
  els.stage.style.width = W * k + "px"; els.stage.style.height = H * k + "px";
  els.stage.width = Math.round(W * k * dpr); els.stage.height = Math.round(H * k * dpr);
  els.exact.style.width = els.stage.style.width; els.exact.style.height = els.stage.style.height;
  draw();
}

// ------------------------------------------------------------------ playback

function plateTime(t) { const d = els.plate.duration || 1; return ((+(st.spec.background.offset || 0) + t) % d + d) % d; }
function syncMedia(force) {
  const [src] = sourceTime(st.t);
  const tol = force ? 0.001 : 0.07;
  if (st.geo && st.geo.clip && Math.abs(els.screen.currentTime - src) > tol) els.screen.currentTime = src;
  if (st.geo && st.geo.audio && Math.abs(els.audio.currentTime - src) > (force ? 0.001 : 0.09)) els.audio.currentTime = src;
  if (st.plate && force) els.plate.currentTime = plateTime(st.t);
}
function seek(t) { st.t = clamp(t, 0, st.spec.duration); hideExact(); syncMedia(true); draw(); }
function play() {
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
  if (st.t >= st.spec.duration) { st.t = st.spec.duration; pause(); return; }
  syncMedia(false);
  draw(); followPlayhead(); requestAnimationFrame(tick);
}

// ------------------------------------------------------------------ exact frame

function hideExact() { els.exact.classList.add("hidden"); }
async function showExact() {
  if (!st.geo.clip) return note("record a take first");
  pause(); $("#exactBtn").disabled = true;
  try {
    await new Promise((res, rej) => { els.exact.onload = res; els.exact.onerror = rej; els.exact.src = `/api/still/${st.name}?t=${st.t.toFixed(3)}&scale=0.5&_=${Date.now()}`; });
    els.exact.classList.remove("hidden");
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
  const now = performance.now();
  const join = coalesce && st.coalesce && st.coalesce.key === coalesce && now - st.coalesce.at < 900;
  if (!join) { st.undo.push(snapshot()); if (st.undo.length > 200) st.undo.shift(); st.redo = []; }
  st.coalesce = coalesce ? { key: coalesce, at: now } : null;
  fn();
  changed();
}
function undo() {
  if (!st.undo.length) return;
  st.redo.push(snapshot()); st.raw = JSON.parse(st.undo.pop()); st.coalesce = null; st.sel = null;
  changed(); buildPanes();
}
function redo() {
  if (!st.redo.length) return;
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
// Set a property at the playhead: a key there when it is animated, else its static value.
function setProp(name, v, coalesce) {
  const p = st.meta.props[name];
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
      if (st.sel && st.sel.type === "key" && st.sel.prop === name) st.sel = null;
    } else {
      const v = r3(valueAt(name, st.t));
      trackOf(name).push({ t: r3(st.t), v }); trackOf(name).sort((a, b) => a.t - b.t);
      st.sel = { type: "key", prop: name, t: r3(st.t) };
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
function selKey() {
  if (!st.sel || st.sel.type !== "key") return null;
  const keys = (st.raw.tracks || {})[st.sel.prop] || [];
  const i = keys.findIndex((k) => Math.abs(k.t - st.sel.t) < 1e-6);
  return i >= 0 ? { keys, i, k: keys[i] } : null;
}
function preset(kind) {
  const t = st.t, d = 0.9, cam = ["zoom", "pan_x", "pan_y"];
  mutate(() => {
    keyAll(cam, t);
    if (kind === "push" || kind === "punch") {
      const z = fitZoom(t), [px, py] = screenPointWorld(0.5, 0.32, t);
      const at = kind === "push" ? t + d : t + frameT();
      keyAll(cam, at, { zoom: z, pan_x: px, pan_y: py }, kind === "push" ? "inOutCubic" : "hold");
    } else {
      const [px, py] = screenPointWorld(0.5, 0.5, t);
      keyAll(cam, t + d, { zoom: 1, pan_x: px, pan_y: py }, "inOutCubic");
    }
  });
  buildPanes();
}
function deleteSelection() {
  if (!st.sel) return;
  if (st.sel.type === "key") {
    const sk = selKey(); if (!sk) return;
    mutate(() => {
      sk.keys.splice(sk.i, 1);
      if (!sk.keys.length) { delete st.raw.tracks[st.sel.prop]; (st.raw.props = st.raw.props || {})[st.sel.prop] = sk.k.v; }
      st.sel = null;
    });
  } else if (st.sel.type === "piece") {
    if (st.raw.edit.length <= 1) return note("the video needs at least one piece");
    mutate(() => { st.raw.edit.splice(st.sel.i, 1); st.sel = null; });
  }
  buildPanes();
}
function jumpKey(dir) {
  const names = st.sel && st.sel.type === "key" ? [st.sel.prop] : Object.keys(st.meta.props);
  const ts = [...new Set(names.flatMap((n) => (st.spec.tracks[n] || []).map((k) => k.t)))].sort((a, b) => a - b);
  const next = dir > 0 ? ts.find((x) => x > st.t + 1e-3) : [...ts].reverse().find((x) => x < st.t - 1e-3);
  if (next != null) { seek(next); followPlayhead(true); }
}

// ------------------------------------------------------------------ edit (the recording)

function splitAt(t = st.t) {
  const [src, i] = sourceTime(t);
  const p = st.raw.edit[i];
  if (!p || src - p.in < 0.05 || p.out - src < 0.05) return note("move the playhead inside a piece to split it");
  mutate(() => { st.raw.edit.splice(i, 1, { in: p.in, out: r3(src) }, { in: r3(src), out: p.out }); st.sel = { type: "piece", i: i + 1 }; });
  buildPanes();
}
function resetEdit() {
  const len = st.tl && st.tl.take ? st.tl.take : st.spec.duration;
  mutate(() => { st.raw.edit = [{ in: 0, out: r3(len) }]; st.sel = null; });
  buildPanes();
}

// ------------------------------------------------------------------ timeline

const tx = (t) => t * st.pps;
function laneWidth() { return Math.max(200, tx(st.spec.duration) + 40); }
function row(cls, labelHTML) {
  const r = el("div", "tl-row " + (cls || ""));
  const lab = el("div", "tl-label", labelHTML);
  const lane = el("div", "tl-lane"); lane.style.width = laneWidth() + "px";
  r.append(lab, lane); els.inner.appendChild(r);
  return { r, lab, lane };
}
function snapT(t, exclude) {
  const cands = [st.t, 0, st.spec.duration];
  for (const n in st.spec.tracks) for (const k of st.spec.tracks[n]) if (k !== exclude) cands.push(k.t);
  for (let i = 1; i < st.spec.edit.length; i++) cands.push(pieceStart(i));
  if (st.tl) for (const v of st.tl.verses) for (const [a] of videoTimes(v.t0, v.t1)) cands.push(a);
  let best = t, bd = 6 / st.pps;
  for (const c of cands) if (Math.abs(c - t) < bd) { bd = Math.abs(c - t); best = c; }
  return best;
}
function buildTimeline() {
  if (!st.spec) return;
  const sl = els.scroll.scrollLeft, stp = els.scroll.scrollTop;
  els.inner.innerHTML = "";
  els.inner.style.width = LABEL_W + laneWidth() + "px";
  const dur = st.spec.duration;
  // ruler
  const ru = row("tl-ruler", '<span class="nm">time</span>');
  const step = st.pps >= 120 ? 0.5 : st.pps >= 50 ? 1 : st.pps >= 20 ? 2 : 5;
  for (let t = 0; t <= dur + 1e-6; t += step) {
    const major = Math.abs(t / (step * 2) - Math.round(t / (step * 2))) < 1e-6 || step >= 2;
    const d = el("div", "tick" + (major ? "" : " minor"), major ? (Number.isInteger(t) ? t : t.toFixed(1)) + "s" : "");
    d.style.left = tx(t) + "px"; ru.lane.appendChild(d);
  }
  ru.lane.onmousedown = scrubFrom;
  // recording pieces
  const rec = row("tall", '<span class="nm">Recording</span>');
  const takeLen = st.tl && st.tl.take;
  st.spec.edit.forEach((p, i) => {
    const a = pieceStart(i), b = a + (p.out - p.in);
    const d = el("div", "piece" + (st.sel && st.sel.type === "piece" && st.sel.i === i ? " on" : "") + (takeLen && p.out > takeLen + 0.05 ? " over" : ""),
      `${p.in.toFixed(2)}–${p.out.toFixed(2)}s<i class="h l"></i><i class="h r"></i>`);
    d.style.left = tx(a) + "px"; d.style.width = Math.max(4, tx(b - a)) + "px";
    d.title = `take ${p.in.toFixed(2)}–${p.out.toFixed(2)}s · drag the edges to trim · S splits at the playhead` + (takeLen && p.out > takeLen ? " · past the end of the take: re-record longer" : "");
    d.onmousedown = (ev) => dragPiece(ev, i);
    rec.lane.appendChild(d);
    if (i > 0) { const c = el("div", "cut"); c.style.left = tx(a) + "px"; rec.lane.appendChild(c); }
  });
  rec.lane.addEventListener("mousedown", (e) => { if (e.target === rec.lane) scrubFrom(e); });
  // reference tracks
  const vr = row("", '<span class="nm">Verses</span>');
  const rp = row("", '<span class="nm">Repeats</span>');
  if (st.tl) {
    for (const v of st.tl.verses) for (const [a, b] of videoTimes(v.t0, v.t1)) {
      const d = el("div", "seg verse" + (v.label === "basmalah" ? " basm" : ""), v.label);
      d.style.left = tx(a) + "px"; d.style.width = Math.max(2, tx(b - a)) + "px"; d.title = `${v.label} · click to jump`;
      d.onmousedown = (e) => { e.stopPropagation(); seek(a); }; vr.lane.appendChild(d);
    }
    const spans = [];
    for (const r of st.tl.repeats) { const l = spans[spans.length - 1]; if (l && l.verse === r.verse && r.t0 - l.t1 < 0.15) l.t1 = r.t1; else spans.push({ ...r }); }
    for (const r of spans) for (const [a, b] of videoTimes(r.t0, r.t1)) {
      const d = el("div", "seg rep", "repeat"); d.style.left = tx(a) + "px"; d.style.width = Math.max(2, tx(b - a)) + "px";
      d.title = `${r.verse} repeat · click to jump`; d.onmousedown = (e) => { e.stopPropagation(); seek(Math.max(0, a - 0.5)); }; rp.lane.appendChild(d);
    }
  }
  for (const r of [vr, rp]) r.lane.addEventListener("mousedown", (e) => { if (e.target === r.lane) scrubFrom(e); });
  // property lanes, by group
  const groups = {};
  for (const [n, p] of Object.entries(st.meta.props)) (groups[p.group] = groups[p.group] || []).push(n);
  for (const [gname, names] of Object.entries(groups)) {
    const nAnim = names.filter(animated).length;
    const gr = row("group", `<span class="nm">${st.collapsed[gname] ? "▸" : "▾"} ${gname}</span><span class="vv">${nAnim ? nAnim + " animated" : ""}</span>`);
    gr.lab.onclick = () => { st.collapsed[gname] = !st.collapsed[gname]; buildTimeline(); };
    gr.lane.addEventListener("mousedown", scrubFrom);
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
      lr.lane.addEventListener("mousedown", (e) => { if (e.target === lr.lane || e.target.closest(".curve")) scrubFrom(e); });
      lr.lane.addEventListener("dblclick", (e) => {
        const t = snapT((e.clientX - lr.lane.getBoundingClientRect().left) / st.pps);
        mutate(() => { keyAll([n], t); st.sel = { type: "key", prop: n, t: r3(t) }; }); seek(t); buildPanes();
      });
    }
  }
  const ph = el("div"); ph.id = "playhead"; els.inner.appendChild(ph);
  els.scroll.scrollLeft = sl; els.scroll.scrollTop = stp;
  placePlayhead(); refreshValues();
}
function drawCurve(lane, n) {
  const p = st.meta.props[n], w = tx(st.spec.duration), h = 24;
  const keys = st.spec.tracks[n];
  const vs = keys.map((k) => +k.v), lo = Math.min(...vs), hi = Math.max(...vs);
  if (hi - lo < 1e-9) return;
  let d = "";
  for (let x = 0; x <= w; x += 3) {
    const v = valueAt(n, x / st.pps), y = h - 4 - ((v - lo) / (hi - lo)) * (h - 8);
    d += (x ? "L" : "M") + x.toFixed(1) + " " + y.toFixed(1);
  }
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "curve"); svg.setAttribute("width", w); svg.setAttribute("height", h);
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path"); path.setAttribute("d", d);
  svg.appendChild(path); lane.appendChild(svg);
  void p;
}
function addDiamond(lane, n, k) {
  const on = st.sel && st.sel.type === "key" && st.sel.prop === n && Math.abs(st.sel.t - k.t) < 1e-6;
  const d = el("div", "key" + (on ? " on" : "") + (k.ease === "hold" ? " hold" : ""));
  d.style.left = tx(k.t) + "px";
  d.title = `${st.meta.props[n].label} = ${(+k.v).toFixed(3)} at ${(+k.t).toFixed(2)}s · ${k.ease || "inOutCubic"}`;
  d.onmousedown = (ev) => dragKey(ev, n, k);
  lane.appendChild(d);
}
function placePlayhead() {
  const ph = $("#playhead"); if (!ph || !st.spec) return;
  ph.style.left = LABEL_W + tx(st.t) + "px";
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
function scrubFrom(ev) {
  if (ev.button !== 0) return;
  ev.preventDefault();
  const lane = $(".tl-lane", els.inner);
  const at = (e) => seek((e.clientX - lane.getBoundingClientRect().left) / st.pps);
  at(ev);
  const up = () => { removeEventListener("mousemove", at); removeEventListener("mouseup", up); };
  addEventListener("mousemove", at); addEventListener("mouseup", up);
}
function dragKey(ev, n, k) {
  ev.preventDefault(); ev.stopPropagation();
  st.sel = { type: "key", prop: n, t: k.t };
  const keys = trackOf(n), i0 = keys.findIndex((x) => Math.abs(x.t - k.t) < 1e-6);
  if (i0 < 0) return;
  const key = keys[i0], t0 = key.t, x0 = ev.clientX;
  let moved = false;
  const move = (e) => {
    const dt = (e.clientX - x0) / st.pps;
    if (!moved && Math.abs(e.clientX - x0) < 3) return;
    if (!moved) { st.undo.push(snapshot()); st.redo = []; moved = true; }
    let t = clamp(t0 + dt, 0, st.spec.duration);
    if (!e.altKey) t = snapT(t, key);
    key.t = r3(t); st.sel.t = key.t;
    keys.sort((a, b) => a.t - b.t);
    st.spec = resolve(st.raw); st.t = key.t; syncMedia(true); buildTimeline(); draw();
  };
  const up = () => {
    removeEventListener("mousemove", move); removeEventListener("mouseup", up);
    if (moved) { st.coalesce = null; changed(); } else { seek(key.t); buildTimeline(); }
    buildPanes();
  };
  addEventListener("mousemove", move); addEventListener("mouseup", up);
}
function dragPiece(ev, i) {
  ev.preventDefault(); ev.stopPropagation();
  st.sel = { type: "piece", i };
  const p = st.raw.edit[i], mode = ev.target.classList.contains("l") ? "l" : ev.target.classList.contains("r") ? "r" : null;
  if (!mode) { buildTimeline(); buildPanes(); showTab("recording"); return; }
  const x0 = ev.clientX, in0 = p.in, out0 = p.out, max = st.tl && st.tl.take ? st.tl.take : 1e9;
  let moved = false;
  const move = (e) => {
    const dt = (e.clientX - x0) / st.pps;
    if (!moved && Math.abs(e.clientX - x0) < 2) return;
    if (!moved) { st.undo.push(snapshot()); st.redo = []; moved = true; }
    if (mode === "l") p.in = r3(clamp(in0 + dt, 0, p.out - 0.1));
    else p.out = r3(clamp(out0 + dt, p.in + 0.1, max));
    st.spec = resolve(st.raw);
    // show the frame at the edge being trimmed
    st.t = clamp(pieceStart(i) + (mode === "l" ? 0 : p.out - p.in - 1e-3), 0, st.spec.duration);
    syncMedia(true); buildTimeline(); draw();
  };
  const up = () => { removeEventListener("mousemove", move); removeEventListener("mouseup", up); if (moved) changed(); buildPanes(); };
  addEventListener("mousemove", move); addEventListener("mouseup", up);
}

// ------------------------------------------------------------------ stage gestures (auto-key)

function stageDrag(ev) {
  if (ev.button !== 0) return;
  ev.preventDefault(); hideExact();
  els.stage.classList.add("drag");
  const rect = els.stage.getBoundingClientRect(), outPerPx = st.geo.W / rect.width;
  const S0 = stateAt(st.t), x0 = ev.clientX, y0 = ev.clientY, phone = ev.altKey;
  const tag = (phone ? "drag-phone:" : "drag-cam:") + performance.now();
  const move = (e) => {
    const dx = (e.clientX - x0) * outPerPx, dy = (e.clientY - y0) * outPerPx;
    if (phone) {
      setProp("phone_x", S0.phone_x + dx / (S0.zoom * st.geo.W), tag);
      setProp("phone_y", S0.phone_y + dy / (S0.zoom * st.geo.H), tag);
    } else {
      // dragging the picture right moves the camera left
      setProp("pan_x", S0.pan_x - dx / (S0.zoom * st.geo.W), tag);
      setProp("pan_y", S0.pan_y - dy / (S0.zoom * st.geo.H), tag);
    }
  };
  const up = () => { els.stage.classList.remove("drag"); removeEventListener("mousemove", move); removeEventListener("mouseup", up); buildTimeline(); };
  addEventListener("mousemove", move); addEventListener("mouseup", up);
}
function stageWheel(ev) {
  ev.preventDefault(); hideExact();
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
  const set = (v) => mutate(() => { if (opts.set) opts.set(v); else setPath(st.raw, path, v); }, opts.coalesce ? "f:" + path : null);
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
    rng.onchange = () => buildTimeline();
    box.append(rng, num); r.append(kb, el("span", null, d.label), box); p.append(r);
  }
  p.append(noteEl("◆ adds or removes a key at the playhead. Once a property has a key, changing it (here or on the picture) keys it at the playhead; with none, it is one value for the whole video."));
  const sk = selKey();
  if (sk) {
    const card = el("div", "card");
    const d = m.props[st.sel.prop];
    card.append(el("h3", null, `Key · ${d.label}`),
      field("Time (s)", "", "number", { step: 0.01, get: () => sk.k.t, set: (v) => { sk.k.t = r3(clamp(v, 0, st.spec.duration)); st.sel.t = sk.k.t; sk.keys.sort((a, b) => a.t - b.t); }, after: () => seek(sk.k.t) }),
      field("Value", "", "number", { step: d.step, get: () => sk.k.v, set: (v) => { sk.k.v = r3(clamp(v, d.min, d.max)); } }),
      field("Ease into it", "", "select", { options: m.eases.map((e) => [e, e === "hold" ? "hold (cut)" : e]), get: () => sk.k.ease || "inOutCubic", set: (v) => { if (v === "inOutCubic") delete sk.k.ease; else sk.k.ease = v; } }));
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
    a.onchange = () => mutate(() => { pc.in = r3(clamp(+a.value, 0, pc.out - 0.05)); });
    b.onchange = () => mutate(() => { pc.out = r3(Math.max(pc.in + 0.05, +b.value)); });
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
  buildTimeline(); draw(); histButtons();
  $("#saveState").textContent = "editing…";
  clearTimeout(st.saveTimer); st.saveTimer = setTimeout(save, 450);
  if (plateLook(st.spec) !== st.lastLook) { clearTimeout(st.plateTimer); st.plateTimer = setTimeout(loadPlate, 400); }
}
async function save() {
  try {
    const r = await api(`/api/spec/${st.name}`, { method: "PUT", body: JSON.stringify(st.raw) });
    $("#saveState").textContent = "saved";
    const i = st.specs.findIndex((x) => x.name === st.name);
    if (i >= 0) { st.specs[i] = r.status; renderList(); }
    if (takeKey(st.spec) !== st.lastTakeKey || st.geo.frame.indexOf(st.spec.phone.color) < 0 || `${st.geo.W}x${st.geo.H}` !== st.meta.aspects[st.spec.aspect].join("x")) await loadGeometry();
  } catch (e) { $("#saveState").textContent = "not saved: " + e.message; }
}
async function loadGeometry() {
  st.geo = await api(`/api/geometry/${st.name}`);
  st.lastTakeKey = takeKey(st.spec);
  els.frame.src = st.geo.frame;
  if (st.geo.clip) { if (!els.screen.src.endsWith(st.geo.clip)) { els.screen.src = st.geo.clip; els.audio.src = st.geo.audio; } }
  else { els.screen.removeAttribute("src"); els.audio.removeAttribute("src"); els.screen.load(); }
  st.tl = await api(`/api/timeline/${st.name}`).catch(() => null);
  sizeStage(); buildTimeline(); buildPanes(); updateActions(); seek(st.t);
}
async function loadPlate() {
  st.lastLook = plateLook(st.spec);
  note("preparing background…", 1500);
  try {
    st.plate = await api(`/api/plate/${st.name}`, { method: "POST", body: JSON.stringify(st.raw) });
    els.plate.src = st.plate.url;
    els.plate.onloadeddata = () => { els.plate.currentTime = plateTime(st.t); draw(); };
  } catch (e) { note("background: " + e.message); }
}
async function open(name) {
  pause();
  st.name = name; st.sel = null; st.t = 0; st.undo = []; st.redo = []; st.coalesce = null;
  const r = await api(`/api/spec/${name}`);
  st.raw = r.raw; st.spec = resolve(st.raw);
  localStorage.setItem("promo.last", name);
  renderList(); histButtons();
  await loadGeometry();
  fitTimeline();
  await loadPlate();
  $("#saveState").textContent = "saved";
}
function fitTimeline() {
  const w = els.scroll.clientWidth - LABEL_W - 50;
  st.pps = clamp(w / Math.max(1, st.spec.duration), 10, 400); $("#pps").value = st.pps; buildTimeline();
}
function renderList() {
  const ul = $("#specList"); ul.innerHTML = "";
  for (const s of st.specs) {
    const li = el("li", s.name === st.name ? "on" : "");
    if (s.error) li.innerHTML = `<div class="t">${s.name}</div><div class="s">⚠ ${s.error}</div>`;
    else {
      const rec = st.meta.reciters[s.app.reciter].split(" ").pop();
      li.innerHTML = `<div class="t">${s.title || s.name}</div><div class="s"><span>${rec}</span><span>${s.app.surah}:${s.app.ayah}</span><span>${s.app.theme}</span><span>${s.aspect}</span><span>${(s.duration || 0).toFixed(1)}s</span></div>
        <div class="s"><span><i class="dot ${s.take ? (s.take_short ? "short" : "ok") : ""}"></i>${s.take_short ? "take too short" : "take"}</span><span><i class="dot ${s.video ? "vid" : ""}"></i>video</span></div>`;
    }
    li.onclick = () => open(s.name);
    ul.appendChild(li);
  }
}
async function refreshList() { st.specs = await api("/api/specs"); renderList(); }
function updateActions() {
  const have = !!(st.geo && st.geo.clip);
  $("#recordBtn").classList.toggle("hidden", have);
  $("#rerecordBtn").classList.toggle("hidden", !have);
  $("#exportBtn").disabled = !have;
  const s = st.specs.find((x) => x.name === st.name), a = $("#openVideo");
  if (s && s.video) { a.href = `/media/out/${st.name}.mp4?${s.video_mtime}`; a.classList.remove("hidden"); } else a.classList.add("hidden");
}

// ------------------------------------------------------------------ jobs & emulator

async function startJob(path, body = {}) {
  try { await api(path, { method: "POST", body: JSON.stringify(body) }); pollJobs(); } catch (e) { note(e.message, 4000); }
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

// ------------------------------------------------------------------ wiring

async function init() {
  st.meta = await api("/api/meta");
  await refreshList();
  const last = localStorage.getItem("promo.last");
  const first = st.specs.find((s) => s.name === last) || st.specs[0];
  if (first) await open(first.name);
  pollJobs(); pollEmu();
}
$("#play").onclick = play;
$("#undo").onclick = undo;
$("#redo").onclick = redo;
$("#keyCam").onclick = () => { mutate(() => keyAll(["zoom", "pan_x", "pan_y"])); buildPanes(); };
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
els.scroll.addEventListener("wheel", (e) => {
  if (!e.ctrlKey) return;               // ctrl/pinch-wheel zooms the timeline around the cursor
  e.preventDefault();
  const rect = els.scroll.getBoundingClientRect(), x = e.clientX - rect.left - LABEL_W + els.scroll.scrollLeft;
  const t = x / st.pps;
  st.pps = clamp(st.pps * Math.exp(-e.deltaY * 0.002), 10, 400); $("#pps").value = st.pps;
  buildTimeline(); els.scroll.scrollLeft = Math.max(0, t * st.pps - (e.clientX - rect.left - LABEL_W));
}, { passive: false });
els.stage.addEventListener("mousedown", stageDrag);
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
  try { await api(`/api/copy/${st.name}/${n}`, { method: "POST" }); await refreshList(); await open(n); } catch (e) { note(e.message); }
};
$("#delSpec").onclick = async () => {
  if (!st.name || !confirm(`Delete the spec "${st.name}"? (Takes and exported videos stay on disk.)`)) return;
  await api(`/api/spec/${st.name}`, { method: "DELETE" }); await refreshList(); if (st.specs[0]) open(st.specs[0].name);
};
addEventListener("resize", sizeStage);
addEventListener("keydown", (e) => {
  const mod = e.ctrlKey || e.metaKey;
  if (mod && (e.key === "z" || e.key === "Z")) { e.preventDefault(); return e.shiftKey ? redo() : undo(); }
  if (mod && (e.key === "y" || e.key === "Y")) { e.preventDefault(); return redo(); }
  if (e.target.closest("input, textarea, select")) return;
  if (e.code === "Space") { e.preventDefault(); play(); }
  else if (e.key === "k" || e.key === "K") { mutate(() => keyAll(["zoom", "pan_x", "pan_y"])); buildPanes(); }
  else if (e.key === "s" || e.key === "S") splitAt();
  else if (e.key === "Delete" || e.key === "Backspace") deleteSelection();
  else if (e.key === "[") jumpKey(-1);
  else if (e.key === "]") jumpKey(1);
  else if (e.key === "Home") seek(0);
  else if (e.key === "End") seek(st.spec.duration);
  else if (e.key === "ArrowLeft") { seek(st.t - (e.shiftKey ? 1 : frameT())); followPlayhead(); }
  else if (e.key === "ArrowRight") { seek(st.t + (e.shiftKey ? 1 : frameT())); followPlayhead(); }
});
els.screen.addEventListener("seeked", draw);
els.plate.addEventListener("seeked", draw);
els.frame.addEventListener("load", draw);
init();
