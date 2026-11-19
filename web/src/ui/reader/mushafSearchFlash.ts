/**
 * The search hit on a settled Mushaf leaf (Android `MushafPager`, flashAyah /
 * flashWordPositions `takeIf { settled }`): the found words breathe orange on
 * the scroll reader's SearchHitFlash clock while the rest of the page recedes.
 */
export interface MushafSearchFlash {
  surahId: number
  ayah: number
  /** Word positions that breathe; every other word on the leaf recedes. */
  positions: ReadonlySet<number>
  /** The page recedes behind the hit until the breaths are over. */
  focusActive: boolean
}

/**
 * The words a pending search flash lights in its verse. A translation-only
 * hit (no word of its own) lights the whole verse, which is where the
 * English matched on a page that carries only Arabic.
 */
export function mushafFlashPositions(
  pending: { wordPosition: number; wordPositions: readonly number[] },
  versePositions: readonly number[],
): number[] {
  const present = new Set(versePositions)
  const asked = pending.wordPositions.length > 0
    ? pending.wordPositions
    : pending.wordPosition > 0 ? [pending.wordPosition] : []
  const found = asked.filter((position) => present.has(position))
  return found.length > 0 ? found : [...versePositions]
}

/** Whether [token] breathes, recedes, or stands as it is. */
export function mushafFlashRole(
  flash: MushafSearchFlash | null,
  surahId: number,
  ayah: number,
  position: number,
): 'hit' | 'recessed' | null {
  if (!flash) return null
  if (flash.surahId === surahId && flash.ayah === ayah && flash.positions.has(position)) return 'hit'
  return flash.focusActive ? 'recessed' : null
}
