import { useId, type ReactNode } from 'react'
import { Radio } from '@base-ui/react/radio'
import { RadioGroup } from '@base-ui/react/radio-group'
import { paperSelectHaptic } from './paperHaptics'

export type PaperChoiceOption<T extends string = string> = {
  value: T
  label: string
  /** Quieter second line (e.g. “No word highlighting”). */
  description?: string
  /** Optional trailing ornament (theme swatches, etc.). */
  trailing?: ReactNode
}

type Props<T extends string> = {
  'aria-label': string
  value: T
  options: PaperChoiceOption<T>[]
  onChange: (value: T) => void
}

/**
 * Vertical ink choice list — Android `SelectRow` parity. A calligraphic nuqta
 * leads each row; selection is carried by ink strength (alpha), not accent color on
 * the label. No floating popup, no Material radio chrome.
 */
export function PaperChoiceList<T extends string>({
  'aria-label': ariaLabel,
  value,
  options,
  onChange,
}: Props<T>) {
  return (
    <RadioGroup
      className="paper-choice-list"
      aria-label={ariaLabel}
      value={value}
      onValueChange={(next) => {
        if (next == null || next === value) return
        paperSelectHaptic()
        onChange(next as T)
      }}
    >
      {options.map((opt) => {
        const selected = opt.value === value
        return (
          <label
            key={opt.value}
            className={`paper-choice-row${selected ? ' is-selected' : ''}`}
          >
            <Radio.Root value={opt.value} className="paper-choice-radio ink-nuqta">
              <InkNuqta />
            </Radio.Root>
            <span className="paper-choice-copy">
              <span className="paper-choice-label">{opt.label}</span>
              {opt.description ? (
                <span className="paper-choice-desc">{opt.description}</span>
              ) : null}
            </span>
            {opt.trailing ? (
              <span className="paper-choice-trailing">{opt.trailing}</span>
            ) : null}
          </label>
        )
      })}
    </RadioGroup>
  )
}

/**
 * Android `InkNuqta`: a qalam-cut Arabic dot. The wet edge, body and dense pool
 * spread on one staggered clock when the parent radio is `[data-checked]`
 * (timings live with `.ink-nuqta` in styles.css); unchecking lifts the ink.
 */
function InkNuqta() {
  const clip = useId()
  return (
    <svg className="ink-nuqta-mark" viewBox="0 0 20 20" aria-hidden="true">
      <clipPath id={clip}>
        <path d={NUQTA_PATH} />
      </clipPath>
      <g clipPath={`url(#${clip})`}>
        <circle className="ink-nuqta-wet" cx="10" cy="10" r="15.68" />
        <circle className="ink-nuqta-body" cx="11.1" cy="9.3" r="13.72" />
        <circle className="ink-nuqta-pool" cx="9.4" cy="10.8" r="11.76" />
      </g>
      <path className="ink-nuqta-outline" d={NUQTA_PATH} />
    </svg>
  )
}

/** Android `inkNuqtaPath(20f)`: the lightly bowed rhombus of one qalam touch. */
const NUQTA_PATH =
  'M9.8 1.8Q13.8 3.2 18.2 8.6Q17.2 13.2 10.6 18.2Q6.2 16.8 1.8 11.6Q2.8 6.6 9.8 1.8Z'
