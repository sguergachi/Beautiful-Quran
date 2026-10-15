import { useLayoutEffect, useRef, useState } from 'react'
import type { RepeatMode, RepeatRange } from '../../playback/playlistNext'
import { BackChevron } from '../kit/BackChevron'
import { PaperChoiceList } from '../kit/PaperChoiceList'
import {
  chooseRepeat,
  REPEAT_CHOICES,
  repeatDraft,
  repeatSelection,
  type RepeatChoice,
} from './repeatPolicy'

/** A loose paper sheet, like Settings, with native modal focus/inert handling. */
export function RepeatSheet({
  currentAyah,
  ayahCount,
  chapter,
  mode,
  range,
  retainedChoice,
  onApply,
  onDismiss,
}: {
  currentAyah: number
  ayahCount: number
  chapter: string
  mode: RepeatMode
  range: RepeatRange | null
  retainedChoice: RepeatChoice | null
  onApply: (
    mode: RepeatMode,
    range: RepeatRange | null,
    choice: RepeatChoice,
  ) => void
  onDismiss: () => void
}) {
  // Freeze the opening ayah: ongoing playback must not move an editing range.
  const [anchor] = useState(() => Math.max(1, Math.min(ayahCount, currentAyah)))
  const [draft, setDraft] = useState(() =>
    repeatDraft(mode, range, anchor, ayahCount, retainedChoice),
  )
  const dialog = useRef<HTMLDialogElement>(null)
  useLayoutEffect(() => {
    const sheet = dialog.current!
    const previous = document.activeElement
    sheet.showModal()
    return () => {
      sheet.close()
      if (previous instanceof HTMLElement && previous.isConnected)
        previous.focus({ preventScroll: true })
    }
  }, [])
  const apply = () => {
    const selected = repeatSelection(draft, anchor, ayahCount)
    onApply(selected.mode, selected.range, draft.choice)
  }
  const number = (
    label: string,
    value: number,
    max: number,
    change: (n: number) => void,
  ) => (
    <label className="repeat-number">
      <span>{label}</span>
      <input
        type="number"
        min={1}
        max={max}
        step={1}
        value={value}
        onChange={(event) => {
          const n = event.currentTarget.valueAsNumber
          if (Number.isFinite(n))
            change(Math.max(1, Math.min(max, Math.trunc(n))))
        }}
      />
    </label>
  )
  return (
    <dialog
      ref={dialog}
      className="repeat-sheet"
      aria-labelledby="repeat-heading"
      aria-describedby="repeat-chapter"
      onCancel={(event) => {
        event.preventDefault()
        onDismiss()
      }}
      onKeyDown={(event) => event.stopPropagation()}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return
        const box = event.currentTarget.getBoundingClientRect()
        if (
          event.clientX < box.left ||
          event.clientX > box.right ||
          event.clientY < box.top ||
          event.clientY > box.bottom
        )
          onDismiss()
      }}
    >
      <div className="settings repeat-sheet-content">
        <button type="button" className="back" onClick={onDismiss}>
          <BackChevron />
          Cancel
        </button>
        <h1 id="repeat-heading">Repeat</h1>
        <p className="repeat-chapter" id="repeat-chapter">
          {chapter} · Ayah {anchor}
        </p>
        <PaperChoiceList
          aria-label="Repeat mode"
          value={draft.choice}
          options={[...REPEAT_CHOICES]}
          onChange={(choice) =>
            setDraft((previous) =>
              chooseRepeat(previous, choice, anchor, ayahCount),
            )
          }
        />
        {draft.choice === 'range' ? (
          <div className="repeat-bounds" role="group" aria-label="Ayah range">
            {number('From ayah', draft.from, ayahCount, (from) =>
              setDraft((d) => ({ ...d, from, to: Math.max(from, d.to) })),
            )}
            <span aria-hidden="true">to</span>
            {number('To ayah', draft.to, ayahCount, (to) =>
              setDraft((d) => ({ ...d, to, from: Math.min(to, d.from) })),
            )}
          </div>
        ) : draft.choice === 'from_here' ? (
          <div className="repeat-from-here">
            {number(
              'Number of ayahs',
              draft.count,
              ayahCount - anchor + 1,
              (count) => setDraft((d) => ({ ...d, count })),
            )}
            <p>
              Ayahs {anchor} to {anchor + draft.count - 1}, including this ayah
            </p>
          </div>
        ) : null}
        <button type="button" className="repeat-done" onClick={apply}>
          Done
        </button>
      </div>
    </dialog>
  )
}
