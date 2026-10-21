// Exact controls for the selected timeline piece, separate from take setup.
import * as E from "../lib/edit.ts";
import { pieceStart } from "../lib/model.ts";
import { clock, useTime } from "../state/clock.ts";
import { deleteSelection, mutate, select, split, useStore } from "../state/store.ts";
import { Button, Field, Icon, TextInput } from "./ui.tsx";

export function ClipEditor() {
  const spec = useStore((s) => s.spec)!, sel = useStore((s) => s.sel), take = useStore((s) => s.tl?.take);
  const t = useTime();
  if (sel?.type !== "piece" || !spec.edit[sel.i]) return null;
  const i = sel.i, piece = spec.edit[i], start = pieceStart(spec, i), end = start + piece.out - piece.in;
  return <div data-clip-editor>
    <div className="mb-3 flex items-center justify-between gap-2">
      <h3 className="font-semibold">Clip {i + 1} of {spec.edit.length}</h3>
      <button title="Deselect clip (Esc)" onClick={() => select(null)} className="text-faint hover:text-ink">{Icon.x}</button>
    </div>
    <p className="num mb-4 text-[12px] text-mute">{(piece.out - piece.in).toFixed(2)} s · video {start.toFixed(2)}–{end.toFixed(2)} s</p>
    {(["in", "out"] as const).map((side) => <Field key={side} label={side === "in" ? "Source in (s)" : "Source out (s)"}>
      <TextInput aria-label={`Clip source ${side}`} type="number" step={1 / spec.fps} min={0} value={piece[side]}
        onCommit={(v) => { const n = +v; if (Number.isFinite(n)) mutate((r) => E.trimPiece(r, i, side, n, take)); }} />
    </Field>)}
    {take != null && piece.out > take + .05 && <p className="mb-3 text-[12px] text-orange">This clip extends past the take. Record the full range to include it.</p>}
    <div className="mt-4 flex flex-wrap gap-2">
      <Button size="sm" onClick={() => { clock.pause(); clock.seek(start); }}>Go to start</Button>
      <Button size="sm" onClick={() => { clock.pause(); clock.seek(Math.max(start, end - 1 / spec.fps)); }}>Go to end</Button>
      <Button size="sm" disabled={t <= start + .05 || t >= end - .05} onClick={() => split()}>{Icon.scissors} Split here</Button>
      <Button size="sm" variant="danger" disabled={spec.edit.length <= 1} onClick={deleteSelection}>{Icon.trash} Delete clip</Button>
    </div>
    <p className="mt-4 text-[11.5px] leading-relaxed text-faint">Source times refer to the recorded take. Picture and sound trim together; animation keys stay in video time.</p>
  </div>;
}
