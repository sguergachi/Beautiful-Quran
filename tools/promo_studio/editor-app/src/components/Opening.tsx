// An ordered on-camera sequence. Its measured playback offset belongs to the
// take, rather than an estimate baked into the editor's source clock.
import { useState } from "react";
import { openingSteps } from "../lib/opening.ts";
import type { RecordingStep } from "../lib/types.ts";
import { mutate, useStore } from "../state/store.ts";
import { Button, Field, Icon, Section, Segmented, Select, TextInput } from "./ui.tsx";

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
  return <Section title="Recording sequence">
    <p className="mb-3 text-[11.5px] leading-relaxed text-mute">Add screens in the order you want them filmed. Each time is a hold after arriving. The opening ceremony plays in full; navigation is recorded too.</p>
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
    </ol>
    <div className="my-3"><Select value={add} options={[["", "+ Add recording step"], ...STEPS.filter(([k]) => k !== "roots" || a.layout === "Scroll" && a.view !== "English")]} disabled={steps.length >= 24} onChange={(v) => {
      setAdd(""); if (v) update([...steps, { kind: v as RecordingStep["kind"], seconds: v === "roots" ? 3 : 2, ...(v === "open" ? { via: "tap" as const } : {}), ...(v === "roots" ? { word: 1 } : {}) }]);
    }} /></div>
    <Field label="Repeat mode"><Segmented size="sm" value={a.repeat} options={[["off", "Off"], ["ayah", "This verse"], ["range", "Range"]]} onChange={(repeat) => mutate((r) => { r.app = { ...r.app, repeat, repeat_to: repeat === "range" ? Math.min(count, Math.max(a.ayah, a.repeat_to ?? a.ayah + 1)) : null }; })} /></Field>
    {a.repeat === "range" && <Field label="Repeat through"><Select value={a.repeat_to ?? a.ayah} options={Array.from({ length: Math.max(0, count - a.ayah + 1) }, (_, i): [number, string] => [a.ayah + i, `${a.surah}:${a.ayah + i}`])} onChange={(v) => mutate((r) => { r.app = { ...r.app, repeat_to: +v }; })} /></Field>}
    <label className="my-3 flex items-center gap-2"><input type="checkbox" checked={a.intro.play} onChange={(e) => mutate((r) => { r.app = { ...r.app, intro: { ...r.app?.intro, play: e.target.checked } }; })} />Play recitation after the sequence</label>
    <Field label={a.intro.play ? "Wait before play" : "Final hold"} hint={a.intro.play ? "Settle on the verse before playback starts." : "Finish on the last screen without starting recitation."}>
      <TextInput aria-label="Wait before playback seconds" type="number" min={0} max={30} step={0.5} value={a.intro.dwell} onCommit={(v) => { const n = +v; if (n >= 0 && n <= 30) mutate((r) => { r.app = { ...r.app, intro: { ...r.app?.intro, dwell: n } }; }); }} />
    </Field>
    <p className="text-[11px] leading-relaxed text-faint">{a.intro.play ? ` ${steps.reduce((n, step) => n + step.seconds, a.intro.dwell).toFixed(1)} s of holds, plus navigation and ceremony. ` : ""}Changing this sequence needs its own take. The recording measures the actual start of recitation.</p>
  </Section>;
}
