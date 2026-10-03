import { describe, expect, it } from 'vitest'
import type { ActiveWord } from '../../../data/models'
import { InkState } from '../InkEngine'
import { MUSHAF_INK_IDLE, mushafTokenInk, type MushafInk } from '../mushafInk'

const word = (ayah: number, wordPosition: number, extra: Partial<ActiveWord> = {}): ActiveWord => ({
  ayah,
  wordPosition,
  durationMs: 600,
  isRepeat: false,
  highWater: wordPosition,
  repeatStart: wordPosition,
  activation: 0,
  ...extra,
})

const voice = (extra: Partial<MushafInk>): MushafInk => ({
  reciting: true,
  inkAyah: 5,
  leadAyah: 5,
  activeWord: word(5, 3),
  speed: 1,
  ...extra,
})

const at = (ayah: number, position: number, surahId = 2) => ({ surahId, ayah, position })
const state = (token: ReturnType<typeof at>, ink: MushafInk, live = true) =>
  mushafTokenInk(token, 2, ink, live).state

describe('mushaf leaf ink', () => {
  it('runs the karaoke wash through the voiced verse', () => {
    const ink = voice({})
    expect(state(at(5, 2), ink)).toBe(InkState.Recited)
    expect(state(at(5, 3), ink)).toBe(InkState.Active)
    expect(state(at(5, 4), ink)).toBe(InkState.Upcoming)
  })

  it('keeps what has been read in full ink and holds what is to come under paper', () => {
    const ink = voice({})
    expect(state(at(4, 9), ink)).toBe(InkState.Plain)
    expect(state(at(6, 1), ink)).toBe(InkState.Upcoming)
    // A chapter sharing the leaf stands before or after the whole of this one.
    expect(state(at(200, 1, 1), ink)).toBe(InkState.Plain)
    expect(state(at(1, 1, 3), ink)).toBe(InkState.Upcoming)
  })

  it('softens the next verse before the audio hands off, without giving it the wash', () => {
    const ink = voice({ leadAyah: 6 })
    expect(state(at(5, 3), ink)).toBe(InkState.Active)
    expect(state(at(6, 1), ink)).toBe(InkState.Upcoming)
    expect(state(at(7, 1), ink)).toBe(InkState.Upcoming)
  })

  it('waits under paper until the verse has its first word', () => {
    const ink = voice({ activeWord: null })
    expect(state(at(5, 1), ink)).toBe(InkState.Upcoming)
  })

  it('carries the orange repeat chain', () => {
    const ink = voice({ activeWord: word(5, 3, { isRepeat: true, repeatStart: 2, highWater: 4 }) })
    expect(mushafTokenInk(at(5, 2), 2, ink, true).repeat).toBe(true)
    expect(mushafTokenInk(at(5, 3), 2, ink, true).repeat).toBe(true)
    expect(mushafTokenInk(at(5, 4), 2, ink, true)).toEqual({ state: InkState.Recited, repeat: false })
  })

  it('holds the whole leaf back while the basmalah is recited', () => {
    const ink = voice({ inkAyah: 0, leadAyah: null, activeWord: null })
    expect(state(at(1, 1), ink)).toBe(InkState.Upcoming)
  })

  it('leaves a browsed leaf, and a silent one, in plain ink', () => {
    expect(state(at(6, 1), voice({}), false)).toBe(InkState.Plain)
    expect(state(at(5, 3), MUSHAF_INK_IDLE)).toBe(InkState.Plain)
  })
})
