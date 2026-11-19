import { useEffect, useRef, useState } from 'react'
import { assetUrl } from '../../assetUrl'
import { appStore, useAppSelector } from '../../store/appStore'
import {
  runtimeMushafCache,
  type RuntimeCacheFailure,
  type RuntimeCacheStatus,
} from '../../data/runtimeMushaf'
import { QF_TIMING_RECITERS, runtimeTimingsCache } from '../../data/runtimeTimings'
import {
  MUSHAF_GLYPH_WIDENS,
  type MushafGlyphWiden,
} from '../../data/settings'
import { customizeSummary } from '../../data/customizePolicy'
import { formatUsage } from '../../playback/recitationDownloads'
import { CustomizeScreen } from './CustomizeScreen'
import { AcknowledgementsScreen } from './AcknowledgementsScreen'
import { ComponentKitScreen, type InkLabState } from './ComponentKitScreen'
import { DownloadManagerScreen, useRecitationUsage } from './DownloadManagerScreen'
import { ReadingHelp } from './ReadingHelp'
import { ReciterChoices, RecitersScreen } from './RecitersScreen'
import { PlaybackScreen } from './PlaybackScreen'
import { playbackSummary } from '../../data/playbackPolicy'
import { settingsLayerFor, type StackLayer } from '../paper/stack'
import {
  SHIPPED_CHECK_PARAMS,
  SHIPPED_CHECK_REVISION,
  type BrushCheckParams,
} from '../kit/brushCheck'
import {
  brushCircleParams,
  SHIPPED_BRUSH_REVISION,
  type BrushCircleParams,
} from '../kit/brushMark'
import { AlphaTag } from '../kit/AlphaTag'
import { BackChevron } from '../kit/BackChevron'
import { DisclosureChevron } from '../kit/DisclosureChevron'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { NuqtaParamsContext, SHIPPED_NUQTA, type NuqtaParams } from '../kit/nuqta'
import { PaperSwitch } from '../kit/PaperSwitch'
import { rearmEducation } from '../../data/education'
import { version as APP_VERSION } from '../../../package.json'
import './settings.css'

/** Triple-tap window for developer unlock — matches Android SettingsScreen. */
const DEVELOPER_TAP_RESET_MS = 1500

type SettingsPage = 'main' | 'reciters' | 'playback' | 'customize' | 'downloads' | 'acknowledgements' | 'help' | 'kit'

