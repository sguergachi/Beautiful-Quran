import { useEffect, useState } from 'react'
import { QuranRepository } from '../../data/repository'
import { runtimeMushafCache } from '../../data/runtimeMushaf'
import {
  buildMushafPage,
  MUSHAF_PAGE_COUNT,
  mushafTokenEndsAyah,
  pageAyahs,
  type MushafWordPlacement,
} from '../../domain/mushafPage'
import { formatAyahNumberMark } from '../../util/digits'
import { appStore } from '../../store/appStore'

/** Playback highlight when it exists; otherwise the ayah the sheet was opened on. */
function followedAyah(activeAyah: number | null, openAyah: number): number {
  return activeAyah != null && activeAyah > 0 ? activeAyah : Math.max(1, openAyah)
}

function ayahLastPosition(surahId: number, ayahNumber: number): number {
  const verse = QuranRepository.surahContent(surahId).ayahs.find((item) => item.number === ayahNumber)
  let last = 0
  for (const word of verse?.words ?? []) {
    if (word.position > last) last = word.position
  }
  return last
}

/**
 * One Madinah leaf. The 604 page boundaries come from the Quran Foundation
 * page map once it is on this device. The words are Hafs: the per-page QCF
 * faces are not shipped to the browser.
 */
export function MushafReader({
  activeSurahId,
  activeAyah,
  openAyah,
  openRevision,
  english,
  onPlayWord,
}: {
  activeSurahId: number
  activeAyah: number | null
  openAyah: number
  openRevision: number
  english: boolean
  onPlayWord: (surahId: number, ayah: number, position: number) => void
}) {
  const targetAyah = followedAyah(activeAyah, openAyah)
  // Swipes stick until the opened ayah or the recited ayah changes.
  const followKey = `${activeSurahId}:${openRevision}:${targetAyah}`
  const [manualPage, setManualPage] = useState<number | null>(null)
  const [manualFor, setManualFor] = useState(followKey)
  if (manualFor !== followKey) {
    setManualFor(followKey)
    setManualPage(null)
  }
  const [ready, setReady] = useState(() => runtimeMushafCache?.layoutReady() ?? false)
  const [drag, setDrag] = useState<{ x: number; y: number } | null>(null)

  useEffect(() => {
    if (!runtimeMushafCache) return
    return runtimeMushafCache.subscribe(() => setReady(runtimeMushafCache.layoutReady()))
  }, [])

  const derived = ready && runtimeMushafCache
    ? runtimeMushafCache.pageOfAyah(activeSurahId, targetAyah)
    : null
  const page = manualPage ?? derived ?? 1

  const turn = (delta: number) => {
    setManualPage((current) => {
      const base = current ?? derived ?? 1
      return Math.min(MUSHAF_PAGE_COUNT, Math.max(1, base + delta))
    })
  }

  if (!ready || !runtimeMushafCache) {
    return (
      <div className="mushaf-wait">
        <p>The printed page is still arriving.</p>
        <p>It uses the same page map as the book, and fills in on its own.</p>
      </div>
    )
  }

  const rows = runtimeMushafCache.pageWords(page) ?? []
  const placements: MushafWordPlacement[] = rows.map((row) => {
    const content = QuranRepository.surahContent(row.surah_id)
    const ayah = content.ayahs.find((item) => item.number === row.ayah_number)
    const word = ayah?.words.find((item) => item.position === row.position)
    return {
      surahId: row.surah_id,
      ayah: row.ayah_number,
      position: row.position,
      line: row.qcf_line,
      arabic: word?.arabic ?? '',
    }
  })
  const leaf = buildMushafPage(page, placements)
  const head = placements[0]
  const headSurah = head ? QuranRepository.surahContent(head.surahId).surah : null
  const englishAyahs = pageAyahs(placements)

  return (
    <div
      className="mushaf"
      dir={english ? 'ltr' : 'rtl'}
      onPointerDown={(event) => setDrag({ x: event.clientX, y: event.clientY })}
      onPointerUp={(event) => {
        if (!drag) return
        const dx = event.clientX - drag.x
        const dy = event.clientY - drag.y
        setDrag(null)
        if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(dy)) return
        // The next leaf is to the left, so a finger moving right turns forward.
        turn(dx > 0 ? 1 : -1)
      }}
      onPointerCancel={() => setDrag(null)}
    >
      <div className="mushaf-head">
        <span>{headSurah?.nameTransliteration ?? ''}</span>
        <span>{headSurah?.nameArabic ?? ''}</span>
      </div>
      {english ? (
        <div className="mushaf-english">
          {englishAyahs.map((item) => {
            const content = QuranRepository.surahContent(item.surahId)
            const ayah = content.ayahs.find((candidate) => candidate.number === item.ayah)
            const active = item.surahId === activeSurahId && item.ayah === activeAyah
            return (
              <p
                key={`${item.surahId}:${item.ayah}`}
                className="mushaf-english-ayah"
                data-active={active || undefined}
              >
                <button
                  type="button"
                  className="mushaf-english-text"
                  onClick={() => onPlayWord(item.surahId, item.ayah, 1)}
                >
                  {ayah?.translation}
                </button>
                <button
                  type="button"
                  className="mushaf-mark"
                  aria-label={`Gather ayah ${item.ayah}`}
                  onClick={(event) => {
                    event.stopPropagation()
                    appStore.onMarkTap(item.surahId, item.ayah)
                  }}
                >
                  {formatAyahNumberMark(item.ayah, false)}
                </button>
              </p>
            )
          })}
        </div>
      ) : (
        <div className="mushaf-lines">
          {leaf.lines.map((line) => (
            <p key={line.number} className="mushaf-line" lang="ar">
              {line.tokens.map((token) => {
                const active =
                  token.surahId === activeSurahId && token.ayah === activeAyah
                const endsAyah = mushafTokenEndsAyah(
                  token.position,
                  ayahLastPosition(token.surahId, token.ayah),
                )
                return (
                  <span key={`${token.surahId}:${token.ayah}:${token.position}`}>
                    <button
                      type="button"
                      className="mushaf-word"
                      data-active={active || undefined}
                      onClick={() => onPlayWord(token.surahId, token.ayah, token.position)}
                    >
                      {token.arabic}
                    </button>
                    {endsAyah ? (
                      <button
                        type="button"
                        className="mushaf-mark"
                        aria-label={`Gather ayah ${token.ayah}`}
                        onClick={(event) => {
                          event.stopPropagation()
                          appStore.onMarkTap(token.surahId, token.ayah)
                        }}
                      >
                        {formatAyahNumberMark(token.ayah, true)}
                      </button>
                    ) : null}
                  </span>
                )
              })}
            </p>
          ))}
        </div>
      )}
      <div className="mushaf-folio">{page}</div>
    </div>
  )
}
