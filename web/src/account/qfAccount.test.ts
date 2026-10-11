import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { emptyJournal } from './bookmarkJournal'

const key = 'beautiful-quran-qf-account-v1'
const guest = [{ surahId: 2, ayah: 255 }]
const partition = 'https://prelive-oauth2.quran.foundation|test-public-client|2026-10-11|bookmark collection offline_access openid'
class MemoryStore {
  values = new Map<string, string>()
  getItem = (name: string) => this.values.get(name) ?? null
  setItem = (name: string, value: string) => { this.values.set(name, value) }
  removeItem = (name: string) => { this.values.delete(name) }
}

let storage: MemoryStore
let session: MemoryStore
let ownsLock: boolean
let subject: string
let options: Record<string, unknown>
let config: Record<string, unknown>
let server: typeof guest
const redirect = vi.fn()
const revoke = vi.fn()
const removeUser = vi.fn()
const fetcher = vi.fn()

beforeEach(() => {
  vi.resetModules()
  storage = new MemoryStore()
  session = new MemoryStore()
  storage.setItem('beautiful-quran-bookmarks', JSON.stringify(guest))
  ownsLock = true
  subject = 'subject-a'
  server = []
  options = {}
  config = { enabled: true, environment: 'prelive', androidClientId: '',
    webClientId: 'test-public-client', webOrigins: ['https://reader.example'],
    scopes: ['openid', 'offline_access', 'bookmark', 'collection'], consentVersion: '2026-10-11' }
  const location = new URL('https://reader.example/?code=test-code&state=test-state')
  vi.stubGlobal('localStorage', storage)
  vi.stubGlobal('sessionStorage', session)
  vi.stubGlobal('location', location)
  vi.stubGlobal('history', { state: null, replaceState: vi.fn((_state, _unused, url) => { location.href = String(url) }) })
  vi.stubGlobal('window', { addEventListener: vi.fn() })
  vi.stubGlobal('document', { visibilityState: 'visible', addEventListener: vi.fn() })
  vi.stubGlobal('navigator', { locks: { request: vi.fn(async (_name, _opts, callback) =>
    callback(ownsLock ? { name: key } : null)) } })
  redirect.mockReset().mockResolvedValue(undefined)
  revoke.mockReset().mockResolvedValue(undefined)
  removeUser.mockReset().mockResolvedValue(undefined)
  fetcher.mockReset().mockImplementation(async (url: string, init: RequestInit) => {
    const path = new URL(url).pathname
    const method = init.method ?? 'GET'
    let response: unknown
    if (path.endsWith('/v1/collections')) {
      response = { success: true, data: [{ id: 'owned', name: 'Beautiful Quran' }],
        pagination: { hasNextPage: false } }
    } else if (path.endsWith('/bookmarks')) {
      const body = JSON.parse(init.body as string) as { key: number; verseNumber: number }
      server = server.filter((verse) => verse.surahId !== body.key || verse.ayah !== body.verseNumber)
      if (method === 'POST') server.push({ surahId: body.key, ayah: body.verseNumber })
      response = { success: true, data: {} }
    } else {
      response = { success: true, data: { collection: { id: 'owned', bookmarksCount: server.length },
        bookmarks: server.map((verse) => ({ id: verse.surahId + ':' + verse.ayah, type: 'ayah',
          key: verse.surahId, verseNumber: verse.ayah, group: 'verses_6236' })) },
        pagination: { hasNextPage: false } }
    }
    return new Response(JSON.stringify(response), { status: 200 })
  })
  vi.stubGlobal('fetch', fetcher)
  vi.doMock('../../../data/qf_account.json', () => ({ default: config }))
  vi.doMock('../data/repository', () => ({ surahs: () => [{ id: 1, ayahCount: 7 }, { id: 2, ayahCount: 286 }] }))
  vi.doMock('oidc-client-ts', () => ({
    InMemoryWebStorage: MemoryStore,
    WebStorageStateStore: class { constructor(readonly options: { store: unknown }) {} },
    UserManager: class {
      constructor(value: Record<string, unknown>) { options = value }
      clearStaleState = async () => {}
      signinRedirect = redirect
      removeUser = removeUser
      revokeTokens = revoke
      signinRedirectCallback = async () => ({
        profile: { sub: subject }, expired: false,
        access_token: 'test-access-token', refresh_token: 'test-refresh-token',
        state: { partition, version: '2026-10-11', at: Date.now() },
      })
    },
  }))
})

