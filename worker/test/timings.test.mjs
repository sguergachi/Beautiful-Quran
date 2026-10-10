import assert from 'node:assert/strict'
import { timingSafeEqual } from 'node:crypto'
import { DatabaseSync } from 'node:sqlite'
import test from 'node:test'
import { createQfProxy } from '../src/index.mjs'
import { createTimingService, SqlTimingStore, TimingController } from '../src/timings.mjs'

crypto.subtle.timingSafeEqual ||= timingSafeEqual // Cloudflare's WebCrypto extension.

class MemoryKV {
  values = new Map()
  async get(key, type) {
    const value = this.values.get(key)
    if (value === undefined) return null
    if (type === 'stream') return new Response(value).body
    return value
  }
  async put(key, value) { this.values.set(key, typeof value === 'string' ? value : await new Response(value).text()) }
  async delete(key) { this.values.delete(key) }
  async list({ prefix }) { return { keys: [...this.values.keys()].filter((name) => name.startsWith(prefix)).map((name) => ({ name })), list_complete: true } }
}

function fixture(t, upstream = () => assert.fail('current cache must not fetch'), now = () => 200) {
  const database = new DatabaseSync(':memory:')
  t.after(() => database.close())
  const storage = {
    sql: { exec: (query, ...bindings) => {
      const rows = database.prepare(query).all(...bindings)
      return { toArray: () => rows }
    } },
    transactionSync(callback) {
      database.exec('BEGIN')
      try { const value = callback(); database.exec('COMMIT'); return value }
      catch (error) { database.exec('ROLLBACK'); throw error }
    },
  }
  const control = new SqlTimingStore(storage)
  const env = { QF_TIMING_STORE: new MemoryKV(), TIMING_MAINTENANCE_KEY: 'private-key' }
  const controller = new TimingController(control, env, upstream, now)
  env.QF_TIMING_CONTROL = { getByName: (name) => { assert.equal(name, 'qf-content-access'); return controller } }
  return { control, env, controller, service: createTimingService() }
}

const page = (mutations = [], token = 'checkpoint') => Response.json({ sync: { mutations, has_more: false, next_sync_token: token, next_page_url: null } })
const change = (type, extra = {}) => ({ type, resource_group: 'chapter_recitations', resource_id: 7, ...extra })
const request = (path = '/api/timings/1', init) => new Request(`https://worker.example${path}`, init)
const privateRequest = (path, body) => request(path, { method: body === undefined ? 'GET' : 'POST',
  headers: { 'x-timing-maintenance-key': 'private-key' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done }); return { promise, resolve } }

async function reviewed(f) {
  const revision = 'a'.repeat(64)
  const state = { epoch: 1, syncToken: 'old-checkpoint', sourceKey: 'timings/1/source/old', syncedAtMs: 100, deleted: false, tombstoned: false }
  const metadata = { schemaVersion: 1, reciterId: 1, qfReciterId: 7, audioSlug: 'Alafasy_128kbps', clock: 'everyayah-ms',
    sourceSha256: 'b'.repeat(64), sourceSyncSequence: 54, sourceSyncedAtMs: 100, normalizerSha256: 'c'.repeat(64), revision }
  const accepted = { sourceKey: state.sourceKey, payloadKey: `timings/1/view/${revision}`, metadata }
  await f.env.QF_TIMING_STORE.put(state.sourceKey, '{"records":[]}')
  await f.env.QF_TIMING_STORE.put(accepted.payloadKey, '{"rows":[[1,1,[[1,0,100]],0]],"withheldVerseKeys":[]}')
  f.control.put('resource:1', { ...state, accepted })
  f.control.put(`blob:${state.sourceKey}`, true)
  f.control.put(`blob:${accepted.payloadKey}`, true)
  return accepted
}

test('serves a reviewed streamed view without its private source or checkpoint', async (t) => {
  const f = fixture(t)
  await reviewed(f)
  const response = await f.service.fetch(request(), f.env)
  assert.equal(response.status, 200)
  const body = await response.json()
  assert.equal(body.sourceSyncedAtMs, 100)
  assert.deepEqual(body.rows, [[1, 1, [[1, 0, 100]], 0]])
  assert.equal(body.syncToken, undefined)
  assert.equal(body.sourceKey, undefined)
  assert.equal(response.headers.get('cache-control'), 'no-store')
})

