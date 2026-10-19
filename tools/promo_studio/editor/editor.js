// Promo Studio editor. The preview draws the same scene as compose.py from
// the same geometry and camera math; "Exact frame" shows the compositor's own
// frame (with motion blur) when it matters.
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

const st = {
  meta: null, specs: [], name: null, raw: null, spec: null, geo: null, plate: null, tl: null,
  t: 0, playing: false, selKey: -1, selCap: -1, saveTimer: null, plateTimer: null, exactTimer: null,
  lastTakeKey: "", lastLook: "", playStartWall: 0, playStartT: 0,
};
const els = {
  stage: $("#stage"), ctx: $("#stage").getContext("2d"), screen: $("#screenVid"), plate: $("#plateVid"),
  audio: $("#audio"), frame: $("#frameImg"), icon: $("#iconImg"), exact: $("#exact"),
};

// ------------------------------------------------------------------ spec model

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
  s.camera = [...(s.camera || [])].sort((a, b) => a.t - b.t);
  if (!s.camera.length) s.camera = [{ t: 0, zoom: 1, x: 0.5, y: 0.5 }];
  return s;
}
const takeKey = (s) => [s.app.reciter, s.app.surah, s.app.ayah, s.app.theme, s.app.layout, s.app.view, s.app.text_size].join("|");
const lookKey = (s) => JSON.stringify([s.background, s.aspect]);
function getPath(o, path) { return path.split(".").reduce((a, k) => (a == null ? a : a[k]), o); }
function setPath(o, path, v) {
  const ks = path.split("."); let cur = o;
  for (const k of ks.slice(0, -1)) { if (cur[k] == null || typeof cur[k] !== "object") cur[k] = {}; cur = cur[k]; }
  cur[ks[ks.length - 1]] = v;
}

// ------------------------------------------------------------------ camera math (mirrors compose.py)

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
const kv = (k) => [+(k.zoom ?? 1), +(k.x ?? 0.5), +(k.y ?? 0.5), +(k.rot ?? 0)];
function cameraAt(keys, t) {
  if (t <= keys[0].t) return kv(keys[0]);
  for (let i = 0; i < keys.length - 1; i++) {
    const a = keys[i], b = keys[i + 1];
    if (a.t <= t && t <= b.t) {
      const e = ease(b.ease || "inOutCubic", (t - a.t) / Math.max(1e-6, b.t - a.t));
      const [za, xa, ya, ra] = kv(a), [zb, xb, yb, rb] = kv(b);
      return [Math.exp(Math.log(za) + (Math.log(zb) - Math.log(za)) * e), xa + (xb - xa) * e, ya + (yb - ya) * e, ra + (rb - ra) * e];
    }
  }
  return kv(keys[keys.length - 1]);
}
function placement() {
  const g = st.geo.g, s = st.spec, W = st.geo.W, H = st.geo.H;
  const s0 = (s.phone.height * H) / g.bh;
  const P0 = [W / 2 + s.phone.x * W, H / 2 + s.phone.y * H];
  const C0 = [g.W / 2, g.H / 2];
  return { g, s0, P0, C0, W, H };
}
function focusWorld(fx, fy) {
  const { g, s0, P0, C0 } = placement();
  return [P0[0] + s0 * (g.sx0 + fx * g.sw - C0[0]), P0[1] + s0 * (g.sy0 + fy * g.sh - C0[1])];
}
function phoneMatrix(cam) {   // canvas px -> output px, as [a b c d e f] for ctx.transform
  const [z, fx, fy, rot] = cam;
  const { s0, P0, C0 } = placement();
  const F = focusWorld(fx, fy);
  const c = Math.cos(rot * Math.PI / 180), sn = Math.sin(rot * Math.PI / 180);
  const k = z * s0;
  const vx = z * (P0[0] - s0 * C0[0] - F[0]), vy = z * (P0[1] - s0 * C0[1] - F[1]);
  return [c * k, sn * k, -sn * k, c * k, c * vx - sn * vy + P0[0], sn * vx + c * vy + P0[1]];
}
function bgMatrix(cam) {
  const [z, fx, fy, rot] = cam;
  const p = +st.spec.background.parallax;
  const zb = 1 + (z - 1) * p;
  const { P0 } = placement();
  const F = focusWorld(fx, fy);
  const sh = [(F[0] - P0[0]) * p, (F[1] - P0[1]) * p];
  const r = rot * p * Math.PI / 180, c = Math.cos(r), sn = Math.sin(r);
  const pk = st.plate.k, O = [st.geo.W / 2, st.geo.H / 2], Bc = [st.plate.w / 2, st.plate.h / 2];
  const L = zb / pk;
  const vx = zb * (-Bc[0] / pk - sh[0]), vy = zb * (-Bc[1] / pk - sh[1]);
  return [c * L, sn * L, -sn * L, c * L, c * vx - sn * vy + O[0], sn * vx + c * vy + O[1]];
}

// ------------------------------------------------------------------ drawing

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}
function wrap(ctx, text, maxW) {
  const words = text.split(/\s+/), lines = []; let cur = "";
  for (const w of words) { const t = (cur + " " + w).trim(); if (ctx.measureText(t).width > maxW && cur) { lines.push(cur); cur = w; } else cur = t; }
  lines.push(cur); return lines;
}
function overlayK(t, t0, t1, fade) {
  if (t < t0 - 1e-6 || t > t1 + fade) return 0;
  let k = fade > 0 ? Math.min(1, (t - t0) / fade) : 1;
  if (t1 < 1e8) k = Math.min(k, fade > 0 ? (t1 + fade - t) / fade : +(t <= t1));
  k = clamp(k, 0, 1); return k * k * (3 - 2 * k);
}

