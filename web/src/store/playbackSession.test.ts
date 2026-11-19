import { describe, expect, it } from 'vitest'
import type { PlayerState } from '../playback/player'
import { hasPlaybackSession, nextPlaybackSession } from './playbackSession'

const idle: PlayerState = {
  nowPlaying: null,
  isPlaying: false,
  isBuffering: false,
  positionMs: 0,
  durationMs: 0,
  speed: 1,
  repeatMode: 'off',
  repeatRange: null,
  error: null,
}
const at = (surahId: number, ayah: number, patch: Partial<PlayerState> = {}): PlayerState => ({
  ...idle,
  nowPlaying: { surahId, ayah, reciterId: 1 },
  ...patch,
})

/** Folds a sequence of player states through the reducer. */
function run(states: PlayerState[]): number | null {
  let session: number | null = null
  let prev = idle
  for (const next of states) {
    session = nextPlaybackSession(session, prev, next)
    prev = next
  }
  return session
}

describe('playback session', () => {
  it('a quiet chapter open parks a verse but starts no session', () => {
    const states = [at(1, 1)]
    expect(hasPlaybackSession(run(states), states.at(-1)!)).toBe(false)
  })

  it('a parked clip stalling in preload is not a session', () => {
    const states = [at(1, 1), at(1, 1, { isBuffering: true }), at(1, 1)]
    expect(hasPlaybackSession(run(states), states.at(-1)!)).toBe(false)
  })

  it('pressing play starts the session, and pausing keeps it', () => {
    const states = [at(2, 1), at(2, 1, { isBuffering: true }), at(2, 1, { isPlaying: true }), at(2, 2, { isPlaying: true }), at(2, 2)]
    expect(hasPlaybackSession(run(states), states.at(-1)!)).toBe(true)
  })

  it('opening another chapter while listening does not move the session to it', () => {
    const states = [
      at(2, 5, { isPlaying: true }),
      // The quiet open lands while the old clip is still pausing.
      at(1, 1, { isPlaying: true }),
      at(1, 1),
    ]
    const session = run(states)
    expect(session).toBe(2)
    expect(hasPlaybackSession(session, states.at(-1)!)).toBe(false)
  })

  it('stop ends the session', () => {
    expect(run([at(2, 1, { isPlaying: true }), idle])).toBeNull()
  })

  it('playing the newly opened chapter starts its own session', () => {
    const states = [at(2, 5, { isPlaying: true }), at(1, 1), at(1, 1, { isPlaying: true })]
    expect(run(states)).toBe(1)
  })
})
