// Native checkpoints and reviewed views are separate. Only this coordination
// object can publish a source or accepted manifest; KV holds immutable bytes.
export const TIMING_RECITERS = new Map([[1, 7], [2, 6], [3, 2], [4, 9], [5, 3], [7, 5]])
const AUDIO_SLUGS = new Map([[1, 'Alafasy_128kbps'], [2, 'Husary_64kbps'],
  [3, 'Abdul_Basit_Murattal_64kbps'], [4, 'Minshawy_Murattal_128kbps'],
  [5, 'Abdurrahmaan_As-Sudais_192kbps'], [7, 'Hani_Rifai_192kbps']])
const PREFIX = 'timings/'
const REFRESH_MS = 6 * 24 * 60 * 60 * 1_000
const HASH = /^[a-f0-9]{64}$/
const METADATA_KEYS = ['schemaVersion', 'reciterId', 'qfReciterId', 'audioSlug', 'clock',
  'sourceSha256', 'sourceSyncSequence', 'sourceSyncedAtMs', 'normalizerSha256', 'revision']

/** Small synchronous SQLite records are the strong publication/revocation gate. */
export class SqlTimingStore {
  constructor(storage) {
    this.storage = storage
    this.sql = storage.sql
    this.sql.exec('CREATE TABLE IF NOT EXISTS timing_control (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
  }
  get(key) {
    const row = this.sql.exec('SELECT value FROM timing_control WHERE key = ?', key).toArray()[0]
    return row ? JSON.parse(row.value) : null
  }
  put(key, value) {
    this.sql.exec('INSERT INTO timing_control VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value', key, JSON.stringify(value))
  }
  delete(key) { this.sql.exec('DELETE FROM timing_control WHERE key = ?', key) }
  keys(prefix) {
    return this.sql.exec('SELECT key FROM timing_control WHERE substr(key, 1, ?) = ?', prefix.length, prefix).toArray().map(({ key }) => key)
  }
  atomic(callback) { return this.storage.transactionSync(callback) }
}

/** One atom owns the six fixed resources and their shared QF authorization. */
export class TimingController {
  constructor(control, env, qfResponse, now = Date.now) {
    this.control = control
    this.env = env
    this.qfResponse = qfResponse
    this.now = now
    this.refreshes = new Map()
    this.writes = new Map()
    this.purgeRequest = null
  }

  global() { return this.control.get('global') || { generation: 0, revoked: false, purgeComplete: true } }
  state(id) { return this.control.get(`resource:${id}`) }
  active(generation = this.global().generation) {
    const current = this.global()
    if (current.revoked || current.generation !== generation) throw new TimingFailure(403, 'qf_access_revoked')
    return generation
  }
  current(id, generation, epoch) {
    this.active(generation)
    if ((this.state(id)?.epoch || 0) !== epoch) throw new TimingFailure(409, 'qf_timing_source_overtaken')
  }

  async upstream(path, generation) {
    this.active(generation)
    let response
    try {
      response = await this.qfResponse(this.env, path)
    } catch (error) {
      if (error.status === 403 && this.global().generation === generation) await this.revoke()
      throw error
    }
    if (response.status === 401 || response.status === 403) {
      if (this.global().generation === generation) await this.revoke()
      throw new TimingFailure(403, 'qf_access_revoked')
    }
    // An old response cannot revoke a later explicit operator regrant.
    this.active(generation)
    return response
  }

  async purge(prefix) {
    const store = this.env.QF_TIMING_STORE
    // Exact recorded keys cover blobs not yet visible to an eventual KV list.
    for (const entry of this.control.keys(`blob:${prefix}`)) {
      await store.delete(entry.slice(5))
      this.control.delete(entry)
    }
    let cursor
    do {
      const page = await store.list({ prefix, ...(cursor ? { cursor } : {}) })
      await Promise.all(page.keys.map(({ name }) => store.delete(name)))
      cursor = page.list_complete ? null : page.cursor
    } while (cursor)
    for (const key of this.control.keys(`retire:${prefix}`)) this.control.delete(key)
  }