test('unchanged native sync advances the durable checkpoint without changing revision', async (t) => {
  const f = fixture(t, async (_env, path) => { assert.match(path, /sync_token=old-checkpoint/); return page([], 'new-checkpoint') }, () => 1_000)
  await reviewed(f)
  await f.controller.refresh(1)
  const body = await (await f.service.fetch(request(), f.env)).json()
  assert.equal(body.revision, 'a'.repeat(64))
  assert.equal(body.sourceSyncedAtMs, 1_000)
  assert.equal(f.controller.state(1).syncToken, 'new-checkpoint')
  const restarted = new TimingController(f.control, f.env, () => assert.fail('persisted current source must not refetch'), () => 1_001)
  assert.equal((await restarted.response(1)).status, 200)
})

test('native updates cannot publish a new source until an exact-source reviewed accept', async (t) => {
  const f = fixture(t, async (_env, path) => path.includes('/snapshots/') ? Response.json({ records: [] }) : page([change('ROW_UPDATE')]), () => 1_000)
  const accepted = await reviewed(f)
  const state = await f.controller.refresh(1)
  assert.notEqual(state.sourceKey, accepted.sourceKey)
  assert.equal(await f.env.QF_TIMING_STORE.get(accepted.sourceKey), null)
  assert.equal((await f.service.fetch(request(), f.env)).status, 503)
  const stale = await f.service.fetch(privateRequest('/internal/timings/1/accept', accepted), f.env)
  assert.equal(stale.status, 409)
  const replacement = { ...accepted, sourceKey: state.sourceKey }
  assert.equal((await f.service.fetch(privateRequest('/internal/timings/1/accept', replacement), f.env)).status, 200)
  assert.equal((await f.service.fetch(request(), f.env)).status, 200)
})

test('a failed replacement does not advance the native checkpoint or readable view', async (t) => {
  const f = fixture(t, async (_env, path) => path.includes('/snapshots/') ? new Response(null, { status: 503 }) : page([change('ROW_UPDATE')]), () => 1_000)
  await reviewed(f)
  await assert.rejects(f.controller.refresh(1))
  assert.equal(f.controller.state(1).syncToken, 'old-checkpoint')
  assert.equal((await f.service.fetch(request(), f.env)).status, 200)
})

test('outages past seven days retain the accepted Content Sync copy without false freshness', async (t) => {
  const f = fixture(t, async () => new Response(null, { status: 503 }), () => 30 * 24 * 60 * 60 * 1_000)
  await reviewed(f)
  const response = await f.service.fetch(request(), f.env)
  assert.equal(response.status, 200)
  assert.equal((await response.json()).sourceSyncedAtMs, 100)
})

test('resource deletion stays hidden when a later page fails; restoration requires reviewed accept', async (t) => {
  let calls = 0
  let restored = false
  const f = fixture(t, async (_env, path) => {
    if (restored) return path.includes('/snapshots/') ? Response.json({ records: [] }) : page([change('RESOURCE_CREATE')])
    return ++calls === 1 ? Response.json({ sync: { mutations: [change('RESOURCE_DELETE')], has_more: true,
      next_page_url: '/api/v4/resources/sync?cursor=next' } }) : new Response(null, { status: 503 })
  }, () => 1_000)
  const accepted = await reviewed(f)
  await f.env.QF_TIMING_STORE.put('timings/2/view/other', 'other voice')
  await assert.rejects(f.controller.refresh(1))
  assert.equal(await f.env.QF_TIMING_STORE.get(accepted.payloadKey), null)
  assert.equal(await f.env.QF_TIMING_STORE.get('timings/2/view/other'), 'other voice')
  assert.equal((await f.service.fetch(request(), f.env)).status, 410)
  restored = true
  const state = await f.controller.refresh(1)
  assert.equal(state.deleted, false)
  assert.equal(state.tombstoned, true)
  assert.equal((await f.service.fetch(request(), f.env)).status, 410)
  await f.env.QF_TIMING_STORE.put(accepted.payloadKey, '{"rows":[],"withheldVerseKeys":[]}')
  await f.controller.accept(1, { ...accepted, sourceKey: state.sourceKey })
  assert.equal(f.controller.state(1).tombstoned, false)
  assert.equal((await f.service.fetch(request(), f.env)).status, 200)
})

