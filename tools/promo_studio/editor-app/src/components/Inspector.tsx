// The right-hand inspector: Animate (properties and keys), Recording (what
// the app plays, the take and the edit), Look (frame, background, phone, sound).
import { useEffect, useState } from "react";
import { api } from "../lib/api.ts";
import * as E from "../lib/edit.ts";
import { clamp, r3, valueAt } from "../lib/model.ts";
import type { Ease, Emulator, Job } from "../lib/types.ts";
import { clock, useTime } from "../state/clock.ts";
import {
  cancelJob, deleteSelection, endGesture, mutate, openPanel, record, select, setEase, setProp, setUI, split, toggleKey,
  useStore, wholeTake, type Tab,
} from "../state/store.ts";
import { Button, Dot, Empty, Field, Icon, Progress, Section, Segmented, Select, Slider, TextInput } from "./ui.tsx";
import { Opening } from "./Opening.tsx";
import { PhoneLookPreview } from "./PhoneLookPreview.tsx";
import { VerseRangeEditor } from "./VerseEditor.tsx";

const EASES: [Ease, string][] = [["inOutCubic", "Ease in & out"], ["inOutSine", "Gentle in & out"], ["outCubic", "Ease out"], ["inCubic", "Ease in"], ["outExpo", "Fast out, long settle"], ["linear", "Linear"], ["hold", "Hold (cut)"]];
const set = (path: string, v: unknown, key: string | null = null) => mutate((raw) => {
  const ks = path.split("."); let cur = raw;
  for (const k of ks.slice(0, -1)) cur = cur[k] = cur[k] && typeof cur[k] === "object" ? cur[k] : {};
  cur[ks[ks.length - 1]] = v;
}, key);

export function Inspector() {
  const tab = useStore((s) => s.ui.tab), spec = useStore((s) => s.spec);
  const capture = useStore((s) => s.jobs.find((j) => j.kind === "capture" && j.name === s.name && (j.state === "running" || j.state === "queued")));
  return (
    <div className="inspector flex h-full min-h-0 min-w-0 flex-col bg-panel">
      <div className="flex shrink-0 gap-1 border-b border-line p-2">
        {(["animate", "recording", "look"] as Tab[]).map((t) => (
          <button key={t} onClick={() => setUI({ tab: t })}
            className={`relative flex-1 rounded-md py-1.5 capitalize transition-colors ${tab === t ? "bg-raised text-ink" : "text-mute hover:text-ink"}`}>
            {t}{t === "recording" && capture && <span className="absolute right-2 top-2 size-1.5 rounded-full bg-orange animate-pulse-soft" />}
          </button>
        ))}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-4 py-4">
        {!spec ? <Empty>No video open.</Empty> : tab === "animate" ? <Animate /> : tab === "recording" ? <Recording /> : <Look />}
      </div>
    </div>
  );
}

// ------------------------------------------------------------ animate

function Animate() {
  const meta = useStore((s) => s.meta)!, spec = useStore((s) => s.spec)!, sel = useStore((s) => s.sel), raw = useStore((s) => s.raw)!;
  const t = useTime(60);
  const groups: Record<string, string[]> = {};
  for (const [n, p] of Object.entries(meta.props)) (groups[p.group] = groups[p.group] || []).push(n);
  const items = sel?.type === "keys" ? sel.items : [];
  return (
    <>
      {items.length > 0 && <KeyCard items={items} />}
      {Object.entries(groups).map(([g, names]) => (
        <Section key={g} title={g}>
          {names.map((n) => {
            const d = meta.props[n], keys = spec.tracks[n] || [], here = keys.some((k) => Math.abs(k.t - t) < 0.5 / (spec.fps || 60) + 1e-6);
            return (
              <div key={n} className="mb-1.5 grid grid-cols-[24px_86px_minmax(0,1fr)] items-center gap-1.5">
                <button onClick={() => toggleKey(n)} title={here ? "Remove the key at the playhead" : "Add a key at the playhead"}
                  className={`grid size-6 place-items-center rounded border text-[11px] ${here ? "border-gold/60 text-gold" : keys.length ? "border-line text-green" : "border-line text-faint hover:text-mute"}`}>◆</button>
                <span className="truncate text-mute" title={`${d.label} · ${keys.length ? `${keys.length} key${keys.length > 1 ? "s" : ""}` : "one value for the whole video"}`}>{d.label}</span>
                <Slider label={d.label} value={valueAt(spec, meta.props, n, t)} min={d.min} max={d.max} step={d.step} digits={d.step < 0.01 ? 3 : 2}
                  onChange={(v) => setProp(n, v)} onDone={endGesture} />
              </div>
            );
          })}
        </Section>
      ))}
      <p className="text-[11.5px] leading-relaxed text-faint">
        ◆ adds or removes a key at the playhead. Once a property has a key, changing it (here or on the picture) keys it at the playhead; without keys it is one value for the whole video.
        {raw.tracks && Object.keys(raw.tracks).length === 0 && " Try Push in under the picture."}
      </p>
    </>
  );
}