export function SettingsScreen({
  stackLayer,
  hasReader,
}: {
  stackLayer: StackLayer
  hasReader: boolean
}) {
  // Settings ignores karaoke emits — only settings / reciter catalog matter.
  const s = useAppSelector((st) => st.settings)
  const reciters = useAppSelector((st) => st.reciters)
  const surahs = useAppSelector((st) => st.surahs)
  const layer = settingsLayerFor(hasReader)
  const isTop = stackLayer === layer
  // Once it has been laid on the page, the sheet is drawn back off it when it
  // goes; before that it was never on the page, and has nothing to animate.
  const [laid, setLaid] = useState(false)
  if (isTop && !laid) setLaid(true)
  const depth = Math.max(0, stackLayer - layer)
  const [page, setPage] = useState<SettingsPage>('main')
  const scrollRef = useRef<HTMLDivElement>(null)
  const favoriteReciters = reciters.filter((reciter) => s.favoriteReciterIds.includes(reciter.id))
  const usage = useRecitationUsage(isTop && page === 'main')

  useEffect(() => {
    if (!isTop) setPage('main')
  }, [isTop])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 })
  }, [page])

  useEffect(() => {
    if (!isTop || page === 'main') return
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      event.preventDefault()
      event.stopPropagation()
      setPage('main')
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [isTop, page])

  // Session-only live knobs for the ink labs (not persisted). Owned here so
  // Customize and every Settings control follow the Component kit live.
  const [brushParams, setBrushParams] = useState<BrushCircleParams>(() =>
    brushCircleParams(s.brushCircleStyle),
  )
  const [checkParams, setCheckParams] = useState<BrushCheckParams>(() => ({
    ...SHIPPED_CHECK_PARAMS,
  }))
  const [paintToken, setPaintToken] = useState(0)
  const [checkPaintToken, setCheckPaintToken] = useState(0)
  const [nuqtaParams, setNuqtaParams] = useState<NuqtaParams>(() => ({ ...SHIPPED_NUQTA }))
  /** Bumps when paste/reset/preset loads so Base UI sliders remount with exact values. */
  const [sliderEpoch, setSliderEpoch] = useState(0)
  const [checkSliderEpoch, setCheckSliderEpoch] = useState(0)
  const [developerTapCount, setDeveloperTapCount] = useState(0)
  const [educationRearmed, setEducationRearmed] = useState(false)
  const lastStyleRef = useRef(s.brushCircleStyle)
  const lastShipRef = useRef(SHIPPED_BRUSH_REVISION)
  const lastCheckShipRef = useRef(SHIPPED_CHECK_REVISION)

  // Reseed when the saved preset changes, or when shipped BASE revision bumps.
  useEffect(() => {
    const styleChanged = lastStyleRef.current !== s.brushCircleStyle
    const shipChanged = lastShipRef.current !== SHIPPED_BRUSH_REVISION
    lastStyleRef.current = s.brushCircleStyle
    lastShipRef.current = SHIPPED_BRUSH_REVISION
    if (shipChanged) {
      if (s.brushCircleStyle !== 'baseline') {
        appStore.updateSettings({ brushCircleStyle: 'baseline' })
      }
      setBrushParams(brushCircleParams('baseline'))
      setPaintToken((n) => n + 1)
      setSliderEpoch((n) => n + 1)
    } else if (styleChanged) {
      setBrushParams(brushCircleParams(s.brushCircleStyle))
      setPaintToken((n) => n + 1)
      setSliderEpoch((n) => n + 1)
    }
  }, [s.brushCircleStyle])

  // Reseed check lab when shipped check design changes.
  useEffect(() => {
    if (lastCheckShipRef.current === SHIPPED_CHECK_REVISION) return
    lastCheckShipRef.current = SHIPPED_CHECK_REVISION
    setCheckParams({ ...SHIPPED_CHECK_PARAMS })
    setCheckPaintToken((n) => n + 1)
    setCheckSliderEpoch((n) => n + 1)
  }, [])

  // Reset developer tap counter after a quiet window.
  useEffect(() => {
    if (developerTapCount <= 0) return
    const t = window.setTimeout(() => setDeveloperTapCount(0), DEVELOPER_TAP_RESET_MS)
    return () => window.clearTimeout(t)
  }, [developerTapCount])

  const lab: InkLabState = {
    brushStyle: s.brushCircleStyle,
    brushParams,
    setBrushParams,
    paintToken,
    bumpPaint: () => setPaintToken((n) => n + 1),
    sliderEpoch,
    bumpSliders: () => setSliderEpoch((n) => n + 1),
    checkParams,
    setCheckParams,
    checkPaintToken,
    bumpCheckPaint: () => setCheckPaintToken((n) => n + 1),
    checkSliderEpoch,
    bumpCheckSliders: () => setCheckSliderEpoch((n) => n + 1),
    nuqtaParams,
    setNuqtaParams,
    noteStyle: (style) => {
      lastStyleRef.current = style
    },
  }

  const onLogoClick = () => {
    const next = developerTapCount + 1
    if (next >= 3) {
      appStore.updateSettings({ developerMode: !s.developerMode })
      setDeveloperTapCount(0)
    } else {
      setDeveloperTapCount(next)
    }
  }

  const back = () => setPage('main')

  return (
    <div
      className="sheet"
      data-name="settings"
      data-layer={layer}
      data-depth={depth}
      data-active={isTop}
      data-laid={laid || undefined}
    >
      <NuqtaParamsContext.Provider value={nuqtaParams}>
      <div className="settings" ref={scrollRef}>
        {page === 'reciters' ? (
          <RecitersScreen
            reciters={reciters}
            selectedId={s.reciterId}
            favorites={s.favoriteReciterIds}
            onSelect={(reciterId) => appStore.selectReciter(reciterId)}
            onToggleFavorite={(id) => appStore.toggleFavoriteReciter(id)}
            onBack={back}
          />
        ) : page === 'playback' ? (
          <PlaybackScreen
            settings={s}
            brushParams={brushParams}
            paintToken={paintToken}
            onBack={back}
          />
        ) : page === 'customize' ? (
          <CustomizeScreen
            settings={s}
            brushParams={brushParams}
            paintToken={paintToken}
            checkParams={checkParams}
            checkPaintToken={checkPaintToken}
            onBack={back}
          />
        ) : page === 'downloads' ? (
          <DownloadManagerScreen reciters={reciters} surahs={surahs} onBack={back} />
        ) : page === 'kit' ? (
          <ComponentKitScreen lab={lab} onBack={back} />
        ) : page === 'help' ? (
          <ReadingHelp onBack={back} />
        ) : page === 'acknowledgements' ? (
          <AcknowledgementsScreen onBack={back} />
        ) : (
          <>
        <button
          type="button"
          className="back settings-back"
          aria-label="Back"
          onClick={() => appStore.goBack()}
        >
          <BackChevron />
        </button>
        <h1>Settings</h1>
        <section className="settings-section settings-section-reciter">
          <h2>Reciter</h2>
          <ReciterChoices
            reciters={favoriteReciters}
            selectedId={s.reciterId}
            onSelect={(reciterId) => appStore.selectReciter(reciterId)}
          />
          {favoriteReciters.length === 0 ? (
            <p className="settings-caption">No favorites yet — keep them from the full list.</p>
          ) : null}
          {/* A link under the favourites, in Home search's "Show N more" voice. */}
          <button type="button" className="settings-show-all-reciters" onClick={() => setPage('reciters')}>
            <span>Show all {reciters.length} reciters</span>
            <DisclosureChevron expanded={false} />
          </button>
        </section>

        {/* Android: Playback, then Customize, follow reciter selection without
            another heading; then Download manager and Acknowledgements, 20px apart.
            Reading help (keyboard shortcuts) is the web's own, so it follows. */}
        <section className="settings-section settings-section-nav">
          <NavRow label="Playback" note={playbackSummary(s.verseGapSeconds)} onClick={() => setPage('playback')} />
          <NavRow label="Customize" note={customizeSummary(s)} onClick={() => setPage('customize')} />
          <NavRow
            label="Download manager"
            note={usage ? formatUsage(usage) : '…'}
            onClick={() => setPage('downloads')}
          />
          <NavRow
            label="Acknowledgements"
            note="Quran text, timings, audio, typeface"
            onClick={() => setPage('acknowledgements')}
          />
          <NavRow
            label="Reading help"
            note="Word meanings, bookmarks, sharing and keyboard shortcuts"
            onClick={() => setPage('help')}
          />
        </section>

        {s.developerMode ? (
          <section className="settings-section settings-section-developer">
            <h2>Developer</h2>
            <p className="settings-caption">Tools for testing work in progress.</p>

            <div className="settings-dev-row">
              <RuntimeCacheStatus active={isTop && page === 'main'} />
            </div>

            <div className="settings-dev-row">
              <PaperSwitch
                id="setting-feature-guides"
                label="Contextual feature guides"
                checked={s.educationGuidesEnabled}
                checkParams={checkParams}
                paintToken={checkPaintToken}
                onChange={(enabled) => {
                  if (enabled) rearmEducation()
                  setEducationRearmed(enabled)
                  appStore.updateSettings({ educationGuidesEnabled: enabled })
                }}
              />
              <p className="settings-caption">
                Off by default. Guides appear only after their matching gesture.
              </p>
            </div>

            <div className="settings-dev-row">
              <button
                type="button"
                className="settings-dev-link"
                onClick={() => {
                  rearmEducation()
                  appStore.updateSettings({ educationGuidesEnabled: true })
                  setEducationRearmed(true)
                }}
              >
                Replay feature guides
              </button>
              <p className="settings-caption">
                {educationRearmed
                  ? 'Ready — open a chapter or add a new bookmark.'
                  : 'Rearms each lesson for its next matching moment.'}
              </p>
            </div>

            <div className="settings-dev-row">
              <button
                type="button"
                className="settings-dev-link"
                onClick={() => {
                  location.hash = '#lab'
                }}
              >
                Ornaments Lab
              </button>
              <p className="settings-caption">
                Explore, design, and save seeds for the procedural star-and-cross
                ornament generator.
              </p>
            </div>

            <div className="settings-dev-row">
              <button type="button" className="settings-dev-link" onClick={() => setPage('kit')}>
                Component kit
              </button>
              <p className="settings-caption">
                Tune the nuqta, ink check and brush circle live, one component per page.
              </p>
            </div>

            <div className="settings-dev-block">
              <PaperSwitch
                id="setting-gapless5"
                label="Gapless verse joins"
                checked={s.gapless5Playback}
                checkParams={checkParams}
                paintToken={checkPaintToken}
                onChange={(checked) =>
                  appStore.updateSettings({ gapless5Playback: checked })
                }
              />
              <p className="settings-caption">
                Default on: Gapless-5 at 1× for seamless verse joins. Non-1× speeds always
                use the media path so pitch stays natural. Turn off to compare dual-element
                joins at 1×.
              </p>
            </div>

            <div className="settings-dev-block">
              <p className="settings-body-label">Mushaf letter widening</p>
              <p className="settings-caption">
                How far a loose line&rsquo;s letters may be widened before space is
                added between its words.
              </p>
              <PaperChoiceList
                aria-label="Mushaf letter widening"
                value={String(s.mushafGlyphWiden)}
                options={MUSHAF_GLYPH_WIDENS.map((percent) => ({
                  value: String(percent),
                  label: percent === 0 ? 'Off' : `Up to ${percent}%`,
                }))}
                onChange={(value) =>
                  appStore.updateSettings({ mushafGlyphWiden: Number(value) as MushafGlyphWiden })
                }
              />
            </div>

          </section>
        ) : null}

        <footer className="settings-colophon">
          <button
            type="button"
            className="settings-colophon-mark"
            aria-label="Beautiful Quran"
            onClick={onLogoClick}
          >
            <img src={assetUrl('icon-192.png')} alt="" width={48} height={48} />
          </button>
          <p className="settings-colophon-name">
            <span>Beautiful Quran</span>
            <AlphaTag />
          </p>
          <p className="settings-colophon-version">
            Version {APP_VERSION}
            {s.developerMode ? ' · developer mode' : ''}
          </p>
        </footer>
          </>
        )}
      </div>
      </NuqtaParamsContext.Provider>
    </div>
  )
}

