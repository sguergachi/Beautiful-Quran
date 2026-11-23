import { useEffect, useRef, useState, type Dispatch, type ReactNode, type SetStateAction } from 'react'
import { appStore } from '../../store/appStore'
import type { BrushCircleStyle } from '../../data/settings'
import {
  BRUSH_CHECK_KNOB_SLIDERS,
  formatBrushCheckCopy,
  formatBrushCheckVerifyLines,
  parseBrushCheckFromText,
  SHIPPED_CHECK_PARAMS,
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
  type BrushCircleParams,
  type BrushKnobKey,
} from '../kit/brushMark'
import { BackChevron } from '../kit/BackChevron'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { PaperSegmented } from '../kit/PaperSegmented'
import { PaperSlider } from '../kit/PaperSlider'
import { PaperSwitch } from '../kit/PaperSwitch'
import type { NuqtaParams } from '../kit/nuqta'
import { NuqtaLab } from './NuqtaLab'

/** The components the kit tunes, in page order (Android `KitComponent`). */
const KIT_COMPONENTS = [
  { id: 'nuqta', title: 'Nuqta radio dot', blurb: 'Every single-choice row.' },
  { id: 'check', title: 'Ink check mark', blurb: 'Every on/off row.' },
  { id: 'brush', title: 'Brush circle', blurb: 'Side-by-side and stacked choice words.' },
] as const

/** Session-only ink lab state, owned by Settings so Customize follows it live. */
export interface InkLabState {
  brushStyle: BrushCircleStyle
  brushParams: BrushCircleParams
  setBrushParams: Dispatch<SetStateAction<BrushCircleParams>>
  paintToken: number
  bumpPaint: () => void
  sliderEpoch: number
  bumpSliders: () => void
  checkParams: BrushCheckParams
  setCheckParams: Dispatch<SetStateAction<BrushCheckParams>>
  checkPaintToken: number
  bumpCheckPaint: () => void
  checkSliderEpoch: number
  bumpCheckSliders: () => void
  nuqtaParams: NuqtaParams
  setNuqtaParams: Dispatch<SetStateAction<NuqtaParams>>
  /** Marks a pasted preset change as already seeded (no reseed effect). */
  noteStyle: (style: BrushCircleStyle) => void
}

/** Clipboard API often fails in preview iframes — fall back to execCommand. */
function copyTextRobust(text: string): boolean {
  try {
    const el = document.createElement('textarea')
    el.value = text
    el.setAttribute('readonly', '')
    Object.assign(el.style, { position: 'fixed', top: '0', left: '0', width: '1px', height: '1px', opacity: '0' })
    document.body.appendChild(el)
    el.select()
    el.setSelectionRange(0, text.length)
    const ok = document.execCommand('copy')
    document.body.removeChild(el)
    return ok
  } catch {
    return false
  }
}

async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // fall through
    }
  }
  return copyTextRobust(text)
}

async function readClipboard(): Promise<string> {
  try {
    return (await navigator.clipboard?.readText?.()) ?? ''
  } catch {
    return ''
  }
}

/**
 * Settings → Developer → Component kit: one of the app's own marks per page,
 * its preview held above its dials. The stage shows the current default (what
 * Reset restores) beside the version being customized. Edits are session-only
 * and every real control on the Settings and Customize sheets follows them.
 */
export function ComponentKitScreen({ lab, onBack }: { lab: InkLabState; onBack: () => void }) {
  const [index, setIndex] = useState(0)
  const [note, setNote] = useState<string | null>(null)
  const component = KIT_COMPONENTS[index]!

  useEffect(() => {
    if (!note) return
    const t = window.setTimeout(() => setNote(null), 2200)
    return () => window.clearTimeout(t)
  }, [note])

  return (
    <div className="component-kit">
      <div className="component-kit-head">
        <button type="button" className="back settings-back" aria-label="Back" onClick={onBack}>
          <BackChevron />
        </button>
        <span className="component-kit-dots" role="tablist" aria-label="Components">
          {KIT_COMPONENTS.map((item, i) => (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-label={item.title}
              aria-current={i === index}
              aria-selected={i === index}
              onClick={() => setIndex(i)}
            >
              <i />
            </button>
          ))}
        </span>
      </div>
      <h1>{component.title}</h1>
      <p className="component-kit-blurb">
        {index + 1} of {KIT_COMPONENTS.length} · {component.blurb}
      </p>
      {component.id === 'nuqta' ? (
        <NuqtaLab params={lab.nuqtaParams} onChange={lab.setNuqtaParams} showTitle={false} />
      ) : component.id === 'check' ? (
        <CheckLab lab={lab} onNote={setNote} />
      ) : (
        <BrushLab lab={lab} onNote={setNote} />
      )}
      {note ? (
        <p className="settings-caption" role="status">
          {note}
        </p>
      ) : null}
    </div>
  )
}

