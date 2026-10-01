import { describe, expect, it } from 'vitest'
import { finishPageTurn, requestPageTurn, type PageTurnQueue } from '../pageTurnQueue'

const open: PageTurnQueue = { settled: 1, flight: null, queued: null }

describe('page turn queue', () => {
  it('keeps one leaf in flight and queues only the latest destination', () => {
    const first = requestPageTurn(open, 3)
    const second = requestPageTurn(first, 5)
    const third = requestPageTurn(second, 9)
    expect(third.flight).toBe(first.flight)
    expect(third.settled).toBe(1)
    expect(third.queued).toBe(9)
    const landed = finishPageTurn(third)
    expect(landed).toEqual({ settled: 3, flight: { from: 3, to: 9 }, queued: null })
    expect(finishPageTurn(landed)).toEqual({ settled: 9, flight: null, queued: null })
  })

  it('lands the current turn before reversing to the old page', () => {
    const first = requestPageTurn(open, 3)
    const reversed = requestPageTurn(first, 1)
    expect(reversed.flight).toBe(first.flight)
    expect(finishPageTurn(reversed)).toEqual({ settled: 3, flight: { from: 3, to: 1 }, queued: null })
  })

  it('ignores a late completion belonging to the previous leaf', () => {
    const first = requestPageTurn(open, 3)
    const next = finishPageTurn(requestPageTurn(first, 5))
    expect(finishPageTurn(next, first.flight)).toBe(next)
  })

  it('drops a queued reversal if the current landing is requested again', () => {
    const first = requestPageTurn(open, 3)
    expect(requestPageTurn(requestPageTurn(first, 1), 3).queued).toBeNull()
  })

  it('does nothing for an already settled page or a duplicate completion', () => {
    expect(requestPageTurn(open, 1)).toBe(open)
    expect(finishPageTurn(open)).toBe(open)
  })
})
