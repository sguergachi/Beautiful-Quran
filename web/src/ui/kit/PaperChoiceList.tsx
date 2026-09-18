import { useContext, useId, type ReactNode } from 'react'
import { Radio } from '@base-ui/react/radio'
import { RadioGroup } from '@base-ui/react/radio-group'
import { NuqtaParamsContext, nuqtaPath, nuqtaStyleVars } from './nuqta'
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
  const nuqta = useContext(NuqtaParamsContext)
  return (
    <RadioGroup
      className="paper-choice-list"
      style={nuqtaStyleVars(nuqta)}
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
 * spread on one staggered clock when the parent radio is `[data-checked]`;
 * unchecking lifts the ink. Geometry comes from [NuqtaParamsContext] here, the
 * timings from the CSS custom properties `.ink-nuqta` in styles.css reads.
 */
function InkNuqta() {
  const clip = useId()
  const p = useContext(NuqtaParamsContext)
  // The viewBox is always 20 units; dp knobs scale into it.
  const unit = 20 / p.sizeDp
  const d = nuqtaPath(20, p.bow)
  return (
    <svg className="ink-nuqta-mark" viewBox="0 0 20 20" aria-hidden="true">
      <clipPath id={clip}>
        <path d={d} />
      </clipPath>
      <g clipPath={`url(#${clip})`}>
        {(['wet', 'body', 'pool'] as const).map((l) => (
          <circle
            key={l}
            className={`ink-nuqta-${l}`}
            cx={10 + p[`${l}Dx`] * unit}
            cy={10 + p[`${l}Dy`] * unit}
            r={14 * p[`${l}Radius`]}
          />
        ))}
      </g>
      <path className="ink-nuqta-outline" d={d} strokeWidth={p.strokeDp * unit} />
    </svg>
  )
}
