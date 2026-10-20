// App state. Every edit goes through mutate(): one undo step (or folded into
// the previous one for drags and slider moves), a resolved spec for the
// preview, and an ordered autosave to the server.
import { create } from "zustand";
import { api } from "../lib/api.ts";
import * as E from "../lib/edit.ts";
import { applyVersePlan, nextVerseRange, verseRange, type VerseAction } from "../lib/verses.ts";
import { clamp, plateLook, resolve, soundOnset, takeKey } from "../lib/model.ts";
import type { Ease, Emulator, Geo, Job, Meta, Plate, RawSpec, Selection, Spec, SpecStatus, Timeline, Wave } from "../lib/types.ts";
import { clock, media } from "./clock.ts";

export type Tab = "animate" | "recording" | "look";
export interface UI {
  leftW: number; rightW: number; tlH: number; labelW: number; hideL: boolean; hideR: boolean;
  tab: Tab; pps: number; fit: boolean; collapsed: Record<string, boolean>;
}
export type Panel = null | "emulators" | "jobs" | "help";
export interface Toast { id: number; msg: string; kind: "info" | "error" | "ok" }
type SaveState = "saved" | "dirty" | "saving" | "error";

const UI_DEFAULTS: UI = { leftW: 248, rightW: 340, tlH: 260, labelW: 136, hideL: false, hideR: false, tab: "animate", pps: 60, fit: false, collapsed: {} };
function loadUI(): UI {
  try { return { ...UI_DEFAULTS, ...JSON.parse(localStorage.getItem("promo.ui") || "{}"), tab: "animate" }; } catch { return UI_DEFAULTS; }
}

interface St {
  meta: Meta | null; specs: SpecStatus[]; name: string | null; raw: RawSpec | null; spec: Spec | null;
  geo: Geo | null; plate: Plate | null; tl: Timeline | null; wave: Wave | null; sel: Selection; clipboard: E.Clip[] | null;
  undo: string[]; redo: string[]; saveState: SaveState; saveError: string | null;
  jobs: Job[]; emus: Emulator[]; booting: Job[]; emusLoaded: boolean;
  exact: string | null; ui: UI; panel: Panel; toasts: Toast[]; fatal: string | null;
  verseBusy: boolean; verseError: string | null;
}

export const useStore = create<St>(() => ({
  meta: null, specs: [], name: null, raw: null, spec: null, geo: null, plate: null, tl: null, wave: null, sel: null, clipboard: null,
  undo: [], redo: [], saveState: "saved", saveError: null, jobs: [], emus: [], booting: [], emusLoaded: false,
  exact: null, ui: loadUI(), panel: null, toasts: [], fatal: null,
  verseBusy: false, verseError: null,
}));
const get = useStore.getState, set = useStore.setState;

let session = 0;                       // bumps on open: late answers for another video are dropped
let saved: string | null = null;       // the JSON last written for this video
let saveChain: Promise<unknown> = Promise.resolve();
let saveTimer: number | undefined, plateTimer: number | undefined;
let coalesce: { key: string; at: number } | null = null;
let lastLook = "", lastGeoKey = "";
let lastTimelineKey = "", verseRequest = 0;

clock.bind(() => {
  const s = get();
  return {
    spec: s.spec, hasClip: !!s.geo?.clip, hasPlate: !!s.plate, offset: +(s.spec?.background.offset || 0),
    onset: s.spec ? soundOnset(s.spec, s.geo?.take?.lead ?? 1) : 0,
  };
});

// -------------------------------------------------------------- feedback

let toastId = 0;
export function toast(msg: string, kind: Toast["kind"] = "info", ms = 3200) {
  const id = ++toastId;
  set({ toasts: [...get().toasts, { id, msg, kind }].slice(-4) });
  setTimeout(() => set({ toasts: get().toasts.filter((x) => x.id !== id) }), kind === "error" ? Math.max(ms, 6000) : ms);
}
export function setUI(patch: Partial<UI>) {
  const ui = { ...get().ui, ...patch };
  if (patch.pps != null && patch.fit == null) ui.fit = false;
  if (innerWidth <= 1000) {
    if (patch.hideL === false) ui.hideR = true;
    else if (patch.hideR === false) ui.hideL = true;
  }
  set({ ui });
  localStorage.setItem("promo.ui", JSON.stringify(ui));
}
export const openPanel = (panel: Panel) => set({ panel: get().panel === panel ? null : panel });

