import { useEffect, useState } from 'react'

/**
 * Progressive mount window around the reading / recitation center.
 *
 * **Invariant:** free scroll must never unmount content under the viewport.
 * The window only *expands* as the user scrolls or recitation advances.
 * Shrinking (and the blank pad gaps it caused) happens only on a far jump
 * via [mountRangeForAyah].
 */
export const WINDOW_BEFORE = 16
export const WINDOW_AFTER = 24

export interface AyahMountRange {
  lo: number
  hi: number
  /** True when every ayah in the surah is a real block (no pad spacers). */
  complete: boolean
}

/** Estimated height per unmounted ayah for top/bottom scroll padding. */
export const AYAH_SPACER_EST_PX = 120

/** Pure window around [centerAyah] — unit-tested. */
export function slidingAyahMountRange(
  ayahCount: number,
  centerAyah: number,
): AyahMountRange {
  if (ayahCount < 1) return { lo: 1, hi: 1, complete: true }
  const center = Math.min(ayahCount, Math.max(1, Math.round(centerAyah)))
  const lo = Math.max(1, center - WINDOW_BEFORE)
  const hi = Math.min(ayahCount, center + WINDOW_AFTER)
  return { lo, hi, complete: lo === 1 && hi === ayahCount }
}

/**
 * Materialize a requested ayah before the focus controller measures it.
 * Already-visible targets keep the current window. Far targets get a fresh
 * tight window (the only path that unmounts distant content).
 */
export function mountRangeForAyah(
  current: AyahMountRange,
  ayahCount: number,
  requestedAyah: number,
): AyahMountRange {
  if (ayahCount < 1) return { lo: 1, hi: 1, complete: true }
  const target = Math.min(ayahCount, Math.max(1, Math.round(requestedAyah)))
  if (target >= current.lo && target <= current.hi) return current
  return slidingAyahMountRange(ayahCount, target)
}

/**
 * Grow [current] so it covers [center] with a comfortable margin.
 * **Never shrinks** — unmounting mid-scroll left blank spacer gaps.
 */
export function expandWindowToward(
  current: AyahMountRange,
  ayahCount: number,
  centerAyah: number,
): AyahMountRange {
  if (ayahCount < 1) return { lo: 1, hi: 1, complete: true }
  if (current.complete) return current
  const center = Math.min(ayahCount, Math.max(1, Math.round(centerAyah)))
  const wantLo = Math.max(1, center - WINDOW_BEFORE)
  const wantHi = Math.min(ayahCount, center + WINDOW_AFTER)
  const lo = Math.min(current.lo, wantLo)
  const hi = Math.max(current.hi, wantHi)
  const complete = lo === 1 && hi === ayahCount
  if (lo === current.lo && hi === current.hi) return current
  return { lo, hi, complete }
}

/**
 * Progressive ayah mount window for the reader.
 *
 * Starts tight around the open anchor, then expands as focus / playhead moves.
 * Never unmounts during scroll — only a far selector/search jump re-windows.
 */
export function useProgressiveAyahWindow(
  ayahCount: number,
  centerAyah: number,
  surahKey: number | undefined,
  requestedAyah: number | null = null,
): AyahMountRange {
  const [range, setRange] = useState<AyahMountRange>(() =>
    slidingAyahMountRange(ayahCount, centerAyah),
  )

  // Surah identity change — hard reset around the landing verse.
  useEffect(() => {
    if (!surahKey || ayahCount < 1) {
      setRange({ lo: 1, hi: 1, complete: true })
      return
    }
    setRange(slidingAyahMountRange(ayahCount, centerAyah))
    // Re-window only when the surah identity changes — not on every center tick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [surahKey, ayahCount])

  // Follow playhead / focus by expanding only (never shrink → never blank).
  useEffect(() => {
    if (!surahKey || ayahCount < 1) return
    setRange((prev) => expandWindowToward(prev, ayahCount, centerAyah))
  }, [centerAyah, ayahCount, surahKey])

  // Selector/search/playback jumps may arrive before the window covers them.
  // Far targets re-window tightly; near targets no-op.
  useEffect(() => {
    if (!surahKey || requestedAyah == null) return
    setRange((prev) => mountRangeForAyah(prev, ayahCount, requestedAyah))
  }, [requestedAyah, ayahCount, surahKey])

  return range
}