function draw() {
  const ctx = els.ctx;
  if (!st.geo || !st.spec) return;
  const { W, H } = st.geo, s = st.spec, g = st.geo.g;
  const ps = els.stage.width / W;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = st.meta.themes[s.app.theme].bg; ctx.fillRect(0, 0, els.stage.width, els.stage.height);
  const cam = cameraAt(s.camera, st.t);
  // background plate
  if (st.plate && els.plate.readyState >= 2) {
    const m = bgMatrix(cam);
    ctx.setTransform(ps, 0, 0, ps, 0, 0); ctx.transform(...m);
    // a little oversize stands in for the exporter's reflected border
    ctx.drawImage(els.plate, -st.plate.w * 0.05, -st.plate.h * 0.05, st.plate.w * 1.1, st.plate.h * 1.1);
  }
  ctx.setTransform(ps, 0, 0, ps, 0, 0);
  const vg = +s.background.vignette;
  if (vg > 0) {
    const grad = ctx.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, Math.hypot(W, H) / 2);
    grad.addColorStop(0, "rgba(0,0,0,0)"); grad.addColorStop(1, `rgba(0,0,0,${vg})`);
    ctx.fillStyle = grad; ctx.fillRect(0, 0, W, H);
  }
  const m = phoneMatrix(cam);
  // shadow
  const shd = +s.phone.shadow;
  if (shd > 0) {
    ctx.save(); ctx.setTransform(ps, 0, 0, ps, 0, 0);
    ctx.translate(0, H * 0.022 * cam[0]); ctx.transform(...m);
    ctx.filter = `blur(${g.H * 0.035 * Math.hypot(m[0], m[1]) * ps}px)`;
    ctx.globalAlpha = shd; ctx.fillStyle = "#000";
    roundRect(ctx, g.W / 2 - g.bw / 2, g.H / 2 - g.bh / 2, g.bw, g.bh, g.body_r); ctx.fill();
    ctx.restore();
  }
  // screen + frame
  ctx.save(); ctx.setTransform(ps, 0, 0, ps, 0, 0); ctx.transform(...m);
  ctx.save(); roundRect(ctx, g.sx0, g.sy0, g.sw, g.sh, g.screen_r); ctx.clip();
  if (st.geo.clip && els.screen.readyState >= 2) ctx.drawImage(els.screen, g.sx0, g.sy0, g.sw, g.sh);
  else {
    ctx.fillStyle = st.meta.themes[s.app.theme].bg; ctx.fillRect(g.sx0, g.sy0, g.sw, g.sh);
    ctx.fillStyle = "#a49c8c"; ctx.font = `italic ${g.sw * 0.06}px "Cormorant Italic"`; ctx.textAlign = "center";
    ctx.fillText(st.geo.clip ? "loading take…" : "no take yet — Record take", g.sx0 + g.sw / 2, g.sy0 + g.sh / 2);
  }
  ctx.restore();
  if (els.frame.complete && els.frame.naturalWidth) ctx.drawImage(els.frame, 0, 0, g.W, g.H);
  ctx.restore();
  // captions and end card (screen space)
  ctx.setTransform(ps, 0, 0, ps, 0, 0);
  const u = Math.min(W, H) / 1080;
  for (const c of s.captions || []) {
    const k = overlayK(st.t, +c.t0, +c.t1, +(c.fade ?? 0.45));
    if (!k) continue;
    const size = (c.size || 46) * u, top = (c.pos || "top") === "top";
    const y = H * (c.y ?? (top ? (s.aspect === "1:1" ? 0.06 : 0.09) : (s.aspect === "1:1" ? 0.94 : 0.91)));
    ctx.font = `${size}px "Cormorant Italic"`; ctx.textAlign = "center"; ctx.textBaseline = "top";
    const lines = wrap(ctx, c.text || "", W * 0.86), lh = size * 1.18;
    const y0 = y - (lh * lines.length) / 2;
    ctx.globalAlpha = k;
    lines.forEach((ln, i) => {
      ctx.fillStyle = "rgba(0,0,0,0.35)"; ctx.fillText(ln, W / 2, y0 + i * lh + size * 0.04);
      ctx.fillStyle = c.color || "#F6F0E4"; ctx.fillText(ln, W / 2, y0 + i * lh);
    });
    ctx.globalAlpha = 1;
  }
  const e = s.end_card;
  if (e) {
    const k = overlayK(st.t, +e.t, 1e9, +(e.fade ?? 0.8));
    if (k) {
      const th = st.meta.themes[s.app.theme];
      const ink = e.color || (s.app.theme === "Paper" ? "#1C1B18" : "#F3ECDF");
      ctx.globalAlpha = k; ctx.fillStyle = e.background || th.bg; ctx.fillRect(0, 0, W, H);
      const isz = 220 * u;
      ctx.save(); roundRect(ctx, (W - isz) / 2, H * 0.5 - isz * 0.95, isz, isz, isz * 0.23); ctx.clip();
      ctx.drawImage(els.icon, (W - isz) / 2, H * 0.5 - isz * 0.95, isz, isz); ctx.restore();
      ctx.fillStyle = ink; ctx.textAlign = "center"; ctx.textBaseline = "top";
      ctx.font = `${84 * u}px Junicode`; ctx.fillText(e.title || "Beautiful Quran", W / 2, H * 0.5 + 10 * u);
      ctx.globalAlpha = k * 0.78; ctx.font = `${40 * u}px "Cormorant Italic"`;
      ctx.fillText(e.subtitle || "Free · Android & Web", W / 2, H * 0.5 + 120 * u);
      ctx.globalAlpha = 1;
    }
  }
  $("#time").textContent = `${st.t.toFixed(2)} / ${(+s.duration).toFixed(2)}`;
  const lane = $(".track[data-track=camera] .lane");
  $("#playhead").style.left = lane.offsetLeft + (st.t / s.duration) * lane.clientWidth - 81 + "px";
}

