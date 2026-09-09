import { assetUrl } from './assetUrl'

/**
 * Register the offline service worker only after the app has booted.
 *
 * Registering from a blank/failed shell is what left phones on a poisoned
 * Cache API entry pointing at deleted hashed assets. Wait until sql.js +
 * quran.db have loaded successfully, then install the worker and warm the
 * DB/wasm into the Cache API (the boot fetch happened before register).
 */
export function registerServiceWorker(): void {
  if (!('serviceWorker' in navigator)) return
  // Dev server HMR and the worker fight over the same scope.
  if (import.meta.env.DEV) return

  window.setTimeout(() => {
    void navigator.serviceWorker
      .register(assetUrl('sw.js'), { scope: import.meta.env.BASE_URL })
      .then((reg) => {
        warmCriticalAssets(reg)
      })
      .catch(() => {
        /* optional */
      })
  }, 0)
}

/** Ask the active worker to cache assets the boot path already downloaded. */
function warmCriticalAssets(reg: ServiceWorkerRegistration): void {
  const urls = [
    assetUrl('quran.db'),
    assetUrl('search_concepts.json'),
    assetUrl('sql-wasm-browser.wasm'),
  ]
  const post = (sw: ServiceWorker) => {
    sw.postMessage({ type: 'WARM_ASSETS', urls })
  }
  if (reg.active) {
    post(reg.active)
    return
  }
  void navigator.serviceWorker.ready.then((ready) => {
    if (ready.active) post(ready.active)
  })
}
