import { describe, expect, it } from 'vitest'
import {
  LEAF_CURL,
  SINGLE_TURN,
  cubicBezier,
  leafPose,
  sheetPoint,
} from '../pageTurnMotion'

const leaf = { single: false, wad: 0, reverse: false }
const steps = Array.from({ length: 201 }, (_, index) => index / 200)

describe('cubicBezier', () => {
  it('matches the CSS curve at its ends and middle', () => {
    expect(cubicBezier(0.42, 0, 0.58, 1, 0)).toBe(0)
    expect(cubicBezier(0.42, 0, 0.58, 1, 1)).toBe(1)
    expect(cubicBezier(0.42, 0, 0.58, 1, 0.5)).toBeCloseTo(0.5, 4)
    // Linear control points give the line back.
    expect(cubicBezier(1 / 3, 1 / 3, 2 / 3, 2 / 3, 0.37)).toBeCloseTo(0.37, 4)
  })
})

describe('a leaf turning across the spread', () => {
  it('lifts flat off one page and lies flat on the other', () => {
    expect(leafPose(0, leaf)).toMatchObject({ turn: 0, curl: 0 })
    const landed = leafPose(1, leaf)
    expect(landed.turn).toBeCloseTo(Math.PI, 6)
    expect(landed.curl).toBe(0)
  })

  it('only ever turns onward', () => {
    let last = -1
    for (const t of steps) {
      const { turn } = leafPose(t, leaf)
      expect(turn).toBeGreaterThanOrEqual(last)
      last = turn
    }
  })

  it('lifts its free edge first and bows no further than a leaf bends', () => {
    expect(leafPose(0.1, leaf).curl).toBeGreaterThan(leafPose(0.1, leaf).turn)
    for (const t of steps) expect(leafPose(t, leaf).curl).toBeLessThanOrEqual(LEAF_CURL + 1e-9)
  })

  it('never passes through the page it lands on', () => {
    for (const t of steps) {
      const { turn, curl, taper } = leafPose(t, leaf)
      // The surface nowhere tips past flat, so no point of it is under the page.
      expect(turn + curl).toBeLessThanOrEqual(Math.PI + 1e-9)
      for (const u of [0.25, 0.5, 0.75, 1]) {
        const footCurl = Math.min(curl * (1 + taper), Math.PI - turn)
        expect(sheetPoint(u, turn, footCurl).z).toBeGreaterThanOrEqual(-1e-9)
      }
    }
  })

  it('moves without a jump from one frame to the next', () => {
    let last = leafPose(0, leaf)
    for (const t of steps.slice(1)) {
      const pose = leafPose(t, leaf)
      expect(Math.abs(pose.turn - last.turn)).toBeLessThan(0.08)
      expect(Math.abs(pose.curl - last.curl)).toBeLessThan(0.08)
      last = pose
    }
  })
})

describe('a pile', () => {
  it('is stiffer than a leaf, and the whole block barely bends', () => {
    const most = (wad: number) => Math.max(...steps.map((t) => leafPose(t, { ...leaf, wad }).curl))
    expect(most(0.5)).toBeLessThan(most(0))
    expect(most(1)).toBeLessThan(LEAF_CURL * 0.2)
  })

  it('has sunk by its own thickness when it lands', () => {
    expect(leafPose(0, { ...leaf, wad: 1 }).share).toBe(0)
    expect(leafPose(1, { ...leaf, wad: 1 }).share).toBe(1)
  })
})

describe('a single leaf', () => {
  const up = { single: true, wad: 0, reverse: false }
  const down = { single: true, wad: 0, reverse: true }

  it('lifts to edge-on and stays bowed', () => {
    expect(leafPose(0, up)).toMatchObject({ turn: 0, curl: 0 })
    const gone = leafPose(1, up)
    expect(gone.turn).toBeCloseTo(SINGLE_TURN, 6)
    expect(gone.curl).toBeGreaterThan(0)
  })

  it('comes down from edge-on to flat', () => {
    expect(leafPose(0, down).turn).toBeCloseTo(SINGLE_TURN, 6)
    expect(leafPose(1, down)).toMatchObject({ turn: 0, curl: 0 })
  })

  it('is in sight early on the way down, not held back to the end', () => {
    // Half-way through its time the leaf is already most of the way down.
    expect(leafPose(0.5, down).turn).toBeLessThan(SINGLE_TURN * 0.4)
  })
})

describe('sheetPoint', () => {
  it('is a straight board when there is no curl', () => {
    const at = sheetPoint(1, Math.PI / 2, 0)
    expect(at.x).toBeCloseTo(0, 9)
    expect(at.z).toBeCloseTo(1, 9)
  })

  it('keeps the sheet its own length however it bends', () => {
    // Paper does not stretch: the length along the curve is the leaf's width.
    for (const [turn, curl] of [[0.3, 0.9], [1.2, 0.5], [2.4, 0.7]]) {
      let length = 0
      let last = sheetPoint(0, turn, curl)
      for (let index = 1; index <= 400; index++) {
        const at = sheetPoint(index / 400, turn, curl)
        length += Math.hypot(at.x - last.x, at.z - last.z)
        last = at
      }
      expect(length).toBeCloseTo(1, 3)
    }
  })
})