function KeyCard({ items }: { items: E.KeyRef[] }) {
  const meta = useStore((s) => s.meta)!, raw = useStore((s) => s.raw)!, spec = useStore((s) => s.spec)!;
  const keys = items.map((r) => E.findKey(raw, r)).filter(Boolean) as { t: number; v: number; ease?: Ease }[];
  if (!keys.length) return null;
  const eases = new Set(keys.map((k) => k.ease || "inOutCubic"));
  const one = items.length === 1, k = keys[0], d = meta.props[items[0].prop];
  return (
    <div className="mb-5 rounded-lg border border-gold/30 bg-gold/[0.06] p-3">
      <div className="mb-2.5 flex items-center justify-between">
        <h3 className="font-semibold">{one ? `${d.label} key` : `${items.length} keys selected`}</h3>
        <button className="text-faint hover:text-ink" onClick={() => select(null)} title="Deselect (Esc)">{Icon.x}</button>
      </div>
      {one && (
        <>
          <Field label="Time (s)">
            <TextInput type="number" step={0.01} value={k.t} onCommit={(v) => {
              const t = r3(clamp(parseFloat(v), 0, spec.duration));
              if (!Number.isFinite(t)) return;
              let next: E.KeyRef[] | null = null;
              mutate((r, c) => { next = E.moveKeys(r, c, items, t - k.t); });
              if (next) { useStore.setState({ sel: { type: "keys", items: next } }); clock.seek(t); }
            }} />
          </Field>
          <Field label="Value">
            <TextInput type="number" step={d.step} value={k.v} onCommit={(v) => { const n = parseFloat(v); if (Number.isFinite(n)) mutate((r) => { const key = E.findKey(r, items[0]); if (key) key.v = r3(clamp(n, d.min, d.max)); }); }} />
          </Field>
        </>
      )}
      <Field label={one ? "Ease into it" : "Ease into them"}>
        <Select value={eases.size > 1 ? "" : [...eases][0]} options={[...(eases.size > 1 ? [["", "(mixed)"] as [string, string]] : []), ...EASES]} onChange={(v) => v && setEase(v as Ease)} />
      </Field>
      <div className="flex gap-2">
        <Button size="sm" variant="danger" onClick={deleteSelection}>{Icon.trash} {one ? "Delete key" : `Delete ${items.length}`}</Button>
        {one && <Button size="sm" variant="ghost" onClick={() => { mutate((r, c) => E.clearTrack(r, c, items[0].prop, clock.t)); select(null); }} title="Remove every key of this property; it keeps the value at the playhead">Clear track</Button>}
      </div>
      {!one && <p className="mt-2 text-[11.5px] text-faint">Drag any of them on the timeline to move them together; , and . nudge them a frame.</p>}
    </div>
  );
}

// ------------------------------------------------------------ recording

