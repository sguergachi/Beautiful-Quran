import { describe, expect, it } from 'vitest'
import {
  RuntimeMushafCache,
  QF_MAX_CACHE_AGE_MS,
  QF_REVALIDATE_AFTER_MS,
  assertQcfV2Runs,
  normalizeLegacyMushaf,
  type RuntimeMushafStore,
  type StoredMushaf,
} from './runtimeMushaf'

const record = {
  record_type: 'mushaf_word' as const,
  record_key: '5:2:19', surah_id: 5, ayah_number: 2, position: 19,
  translation_en: 'seeking', transliteration: 'yabtaghūna', qcf_v2: 'x',
  qcf_page: 106, qcf_line: 12, qcf_span_end: 19, ayah_page: 106,
}

const canonical = new Map([['5:1', ['يَـٰٓأَيُّهَا', 'ٱلَّذِينَ']]])
const legacyVerse = {
  verse_key: '5:1', page_number: 106,
  words: [
    { char_type_name: 'word', text_uthmani: 'يَـٰٓأَيُّهَا', code_v2: '\uFC41', page_number: 106, line_number: 8, translation: { text: 'O' }, transliteration: { text: 'yāayyuhā' } },
    { char_type_name: 'word', text_uthmani: 'ٱلَّذِينَ', code_v2: '\uFC42', page_number: 106, line_number: 8, translation: { text: 'you who' }, transliteration: { text: 'alladhīna' } },
    { char_type_name: 'end', code_v2: '\uFC43' },
  ],
}

class Store implements RuntimeMushafStore {
  value: StoredMushaf | null = null
  async get() { return this.value }
  async put(value: StoredMushaf) { this.value = value }
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  })
}

function contentFetcher() {
  return async (input: RequestInfo | URL) => {
    const path = String(input)
    return jsonResponse(path.includes('/snapshots/') ? {
      schema_version: 1, resource_group: 'mushafs', resource_id: 1, records: [record],
    } : {
      sync: {
        mutations: [{
          type: 'RESOURCE_CREATE', snapshot_url: '/api/v4/resources/snapshots/mushafs/1',
        }],
        next_page_url: null, next_sync_token: 'token', content_age_ms: 0,
      },
    })
  }
}

