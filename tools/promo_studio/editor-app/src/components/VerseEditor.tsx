import { nextVerseRange, verseRange, type VerseAction } from "../lib/verses.ts";
import { changeVerse, editVerses, loadTimeline, useStore } from "../state/store.ts";
import { openMenu } from "./Menu.tsx";
import { Button, Field, Select } from "./ui.tsx";

const ACTIONS: [VerseAction, string, string][] = [
  ["add-start", "+", "Add previous verse"], ["remove-start", "−", "Remove first verse"],
  ["remove-end", "−", "Remove last verse"], ["add-end", "+", "Add next verse"],
];

function useVerseEdit() {
  const spec = useStore((s) => s.spec), tl = useStore((s) => s.tl), meta = useStore((s) => s.meta);
  const busy = useStore((s) => s.verseBusy), error = useStore((s) => s.verseError);
  const count = meta?.surahs.find((s) => s.id === spec?.app.surah)?.ayahs ?? 0;
  const linear = spec?.app.repeat === "off" && spec.app.intro.play;
  const range = spec && tl && linear ? verseRange(spec, tl.verses) : null;
  const can = (action: VerseAction) => !busy && !!nextVerseRange(range, action, count);
  return { spec, tl, count, range, busy, error, can };
}

/** The same verse actions live on the sticky timeline label and in the edit. */
export function TimelineVerseControls() {
  const { can, busy, error } = useVerseEdit();
  return <div className="flex w-full items-center gap-1">
    <button className="min-w-0 flex-1 text-left" title={error || (busy ? "Finding verse boundaries…" : "Verse actions: add or remove at either end")} onClick={(e) => openMenu(e, [
      { head: "Whole verses" },
      ...ACTIONS.map(([action, sign, label]) => ({ label: `${sign} ${label}`, disabled: !can(action), run: () => changeVerse(action) })),
    ])}>{busy ? "Verses…" : "Verses"}</button>
    {(["remove-end", "add-end"] as VerseAction[]).map((action) => <button key={action}
      className="grid size-4 shrink-0 place-items-center rounded text-[15px] text-gold hover:bg-raised disabled:text-faint disabled:opacity-40"
      disabled={!can(action)} title={action === "add-end" ? "Add next verse" : "Remove last verse"} onClick={() => changeVerse(action)}>
      {action === "add-end" ? "+" : "−"}
    </button>)}
  </div>;
}

export function VerseRangeEditor() {
  const { spec, tl, count, range, busy, error, can } = useVerseEdit();
  if (!spec) return null;
  if (!spec.app.intro.play) return <p className="mb-3 text-[12px] text-mute">Screen-only take. Trim the recorded sequence below.</p>;
  if (spec.app.repeat !== "off") return <p className="mb-3 text-[12px] text-mute">Choose the verses to loop under Recording sequence. Trim repeated playback below.</p>;
  const current = range ?? { start: spec.app.ayah, end: spec.app.ayah };
  const options: [number, string][] = Array.from({ length: count }, (_, i) => [i + 1, `${spec.app.surah}:${i + 1}`]);
  return <div className="mb-4" aria-busy={busy}>
    <Field label="Start verse"><Select disabled={busy || !tl} value={current.start} options={options}
      onChange={(v) => void editVerses(+v, Math.max(+v, current.end))} /></Field>
    <Field label="Stop verse"><Select disabled={busy || !tl} value={current.end} options={options}
      onChange={(v) => void editVerses(Math.min(current.start, +v), +v)} /></Field>
    <div className="grid grid-cols-2 gap-1.5">
      {ACTIONS.map(([action, sign, label]) => <Button key={action} size="sm" disabled={!can(action)}
        className="!h-auto min-h-7 !whitespace-normal text-left" onClick={() => changeVerse(action)}>
        <span className="shrink-0 text-gold">{sign}</span><span>{label}</span>
      </Button>)}
    </div>
    {busy && <p role="status" className="mt-2 text-[12px] text-gold">Finding verse boundaries…</p>}
    {error && <p role="alert" className="mt-2 break-words text-[12px] text-danger">{error}</p>}
    {!tl && <p className="mt-2 text-[12px] text-mute">Loading verse boundaries. <button className="text-gold underline" onClick={() => void loadTimeline()}>Retry</button></p>}
    {spec.edit.length > 1 && <p className="mt-2 text-[11.5px] text-faint">Start/stop selects one continuous range. The +/− actions keep your cuts in between.</p>}
  </div>;
}
