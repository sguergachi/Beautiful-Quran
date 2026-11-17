import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import type { Reciter, Surah } from '../../data/models'
import {
  actionIsFetch,
  chapterActionLabel,
  chapterComplete,
  chapterEmpty,
  chapterFactLine,
  chapterKey,
  chapterOffersDelete,
  chapterProgressFactLine,
  chapterProgressFraction,
  chapterTrailingLabels,
  downloadPercent,
  formatUsage,
  isReciterBusy,
  isReciterPaused,
  recitationDownloads,
  reciterBytes,
  reciterDownloadLabel,
  reciterHeaderAction,
  reciterProgressLabel,
  type ChapterDownload,
  type RecitationUsage,
  type ReciterDownloads,
} from '../../playback/recitationDownloads'
import { BackChevron } from '../kit/BackChevron'
import { DisclosureChevron } from '../kit/DisclosureChevron'

type Pending =
  | { kind: 'all' }
  | { kind: 'reciter'; reciterId: number }
  | { kind: 'chapter'; reciterId: number; surahId: number }

const samePending = (a: Pending | null, b: Pending) => JSON.stringify(a) === JSON.stringify(b)

/** Settings note: "X stored · Y cached", refreshed whenever downloads change. */
export function useRecitationUsage(active: boolean): RecitationUsage | null {
  const progress = useSyncExternalStore(recitationDownloads.subscribe, recitationDownloads.getProgress)
  const [usage, setUsage] = useState<RecitationUsage | null>(null)
  useEffect(() => {
    if (!active) return
    let live = true
    void recitationDownloads.usage().then((next) => live && setUsage(next)).catch(() => undefined)
    return () => {
      live = false
    }
  }, [active, progress.revision])
  return usage
}

/**
 * Android `DownloadManagerPage`: what recitation is on this device, and the
 * verbs to fetch or let go of it — per reciter, per chapter, or all at once.
 * Confirmations happen in place on the paper (Keep / Delete), never a dialog.
 */
