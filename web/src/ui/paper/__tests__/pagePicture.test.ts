import { describe, expect, it } from 'vitest'
import { parseFootFade, parseMatrix, snapFrame, textRuns } from '../pagePicture'
import { parseRgba, sweepBox } from '../pageTurn'

describe('snapFrame', () => {
  it('moves a frame out to whole device pixels', () => {
    expect(snapFrame({ left: 226.766, top: 50.969, width: 493.234, height: 798.062 }, 1))
      .toEqual({ left: 227, top: 51, width: 493, height: 798 })
    const dense = snapFrame({ left: 10.3, top: 0.2, width: 100.1, height: 50.4 }, 2)
    for (const value of Object.values(dense)) expect(Number.isInteger(value * 2)).toBe(true)
  })
})

describe('parseMatrix', () => {
  it('reads a computed 2D transform', () => {
    expect(parseMatrix('matrix(1.06, 0, 0, 1, 0, 0)')).toEqual([1.06, 0, 0, 1, 0, 0])
  })

  it('leaves none, and anything in depth, alone', () => {
    expect(parseMatrix('none')).toBeNull()
    expect(parseMatrix('matrix3d(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)')).toBeNull()
  })
})

describe('parseFootFade', () => {
  it('reads how deep a foot fade is from the computed mask', () => {
    expect(parseFootFade('linear-gradient(rgb(0, 0, 0) calc(100% - 36.96px), rgba(0, 0, 0, 0))')).toBeCloseTo(36.96)
  })

  it('is nothing for no mask or another kind', () => {
    expect(parseFootFade('none')).toBe(0)
    expect(parseFootFade('url("mask.svg")')).toBe(0)
  })
})

describe('textRuns', () => {
  it('cuts a text node into its words', () => {
    expect(textRuns('  In the  name ')).toEqual([
      { start: 2, end: 4 },
      { start: 5, end: 8 },
      { start: 10, end: 14 },
    ])
    expect(textRuns(' \n ')).toEqual([])
  })
})

describe('parseRgba', () => {
  it('reads computed colours, with and without alpha', () => {
    expect(parseRgba('rgb(255, 0, 51)')).toEqual([1, 0, 0.2, 1])
    expect(parseRgba('rgba(60, 44, 20, 0.16)')?.[3]).toBeCloseTo(0.16)
    expect(parseRgba('color(srgb 1 0 0)')).toBeNull()
  })
})

describe('sweepBox', () => {
  const shell = { left: 0, top: 0, width: 1440, height: 900 }

  it('holds both pages and the overhang of a leaf standing toward the eye', () => {
    const box = sweepBox(720, 51, 493, 798, 493, 720, 450, 2600, { ...shell, width: 4000, height: 4000, left: -1000, top: -1000 })
    expect(box.left).toBeLessThan(720 - 493)
    expect(box.left + box.width).toBeGreaterThan(720 + 493)
    expect(box.top).toBeLessThan(51)
    expect(box.top + box.height).toBeGreaterThan(51 + 798)
  })

  it('never leaves the window', () => {
    const box = sweepBox(720, 51, 493, 798, 493, 720, 450, 2600, shell)
    expect(box.left).toBeGreaterThanOrEqual(0)
    expect(box.top).toBeGreaterThanOrEqual(0)
    expect(box.left + box.width).toBeLessThanOrEqual(1440)
    expect(box.top + box.height).toBeLessThanOrEqual(900)
  })
})