test('OAuth and content rejection persist global revocation and purge all voices', async (t) => {
  for (const oauthFailure of [false, true]) {
    const f = fixture(t, async () => {
      if (oauthFailure) throw Object.assign(new Error('qf_access_revoked'), { status: 403, code: 'qf_access_revoked' })
      return new Response(null, { status: 403 })
    }, () => 1_000)
    await reviewed(f)
    await f.env.QF_TIMING_STORE.put('timings/2/view/other', 'other voice')
    await assert.rejects(f.controller.refresh(1))
    assert.equal((await f.env.QF_TIMING_STORE.list({ prefix: 'timings/' })).keys.length, 0)
    assert.equal(f.controller.global().purgeComplete, true)
    const restarted = new TimingController(f.control, f.env, () => assert.fail('revoked state must not fetch'))
    assert.equal((await restarted.response(1)).status, 403)
    assert.equal((await restarted.content('/api/v4/resources/snapshots/mushafs/1')).status, 403)
  }
})

test('a suspended snapshot cannot recreate state after revoke or revoke a later regrant', async (t) => {
  const snapshot = deferred(), started = deferred()
  const f = fixture(t, async (_env, path) => {
    if (path.includes('/snapshots/')) { started.resolve(); return snapshot.promise }
    return page([change('ROW_UPDATE')])
  }, () => 1_000)
  await reviewed(f)
  const refresh = f.controller.refresh(1)
  await started.promise
  await f.controller.revoke()
  assert.deepEqual(f.controller.regrant(), { ok: true })
  snapshot.resolve(Response.json({ records: [] }))
  await assert.rejects(refresh, { code: 'qf_access_revoked' })
  assert.equal(f.controller.state(1), null)
  assert.equal(f.controller.global().revoked, false)
  assert.equal((await f.env.QF_TIMING_STORE.list({ prefix: 'timings/' })).keys.length, 0)
})

test('revocation waits for an in-flight KV write before completing purge and regrant', async (t) => {
  const write = deferred(), started = deferred()
  const f = fixture(t, async (_env, path) => path.includes('/snapshots/') ? Response.json({ records: [] }) : page([change('ROW_UPDATE')]), () => 1_000)
  await reviewed(f)
  const originalPut = f.env.QF_TIMING_STORE.put.bind(f.env.QF_TIMING_STORE)
  f.env.QF_TIMING_STORE.put = async (key, value) => { started.resolve(); await write.promise; return originalPut(key, value) }
  const refresh = f.controller.refresh(1)
  await started.promise
  const revoke = f.controller.revoke()
  assert.equal((await f.service.fetch(request(), f.env)).status, 403)
  assert.throws(() => f.controller.regrant(), { code: 'qf_timing_purge_incomplete' })
  write.resolve()
  await assert.rejects(refresh, { code: 'qf_access_revoked' })
  await revoke
  assert.equal(f.controller.state(1), null)
  assert.equal((await f.env.QF_TIMING_STORE.list({ prefix: 'timings/' })).keys.length, 0)
  assert.deepEqual(f.controller.regrant(), { ok: true })
})

test('concurrent refreshes coalesce rather than advancing competing checkpoints', async (t) => {
  const sync = deferred(), started = deferred()
  let calls = 0
  const f = fixture(t, async () => { calls += 1; started.resolve(); return sync.promise }, () => 1_000)
  await reviewed(f)
  const first = f.controller.refresh(1)
  await started.promise
  const second = f.controller.refresh(1)
  sync.resolve(page([], 'single-checkpoint'))
  assert.deepEqual(await first, await second)
  assert.equal(calls, 1)
  assert.equal(f.controller.state(1).syncToken, 'single-checkpoint')
})

