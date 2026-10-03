import { describe, expect, it } from 'vitest'
import { coverLayout, coverLayoutCssVars, sealBox } from '../coverLayout'

describe('coverLayout', () => {
  it('scales frame and corner seals with the short side', () => {
    const phone = coverLayout(390, 844)
    const sheet = coverLayout(896, 900)

    expect(phone.outerInset).toBeGreaterThan(18)
    expect(phone.innerRadius).toBeCloseTo(
      Math.max(20, phone.outerRadius - (phone.innerInset - phone.outerInset)),
      5,
    )
    expect(phone.innerInset).toBeGreaterThan(phone.outerInset)

    expect(sheet.outerInset).toBeGreaterThan(phone.outerInset)
    expect(sheet.sealRadius).toBeGreaterThan(phone.sealRadius)
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
    expect(vars['--cover-seal-c']).toMatch(/px$/)
    expect(vars['--cover-medallion']).toMatch(/px$/)
    expect(vars['--cover-air-top']).toMatch(/^\d/)
  })

  it('never collapses the frame on tiny boards', () => {
    const tiny = coverLayout(280, 400)
    expect(tiny.outerInset).toBeGreaterThan(12)
    expect(tiny.sealRadius * 2).toBeGreaterThan(12)
    expect(tiny.titleAr).toBeGreaterThan(14)
  })

  it('seats each corner seal inside the border band', () => {
    for (const [w, h] of [[390, 844], [896, 900], [600, 970], [280, 400]] as const) {
      const layout = coverLayout(w, h)
      // Centre of the corner arcs both rules share.
      const corner = layout.outerRadius + layout.outerInset
      const fromArcCentre = Math.hypot(corner - layout.sealCenter, corner - layout.sealCenter)
      // Tangent to the outer rule and to the inner one: neither is broken.
      expect(fromArcCentre + layout.sealRadius).toBeCloseTo(layout.outerRadius, 5)
      expect(fromArcCentre - layout.sealRadius).toBeCloseTo(corner - layout.innerInset, 5)
      // The drawing box is larger than the seal: its tips sit at 0.62 of it.
      expect(sealBox(layout, 0.62) * 0.62).toBeCloseTo(layout.sealRadius, 5)
    }
  })

  it('falls back to the miter when a corner has no arc', () => {
    const layout = { ...coverLayout(390, 844) }
    expect(layout.sealCenter).toBeLessThan(layout.outerRadius + layout.outerInset)
    expect(layout.sealCenter).toBeGreaterThan(layout.bandCenter)
  })
})
