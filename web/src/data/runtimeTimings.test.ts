import {
  QF_MAX_CACHE_AGE_MS,
  QF_REVALIDATE_AFTER_MS,
  RuntimeTimingCache,
  type StoredTimingResource,
  type TimingContentStore,
} from './runtimeTimings'

const row = {
  record_type: 'timing' as const,
  record_key: '2:1',
  surah_id: 2,
  ayah_number: 1,
  segments: [[1, 20, 40]],
  audio_onset_ms: 20,
}

class MemoryStore implements TimingContentStore {
  resource: StoredTimingResource | null = null

  async get(reciterId: number) {
    return this.resource?.reciterId === reciterId ? this.resource : null
  }

  async put(resource: StoredTimingResource) {
    this.resource = structuredClone(resource)
  }

  async clear() {
    this.resource = null
  }
}

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  })
}

describe('RuntimeTimingCache', () => {
  it('uses the bundled fallback immediately then publishes an atomic snapshot', async () => {
    const store = new MemoryStore()
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const path = new URL(String(input)).pathname
      if (path.endsWith('/sync')) {
        return response({
          sync: {
            next_sync_token: 'token-1',
            content_age_ms: 80,
            mutations: [{
              type: 'RESOURCE_CREATE',
              snapshot_url: '/api/v4/resources/snapshots/recitations/1',
            }],
          },
        })
      }
      return response({
        schema_version: 1,
        resource_group: 'recitations',
        resource_id: 1,
        records: [row],
      })
    }) as typeof fetch
    const cache = new RuntimeTimingCache('https://content.example', store, fetcher, () => 100, 1)

    expect(cache.timings(1, 2)).toBeNull()
    expect(await cache.refresh(1)).toBe(true)
    expect(cache.timings(1, 2)?.get(1)).toEqual([
      { position: 1, startMs: 20, endMs: 40 },
    ])
    expect(store.resource?.token).toBe('token-1')
    expect(store.resource?.updatedAtMs).toBe(20)
  })

  it('restores a fresh IndexedDB-shaped resource without a network wait', async () => {
    const store = new MemoryStore()
    store.resource = { reciterId: 1, token: 'cached', updatedAtMs: 90, records: [row] }
    const fetcher = vi.fn() as unknown as typeof fetch
    const cache = new RuntimeTimingCache('https://content.example', store, fetcher, () => 100, 1)

    await cache.restore(1)

    expect(cache.timings(1, 2)?.get(1)?.[0]?.startMs).toBe(20)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('follows relative sync pages before committing the final token', async () => {
    const store = new MemoryStore()
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const url = new URL(String(input))
      if (url.searchParams.has('bootstrap')) {
        return response({
          sync: {
            mutations: [],
            next_page_url: '/api/v4/resources/sync?cursor=two',
          },
        })
      }
      if (url.searchParams.get('cursor') === 'two') {
        return response({
          sync: {
            next_sync_token: 'final',
            content_age_ms: 0,
            mutations: [{
              type: 'RESOURCE_CREATE',
              snapshot_url: '/api/v4/resources/snapshots/recitations/1',
            }],
          },
        })
      }
      return response({
        schema_version: 1,
        resource_group: 'recitations',
        resource_id: 1,
        records: [row],
      })
    }) as typeof fetch
    const cache = new RuntimeTimingCache('https://content.example', store, fetcher, () => 100, 1)

    expect(await cache.refresh(1)).toBe(true)
    expect(store.resource?.token).toBe('final')
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('never serves content beyond seven days when refresh fails', async () => {
    const store = new MemoryStore()
    store.resource = { reciterId: 1, token: 'old', updatedAtMs: 0, records: [row] }
    const fetcher = vi.fn(async () => { throw new Error('offline') }) as unknown as typeof fetch
    const cache = new RuntimeTimingCache(
      'https://content.example',
      store,
      fetcher,
      () => QF_MAX_CACHE_AGE_MS + 1,
      1,
    )

    await cache.restore(1)
    expect(cache.timings(1, 2)).toBeNull()
    expect(await cache.refresh(1)).toBe(false)
    expect(cache.timings(1, 2)).toBeNull()
  })

  it('keeps a six-day snapshot readable while revalidation fails', async () => {
    const store = new MemoryStore()
    store.resource = { reciterId: 1, token: 'old', updatedAtMs: 0, records: [row] }
    const fetcher = vi.fn(async () => { throw new Error('offline') }) as unknown as typeof fetch
    const cache = new RuntimeTimingCache(
      'https://content.example',
      store,
      fetcher,
      () => QF_REVALIDATE_AFTER_MS + 1,
      1,
    )

    await cache.restore(1)
    expect(cache.timings(1, 2)?.get(1)?.[0]?.startMs).toBe(20)
    expect(await cache.refresh(1)).toBe(false)
    expect(cache.timings(1, 2)?.get(1)?.[0]?.startMs).toBe(20)
  })

  it('keeps the prior cache when a replacement snapshot is partial', async () => {
    const store = new MemoryStore()
    store.resource = {
      reciterId: 1,
      token: 'old',
      updatedAtMs: 90,
      records: [row, { ...row, record_key: '2:2', ayah_number: 2 }],
    }
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      if (new URL(String(input)).pathname.endsWith('/sync')) {
        return response({
          sync: {
            next_sync_token: 'new',
            content_age_ms: 0,
            mutations: [{
              type: 'RESOURCE_INVALIDATE',
              snapshot_url: '/api/v4/resources/snapshots/recitations/1',
            }],
          },
        })
      }
      return response({
        schema_version: 1,
        resource_group: 'recitations',
        resource_id: 1,
        records: [row],
      })
    }) as typeof fetch
    const cache = new RuntimeTimingCache('https://content.example', store, fetcher, () => 100, 2)

    await cache.restore(1)
    expect(await cache.refresh(1)).toBe(false)
    expect(store.resource?.token).toBe('old')
    expect(store.resource?.records).toHaveLength(2)
  })
})