// -------------------------------------------------------------- context

export function ctx(): E.Ctx {
  const s = get();
  return { spec: s.spec!, props: s.meta!.props, fps: s.spec?.fps || 60 };
}
const snapshot = () => JSON.stringify(get().raw);

// -------------------------------------------------------------- edits

// One undo step; edits sharing a `key` within 0.9 s (a slider drag, a scroll
// zoom) fold together, and a drag ("drag:…") folds for its whole gesture.
export function mutate(fn: (raw: RawSpec, c: E.Ctx) => void, key: string | null = null) {
  const { raw, meta } = get();
  if (!raw || !meta) return;
  const before = JSON.stringify(raw), draft = JSON.parse(before) as RawSpec;
  fn(draft, ctx());
  const after = JSON.stringify(draft);
  if (after === before) return;
  const now = performance.now();
  const join = key && coalesce && coalesce.key === key && (key.startsWith("drag:") || now - coalesce.at < 900);
  const undo = join ? get().undo : [...get().undo, before].slice(-200);
  coalesce = key ? { key, at: now } : null;
  commit(draft, undo, join ? get().redo : []);
}
export function endGesture() { coalesce = null; }

function commit(draft: RawSpec, undo: string[], redo: string[]) {
  const spec = resolve(draft, get().meta!);
  set({ raw: draft, spec, undo, redo, exact: null, saveState: "dirty" });
  if (clock.t > spec.duration) clock.seek(spec.duration); else clock.emit();
  clearTimeout(saveTimer);
  saveTimer = window.setTimeout(save, 450);
  if (plateLook(spec) !== lastLook) { clearTimeout(plateTimer); plateTimer = window.setTimeout(loadPlate, 350); }
}

export function undo() {
  const { undo: u, redo: r } = get();
  if (!u.length) return;
  coalesce = null;
  const prev = JSON.parse(u[u.length - 1]);
  set({ sel: null });
  commit(prev, u.slice(0, -1), [...r, snapshot()]);
}
export function redo() {
  const { undo: u, redo: r } = get();
  if (!r.length) return;
  coalesce = null;
  const next = JSON.parse(r[r.length - 1]);
  set({ sel: null });
  commit(next, [...u, snapshot()], r.slice(0, -1));
}

// -------------------------------------------------------------- saving

// Saves run one after another, so a slow early PUT never lands after a later one.
export function save(): Promise<boolean> {
  clearTimeout(saveTimer);
  const { name, raw } = get();
  if (!name || !raw) return Promise.resolve(true);
  const body = JSON.stringify(raw), mine = session;
  const task = saveChain.catch(() => {}).then(async () => {
    if (mine !== session) return;
    if (saved === body) { if (body === snapshot()) set({ saveState: "saved" }); return; }
    set({ saveState: "saving" });
    const r = await api.save(name, body);
    if (mine !== session) return;
    saved = body;
    set({ saveState: body === snapshot() ? "saved" : "dirty", saveError: null, specs: get().specs.map((x) => (x.name === name ? r.status : x)) });
    const s = get().spec!;
    if (geoKey(s) !== lastGeoKey) await loadGeometry();
    else if (timelineKey(s) !== lastTimelineKey) void loadTimeline();
  });
  saveChain = task;
  return task.then(() => true, (e: Error) => {
    if (mine === session) { set({ saveState: "error", saveError: e.message }); toast("Not saved: " + e.message, "error"); }
    return false;
  });
}
const geoKey = (s: Spec) => [takeKey(s), s.aspect, s.phone.color].join("|");
const timelineKey = (s: Spec) => JSON.stringify([takeKey(s), s.app.seconds, Math.max(...s.edit.map((p) => p.out))]);

// -------------------------------------------------------------- loading

let started = false;
export async function init() {
  if (started) return;                 // StrictMode mounts twice in development
  started = true;
  try {
    const meta = await api.meta();
    set({ meta });
    await refreshSpecs();
    const last = localStorage.getItem("promo.last");
    const first = get().specs.find((s) => s.name === last) || get().specs[0];
    if (first) await open(first.name);
    pollJobs(); pollEmus();
  } catch (e) {
    set({ fatal: (e as Error).message });
  }
}

export async function refreshSpecs() { set({ specs: await api.specs() }); }

