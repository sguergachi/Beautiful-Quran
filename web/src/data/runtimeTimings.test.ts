import { describe, expect, it, vi } from 'vitest'
import {
  QF_TIMING_RECITERS,
  RuntimeTimingsCache,
  TIMING_REFRESH_AFTER_MS,
  validateTimingResource,
  type RuntimeTimingResource,
  type RuntimeTimingStore,
} from './runtimeTimings'

const canonical = new Map([['1:1', 2], ['2:1', 2], ['2:2', 1]])
const now = 1_000

async function revision(resource: RuntimeTimingResource) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify({
    rows: resource.rows, withheldVerseKeys: resource.withheldVerseKeys,
  })))
  resource.revision = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  return resource
}

async function resource(reciterId = 1): Promise<RuntimeTimingResource> {
  const [qfReciterId, audioSlug] = QF_TIMING_RECITERS[reciterId]!
  return revision({
    schemaVersion: 1, reciterId, qfReciterId, audioSlug, clock: 'everyayah-ms',
    sourceSha256: 'a'.repeat(64), sourceSyncSequence: 541_039, sourceSyncedAtMs: now,
    normalizerSha256: 'b'.repeat(64), revision: '',
    rows: [
      [1, 1, [[1, 10, 20], [2, 20, 30]], 10],
      [2, 1, [[1, 10, 20], [2, 20, 30], [1, 40, 50], [2, 50, 60], [2, 70, 80]], 10],
    ],
    withheldVerseKeys: ['2:2'],
  })
}

class MemoryStore implements RuntimeTimingStore {
  rows = new Map<number, RuntimeTimingResource>()
  rejectWrites = false
  async get(id: number) { return structuredClone(this.rows.get(id) ?? null) }
  async put(value: RuntimeTimingResource) {
    if (this.rejectWrites) throw new Error('storage full')
    this.rows.set(value.reciterId, structuredClone(value))
  }
  async remove(id: number) { this.rows.delete(id) }
  async clear() { this.rows.clear() }
}

function cache(store: MemoryStore, fetcher: typeof fetch, clock = () => now) {
  return new RuntimeTimingsCache('https://content.example', store, fetcher, clock, () => canonical)
}

describe('accepted runtime timing resource', () => {
  it('preserves ordered phrase repeats, same-word repeats, onset, and explicit withheld rows', async () => {
    const value = await resource()
    await validateTimingResource(value, 1, canonical)
    expect(value.rows[1]![2].map((segment) => segment[0])).toEqual([1, 2, 1, 2, 2])
    expect(value.rows[0]![3]).toBe(10)
    expect(value.withheldVerseKeys).toEqual(['2:2'])
  })

  it.each([
    ['owner', (value: RuntimeTimingResource) => { value.reciterId = 2 }],
    ['QF identity', (value: RuntimeTimingResource) => { value.qfReciterId = 6 }],
    ['recording', (value: RuntimeTimingResource) => { value.audioSlug = 'other-audio' }],
    ['clock', (value: RuntimeTimingResource) => { value.clock = 'chapter-ms' as 'everyayah-ms' }],
    ['schema', (value: RuntimeTimingResource) => { value.schemaVersion = 2 as 1 }],
    ['source hash', (value: RuntimeTimingResource) => { value.sourceSha256 = 'bad' }],
    ['fractional checkpoint', (value: RuntimeTimingResource) => { value.sourceSyncSequence = 1.5 }],
    ['missing source freshness', (value: RuntimeTimingResource) => { value.sourceSyncedAtMs = 0 }],
    ['empty accepted corpus', (value: RuntimeTimingResource) => {
      value.rows = []; value.withheldVerseKeys = [...canonical.keys()]
    }],
    ['missing verse', (value: RuntimeTimingResource) => { value.withheldVerseKeys = [] }],
    ['duplicate withheld verse', (value: RuntimeTimingResource) => { value.withheldVerseKeys = ['2:2', '2:2'] }],
    ['row also withheld', (value: RuntimeTimingResource) => { value.withheldVerseKeys = ['1:1', '2:2'] }],
    ['unordered rows', (value: RuntimeTimingResource) => { value.rows.reverse() }],
    ['duplicate verse', (value: RuntimeTimingResource) => { value.rows.push(value.rows[1]!) }],
    ['missing word', (value: RuntimeTimingResource) => { value.rows[0]![2].pop() }],
    ['foreign word', (value: RuntimeTimingResource) => { value.rows[0]![2][0]![0] = 3 }],
    ['fractional boundary', (value: RuntimeTimingResource) => { value.rows[0]![2][0]![1] = 10.5 }],
    ['negative onset', (value: RuntimeTimingResource) => { value.rows[0]![3] = -1 }],
    ['segment before onset', (value: RuntimeTimingResource) => { value.rows[0]![3] = 11 }],
    ['overlapping segments', (value: RuntimeTimingResource) => { value.rows[0]![2][1]![1] = 19 }],
    ['reversed boundary', (value: RuntimeTimingResource) => { value.rows[0]![2][0]![2] = 5 }],
    ['unordered clock', (value: RuntimeTimingResource) => { value.rows[1]![2].reverse() }],
  ])('rejects %s before publication', async (_, change) => {
    const value = await resource()
    change(value)
    await revision(value)
    await expect(validateTimingResource(value, 1, canonical)).rejects.toThrow()
  })

  it('binds a revision to every repeat boundary and withheld verse', async () => {
    const value = await resource()
    value.rows[1]![2][3]![1] = 51
    await expect(validateTimingResource(value, 1, canonical)).rejects.toThrow('digest mismatch')
  })
})