test('an accept overtaken while checking payload existence cannot overwrite the new source', async (t) => {
  const payload = deferred(), started = deferred()
  const f = fixture(t, async (_env, path) => path.includes('/snapshots/') ? Response.json({ records: [] }) : page([change('ROW_UPDATE')]), () => 1_000)
  const accepted = await reviewed(f)
  const originalGet = f.env.QF_TIMING_STORE.get.bind(f.env.QF_TIMING_STORE)
  f.env.QF_TIMING_STORE.get = async (key, type) => {
    if (key === accepted.payloadKey) { started.resolve(); await payload.promise }
    return originalGet(key, type)
  }
  const accept = f.controller.accept(1, accepted)
  await started.promise
  const state = await f.controller.refresh(1)
  payload.resolve()
  await assert.rejects(accept, { code: 'qf_timing_source_overtaken' })
  assert.equal(f.controller.state(1).sourceKey, state.sourceKey)
  assert.equal((await f.service.fetch(request(), f.env)).status, 503)
})

test('a suspended older accept cannot overwrite a newer accepted view for the same source', async (t) => {
  const payload = deferred(), started = deferred()
  const f = fixture(t)
  const older = await reviewed(f)
  const revision = 'd'.repeat(64)
  const newer = { ...older, payloadKey: `timings/1/view/${revision}`, metadata: { ...older.metadata, revision } }
  await f.env.QF_TIMING_STORE.put(newer.payloadKey, '{"rows":[[1,1,[[1,0,99]],0]],"withheldVerseKeys":[]}')
  const originalGet = f.env.QF_TIMING_STORE.get.bind(f.env.QF_TIMING_STORE)
  f.env.QF_TIMING_STORE.get = async (key, type) => {
    const body = await originalGet(key, type)
    if (key === older.payloadKey) { started.resolve(); await payload.promise }
    return body
  }
  const accept = f.controller.response(1, 'accept', older)
  await started.promise
  assert.deepEqual(await f.controller.accept(1, newer), { ok: true, revision })
  payload.resolve()
  const rejected = await accept
  assert.equal(rejected.status, 409)
  assert.equal((await rejected.json()).error.code, 'qf_timing_source_overtaken')
  assert.equal(f.controller.state(1).accepted.payloadKey, newer.payloadKey)
  assert.equal(f.controller.state(1).epoch, 2)
  assert.equal(f.env.QF_TIMING_STORE.values.has(older.payloadKey), false)
  assert.equal((await (await f.service.fetch(request(), f.env)).json()).revision, revision)
})

test('same-key acceptance retains its immutable view', async (t) => {
  const f = fixture(t)
  const accepted = await reviewed(f)
  await f.controller.accept(1, accepted)
  assert.equal(f.env.QF_TIMING_STORE.values.has(accepted.payloadKey), true)
  assert.deepEqual(f.control.keys('retire:'), [])
  assert.equal((await f.service.fetch(request(), f.env)).status, 200)
})

test('a view being retired cannot be republished while its deletion is suspended', async (t) => {
  const deletion = deferred(), started = deferred()
  const f = fixture(t)
  const older = await reviewed(f)
  const revision = 'd'.repeat(64)
  const newer = { ...older, payloadKey: `timings/1/view/${revision}`, metadata: { ...older.metadata, revision } }
  await f.env.QF_TIMING_STORE.put(newer.payloadKey, '{"rows":[],"withheldVerseKeys":[]}')
  const originalDelete = f.env.QF_TIMING_STORE.delete.bind(f.env.QF_TIMING_STORE)
  f.env.QF_TIMING_STORE.delete = async (key) => {
    if (key === older.payloadKey) { started.resolve(); await deletion.promise }
    return originalDelete(key)
  }
  const accept = f.controller.accept(1, newer)
  await started.promise
  assert.equal(f.controller.state(1).accepted.payloadKey, newer.payloadKey)
  await assert.rejects(f.controller.accept(1, older), { code: 'qf_timing_payload_unavailable' })
  deletion.resolve()
  await accept
  assert.deepEqual(f.control.keys('retire:'), [])
  assert.equal((await (await f.service.fetch(request(), f.env)).json()).revision, revision)
})