function Recording() {
  const meta = useStore((s) => s.meta)!, spec = useStore((s) => s.spec)!, raw = useStore((s) => s.raw)!;
  const geo = useStore((s) => s.geo), tl = useStore((s) => s.tl), sel = useStore((s) => s.sel), name = useStore((s) => s.name);
  const emus = useStore((s) => s.emus);
  const job = useStore((s) => s.jobs.find((j) => j.kind === "capture" && j.name === s.name));
  const live = job && (job.state === "running" || job.state === "queued") ? job : null;
  const [serial, setSerial] = useState<string>("auto");
  const a = spec.app, take = geo?.take;
  const activeFps = take?.active_fps;
  const takeShort = take && Math.max(...spec.edit.map((p) => p.out)) > take.length + 0.05;
  const usable = emus.filter((e) => e.kind === "capture" && e.booted && e.app);
  return (
    <>
      <Section title="What the app plays">
        <Field label="Reciter"><Select value={a.reciter} options={Object.entries(meta.reciters)} onChange={(v) => set("app.reciter", v)} /></Field>
        <Field label="Chapter"><Select value={a.surah} options={meta.surahs.map((s) => [s.id, `${s.id}. ${s.name} (${s.ayahs})`])} onChange={(v) => mutate((r) => {
          const count = meta.surahs.find((s) => s.id === +v)!.ayahs, ayah = Math.min(a.ayah, count);
          r.app = { ...r.app, surah: +v, ayah, ...(a.repeat === "range" ? { repeat_to: Math.max(ayah, Math.min(a.repeat_to ?? ayah, count)) } : {}) };
        })} /></Field>
        <Field label="Take starts" hint="The recording opens here. Choose the video's start and stop verses under Edit. Verse 1 includes the chapter opening.">
          <TextInput type="number" min={1} max={meta.surahs.find((s) => s.id === a.surah)?.ayahs} value={a.ayah} onCommit={(v) => { const n = Number(v), count = meta.surahs.find((s) => s.id === a.surah)!.ayahs; if (Number.isInteger(n) && n >= 1 && n <= count) mutate((r) => { r.app = { ...r.app, ayah: n, ...(a.repeat === "range" ? { repeat_to: Math.max(n, a.repeat_to ?? n) } : {}) }; }); }} />
        </Field>
        <Field label="Theme" hint={a.theme === "Paper" ? "Tarjīʿ glimmer only shows on Nightfall and Royal green." : undefined}>
          <Segmented size="sm" value={a.theme} options={Object.keys(meta.themes).map((t) => [t, t.replace("Royal green", "Royal")])} onChange={(v) => set("app.theme", v)} />
        </Field>
        <Field label="Layout"><Segmented size="sm" value={a.layout} options={meta.layouts.map((l) => [l, l])} onChange={(v) => set("app.layout", v)} /></Field>
        <Field label="View"><Segmented size="sm" value={a.view} options={meta.views.map((v) => [v, v])} onChange={(v) => set("app.view", v)} /></Field>
        <Field label="Text size"><Segmented size="sm" value={a.text_size} options={[["default", "Default"], ["max", "Largest"]]} onChange={(v) => set("app.text_size", v)} /></Field>
        <Field label={a.intro.play ? "Recitation (s)" : "Minimum take (s)"} hint={a.intro.play ? "Seconds of recitation after the opening, at 1×, with the emulator's own sound." : "Minimum total duration. Every sequence step and its final hold are always included."}>
          <TextInput type="number" min={5} step={1} value={a.seconds} onCommit={(v) => { const n = parseInt(v); if (n >= 5) set("app.seconds", n); }} />
        </Field>
      </Section>

      <Opening />

      <Section title="Take">
        {live ? <CaptureCard job={live} /> : (
          <div className="mb-3 rounded-lg border border-line bg-panel2 p-3">
            {take ? (
              <p className="mb-2.5 text-mute"><Dot tone={a.intro.play && activeFps != null && activeFps < 50 ? "gold" : "green"} /> <span className="ml-1 text-ink">{take.length.toFixed(1)} s</span> · {take.width} × {take.height} · recorded {take.recorded}{activeFps != null ? ` · ${activeFps} fps active` : ` · ${take.capture_fps} fps average (includes still pauses)`}</p>
            ) : <p className="mb-2.5 text-mute"><Dot tone="faint" /> <span className="ml-1">No take for these settings yet.</span></p>}
            {a.intro.play && activeFps != null && activeFps < 50 && (
              <p className="mb-2.5 text-[12px] text-gold">The active frames are slow. Use 6 cores and record one take at a time for smoother ink.</p>
            )}
            {take?.frame_p90_ms != null && <p className="mb-2.5 text-[11.5px] text-faint">90% of active frame intervals are within {take.frame_p90_ms} ms. Still holds are excluded.</p>}
            {takeShort && (
              <p className="mb-2.5 text-[12px] text-orange">This edit needs a longer take. The recording length is set to include it; record the full range to preview and export the added verses.</p>
            )}
            {take && a.intro.play && Math.max(...spec.edit.map((p) => p.out)) <= take.lead && <p className="mb-2.5 text-[12px] text-gold">This edit ends during the opening. Use the whole take to include playback.</p>}
            {take && <Button size="sm" className="mb-2.5" onClick={wholeTake}>Use whole take · {take.length.toFixed(1)} s</Button>}
            {job?.state === "error" && <p className="mb-2.5 rounded border border-danger/40 bg-danger/10 p-2 text-[12px] text-danger">Last attempt failed: {job.error}</p>}
            <div className="flex flex-wrap items-center gap-2">
              <Button variant={take ? "default" : "primary"} disabled={!usable.length} onClick={() => record(serial === "auto" ? null : serial, !!take)}>
                {Icon.rec} {takeShort ? "Record full range" : take ? "Record again" : "Record take"}
              </Button>
              <div className="min-w-[130px] flex-1">
                <Select value={serial} onChange={setSerial}
                  options={[["auto", usable.length ? "Any free emulator" : "No emulator ready"], ...usable.map((e): [string, string] => [e.serial, `${e.serial}${e.job ? " (busy)" : ""}`])]} />
              </div>
            </div>
            {!usable.length && (
              <p className="mt-2.5 text-[12px] text-faint">Takes are recorded on an emulator with the app installed. <button className="text-gold underline" onClick={() => openPanel("emulators")}>Open Emulators</button></p>
            )}
          </div>
        )}
      </Section>

      <Section title="Edit" right={<span className="num text-[11.5px] text-faint">{spec.duration.toFixed(2)} s</span>}>
        <VerseRangeEditor />
        <div className="mb-1 grid grid-cols-[22px_minmax(0,1fr)_minmax(0,1fr)_26px] gap-1.5 text-[11px] text-faint"><span>#</span><span>In (take s)</span><span>Out (take s)</span><span /></div>
        {raw.edit.map((p: { in: number; out: number }, i: number) => (
          <div key={i} className={`mb-1.5 grid grid-cols-[22px_minmax(0,1fr)_minmax(0,1fr)_26px] items-center gap-1.5 ${sel?.type === "piece" && sel.i === i ? "text-blue" : ""}`}>
            <button className="text-left" onClick={() => select({ type: "piece", i })}>{i + 1}</button>
            <TextInput type="number" step={0.01} value={p.in} onCommit={(v) => mutate((r) => E.trimPiece(r, i, "in", parseFloat(v), tl?.take))} />
            <TextInput type="number" step={0.01} value={p.out} onCommit={(v) => mutate((r) => E.trimPiece(r, i, "out", parseFloat(v), tl?.take))} />
            <button className="grid h-7 place-items-center text-faint hover:text-danger disabled:opacity-30" disabled={raw.edit.length <= 1}
              onClick={() => mutate((r) => { E.deletePiece(r, i); })} title="Delete this piece">{Icon.x}</button>
          </div>
        ))}
        <div className="mt-2 flex flex-wrap gap-2">
          <Button size="sm" onClick={() => split()}>{Icon.scissors} Split at playhead</Button>
          <Button size="sm" variant="ghost" onClick={wholeTake} title="One piece: the whole take">Whole take</Button>
        </div>
        <p className="mt-2 text-[11.5px] leading-relaxed text-faint">{take ? (a.intro.play ? `The recitation starts ${take.lead.toFixed(1)} s into the take.` : "This take records the screen sequence without recitation.") : "The take opens on its opening, then the recitation."} Pieces play back to back and the sound is cut with the picture; keys stay where they are in video time.{tl?.take != null && ` The take is ${tl.take.toFixed(1)} s long.`}</p>
      </Section>
      {name && <p className="break-words text-[11px] text-faint">Spec: specs/{name}.json</p>}
    </>
  );
}