export async function open(name: string) {
  clock.pause();
  if (get().name && !(await save())) { toast("Save failed, so this video stays open. Retry from the save status.", "error"); return; }
  const mine = ++session;
  try {
    const r = await api.spec(name);
    if (mine !== session) return;
    clearTimeout(saveTimer); clearTimeout(plateTimer);
    coalesce = null; lastLook = ""; lastGeoKey = ""; lastTimelineKey = ""; verseRequest++;
    const spec = resolve(r.raw, get().meta!);
    // New specs may contain just a title; editing always needs real pieces.
    r.raw.edit = spec.edit.map((p) => ({ ...p }));
    saved = JSON.stringify(r.raw);
    set({ name, raw: r.raw, spec, geo: null, plate: null, tl: null, wave: null, sel: null, undo: [], redo: [], exact: null, saveState: "saved", saveError: null, verseBusy: false, verseError: null });
    localStorage.setItem("promo.last", name);
    clock.t = 0;
    await Promise.all([loadGeometry(), loadPlate()]);
    clock.seek(0);
  } catch (e) {
    toast("Could not open " + name + ": " + (e as Error).message, "error");
  }
}

export async function loadGeometry() {
  const { name, spec } = get();
  if (!name || !spec) return;
  const mine = session, key = geoKey(spec);
  void loadTimeline();
  const geo = await api.geometry(name);
  if (mine !== session || geoKey(get().spec!) !== key) return;
  lastGeoKey = key;
  media.frame.src = geo.frame;
  if (geo.clip) {
    if (!media.screen.src.endsWith(geo.clip)) { media.screen.src = geo.clip; media.audio.src = geo.audio!; }
  } else {
    media.screen.removeAttribute("src"); media.audio.removeAttribute("src"); media.screen.load(); media.audio.load();
  }
  set({ geo });
  clock.seek(clock.t);
  void loadWave(geo.peaks);
}

let waveUrl: string | null = null;
async function loadWave(url: string | null) {
  if (url === waveUrl && get().wave) return;
  waveUrl = url;
  if (!url) return set({ wave: null });
  try {
    const r = await fetch(url);
    if (!r.ok) throw new Error(String(r.status));
    const w = await r.json() as { rate: number; peaks: number[] };
    if (waveUrl === url) set({ wave: { rate: w.rate, peaks: Uint8Array.from(w.peaks) } });
  } catch { if (waveUrl === url) set({ wave: null }); }   // the lane draws its fades without a waveform
}

export async function loadTimeline() {
  const { name, spec, raw } = get();
  if (!name || !spec || !raw) return;
  const mine = session, key = timelineKey(spec);
  try {
    const tl = await api.timeline(name, raw);
    if (mine === session && get().spec && timelineKey(get().spec!) === key) { lastTimelineKey = key; set({ tl }); }
  } catch { /* Range controls provide a retry when the timeline is unavailable. */ }
}

/** Choose a continuous range, or add/remove a complete verse at an edit edge. */
export async function editVerses(start: number, end: number, action?: VerseAction) {
  const s = get();
  if (!s.spec || !s.raw || !s.name || s.verseBusy) return;
  const mine = session, request = ++verseRequest;
  const identity = JSON.stringify([takeKey(s.spec), s.spec.edit]);
  set({ verseBusy: true, verseError: null });
  try {
    const plan = await api.verseRange(s.name, s.raw, start, end);
    if (mine !== session || request !== verseRequest) return;
    const current = get();
    if (!current.spec || JSON.stringify([takeKey(current.spec), current.spec.edit]) !== identity) throw new Error("The edit changed while loading verses. Try the verse action again.");
    const changedTake = plan.anchor !== current.spec.app.ayah;
    mutate((raw) => applyVersePlan(raw, current.spec!, plan, action));
    set({ sel: null, tl: { verses: plan.verses, repeats: changedTake ? [] : (current.tl?.repeats ?? []), take: changedTake ? null : (current.tl?.take ?? null) }, ...(changedTake ? { geo: null } : {}) });
    // Save/load the new recording origin immediately; existing footage stays
    // available for extensions on the same take and is marked short in the UI.
    await save();
  } catch (e) {
    if (mine === session && request === verseRequest) set({ verseError: (e as Error).message });
  } finally {
    if (mine === session && request === verseRequest) set({ verseBusy: false });
  }
}