/** One labelled copy of the mark on the stage (default or customized). */
function KitVersion({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="component-kit-version">
      <p className="component-kit-version-label">{label}</p>
      {children}
    </div>
  )
}

function CheckLab({ lab, onNote }: { lab: InkLabState; onNote: (note: string) => void }) {
  const [checked, setChecked] = useState(true)
  const [defaultChecked, setDefaultChecked] = useState(true)
  const [pasteText, setPasteText] = useState('')
  const pasteRef = useRef<HTMLTextAreaElement>(null)
  const { checkParams, setCheckParams, bumpCheckPaint, bumpCheckSliders } = lab

  const setKnob = (key: BrushCheckKnobKey, value: number) => {
    setCheckParams((prev) => ({ ...prev, [key]: value }))
    bumpCheckPaint()
  }

  const apply = (raw: string) => {
    const parsed = parseBrushCheckFromText(raw, checkParams)
    if (!parsed) {
      onNote('No check knobs found in paste')
      return
    }
    setCheckParams(parsed)
    bumpCheckPaint()
    bumpCheckSliders()
    setPasteText(raw.trim())
    onNote('Applied check params')
  }

  const copy = async () => {
    const text = formatBrushCheckCopy(checkParams)
    if (await copyText(text)) {
      onNote('Copied check params')
      return
    }
    setPasteText(text)
    window.requestAnimationFrame(() => pasteRef.current?.select())
    onNote('Selected in field — press ⌘/Ctrl+C')
  }

  const paste = async () => {
    const text = await readClipboard()
    if (text.trim()) apply(text)
    else {
      onNote('Paste into the check field below, then Apply')
      pasteRef.current?.focus()
    }
  }

  return (
    <>
      <div className="component-kit-stage component-kit-compare">
        <KitVersion label="Current default">
          <PaperSwitch
            id="kit-check-default"
            label="Preview"
            checked={defaultChecked}
            checkParams={SHIPPED_CHECK_PARAMS}
            paintToken={lab.checkPaintToken}
            onChange={(next) => {
              setDefaultChecked(next)
              bumpCheckPaint()
            }}
          />
        </KitVersion>
        <KitVersion label="Customized">
          <PaperSwitch
            id="kit-check-preview"
            label="Preview"
            checked={checked}
            checkParams={checkParams}
            paintToken={lab.checkPaintToken}
            onChange={(next) => {
              setChecked(next)
              bumpCheckPaint()
            }}
          />
        </KitVersion>
      </div>
      <div className="settings-dev-block">
        <p className="settings-body-label">Stroke</p>
        <div className="brush-lab-sliders">
          {BRUSH_CHECK_KNOB_SLIDERS.map((spec) => (
            <PaperSlider
              key={`${spec.key}-${lab.checkSliderEpoch}`}
              id={`check-${spec.key}`}
              label={spec.label}
              value={checkParams[spec.key]}
              min={spec.min}
              max={spec.max}
              step={spec.step}
              format={spec.format}
              onChange={(v) => setKnob(spec.key, v)}
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
              bumpCheckPaint()
              bumpCheckSliders()
            }}
          >
            Reset
          </button>
          <button
            type="button"
            className="settings-dev-link"
            onClick={() => {
              setChecked(true)
              bumpCheckPaint()
            }}
          >
            Replay
          </button>
          <button type="button" className="settings-dev-link" onClick={() => void copy()}>
            Copy
          </button>
          <button type="button" className="settings-dev-link" onClick={() => void paste()}>
            Paste
          </button>
        </div>
        <textarea
          ref={pasteRef}
          className="brush-lab-paste"
          rows={4}
          spellCheck={false}
          placeholder="Paste check params here (TS or Kotlin), then Apply…"
          value={pasteText}
          onChange={(e) => setPasteText(e.target.value)}
          onKeyDown={(e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
              e.preventDefault()
              apply(pasteText)
            }
          }}
        />
        <div className="brush-lab-actions">
          <button
            type="button"
            className="settings-dev-link"
            disabled={!pasteText.trim()}
            onClick={() => apply(pasteText)}
          >
            Apply paste
          </button>
        </div>
      </div>
    </>
  )
}

