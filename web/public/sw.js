/**
 * Offline shell for the GitHub Pages reader.
 *
 * Strategy:
 *   - Navigations / HTML → network-first; on success stash a copy for offline
 *     reload only. HTML is never served from cache while online (avoids a
 *     poisoned shell pointing at deleted hashed assets after a Pages deploy).
 *   - Hashed JS/CSS, wasm, fonts, quran.db → cache-first
 *   - After boot, the client may postMessage WARM_ASSETS so the DB/wasm land
 *     in the Cache API even though the first fetch happened before register
 *   - sw.js itself is never cached through this worker
 *
 * Bump CACHE (and OFFLINE_SHELL) whenever this contract changes. Activate
 * deletes every other cache name and reloads open clients so a poisoned
 * shell cannot stick.
 */
const CACHE = 'beautiful-quran-web-v11'
const OFFLINE_SHELL = 'beautiful-quran-offline-shell-v11'
const BASE = self.registration.scope

function isNavigationRequest(req, url) {
  if (req.mode === 'navigate') return true
  if (url.pathname.endsWith('.html')) return true
  // Scope root (…/app/ or …/app)
  const path = url.pathname.endsWith('/') ? url.pathname : `${url.pathname}/`
  const basePath = new URL(BASE).pathname
  return path === basePath
}

function isServiceWorkerScript(url) {
  return url.pathname.endsWith('/sw.js') || url.pathname.endsWith('sw.js')
}

function isHtmlRequest(url) {
  if (url.pathname.endsWith('.html')) return true
  const path = url.pathname.endsWith('/') ? url.pathname : `${url.pathname}/`
  const basePath = new URL(BASE).pathname
  return path === basePath
}

function shouldCacheAsset(url) {
  if (!url.href.startsWith(BASE)) return false
  if (isServiceWorkerScript(url)) return false
  if (isHtmlRequest(url)) return false
  return (
    url.pathname.endsWith('.db') ||
    url.pathname.endsWith('.js') ||
    url.pathname.endsWith('.css') ||
    url.pathname.endsWith('.ttf') ||
    url.pathname.endsWith('.woff2') ||
    url.pathname.endsWith('.wasm') ||
    url.pathname.endsWith('.svg') ||
    url.pathname.endsWith('.webmanifest') ||
    url.pathname.endsWith('.png')
  )
}

self.addEventListener('install', () => {
  // Do not precache HTML or the 27 MB DB during install — HTML must stay
  // network-first, and addAll(quran.db) blows mobile cache quotas.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys()
      const keep = new Set([CACHE, OFFLINE_SHELL])
      const hadStale = keys.some((k) => !keep.has(k))
      const deletes = []
      for (const k of keys) {
        if (!keep.has(k)) deletes.push(caches.delete(k))
      }
      await Promise.all(deletes)

      // Older workers may have stored index.html under the asset cache.
      // Strip any HTML out of CACHE so it can never be replayed as an asset.
      let purgedHtml = false
      try {
        const cache = await caches.open(CACHE)
        const reqs = await cache.keys()
        const htmlReqs = reqs.filter((r) => isHtmlRequest(new URL(r.url)))
        if (htmlReqs.length > 0) {
          purgedHtml = true
          await Promise.all(htmlReqs.map((r) => cache.delete(r)))
        }
      } catch {
        /* ignore */
      }

      await self.clients.claim()

      // Only force-reload when we actually cleared a poisoned shell — a bare
      // first install must not bounce the user who just finished booting.
      if (hadStale || purgedHtml) {
        const clients = await self.clients.matchAll({
          type: 'window',
          includeUncontrolled: true,
        })
        await Promise.all(
          clients.map((client) => {
            if ('navigate' in client) return client.navigate(client.url)
            return undefined
          }),
        )
      }
    })(),
  )
})

self.addEventListener('message', (event) => {
  const data = event.data
  if (!data || data.type !== 'WARM_ASSETS' || !Array.isArray(data.urls)) return
  event.waitUntil(warmAssets(data.urls))
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)
  if (!url.href.startsWith(BASE)) return

  // Always hit the network for the worker script so updates are not pinned.
  if (isServiceWorkerScript(url)) {
    event.respondWith(fetch(req))
    return
  }

  if (isNavigationRequest(req, url)) {
    event.respondWith(networkFirstNavigation(req))
    return
  }

  event.respondWith(cacheFirstAsset(req, url))
})

async function networkFirstNavigation(req) {
  try {
    const res = await fetch(req)
    if (res.ok) {
      const copy = res.clone()
      void caches.open(OFFLINE_SHELL).then((c) => c.put(req, copy))
    }
    return res
  } catch {
    const cached =
      (await caches.match(req, { cacheName: OFFLINE_SHELL })) ||
      (await caches.match(req.url, { cacheName: OFFLINE_SHELL }))
    if (cached) return cached
    return new Response(
      `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><title>Beautiful Quran</title></head><body style="margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#FAF3E8;color:#1C1B18;font-family:Georgia,serif;text-align:center;padding:2rem"><div><h1 style="font-weight:500;letter-spacing:.02em">Beautiful Quran</h1><p style="opacity:.7">You appear to be offline. Reconnect and reload.</p></div></body></html>`,
      {
        status: 503,
        headers: { 'Content-Type': 'text/html; charset=utf-8' },
      },
    )
  }
}

async function warmAssets(urls) {
  const cache = await caches.open(CACHE)
  await Promise.all(
    urls.map(async (href) => {
      try {
        const url = new URL(href, self.location.href)
        if (!shouldCacheAsset(url)) return
        if (await cache.match(href)) return
        const res = await fetch(href, { credentials: 'same-origin' })
        if (res.ok) await cache.put(href, res)
      } catch {
        /* optional warm */
      }
    }),
  )
}

async function cacheFirstAsset(req, url) {
  const cached = await caches.match(req)
  if (cached) return cached
  try {
    const res = await fetch(req)
    if (res.ok && shouldCacheAsset(url)) {
      const copy = res.clone()
      void caches.open(CACHE).then((c) => c.put(req, copy))
    }
    return res
  } catch {
    return cached || Response.error()
  }
}
