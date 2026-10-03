import { describe, expect, it } from 'vitest'
import { createWheelTurn, isSidewaysWheel, WHEEL_TURN_HOLD_MS, WHEEL_TURN_IDLE_MS } from '../wheelTurn'

describe('two-finger sweep', () => {
  it('turns on when the fingers move right, back when they move left', () => {
    const on = createWheelTurn(60)
    expect(on(-30, 0, 0)).toBe(0)
    expect(on(-40, 0, 16)).toBe(1)
    const back = createWheelTurn(60)
    expect(back(30, 0, 0)).toBe(0)
    expect(back(40, 0, 16)).toBe(-1)
  })

  it('turns one leaf for one sweep, momentum tail included', () => {
    const turn = createWheelTurn(60)
    let turns = 0
    for (let i = 0; i < 40; i++) turns += Math.abs(turn(-25, 0, i * 16))
    expect(turns).toBe(1)
  })

  it('takes a new sweep once the wheel has been quiet', () => {
    const turn = createWheelTurn(60)
    expect(turn(-80, 0, 0)).toBe(1)
    expect(turn(-80, 0, 16)).toBe(0)
    expect(turn(-80, 0, 16 + WHEEL_TURN_HOLD_MS + WHEEL_TURN_IDLE_MS + 1)).toBe(1)
  })

  it('does not take the rest of a sweep for a new one when the turn it started held the thread up', () => {
    const turn = createWheelTurn(60)
    expect(turn(-40, 0, 0)).toBe(0)
    expect(turn(-40, 0, 40)).toBe(1)
    // The turn starts: the next events of the same sweep arrive late.
    let turns = 0
    for (let i = 0; i < 8; i++) turns += Math.abs(turn(-40, 0, 40 + WHEEL_TURN_IDLE_MS + 50 + i * 45))
    expect(turns).toBe(0)
  })

  it('leaves a vertical scroll alone', () => {
    const turn = createWheelTurn(60)
    for (let i = 0; i < 20; i++) expect(turn(-10, 40, i * 16)).toBe(0)
    expect(isSidewaysWheel(-10, 40)).toBe(false)
    expect(isSidewaysWheel(-40, 10)).toBe(true)
  })
})
