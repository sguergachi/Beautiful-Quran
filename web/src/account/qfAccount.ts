import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import publicConfig from '../../../data/qf_account.json'
import { loadBookmarks, saveBookmarks, toggleBookmark, type Bookmark } from '../data/settings'
import { surahs } from '../data/repository'
import { editJournal, emptyJournal, sameVerse, validJournal,
  type BookmarkJournal } from './bookmarkJournal'
import { QfBookmarkApi } from './qfBookmarkApi'
import { syncBookmarks } from './bookmarkSync'

const config: {
  enabled: boolean; environment: string; webClientId: string; scopes: string[];
  webOrigins: string[]; consentVersion: string;
} = publicConfig

const STORE_KEY = 'beautiful-quran-qf-account-v1'
const ISSUER = config.environment === 'production'
  ? 'https://oauth2.quran.foundation' : 'https://prelive-oauth2.quran.foundation'
const API_BASE = config.environment === 'production'
  ? 'https://apis.quran.foundation/auth' : 'https://apis-prelive.quran.foundation/auth'
const PARTITION = ISSUER + '|' + config.webClientId + '|' + config.consentVersion +
  '|' + [...config.scopes].sort().join(' ')

interface AccountRecord {
  partition: string
  subject: string
  consentedAt: number
  lastSyncedAt: number
  journal: BookmarkJournal
}

export interface QfAccountStatus {
  available: boolean
  environment: string
  connected: boolean
  authRequired: boolean
  writable: boolean
  busy: boolean
  pending: number
  deleting: boolean
  lastSyncedAt: number
  message: string
}

class QfHttpError extends Error {
  constructor(readonly status: number) { super('QF request failed') }
}

