import { useEffect, useRef, useState } from 'react'
import { assetUrl } from '../../assetUrl'
import { appStore, useAppSelector } from '../../store/appStore'
import {
  type HomeBookmarkStyle,
  type BrushCircleStyle,
} from '../../data/settings'
import { customizeSummary } from '../../data/customizePolicy'
import { CustomizeScreen } from './CustomizeScreen'
import { settingsLayerFor, type StackLayer } from '../paper/stack'
import {
  BRUSH_CHECK_KNOB_SLIDERS,
  formatBrushCheckCopy,
  formatBrushCheckVerifyLines,
  parseBrushCheckFromText,
  SHIPPED_CHECK_PARAMS,
  SHIPPED_CHECK_REVISION,
  type BrushCheckKnobKey,
  type BrushCheckParams,
} from '../kit/brushCheck'
import {
  BRUSH_CIRCLE_STYLE_IDS,
  BRUSH_KNOB_SLIDERS,
  brushCircleParams,
  formatBrushKnobsExact,
  formatBrushKnobsVerifyLines,
  formatBrushParamsCopy,
  parseBrushParamsFromText,
  SHIPPED_BRUSH_REVISION,
  type BrushCircleParams,
  type BrushKnobKey,
} from '../kit/brushMark'
import { FontSizeControl } from '../kit/FontSizeControl'
import { DisclosureChevron } from '../kit/DisclosureChevron'
import { InkCheckMark } from '../kit/InkCheckMark'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { PaperSlider } from '../kit/PaperSlider'
import { PaperSwitch } from '../kit/PaperSwitch'
import { rearmEducation } from '../../data/education'

const ATTRIBUTIONS = `Quran text (Uthmani script) and Saheeh International translation via the quran-json project, from Tanzil and Al Quran Cloud.

Word-by-word translation and transliteration from the Quran.com dataset.

Root, lemma, and morphological annotation from the Quranic Arabic Corpus (corpus.quran.com), © Kais Dukes.

Word-level audio timing data © the quran-align project contributors, CC-BY 4.0.

Recitation audio streamed from everyayah.com. All rights to the recitations belong to the respective reciters.

Arabic typeface: KFGQPC HAFS Uthmanic Script © King Fahd Glorious Quran Printing Complex, Madinah.

This app is free, ad-free, and collects no data.`

const APP_VERSION = '0.1.0'

const HOME_BOOKMARK_OPTIONS: { value: HomeBookmarkStyle; label: string }[] = [
  { value: 'top_bound', label: 'Top-bound ribbon' },
  { value: 'saved_passages', label: 'Saved passages line' },
]