/** Opens a sub-page on this same sheet — label, quiet summary, chevron. */
function NavRow({ label, note, onClick }: { label: string; note: string; onClick: () => void }) {
  return (
    <button type="button" className="settings-nav" onClick={onClick}>
      <span className="settings-nav-copy">
        <span className="settings-nav-label">{label}</span>
        <span className="settings-nav-note">{note}</span>
      </span>
      <DisclosureChevron expanded={false} />
    </button>
  )
}

const TIMING_PROVIDER_IDS = Object.keys(QF_TIMING_RECITERS).map(Number)

/** Android `MushafRuntimeCacheStatus`: the QF cache and reviewed timings. */
function RuntimeCacheStatus({ active }: { active: boolean }) {
  const [, setClock] = useState(0)
  useEffect(() => {
    if (!active) return
    const tick = () => setClock((value) => value + 1)
    const unsubscribes = [runtimeMushafCache?.subscribeDiagnostics(tick), runtimeTimingsCache.subscribe(tick)]
    const interval = window.setInterval(tick, 60_000)
    return () => {
      unsubscribes.forEach((unsubscribe) => unsubscribe?.())
      window.clearInterval(interval)
    }
  }, [active])
  const status: RuntimeCacheStatus | null = runtimeMushafCache?.status() ?? null
  const checked = TIMING_PROVIDER_IDS.filter((id) => runtimeTimingsCache.resource(id) != null).length
  const refreshing = status?.phase === 'refreshing'
  return (
    <>
      <p className="settings-body-label">Quran Foundation word &amp; QCF cache</p>
      {runtimeCacheLines(status, Date.now()).map((line) => (
        <p key={line} className="settings-caption">
          {line}
        </p>
      ))}
      {status ? (
        <>
          <p className="settings-caption">
            Recitation timings · {checked} of {TIMING_PROVIDER_IDS.length} providers checked
          </p>
          <button
            type="button"
            className="settings-dev-link"
            disabled={refreshing}
            onClick={() => {
              void runtimeMushafCache?.refresh()
              void runtimeTimingsCache.refreshLoaded()
            }}
          >
            {refreshing ? 'Updating Quran cache…' : 'Force Quran cache update'}
          </button>
          <p className="settings-caption">
            Refreshes authenticated Content Sync and reviewed recitation timings.
          </p>
        </>
      ) : null}
    </>
  )
}