describe('RuntimeMushafCache', () => {
  it('aligns and caches a legacy Quran.com verse without bundled provider fields', () => {
    const records = normalizeLegacyMushaf(canonical, new Map([[5, [legacyVerse]]]))

    expect(records.map((row) => row.record_key)).toEqual(['5:1:1', '5:1:2'])
    expect(records[1]).toMatchObject({ qcf_v2: '\uFC42 \uFC43', qcf_page: 106, ayah_page: 106 })
  })

  it('keeps glosses aligned when canonical and provider token boundaries differ', () => {
    const fusedCanonical = new Map([['36:22', ['وَمَالِيَ', 'لَآ']]])
    const records = normalizeLegacyMushaf(fusedCanonical, new Map([[36, [{
      verse_key: '36:22', page_number: 442,
      words: [
        { char_type_name: 'word', text_uthmani: 'وَمَا', code_v2: '\uFC41', page_number: 442, line_number: 4, translation: { text: 'And what' }, transliteration: { text: 'wamā' } },
        { char_type_name: 'word', text_uthmani: 'لِىَ', code_v2: '\uFC42', page_number: 442, line_number: 4, translation: { text: '(is) for me' }, transliteration: { text: 'liya' } },
        { char_type_name: 'word', text_uthmani: 'لَآ', code_v2: '\uFC43', page_number: 442, line_number: 4, translation: { text: 'not' }, transliteration: { text: 'lā' } },
      ],
    }]]]))

    expect(records[0]).toMatchObject({ translation_en: 'And what (is) for me', transliteration: 'wamā liya' })
    expect(records[1]).toMatchObject({ translation_en: 'not', transliteration: 'lā' })
  })

  it('rejects a verse_key that only shares a prefix with the requested surah', () => {
    expect(() => normalizeLegacyMushaf(canonical, new Map([[5, [{
      ...legacyVerse, verse_key: '50:1',
    }]]]))).toThrow('Legacy verse key mismatch')
  })

  it('rejects a glyph outside the page font run', () => {
    const rows = normalizeLegacyMushaf(canonical, new Map([[5, [legacyVerse]]]))
    rows[0]!.qcf_v2 = '\uFC42'

    expect(() => assertQcfV2Runs(rows, [106])).toThrow('expected U+FC41')
  })

  it('withholds a miss then installs one complete atomic snapshot', async () => {
    const store = new Store()
    const cache = new RuntimeMushafCache(
      'https://content.example', store, contentFetcher() as typeof fetch, () => 100, 1,
    )
    expect(cache.word(5, 2, 19)).toBeNull()
    await cache.refresh()
    expect(cache.word(5, 2, 19)?.translation_en).toBe('seeking')
    expect(cache.status().apiCalls).toBe(2)
  })

  it('keeps the prior cache when a replacement snapshot is incomplete', async () => {
    const store = new Store()
    store.value = { id: 1, token: 'old', updatedAtMs: 90, records: [record] }
    const fetcher = async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.includes('/snapshots/')) {
        return jsonResponse({
          schema_version: 1, resource_group: 'mushafs', resource_id: 1, records: [],
        })
      }
      return jsonResponse({
        sync: {
          mutations: [{
            type: 'RESOURCE_INVALIDATE', snapshot_url: '/api/v4/resources/snapshots/mushafs/1',
          }],
          next_page_url: null, next_sync_token: 'new', content_age_ms: 0,
        },
      })
    }
    const cache = new RuntimeMushafCache(
      'https://content.example', store, fetcher as typeof fetch, () => 100, 1,
    )

    await cache.restore()
    expect(await cache.refresh()).toBe(false)
    expect(store.value?.token).toBe('old')
    expect(cache.word(5, 2, 19)?.translation_en).toBe('seeking')
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

  it('calls unauthenticated api.quran.com without CORS-preflight cache headers', async () => {
    const store = new Store()
    const calls: { url: string; init?: RequestInit }[] = []
    const fetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init })
      return jsonResponse({ verses: [legacyVerse], pagination: { next_page: null } })
    }
    const cache = new RuntimeMushafCache(
      'https://api.quran.com', store, fetcher as typeof fetch, () => 100, 1, () => canonical, [106],
    )

    expect(cache.word(5, 1, 2)).toBeNull()
    expect(await cache.refresh()).toBe(true)
    expect(cache.word(5, 1, 2)).toMatchObject({ qcf_v2: '\uFC42 \uFC43', translation_en: 'you who' })
    expect(cache.status().apiCalls).toBe(1)
    expect(calls).toHaveLength(1)
    expect(calls[0]!.url).toBe(
      'https://api.quran.com/api/v4/verses/by_chapter/5?words=true&per_page=50&page=1' +
        '&word_fields=location,line_number,char_type_name,code_v2,text_uthmani,page_number&_=100',
    )
    expect(calls[0]!.init?.headers).toEqual({ accept: 'application/json' })
    expect(calls[0]!.init?.cache).toBeUndefined()
    expect(JSON.stringify(calls[0]!.init?.headers ?? {})).not.toMatch(/cache-control/i)
  })

  it('counts every Quran.com page and retries after coming online', async () => {
    const store = new Store()
    const twoAyahs = new Map(canonical)
    twoAyahs.set('5:2', ['ءَامَنُوا۟'])
    let online = false
    const fetcher = async (input: RequestInfo | URL) => {
      if (!online) throw new Error('offline')
      const page = new URL(String(input)).searchParams.get('page')
      if (page === '1') {
        return jsonResponse({ verses: [legacyVerse], pagination: { next_page: 2 } })
      }
      return jsonResponse({
        verses: [{
          verse_key: '5:2', page_number: 106,
          words: [
            { char_type_name: 'word', text_uthmani: 'ءَامَنُوا۟', code_v2: '\uFC44', page_number: 106, line_number: 8, translation: { text: 'believe' }, transliteration: { text: 'āmanū' } },
            { char_type_name: 'end', code_v2: '\uFC45' },
          ],
        }],
        pagination: { next_page: null },
      })
    }
    const cache = new RuntimeMushafCache(
      'https://api.quran.com', store, fetcher as typeof fetch, () => 100, 1, () => twoAyahs, [106],
    )

    expect(await cache.refresh()).toBe(false)
    expect(cache.status().phase).toBe('error')
    expect(cache.status().apiCalls).toBe(1)
    expect(cache.status().lastError).toBe('offline')

    online = true
    expect(await cache.refreshIfNeeded()).toBe(true)
    expect(cache.word(5, 1, 1)?.translation_en).toBe('O')
    expect(cache.word(5, 1, 2)?.qcf_v2).toBe('\uFC42 \uFC43')
    expect(cache.word(5, 2, 1)?.qcf_v2).toBe('\uFC44 \uFC45')
    expect(cache.status().apiCalls).toBe(3)
    expect(store.value?.token).toBe('legacy-100')
  })

  it('does not query bundled rows until restore runs after the database is ready', async () => {
    const store = new Store()
    let loaded = 0
    const loadCanonical = () => {
      loaded += 1
      return canonical
    }
    const cache = new RuntimeMushafCache(
      'https://api.quran.com', store,
      (async () => jsonResponse({ verses: [legacyVerse], pagination: { next_page: null } })) as typeof fetch,
      () => 100, 1, loadCanonical, [106],
    )

    expect(loaded).toBe(0)
    const restored = cache.restore()
    expect(loaded).toBe(0)
    await restored
    expect(loaded).toBe(1)
    expect(cache.word(5, 1, 1)?.translation_en).toBe('O')
  })

  it('keeps initial restore pending until a missing cache is installed', async () => {
    let markStarted!: () => void
    const started = new Promise<void>((resolve) => { markStarted = resolve })
    let release!: () => void
    const fetcher = () => new Promise<Response>((resolve) => {
      markStarted()
      release = () => resolve(jsonResponse({ verses: [legacyVerse], pagination: { next_page: null } }))
    })
    const cache = new RuntimeMushafCache(
      'https://api.quran.com', new Store(), fetcher as typeof fetch,
      () => 100, 1, () => canonical, [106],
    )
    let settled = false

    const restoring = cache.restore().then(() => { settled = true })
    await started
    expect(settled).toBe(false)
    expect(cache.status().phase).toBe('refreshing')
    expect(cache.status().apiCalls).toBe(1)
    expect(cache.haveRequestsSettled()).toBe(false)
    expect(cache.downloadProgress()).toEqual({ completed: 0, total: 1, fraction: 0 })

    release()
    await restoring
    expect(settled).toBe(true)
    expect(cache.haveRequestsSettled()).toBe(true)
    expect(cache.downloadProgress()).toEqual({ completed: 1, total: 1, fraction: 1 })
    expect(cache.word(5, 1, 1)?.translation_en).toBe('O')
  })

  it('leaves Content Sync URLs un-bust so a future QF host stays on that protocol', async () => {
    const urls: string[] = []
    const fetcher = async (input: RequestInfo | URL) => {
      urls.push(String(input))
      return contentFetcher()(input)
    }
    const cache = new RuntimeMushafCache(
      'https://content.example', new Store(), fetcher as typeof fetch, () => 100, 1,
    )
    await cache.refresh()
    expect(urls.every((url) => !url.includes('_='))).toBe(true)
    expect(urls[0]).toContain('/api/v4/resources/sync?bootstrap=true')
  })
})