  async revoke() {
    if (!this.global().revoked) {
      this.control.atomic(() => {
        this.control.put('global', { generation: this.global().generation + 1, revoked: true, purgeComplete: false })
        for (const id of TIMING_RECITERS.keys()) this.control.delete(`resource:${id}`)
      })
    }
    if (this.purgeRequest) return this.purgeRequest
    const generation = this.global().generation
    // Periodic cleanup also excludes regrant until its deletes finish.
    this.control.put('global', { generation, revoked: true, purgeComplete: false })
    const promise = (async () => {
      // A late snapshot write must settle and be removed before recovery.
      await Promise.allSettled([...this.writes.values()])
      await this.purge(PREFIX)
      for (const key of this.control.keys('write:')) {
        await this.env.QF_TIMING_STORE.delete(key.slice(6))
        this.control.delete(key)
      }
      if (this.global().revoked && this.global().generation === generation) {
        this.control.put('global', { generation, revoked: true, purgeComplete: true })
      }
    })()
    this.purgeRequest = promise
    try { await promise } finally { if (this.purgeRequest === promise) this.purgeRequest = null }
  }

  regrant() {
    const global = this.global()
    if (!global.revoked || !global.purgeComplete || this.control.keys('write:').length) {
      throw new TimingFailure(409, 'qf_timing_purge_incomplete')
    }
    this.control.put('global', { generation: global.generation + 1, revoked: false, purgeComplete: true })
    return { ok: true }
  }

  async writeSource(id, key, body, generation, epoch) {
    this.current(id, generation, epoch)
    this.control.put(`write:${key}`, { generation })
    this.control.put(`blob:${key}`, true)
    const promise = (async () => {
      try {
        await this.env.QF_TIMING_STORE.put(key, body)
        this.current(id, generation, epoch)
      } catch (error) {
        await this.env.QF_TIMING_STORE.delete(key)
        this.control.delete(`blob:${key}`)
        throw error
      } finally {
        this.control.delete(`write:${key}`)
      }
    })()
    this.writes.set(key, promise)
    try { await promise } finally { this.writes.delete(key) }
  }

  async withdraw(id, generation, epoch) {
    this.current(id, generation, epoch)
    const previous = this.state(id)
    const next = { ...previous, epoch: epoch + 1, sourceKey: null, accepted: null, deleted: true, tombstoned: true }
    this.control.put(`resource:${id}`, next)
    await this.purge(`${PREFIX}${id}/`)
    this.current(id, generation, next.epoch)
    return next.epoch
  }

  async refresh(id) {
    if (!TIMING_RECITERS.has(id)) throw new TimingFailure(404, 'not_found')
    if (this.refreshes.has(id)) return this.refreshes.get(id)
    const promise = this.refreshSource(id)
    this.refreshes.set(id, promise)
    try { return await promise } finally { if (this.refreshes.get(id) === promise) this.refreshes.delete(id) }
  }

  async maintain(id) {
    // Sweep again while revoked: a concurrent operator upload may become
    // visible to KV's eventual listing only after the first purge completed.
    if (this.global().revoked) return this.revoke()
    await this.retireViews(id).catch(() => {})
    return this.refresh(id)
  }

  async retireViews(id) {
    for (const entry of this.control.keys(`retire:${PREFIX}${id}/view/`)) {
      const key = entry.slice(7)
      if (this.writes.has(key)) { await this.writes.get(key); continue }
      const promise = (async () => {
        await this.env.QF_TIMING_STORE.delete(key)
        this.control.delete(`blob:${key}`)
        this.control.delete(entry)
      })()
      this.writes.set(key, promise)
      try { await promise } finally { if (this.writes.get(key) === promise) this.writes.delete(key) }
    }
  }

  async body(key, guard) {
    const body = await this.env.QF_TIMING_STORE.get(key, 'stream')
    try { guard(); return body } catch (error) {
      await body?.cancel().catch(() => {})
      throw error
    }
  }

