// Version history: every version a save replaced, every deleted video, kept by
// the server outside the repo, so no edit is ever unrecoverable. Restoring keeps
// what it replaces.
import { useEffect, useState } from "react";
import { api } from "../lib/api.ts";
import { ago, describeVersion, reasonLabel, type Version } from "../lib/safety.ts";
import { open, openPanel, refreshSpecs, toast, useStore } from "../state/store.ts";
import { Drawer } from "./Panels.tsx";
import { Button } from "./ui.tsx";

type Deleted = { name: string; id: string; at: number; title: string | null; pieces: number; keys: number; size: number };

export function HistoryPanel() {
  const name = useStore((s) => s.name), saveState = useStore((s) => s.saveState);
  const [versions, setVersions] = useState<Version[] | null>(null), [deleted, setDeleted] = useState<Deleted[]>([]);
  const [error, setError] = useState<string | null>(null), [sure, setSure] = useState<string | null>(null), [busy, setBusy] = useState(false);
  const load = () => {
    if (name) api.history(name).then((r) => { setVersions(r.versions); setError(null); }, (e: Error) => setError(e.message));
    api.trash().then((r) => setDeleted(r.deleted), () => {});
  };
  useEffect(load, [name, saveState === "saved"]);

  const restore = async (target: string, id: string, reopen: boolean) => {
    setBusy(true);
    try {
      await api.restore(target, id);
      await refreshSpecs();
      await open(target, { discard: reopen });
      toast("Restored. The version it replaced is still in the history.", "ok");
      openPanel("history");
      setSure(null);
      load();
    } catch (e) { toast("Could not restore: " + (e as Error).message, "error"); }
    setBusy(false);
  };

  return (
    <Drawer title="Version history" onClose={() => openPanel(null)} width={460}>
      <p className="mb-3 text-[12px] leading-relaxed text-faint">Every save keeps the version it replaced, outside the project, so an edit is never lost to a bad save, a deleted video or a second window. Restoring keeps what it replaces.</p>
      {error && <p className="mb-2 text-[12px] text-danger">{error}</p>}
      {name && (
        <>
          <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-mute">This video</h3>
          {versions == null && <p className="text-mute">Loading…</p>}
          {versions?.length === 0 && <p className="text-mute">No earlier versions yet: they appear as you edit.</p>}
          <ul className="space-y-1.5">
            {versions?.map((v) => (
              <li key={v.id} className="rounded-lg border border-line bg-panel2 px-3 py-2">
                <div className="flex items-baseline gap-2">
                  <span className="font-medium">{ago(v.at)}</span>
                  <span className="text-[11.5px] text-faint">{new Date(v.at * 1000).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit" })}</span>
                  <div className="flex-1" />
                  {sure === v.id
                    ? <><Button size="sm" variant="primary" disabled={busy} onClick={() => restore(name, v.id, true)}>Restore this</Button><Button size="sm" variant="ghost" onClick={() => setSure(null)}>Cancel</Button></>
                    : <Button size="sm" variant="subtle" disabled={busy || saveState === "conflict"} onClick={() => setSure(v.id)}>Restore…</Button>}
                </div>
                <p className="num mt-0.5 text-[11.5px] text-mute">{reasonLabel(v.reason)} · {describeVersion(v)}{v.title ? ` · ${v.title}` : ""}</p>
              </li>
            ))}
          </ul>
        </>
      )}
      {deleted.length > 0 && (
        <>
          <h3 className="mb-2 mt-5 text-[11px] font-semibold uppercase tracking-wider text-mute">Deleted videos</h3>
          <ul className="space-y-1.5">
            {deleted.map((d) => (
              <li key={d.name} className="flex items-center gap-2 rounded-lg border border-line bg-panel2 px-3 py-2">
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium" title={d.title || d.name}>{d.title || d.name}</p>
                  <p className="num text-[11.5px] text-mute">Deleted {ago(d.at)} · {describeVersion(d)}</p>
                </div>
                <Button size="sm" variant="subtle" disabled={busy} onClick={() => restore(d.name, d.id, false)}>Restore</Button>
              </li>
            ))}
          </ul>
        </>
      )}
    </Drawer>
  );
}
