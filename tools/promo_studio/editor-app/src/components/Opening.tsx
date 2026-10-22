// An ordered on-camera sequence. Its measured playback offset belongs to the
// take, rather than an estimate baked into the editor's source clock.
import { useState } from "react";
import { openingSteps } from "../lib/opening.ts";
import type { RecordingStep } from "../lib/types.ts";
import { mutate, useStore } from "../state/store.ts";
import { VerseRangeEditor } from "./VerseEditor.tsx";
import { Button, Field, Icon, Segmented, Select, TextInput } from "./ui.tsx";

const GOTO_HINT: Record<"scroll" | "rail" | "jump", string> = {
  scroll: "Swipe down the page to the start verse, as a reader would.",
  rail: "Scrub the ayah rail at the page's edge to the start verse; the page jumps there.",
  jump: "Cut to the verse (back to Home, then open it directly).",
};

const STEPS: [RecordingStep["kind"], string][] = [["cover", "Opening ceremony"], ["home", "Chapter list / Home"], ["open", "Open chapter"], ["header", "Chapter header"], ["settings", "Settings"], ["roots", "Root word viewer"], ["repeat", "Repeat settings"], ["wait", "Wait on current screen"]];
const HINT: Record<RecordingStep["kind"], string> = {
  cover: "Launch the app, show the full opening ceremony, then hold on Home.", home: "Show Home with the chapter list.",
  open: "Open the selected chapter using the route below.", header: "Show the chapter's beginning before returning to your verse.",
  settings: "Open Settings. The next step decides where to go afterwards.", roots: "Hold an Arabic word to open its root viewer (Scroll layout).",
  repeat: "Show the repeat picker and apply the repeat choice below.", wait: "Stay on whichever screen the previous step left open.",
};
export function Opening() {
  const spec = useStore((s) => s.spec)!, meta = useStore((s) => s.meta)!;
  const [add, setAdd] = useState("");
  const a = spec.app, steps = openingSteps(spec), count = meta.surahs.find((s) => s.id === a.surah)?.ayahs ?? a.ayah;
  const update = (next: RecordingStep[]) => mutate((r) => { r.app = { ...r.app, intro: { ...r.app?.intro, steps: next } }; });
  const patch = (i: number, value: Partial<RecordingStep>) => update(steps.map((s, n) => n === i ? { ...s, ...value } : s));
  const move = (i: number, delta: number) => { const next = [...steps]; [next[i], next[i + delta]] = [next[i + delta], next[i]]; update(next); };
  const startVerse = <Field label="Start verse"><TextInput aria-label="Start verse" type="number" min={1} max={count} value={a.ayah} onCommit={(v) => {
    const n = +v; if (Number.isInteger(n) && n >= 1 && n <= count) mutate((r) => { r.app = { ...r.app, ayah: n, ...(a.repeat === "range" ? { repeat_to: Math.max(n, a.repeat_to ?? n) } : {}) }; });
  }} /></Field>;
  // A chapter opened from the list, or its header, leaves the reader at the top: the take travels to the verse.
  const travels = a.layout === "Scroll" && a.ayah > 1 && steps.some((s) => s.kind === "header" || (s.kind === "open" && (s.via ?? "direct") !== "direct"));
  const addStep = <Select value={add} options={[["", "+ Add screen"], ...STEPS.filter(([k]) => k !== "roots" || a.layout === "Scroll" && a.view !== "English")]} disabled={steps.length >= 24} onChange={(v) => {
    setAdd(""); if (v) update([...steps, { kind: v as RecordingStep["kind"], seconds: v === "roots" ? 3 : 2, ...(v === "open" ? { via: "tap" as const } : {}), ...(v === "roots" ? { word: 1 } : {}) }]);
  }} />;
  return <div>
    <p className="mb-3 text-[11.5px] leading-relaxed text-mute">Build the take in order: show screens, then play your verses or hold the final screen. Navigation and pauses are recorded too.</p>
    <ol className="space-y-2">
      {steps.map((step, i) => <li key={i} className="rounded border border-line bg-panel2 p-2.5">
        <div className="mb-2 flex min-w-0 items-center gap-1">
          <span className="num mr-1 text-faint">{i + 1}.</span><span className="min-w-0 flex-1 font-medium">{STEPS.find(([k]) => k === step.kind)?.[1]}</span>
          <Button size="sm" variant="ghost" className="!px-1" title="Move step up" disabled={i === 0} onClick={() => move(i, -1)}>↑</Button>
          <Button size="sm" variant="ghost" className="!px-1" title="Move step down" disabled={i === steps.length - 1} onClick={() => move(i, 1)}>↓</Button>
          <Button size="sm" variant="ghost" className="!px-1" title="Remove step" onClick={() => update(steps.filter((_, n) => n !== i))}>{Icon.x}</Button>
        </div>
        <p className="mb-2 text-[11px] leading-snug text-faint">{HINT[step.kind]}</p>
        <label className="flex items-center gap-2 text-[12px] text-mute">Hold <div className="w-20"><TextInput aria-label={`Step ${i + 1} hold seconds`} type="number" min={0} max={30} step={0.5} value={step.seconds} onCommit={(v) => { const n = +v; if (n >= 0 && n <= 30) patch(i, { seconds: n }); }} /></div>seconds</label>
        {step.kind === "open" && <div className="mt-2"><Select value={step.via ?? "direct"} options={[["direct", "Open the verse directly"], ["tap", "Tap chapter in the list"], ["search", "Search, then open chapter"]]} onChange={(via) => patch(i, { via: via as RecordingStep["via"] })} /></div>}
        {step.kind === "roots" && <label className="mt-2 flex items-center gap-2 text-[12px] text-mute">Word <div className="w-20"><TextInput aria-label="Root viewer word position" type="number" min={1} max={100} value={step.word ?? 1} onCommit={(v) => { const n = +v; if (Number.isInteger(n) && n >= 1 && n <= 100) patch(i, { word: n }); }} /></div></label>}
      </li>)}
      <li className="py-1">{addStep}</li>
      <li className="rounded border border-gold/30 bg-gold/[0.04] p-2.5">
        <div className="mb-3 flex items-center gap-2"><span className="num text-faint">{steps.length + 1}.</span><span className="font-medium">{a.intro.play ? "Recitation" : "Hold final screen"}</span></div>
        <div className="mb-3" role="group" aria-label="Finish with"><Segmented size="sm" value={a.intro.play ? "play" : "hold"} options={[["play", "Recitation"], ["hold", "Screens only"]]} onChange={(v) => mutate((r) => { r.app = { ...r.app, intro: { ...r.app?.intro, play: v === "play" } }; })} /></div>
        {a.intro.play ? <>
          <div className="mb-3" role="group" aria-label="Repeat"><span className="mb-1.5 block text-mute">Repeat</span><Segmented size="sm" value={a.repeat} options={[["off", "Off"], ["ayah", "One verse"], ["range", "Range"]]} onChange={(repeat) => mutate((r) => { r.app = { ...r.app, repeat, repeat_to: repeat === "range" ? Math.min(count, Math.max(a.ayah, a.repeat_to ?? a.ayah + 1)) : null }; })} /></div>
          {a.repeat === "off" ? <VerseRangeEditor /> : <>
            {startVerse}
            {a.repeat === "range" && <Field label="Stop verse"><Select value={a.repeat_to ?? a.ayah} options={Array.from({ length: Math.max(0, count - a.ayah + 1) }, (_, i): [number, string] => [a.ayah + i, `${a.surah}:${a.ayah + i}`])} onChange={(v) => mutate((r) => { r.app = { ...r.app, repeat_to: +v }; })} /></Field>}
            <Field label="Play for (s)"><TextInput aria-label="Recitation duration seconds" type="number" min={5} step={1} value={a.seconds} onCommit={(v) => { const n = +v; if (Number.isFinite(n) && n >= 5) mutate((r) => { r.app = { ...r.app, seconds: n }; }); }} /></Field>
          </>}
        </> : <>
          {startVerse}
          <p className="mb-3 text-[11px] leading-snug text-faint">Chapter and verse choose the page shown during reader, root viewer and repeat steps. The take finishes on the last screen above.</p>
          <Field label="Minimum (s)" hint="The full sequence is always included. Add time here to keep its final screen longer."><TextInput aria-label="Minimum take duration seconds" type="number" min={5} step={1} value={a.seconds} onCommit={(v) => { const n = +v; if (Number.isFinite(n) && n >= 5) mutate((r) => { r.app = { ...r.app, seconds: n }; }); }} /></Field>
        </>}
        {travels && <div className="mb-3" role="group" aria-label="Go to the verse">
          <span className="mb-1.5 block text-mute">Go to the verse</span>
          <Segmented size="sm" value={a.intro.goto ?? "scroll"} onChange={(goto) => mutate((r) => { r.app = { ...r.app, intro: { ...r.app?.intro, goto } }; })}
            options={[["scroll", "Scroll"], ["rail", "Ayah rail"], ["jump", "Jump"]]} />
          <p className="mt-1 text-[11px] leading-snug text-faint">{GOTO_HINT[a.intro.goto ?? "scroll"]}</p>
        </div>}
        <Field label={a.intro.play ? "Pause before (s)" : "Final hold (s)"} hint={a.intro.play ? "Settle on the start verse before playing." : "Hold the final screen without starting recitation."}>
          <TextInput aria-label="Wait before playback seconds" type="number" min={0} max={30} step={0.5} value={a.intro.dwell} onCommit={(v) => { const n = +v; if (n >= 0 && n <= 30) mutate((r) => { r.app = { ...r.app, intro: { ...r.app?.intro, dwell: n } }; }); }} />
        </Field>
      </li>
    </ol>
    <p className="mt-3 text-[11px] leading-relaxed text-faint">{steps.length ? "Each screen's time is its hold after arriving. The opening ceremony plays in full. " : `Starts on the reader at ${a.surah}:${a.ayah}. `}Changing screens or the start verse needs a matching recording.</p>
  </div>;
}
