import { describe, expect, it } from 'vitest'
import { createRepeatWashGate } from '../repeatWashGate'

describe('createRepeatWashGate', () => {
  it('runs jobs strictly one after another', async () => {
    const gate = createRepeatWashGate()
    const order: number[] = []
    const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))

    const a = gate.run(5, async () => {
      order.push(1)
      await delay(30)
      order.push(2)
      return 'a'
    })
    const b = gate.run(6, async () => {
      order.push(3)
      await delay(10)
      order.push(4)
      return 'b'
    })

    await expect(Promise.all([a, b])).resolves.toEqual(['a', 'b'])
    expect(order).toEqual([1, 2, 3, 4])
  })

  it('drains by word position after same-tick batching', async () => {
    const gate = createRepeatWashGate()
    const order: number[] = []
    const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))

    // Enqueue high first in the same turn — microtask batch still sorts 5→7→9.
    const high = gate.run(9, async () => {
      order.push(9)
      await delay(5)
    })
    const low = gate.run(5, async () => {
      order.push(5)
      await delay(5)
    })
    const mid = gate.run(7, async () => {
      order.push(7)
      await delay(5)
    })

    await Promise.all([high, low, mid])
    expect(order).toEqual([5, 7, 9])
  })

  it('keeps the queue alive after a rejection', async () => {
    const gate = createRepeatWashGate()
    const order: string[] = []

    await expect(
      gate.run(1, async () => {
        order.push('fail')
        throw new Error('boom')
      }),
    ).rejects.toThrow('boom')

    await gate.run(2, async () => {
      order.push('ok')
    })
    expect(order).toEqual(['fail', 'ok'])
  })
})