function BrushLab({ lab, onNote }: { lab: InkLabState; onNote: (note: string) => void }) {
  const [choice, setChoice] = useState('scroll')
  const [defaultChoice, setDefaultChoice] = useState('scroll')
  const [pasteText, setPasteText] = useState('')
  const pasteRef = useRef<HTMLTextAreaElement>(null)
  const { brushParams, setBrushParams, bumpPaint, bumpSliders, brushStyle } = lab

  const setKnob = (key: BrushKnobKey, value: number) => {
    setBrushParams((prev) => ({ ...prev, label: 'Custom', [key]: value }))
    bumpPaint()
  }

  const loadPreset = (id: BrushCircleStyle) => {
    appStore.updateSettings({ brushCircleStyle: id })
    setBrushParams(brushCircleParams(id))
    bumpPaint()
    bumpSliders()
  }

  const apply = (raw: string) => {
    const parsed = parseBrushParamsFromText(raw, brushParams)
    if (!parsed) {
      onNote('No brush knobs found in paste')
      return
    }
    if (brushStyle !== 'baseline') {
      lab.noteStyle('baseline')
      appStore.updateSettings({ brushCircleStyle: 'baseline' })
    }
    setBrushParams({ ...parsed, label: 'Custom' })
    bumpPaint()
    bumpSliders()
    setPasteText(raw.trim())
    onNote(`Applied: ${formatBrushKnobsExact(parsed)}`)
  }

  const copy = async () => {
    const text = formatBrushParamsCopy(brushParams)
    if (await copyText(text)) {
      onNote('Copied TS + Kotlin params')
      return
    }
    setPasteText(text)
    window.requestAnimationFrame(() => pasteRef.current?.select())
    onNote('Selected in field — press ⌘/Ctrl+C')
  }

  const paste = async () => {
    const text = await readClipboard()
    if (text.trim()) apply(text)
    else {
      onNote('Paste into the field below, then Apply')
      pasteRef.current?.focus()
    }
  }

  return (
    <>
      <div className="component-kit-stage component-kit-compare">
        <KitVersion label="Current default">
          <PaperSegmented
            aria-label="Brush circle default preview"
            value={defaultChoice}
            brushParams={brushCircleParams(brushStyle)}
            paintToken={lab.paintToken}
            options={[
              { value: 'scroll', label: 'Scroll' },
              { value: 'mushaf', label: 'Mushaf' },
              { value: 'both', label: 'Both' },
            ]}
            onChange={setDefaultChoice}
          />
        </KitVersion>
        <KitVersion label="Customized">
          <PaperSegmented
            aria-label="Brush circle preview"
            value={choice}
            brushParams={brushParams}
            paintToken={lab.paintToken}
            options={[
              { value: 'scroll', label: 'Scroll' },
              { value: 'mushaf', label: 'Mushaf' },
              { value: 'both', label: 'Both' },
            ]}
            onChange={setChoice}
          />
        </KitVersion>
      </div>
      <div className="settings-dev-block">
        <p className="settings-body-label">Preset</p>
        <PaperChoiceList
          aria-label="Selector brush circle preset"
          value={brushStyle}
          options={BRUSH_CIRCLE_STYLE_IDS.map((id) => ({ value: id, label: brushCircleParams(id).label }))}
          onChange={(v) => loadPreset(v as BrushCircleStyle)}
        />
      </div>
      <div className="settings-dev-block">
        <p className="settings-body-label">Stroke</p>
        <div className="brush-lab-sliders">
          {BRUSH_KNOB_SLIDERS.map((spec) => (
            <PaperSlider
              key={`${spec.key}-${lab.sliderEpoch}`}
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
              setBrushParams(brushCircleParams(brushStyle))
              bumpPaint()
              bumpSliders()
            }}
          >
            Reset
          </button>
          <button type="button" className="settings-dev-link" onClick={bumpPaint}>
            Replay
          </button>
          <button type="button" className="settings-dev-link" onClick={() => void copy()}>
            Copy
          </button>
          <button type="button" className="settings-dev-link" onClick={() => void paste()}>
            Paste
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
              apply(pasteText)
            }
          }}
        />
        <div className="brush-lab-actions">
          <button
            type="button"
            className="settings-dev-link"
            disabled={!pasteText.trim()}
            onClick={() => apply(pasteText)}
          >
            Apply paste
          </button>
        </div>
      </div>
    </>
  )
}
