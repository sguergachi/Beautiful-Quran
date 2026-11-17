import { describe, expect, it } from 'vitest'
import {
  dialAlong,
  dialChapterAt,
  dialChapterAtHysteresis,
  dialChapterRun,
  dialCombSeats,
  dialFraction,
  dialLabelFoot,
  dialLabelHead,
  dialPastTrough,
  dialReturnLeft,
  dialShouldOpen,
  dialTrackFraction,
  dialTrackX,
  dialTroughPage,
  mushafReturnWay,
} from '../mushafDial'

/** A plausible 114-chapter comb: the head spread out, the tail crowded. */
const marks = Array.from({ length: 114 }, (_, i) => i < 24 ? 1 + i * 18 : Math.min(604, 433 + (i - 24) * 2))

describe('mushafDial', () => {
  it('puts leaf 1 at the binding: the right in a mushaf, the left in English', () => {
    expect(dialTrackX(dialAlong(dialFraction(1, 604), true), 300, 14)).toBe(286)
    expect(dialTrackX(dialAlong(dialFraction(1, 604), false), 300, 14)).toBe(14)
    expect(dialTrackX(dialAlong(dialFraction(604, 604), true), 300, 14)).toBe(14)
  })

  it('reads the measure back as the track it was drawn on', () => {
    expect(dialTrackFraction(dialTrackX(0.3, 300, 14), 300, 14)).toBeCloseTo(0.3)
    expect(dialTrackFraction(0, 300, 14)).toBe(0)
  })

  it("gives every chapter its own cell, in the book's order, both ways", () => {
    for (const rtl of [true, false]) {
      const seats = dialCombSeats(marks, 604, 14, 380, rtl)
      expect(seats).toHaveLength(114)
      for (let i = 1; i < seats.length; i++) {
        expect(rtl ? seats[i]! < seats[i - 1]! : seats[i]! > seats[i - 1]!).toBe(true)
      }
      seats.forEach((x, i) => expect(dialChapterAt(seats, x)).toBe(i))
    }
  })

  it('steps one chapter at a time as the hand crosses the comb', () => {
    for (const rtl of [true, false]) {
      const seats = dialCombSeats(marks, 604, 14, 380, rtl)
      let at = 0
      const seen = new Set<number>([0])
      const from = seats[0]!
      const to = seats[seats.length - 1]!
      for (let step = 0; step <= 2000; step++) {
        at = dialChapterAtHysteresis(seats, from + ((to - from) * step) / 2000, at)
        seen.add(at)
      }
      expect(seen.size).toBe(114)
    }
  })

  it('names the run of a chapter, a shared leaf never empty', () => {
    expect(dialChapterRun([1, 2, 50, 50, 77], 60, 604)).toEqual({ first: 50, last: 76 })
    expect(dialChapterRun([1, 2, 50, 50, 77], 600, 604)).toEqual({ first: 77, last: 604 })
    expect(dialChapterRun([1, 2, 50], 1, 604)).toEqual({ first: 1, last: 1 })
  })

  it('maps the trough absolutely to its chapter', () => {
    const run = { first: 50, last: 76 }
    expect(dialTroughPage(1, run, true)).toBe(50)
    expect(dialTroughPage(0, run, true)).toBe(76)
    expect(dialTroughPage(0, run, false)).toBe(50)
    expect(dialPastTrough(10, 300, 40)).toBe(true)
    expect(dialPastTrough(150, 300, 40)).toBe(false)
  })

  it('opens the trough only on a still hold of 1.62 s', () => {
    expect(dialShouldOpen(5, 1.7)).toBe(true)
    expect(dialShouldOpen(5, 1.5)).toBe(false)
    expect(dialShouldOpen(40, 3)).toBe(false)
  })

  it('keeps the return roundel on the rule and points it home', () => {
    expect(dialReturnLeft(1, 604, 300, 14, 44, true)).toBe(256)
    expect(dialReturnLeft(604, 604, 300, 14, 44, true)).toBe(0)
    expect(mushafReturnWay(10, 20, true)).toBe('left')
    expect(mushafReturnWay(10, 20, false)).toBe('right')
    expect(mushafReturnWay(10, 5, true)).toBe('right')
    expect(mushafReturnWay(10, 10, true)).toBe(null)
  })

  it('labels the tier it is working at', () => {
    const label = { number: 2, chapter: 'Al-Baqarah', fromAyah: 6, toAyah: 16 }
    expect(dialLabelHead(label, false, 2)).toBe('2  ·  Al-Baqarah')
    expect(dialLabelFoot(label, false, 2)).toBe('p. 2')
    expect(dialLabelHead(label, true, 3)).toBe('Al-Baqarah  ·  p. 3')
    expect(dialLabelFoot(label, true)).toBe('Ayah 6–16')
  })
})
