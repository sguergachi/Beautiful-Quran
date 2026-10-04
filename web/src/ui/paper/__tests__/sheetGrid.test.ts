import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { SCROLL_MARGIN } from '../../reader/scrollGrid'
import { LEAF_MARGIN, REFERENCE_MARGIN, SETTINGS, SHEET_MARGIN, SHEET_UNIT } from '../sheetGrid'

const css = readFileSync(fileURLToPath(new URL('../../theme/styles.css', import.meta.url)), 'utf8')

/** The value a custom property is first given, in the root block. */
function token(name: string): string {
  const match = new RegExp(`^  ${name}: ([^;]+);`, 'm').exec(css)
  if (!match) throw new Error(`${name} is not declared`)
  return match[1]
}

/** The declarations of the first rule whose selector is exactly [selector]. */
function rule(selector: string): string {
  const at = css.indexOf(`\n${selector} {\n`)
  if (at < 0) throw new Error(`no rule for ${selector}`)
  return css.slice(at, css.indexOf('\n}', at))
}

const deck = ".app-shell:not([data-spread='true'])"

describe('sheet grid', () => {
  it('gives each kind of sheet Android\'s margin', () => {
    expect(SHEET_MARGIN).toBe(28)
    expect(REFERENCE_MARGIN).toBe(24)
    expect(SCROLL_MARGIN).toBe(38)
    expect(LEAF_MARGIN).toBe(10)
  })

  it('hangs Settings on the step, but for Android\'s two optical figures', () => {
    const offStep = Object.entries(SETTINGS).filter(([, figure]) => figure % SHEET_UNIT !== 0).map(([name]) => name)
    expect(offStep).toEqual(['chevronToTitle', 'colophonToCredits'])
  })

  it('is what the stylesheet declares', () => {
    expect(token('--grid')).toBe(`${SHEET_UNIT}px`)
    expect(token('--sheet-margin')).toBe(`${SHEET_MARGIN}px`)
    expect(token('--reference-margin')).toBe(`${REFERENCE_MARGIN}px`)
    expect(token('--leaf-margin')).toBe(`${LEAF_MARGIN}px`)
    expect(token('--scroll-margin')).toBe(`${SCROLL_MARGIN}px`)
    expect(token('--home-start-inset')).toBe(`${SHEET_MARGIN}px`)
    expect(token('--home-end-inset')).toBe(`${SHEET_MARGIN}px`)
  })

  it('fixes the deck\'s margins instead of scaling them with the window', () => {
    expect(rule(deck)).toContain('--sheet-pad: var(--sheet-margin);')
    expect(rule(`${deck} .ink-bleed`)).toContain('--root-inline-padding: var(--reference-margin);')
    expect(css).toContain('--bookmark-pad: var(--reference-margin);')
    // No margin of the deck is a share of the viewport.
    expect(rule(deck)).not.toMatch(/vw|clamp/)
  })

  it('sets Settings by those figures', () => {
    expect(rule(`${deck} .settings`)).toContain(`+ ${SETTINGS.head}px)`)
    expect(rule(`${deck} .settings`)).toContain(`calc(${SETTINGS.foot}px +`)
    expect(rule(`${deck} :is(.settings .back, .settings-back)`)).toContain(`margin-bottom: ${SETTINGS.chevronToTitle}px;`)
    expect(rule(`${deck} .settings h1`)).toContain(`margin-bottom: ${SETTINGS.titleToLabel}px;`)
    expect(rule(`${deck} .settings-section h2`)).toContain(`margin-bottom: ${SETTINGS.labelToRows}px;`)
    expect(rule(`${deck} .paper-check-row`)).toContain(`padding-block: ${SETTINGS.rowPad}px;`)
    expect(rule(`${deck} .settings-dev-block`)).toContain(`margin-top: ${SETTINGS.groupGap}px;`)
    expect(rule(`${deck} .settings-colophon`)).toContain(`margin: ${SETTINGS.toColophon}px 0 ${SETTINGS.colophonToCredits}px;`)
  })
})
