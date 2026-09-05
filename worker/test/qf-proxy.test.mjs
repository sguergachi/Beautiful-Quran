import assert from 'node:assert/strict'
import test from 'node:test'
import { createQfProxy } from '../src/index.mjs'

const env = {
  QF_CLIENT_ID: 'client-id',
  QF_CLIENT_SECRET: 'client-secret',
  QF_ENV: 'prelive',
  ALLOWED_ORIGIN: 'https://sguergachi.github.io',
}
const bootstrap = '/api/v4/resources/sync?bootstrap=true&resources=mushafs%3A1%3Bword_by_word_translations%3A85%3Bword_by_word_transliterations%3A60'

test('proxies only the fixed Content Sync resource set', async () => {
  const calls = []
  const proxy = createQfProxy(async (url, init = {}) => {
    calls.push([url, init])
    if (url.includes('/oauth2/token')) return Response.json({ access_token: 'token', expires_in: 3_600 })
    return Response.json({ sync: { mutations: [], next_sync_token: 'checkpoint' } })
  })

  const response = await proxy.fetch(new Request(`https://worker.example${bootstrap}`, {
    headers: { Origin: env.ALLOWED_ORIGIN },
  }), env)

  assert.equal(response.status, 200)
  assert.equal(calls.length, 2)
  assert.match(calls[1][0], /^https:\/\/apis-prelive\.quran\.foundation\/content\/api\/v4\/resources\/sync/)
  assert.equal(calls[1][1].headers['x-auth-token'], 'token')
  assert.equal(calls[1][1].headers['x-client-id'], env.QF_CLIENT_ID)
  assert.equal(response.headers.get('cache-control'), 'no-store')
  assert.equal(response.headers.get('access-control-allow-origin'), env.ALLOWED_ORIGIN)
})

test('retries a content request once with a replacement token after 401', async () => {
  let tokenRequests = 0
  let contentRequests = 0
  const proxy = createQfProxy(async (url) => {
    if (url.includes('/oauth2/token')) {
      tokenRequests += 1
      return Response.json({ access_token: `token-${tokenRequests}`, expires_in: 3_600 })
    }
    contentRequests += 1
    return contentRequests === 1
      ? new Response(null, { status: 401 })
      : Response.json({ sync: { mutations: [], next_sync_token: 'checkpoint' } })
  })

  const response = await proxy.fetch(new Request(`https://worker.example${bootstrap}`), env)
  assert.equal(response.status, 200)
  assert.equal(tokenRequests, 2)
  assert.equal(contentRequests, 2)
})

test('reports a rejected credential request without exposing the response body', async () => {
  const proxy = createQfProxy(async () => Response.json(
    { error: 'invalid_client', leaked_detail: 'must not escape' },
    { status: 401 },
  ))

  const response = await proxy.fetch(new Request(`https://worker.example${bootstrap}`), env)
  assert.equal(response.status, 503)
  assert.deepEqual(await response.json(), { error: { code: 'qf_auth_rejected_401' } })
})

test('rejects untrusted origins and arbitrary upstream paths without fetching', async () => {
  let calls = 0
  const proxy = createQfProxy(async () => {
    calls += 1
    return Response.json({})
  })
  const origin = await proxy.fetch(new Request(`https://worker.example${bootstrap}`, {
    headers: { Origin: 'https://elsewhere.example' },
  }), env)
  const path = await proxy.fetch(new Request('https://worker.example/api/v4/chapters/1'), env)
  assert.equal(origin.status, 403)
  assert.equal(path.status, 404)
  assert.equal(calls, 0)
})

test('allows only declared resource snapshots', async () => {
  const proxy = createQfProxy(async (url) => {
    if (url.includes('/oauth2/token')) return Response.json({ access_token: 'token', expires_in: 3_600 })
    return Response.json({ records: [] })
  })
  const allowed = await proxy.fetch(new Request('https://worker.example/api/v4/resources/snapshots/mushafs/1'), env)
  const denied = await proxy.fetch(new Request('https://worker.example/api/v4/resources/snapshots/tafsirs/1'), env)
  assert.equal(allowed.status, 200)
  assert.equal(denied.status, 404)
})

test('rejects ambiguous sync queries before contacting QF', async () => {
  let calls = 0
  const proxy = createQfProxy(async () => {
    calls += 1
    return Response.json({})
  })
  const mixedCursor = await proxy.fetch(new Request(
    `https://worker.example${bootstrap}&cursor=next`,
  ), env)
  const falseBootstrap = await proxy.fetch(new Request(
    `https://worker.example${bootstrap.replace('bootstrap=true', 'bootstrap=false')}`,
  ), env)
  const duplicateResource = await proxy.fetch(new Request(
    `https://worker.example${bootstrap}&resources=mushafs%3A1`,
  ), env)

  assert.equal(mixedCursor.status, 404)
  assert.equal(falseBootstrap.status, 404)
  assert.equal(duplicateResource.status, 404)
  assert.equal(calls, 0)
})