/** Memory-only tokens, a durable account journal, and one writer tab prevent token/outbox races. */
class QfAccount {
  private account: AccountRecord | null = this.read()
  private device = loadBookmarks()
  private user: User | null = null
  private oidc: UserManager | null = null
  private listeners = new Set<() => void>()
  private generation = 0
  private ownsLock = false
  private lockAttempt: Promise<boolean> | null = null
  private syncTask: Promise<void> | null = null
  private rerun = false
  private refreshTask: Promise<User> | null = null
  private requests = new AbortController()
  private status: QfAccountStatus = {
    available: config.enabled && ['prelive', 'production'].includes(config.environment) &&
      Boolean(config.webClientId) && config.scopes.includes('openid') &&
      config.webOrigins.includes(globalThis.location?.origin ?? '') &&
      Boolean(config.consentVersion),
    environment: config.environment, connected: this.account !== null,
    authRequired: this.account !== null, writable: false, busy: false,
    pending: this.account?.journal.pending.length ?? 0,
    deleting: this.account?.journal.deleting ?? false,
    lastSyncedAt: this.account?.lastSyncedAt ?? 0, message: '',
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  getSnapshot = () => this.status
  bookmarks = (): Bookmark[] => this.account?.journal.saved ?? this.device
  deviceCount = () => this.device.length

  /** Strip OAuth query data synchronously, before reader routing or service-worker registration. */
  async initialize(): Promise<void> {
    const callback = new URL(location.href)
    const isCallback = callback.searchParams.has('state') &&
      (callback.searchParams.has('code') || callback.searchParams.has('error'))
    if (isCallback) {
      const clean = new URL(callback)
      for (const key of ['code', 'state', 'session_state', 'iss', 'error', 'error_description'])
        clean.searchParams.delete(key)
      history.replaceState(history.state, '', clean)
    }
    window.addEventListener('storage', (event) => {
      if (event.key !== STORE_KEY || this.ownsLock) return
      this.account = this.read()
      this.emit()
    })
    window.addEventListener('online', () => { void this.retry() })
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') void this.retry()
    })
    if (!this.status.available) return
    await this.claim()
    if (this.ownsLock) await this.manager().clearStaleState()
    if (isCallback) {
      try {
        if (!this.ownsLock) throw new Error('Another tab owns the account')
        const user = await this.manager().signinRedirectCallback(callback.href)
        const consent = user.state as { partition?: string; version?: string; at?: number } | undefined
        if (consent?.partition !== PARTITION || consent.version !== config.consentVersion ||
            typeof consent.at !== 'number' || Date.now() - consent.at < 0 ||
            Date.now() - consent.at > 600_000 || !user.profile.sub) {
          throw new Error('Sign-in transaction expired')
        }
        if (this.account && this.account.subject !== user.profile.sub)
          throw new Error('Sign in to the original account or disconnect first')
        this.generation++
        this.requests.abort()
        this.requests = new AbortController()
        this.syncTask = null
        this.refreshTask = null
        this.update(this.account ? { ...this.account, consentedAt: Date.now() } : {
          partition: PARTITION, subject: user.profile.sub, consentedAt: Date.now(),
          lastSyncedAt: 0, journal: emptyJournal(),
        })
        this.user = user
        this.emit('Connected. Loading your QF bookmarks…')
        void this.retry()
      } catch {
        this.user = null
        await this.manager().removeUser()
        this.emit(this.account
          ? 'Sign in to the same QF account to resume. Export and disconnect before switching accounts.'
          : 'Sign-in did not finish. Your device bookmarks are unchanged.')
      }
    } else this.emit(this.account ? 'Sign in to sync. Offline edits are saved in this browser.' : '')
  }

  async signIn(): Promise<void> {
    if (!this.status.available) return
    if (!await this.claim()) {
      this.emit('Another tab is using QF bookmarks. Close that tab, then use this one.')
      return
    }
    try {
      this.emit('Waiting for QF sign-in…', true)
      const nonce = [...crypto.getRandomValues(new Uint8Array(32))]
        .map((byte) => byte.toString(16).padStart(2, '0')).join('')
      await this.manager().signinRedirect({ nonce, state:
        { partition: PARTITION, version: config.consentVersion, at: Date.now() } })
    } catch {
      this.emit('QF sign-in could not start. Reconnect and try again.')
    }
  }

  toggle(surahId: number, ayah: number): boolean {
    const verse = { surahId, ayah }
    const present = !this.bookmarks().some((v) => sameVerse(v, verse))
    try {
      if (this.account) {
        if (!this.ownsLock || this.account.journal.deleting) return !present
        this.update({ ...this.account, journal: editJournal(this.account.journal, verse, present) })
        void this.retry()
      } else {
        const next = toggleBookmark(this.device, surahId, ayah)
        saveBookmarks(next)
        this.device = next
        this.emit()
      }
      return present
    } catch {
      this.emit('This browser could not save the change. Free some storage and retry.')
      return !present
    }
  }

  importDeviceBookmarks(): void {
    if (!this.account || !this.ownsLock || this.account.journal.deleting) return
    try {
      const journal = this.device.reduce((next, verse) => editJournal(next, verse, true), this.account.journal)
      this.update({ ...this.account, journal })
      void this.retry()
    } catch { this.emit('This browser could not save the import. Retry after freeing storage.') }
  }

  eraseSyncedBookmarks(): void {
    if (!this.account || !this.ownsLock) return
    try {
      this.update({ ...this.account, journal: { ...this.account.journal, deleting: true } })
      void this.retry()
    } catch { this.emit('The removal request could not be saved. Retry after freeing storage.') }
  }

  /** Clear local data immediately; revocation is separate and never reported as successful on failure. */
  async disconnect(): Promise<void> {
    if (!this.ownsLock) return
    const hadUser = this.user !== null
    this.generation++
    const epoch = this.generation
    this.requests.abort()
    this.requests = new AbortController()
    this.account = null
    this.user = null
    this.syncTask = null
    this.refreshTask = null
    this.device = loadBookmarks()
    let cleared = false
    let revoked = !hadUser
    try {
      localStorage.removeItem(STORE_KEY)
      cleared = true
    } catch { /* Stop requests even when browser storage cannot be cleared. */ }
    this.emit(cleared ? 'Local QF copy cleared. Revoking this browser’s QF tokens…'
      : 'Sync stopped. Clear this site’s storage to finish removing the local QF copy.', hadUser)
    try {
      if (hadUser) await this.manager().revokeTokens(['refresh_token', 'access_token'])
      revoked = true
    } catch { /* The hosted QF controls remain available if public revocation is refused. */ }
    finally { await this.manager().removeUser() }
    if (epoch === this.generation) this.emit(!cleared
      ? 'Sync stopped. Clear this site’s storage to finish removing the local QF copy.'
      : !hadUser ? 'Disconnected from this browser. Your QF collection is kept.'
        : revoked ? 'Disconnected. QF tokens revoked; your QF collection is kept.'
        : 'Local QF copy cleared. Token revocation could not be confirmed; remove app access in QF.')
  }

  exportBookmarks(): void {
    const url = URL.createObjectURL(new Blob([JSON.stringify({
      format: 'beautiful-quran/bookmarks-v1', scope: this.account ? 'qf' : 'device',
      bookmarks: this.bookmarks(),
    }, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'beautiful-quran-bookmarks.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  async retry(): Promise<void> {
    if (!this.account || !this.user || !this.ownsLock) return
    if (this.syncTask) { this.rerun = true; return }
    const epoch = this.generation
    this.rerun = false
    this.emit('Syncing bookmarks…', true)
    let succeeded = false
    this.syncTask = this.sync(epoch).then(() => { succeeded = true }).catch((error: unknown) => {
      if (epoch !== this.generation) return
      this.emit(error instanceof QfHttpError && error.status === 403
        ? 'QF has not granted bookmark access to this app yet.'
        : 'Sync did not finish. Your edits are saved here; sign in or reconnect and retry.')
    }).finally(() => {
      if (epoch !== this.generation) return
      this.syncTask = null
      this.emit(succeeded ? (this.account?.journal.deleting
        ? 'Removal is waiting for QF confirmation. Retry to finish.'
        : this.account?.journal.pending.length
          ? 'New edits are waiting to sync.' : 'Bookmarks are up to date.') : this.status.message)
      if (this.rerun && succeeded) void this.retry()
    })
    return this.syncTask
  }

  private async sync(epoch: number): Promise<void> {
    const counts = new Map(surahs().map((surah) => [surah.id, surah.ayahCount]))
    const api = new QfBookmarkApi(async (method, path, body) => {
      let token = await this.freshToken(epoch)
      const send = async () => {
        this.current(epoch)
        const cancellation = this.requests
        const deadline = setTimeout(() => cancellation.abort(), 20_000)
        try {
          const response = await fetch(API_BASE + path, {
            method, credentials: 'omit', redirect: 'error', cache: 'no-store',
            signal: cancellation.signal,
            headers: { 'Accept': 'application/json', 'x-auth-token': token,
              'x-client-id': config.webClientId, ...(body ? { 'Content-Type': 'application/json' } : {}) },
            ...(body ? { body: JSON.stringify(body) } : {}),
          })
          if (!response.ok) throw new QfHttpError(response.status)
          const raw = await response.text()
          if (raw.length > 2_000_000) throw new Error('QF response too large')
          this.current(epoch)
          return JSON.parse(raw) as Record<string, unknown>
        } finally {
          clearTimeout(deadline)
          if (cancellation === this.requests && cancellation.signal.aborted)
            this.requests = new AbortController()
        }
      }
      try { return await send() } catch (error) {
        if (!(error instanceof QfHttpError) || error.status !== 401) throw error
        token = await this.freshToken(epoch, true)
        return send()
      }
    }, (verse) => Number.isInteger(verse.surahId) && Number.isInteger(verse.ayah) &&
      verse.ayah >= 1 && verse.ayah <= (counts.get(verse.surahId) ?? 0))
    await syncBookmarks(api, () => this.current(epoch).journal, (journal) => {
      this.update({ ...this.current(epoch), journal })
    })
    this.update({ ...this.current(epoch), lastSyncedAt: Date.now() })
  }

  private async freshToken(epoch: number, force = false): Promise<string> {
    const account = this.current(epoch)
    if (!this.user || this.user.profile.sub !== account.subject) throw new Error('Sign-in required')
    if (force || this.user.expired) {
      if (!this.user.refresh_token) {
        this.user = null
        await this.manager().removeUser()
        throw new Error('Sign-in required')
      }
      this.refreshTask ??= this.manager().signinSilent().then((user) => {
        if (!user) throw new Error('Sign-in required')
        if (user.profile.sub !== this.current(epoch).subject) throw new Error('Account changed')
        this.user = user
        return user
      }).catch(async (error: unknown) => {
        if (epoch === this.generation) {
          this.user = null
          await this.manager().removeUser()
        }
        throw error
      }).finally(() => { if (epoch === this.generation) this.refreshTask = null })
      await this.refreshTask
    }
    this.current(epoch)
    return this.user.access_token
  }

  private current(epoch: number): AccountRecord {
    if (!this.account || epoch !== this.generation || !this.ownsLock) throw new Error('Account changed')
    return this.account
  }

  private manager(): UserManager {
    this.oidc ??= new UserManager({
      authority: ISSUER, client_id: config.webClientId,
      redirect_uri: location.origin + import.meta.env.BASE_URL,
      response_type: 'code', scope: config.scopes.join(' '), loadUserInfo: true,
      automaticSilentRenew: false, staleStateAgeInSeconds: 600,
      requestTimeoutInSeconds: 20,
      userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
      stateStore: new WebStorageStateStore({ store: sessionStorage, prefix: 'beautiful-quran-qf.oidc.' }),
    })
    return this.oidc
  }

  /** One writer tab keeps rotating refresh tokens and the localStorage journal serialized. */
  async claim(): Promise<boolean> {
    if (this.ownsLock) return true
    if (!navigator.locks) {
      this.emit('This browser cannot safely edit QF bookmarks. Use a current browser.')
      return false
    }
    this.lockAttempt ??= new Promise<boolean>((resolve) => {
      void navigator.locks.request(STORE_KEY, { ifAvailable: true }, async (lock) => {
        this.ownsLock = lock !== null
        this.account = this.read()
        this.emit()
        resolve(this.ownsLock)
        if (lock) await new Promise<void>(() => { /* released by the browser when this tab closes */ })
      }).catch(() => { resolve(false) })
    }).finally(() => { this.lockAttempt = null })
    return this.lockAttempt
  }

  private read(): AccountRecord | null {
    try {
      const raw = localStorage.getItem(STORE_KEY)
      if (!raw || !config.enabled) return null
      const value = JSON.parse(raw) as AccountRecord
      if (value.partition !== PARTITION || typeof value.subject !== 'string' || !value.subject ||
          !validJournal(value.journal)) return null
      return value
    } catch { return null }
  }

  private update(next: AccountRecord): void {
    if (!this.ownsLock) throw new Error('Another tab owns the account')
    localStorage.setItem(STORE_KEY, JSON.stringify(next))
    this.account = next
    this.emit(this.status.message, this.status.busy)
  }

  private emit(message = this.status.message, busy = false): void {
    this.status = { ...this.status, connected: this.account !== null,
      authRequired: this.account !== null && this.user === null, writable: this.ownsLock, busy,
      pending: this.account?.journal.pending.length ?? 0,
      deleting: this.account?.journal.deleting ?? false,
      lastSyncedAt: this.account?.lastSyncedAt ?? 0, message }
    this.listeners.forEach((listener) => listener())
  }
}

export const qfAccount = new QfAccount()