/** Triple-tap window for developer unlock — matches Android SettingsScreen. */
const DEVELOPER_TAP_RESET_MS = 1500

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
  const layer = settingsLayerFor(hasReader)
  const isTop = stackLayer === layer
  const depth = Math.max(0, stackLayer - layer)
  const [customizeOpen, setCustomizeOpen] = useState(false)
  const showReadingToggles = s.readingMode === 'arabic_english'

  // Session-only live knobs for the brush labs (not persisted).
  const [brushParams, setBrushParams] = useState<BrushCircleParams>(() =>
    brushCircleParams(s.brushCircleStyle),
  )
  const [checkParams, setCheckParams] = useState<BrushCheckParams>(() => ({
    ...SHIPPED_CHECK_PARAMS,
  }))
  const [paintToken, setPaintToken] = useState(0)
  const [checkPaintToken, setCheckPaintToken] = useState(0)
  const [checkPreviewOn, setCheckPreviewOn] = useState(true)
  /** Bumps when paste/reset/preset loads so Base UI sliders remount with exact values. */
  const [sliderEpoch, setSliderEpoch] = useState(0)
  const [checkSliderEpoch, setCheckSliderEpoch] = useState(0)
  const [copyNote, setCopyNote] = useState<string | null>(null)
  const [presetsOpen, setPresetsOpen] = useState(false)
  const [pasteText, setPasteText] = useState('')
  const [checkPasteText, setCheckPasteText] = useState('')
  const [developerTapCount, setDeveloperTapCount] = useState(0)
  const [educationRearmed, setEducationRearmed] = useState(false)
  const pasteRef = useRef<HTMLTextAreaElement>(null)
  const checkPasteRef = useRef<HTMLTextAreaElement>(null)
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

  // Clear copy flash after a beat.
  useEffect(() => {
    if (!copyNote) return
    const t = window.setTimeout(() => setCopyNote(null), 2200)
    return () => window.clearTimeout(t)
  }, [copyNote])

  const setKnob = (key: BrushKnobKey, value: number) => {
    setBrushParams((prev) => ({ ...prev, label: 'Custom', [key]: value }))
    setPaintToken((n) => n + 1)
  }

  const setCheckKnob = (key: BrushCheckKnobKey, value: number) => {
    setCheckParams((prev) => ({ ...prev, [key]: value }))
    setCheckPaintToken((n) => n + 1)
  }

  const loadPreset = (id: BrushCircleStyle) => {
    appStore.updateSettings({ brushCircleStyle: id })
    setBrushParams(brushCircleParams(id))
    setPaintToken((n) => n + 1)
    setSliderEpoch((n) => n + 1)
  }

  const flashNote = (msg: string) => setCopyNote(msg)

  const onLogoClick = () => {
    const next = developerTapCount + 1
    if (next >= 3) {
      appStore.updateSettings({ developerMode: !s.developerMode })
      setDeveloperTapCount(0)
    } else {
      setDeveloperTapCount(next)
    }
  }

  /** Clipboard API often fails in preview iframes — fall back to execCommand + field. */
  const copyTextRobust = (text: string): boolean => {
    try {
      const el = document.createElement('textarea')
      el.value = text
      el.setAttribute('readonly', '')
      el.style.position = 'fixed'
      el.style.top = '0'
      el.style.left = '0'
      el.style.width = '1px'
      el.style.height = '1px'
      el.style.padding = '0'
      el.style.border = 'none'
      el.style.outline = 'none'
      el.style.boxShadow = 'none'
      el.style.background = 'transparent'
      el.style.opacity = '0'
      document.body.appendChild(el)
      el.focus()
      el.select()
      el.setSelectionRange(0, text.length)
      const ok = document.execCommand('copy')
      document.body.removeChild(el)
      if (ok) return true
    } catch {
      // continue
    }
    return false
  }

  const copyParams = async () => {
    const text = formatBrushParamsCopy(brushParams)
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text)
        flashNote('Copied TS + Kotlin params')
        return
      } catch {
        // fall through
      }
    }
    if (copyTextRobust(text)) {
      flashNote('Copied TS + Kotlin params')
      return
    }
    setPasteText(text)
    window.requestAnimationFrame(() => {
      pasteRef.current?.focus()
      pasteRef.current?.select()
    })
    flashNote('Selected in field — press ⌘/Ctrl+C')
  }

  const applyPaste = (raw: string) => {
    const parsed = parseBrushParamsFromText(raw, brushParams)
    if (!parsed) {
      flashNote('No brush knobs found in paste')
      return
    }
    if (s.brushCircleStyle !== 'baseline') {
      lastStyleRef.current = 'baseline'
      appStore.updateSettings({ brushCircleStyle: 'baseline' })
    }
    setBrushParams({ ...parsed, label: 'Custom' })
    setPaintToken((n) => n + 1)
    setSliderEpoch((n) => n + 1)
    setPasteText(raw.trim())
    flashNote(`Applied: ${formatBrushKnobsExact(parsed)}`)
  }

  const copyCheckParams = async () => {
    const text = formatBrushCheckCopy(checkParams)
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text)
        flashNote('Copied check params')
        return
      } catch {
        // fall through
      }
    }
    if (copyTextRobust(text)) {
      flashNote('Copied check params')
      return
    }
    setCheckPasteText(text)
    window.requestAnimationFrame(() => {
      checkPasteRef.current?.focus()
      checkPasteRef.current?.select()
    })
    flashNote('Selected in field — press ⌘/Ctrl+C')
  }

  const applyCheckPaste = (raw: string) => {
    const parsed = parseBrushCheckFromText(raw, checkParams)
    if (!parsed) {
      flashNote('No check knobs found in paste')
      return
    }
    setCheckParams(parsed)
    setCheckPaintToken((n) => n + 1)
    setCheckSliderEpoch((n) => n + 1)
    setCheckPasteText(raw.trim())
    flashNote('Applied check params')
  }

  const pasteCheckFromClipboard = async () => {
    if (navigator.clipboard?.readText) {
      try {
        const text = await navigator.clipboard.readText()
        if (text.trim()) {
          applyCheckPaste(text)
          return
        }
      } catch {
        // fall through
      }
    }
    flashNote('Paste into the check field below, then Apply')
    checkPasteRef.current?.focus()
  }

  const pasteFromClipboard = async () => {
    if (navigator.clipboard?.readText) {
      try {
        const text = await navigator.clipboard.readText()
        if (text.trim()) {
          applyPaste(text)
          return
        }
      } catch {
        // fall through
      }
    }
    flashNote('Paste into the field below, then Apply')
    pasteRef.current?.focus()
  }

  return (
    <div
      className="sheet"
      data-name="settings"
      data-layer={layer}
      data-depth={depth}
      data-active={isTop}
    >
      <div className="settings">
        {customizeOpen ? (
          <CustomizeScreen
            settings={s}
            brushParams={brushParams}
            paintToken={paintToken}
            checkParams={checkParams}
            checkPaintToken={checkPaintToken}
            onBack={() => setCustomizeOpen(false)}
          />
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

        <section className="settings-section">
          <h2>Reciter</h2>
          <PaperChoiceList
            aria-label="Reciter"
            value={String(s.reciterId)}
            options={reciters.map((r) => ({
              value: String(r.id),
              label: r.name,
              description: r.hasTimings ? undefined : 'No word highlighting',
            }))}
            onChange={(v) => appStore.updateSettings({ reciterId: Number(v) })}
          />
        </section>

        <section className="settings-section">
          <h2>Reading</h2>
          <button
            type="button"
            className="settings-nav"
            onClick={() => setCustomizeOpen(true)}
          >
            <span className="settings-nav-copy">
              <span className="settings-nav-label">Customize</span>
              <span className="settings-nav-note">{customizeSummary(s)}</span>
            </span>
            <DisclosureChevron expanded={false} />
          </button>
        </section>

        <section className="settings-section">
          <h2>Text size</h2>
          <FontSizeControl
            scale={s.fontScale}
            onChange={(fontScale) => appStore.updateSettings({ fontScale })}
          />
        </section>

        {showReadingToggles ? (
          <section className="settings-section settings-section-toggles">
            <PaperSwitch
              id="setting-translit"
              label="Transliteration"
              checked={s.showTransliteration}
              checkParams={checkParams}
              paintToken={checkPaintToken}
              onChange={(checked) =>
                appStore.updateSettings({ showTransliteration: checked })
              }
            />
            <PaperSwitch
              id="setting-translation"
              label="Ayah translation"
              checked={s.showTranslation}
              checkParams={checkParams}
              paintToken={checkPaintToken}
              onChange={(checked) =>
                appStore.updateSettings({ showTranslation: checked })
              }
            />
          </section>
        ) : null}

        {s.developerMode ? (
          <section className="settings-section settings-section-developer">
            <h2>Developer</h2>
            <p className="settings-caption">Tools for testing work in progress.</p>

            <div className="settings-dev-block">
              <PaperSwitch
                id="setting-education-guides"
                label="Contextual feature guides"
                checked={s.educationGuidesEnabled}
                checkParams={checkParams}
                paintToken={checkPaintToken}
                onChange={(enabled) => {
                  if (enabled) {
                    rearmEducation()
                    setEducationRearmed(true)
                  } else {
                    setEducationRearmed(false)
                  }
                  appStore.updateSettings({ educationGuidesEnabled: enabled })
                }}
              />
              <p className="settings-caption">
                Off by default. Guides appear only after their matching gesture.
              </p>
            </div>

            <div className="settings-dev-block">
              <button
                type="button"
                className="settings-dev-link settings-dev-primary"
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

            <button
              type="button"
              className="settings-dev-link settings-dev-primary"
              onClick={() => {
                location.hash = '#lab'
              }}
            >
              Ornaments Lab
            </button>
            <p className="settings-caption">
              Explore, design, and save seeds for the procedural star-and-cross ornament
              generator.
            </p>

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
              <p className="settings-body-label">Home bookmark</p>
              <p className="settings-caption">
                Changes the Chapters shortcut; bookmark ribbons inside verses are unchanged.
              </p>
              <PaperChoiceList
                aria-label="Home bookmark"
                value={s.homeBookmarkStyle}
                options={HOME_BOOKMARK_OPTIONS}
                onChange={(homeBookmarkStyle) =>
                  appStore.updateSettings({ homeBookmarkStyle })
                }
              />
            </div>

            <div className="settings-dev-block">
              <p className="settings-body-label">Selector brush circle</p>
              <button
                type="button"
                className="settings-dev-link brush-lab-presets-toggle"
                aria-expanded={presetsOpen}
                onClick={() => setPresetsOpen((o) => !o)}
              >
                <span>Presets</span>
                <DisclosureChevron expanded={presetsOpen} />
                <span className="brush-lab-presets-current">
                  {brushCircleParams(s.brushCircleStyle).label}
                </span>
              </button>
              {presetsOpen ? (
                <PaperChoiceList
                  aria-label="Selector brush circle preset"
                  value={s.brushCircleStyle}
                  options={BRUSH_CIRCLE_STYLE_IDS.map((id) => ({
                    value: id,
                    label: brushCircleParams(id).label,
                  }))}
                  onChange={(v) => loadPreset(v as BrushCircleStyle)}
                />
              ) : null}
              <div className="brush-lab-sliders">
                {BRUSH_KNOB_SLIDERS.map((spec) => (
                  <PaperSlider
                    key={`${spec.key}-${sliderEpoch}`}
                    id={`brush-${spec.key}`}
                    label={spec.label}
                    value={brushParams[spec.key]}
                    min={spec.min}
                    max={spec.max}
                    step={spec.step}
                    format={spec.format}
                    onChange={(v) => setKnob(spec.key, v)}
                  />
                ))}
              </div>
              <pre className="brush-lab-verify" aria-live="polite">
                {formatBrushKnobsVerifyLines(brushParams)}
              </pre>
              <div className="brush-lab-actions">
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => {
                    setBrushParams(brushCircleParams(s.brushCircleStyle))
                    setPaintToken((n) => n + 1)
                    setSliderEpoch((n) => n + 1)
                  }}
                >
                  Reset to preset
                </button>
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => setPaintToken((n) => n + 1)}
                >
                  Replay paint
                </button>
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => void copyParams()}
                >
                  Copy values
                </button>
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => void pasteFromClipboard()}
                >
                  Paste values
                </button>
              </div>
              <textarea
                ref={pasteRef}
                className="brush-lab-paste"
                rows={4}
                spellCheck={false}
                placeholder="Paste saved brush params here (TS or Kotlin), then Apply…"
                value={pasteText}
                onChange={(e) => setPasteText(e.target.value)}
                onKeyDown={(e) => {
                  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                    e.preventDefault()
                    applyPaste(pasteText)
                  }
                }}
              />
              <div className="brush-lab-actions">
                <button
                  type="button"
                  className="settings-dev-link"
                  disabled={!pasteText.trim()}
                  onClick={() => applyPaste(pasteText)}
                >
                  Apply paste
                </button>
              </div>
            </div>

            <div className="settings-dev-block">
              <p className="settings-body-label">Ink check mark</p>
              <div className="check-lab-preview">
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => {
                    setCheckPreviewOn((v) => !v)
                    setCheckPaintToken((n) => n + 1)
                  }}
                >
                  Preview — see toggles above
                </button>
                <InkCheckMark
                  checked={checkPreviewOn}
                  params={checkParams}
                  paintToken={checkPaintToken}
                />
              </div>
              <div className="brush-lab-sliders">
                {BRUSH_CHECK_KNOB_SLIDERS.map((spec) => (
                  <PaperSlider
                    key={`${spec.key}-${checkSliderEpoch}`}
                    id={`check-${spec.key}`}
                    label={spec.label}
                    value={checkParams[spec.key]}
                    min={spec.min}
                    max={spec.max}
                    step={spec.step}
                    format={spec.format}
                    onChange={(v) => setCheckKnob(spec.key, v)}
                  />
                ))}
              </div>
              <pre className="brush-lab-verify" aria-live="polite">
                {formatBrushCheckVerifyLines(checkParams)}
              </pre>
              <div className="brush-lab-actions">
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => {
                    setCheckParams({ ...SHIPPED_CHECK_PARAMS })
                    setCheckPaintToken((n) => n + 1)
                    setCheckSliderEpoch((n) => n + 1)
                  }}
                >
                  Reset check
                </button>
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => {
                    setCheckPreviewOn(true)
                    setCheckPaintToken((n) => n + 1)
                  }}
                >
                  Replay paint
                </button>
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => void copyCheckParams()}
                >
                  Copy check
                </button>
                <button
                  type="button"
                  className="settings-dev-link"
                  onClick={() => void pasteCheckFromClipboard()}
                >
                  Paste check
                </button>
              </div>
              <textarea
                ref={checkPasteRef}
                className="brush-lab-paste"
                rows={4}
                spellCheck={false}
                placeholder="Paste check params here (TS or Kotlin), then Apply…"
                value={checkPasteText}
                onChange={(e) => setCheckPasteText(e.target.value)}
                onKeyDown={(e) => {
                  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                    e.preventDefault()
                    applyCheckPaste(checkPasteText)
                  }
                }}
              />
              <div className="brush-lab-actions">
                <button
                  type="button"
                  className="settings-dev-link"
                  disabled={!checkPasteText.trim()}
                  onClick={() => applyCheckPaste(checkPasteText)}
                >
                  Apply paste
                </button>
              </div>
              {copyNote ? (
                <p className="settings-caption" role="status">
                  {copyNote}
                </p>
              ) : null}
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
          <p className="settings-colophon-name">Beautiful Quran</p>
          <p className="settings-colophon-version">
            Version {APP_VERSION}
            {s.developerMode ? ' · developer mode' : ''}
          </p>
        </footer>

        <p className="settings-attributions">{ATTRIBUTIONS}</p>
          </>
        )}
      </div>
    </div>
  )
}

function BackChevron() {
  return (
    <svg
      className="settings-back-icon"
      viewBox="0 0 24 24"
      width="24"
      height="24"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M15.5 5.5 L9 12 l6.5 6.5"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
