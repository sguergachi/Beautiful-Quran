import { describe, expect, it } from 'vitest'
import {
  QF_MAX_CACHE_AGE_MS,
  QF_REVALIDATE_AFTER_MS,
  RuntimeMushafCache,
  type RuntimeMushafStore,
  type StoredMushaf,
} from './runtimeMushaf'

const canonical = new Map([['5:1', ['يَـٰٓأَيُّهَا', 'ٱلَّذِينَ']]])
const qcfRecords = [
  { id: 1, record_type: 'mushaf', pages_count: 1, lines_per_page: 15 },
  { id: 2, record_type: 'mushaf_page', page_number: 106 },
  { id: 3, record_type: 'mushaf_word', verse_id: 1, word_id: 10, text: '\uFC41', char_type_name: 'word', page_number: 106, line_number: 8, position_in_verse: 1 },
  { id: 4, record_type: 'mushaf_word', verse_id: 1, word_id: 11, text: '\uFC42', char_type_name: 'word', page_number: 106, line_number: 8, position_in_verse: 2 },
  { id: 5, record_type: 'mushaf_word', verse_id: 1, word_id: 12, text: '\uFC43', char_type_name: 'end', page_number: 106, line_number: 8, position_in_verse: 3 },
]
const translationRecords = [
  { id: 10, word_id: 10, text: 'O' },
  { id: 11, word_id: 11, text: 'you who' },
]
const transliterationRecords = [
  { id: 10, word_id: 10, text: 'yāayyuhā' },
  { id: 11, word_id: 11, text: 'alladhīna' },
]

