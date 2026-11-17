/** The row resting under a dial's centre slot after scrolling [scrollTop]. */
export function dialIndexAt(scrollTop: number, itemHeight: number, count: number): number {
  if (count <= 0) return 0
  return Math.min(count - 1, Math.max(0, Math.round(scrollTop / itemHeight)))
}

/** The ayah dial stays within the centred surah as that surah changes. */
export function clampAyah(ayah: number, ayahCount: number): number {
  return Math.min(Math.max(1, ayah), Math.max(1, ayahCount))
}