export function changeVerse(action: VerseAction) {
  const { spec, tl, meta } = get();
  if (!spec || !tl || !meta) return;
  const count = meta.surahs.find((s) => s.id === spec.app.surah)?.ayahs ?? 0;
  const next = nextVerseRange(verseRange(spec, tl.verses), action, count);
  if (next) void editVerses(next.start, next.end, action);
}

export async function loadPlate() {
  const { name, spec } = get();
  if (!name || !spec) return;
  const mine = session, look = plateLook(spec);
  try {
    const plate = await api.plate(name, snapshot());
    if (mine !== session || look !== plateLook(get().spec!)) return;
    lastLook = look;
    media.plate.src = plate.url;
    set({ plate });
  } catch (e) {
    if (mine === session) toast("Background: " + (e as Error).message, "error");
  }
}

// -------------------------------------------------------------- selection & keys

export const selKeys = (): E.KeyRef[] => { const s = get().sel; return s?.type === "keys" ? s.items : []; };
export const selectKeys = (items: E.KeyRef[]) => set({ sel: items.length ? { type: "keys", items } : null });
export const select = (sel: Selection) => set({ sel });

export function setProp(name: string, v: number, key?: string) {
  if (!Number.isFinite(v)) return;
  mutate((raw, c) => E.setProp(raw, c, name, v, clock.t), key ?? "prop:" + name);
}
export function toggleKey(name: string) {
  let added: E.KeyRef | null = null;
  mutate((raw, c) => { added = E.toggleKey(raw, c, name, clock.t); });
  selectKeys(added ? [added] : []);
}
export function keyCamera() { mutate((raw, c) => E.keyAll(raw, c, ["zoom", "pan_x", "pan_y"], clock.t)); }
export function preset(kind: "push" | "pull" | "punch") {
  const geo = get().geo;
  if (!geo) return;
  mutate((raw, c) => E.preset(raw, c, geo, kind, clock.t));
}
export function nudge(frames: number) {
  const items = selKeys();
  if (!items.length) return;
  let moved: E.KeyRef[] | null = null;
  mutate((raw, c) => { moved = E.moveKeys(raw, c, items, frames / (c.fps || 60)); }, "nudge");
  if (moved) { selectKeys(moved); clock.seek((moved as E.KeyRef[])[0].t); } else toast("A key is in the way");
}
export function deleteSelection() {
  const s = get().sel;
  if (!s) return;
  clock.pause();
  if (s.type === "keys") mutate((raw) => E.deleteKeys(raw, s.items));
  else if (s.type === "piece") {
    if ((get().raw!.edit || []).length <= 1) return toast("The video needs at least one piece");
    mutate((raw) => { E.deletePiece(raw, s.i); });
  }
  set({ sel: null });
}
export function setEase(ease: Ease) { const items = selKeys(); if (items.length) mutate((raw) => E.setEase(raw, items, ease)); }
export function copy() {
  const clip = E.copyKeys(get().raw!, selKeys());
  if (!clip.length) return toast("Select keys to copy");
  set({ clipboard: clip });
  toast(`Copied ${clip.length} key${clip.length > 1 ? "s" : ""}`);
}
export function paste(at = clock.t) {
  const clip = get().clipboard;
  if (!clip) return;
  let placed: E.KeyRef[] = [];
  mutate((raw, c) => { placed = E.pasteKeys(raw, c, clip, at); });
  selectKeys(placed);
}
export function selectAll() {
  const items: E.KeyRef[] = [];
  for (const [prop, keys] of Object.entries(get().spec?.tracks || {})) for (const k of keys) items.push({ prop, t: k.t });
  selectKeys(items);
  if (items.length) toast(`${items.length} key${items.length > 1 ? "s" : ""} selected`);
}
export function split(t = clock.t) {
  let at: number | null = null;
  mutate((raw, c) => { at = E.splitAt(raw, c, t); });
  if (at == null) toast("Put the playhead inside a piece to split it");
  else set({ sel: { type: "piece", i: at } });
}
export function wholeTake() {
  const len = get().geo?.take?.length ?? get().tl?.take ?? Math.max(...get().spec!.edit.map((p) => p.out));
  mutate((raw) => { raw.edit = [{ in: 0, out: +len.toFixed(3) }]; });
  set({ sel: null });
}

// -------------------------------------------------------------- jobs & emulators

