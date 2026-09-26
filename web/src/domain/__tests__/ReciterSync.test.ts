import { describe, expect, it } from 'vitest'
import { highlightAdvanceMs } from '../ReciterSync'

describe('ReciterSync', () => {
  it('runs Yasser highlight 200 ms ahead', () => {
    expect(highlightAdvanceMs(9)).toBe(200)
  })

  it('leaves other reciters on their source clock', () => {
    expect(highlightAdvanceMs(1)).toBe(0)
    expect(highlightAdvanceMs(19)).toBe(0)
  })
})