function sizeStage() {
  if (!st.geo) return;
  const wrapEl = $("#stageWrap"), { W, H } = st.geo;
  const k = Math.min((wrapEl.clientWidth - 24) / W, (wrapEl.clientHeight - 40) / H);
  const dpr = window.devicePixelRatio || 1;
  els.stage.style.width = W * k + "px"; els.stage.style.height = H * k + "px";
  els.stage.width = Math.round(W * k * dpr); els.stage.height = Math.round(H * k * dpr);
  els.exact.style.width = els.stage.style.width; els.exact.style.height = els.stage.style.height;
  draw();
}

// ------------------------------------------------------------------ playback

function mediaTime(t) { return +st.spec.app.start + t; }
function plateTime(t) {
  const d = els.plate.duration || 1;
  return ((+(st.spec.background.offset || 0) + t) % d + d) % d;
}
function seek(t) {
  st.t = clamp(t, 0, +st.spec.duration);
  hideExact();
  if (st.geo && st.geo.clip) els.screen.currentTime = mediaTime(st.t);
  if (st.plate) els.plate.currentTime = plateTime(st.t);
  if (st.geo && st.geo.audio) els.audio.currentTime = mediaTime(st.t);
  draw();
}
function play() {
  if (st.playing) return pause();
  if (st.t >= st.spec.duration - 0.05) seek(0);
  st.playing = true; $("#play").textContent = "❚❚"; hideExact();
  st.playStartWall = performance.now(); st.playStartT = st.t;
  const ps = [];
  if (st.geo.clip) { els.screen.currentTime = mediaTime(st.t); ps.push(els.screen.play().catch(() => {})); }
  if (st.plate) { els.plate.currentTime = plateTime(st.t); ps.push(els.plate.play().catch(() => {})); }
  if (st.geo.audio) { els.audio.currentTime = mediaTime(st.t); ps.push(els.audio.play().catch(() => {})); }
  requestAnimationFrame(tick);
}
function pause() {
  st.playing = false; $("#play").textContent = "▶";
  els.screen.pause(); els.plate.pause(); els.audio.pause(); draw();
}
function tick() {
  if (!st.playing) return;
  // the audio is the clock when there is one (it is the take's own sound)
  if (st.geo.audio && !els.audio.paused) st.t = els.audio.currentTime - +st.spec.app.start;
  else st.t = st.playStartT + (performance.now() - st.playStartWall) / 1000;
  if (st.geo.clip && Math.abs(els.screen.currentTime - mediaTime(st.t)) > 0.08) els.screen.currentTime = mediaTime(st.t);
  if (st.t >= st.spec.duration) { st.t = +st.spec.duration; pause(); return; }
  draw(); requestAnimationFrame(tick);
}

// ------------------------------------------------------------------ exact frame

function hideExact() { els.exact.classList.add("hidden"); }
async function showExact() {
  if (!st.geo.clip) return note("record a take first");
  pause();
  $("#exactBtn").disabled = true;
  try {
    const url = `/api/still/${st.name}?t=${st.t.toFixed(3)}&scale=0.5&_=${Date.now()}`;
    await new Promise((res, rej) => { els.exact.onload = res; els.exact.onerror = rej; els.exact.src = url; });
    els.exact.classList.remove("hidden");
  } catch { note("exact frame failed (see the terminal)"); }
  $("#exactBtn").disabled = false;
}
function note(msg, ms = 2600) {
  const n = $("#stageNote"); n.textContent = msg; n.classList.remove("hidden");
  clearTimeout(note.t); note.t = setTimeout(() => n.classList.add("hidden"), ms);
}

// ------------------------------------------------------------------ timeline

const tToX = (t, lane) => (t / st.spec.duration) * lane.clientWidth;
const xToT = (x, lane) => clamp((x / lane.clientWidth) * st.spec.duration, 0, +st.spec.duration);