test('failed obsolete-view deletion remains durable and retries during maintenance', async (t) => {
  const f = fixture(t, async () => page([], 'maintained'))
  const older = await reviewed(f)
  const revision = 'd'.repeat(64)
  const newer = { ...older, payloadKey: `timings/1/view/${revision}`, metadata: { ...older.metadata, revision } }
  await f.env.QF_TIMING_STORE.put(newer.payloadKey, '{"rows":[],"withheldVerseKeys":[]}')
  const originalDelete = f.env.QF_TIMING_STORE.delete.bind(f.env.QF_TIMING_STORE)
  f.env.QF_TIMING_STORE.delete = async () => { throw new Error('KV temporarily unavailable') }
  assert.deepEqual(await f.controller.accept(1, newer), { ok: true, revision })
  assert.deepEqual(f.control.keys('retire:'), [`retire:${older.payloadKey}`])
  f.env.QF_TIMING_STORE.delete = originalDelete
  const restarted = new TimingController(f.control, f.env, async () => page([], 'maintained'), () => 1_000)
  await restarted.maintain(1)
  assert.equal(f.env.QF_TIMING_STORE.values.has(older.payloadKey), false)
  assert.deepEqual(f.control.keys('retire:'), [])
  assert.equal((await (await restarted.response(1)).json()).revision, revision)
})

test('a delayed public KV read cannot serve an accepted view after withdrawal', async (t) => {
  const payload = deferred(), started = deferred()
  const f = fixture(t)
  const accepted = await reviewed(f)
  const originalGet = f.env.QF_TIMING_STORE.get.bind(f.env.QF_TIMING_STORE)
  f.env.QF_TIMING_STORE.get = async (key, type) => {
    const result = await originalGet(key, type)
    if (key === accepted.payloadKey) { started.resolve(); await payload.promise }
    return result
  }
  const response = f.service.fetch(request(), f.env)
  await started.promise
  await f.controller.withdraw(1, f.controller.global().generation, f.controller.state(1).epoch)
  payload.resolve()
  assert.equal((await response).status, 410)
})

test('in-flight timing and word streams abort when global permission is revoked', async (t) => {
  for (const kind of ['timing', 'words']) {
    const started = deferred(), tail = deferred()
    const bytes = new TextEncoder()
    let chunk = 0
    const body = new ReadableStream({ async pull(controller) {
      if (chunk++ === 0) controller.enqueue(bytes.encode('{"rows":['))
      else { started.resolve(); await tail.promise; controller.enqueue(bytes.encode('],"withheldVerseKeys":[]}')); controller.close() }
    } })
    const f = fixture(t, async () => new Response(body))
    const accepted = await reviewed(f)
    if (kind === 'timing') f.env.QF_TIMING_STORE.get = async () => body
    const response = kind === 'timing' ? await f.controller.response(1) : await f.controller.content('/api/v4/resources/snapshots/mushafs/1')
    assert.equal(response.status, 200)
    const text = response.text()
    await started.promise
    await f.controller.revoke()
    tail.resolve()
    await assert.rejects(text, { code: 'qf_access_revoked' })
    assert.equal(f.controller.state(1), null)
    assert.equal(await f.env.QF_TIMING_STORE.get(accepted.payloadKey), kind === 'timing' ? body : null)
  }
})

test('a rejected checkpoint bootstraps a full replacement before advancing', async (t) => {
  const paths = []
  const f = fixture(t, async (_env, path) => {
    paths.push(path)
    if (paths.length === 1) return Response.json({ error: { code: 'resync_required' } }, { status: 410 })
    return path.includes('/snapshots/') ? Response.json({ records: [] }) : page([change('RESOURCE_CREATE')], 'replacement')
  }, () => 1_000)
  await reviewed(f)
  await f.controller.refresh(1)
  assert.match(paths[1], /bootstrap=true/)
  assert.equal(f.controller.state(1).syncToken, 'replacement')
  assert.equal((await f.service.fetch(request(), f.env)).status, 503)
})

