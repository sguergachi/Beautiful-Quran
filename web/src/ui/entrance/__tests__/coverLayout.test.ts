import { describe, expect, it } from 'vitest'
import { coverLayout, coverLayoutCssVars } from '../coverLayout'

describe('coverLayout', () => {
  it('scales frame and corner seals with the short side', () => {
    const phone = coverLayout(390, 844)
    const sheet = coverLayout(896, 900)

    expect(phone.outerInset).toBeGreaterThan(18)
    expect(phone.innerRadius).toBeCloseTo(
      Math.max(20, phone.outerRadius - (phone.innerInset - phone.outerInset)),
      5,
    )
    expect(phone.starSize).toBeGreaterThan(phone.outerInset)
    expect(phone.starSize).toBeLessThan(phone.outerInset * 1.7)
    expect(phone.innerInset).toBeGreaterThan(phone.outerInset)

    expect(sheet.outerInset).toBeGreaterThan(phone.outerInset)
    expect(sheet.starSize).toBeGreaterThan(phone.starSize)
    expect(sheet.medallion).toBeGreaterThan(phone.medallion)
    expect(sheet.titleAr).toBeGreaterThanOrEqual(phone.titleAr)
    expect(sheet.titleAr).toBeLessThanOrEqual(40)
  })

  it('keeps the medallion from eating the vertical stack on tall phones', () => {
    const tall = coverLayout(360, 800)
    expect(tall.medallion).toBeLessThanOrEqual(800 * 0.3 + 0.5)
    expect(tall.medallion + tall.gapMedallionTitle + tall.titleAr * 2.4).toBeLessThan(
      800 - tall.padY * 2,
    )
  })

  it('compresses top air on squat / landscape boards', () => {
    const portrait = coverLayout(390, 844)
    const landscape = coverLayout(900, 500)
    expect(landscape.airTop).toBeLessThan(portrait.airTop)
    expect(landscape.medallion).toBeLessThanOrEqual(500 * 0.3 + 0.5)
  })

  it('emits CSS pixel vars for the board', () => {
    const vars = coverLayoutCssVars(coverLayout(400, 700))
    expect(vars['--cover-star']).toMatch(/px$/)
    expect(vars['--cover-medallion']).toMatch(/px$/)
    expect(vars['--cover-air-top']).toMatch(/^\d/)
  })

  it('never collapses the frame on tiny boards', () => {
    const tiny = coverLayout(280, 400)
    expect(tiny.outerInset).toBeGreaterThan(12)
    expect(tiny.starSize).toBeGreaterThan(18)
    expect(tiny.titleAr).toBeGreaterThan(14)
  })
})