function renderTimeline() {
  if (!st.spec) return;
  const dur = +st.spec.duration;
  const ruler = $("#ruler"); ruler.innerHTML = "";
  const step = dur > 40 ? 5 : dur > 15 ? 2 : 1;
  for (let t = 0; t <= dur + 1e-6; t += step) {
    const d = document.createElement("div"); d.className = "tick"; d.style.left = (t / dur) * 100 + "%"; d.textContent = t + "s"; ruler.appendChild(d);
  }
  const lanes = Object.fromEntries($$(".track").map((tr) => [tr.dataset.track, $(".lane", tr)]));
  Object.values(lanes).forEach((l) => (l.innerHTML = ""));
  const segPct = (a, b) => [`${(clamp(a, 0, dur) / dur) * 100}%`, `${((clamp(b, 0, dur) - clamp(a, 0, dur)) / dur) * 100}%`];
  if (st.tl) {
    for (const v of st.tl.verses) {
      if (v.t1 < 0 || v.t0 > dur) continue;
      const d = document.createElement("div"); d.className = "seg verse" + (v.label === "basmalah" ? " basm" : "");
      [d.style.left, d.style.width] = segPct(v.t0, v.t1); d.textContent = v.label; d.title = `${v.label}: ${v.t0.toFixed(2)}–${v.t1.toFixed(2)}s`;
      d.onclick = () => seek(Math.max(0, v.t0)); lanes.verses.appendChild(d);
    }
    // merge adjacent repeat words into one span per pass
    const spans = [];
    for (const r of st.tl.repeats) {
      const last = spans[spans.length - 1];
      if (last && last.verse === r.verse && r.t0 - last.t1 < 0.15) { last.t1 = r.t1; last.n++; } else spans.push({ ...r, n: 1 });
    }
    for (const r of spans) {
      if (r.t1 < 0 || r.t0 > dur) continue;
      const d = document.createElement("div"); d.className = "seg rep";
      [d.style.left, d.style.width] = segPct(r.t0, r.t1); d.textContent = "repeat"; d.title = `${r.verse} repeat ${r.t0.toFixed(2)}–${r.t1.toFixed(2)}s · click to jump`;
      d.onclick = () => seek(Math.max(0, r.t0 - 0.5)); lanes.repeats.appendChild(d);
    }
  }
  // camera keys
  const keys = st.raw.camera || [];
  const sorted = keys.map((k, i) => [k, i]).sort((a, b) => a[0].t - b[0].t);
  for (let j = 0; j < sorted.length - 1; j++) {
    const a = sorted[j][0], b = sorted[j + 1][0];
    if (Math.abs((a.zoom ?? 1) - (b.zoom ?? 1)) > 1e-3 || a.x !== b.x || a.y !== b.y || (a.rot || 0) !== (b.rot || 0)) {
      const d = document.createElement("div"); d.className = "camline";
      [d.style.left, d.style.width] = segPct(a.t, b.t); lanes.camera.appendChild(d);
    }
  }
  for (const [k, i] of sorted) {
    const d = document.createElement("div");
    d.className = "key" + (i === st.selKey ? " on" : "") + (k.ease === "hold" ? " cut" : "");
    d.style.left = (clamp(k.t, 0, dur) / dur) * 100 + "%";
    d.title = `t ${(+k.t).toFixed(2)}s · zoom ${(+(k.zoom ?? 1)).toFixed(2)} · ${k.ease || "inOutCubic"}`;
    d.onmousedown = (ev) => dragKey(ev, i);
    lanes.camera.appendChild(d);
  }
  // captions + end card
  (st.raw.captions || []).forEach((c, i) => {
    const d = document.createElement("div"); d.className = "seg cap" + (i === st.selCap ? " on" : "");
    [d.style.left, d.style.width] = segPct(+c.t0, +c.t1); d.textContent = c.text || "(text)"; d.title = c.text || "";
    d.innerHTML += '<i class="h l"></i><i class="h r"></i>';
    d.onmousedown = (ev) => dragCap(ev, i);
    lanes.captions.appendChild(d);
  });
  if (st.raw.end_card) {
    const e = st.raw.end_card, d = document.createElement("div"); d.className = "seg end";
    [d.style.left, d.style.width] = segPct(+e.t, dur); d.textContent = "end card";
    d.onmousedown = (ev) => dragEnd(ev);
    lanes.captions.appendChild(d);
  }
  draw();
}

function dragKey(ev, i) {
  ev.preventDefault(); ev.stopPropagation();
  selectKey(i);
  const lane = $(".track[data-track=camera] .lane"), x0 = ev.clientX, t0 = +st.raw.camera[i].t;
  const move = (e) => {
    st.raw.camera[i].t = Math.round(clamp(t0 + ((e.clientX - x0) / lane.clientWidth) * st.spec.duration, 0, st.spec.duration) * 100) / 100;
    changed({ quick: true }); seek(st.raw.camera[i].t);
  };
  const up = () => { removeEventListener("mousemove", move); removeEventListener("mouseup", up); changed(); };
  addEventListener("mousemove", move); addEventListener("mouseup", up);
}
function dragCap(ev, i) {
  ev.preventDefault(); ev.stopPropagation();
  selectCap(i);
  const lane = $(".track[data-track=captions] .lane"), c = st.raw.captions[i];
  const mode = ev.target.classList.contains("l") ? "l" : ev.target.classList.contains("r") ? "r" : "m";
  const x0 = ev.clientX, a0 = +c.t0, b0 = +c.t1, dur = +st.spec.duration;
  const move = (e) => {
    const dt = ((e.clientX - x0) / lane.clientWidth) * dur, r = (v) => Math.round(v * 100) / 100;
    if (mode === "l") c.t0 = r(clamp(a0 + dt, 0, b0 - 0.2));
    else if (mode === "r") c.t1 = r(clamp(b0 + dt, a0 + 0.2, dur));
    else { const w = b0 - a0; c.t0 = r(clamp(a0 + dt, 0, dur - w)); c.t1 = r(c.t0 + w); }
    changed({ quick: true });
  };
  const up = () => { removeEventListener("mousemove", move); removeEventListener("mouseup", up); changed(); buildPanes(); };
  addEventListener("mousemove", move); addEventListener("mouseup", up);
}
function dragEnd(ev) {
  ev.preventDefault();
  const lane = $(".track[data-track=captions] .lane"), e0 = +st.raw.end_card.t, x0 = ev.clientX;
  const move = (e) => { st.raw.end_card.t = Math.round(clamp(e0 + ((e.clientX - x0) / lane.clientWidth) * st.spec.duration, 0, st.spec.duration) * 100) / 100; changed({ quick: true }); };
  const up = () => { removeEventListener("mousemove", move); removeEventListener("mouseup", up); changed(); buildPanes(); };
  addEventListener("mousemove", move); addEventListener("mouseup", up);
}
function scrubFrom(ev) {
  const lane = $(".track[data-track=camera] .lane");
  const at = (e) => seek(xToT(e.clientX - lane.getBoundingClientRect().left, lane));
  at(ev);
  const up = () => { removeEventListener("mousemove", at); removeEventListener("mouseup", up); };
  addEventListener("mousemove", at); addEventListener("mouseup", up);
}