export function DownloadManagerScreen({
  reciters,
  surahs,
  onBack,
}: {
  reciters: Reciter[]
  surahs: Surah[]
  onBack: () => void
}) {
  const progress = useSyncExternalStore(recitationDownloads.subscribe, recitationDownloads.getProgress)
  const [usage, setUsage] = useState<RecitationUsage | null>(null)
  const [rows, setRows] = useState<ReciterDownloads[] | null>(null)
  const [expanded, setExpanded] = useState<Set<number>>(() => new Set())
  const [pending, setPending] = useState<Pending | null>(null)
  const [deleting, setDeleting] = useState(false)

  const refresh = useCallback(async () => {
    const [nextUsage, nextRows] = await Promise.all([
      recitationDownloads.usage(),
      recitationDownloads.catalog(reciters, surahs),
    ])
    setUsage(nextUsage)
    setRows(nextRows)
  }, [reciters, surahs])

  useEffect(() => {
    void refresh().catch(() => setRows([]))
  }, [refresh, progress.revision])

  // Listening warms the evictable cache behind our back; keep its share fresh.
  useEffect(() => {
    const id = window.setInterval(() => {
      if (document.visibilityState === 'visible') void recitationDownloads.usage().then(setUsage).catch(() => undefined)
    }, 5000)
    return () => window.clearInterval(id)
  }, [])

  const remove = (work: () => Promise<void>) => {
    if (deleting) return
    setPending(null)
    setDeleting(true)
    void work().finally(() => {
      void refresh().finally(() => setDeleting(false))
    })
  }

  const surahName = (id: number) => surahs.find((s) => s.id === id)?.nameTransliteration ?? ''
  const loaded = rows != null && usage != null
  const visibleRows = rows ?? reciters.map((reciter) => ({ reciter, chapters: [] }))

  return (
    <div className="download-manager">
      <button type="button" className="back settings-back" aria-label="Back" onClick={onBack}>
        <BackChevron />
      </button>
      <h1>Download manager</h1>
      {!loaded ? (
        <FactActionRow lead fact="Downloads" action="…" enabled={false} />
      ) : samePending(pending, { kind: 'all' }) ? (
        <ConfirmRow
          lead
          fact="Delete all downloads?"
          onKeep={() => setPending(null)}
          onDelete={() => remove(() => recitationDownloads.clearAll())}
        />
      ) : (
        <FactActionRow
          lead
          fact={formatUsage(usage!)}
          action={deleting ? "…" : usage!.listenBytes + usage!.keepBytes > 0 ? 'Delete all' : null}
          enabled={!deleting}
          onAction={() => setPending({ kind: 'all' })}
        />
      )}
      {progress.error ? <p className="settings-caption download-error">{progress.error}</p> : null}

      <section className="settings-section download-reciters">
        <h2>Reciters</h2>
        {visibleRows.map((row) => {
          const reciterId = row.reciter.id
          const isOpen = loaded && expanded.has(reciterId)
          const busy = isReciterBusy(progress, reciterId)
          const hasPaused = isReciterPaused(progress, reciterId)
          const downloadable = row.chapters.filter((ch) => {
            const key = chapterKey(reciterId, ch.surah.id)
            return !chapterComplete(ch) && progress.active !== key && !progress.queued.includes(key) && !progress.paused.has(key)
          })
          const hasResumable = hasPaused || row.chapters.some((ch) => !chapterComplete(ch) && !chapterEmpty(ch))
          const confirming = samePending(pending, { kind: 'reciter', reciterId })
          const action = !loaded
            ? null
            : deleting
              ? '…'
              : reciterHeaderAction({
                  expanded: isOpen,
                  busy,
                  paused: hasPaused && !busy,
                  hasDownloadable: downloadable.length > 0,
                  hasBytes: reciterBytes(row) > 0,
                  hasResumable,
                  confirming,
                })
          const live = reciterProgressLabel(progress, reciterId, surahName)
          const subtitle = !loaded ? '…' : !isOpen && live ? live : reciterDownloadLabel(row)
          const onAction = () => {
            switch (action) {
              case 'Pause':
                recitationDownloads.pauseReciter(reciterId)
                break
              case 'Resume':
                if (hasPaused) recitationDownloads.resumeReciter(row.reciter, surahs)
                else
                  recitationDownloads.downloadAll(
                    row.reciter,
                    row.chapters.filter((ch) => !chapterComplete(ch) && !chapterEmpty(ch)).map((ch) => ch.surah),
                  )
                break
              case 'Delete':
                setPending({ kind: 'reciter', reciterId })
                break
              case 'Download all':
                recitationDownloads.downloadAll(row.reciter, downloadable.map((ch) => ch.surah))
                break
            }
          }
          return (
            <div key={reciterId} className="download-reciter">
              <button
                type="button"
                className="download-reciter-name"
                aria-expanded={isOpen}
                aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${row.reciter.name}`}
                onClick={() => {
                  if (!loaded) return
                  setPending(null)
                  setExpanded((prev) => {
                    const next = new Set(prev)
                    if (next.has(reciterId)) next.delete(reciterId)
                    else next.add(reciterId)
                    return next
                  })
                }}
              >
                <span>{row.reciter.name}</span>
                <DisclosureChevron expanded={isOpen} />
              </button>
              {confirming ? (
                <ConfirmRow
                  fact={subtitle}
                  onKeep={() => setPending(null)}
                  onDelete={() => remove(() => recitationDownloads.clearReciter(row.reciter))}
                />
              ) : (
                <FactActionRow fact={subtitle} action={action} enabled={!deleting} onAction={onAction} />
              )}
              {isOpen ? (
                <div className="download-chapters">
                  {row.chapters.map((chapter) => (
                    <ChapterRow
                      key={chapter.surah.id}
                      row={chapter}
                      reciter={row.reciter}
                      progress={progress}
                      confirming={samePending(pending, { kind: 'chapter', reciterId, surahId: chapter.surah.id })}
                      enabled={!deleting}
                      onConfirm={() => setPending({ kind: 'chapter', reciterId, surahId: chapter.surah.id })}
                      onKeep={() => setPending(null)}
                      onDelete={() => remove(() => recitationDownloads.clearChapter(row.reciter, chapter.surah))}
                    />
                  ))}
                </div>
              ) : null}
            </div>
          )
        })}
      </section>
    </div>
  )
}

function ChapterRow({
  row,
  reciter,
  progress,
  confirming,
  enabled,
  onConfirm,
  onKeep,
  onDelete,
}: {
  row: ChapterDownload
  reciter: Reciter
  progress: ReturnType<typeof recitationDownloads.getProgress>
  confirming: boolean
  enabled: boolean
  onConfirm: () => void
  onKeep: () => void
  onDelete: () => void
}) {
  const key = chapterKey(reciter.id, row.surah.id)
  const downloading = progress.active === key
  const waiting = progress.queued.includes(key)
  const pausedClock = progress.paused.get(key)
  const paused = pausedClock != null
  const pausedAyahs = pausedClock && pausedClock.ayahCount > 0 ? pausedClock.ayah : row.cached
  const percent = downloading
    ? downloadPercent(progress.ayah, progress.ayahCount)
    : paused
      ? downloadPercent(pausedAyahs, row.surah.ayahCount)
      : null
  const complete = chapterComplete(row)
  const facts =
    downloading || paused
      ? chapterProgressFactLine(downloading ? progress.ayah : pausedAyahs, row.surah.ayahCount, downloading ? percent : null)
      : chapterFactLine(row, waiting, paused)
  const action = chapterActionLabel(row, downloading, waiting, paused)
  const labels = chapterTrailingLabels(action, chapterOffersDelete(row, downloading, waiting, paused))
  const fill = chapterProgressFraction(downloading, paused, percent, complete)
  const run = (label: string) => {
    switch (label) {
      case 'Download':
        recitationDownloads.downloadChapter(reciter, row.surah)
        break
      case 'Resume':
        recitationDownloads.resumeChapter(reciter, row.surah)
        break
      case 'Pause':
        recitationDownloads.pauseChapter(reciter.id, row.surah.id)
        break
      case 'Delete':
        onConfirm()
        break
    }
  }
  return (
    <div className="download-chapter">
      <div className="download-chapter-row">
        <div className="download-chapter-copy">
          <span className="download-chapter-name">{row.surah.nameTransliteration}</span>
          <span className="download-chapter-facts" data-live={downloading || paused || complete || undefined}>
            {facts}
          </span>
        </div>
        <div className="download-action-slot">
          {confirming ? (
            <ConfirmActions onKeep={onKeep} onDelete={onDelete} />
          ) : (
            labels.map((label) => (
              <ActionWord
                key={label}
                label={label}
                enabled={enabled}
                aria-label={`${label} ${row.surah.nameTransliteration}`}
                onClick={() => run(label)}
              />
            ))
          )}
        </div>
      </div>
      <div className="download-hairline" aria-hidden="true">
        {fill != null && fill > 0 ? <i style={{ transform: `scaleX(${fill})` }} /> : null}
      </div>
    </div>
  )
}

function FactActionRow({
  fact,
  action = null,
  enabled = true,
  onAction,
  lead = false,
}: {
  fact: string
  action?: string | null
  enabled?: boolean
  onAction?: () => void
  lead?: boolean
}) {
  return (
    <div className={`download-fact-row${lead ? ' download-fact-row--lead' : ''}`}>
      <span className="download-fact">{fact}</span>
      {action ? <ActionWord label={action} enabled={enabled && action !== '…'} onClick={() => onAction?.()} /> : null}
    </div>
  )
}

function ConfirmRow({
  fact,
  onKeep,
  onDelete,
  lead = false,
}: {
  fact: string
  onKeep: () => void
  onDelete: () => void
  lead?: boolean
}) {
  return (
    <div className={`download-fact-row${lead ? ' download-fact-row--lead' : ''}`}>
      <span className="download-fact">{fact}</span>
      <ConfirmActions onKeep={onKeep} onDelete={onDelete} />
    </div>
  )
}

function ConfirmActions({ onKeep, onDelete }: { onKeep: () => void; onDelete: () => void }) {
  return (
    <span className="download-confirm">
      <ActionWord label="Keep" fetch onClick={onKeep} />
      <ActionWord label="Delete" fetch={false} onClick={onDelete} />
    </span>
  )
}

/** One size for every trailing verb — Download, Resume, Pause, Delete. */
function ActionWord({
  label,
  onClick,
  enabled = true,
  fetch = actionIsFetch(label),
  'aria-label': ariaLabel,
}: {
  label: string
  onClick: () => void
  enabled?: boolean
  fetch?: boolean
  'aria-label'?: string
}) {
  return (
    <button
      type="button"
      className="download-action"
      data-fetch={fetch || undefined}
      disabled={!enabled}
      aria-label={ariaLabel}
      onClick={onClick}
    >
      {label}
    </button>
  )
}
