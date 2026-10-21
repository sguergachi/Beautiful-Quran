// The studio server's API (server.py). Errors carry the server's message.
import type { Emulator, ExportOptions, Geo, Job, Meta, Plate, RawSpec, SpecStatus, Timeline, VersePlan } from "./types.ts";

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
const post = <T,>(path: string, body: unknown = {}) => call<T>(path, { method: "POST", body: JSON.stringify(body) });

export const api = {
  meta: () => call<Meta>("/api/meta"),
  specs: () => call<SpecStatus[]>("/api/specs"),
  spec: (name: string) => call<{ raw: RawSpec; status: SpecStatus }>(`/api/spec/${name}`),
  save: (name: string, body: string) => call<{ ok: true; status: SpecStatus }>(`/api/spec/${name}`, { method: "PUT", body }),
  remove: (name: string) => call<{ ok: true }>(`/api/spec/${name}`, { method: "DELETE" }),
  create: (name: string, from?: string) => post<{ ok: true }>(`/api/new/${name}`, from ? { from } : {}),
  copy: (from: string, to: string) => post<{ ok: true }>(`/api/copy/${from}/${to}`),
  rename: (from: string, to: string) => post<{ ok: true }>(`/api/rename/${from}/${to}`),
  geometry: (name: string) => call<Geo>(`/api/geometry/${name}`),
  timeline: (name: string, raw: RawSpec) => post<Timeline>(`/api/timeline/${name}`, raw),
  verseRange: (name: string, raw: RawSpec, start: number, end: number) => post<VersePlan>(`/api/verse-range/${name}`, { raw, start, end }),
  plate: (name: string, raw: string) => call<Plate>(`/api/plate/${name}`, { method: "POST", body: raw }),
  stillUrl: (name: string, t: number) => `/api/still/${name}?t=${t.toFixed(3)}&scale=0.5&_=${Date.now()}`,
  jobs: () => call<Job[]>("/api/jobs"),
  job: (id: string) => call<Job>(`/api/jobs/${id}`),
  cancel: (id: string) => post<Job>(`/api/jobs/${id}/cancel`),
  clearJobs: () => post<{ ok: true }>("/api/jobs/clear"),
  capture: (name: string, opts: { force?: boolean; serial?: string | null }) => post<Job>(`/api/capture/${name}`, opts),
  render: (name: string, opts: ExportOptions) => post<Job>(`/api/render/${name}`, opts),
  emus: (fresh = false) => call<{ emulators: Emulator[]; booting: Job[] }>(`/api/emus${fresh ? "?fresh=1" : ""}`),
  bootEmu: (cores: number) => post<Job>("/api/emus/boot", { cores }),
  prepareEmu: (serial: string) => post<Job>(`/api/emus/${serial}/prepare`),
  stopEmu: (serial: string) => post<{ ok: true }>(`/api/emus/${serial}/stop`),
  screenUrl: (serial: string) => `/api/emus/${serial}/screen.jpg?_=${Date.now()}`,
};
