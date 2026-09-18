import { useEffect, useRef, useState } from 'react'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import { PaperSlider } from '../kit/PaperSlider'
import {
  formatNuqtaCopy,
  formatNuqtaKnob,
  NUQTA_KNOB_GROUPS,
  parseNuqtaFromText,
  SHIPPED_NUQTA,
  type NuqtaParams,
} from '../kit/nuqta'

type Props = {
  params: NuqtaParams
  onChange: (next: NuqtaParams) => void
}

/**
 * Developer lab for the nuqta radio dot — Android `NuqtaLab`. Edits are
 * session-only and reach every choice list on the Settings and Customize
 * sheets live; the two rows here give a place to flick back and forth.
 */
export function NuqtaLab({ params, onChange }: Props) {
  const [demo, setDemo] = useState<'first' | 'second'>('first')
  /** Bumps on paste/reset so Base UI sliders remount with exact values. */
  const [epoch, setEpoch] = useState(0)
  const [note, setNote] = useState<string | null>(null)
  const [pasteText, setPasteText] = useState('')
  const pasteRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!note) return
    const t = window.setTimeout(() => setNote(null), 2000)
    return () => window.clearTimeout(t)
  }, [note])

  const replace = (next: NuqtaParams, message?: string) => {
    onChange(next)
    setEpoch((n) => n + 1)
    if (message) setNote(message)
  }

  const applyPaste = (raw: string) => {
    const parsed = parseNuqtaFromText(raw, params)
    if (!parsed) {
      setNote('No nuqta knobs found in paste')
      return
    }
    replace(parsed, 'Applied nuqta params')
    setPasteText('')
  }

  const copy = async () => {
    const text = formatNuqtaCopy(params)
    try {
      await navigator.clipboard.writeText(text)
      setNote('Copied nuqta params')
    } catch {
      // Preview iframes often refuse the clipboard: leave it selected instead.
      setPasteText(text)
      window.requestAnimationFrame(() => pasteRef.current?.select())
      setNote('Selected in field — press ⌘/Ctrl+C')
    }
  }

  const paste = async () => {
    try {
      const text = await navigator.clipboard.readText()
      if (text.trim()) {
        applyPaste(text)
        return
      }
    } catch {
      // fall through
    }
    setNote('Paste into the nuqta field below, then Apply')
    pasteRef.current?.focus()
  }

  return (
    <div className="settings-dev-block">
      <p className="settings-body-label">Nuqta radio dot</p>
      <p className="settings-caption">
        Every single-choice list on these sheets follows these knobs live.
      </p>
      <PaperChoiceList
        aria-label="Nuqta preview"
        value={demo}
        options={[
          { value: 'first', label: 'First choice' },
          { value: 'second', label: 'Second choice' },
        ]}
        onChange={setDemo}
      />
      {NUQTA_KNOB_GROUPS.map((group) => (
        <div key={group.title} className="brush-lab-sliders">
          <p className="settings-caption">{group.title}</p>
          {group.knobs.map((knob) => (
            <PaperSlider
              key={`${knob.key}-${epoch}`}
              id={`nuqta-${knob.key}`}
              label={knob.label}
              value={params[knob.key]}
              min={knob.min}
              max={knob.max}
              step={knob.digits === 0 ? 1 : 10 ** -knob.digits}
              format={(v) => formatNuqtaKnob(knob.key, v)}
              onChange={(v) => onChange({ ...params, [knob.key]: v })}
            />
          ))}
        </div>
      ))}
      <div className="brush-lab-actions">
        <button
          type="button"
          className="settings-dev-link"
          onClick={() => replace({ ...SHIPPED_NUQTA })}
        >
          Reset nuqta
        </button>
        <button
          type="button"
          className="settings-dev-link"
          onClick={() => setDemo((d) => (d === 'first' ? 'second' : 'first'))}
        >
          Replay
        </button>
        <button type="button" className="settings-dev-link" onClick={() => void copy()}>
          Copy nuqta
        </button>
        <button type="button" className="settings-dev-link" onClick={() => void paste()}>
          Paste nuqta
        </button>
      </div>
      <textarea
        ref={pasteRef}
        className="brush-lab-paste"
        rows={4}
        spellCheck={false}
        placeholder="Paste nuqta params here, then Apply…"
        value={pasteText}
        onChange={(e) => setPasteText(e.target.value)}
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
      {note ? (
        <p className="settings-caption" role="status">
          {note}
        </p>
      ) : null}
    </div>
  )
}
