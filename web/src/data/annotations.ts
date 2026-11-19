/**
 * Verse annotations (ḥawāshī) — Android `AnnotationRepository`.
 *
 * Reader-written notes are user data: one entry per verse, keyed
 * `<source>:<surahId>:<ayah>` so a later scholar's gloss can sit beside the
 * reader's own without reshaping storage. Blank text deletes the entry.
 * Turning annotations off in Settings hides them; stored writing is never
 * deleted (docs/ANNOTATIONS.md).
 */
export type AnnotationSource = 'reader'

export interface Annotation {
  surahId: number
  ayah: number
  text: string
  source: AnnotationSource
}

const KEY = 'beautiful-quran-annotations'

export function annotationKey(surahId: number, ayah: number, source: AnnotationSource = 'reader'): string {
  return `${source}:${surahId}:${ayah}`
}

/** Stored annotations by [annotationKey]. Malformed entries are dropped. */
export type AnnotationMap = Readonly<Record<string, string>>

/** Parses the stored JSON; anything malformed is dropped, never thrown. */
export function parseAnnotations(raw: string | null): AnnotationMap {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as unknown
    if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const out: Record<string, string> = {}
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof value === 'string' && value.trim() && /^reader:\d+:\d+$/.test(key)) out[key] = value
    }
    return out
  } catch {
    return {}
  }
}

export function loadAnnotations(): AnnotationMap {
  try {
    return parseAnnotations(localStorage.getItem(KEY))
  } catch {
    return {}
  }
}

export function saveAnnotations(map: AnnotationMap): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(map))
  } catch {
    /* storage full or blocked: keep the in-memory copy */
  }
}

/** Returns a new map with [text] stored for the verse; blank text removes it. */
export function withAnnotation(map: AnnotationMap, surahId: number, ayah: number, text: string): AnnotationMap {
  const key = annotationKey(surahId, ayah)
  const next: Record<string, string> = { ...map }
  if (text.trim()) next[key] = text
  else delete next[key]
  return next
}

export function annotationFor(map: AnnotationMap, surahId: number, ayah: number): string | null {
  return map[annotationKey(surahId, ayah)] ?? null
}