class Store implements RuntimeMushafStore {
  value: StoredMushaf | null = null
  async get() { return this.value }
  async put(value: StoredMushaf) { this.value = structuredClone(value) }
  async clear() { this.value = null }
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function fetcher(
  calls: string[],
  options: {
    accessRevoked?: boolean
    invalidMushaf?: boolean
    invalidateMushaf?: boolean
    resyncOnce?: boolean
  } = {},
) {
  let rejected = false
  return async (input: RequestInfo | URL) => {
    const url = String(input)
    calls.push(url)
    if (options.accessRevoked && url.includes('sync_token=')) {
      return response({ error: { code: 'qf_access_revoked' } }, 403)
    }
    if (options.resyncOnce && url.includes('sync_token=') && !rejected) {
      rejected = true
      return response({ error: { code: 'resync_required' } }, 410)
    }
    if (url.includes('/resources/sync')) {
      const bootstrap = url.includes('bootstrap=true')
      return response({ sync: {
        has_more: false,
        next_page_url: null,
        next_sync_token: bootstrap ? 'boot-token' : 'next-token',
        mutations: bootstrap ? [
          mutation('mushafs', 1),
          mutation('word_by_word_translations', 59),
          mutation('word_by_word_transliterations', 60),
        ] : options.invalidateMushaf ? [{ ...mutation('mushafs', 1), type: 'RESOURCE_INVALIDATE' }] : [],
      } })
    }
    if (url.includes('/snapshots/mushafs/1')) return response(snapshot(
      'mushafs', 1, options.invalidMushaf ? qcfRecords.slice(0, -1) : qcfRecords,
    ))
    if (url.includes('/snapshots/word_by_word_translations/59')) {
      return response(snapshot('word_by_word_translations', 59, translationRecords))
    }
    if (url.includes('/snapshots/word_by_word_transliterations/60')) {
      return response(snapshot('word_by_word_transliterations', 60, transliterationRecords))
    }
    const verseKey = url.match(/by_key\/(\d+:\d+)/)?.[1]
    if (verseKey) return response({ verse: {
      verse_key: verseKey,
      words: [{ id: 1000 + calls.length, char_type_name: 'word', transliteration: { text: 'safe' } }],
    } })
    throw new Error(`Unexpected URL ${url}`)
  }
}

function mutation(group: string, id: number) {
  return {
    type: 'RESOURCE_CREATE', resource_group: group, resource_id: id,
    snapshot_url: `/api/v4/resources/snapshots/${group}/${id}`,
  }
}

function snapshot(group: string, id: number, records: unknown[]) {
  return { schema_version: 1, resource_group: group, resource_id: id, records }
}

async function seeded(now = 100) {
  const store = new Store()
  const calls: string[] = []
  const cache = new RuntimeMushafCache(
    'https://content.example', store, fetcher(calls) as typeof fetch,
    () => now, 2, () => canonical, [106],
  )
  expect(await cache.refresh()).toBe(true)
  return { store, calls, cache }
}

describe('RuntimeMushafCache', () => {
  it('bootstraps three QF resources and publishes one atomic reader view', async () => {
    const { cache, store, calls } = await seeded()

    expect(cache.word(5, 1, 1)?.translation_en).toBe('O')
    expect(cache.word(5, 1, 2)?.qcf_v2).toBe('\uFC42 \uFC43')
    expect(store.value?.resources).toHaveLength(4)
    expect(cache.status().apiCalls).toBe(9)
    expect(cache.status().lastRefreshApiCalls).toBe(9)
    expect(calls[0]).toContain('word_by_word_translations%3A59')
  })

  it('restores a fresh complete cache with zero API calls', async () => {
    const first = await seeded()
    const calls: string[] = []
    const cache = new RuntimeMushafCache(
      'https://content.example', first.store, fetcher(calls) as typeof fetch,
      () => 101, 2, () => canonical, [106],
    )

    await cache.restore()

    expect(cache.word(5, 1, 2)?.translation_en).toBe('you who')
    expect(cache.status().apiCalls).toBe(0)
    expect(calls).toHaveLength(0)
  })

  it('uses the checkpoint and makes only six calls when content is unchanged', async () => {
    const { cache, calls, store } = await seeded()
    calls.length = 0

    expect(await cache.refresh()).toBe(true)

    expect(calls).toHaveLength(6)
    expect(calls[0]).toContain('sync_token=boot-token')
    expect(store.value?.token).toBe('next-token')
    expect(cache.status().lastRefreshApiCalls).toBe(6)
  })

  it('bootstraps again when QF rejects an old checkpoint', async () => {
    const first = await seeded()
    const calls: string[] = []
    const cache = new RuntimeMushafCache(
      'https://content.example', first.store, fetcher(calls, { resyncOnce: true }) as typeof fetch,
      () => QF_REVALIDATE_AFTER_MS + 101, 2, () => canonical, [106],
    )

    await cache.restore()

    expect(calls[0]).toContain('sync_token=boot-token')
    expect(calls.some((url) => url.includes('bootstrap=true'))).toBe(true)
    expect(cache.word(5, 1, 1)?.translation_en).toBe('O')
  })

  it('keeps the prior cache when replacement data fails validation', async () => {
    const first = await seeded()
    const calls: string[] = []
    const cache = new RuntimeMushafCache(
      'https://content.example', first.store,
      fetcher(calls, { invalidMushaf: true, invalidateMushaf: true }) as typeof fetch,
      () => 101, 2, () => canonical, [106],
    )
    await cache.restore()

    expect(await cache.refresh()).toBe(false)
    expect(first.store.value?.token).toBe('boot-token')
    expect(cache.word(5, 1, 1)?.translation_en).toBe('O')
  })

  it('withholds an expired cache while offline', async () => {
    const first = await seeded(0)
    const offline = async () => { throw new Error('offline') }
    const cache = new RuntimeMushafCache(
      'https://content.example', first.store, offline as typeof fetch,
      () => QF_MAX_CACHE_AGE_MS + 1, 2, () => canonical, [106],
    )

    await cache.restore()

    expect(cache.word(5, 1, 1)).toBeNull()
    expect(cache.status().phase).toBe('error')
    expect(first.store.value?.records).toHaveLength(0)
    expect(first.store.value?.resources.some((row) => row.resourceGroup === 'word_supplements')).toBe(false)
  })

  it('keeps a six-day cache readable when refresh is offline', async () => {
    const first = await seeded(0)
    const offline = async () => { throw new Error('offline') }
    const cache = new RuntimeMushafCache(
      'https://content.example', first.store, offline as typeof fetch,
      () => QF_REVALIDATE_AFTER_MS + 1, 2, () => canonical, [106],
    )

    await cache.restore()

    expect(cache.word(5, 1, 1)?.translation_en).toBe('O')
    expect(cache.status().phase).toBe('error')
  })

  it('purges readable QF content when access is revoked', async () => {
    const first = await seeded()
    const calls: string[] = []
    const cache = new RuntimeMushafCache(
      'https://content.example', first.store, fetcher(calls, { accessRevoked: true }) as typeof fetch,
      () => QF_REVALIDATE_AFTER_MS + 101, 2, () => canonical, [106],
    )

    await cache.restore()

    expect(first.store.value).toBeNull()
    expect(cache.word(5, 1, 1)).toBeNull()
    expect(cache.status().phase).toBe('error')
  })

  it('never exposes authentication material in client requests', async () => {
    const { calls } = await seeded()
    expect(calls.every((url) => !url.includes('_='))).toBe(true)
    expect(calls.every((url) => !/client_secret|access_token/i.test(url))).toBe(true)
  })
})
