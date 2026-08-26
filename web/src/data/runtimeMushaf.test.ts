import { describe, expect, it } from 'vitest'
import {
  RuntimeMushafCache,
  type RuntimeMushafStore,
  type StoredMushaf,
} from './runtimeMushaf'
import { QF_MAX_CACHE_AGE_MS, QF_REVALIDATE_AFTER_MS } from './runtimeTimings'

const record = {
  record_type: 'mushaf_word' as const,
  record_key: '5:2:19', surah_id: 5, ayah_number: 2, position: 19,
  translation_en: 'seeking', transliteration: 'yabtaghūna', qcf_v2: 'x',
  qcf_page: 106, qcf_line: 12, qcf_span_end: 19, ayah_page: 106,
}

class Store implements RuntimeMushafStore {
  value: StoredMushaf | null = null
  async get() { return this.value }
  async put(value: StoredMushaf) { this.value = value }
}

describe('RuntimeMushafCache', () => {
  it('withholds a miss then installs one complete atomic snapshot', async () => {
    const store = new Store()
    const fetcher = async (input: RequestInfo | URL) => {
      const path = String(input)
      return new Response(JSON.stringify(path.includes('/snapshots/') ? {
        schema_version: 1, resource_group: 'mushafs', resource_id: 1, records: [record],
      } : {
        sync: {
          mutations: [{
            type: 'RESOURCE_CREATE', snapshot_url: '/api/v4/resources/snapshots/mushafs/1',
          }],
          next_page_url: null, next_sync_token: 'token', content_age_ms: 0,
        },
      }))
    }
    const cache = new RuntimeMushafCache(
      'https://content.example', store, fetcher as typeof fetch, () => 100, 1,
    )
    expect(cache.word(5, 2, 19)).toBeNull()
    await cache.refresh()
    expect(cache.word(5, 2, 19)?.translation_en).toBe('seeking')
    expect(cache.status().apiCalls).toBe(2)
  })

  it('withholds an expired cache while an offline refresh fails', async () => {
    const store = new Store()
    store.value = { id: 1, token: 'old', updatedAtMs: 0, records: [record] }
    const offline = async () => { throw new Error('offline') }
    const cache = new RuntimeMushafCache(
      'https://content.example', store, offline as typeof fetch,
      () => QF_MAX_CACHE_AGE_MS + 1, 1,
    )

    await cache.restore()
    expect(cache.word(5, 2, 19)).toBeNull()
    expect(await cache.refresh()).toBe(false)
    expect(cache.word(5, 2, 19)).toBeNull()
  })

  it('keeps a six day cache readable while an offline refresh fails', async () => {
    const store = new Store()
    store.value = { id: 1, token: 'old', updatedAtMs: 0, records: [record] }
    const offline = async () => { throw new Error('offline') }
    const cache = new RuntimeMushafCache(
      'https://content.example', store, offline as typeof fetch,
      () => QF_REVALIDATE_AFTER_MS + 1, 1,
    )

    await cache.restore()
    expect(cache.word(5, 2, 19)?.translation_en).toBe('seeking')
    expect(await cache.refresh()).toBe(false)
    expect(cache.word(5, 2, 19)?.translation_en).toBe('seeking')
  })
})