afterEach(() => {
  vi.doUnmock('../../../data/qf_account.json')
  vi.doUnmock('../data/repository')
  vi.doUnmock('oidc-client-ts')
  vi.unstubAllGlobals()
  vi.resetModules()
})

async function ready() {
  const { qfAccount } = await import('./qfAccount')
  await qfAccount.initialize()
  for (let attempt = 0; attempt < 100 && qfAccount.getSnapshot().busy; attempt++)
    await Promise.resolve()
  expect(qfAccount.getSnapshot().busy).toBe(false)
  return qfAccount
}

describe('browser account boundaries', () => {
  it('never auto-uploads guest saves or persists account tokens', async () => {
    const account = await ready()
    expect(account.bookmarks()).toEqual([])
    expect(account.deviceCount()).toBe(1)
    expect(server).toEqual([])
    expect(fetcher.mock.calls.every(([, init]) => (init as RequestInit).method === 'GET')).toBe(true)
    expect(storage.getItem('beautiful-quran-bookmarks')).toBe(JSON.stringify(guest))
    expect(storage.getItem(key)).not.toContain('test-access-token')
    expect(storage.getItem(key)).not.toContain('test-refresh-token')
    expect((options.userStore as { options: { store: unknown } }).options.store).toBeInstanceOf(MemoryStore)
    expect((options.userStore as { options: { store: unknown } }).options.store).not.toBe(storage)
    expect((options.stateStore as { options: { store: unknown } }).options.store).toBe(session)
    expect(location.search).toBe('')
  })

  it('supplies an unpredictable nonce to the OAuth library', async () => {
    location.href = 'https://reader.example/'
    const account = await ready()
    await account.signIn()
    const request = redirect.mock.calls[0]![0] as { nonce: string; state: unknown }
    expect(request.nonce).toMatch(/^[a-f0-9]{64}$/)
    expect(options.response_type).toBe('code')
    expect(options.automaticSilentRenew).toBe(false)
  })

  it('keeps a different-account callback away from the cached outbox', async () => {
    const journal = { ...emptyJournal(), collectionId: 'owned', saved: guest,
      revision: 1, pending: [{ verse: guest[0], present: true, revision: 1 }] }
    const old = JSON.stringify({ partition, subject: 'subject-a', consentedAt: Date.now(),
      lastSyncedAt: 0, journal })
    storage.setItem(key, old)
    subject = 'subject-b'
    const account = await ready()
    expect(fetcher).not.toHaveBeenCalled()
    expect(storage.getItem(key)).toBe(old)
    expect(account.getSnapshot().authRequired).toBe(true)
    expect(account.bookmarks()).toEqual(guest)
    expect(account.getSnapshot().message).toContain('same QF account')
  })

  it('prevents a second tab from editing or importing into the account journal', async () => {
    location.href = 'https://reader.example/'
    ownsLock = false
    const old = JSON.stringify({ partition, subject: 'subject-a', consentedAt: Date.now(),
      lastSyncedAt: 0, journal: { ...emptyJournal(), collectionId: 'owned' } })
    storage.setItem(key, old)
    const account = await ready()
    account.toggle(2, 255)
    account.importDeviceBookmarks()
    expect(account.getSnapshot().writable).toBe(false)
    expect(storage.getItem(key)).toBe(old)
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('clears the local account and restores guest saves even when token revocation fails', async () => {
    const account = await ready()
    revoke.mockRejectedValue(new Error('Provider refused public revocation'))
    await account.disconnect()
    expect(storage.getItem(key)).toBeNull()
    expect(account.bookmarks()).toEqual(guest)
    expect(account.getSnapshot().connected).toBe(false)
    expect(account.getSnapshot().message).toContain('could not be confirmed')
    expect(removeUser).toHaveBeenCalled()
  })

  it('makes zero account requests with disabled configuration', async () => {
    config.enabled = false
    const account = await ready()
    await account.signIn()
    expect(account.getSnapshot().available).toBe(false)
    expect(account.bookmarks()).toEqual(guest)
    expect(fetcher).not.toHaveBeenCalled()
    expect(redirect).not.toHaveBeenCalled()
  })
})