// Live progress of a capture: the steps so far, the bar, and the emulator's screen.
export function CaptureCard({ job, compact }: { job: Job; compact?: boolean }) {
  const steps = [["check", "Check emulator"], ["prepare", "Prepare device"], ["reciter", "Reciter"], ["customize", "Theme & layout"], ["open", "Open the verse"], ["opening", "Recording sequence"], ["record", "Record"], ["conform", "Sync & conform"]];
  const seen = new Set(job.stages.map((s) => s.key)), current = job.stage?.key;
  return (
    <div className="mb-3 rounded-lg border border-orange/40 bg-orange/[0.06] p-3">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <span className="size-2 rounded-full bg-orange animate-pulse-soft" />
        <span className="font-semibold">{job.state === "queued" ? "Waiting for the emulator" : "Recording a take"}</span>
        <span className="text-faint">on {job.serial}</span>
        <div className="flex-1" />
        <Button size="sm" variant="ghost" onClick={() => cancelJob(job.id)}>Cancel</Button>
      </div>
      <Progress value={job.progress} tone="gold" indeterminate={job.state === "queued"} />
      <p className="num mt-1.5 break-words text-[12px] text-mute">{job.msg}</p>
      {!compact && (
        <div className="mt-3 flex gap-3">
          <ol className="flex-1 space-y-1 text-[12px]">
            {steps.map(([k, label]) => (
              <li key={k} className={`flex items-center gap-1.5 ${k === current ? "text-ink" : seen.has(k) ? "text-green" : "text-faint"}`}>
                <span className="w-3.5">{k === current ? "▸" : seen.has(k) ? Icon.check : "·"}</span>{label}
              </li>
            ))}
          </ol>
          {job.serial && <LiveScreen serial={job.serial} />}
        </div>
      )}
    </div>
  );
}

