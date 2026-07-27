import { describe, expect, it, vi } from 'vitest'
import { MediaElementTransport, type MediaElementEvents } from '../mediaElementTransport'
import { FakeAudio } from './fakeAudio'

function events(): MediaElementEvents {
  return {
    timeUpdate: vi.fn(),
    ended: vi.fn(),
    play: vi.fn(),
    playing: vi.fn(),
    pause: vi.fn(),
    waiting: vi.fn(),
    stalled: vi.fn(),
    error: vi.fn(),
    loadedMetadata: vi.fn(),
    canPlayThrough: vi.fn(),
  }
}

describe('MediaElementTransport', () => {
  it('keeps one persistent element in the iOS transport', () => {
    const created: FakeAudio[] = []
    const transport = new MediaElementTransport(true, events(), () => {
      const audio = new FakeAudio()
      created.push(audio)
      return audio.asAudio()
    })

    expect(created).toHaveLength(1)
    expect(transport.standby).toBeNull()
  })

  it('promotes standby and ignores later events from the retired element', () => {
    const created: FakeAudio[] = []
    const callbacks = events()
    const transport = new MediaElementTransport(false, callbacks, () => {
      const audio = new FakeAudio()
      created.push(audio)
      return audio.asAudio()
    })
    const [first, second] = created as [FakeAudio, FakeAudio]

    transport.prepareStandby(1, 'blob:ayah-2', 1.25)
    expect(transport.isStandbyReady(1)).toBe(true)
    expect(transport.promoteStandby(1)).toBe(true)

    first.emit('pause')
    expect(callbacks.pause).not.toHaveBeenCalled()
    second.emit('play')
    expect(callbacks.play).toHaveBeenCalledOnce()
    expect(first.src).toBe('')
  })

  it('waits for canplay and rejects an element error', async () => {
    const audio = new FakeAudio()
    audio.readyState = 0
    const transport = new MediaElementTransport(true, events(), () => audio.asAudio())

    const ready = transport.waitForCanPlay(audio.asAudio())
    audio.emit('canplay')
    await expect(ready).resolves.toBeUndefined()

    const failed = transport.waitForCanPlay(audio.asAudio())
    audio.emit('error')
    await expect(failed).rejects.toThrow('Audio failed to load')
  })

  it('seeds defaultPlaybackRate so src/load cannot wipe a non-1× preference', () => {
    const audio = new FakeAudio()
    // Simulate browser re-seed from defaultPlaybackRate during load().
    audio.load.mockImplementation(() => {
      audio.playbackRate = audio.defaultPlaybackRate
    })
    const transport = new MediaElementTransport(true, events(), () => audio.asAudio())

    transport.loadActive({
      src: 'https://example.test/001001.mp3',
      loop: false,
      playbackRate: 0.75,
      volume: 1,
    })

    expect(audio.defaultPlaybackRate).toBe(0.75)
    expect(audio.playbackRate).toBe(0.75)
  })

  it('updates standby rate when the same clip is re-prepared at a new speed', () => {
    const created: FakeAudio[] = []
    const transport = new MediaElementTransport(false, events(), () => {
      const audio = new FakeAudio()
      created.push(audio)
      return audio.asAudio()
    })
    const standby = created[1]!

    transport.prepareStandby(1, 'blob:ayah-2', 1)
    transport.setSpeed(0.75)
    // prepareStandby early-returns on same source; must still honor the rate.
    transport.prepareStandby(1, 'blob:ayah-2', 0.75)

    expect(standby.defaultPlaybackRate).toBe(0.75)
    expect(standby.playbackRate).toBe(0.75)
  })
})
