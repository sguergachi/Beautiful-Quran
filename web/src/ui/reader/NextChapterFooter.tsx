/**
 * End-of-chapter invitation — Android `NextChapterFooter` parity.
 *
 * Built around the same opening block as the real surah header (weave +
 * medallion + title). Invitation chrome (air, NEXT, pill) only fades/collapses
 * around that block so a continuous advance can lift it into the header slot
 * without a pattern or type reconnect.
 */

import { useMemo } from 'react'
import type { Surah } from '../../data/models'
import { fieldWeaveBackground, GeneratedRosette, useFieldCellWidth } from '../theme/GeneratedOrnament'
import { chapterOrnamentSeed, generateChapterOrnament } from '../theme/ornamentGenerator'
import { resolveTheme } from '../App'

function chapterWeaveInk(themeMode: string): string {
  return resolveTheme(themeMode) === 'light'
    ? 'rgba(28, 27, 24, 0.03)'
    : 'rgba(232, 226, 213, 0.03)'
}

/** Material-ish ease for invitation collapse. */
function easeMorph(t: number): number {
  const x = Math.min(1, Math.max(0, t))
  return x * x * (3 - 2 * x)
}

type Props = {
  surah: Surah
  themeMode: string
  onOpen: () => void
  enabled?: boolean
  /**
   * 0 = full invitation chrome.
   * 1 = pure chapter opening (identical to the surah header block).
   */
  headerMorph?: number
}

export function NextChapterFooter({
  surah,
  themeMode,
  onOpen,
  enabled = true,
  headerMorph = 0,
}: Props) {
  const morph = easeMorph(headerMorph)
  // Chrome fully gone by ~0.85 so the scroll phase is a pure header lift.
  const invite = Math.max(0, 1 - morph / 0.85)
  const rosetteScale = 40 / 52 + (1 - 40 / 52) * morph
  const rosetteAlpha = 0.88 + 0.12 * morph

  const ornament = useMemo(
    () => generateChapterOrnament(chapterOrnamentSeed(surah.id, surah.ayahCount)),
    [surah.id, surah.ayahCount],
  )
  const fieldSize = useFieldCellWidth(ornament.field.cellWidthDp)
  const weave = useMemo(
    () =>
      fieldWeaveBackground(
        ornament.field,
        chapterWeaveInk(themeMode),
        'rgba(255, 255, 255, 0.0375)',
        fieldSize.cellWidth,
      ),
    [ornament.field, themeMode, fieldSize.cellWidth],
  )
  const place =
    surah.revelationPlace.length > 0
      ? surah.revelationPlace.charAt(0).toUpperCase() + surah.revelationPlace.slice(1)
      : surah.revelationPlace

  return (
    <div
      className="next-chapter"
      data-morphing={morph > 0.02 || undefined}
      style={
        {
          ['--invite' as string]: String(invite),
          ['--rosette-scale' as string]: String(rosetteScale),
          ['--rosette-alpha' as string]: String(rosetteAlpha),
        } as import('react').CSSProperties
      }
    >
      {/* Invitation air + NEXT — collapses without touching the opening layout. */}
      <div className="next-chapter-invite-top" aria-hidden={invite < 0.02}>
        <span className="next-chapter-label">Next</span>
      </div>

      {/* Same structure / classes as .surah-header — the continuous handoff target. */}
      <div className="surah-header next-chapter-opening">
        <div className="surah-header-weave" ref={fieldSize.ref} style={weave} aria-hidden="true" />
        <GeneratedRosette
          spec={ornament.rosette}
          className="rosette next-chapter-rosette"
          built
          animated={false}
          ruleWidth={4.6}
          hairWidth={4.6}
          brightGold="var(--gold-bright)"
          deepGold="var(--gold-deep)"
          embossDark="var(--emboss-dark)"
          embossLight="var(--emboss-light)"
        />
        {/* Same three lines as the reader's own opening: this block flies
            into that slot, so the two must set identically. */}
        <p className="ar-title" lang="ar" dir="rtl">
          سُورَةُ {surah.nameArabic}
        </p>
        <h2>
          {surah.nameTransliteration} · {surah.nameTranslation}
        </h2>
        <p className="sub">
          Chapter {surah.id} · {place.charAt(0).toUpperCase() + place.slice(1)} · {surah.ayahCount} ayahs
        </p>
      </div>

      <div className="next-chapter-invite-pill" aria-hidden={invite < 0.02}>
        <button
          type="button"
          className="next-chapter-pill"
          onClick={onOpen}
          disabled={!enabled || invite < 0.45}
          aria-label={`Open ${surah.nameTransliteration}`}
        >
          <span className="next-chapter-pill-label">Continue</span>
          <svg
            className="next-chapter-pill-arrow"
            viewBox="0 0 18 18"
            width="18"
            height="18"
            aria-hidden="true"
          >
            <path
              d="M4 7 L9 11.5 L14 7"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      </div>
    </div>
  )
}