// ------------------------------------------------------------------ camera editing

function selectKey(i) {
  st.selKey = i; st.selCap = -1;
  if (i >= 0) { seek(+st.raw.camera[i].t); showTab("key"); }
  renderTimeline(); buildKeyPane();
}
function selectCap(i) { st.selCap = i; st.selKey = -1; showTab("text"); renderTimeline(); buildPanes(); }
function addKey(at = st.t, vals = null) {
  const [z, x, y, rot] = cameraAt(st.spec.camera, at);
  const k = { t: Math.round(at * 100) / 100, zoom: +z.toFixed(3), x: +x.toFixed(4), y: +y.toFixed(4) };
  if (Math.abs(rot) > 1e-3) k.rot = +rot.toFixed(2);
  Object.assign(k, vals || {});
  st.raw.camera = (st.raw.camera || []).filter((c) => Math.abs(c.t - k.t) > 0.01);
  st.raw.camera.push(k);
  st.raw.camera.sort((a, b) => a.t - b.t);
  changed();
  selectKey(st.raw.camera.indexOf(k));
}
function activeVerseY() { return 0.28; }   // the reader keeps the active verse in the upper third
function preset(kind) {
  const t = st.t, d = 0.9;
  if (!st.raw.camera || !st.raw.camera.length) st.raw.camera = [{ t: 0, zoom: 1, x: 0.5, y: 0.5 }];
  if (kind === "push") { addKey(t); addKey(t + d, { zoom: 2.2, x: 0.5, y: activeVerseY(), ease: "inOutCubic" }); }
  if (kind === "pull") { addKey(t); addKey(t + d, { zoom: 1, x: 0.5, y: 0.5, ease: "inOutCubic" }); }
  if (kind === "punch") { addKey(t); addKey(t + 0.02, { zoom: 2.4, x: 0.5, y: activeVerseY(), ease: "hold" }); }
}
function deleteKey() {
  if (st.selKey < 0) return;
  st.raw.camera.splice(st.selKey, 1); st.selKey = -1; changed(); renderTimeline(); buildKeyPane();
}
function stageDrag(ev) {
  if (st.selKey < 0) return note("select a camera key first (or add one with K)");
  const k = st.raw.camera[st.selKey];
  if (Math.abs(st.t - k.t) > 0.02) seek(+k.t);
  ev.preventDefault();
  els.stage.classList.add("drag");
  const rect = els.stage.getBoundingClientRect(), outPerPx = st.geo.W / rect.width;
  const { g, s0 } = placement();
  const x0 = ev.clientX, y0 = ev.clientY, fx0 = +(k.x ?? 0.5), fy0 = +(k.y ?? 0.5), z = +(k.zoom ?? 1);
  const move = (e) => {
    const dx = (e.clientX - x0) * outPerPx, dy = (e.clientY - y0) * outPerPx;
    // dragging the picture right moves the camera's focus left on the screen
    k.x = +clamp(fx0 - dx / (z * s0 * g.sw), -0.5, 1.5).toFixed(4);
    k.y = +clamp(fy0 - dy / (z * s0 * g.sh), -0.5, 1.5).toFixed(4);
    changed({ quick: true });
  };
  const up = () => { els.stage.classList.remove("drag"); removeEventListener("mousemove", move); removeEventListener("mouseup", up); changed(); buildKeyPane(); };
  addEventListener("mousemove", move); addEventListener("mouseup", up);
}
function stageWheel(ev) {
  if (st.selKey < 0) return;
  ev.preventDefault();
  const k = st.raw.camera[st.selKey];
  if (Math.abs(st.t - k.t) > 0.02) seek(+k.t);
  if (ev.shiftKey) k.rot = +(((+k.rot || 0) + (ev.deltaY > 0 ? 0.5 : -0.5))).toFixed(2);
  else k.zoom = +clamp((+k.zoom || 1) * Math.exp(-ev.deltaY * 0.0015), 0.4, 6).toFixed(3);
  changed({ quick: true });
  clearTimeout(stageWheel.t); stageWheel.t = setTimeout(() => { changed(); buildKeyPane(); }, 250);
}

// ------------------------------------------------------------------ inspector

function field(label, path, type, opts = {}) {
  const wrapEl = document.createElement("label"); wrapEl.className = "field";
  wrapEl.innerHTML = `<span>${label}</span>`;
  const target = opts.target || st.raw;
  const cur = opts.get ? opts.get() : (getPath(target, path) ?? getPath(st.spec, path));
  let input;
  const set = (v) => { if (opts.set) opts.set(v); else setPath(target, path, v); changed({ structural: opts.structural }); if (opts.after) opts.after(v); };
  if (type === "select") {
    input = document.createElement("select");
    for (const [v, t] of opts.options) { const o = document.createElement("option"); o.value = v; o.textContent = t; if (String(v) === String(cur)) o.selected = true; input.appendChild(o); }
    input.onchange = () => set(opts.num ? +input.value : input.value);
  } else if (type === "range") {
    const box = document.createElement("div"); box.className = "rng";
    input = document.createElement("input"); input.type = "range"; input.min = opts.min; input.max = opts.max; input.step = opts.step; input.value = cur;
    const out = document.createElement("output"); out.textContent = (+cur).toFixed(opts.dp ?? 2);
    input.oninput = () => { out.textContent = (+input.value).toFixed(opts.dp ?? 2); set(+input.value); };
    box.append(input, out); wrapEl.appendChild(box); return wrapEl;
  } else if (type === "textarea") {
    input = document.createElement("textarea"); input.value = cur ?? ""; input.oninput = () => set(input.value);
  } else if (type === "check") {
    input = document.createElement("input"); input.type = "checkbox"; input.checked = !!cur; input.onchange = () => set(input.checked);
  } else {
    input = document.createElement("input"); input.type = type; input.value = cur ?? "";
    if (opts.step) input.step = opts.step; if (opts.min != null) input.min = opts.min; if (opts.max != null) input.max = opts.max;
    input.onchange = () => set(type === "number" ? +input.value : input.value);
  }
  wrapEl.appendChild(input); return wrapEl;
}
function h3(t) { const h = document.createElement("h3"); h.textContent = t; return h; }
function noteEl(t) { const p = document.createElement("p"); p.className = "note"; p.textContent = t; return p; }

