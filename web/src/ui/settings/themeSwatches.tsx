import { useEffect, useState, type CSSProperties } from 'react'
import type { ThemeMode } from '../../data/settings'

const PAPER = '#FAF3E8'
const NIGHTFALL = '#0C0B09'
const ROYAL_GREEN = '#062C24'
/** Paper gilt on light paper; warmer gilt on the dark sheets (Android Theme.kt). */
const GILT_LIGHT = '#C9A227'
const GILT_DARK = '#D9B44A'

/** Main paper colour and gilt rim for each theme (Android `ThemeColorPreview`). */
export function themeSwatch(mode: ThemeMode, systemDark: boolean): { fill: string; gilt: string } {
  switch (mode) {
    case 'light':
      return { fill: PAPER, gilt: GILT_LIGHT }
    case 'dark':
      return { fill: NIGHTFALL, gilt: GILT_DARK }
    case 'royal_green':
      return { fill: ROYAL_GREEN, gilt: GILT_DARK }
    case 'system':
      return systemDark ? { fill: NIGHTFALL, gilt: GILT_DARK } : { fill: PAPER, gilt: GILT_LIGHT }
  }
}

/** A small sample of the theme's paper in a gilded rim. */
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
      style={{ background: fill, '--swatch-gilt': gilt } as CSSProperties}
    />
  )
}
