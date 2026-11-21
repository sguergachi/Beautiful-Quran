import { useEffect, useState, type CSSProperties } from 'react'
import type { ThemeMode } from '../../data/settings'

const PAPER = '#FAF3E8'
const NIGHTFALL = '#0C0B09'
const ROYAL_GREEN = '#062C24'
/** Each theme's gilding stops, deep and bright (Android Theme.kt goldDeep/goldBright). */
const GILT_PAPER = { deep: '#7A5B05', bright: '#C9AE67' }
const GILT_NIGHTFALL = { deep: '#9A7B32', bright: '#ECD189' }
const GILT_ROYAL = { deep: '#A07F38', bright: '#F2D68F' }

type Swatch = { fill: string; gilt: { deep: string; bright: string } }

/** Main paper colour and gilding for each theme (Android `ThemeColorPreview`). */
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

/** A small sample of the theme's paper with one gilt dot, no rim. */
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
          '--swatch-gilt-deep': gilt.deep,
          '--swatch-gilt-bright': gilt.bright,
        } as CSSProperties
      }
    />
  )
}