describe('authenticated timing cache', () => {
  it('deduplicates the initial fill and publishes only after persistence succeeds', async () => {
    const value = await resource()
    const store = new MemoryStore()
    const fetcher = vi.fn<typeof fetch>(async () => Response.json(value))
    const subject = cache(store, fetcher)
    const changes: (number | null)[] = []
    subject.subscribe((id) => {
      expect(store.rows.get(id!)?.revision).toBe(value.revision)
      changes.push(id)
    })
    await Promise.all([subject.ensure(1), subject.ensure(1)])
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]?.[0]).toBe('https://content.example/api/timings/1')
    expect(changes).toEqual([1])
    expect(subject.resource(1)?.rows).toEqual(value.rows)
  })

  it('restores a complete current cache with zero network calls', async () => {
    const store = new MemoryStore()
    await store.put(await resource())
    const fetcher = vi.fn<typeof fetch>()
    const subject = cache(store, fetcher)
    await subject.ensure(1)
    expect(fetcher).not.toHaveBeenCalled()
    expect(subject.resource(1)?.rows[0]![0]).toBe(1)
  })

  it('keeps a synchronized cache readable beyond seven days during outages', async () => {
    const value = await resource()
    const store = new MemoryStore()
    await store.put(value)
    const subject = cache(store, vi.fn(async () => { throw new TypeError('offline') }),
      () => now + TIMING_REFRESH_AFTER_MS * 2)
    await subject.ensure(1)
    await expect(subject.refresh(1)).rejects.toThrow('offline')
    expect(subject.resource(1)?.revision).toBe(value.revision)
    expect(store.rows.get(1)?.revision).toBe(value.revision)
  })

  it('advances unchanged source freshness without invalidating prepared ink', async () => {
    const value = await resource()
    const store = new MemoryStore()
    await store.put(value)
    const fresh = { ...value, sourceSyncedAtMs: now + 100 }
    const subject = cache(store, vi.fn(async () => Response.json(fresh)))
    const changes = vi.fn()
    subject.subscribe(changes)
    await subject.ensure(1)
    changes.mockClear()
    await subject.refresh(1)
    expect(changes).not.toHaveBeenCalled()
    expect(store.rows.get(1)?.sourceSyncedAtMs).toBe(now + 100)
  })

  it.each(['invalid replacement', 'review required', 'storage failure'])('rolls back a %s', async (failure) => {
    const value = await resource()
    const next = structuredClone(value)
    next.rows[1]![2][3]![1] = 51
    if (failure !== 'invalid replacement') await revision(next)
    const store = new MemoryStore()
    await store.put(value)
    const subject = cache(store, vi.fn(async () => failure === 'review required'
      ? Response.json({ error: { code: 'qf_timing_review_required' } }, { status: 503 })
      : Response.json(next)))
    await subject.ensure(1)
    store.rejectWrites = failure === 'storage failure'
    await expect(subject.refresh(1)).rejects.toThrow()
    expect(subject.resource(1)?.revision).toBe(value.revision)
    expect(store.rows.get(1)?.revision).toBe(value.revision)
  })

  it('refills a corrupt local copy instead of trusting an unchanged checkpoint', async () => {
    const value = await resource()
    const corrupted = structuredClone(value)
    corrupted.rows.pop()
    const store = new MemoryStore()
    await store.put(corrupted)
    const subject = cache(store, vi.fn(async () => Response.json(value)))
    await subject.ensure(1)
    expect(subject.resource(1)?.rows).toEqual(value.rows)
    expect(store.rows.get(1)?.rows).toEqual(value.rows)
  })

  it('purges every voice and notifies active consumers on shared credential rejection', async () => {
    const store = new MemoryStore()
    await store.put(await resource(1))
    await store.put(await resource(2))
    const subject = cache(store, vi.fn(async () => Response.json(
      { error: { code: 'qf_access_revoked' } }, { status: 403 },
    )))
    await subject.ensure(1)
    await subject.ensure(2)
    const changes = vi.fn()
    subject.subscribe(changes)
    await expect(subject.refresh(1)).rejects.toThrow('revoked')
    expect(store.rows.size).toBe(0)
    expect(subject.resource(1)).toBeNull()
    expect(subject.resource(2)).toBeNull()
    expect(changes).toHaveBeenCalledExactlyOnceWith(null)
  })

  it('removes a withdrawn resource without discarding other voices', async () => {
    const store = new MemoryStore()
    await store.put(await resource(1))
    await store.put(await resource(2))
    const subject = cache(store, vi.fn(async () => Response.json(
      { error: { code: 'qf_timing_resource_deleted' } }, { status: 410 },
    )))
    await subject.ensure(1)
    await subject.ensure(2)
    await expect(subject.refresh(1)).rejects.toThrow('deleted')
    expect(subject.resource(1)).toBeNull()
    expect(store.rows.has(1)).toBe(false)
    expect(subject.resource(2)).not.toBeNull()
    expect(store.rows.has(2)).toBe(true)
  })

  it('does not resurrect an in-flight response after global revocation', async () => {
    const value = await resource(2)
    const store = new MemoryStore()
    await store.put(await resource(1))
    await store.put(value)
    let respond!: (response: Response) => void
    const waiting = new Promise<Response>((resolve) => { respond = resolve })
    const subject = cache(store, vi.fn(async (input) => String(input).endsWith('/2')
      ? waiting : Response.json({ error: { code: 'qf_access_revoked' } }, { status: 403 })))
    await subject.ensure(1)
    await subject.ensure(2)
    const pending = subject.refresh(2)
    await expect(subject.refresh(1)).rejects.toThrow('revoked')
    respond(Response.json(value))
    await expect(pending).rejects.toThrow('superseded')
    expect(store.rows.size).toBe(0)
    expect(subject.resource(2)).toBeNull()
  })

  it('cannot restore an unloaded voice while a global purge is still being persisted', async () => {
    const store = new MemoryStore()
    await store.put(await resource(1))
    await store.put(await resource(2))
    let release!: () => void
    let started!: () => void
    const clearing = new Promise<void>((resolve) => { started = resolve })
    const blocked = new Promise<void>((resolve) => { release = resolve })
    store.clear = async () => { started(); await blocked; store.rows.clear() }
    const subject = cache(store, vi.fn(async () => Response.json(
      { error: { code: 'qf_timing_review_required' } }, { status: 503 },
    )))
    await subject.ensure(1)
    const purging = subject.revoke()
    await clearing
    await expect(subject.ensure(2)).rejects.toThrow('503')
    expect(subject.resource(2)).toBeNull()
    release()
    await purging
    expect(store.rows.size).toBe(0)
  })

  it('a withdrawn voice does not cancel an unrelated initial fill', async () => {
    const store = new MemoryStore()
    await store.put(await resource(1))
    const value = await resource(2)
    let respond!: (response: Response) => void
    const waiting = new Promise<Response>((resolve) => { respond = resolve })
    const subject = cache(store, vi.fn(async (input) => String(input).endsWith('/2')
      ? waiting : Response.json({ error: { code: 'qf_timing_resource_deleted' } }, { status: 410 })))
    await subject.ensure(1)
    const pending = subject.ensure(2)
    await expect(subject.refresh(1)).rejects.toThrow('deleted')
    respond(Response.json(value))
    await pending
    expect(subject.resource(1)).toBeNull()
    expect(subject.resource(2)?.revision).toBe(value.revision)
    expect(store.rows.get(2)?.revision).toBe(value.revision)
  })
})