export function LiveScreen({ serial, width = 92, every = 1000 }: { serial: string; width?: number; every?: number }) {
  const [src, setSrc] = useState(api.screenUrl(serial));
  useEffect(() => { const id = setInterval(() => setSrc(api.screenUrl(serial)), every); return () => clearInterval(id); }, [serial, every]);
  return <img src={src} alt={`${serial} screen`} style={{ width }} className="h-fit max-w-full shrink-0 rounded-md border border-line bg-black" onError={(e) => { (e.target as HTMLImageElement).style.opacity = "0.2"; }} />;
}

export const emulatorTone = (e: Emulator): "green" | "gold" | "orange" | "faint" | "danger" =>
  e.error ? "danger" : !e.booted ? "faint" : e.job ? "orange" : e.app ? "green" : "gold";

// ------------------------------------------------------------ look

function Look() {
  const meta = useStore((s) => s.meta)!, spec = useStore((s) => s.spec)!, raw = useStore((s) => s.raw)!;
  const t = useTime();
  const slider = (label: string, path: string, value: number, min: number, max: number, step: number, hint?: string) => (
    <Field label={label} hint={hint}><Slider label={label} value={value} min={min} max={max} step={step} onChange={(v) => set(path, v, "f:" + path)} onDone={endGesture} /></Field>
  );
  const property = (name: string, hint?: string) => {
    const d = meta.props[name], animated = !!spec.tracks[name]?.length;
    return <Field label={d.label} hint={animated ? `${hint ?? ""} Animated · changes apply at the playhead.`.trim() : hint}>
      <Slider label={d.label} value={valueAt(spec, meta.props, name, t)} min={d.min} max={d.max} step={d.step}
        onChange={(v) => setProp(name, v, "look:" + name)} onDone={endGesture} />
    </Field>;
  };
  return (
    <>
      <Section title="Canvas">
        <Field label="Video name"><TextInput value={spec.title} placeholder="What this video is" onCommit={(v) => set("title", v)} /></Field>
        <Field label="Aspect"><Segmented size="sm" value={spec.aspect} options={Object.keys(meta.aspects).map((a) => [a, a])} onChange={(v) => set("aspect", v)} /></Field>
        <Field label="Frame rate"><Segmented size="sm" value={String(spec.fps)} options={[["60", "60 fps"], ["30", "30 fps"]]} onChange={(v) => set("fps", +v)} /></Field>
        {slider("Motion blur", "motion_blur", spec.motion_blur, 0, 2, 0.05, "Softens fast camera moves in exports. Use Exact frame to preview it.")}
      </Section>
      <Section title="Background">
        <div className="mb-3 grid grid-cols-3 gap-1.5">
          {Object.entries(meta.backgrounds).map(([id, b]) => (
            <button key={id} onClick={() => set("background.id", id)} title={`${b.title} · suits ${b.suits.join(", ")}`} aria-label={b.label ?? b.title} aria-pressed={id === spec.background.id}
              className={`relative aspect-square overflow-hidden rounded-md border-2 ${id === spec.background.id ? "border-gold" : "border-transparent hover:border-line2"}`}>
              <img src={b.thumb} alt="" loading="lazy" className="size-full object-cover blur-[1.5px]" />
              <span className="absolute inset-x-0 bottom-0 line-clamp-2 bg-black/65 px-1.5 py-1 text-left text-[10px] leading-tight">{b.label ?? b.title}</span>
            </button>
          ))}
        </div>
        <h4 className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-faint">Image</h4>
        {property("bg_blur", "0 keeps the background sharp. More blur keeps attention on the phone.")}
        {property("bg_brightness")}
        {slider("Saturation", "background.saturation", spec.background.saturation, 0, 1.6, 0.05)}
        {slider("Vignette", "background.vignette", spec.background.vignette, 0, 0.8, 0.01)}
        <h4 className="mb-2 mt-4 text-[10px] font-semibold uppercase tracking-wider text-faint">Movement</h4>
        {slider("Motion speed", "background.speed", spec.background.speed, 0.1, 1.5, 0.05)}
        {slider("Parallax", "background.parallax", spec.background.parallax, 0, 1, 0.01, "How much of the camera's moves the background follows.")}
      </Section>
      <Section title="Phone">
        <PhoneLookPreview />
        <Field label="Colour"><Select value={raw.phone?.color ?? "auto"} options={[["auto", "Match the theme"], ...meta.phones.map((c): [string, string] => [c, c[0].toUpperCase() + c.slice(1)])]} onChange={(v) => set("phone.color", v)} /></Field>
        {slider("Base size", "phone.height", spec.phone.height, 0.4, 1.2, 0.01, "The phone's height as a share of the frame, before zoom.")}
        {slider("Shadow", "phone.shadow", spec.phone.shadow, 0, 1, 0.01)}
      </Section>
      <Section title="Sound">
        {slider("Gain (dB)", "audio.gain_db", spec.audio.gain_db, -12, 12, 0.5)}
        {slider("Fade in (s)", "audio.fade_in", spec.audio.fade_in, 0, 3, 0.1, "Starts with the first sound: takes open on a silent second.")}
        {slider("Fade out (s)", "audio.fade_out", spec.audio.fade_out, 0, 4, 0.1, "Ends with the video.")}
        <p className="text-[11.5px] text-faint">The emulator's own recording, normalised to −16 LUFS on export. The preview plays the gain and fades; drag the handles on the timeline's Sound lane to set the fades there.</p>
      </Section>
    </>
  );
}