  async refreshSource(id) {
    if (!this.env.QF_TIMING_STORE) throw new TimingFailure(503, 'qf_timing_unconfigured')
    const generation = this.active()
    const qfId = TIMING_RECITERS.get(id)
    const previous = this.state(id)
    let epoch = previous?.epoch || 0
    let bootstrap = !previous?.syncToken
    let path = syncPath(qfId, bootstrap, previous?.syncToken)
    let dirty = bootstrap
    let deleted = previous?.deleted || false
    let syncToken
    const cursors = new Set()
    for (let page = 0; ; page += 1) {
      this.current(id, generation, epoch)
      if (page >= 100 || cursors.has(path)) throw new TimingFailure(503, 'qf_timing_sync_invalid')
      cursors.add(path)
      const response = await this.upstream(path, generation)
      this.current(id, generation, epoch)
      if (!response.ok) {
        const body = await response.json().catch(() => null)
        this.current(id, generation, epoch)
        if (!bootstrap && (response.status === 400 || response.status === 404 ||
            (response.status === 410 && body?.error?.code === 'resync_required'))) {
          bootstrap = true
          dirty = true
          path = syncPath(qfId, true)
          continue
        }
        throw new TimingFailure(503, 'qf_timing_source_unavailable')
      }
      const sync = (await response.json()).sync
      this.current(id, generation, epoch)
      if (!sync || !Array.isArray(sync.mutations) || typeof sync.has_more !== 'boolean') {
        throw new TimingFailure(503, 'qf_timing_sync_invalid')
      }
      for (const mutation of sync.mutations) {
        if (mutation.resource_group !== 'chapter_recitations' || mutation.resource_id !== qfId) {
          throw new TimingFailure(503, 'qf_timing_sync_invalid')
        }
        if (mutation.type === 'RESOURCE_DELETE') {
          // The tombstone is durable even if a later native page fails.
          epoch = await this.withdraw(id, generation, epoch)
          deleted = true
        } else if (['RESOURCE_CREATE', 'RESOURCE_INVALIDATE', 'ROW_CREATE', 'ROW_UPDATE', 'ROW_DELETE'].includes(mutation.type)) {
          dirty = true
          deleted = false
        } else if (mutation.type !== 'RESOURCE_UPDATE') {
          throw new TimingFailure(503, 'qf_timing_sync_invalid')
        }
      }
      if (!sync.has_more) {
        if (sync.next_page_url || typeof sync.next_sync_token !== 'string' || !sync.next_sync_token) {
          throw new TimingFailure(503, 'qf_timing_sync_invalid')
        }
        syncToken = sync.next_sync_token
        break
      }
      const next = new URL(sync.next_page_url, 'https://qf.invalid')
      const keys = [...next.searchParams.keys()]
      if (next.origin !== 'https://qf.invalid' || next.pathname !== '/api/v4/resources/sync' ||
          !next.searchParams.get('cursor') || keys.some((key) => !['cursor', 'per_page'].includes(key) || next.searchParams.getAll(key).length !== 1)) {
        throw new TimingFailure(503, 'qf_timing_sync_invalid')
      }
      path = next.pathname + next.search
    }
    let sourceKey = deleted ? null : this.state(id)?.sourceKey
    if (dirty && !deleted) {
      const response = await this.upstream(`/api/v4/resources/snapshots/chapter_recitations/${qfId}`, generation)
      this.current(id, generation, epoch)
      if (!response.ok || !response.body) throw new TimingFailure(503, 'qf_timing_source_unavailable')
      sourceKey = `${PREFIX}${id}/source/${crypto.randomUUID()}`
      await this.writeSource(id, sourceKey, response.body, generation, epoch)
    }
    this.current(id, generation, epoch)
    const state = this.state(id)
    const replacement = { ...state, epoch: epoch + (sourceKey !== state?.sourceKey ? 1 : 0),
      syncToken, sourceKey: sourceKey || null, deleted, tombstoned: state?.tombstoned || false, syncedAtMs: this.now() }
    this.control.put(`resource:${id}`, replacement)
    if (state?.sourceKey && state.sourceKey !== sourceKey) {
      await this.env.QF_TIMING_STORE.delete(state.sourceKey)
      this.control.delete(`blob:${state.sourceKey}`)
    }
    return replacement
  }