/** Android's status lines, one fact each. */
function runtimeCacheLines(status: RuntimeCacheStatus | null, now: number): string[] {
  if (!status) return ['Unavailable · QF-provided fields withheld']
  const state = {
    empty: 'Empty · downloading when online',
    fresh: `Fresh · refresh ${cacheCountdown(status.refreshAtMs, now)}`,
    refresh_due: 'Refresh due · retrying in the background',
    expired: 'Expired · QF-provided fields withheld',
    refreshing: 'Refreshing in the background',
    error: 'Refresh failed · retrying when internet returns',
  }[status.phase]
  const lastRefresh = status.lastRefreshApiCalls == null ? 'not recorded' : `${status.lastRefreshApiCalls} calls`
  const lines = [state, `API usage · last refresh ${lastRefresh} · this launch ${status.apiCalls} calls`]
  if (status.updatedAtMs != null) {
    lines.push(
      `Updated ${cacheAge(status.updatedAtMs, now)} · seven-day limit ${cacheCountdown(status.expiresAtMs, now)}`,
    )
  }
  const error = status.lastError ? lastAttempt(status.lastError) : ''
  if (error) lines.push(`Last error · ${error}`)
  return lines
}

/** Reader-facing reason for the last failed refresh; local faults stay unspoken. */
function lastAttempt(failure: RuntimeCacheFailure): string {
  switch (failure) {
    case 'offline': return 'no connection'
    case 'server': return "the server didn't respond"
    case 'revoked': return 'access to this content was withdrawn'
    case 'other': return ''
  }
}

function cacheAge(atMs: number, nowMs: number): string {
  const minutes = Math.floor(Math.max(0, nowMs - atMs) / 60_000)
  const days = Math.floor(minutes / (24 * 60))
  const hours = Math.floor((minutes % (24 * 60)) / 60)
  if (days > 0) return `${days}d ${hours}h ago`
  if (hours > 0) return `${hours}h ago`
  return `${minutes}m ago`
}

function cacheCountdown(atMs: number | null, nowMs: number): string {
  if (atMs == null) return 'when online'
  if (atMs <= nowMs) return 'now'
  const minutes = Math.ceil((atMs - nowMs) / 60_000)
  const days = Math.floor(minutes / (24 * 60))
  const hours = Math.floor((minutes % (24 * 60)) / 60)
  if (days > 0) return `in ${days}d ${hours}h`
  if (hours > 0) return `in ${hours}h`
  return `in ${minutes}m`
}