test('private routes reject app access, arbitrary voices, malformed manifests and missing blobs', async (t) => {
  const f = fixture(t)
  const accepted = await reviewed(f)
  assert.equal((await f.service.fetch(request('/internal/timings/1/source'), f.env)).status, 404)
  assert.equal(await f.service.fetch(request('/api/timings/19'), f.env), null)
  assert.equal((await f.service.fetch(request('/api/timings/1/source'), f.env)).status, 405)
  assert.equal((await f.service.fetch(request('/api/timings/1?source=1'), f.env)).status, 405)
  for (const invalid of [null, { ...accepted, metadata: { ...accepted.metadata, reciterId: 2 } },
    { ...accepted, payloadKey: 'timings/2/view/other' }, { ...accepted, metadata: { ...accepted.metadata, sourceKey: 'leak' } }]) {
    assert.equal((await f.service.fetch(privateRequest('/internal/timings/1/accept', invalid), f.env)).status, 400)
  }
  await f.env.QF_TIMING_STORE.delete(accepted.payloadKey)
  assert.equal((await f.service.fetch(privateRequest('/internal/timings/1/accept', accepted), f.env)).status, 409)
})

test('rejects cross-resource mutations and external/ambiguous cursors before fetching them', async (t) => {
  for (const sync of [
    { mutations: [change('ROW_UPDATE', { resource_id: 6 })], has_more: false, next_sync_token: 'bad' },
    { mutations: [], has_more: true, next_page_url: 'https://attacker.example/api/v4/resources/sync?cursor=bad' },
    { mutations: [], has_more: true, next_page_url: '/api/v4/resources/sync?cursor=one&cursor=two' },
  ]) {
    let calls = 0
    const f = fixture(t, async () => { calls += 1; return Response.json({ sync }) })
    await assert.rejects(f.controller.refresh(1))
    assert.equal(calls, 1)
    assert.equal(f.controller.state(1), null)
  }
})

test('the existing narrow word proxy honors timing-triggered global revocation', async (t) => {
  const f = fixture(t)
  await reviewed(f)
  const proxy = createQfProxy(() => assert.fail('revoked word access must not reach OAuth'))
  await f.controller.revoke()
  const response = await proxy.fetch(request('/api/v4/resources/snapshots/mushafs/1'), f.env)
  assert.equal(response.status, 403)
  assert.deepEqual(await response.json(), { error: { code: 'qf_access_revoked' } })
})

test('scheduled revoked-state maintenance removes orphan uploads visible after the first purge', async (t) => {
  const f = fixture(t)
  await reviewed(f)
  await f.controller.revoke()
  await f.env.QF_TIMING_STORE.put('timings/2/view/late-upload', 'late private upload')
  await f.service.scheduled({ scheduledTime: Date.UTC(2026, 9, 10, 5) }, f.env)
  assert.equal(await f.env.QF_TIMING_STORE.get('timings/2/view/late-upload'), null)
  assert.equal(f.controller.global().revoked, true)
})

test('operator recovery cannot overtake a periodic revoked-state purge', async (t) => {
  const f = fixture(t)
  await reviewed(f)
  await f.controller.revoke()
  const listed = deferred(), resume = deferred()
  const originalList = f.env.QF_TIMING_STORE.list.bind(f.env.QF_TIMING_STORE)
  f.env.QF_TIMING_STORE.list = async (options) => { listed.resolve(); await resume.promise; return originalList(options) }
  const maintenance = f.controller.maintain(1)
  await listed.promise
  assert.throws(() => f.controller.regrant(), { code: 'qf_timing_purge_incomplete' })
  resume.resolve()
  await maintenance
  assert.deepEqual(f.controller.regrant(), { ok: true })
})

test('cron maintains one fixed voice per four-hour trigger', async () => {
  const calls = []
  const env = { QF_TIMING_STORE: {}, QF_TIMING_CONTROL: { getByName: () => ({ maintain: async (id) => { calls.push(id) } }) } }
  const service = createTimingService()
  for (const hour of [1, 5, 9, 13, 17, 21]) await service.scheduled({ scheduledTime: Date.UTC(2026, 9, 10, hour) }, env)
  assert.deepEqual(calls, [1, 2, 3, 4, 5, 7])
})