  async accept(id, manifest) {
    const generation = this.active()
    const state = this.state(id)
    const metadata = manifest?.metadata
    if (!manifest || Object.keys(manifest).length !== 3 || !['sourceKey', 'payloadKey', 'metadata'].every((key) => Object.hasOwn(manifest, key))) {
      throw new TimingFailure(400, 'qf_timing_manifest_invalid')
    }
    if (!state?.sourceKey || state.deleted) throw new TimingFailure(409, 'qf_timing_source_overtaken')
    if (manifest.sourceKey !== state.sourceKey) throw new TimingFailure(409, 'qf_timing_source_overtaken')
    if (!metadata || Object.keys(metadata).length !== METADATA_KEYS.length ||
        METADATA_KEYS.some((key) => !Object.hasOwn(metadata, key)) || metadata.schemaVersion !== 1 ||
        metadata.reciterId !== id || metadata.qfReciterId !== TIMING_RECITERS.get(id) ||
        metadata.audioSlug !== AUDIO_SLUGS.get(id) || metadata.clock !== 'everyayah-ms' ||
        ![metadata.sourceSha256, metadata.normalizerSha256, metadata.revision].every((value) => typeof value === 'string' && HASH.test(value)) ||
        !Number.isSafeInteger(metadata.sourceSyncSequence) || metadata.sourceSyncSequence < 0 ||
        !Number.isSafeInteger(metadata.sourceSyncedAtMs) || metadata.sourceSyncedAtMs < 0 || metadata.sourceSyncedAtMs > state.syncedAtMs ||
        manifest.payloadKey !== `${PREFIX}${id}/view/${metadata.revision}`) {
      throw new TimingFailure(400, 'qf_timing_manifest_invalid')
    }
    if (this.control.get(`retire:${manifest.payloadKey}`)) throw new TimingFailure(409, 'qf_timing_payload_unavailable')
    const body = await this.body(manifest.payloadKey, () => this.current(id, generation, state.epoch))
    if (!body) throw new TimingFailure(409, 'qf_timing_payload_unavailable')
    await body.cancel()
    this.current(id, generation, state.epoch)
    this.control.atomic(() => {
      this.control.put(`blob:${manifest.payloadKey}`, true)
      this.control.put(`resource:${id}`, { ...this.state(id), epoch: state.epoch + 1, accepted: manifest, tombstoned: false })
      if (state.accepted?.payloadKey && state.accepted.payloadKey !== manifest.payloadKey) {
        this.control.put(`retire:${state.accepted.payloadKey}`, true)
      }
    })
    // Publish first; failed cleanup stays queued for the next maintenance run.
    await this.retireViews(id).catch(() => {})
    return { ok: true, revision: metadata.revision }
  }

  readable(id, generation, accepted) {
    this.active(generation)
    const state = this.state(id)
    if (state?.deleted || state?.tombstoned) throw new TimingFailure(410, 'qf_timing_resource_deleted')
    if (!state?.sourceKey || !state.accepted || state.accepted.sourceKey !== state.sourceKey ||
        (accepted && (accepted.sourceKey !== state.sourceKey || accepted.payloadKey !== state.accepted.payloadKey))) {
      throw new TimingFailure(503, 'qf_timing_review_required')
    }
    return state
  }

  async response(id, operation = '', manifest, cors = {}) {
    try {
      if (operation === 'regrant') return Response.json(this.regrant(), { headers: headers(cors) })
      this.active()
      if (operation === 'refresh') return Response.json(await this.refresh(id), { headers: headers(cors) })
      if (operation === 'accept') return Response.json(await this.accept(id, manifest), { headers: headers(cors) })
      let state = this.state(id)
      if (operation === 'state') return Response.json({ state, accepted: state?.accepted || null, global: this.global() }, { headers: headers(cors) })
      if (!operation && (!state || this.now() - state.syncedAtMs >= REFRESH_MS)) {
        try { state = await this.refresh(id) } catch (error) {
          if (error.status === 403) throw error
          // A temporary outage cannot expire a permitted offline copy.
        }
      }
      const generation = this.active()
      if (operation === 'source') {
        if (!state?.sourceKey || state.deleted) throw new TimingFailure(503, 'qf_timing_source_unavailable')
        const sourceKey = state.sourceKey
        const guard = () => {
          this.active(generation)
          if (this.state(id)?.sourceKey !== sourceKey || this.state(id)?.deleted) throw new TimingFailure(409, 'qf_timing_source_overtaken')
        }
        const body = await this.body(sourceKey, guard)
        if (!body) throw new TimingFailure(503, 'qf_timing_source_unavailable')
        return new Response(guardedStream(body, guard), { headers: headers(cors) })
      }
      state = this.readable(id, generation)
      const accepted = state.accepted
      const guard = () => this.readable(id, generation, accepted)
      const body = await this.body(accepted.payloadKey, guard)
      if (!body) throw new TimingFailure(503, 'qf_timing_unavailable')
      const metadata = { ...accepted.metadata, sourceSyncedAtMs: this.state(id).syncedAtMs }
      return new Response(guardedStream(body, guard, metadata), { headers: headers(cors) })
    } catch (error) { return reply(error.status || 503, error.code || 'qf_timing_unavailable', cors) }
  }