function buildPanes() {
  if (!st.raw) return;
  const m = st.meta;
  // Video
  let p = $(".pane[data-pane=video]"); p.innerHTML = "";
  p.append(h3("Video"),
    field("Title", "title", "text"),
    field("Aspect", "aspect", "select", { options: Object.keys(m.aspects).map((a) => [a, `${a}  (${m.aspects[a].join("×")})`]), structural: true }),
    field("Duration (s)", "duration", "number", { step: 0.1, min: 1, after: renderTimeline }),
    field("Frame rate", "fps", "select", { options: [[60, "60 fps"], [30, "30 fps"]], num: true }),
    field("Motion blur", "motion_blur", "range", { min: 0, max: 2, step: 0.05 }),
    noteEl("Blur on fast camera moves, as a share of the frame interval. 0 = off."),
    h3("Sound (the emulator's own recording)"),
    field("Gain (dB)", "audio.gain_db", "range", { min: -12, max: 12, step: 0.5, dp: 1 }),
    field("Fade in (s)", "audio.fade_in", "range", { min: 0, max: 3, step: 0.1, dp: 1 }),
    field("Fade out (s)", "audio.fade_out", "range", { min: 0, max: 4, step: 0.1, dp: 1 }));
  // App
  p = $(".pane[data-pane=app]"); p.innerHTML = "";
  const surahs = m.surahs.map((s) => [s.id, `${s.id}. ${s.name} (${s.ayahs})`]);
  p.append(h3("What the app plays"),
    field("Reciter", "app.reciter", "select", { options: Object.entries(m.reciters), structural: true }),
    field("Chapter", "app.surah", "select", { options: surahs, num: true, structural: true }),
    field("From ayah", "app.ayah", "number", { min: 1, structural: true }),
    noteEl("Ayah 1 opens on the chapter header and plays the basmalah first."),
    field("Theme", "app.theme", "select", { options: Object.keys(m.themes).map((t) => [t, t]), structural: true }),
    noteEl("Tarjīʿ glimmer shows on Nightfall and Royal green only."),
    field("Layout", "app.layout", "select", { options: m.layouts.map((l) => [l, l]), structural: true }),
    field("View", "app.view", "select", { options: m.views.map((v) => [v, v]), structural: true }),
    field("Text size", "app.text_size", "select", { options: [["default", "Default"], ["max", "Largest"]], structural: true }),
    h3("Take"),
    field("Record (s)", "app.seconds", "number", { min: 5, step: 1 }),
    noteEl("Seconds of recitation to record. Recording runs at 1× with the emulator's audio."),
    field("Start at (s)", "app.start", "number", { step: 0.05, min: 0 }),
    noteEl("Where the video starts in the take. The take opens 1 s before the recitation."));
  // Look
  p = $(".pane[data-pane=look]"); p.innerHTML = "";
  p.append(h3("Background"));
  const grid = document.createElement("div"); grid.className = "bggrid";
  for (const [id, b] of Object.entries(m.backgrounds)) {
    const btn = document.createElement("button"); btn.className = id === st.spec.background.id ? "on" : "";
    btn.title = `${b.title} · suits ${b.suits.join(", ")}`;
    btn.innerHTML = `<img src="${b.thumb}" loading="lazy" alt=""><span>${id}</span>`;
    btn.onclick = () => { setPath(st.raw, "background.id", id); changed(); buildPanes(); };
    grid.appendChild(btn);
  }
  p.append(grid,
    field("Blur", "background.blur", "range", { min: 4, max: 80, step: 1, dp: 0 }),
    field("Brightness", "background.brightness", "range", { min: -0.4, max: 0.3, step: 0.01 }),
    field("Saturation", "background.saturation", "range", { min: 0, max: 1.6, step: 0.05 }),
    field("Motion speed", "background.speed", "range", { min: 0.1, max: 1.5, step: 0.05 }),
    field("Parallax", "background.parallax", "range", { min: 0, max: 1, step: 0.01 }),
    field("Vignette", "background.vignette", "range", { min: 0, max: 0.8, step: 0.01 }),
    noteEl("Footage from Pexels (free licence). Add your own as \"file:/path\" in the spec's background.id."),
    h3("Phone"),
    field("Colour", "phone.color", "select", { options: [["auto", "Match theme"], ...m.phones.map((c) => [c, c[0].toUpperCase() + c.slice(1)])], get: () => st.raw.phone?.color ?? "auto", structural: true }),
    field("Size", "phone.height", "range", { min: 0.4, max: 1.2, step: 0.01 }),
    field("Offset x", "phone.x", "range", { min: -0.5, max: 0.5, step: 0.01 }),
    field("Offset y", "phone.y", "range", { min: -0.5, max: 0.5, step: 0.01 }),
    field("Shadow", "phone.shadow", "range", { min: 0, max: 1, step: 0.01 }));
  // Text
  p = $(".pane[data-pane=text]"); p.innerHTML = "";
  p.append(h3("Captions"));
  const add = document.createElement("button"); add.textContent = "+ Caption at playhead";
  add.onclick = () => { (st.raw.captions = st.raw.captions || []).push({ t0: +st.t.toFixed(2), t1: +Math.min(st.spec.duration, st.t + 3).toFixed(2), text: "Every word, written as it is recited.", pos: "top" }); st.selCap = st.raw.captions.length - 1; changed(); buildPanes(); renderTimeline(); };
  p.append(add, document.createElement("br"), document.createElement("br"));
  (st.raw.captions || []).forEach((c, i) => {
    const card = document.createElement("div"); card.className = "cap-card" + (i === st.selCap ? " on" : "");
    card.append(
      field("Text", "text", "textarea", { target: c }),
      field("From (s)", "t0", "number", { target: c, step: 0.05, after: renderTimeline }),
      field("To (s)", "t1", "number", { target: c, step: 0.05, after: renderTimeline }),
      field("Position", "pos", "select", { target: c, options: [["top", "Top"], ["bottom", "Bottom"]], get: () => c.pos || "top" }),
      field("Size", "size", "range", { target: c, min: 24, max: 90, step: 1, dp: 0, get: () => c.size || 46 }));
    const del = document.createElement("button"); del.className = "danger"; del.textContent = "Remove";
    del.onclick = () => { st.raw.captions.splice(i, 1); st.selCap = -1; changed(); buildPanes(); renderTimeline(); };
    card.append(del); p.append(card);
  });
  p.append(h3("End card"));
  const ecOn = field("Show", "", "check", { get: () => !!st.raw.end_card, set: (v) => { st.raw.end_card = v ? { t: Math.max(0, st.spec.duration - 3), title: "Beautiful Quran", subtitle: "Free · Android & Web" } : null; }, after: () => { buildPanes(); renderTimeline(); } });
  p.append(ecOn);
  if (st.raw.end_card) {
    p.append(field("At (s)", "t", "number", { target: st.raw.end_card, step: 0.1, after: renderTimeline }),
      field("Title", "title", "text", { target: st.raw.end_card }),
      field("Subtitle", "subtitle", "text", { target: st.raw.end_card }));
  }
  buildKeyPane();
}

