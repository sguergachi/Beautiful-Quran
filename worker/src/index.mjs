const RESOURCES = 'mushafs:1;word_by_word_translations:85;word_by_word_transliterations:60'
const SNAPSHOTS = new Set([
  '/api/v4/resources/snapshots/mushafs/1',
  '/api/v4/resources/snapshots/word_by_word_translations/85',
  '/api/v4/resources/snapshots/word_by_word_transliterations/60',
])
const ALLOWED_SYNC_KEYS = new Set(['bootstrap', 'resources', 'sync_token', 'cursor', 'per_page'])

/**
 * A deliberately narrow browser/mobile-safe proxy for QF Content Sync.
 * It holds only the short-lived OAuth token in Worker memory; Quran content
 * passes through to the device cache and is never stored by this service.
 */
export function createQfProxy(fetchImpl = fetch) {
  let cachedToken = null
  let tokenRequest = null

  const clearToken = () => { cachedToken = null }

  async function accessToken(env) {
    if (cachedToken && cachedToken.expiresAtMs > Date.now()) return cachedToken.value
    if (tokenRequest) return tokenRequest
    tokenRequest = (async () => {
      const clientId = requiredSecret(env, 'QF_CLIENT_ID')
      const clientSecret = requiredSecret(env, 'QF_CLIENT_SECRET')
      const response = await fetchImpl(`${oauthBase(env)}/oauth2/token`, {
        method: 'POST',
        headers: {
          Authorization: `Basic ${btoa(`${clientId}:${clientSecret}`)}`,
          'Content-Type': 'application/x-www-form-urlencoded',
          Accept: 'application/json',
        },
        body: 'grant_type=client_credentials&scope=content',
      })
      if (!response.ok) throw new ProxyFailure(503, 'qf_auth_unavailable')
      const body = await response.json()
      if (typeof body.access_token !== 'string' || !body.access_token) {
        throw new ProxyFailure(503, 'qf_auth_unavailable')
      }
      const expiresInSeconds = Number(body.expires_in)
      const lifetimeMs = Number.isFinite(expiresInSeconds)
        ? Math.max(0, expiresInSeconds * 1_000 - 30_000)
        : 0
      cachedToken = { value: body.access_token, expiresAtMs: Date.now() + lifetimeMs }
      return cachedToken.value
    })()
    try {
      return await tokenRequest
    } finally {
      tokenRequest = null
    }
  }

  async function qfResponse(env, path) {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const token = await accessToken(env)
      const response = await fetchImpl(`${contentBase(env)}${path}`, {
        headers: {
          Accept: 'application/json',
          'x-auth-token': token,
          'x-client-id': requiredSecret(env, 'QF_CLIENT_ID'),
        },
      })
      if (response.status !== 401 || attempt === 1) return response
      clearToken()
    }
    throw new ProxyFailure(503, 'qf_content_unavailable')
  }

  return {
    async fetch(request, env) {
      const url = new URL(request.url)
      const origin = request.headers.get('Origin')
      if (origin && origin !== allowedOrigin(env)) return failure(403, 'origin_not_allowed')
      const cors = corsHeaders(origin)

      if (request.method === 'OPTIONS') {
        return new Response(null, { status: 204, headers: cors })
      }
      if (request.method !== 'GET') return failure(405, 'method_not_allowed', cors)
      if (url.pathname === '/healthz') {
        return json({ ok: true, environment: qfEnvironment(env) }, 200, cors)
      }

      const path = allowedContentPath(url)
      if (!path) return failure(404, 'not_found', cors)
      try {
        const upstream = await qfResponse(env, path)
        if (!upstream.ok) return failure(upstream.status, 'qf_content_unavailable', cors)
        return new Response(upstream.body, {
          status: upstream.status,
          headers: {
            'Content-Type': upstream.headers.get('Content-Type') || 'application/json',
            'Cache-Control': 'no-store',
            ...cors,
          },
        })
      } catch (error) {
        if (error instanceof ProxyFailure) return failure(error.status, error.code, cors)
        return failure(503, 'qf_content_unavailable', cors)
      }
    },
  }
}

function allowedContentPath(url) {
  if (url.pathname === '/api/v4/resources/sync') {
    const keys = [...url.searchParams.keys()]
    if (keys.some((key) => !ALLOWED_SYNC_KEYS.has(key))) return null
    if (keys.some((key) => url.searchParams.getAll(key).length !== 1)) return null
    const cursor = url.searchParams.get('cursor')
    if (cursor) {
      if (keys.some((key) => key !== 'cursor' && key !== 'per_page')) return null
      return url.pathname + url.search
    }
    const resources = url.searchParams.get('resources')
    const bootstrap = url.searchParams.get('bootstrap')
    const token = url.searchParams.get('sync_token')
    if (bootstrap !== null && bootstrap !== 'true') return null
    if (resources !== RESOURCES || (bootstrap === 'true') === Boolean(token)) return null
    return url.pathname + url.search
  }
  if (SNAPSHOTS.has(url.pathname)) return url.pathname + url.search
  return null
}

function qfEnvironment(env) {
  const value = env.QF_ENV || 'prelive'
  if (value !== 'prelive' && value !== 'production') throw new ProxyFailure(503, 'qf_proxy_misconfigured')
  return value
}

function oauthBase(env) {
  return qfEnvironment(env) === 'production'
    ? 'https://oauth2.quran.foundation'
    : 'https://prelive-oauth2.quran.foundation'
}

function contentBase(env) {
  return qfEnvironment(env) === 'production'
    ? 'https://apis.quran.foundation/content'
    : 'https://apis-prelive.quran.foundation/content'
}

function requiredSecret(env, name) {
  const value = env[name]
  if (!value) throw new ProxyFailure(503, 'qf_proxy_unconfigured')
  return value
}

function allowedOrigin(env) {
  return env.ALLOWED_ORIGIN || 'https://sguergachi.github.io'
}

function corsHeaders(origin) {
  return origin
    ? {
        'Access-Control-Allow-Origin': origin,
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
        Vary: 'Origin',
      }
    : {}
}

function json(body, status, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers },
  })
}

function failure(status, code, headers = {}) {
  return json({ error: { code } }, status, headers)
}

class ProxyFailure extends Error {
  constructor(status, code) {
    super(code)
    this.status = status
    this.code = code
  }
}

const proxy = createQfProxy()
export default {
  fetch: (request, env) => proxy.fetch(request, env),
}
