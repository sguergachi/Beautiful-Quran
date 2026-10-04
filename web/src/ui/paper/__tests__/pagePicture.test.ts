import { describe, expect, it } from 'vitest'
import { clearStops, parseFootFade, parseLinearGradient, parseMatrix, snapFrame, textRuns } from '../pagePicture'
import { parseRgba } from '../pageTurn'

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

describe('parseLinearGradient', () => {
  it('reads a computed gradient that runs down the box by default', () => {
    expect(parseLinearGradient('linear-gradient(rgb(250, 243, 232), rgba(0, 0, 0, 0))')).toEqual({
      angle: 180,
      stops: [{ colour: [250, 243, 232, 1], at: null }, { colour: [0, 0, 0, 0], at: null }],
    })
  })

  it('reads its side or angle, and where its stops stand', () => {
    expect(parseLinearGradient('linear-gradient(to top, rgb(1, 2, 3) 0%, rgba(4, 5, 6, 0.5) 100%)')).toEqual({
      angle: 0,
      stops: [{ colour: [1, 2, 3, 1], at: 0 }, { colour: [4, 5, 6, 0.5], at: 1 }],
    })
    expect(parseLinearGradient('linear-gradient(90deg, rgb(1, 2, 3), rgb(4, 5, 6))')?.angle).toBe(90)
  })

  it('takes only the first layer, and nothing that is not a linear gradient', () => {
    expect(parseLinearGradient('linear-gradient(rgb(1, 2, 3), rgb(4, 5, 6)), url("x.png")')?.stops).toHaveLength(2)
    expect(parseLinearGradient('none')).toBeNull()
    expect(parseLinearGradient('url("grain.svg")')).toBeNull()
    expect(parseLinearGradient('linear-gradient(red, blue)')).toBeNull()
  })
})

describe('clearStops', () => {
  it('fades paper to clear paper, not through black', () => {
    const stops = clearStops([{ colour: [250, 243, 232, 1], at: null }, { colour: [0, 0, 0, 0], at: null }])
    expect(stops[1].colour).toEqual([250, 243, 232, 0])
    const rising = clearStops([{ colour: [0, 0, 0, 0], at: 0 }, { colour: [10, 20, 30, 1], at: 1 }])
    expect(rising[0].colour).toEqual([10, 20, 30, 0])
  })
})