function buildKeyPane() {
  const p = $(".pane[data-pane=key]"); p.innerHTML = "";
  if (st.selKey < 0 || !st.raw.camera || !st.raw.camera[st.selKey]) {
    p.append(h3("Camera key"), noteEl("Click a ◆ on the Camera track, or add one at the playhead (+ Camera key, or K). Then drag the picture to pan and scroll to zoom."));
    const keys = st.raw?.camera || [];
    p.append(noteEl(`${keys.length} key${keys.length === 1 ? "" : "s"}.`));
    return;
  }
  const k = st.raw.camera[st.selKey];
  const after = () => { renderTimeline(); };
  p.append(h3(`Camera key at ${(+k.t).toFixed(2)} s`),
    field("Time (s)", "t", "number", { target: k, step: 0.05, after: () => { st.raw.camera.sort((a, b) => a.t - b.t); st.selKey = st.raw.camera.indexOf(k); renderTimeline(); seek(+k.t); } }),
    field("Zoom", "zoom", "range", { target: k, min: 0.4, max: 6, step: 0.01, get: () => k.zoom ?? 1, after }),
    field("Focus x", "x", "range", { target: k, min: -0.5, max: 1.5, step: 0.005, dp: 3, get: () => k.x ?? 0.5, after }),
    field("Focus y", "y", "range", { target: k, min: -0.5, max: 1.5, step: 0.005, dp: 3, get: () => k.y ?? 0.5, after }),
    field("Rotate (°)", "rot", "range", { target: k, min: -20, max: 20, step: 0.1, dp: 1, get: () => k.rot ?? 0, after }),
    field("Ease into it", "ease", "select", { target: k, options: st.meta.eases.map((e) => [e, e === "hold" ? "hold (cut)" : e]), get: () => k.ease || "inOutCubic", after }),
    noteEl("Focus is the point on the phone screen the camera centres on (0–1 across, 0–1 down). Zoom 1 shows the whole phone."));
  const del = document.createElement("button"); del.className = "danger"; del.textContent = "Delete key"; del.onclick = deleteKey;
  p.append(del);
}
function showTab(name) {
  $$("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.tab === name));
  $$(".pane").forEach((p) => p.classList.toggle("on", p.dataset.pane === name));
}

// ------------------------------------------------------------------ saving & loading

function changed(opts = {}) {
  st.spec = resolve(st.raw);
  if (!opts.quick) renderTimeline(); else draw();
  $("#saveState").textContent = "editing…";
  clearTimeout(st.saveTimer);
  st.saveTimer = setTimeout(save, opts.quick ? 700 : 350);
  if (lookKey(st.spec) !== st.lastLook) { clearTimeout(st.plateTimer); st.plateTimer = setTimeout(loadPlate, 400); }
}
async function save() {
  try {
    const r = await api(`/api/spec/${st.name}`, { method: "PUT", body: JSON.stringify(st.raw) });
    $("#saveState").textContent = "saved";
    const i = st.specs.findIndex((x) => x.name === st.name);
    if (i >= 0) { st.specs[i] = r.status; renderList(); }
    const tk = takeKey(st.spec);
    if (tk !== st.lastTakeKey || st.geo?.frame?.indexOf(st.spec.phone.color) < 0 || `${st.geo.W}x${st.geo.H}` !== st.meta.aspects[st.spec.aspect].join("x")) {
      await loadGeometry();
    }
  } catch (e) { $("#saveState").textContent = "not saved: " + e.message; }
}
async function loadGeometry() {
  st.geo = await api(`/api/geometry/${st.name}`);
  st.lastTakeKey = takeKey(st.spec);
  els.frame.src = st.geo.frame;
  if (st.geo.clip) {
    if (!els.screen.src.endsWith(st.geo.clip)) { els.screen.src = st.geo.clip; els.audio.src = st.geo.audio; }
  } else { els.screen.removeAttribute("src"); els.audio.removeAttribute("src"); els.screen.load(); }
  st.tl = await api(`/api/timeline/${st.name}`).catch(() => null);
  sizeStage(); renderTimeline(); updateActions();
  seek(st.t);
}
async function loadPlate() {
  st.lastLook = lookKey(st.spec);
  note("preparing background…", 1500);
  try {
    st.plate = await api(`/api/plate/${st.name}`, { method: "POST", body: JSON.stringify(st.raw) });
    els.plate.src = st.plate.url;
    els.plate.onloadeddata = () => { els.plate.currentTime = plateTime(st.t); draw(); };
  } catch (e) { note("background: " + e.message); }
}
async function open(name) {
  pause();
  st.name = name; st.selKey = -1; st.selCap = -1; st.t = 0;
  const r = await api(`/api/spec/${name}`);
  st.raw = r.raw; st.spec = resolve(st.raw);
  localStorage.setItem("promo.last", name);
  renderList(); buildPanes();
  await loadGeometry();
  await loadPlate();
  $("#saveState").textContent = "saved";
}
function renderList() {
  const ul = $("#specList"); ul.innerHTML = "";
  for (const s of st.specs) {
    const li = document.createElement("li"); li.className = s.name === st.name ? "on" : "";
    if (s.error) { li.innerHTML = `<div class="t">${s.name}</div><div class="s">⚠ ${s.error}</div>`; }
    else {
      const rec = st.meta.reciters[s.app.reciter].split(" ").pop();
      li.innerHTML = `<div class="t">${s.title || s.name}</div><div class="s"><span>${rec}</span><span>${s.app.surah}:${s.app.ayah}</span><span>${s.app.theme}</span><span>${s.aspect}</span></div>
        <div class="s"><span><i class="dot ${s.take ? "ok" : ""}"></i>take</span><span><i class="dot ${s.video ? "vid" : ""}"></i>video</span></div>`;
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
  const s = st.specs.find((x) => x.name === st.name);
  const a = $("#openVideo");
  if (s && s.video) { a.href = `/media/out/${st.name}.mp4?${s.video_mtime}`; a.classList.remove("hidden"); } else a.classList.add("hidden");
}

// ------------------------------------------------------------------ jobs & emulator

async function startJob(path, body = {}) {
  try { await api(path, { method: "POST", body: JSON.stringify(body) }); pollJobs(); }
  catch (e) { note(e.message, 4000); }
}
let jobsTimer = null;
async function pollJobs() {
  clearTimeout(jobsTimer);
  const jobs = await api("/api/jobs").catch(() => []);
  const box = $("#jobs"); box.innerHTML = "";
  let active = false;
  for (const j of jobs.slice(0, 8)) {
    const d = document.createElement("div"); d.className = "job " + j.state;
    const pct = Math.round((j.progress || 0) * 100);
    const link = j.state === "done" && j.kind === "render" ? ` · <a target="_blank" href="/media/out/${j.name}.mp4?${Date.now()}">open ↗</a>` : "";
    d.innerHTML = `<div><b class="k">${j.kind}</b> ${j.name} — ${j.state}${j.state === "running" && j.kind === "render" ? ` ${pct}%` : ""}${link}</div>
      ${j.state === "running" ? `<div class="bar"><i style="width:${j.kind === "render" ? pct : 100}%"></i></div>` : ""}
      ${j.log.length ? `<pre>${j.log.slice(-4).join("\n").replace(/</g, "&lt;")}</pre>` : ""}`;
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
  const el = $("#emu"); el.textContent = `${e.serial} ${e.up ? "ready" : "off"}`; el.classList.toggle("up", e.up);
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
$("#addKey").onclick = () => addKey();
$("#delKey").onclick = deleteKey;
$("#presetPush").onclick = () => preset("push");
$("#presetPull").onclick = () => preset("pull");
$("#presetPunch").onclick = () => preset("punch");
$("#exactBtn").onclick = showExact;
$("#ruler").onmousedown = scrubFrom;
$$(".track .lane").forEach((l) => l.addEventListener("mousedown", (e) => { if (e.target === l) scrubFrom(e); }));
$(".track[data-track=camera] .lane").addEventListener("dblclick", (e) => { const l = e.currentTarget; addKey(xToT(e.clientX - l.getBoundingClientRect().left, l)); });
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
  if (e.target.closest("input, textarea, select")) return;
  if (e.code === "Space") { e.preventDefault(); play(); }
  else if (e.key === "k" || e.key === "K") addKey();
  else if (e.key === "Delete" || e.key === "Backspace") deleteKey();
  else if (e.key === "ArrowLeft") seek(st.t - (e.shiftKey ? 1 : 1 / 30));
  else if (e.key === "ArrowRight") seek(st.t + (e.shiftKey ? 1 : 1 / 30));
});
els.screen.addEventListener("seeked", draw);
els.plate.addEventListener("seeked", draw);
els.frame.addEventListener("load", draw);
document.fonts.load('46px "Cormorant Italic"').then(draw);
init();