let jobsTimer: number | undefined, emusTimer: number | undefined;
const seenDone = new Set<string>();
export async function pollJobs() {
  clearTimeout(jobsTimer);
  try {
    const jobs = await api.jobs();
    set({ jobs });
    for (const j of jobs) {
      if (j.state === "running" || j.state === "queued" || seenDone.has(j.id)) continue;
      seenDone.add(j.id);
      if (!j.ended || Date.now() / 1000 - j.ended > 30) continue;   // old news from before this page loaded
      if (j.state === "done") toast(`${j.title} · ${j.name}: done`, "ok");
      else if (j.state === "error") toast(`${j.title} · ${j.name} failed: ${j.error ?? ""}`.slice(0, 220), "error");
      if (j.kind === "capture" && j.name === get().name && j.state === "done") loadGeometry();
      if (j.kind === "capture" || j.kind === "render") refreshSpecs();
      if (j.kind.startsWith("emu")) pollEmus(true);
    }
  } catch { /* the server may be restarting: keep polling */ }
  const active = get().jobs.some((j) => j.state === "running" || j.state === "queued");
  jobsTimer = window.setTimeout(pollJobs, active ? 700 : 3000);
}
export async function pollEmus(fresh = false) {
  clearTimeout(emusTimer);
  try {
    const r = await api.emus(fresh);
    set({ emus: r.emulators, booting: r.booting, emusLoaded: true });
  } catch { /* keep polling */ }
  emusTimer = window.setTimeout(pollEmus, get().panel === "emulators" ? 1500 : 4000);
}
async function job(p: Promise<Job>, what: string) {
  try { const j = await p; toast(`${what}: ${j.state === "queued" ? "queued" : "started"}`); pollJobs(); return j; }
  catch (e) { toast((e as Error).message, "error"); return null; }
}
export async function record(serial: string | null, force: boolean) {
  const { name } = get();
  if (!name || !(await save())) return;
  return job(api.capture(name, { force, serial }), "Recording");
}
export async function exportVideo(name = get().name, scale = 1) {
  if (!name) return;
  if (name === get().name && !(await save())) return;
  return job(api.render(name, scale), `Export ${name}`);
}
export const cancelJob = (id: string) => api.cancel(id).then(pollJobs, (e) => toast(e.message, "error"));
export const bootEmulator = (cores: number) => job(api.bootEmu(cores), "Starting an emulator").then(() => pollEmus(true));
export const prepareEmulator = (serial: string) => job(api.prepareEmu(serial), `Preparing ${serial}`);
export const stopEmulator = (serial: string) => api.stopEmu(serial).then(() => { toast(`Stopped ${serial}`); pollEmus(true); }, (e) => toast(e.message, "error"));

// -------------------------------------------------------------- videos

const slug = (s: string) => s.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
export async function createVideo(title: string, from?: string) {
  const name = slug(title);
  if (!name) return;
  try {
    if (from === get().name && !(await save())) return;
    await (from ? api.copy(from, name) : api.create(name));
    await refreshSpecs();
    await open(name);
    if (!from) mutate((raw) => { raw.title = title; });
  } catch (e) { toast((e as Error).message, "error"); }
}
export async function renameVideo(from: string, title: string) {
  const to = slug(title);
  if (!to || to === from) return;
  try {
    if (from === get().name && !(await save())) return;
    await api.rename(from, to);
    await refreshSpecs();
    if (from === get().name) { session++; set({ name: null }); await open(to); }
  } catch (e) { toast((e as Error).message, "error"); }
}
export async function deleteVideo(name: string) {
  try {
    if (name === get().name) { clearTimeout(saveTimer); session++; set({ name: null, raw: null, spec: null, geo: null, plate: null, tl: null }); }
    await api.remove(name);
    await refreshSpecs();
    if (!get().name && get().specs[0]) await open(get().specs[0].name);
    toast(`Deleted ${name} (its take and video stay on disk)`);
  } catch (e) { toast((e as Error).message, "error"); }
}

export async function showExact() {
  const { name, geo } = get();
  if (!name || !geo?.clip) return toast("Record a take first");
  clock.pause();
  if (!(await save())) return;
  const url = api.stillUrl(name, clock.t), at = clock.t, body = snapshot();
  const img = new Image();
  img.onload = () => { if (at === clock.t && body === snapshot()) set({ exact: url }); };
  img.onerror = () => toast("The exact frame failed (see the terminal)", "error");
  img.src = url;
}
export const hideExact = () => { if (get().exact) set({ exact: null }); };
export const clampPane = (v: number, lo: number, hi: number) => Math.round(clamp(v, lo, hi));
