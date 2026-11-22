import { useEffect, useState, type CSSProperties } from 'react'
import type { ThemeMode } from '../../data/settings'

const PAPER = '#FAF3E8'
const NIGHTFALL = '#0C0B09'
const ROYAL_GREEN = '#062C24'
/** Each theme's gold (Android Theme.kt `gold`). */
const GILT_PAPER = '#A8831D'
const GILT_NIGHTFALL = '#CAA547'
const GILT_ROYAL = '#D0AA4D'

type Swatch = { fill: string; gilt: string }

/** Main paper colour and gold for each theme (Android `ThemeColorPreview`). */
export function themeSwatch(mode: ThemeMode, systemDark: boolean): Swatch {
  switch (mode) {
    case 'light':
      return { fill: PAPER, gilt: GILT_PAPER }
    case 'dark':
      return { fill: NIGHTFALL, gilt: GILT_NIGHTFALL }
    case 'royal_green':
      return { fill: ROYAL_GREEN, gilt: GILT_ROYAL }
    case 'system':
      return systemDark
        ? { fill: NIGHTFALL, gilt: GILT_NIGHTFALL }
        : { fill: PAPER, gilt: GILT_PAPER }
  }
}

/** A small sample of the theme's paper edged with a hairline of its gold. */
export function ThemeSwatches({ mode }: { mode: ThemeMode }) {
  const [systemDark, setSystemDark] = useState(() =>
    typeof window !== 'undefined'
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
      : false,
  )

  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => setSystemDark(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  const { fill, gilt } = themeSwatch(mode, systemDark)
  return (
    <span
      className="theme-paint"
      aria-hidden="true"
      style={
        {
          background: fill,
          '--swatch-gilt': gilt,
        } as CSSProperties
      }
    />
  )
}