  async content(path, cors = {}) {
    try {
      const generation = this.active()
      const response = await this.upstream(path, generation)
      if (!response.ok) {
        if (response.status === 410 && (await response.json().catch(() => null))?.error?.code === 'resync_required') {
          this.active(generation)
          return reply(410, 'resync_required', cors)
        }
        this.active(generation)
        return reply(response.status, 'qf_content_unavailable', cors)
      }
      return new Response(response.body && guardedStream(response.body, () => this.active(generation)),
        { status: response.status, headers: headers(cors) })
    } catch (error) { return reply(error.status || 503, error.code || 'qf_content_unavailable', cors) }
  }
}

// Byte-oriented streams can cross Workers RPC. Recheck after every awaited read
// so a late withdrawal cannot complete a cached response on the client.
function guardedStream(body, guard, metadata) {
  const reader = body.getReader()
  const prefix = metadata && new TextEncoder().encode(JSON.stringify(metadata).slice(0, -1) + ',')
  let first = Boolean(metadata)
  return new ReadableStream({
    type: 'bytes',
    async pull(controller) {
      try {
        guard()
        let { value, done } = await reader.read()
        guard()
        if (done) {
          if (first) throw new Error('Empty timing payload')
          controller.close()
          return
        }
        if (!value.byteLength) return
        if (first) {
          if (value[0] !== 123) throw new Error('Invalid timing payload')
          controller.enqueue(prefix)
          value = value.subarray(1)
          first = false
        }
        if (value.byteLength) controller.enqueue(value)
      } catch (error) {
        controller.error(error)
        await reader.cancel(error).catch(() => {})
      }
    },
    cancel(reason) { return reader.cancel(reason) },
  })
}

/** Public/private route adapter; mutable state is never read from Workers KV. */
export function createTimingService() {
  const stub = (env) => {
    if (!env.QF_TIMING_CONTROL || !env.QF_TIMING_STORE) throw new TimingFailure(503, 'qf_timing_unconfigured')
    return env.QF_TIMING_CONTROL.getByName('qf-content-access')
  }
  return {
    async fetch(request, env, cors = {}) {
      const url = new URL(request.url)
      const regrant = url.pathname === '/internal/timings/regrant'
      const match = url.pathname.match(/^\/(api|internal)\/timings\/(\d+)(?:\/(source|refresh|accept))?$/)
      if (!regrant && (!match || !TIMING_RECITERS.has(Number(match[2])))) return null
      const internal = regrant || match[1] === 'internal'
      if (internal && !authorized(request, env)) return reply(404, 'not_found', cors)
      const operation = regrant ? 'regrant' : match[3] || (internal ? 'state' : '')
      if (url.search || (!internal && operation) || request.method !== (['refresh', 'accept', 'regrant'].includes(operation) ? 'POST' : 'GET')) {
        return reply(405, 'method_not_allowed', cors)
      }
      try {
        let manifest
        if (operation === 'accept') {
          const body = await request.text()
          if (body.length > 16_384) throw new TimingFailure(400, 'qf_timing_manifest_invalid')
          try { manifest = JSON.parse(body) } catch { throw new TimingFailure(400, 'qf_timing_manifest_invalid') }
        }
        return await stub(env).response(regrant ? null : Number(match[2]), operation, manifest, cors)
      } catch (error) { return reply(error.status || 503, error.code || 'qf_timing_unavailable', cors) }
    },
    content: (env, path, cors) => stub(env).content(path, cors),
    revoke: (env) => env.QF_TIMING_CONTROL ? stub(env).revoke() : Promise.resolve(),
    refresh: (id, env) => stub(env).refresh(id),
    async scheduled(event, env) {
      const id = [...TIMING_RECITERS.keys()][Math.floor(new Date(event.scheduledTime).getUTCHours() / 4)]
      await stub(env).maintain(id)
    },
  }
}

function syncPath(id, bootstrap, token) {
  const query = new URLSearchParams({ resources: `chapter_recitations:${id}`, per_page: '100' })
  query.set(bootstrap ? 'bootstrap' : 'sync_token', bootstrap ? 'true' : token)
  return `/api/v4/resources/sync?${query}`
}
function authorized(request, env) {
  const encoder = new TextEncoder()
  const expected = encoder.encode(env.TIMING_MAINTENANCE_KEY || '')
  const supplied = encoder.encode(request.headers.get('x-timing-maintenance-key') || '')
  return expected.length > 0 && expected.length === supplied.length && crypto.subtle.timingSafeEqual(expected, supplied)
}
function headers(cors) { return { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...cors } }
function reply(status, code, cors) { return Response.json({ error: { code } }, { status, headers: headers(cors) }) }
class TimingFailure extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code }
}
