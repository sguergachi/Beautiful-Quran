// The studio server's API (server.py). Errors carry the server's message.
import type { Emulator, ExportFile, ExportOptions, Geo, Job, Meta, Plate, RawSpec, SpecStatus, Timeline, VersePlan } from "./types.ts";
import type { Version } from "./safety.ts";

async function call<T>(path: string, opts: RequestInit = {}): Promise<T> {
  let r: Response;
  try {
    r = await fetch(path, { headers: { "Content-Type": "application/json" }, ...opts });
  } catch {
    throw new Error("the studio server is not answering (is `studio.py serve` running?)");
  }
  const j = await r.json().catch(() => ({}));
  if (!r.ok || (j && (j as { error?: string }).error)) throw new Error((j as { error?: string }).error || r.statusText);
  return j as T;
}
/** A save made against a version that is no longer the current one: the server refused it and says what is there now. */
export class SaveConflict extends Error {
  raw: RawSpec;
  rev: string;
  constructor(raw: RawSpec, rev: string) { super("This video was changed elsewhere (another tab or window)."); this.raw = raw; this.rev = rev; }
}

/** PUT a spec. `rev` is the revision the edit was made against (If-Match): a stale one is refused with a SaveConflict, never written. */
async function putSpec(name: string, body: string, rev: string | null, keepalive = false): Promise<{ ok: true; rev: string; status: SpecStatus }> {
  let r: Response;
  try {
    r = await fetch(`/api/spec/${name}`, { method: "PUT", body, keepalive, headers: { "Content-Type": "application/json", ...(rev ? { "If-Match": rev } : {}) } });
  } catch {
    throw new Error("the studio server is not answering; your edits are kept in this browser and saved when it is back");
  }
  const j = await r.json().catch(() => ({}));
  if (r.status === 409 && j.conflict) throw new SaveConflict(j.raw, j.rev);
  if (!r.ok || j.error) throw new Error(j.error || r.statusText);
  return j;
}
const post = <T,>(path: string, body: unknown = {}) => call<T>(path, { method: "POST", body: JSON.stringify(body) });

export const api = {
  meta: () => call<Meta>("/api/meta"),
  specs: () => call<SpecStatus[]>("/api/specs"),
  spec: (name: string) => call<{ raw: RawSpec; rev: string; status: SpecStatus }>(`/api/spec/${name}`),
  save: putSpec,
  history: (name: string) => call<{ versions: Version[]; rev: string | null }>(`/api/spec/${name}/history`),
  restore: (name: string, id: string) => post<{ ok: true; raw: RawSpec; rev: string; status: SpecStatus }>(`/api/spec/${name}/restore`, { id }),
  stash: (name: string, raw: RawSpec, reason: "unsaved" | "replaced" = "unsaved") => post<{ ok: true; id: string }>(`/api/spec/${name}/stash`, { raw, reason }),
  trash: () => call<{ deleted: (Pick<Version, "id" | "at" | "title" | "pieces" | "keys" | "size"> & { name: string })[] }>("/api/trash"),
  remove: (name: string) => call<{ ok: true }>(`/api/spec/${name}`, { method: "DELETE" }),
  removeExport: (name: string, file: ExportFile) => call<{ ok: true; exports: ExportFile[] }>(`/api/exports/${encodeURIComponent(name)}/${encodeURIComponent(file.file)}`, { method: "DELETE", body: JSON.stringify({ revision: file.revision }) }),
  create: (name: string, from?: string, title?: string) => post<{ ok: true }>(`/api/new/${name}`, { from, title }),
  copy: (from: string, to: string) => post<{ ok: true }>(`/api/copy/${from}/${to}`),
  rename: (from: string, to: string) => post<{ ok: true }>(`/api/rename/${from}/${to}`),
  geometry: (name: string) => call<Geo>(`/api/geometry/${name}`),
  timeline: (name: string, raw: RawSpec) => post<Timeline>(`/api/timeline/${name}`, raw),
  verseRange: (name: string, raw: RawSpec, start: number, end: number, take = false) => post<VersePlan>(`/api/verse-range/${name}`, { raw, start, end, take }),
  plate: (name: string, raw: string) => call<Plate>(`/api/plate/${name}`, { method: "POST", body: raw }),
  stillUrl: (name: string, t: number) => `/api/still/${name}?t=${t.toFixed(3)}&scale=0.5&_=${Date.now()}`,
  jobs: () => call<Job[]>("/api/jobs"),
  job: (id: string) => call<Job>(`/api/jobs/${id}`),
  cancel: (id: string) => post<Job>(`/api/jobs/${id}/cancel`),
  clearJobs: () => post<{ ok: true }>("/api/jobs/clear"),
  capture: (name: string, opts: { force?: boolean; serial?: string | null }) => post<Job>(`/api/capture/${name}`, opts),
  render: (name: string, opts: ExportOptions) => post<Job>(`/api/render/${name}`, opts),
  emus: (fresh = false) => call<{ emulators: Emulator[]; booting: Job[] }>(`/api/emus${fresh ? "?fresh=1" : ""}`),
  apk: () => call<Meta["apk_info"]>("/api/apk"),
  buildApk: () => post<Job>("/api/apk/build"),
  bootEmu: (cores: number) => post<Job>("/api/emus/boot", { cores }),
  prepareEmu: (serial: string) => post<Job>(`/api/emus/${serial}/prepare`),
  stopEmu: (serial: string) => post<{ ok: true }>(`/api/emus/${serial}/stop`),
  screenUrl: (serial: string) => `/api/emus/${serial}/screen.jpg?_=${Date.now()}`,
};
