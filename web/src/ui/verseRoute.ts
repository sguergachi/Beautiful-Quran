import type { AyahRef } from '../share/gather'

/** Compact verse addresses work under both localhost and the Pages app base. */
export function parseVerseHash(hash: string, ayahCount: (surahId: number) => number | undefined): AyahRef | null {
  const match = /^#(\d{1,3}):(\d{1,3})$/.exec(hash)
  if (!match) return null
  const surahId = Number(match[1])
  const ayah = Number(match[2])
  const count = ayahCount(surahId)
  return count != null && ayah >= 1 && ayah <= count ? { surahId, ayah } : null
}

export function verseHash(place: AyahRef): string {
  return `#${place.surahId}:${place.ayah}`
}

export function verseLink(base: string, place: AyahRef): string {
  const url = new URL(base)
  url.hash = verseHash(place)
  return url.href
}
