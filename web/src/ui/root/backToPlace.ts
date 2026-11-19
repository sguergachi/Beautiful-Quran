/**
 * "Back to {chapter · ayah}" after a Root Viewer concordance jump — port of
 * Android `RootReturnTarget` and MainActivity's `rootReturnTarget` /
 * `rootReturnDismissArmed` pair.
 *
 * The capsule stays until the reader moves by hand (scroll, page turn, a
 * move across the paper stack), then clears 30 s after that first move. The
 * programmatic settle that lands the jump itself is ignored by a short grace.
 *
 * Pure: no DOM, no app store. The live singleton (wired to window gestures
 * and the paper stack) is in `backToPlaceStore.ts`.
 */

export interface BackToPlaceTarget {
  surahId: number
  ayah: number
  surahNameTransliteration: string
}

/** Android `ROOT_RETURN_SETTLE_GRACE_MS`. */
export const BACK_TO_PLACE_SETTLE_GRACE_MS = 1_200
/** Android `ROOT_RETURN_DISMISS_MS`. */
export const BACK_TO_PLACE_DISMISS_MS = 30_000

/** Chapter name alone, for the capsule's primary mark. */
export function backToPlaceChapterLabel(target: BackToPlaceTarget): string {
  return target.surahNameTransliteration.trim() || String(target.surahId)
}

/** Compact ayah reference, e.g. "2:3". */
export function backToPlaceAyahLabel(target: BackToPlaceTarget): string {
  return `${target.surahId}:${target.ayah}`
}

/** Full spoken label, e.g. "Back to Al-Baqarah 2:3". */
export function backToPlaceLabel(target: BackToPlaceTarget): string {
  const name = target.surahNameTransliteration.trim()
  return `Back to ${name ? `${name} ` : ''}${backToPlaceAyahLabel(target)}`
}

/**
 * Where the hold started, when a concordance jump leaves it. Null when the
 * origin is unknown or the jump lands on the same verse (nothing to go back
 * to) — Android `jumpFromConcordance` keeps any earlier target then.
 */
export function concordanceReturnTarget(
  origin: { surahId: number; ayah: number },
  destination: { surahId: number; ayah: number },
  surahNameTransliteration: string,
): BackToPlaceTarget | null {
  if (origin.surahId <= 0 || origin.ayah <= 0) return null
  if (origin.surahId === destination.surahId && origin.ayah === destination.ayah) return null
  return { surahId: origin.surahId, ayah: origin.ayah, surahNameTransliteration }
}

export interface BackToPlaceState {
  target: BackToPlaceTarget | null
  /** When the jump landed (ms); hand moves before the grace ends are the settle. */
  rememberedAt: number
  /** First hand move after the grace (ms); null until then. */
  armedAt: number | null
}

export const EMPTY_BACK_TO_PLACE: BackToPlaceState = {
  target: null,
  rememberedAt: 0,
  armedAt: null,
}

export function rememberPlace(target: BackToPlaceTarget, now: number): BackToPlaceState {
  return { target, rememberedAt: now, armedAt: null }
}

/** A hand move: arms the dismiss countdown once the settle grace is over. */
export function noteUserMoved(state: BackToPlaceState, now: number): BackToPlaceState {
  if (state.target == null || state.armedAt != null) return state
  if (now - state.rememberedAt < BACK_TO_PLACE_SETTLE_GRACE_MS) return state
  return { ...state, armedAt: now }
}

/** When the capsule clears itself; null while it waits for a hand move. */
export function backToPlaceExpiresAt(state: BackToPlaceState): number | null {
  if (state.target == null || state.armedAt == null) return null
  return state.armedAt + BACK_TO_PLACE_DISMISS_MS
}

export interface BackToPlaceEnv {
  now(): number
  setTimer(fn: () => void, ms: number): unknown
  clearTimer(handle: unknown): void
  /**
   * Start listening for hand moves (scroll, page turn, paper-stack move);
   * returns the stop function. Called once the settle grace is over.
   */
  listenForUserMoves(onMove: () => void): () => void
}

export interface BackToPlaceStore {
  getSnapshot(): BackToPlaceState
  subscribe(listener: () => void): () => void
  /** A concordance jump left [target]; replaces any earlier one. */
  remember(target: BackToPlaceTarget): void
  /** The reader moved by hand (scroll / page turn / stack move). */
  userMoved(): void
  clear(): void
  /** Clears and hands back the place to return to (null if none). */
  takeTarget(): BackToPlaceTarget | null
}

export function createBackToPlaceStore(env: BackToPlaceEnv): BackToPlaceStore {
  let state = EMPTY_BACK_TO_PLACE
  const listeners = new Set<() => void>()
  let graceTimer: unknown = null
  let dismissTimer: unknown = null
  let stopListening: (() => void) | null = null

  const teardown = () => {
    if (graceTimer != null) env.clearTimer(graceTimer)
    if (dismissTimer != null) env.clearTimer(dismissTimer)
    graceTimer = null
    dismissTimer = null
    stopListening?.()
    stopListening = null
  }

  const set = (next: BackToPlaceState) => {
    if (next === state) return
    state = next
    for (const listener of [...listeners]) listener()
  }

  const store: BackToPlaceStore = {
    getSnapshot: () => state,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    remember(target) {
      teardown()
      set(rememberPlace(target, env.now()))
      graceTimer = env.setTimer(() => {
        graceTimer = null
        stopListening = env.listenForUserMoves(() => store.userMoved())
      }, BACK_TO_PLACE_SETTLE_GRACE_MS)
    },
    userMoved() {
      const next = noteUserMoved(state, env.now())
      if (next === state) return
      // Armed once: the countdown runs from the first move, not the last.
      stopListening?.()
      stopListening = null
      set(next)
      const expiresAt = backToPlaceExpiresAt(next)
      if (expiresAt == null) return
      dismissTimer = env.setTimer(() => {
        dismissTimer = null
        store.clear()
      }, Math.max(0, expiresAt - env.now()))
    },
    clear() {
      teardown()
      set(EMPTY_BACK_TO_PLACE)
    },
    takeTarget() {
      const target = state.target
      store.clear()
      return target
    },
  }
  return store
}
