import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  PLAYBACK_MESSAGES,
  classifyPlaybackError,
  describePlaybackError,
  playbackErrorMessage,
} from '../playbackErrors'

function domError(name: string, message = 'raw detail'): Error {
  const error = new Error(message)
  error.name = name
  return error
}

/** Shape of an HTMLMediaElement MediaError (no `name`). */
function mediaError(code: number) {
  return { code, message: 'DEMUXER_ERROR_COULD_NOT_OPEN' }
}

describe('playback error copy', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('asks the reader to press play when autoplay is blocked', () => {
    expect(playbackErrorMessage(domError('NotAllowedError'))).toBe(PLAYBACK_MESSAGES.autoplay)
  })

  it('maps MediaError codes, directly or as a cause', () => {
    expect(classifyPlaybackError(mediaError(2))).toBe('network')
    expect(classifyPlaybackError(mediaError(3))).toBe('decode')
    expect(classifyPlaybackError(mediaError(4))).toBe('unavailable')
    expect(classifyPlaybackError(mediaError(1))).toBe('generic')
    const wrapped = new Error('Audio failed to load', { cause: mediaError(4) })
    expect(classifyPlaybackError(wrapped)).toBe('unavailable')
  })

  it('maps DOMException names and transport messages', () => {
    expect(classifyPlaybackError(domError('NotSupportedError'))).toBe('unavailable')
    expect(classifyPlaybackError(domError('EncodingError'))).toBe('decode')
    expect(classifyPlaybackError(new Error('Audio buffer timeout'))).toBe('network')
    expect(classifyPlaybackError(new TypeError('Failed to fetch'))).toBe('network')
    expect(classifyPlaybackError('Failed to load audio track')).toBe('unavailable')
    expect(classifyPlaybackError('Error playing Gapless 5 audio')).toBe('generic')
    expect(classifyPlaybackError(undefined)).toBe('generic')
    expect(classifyPlaybackError(null)).toBe('generic')
  })

  it('blames the connection for anything but autoplay while offline', () => {
    vi.stubGlobal('navigator', { onLine: false })
    expect(classifyPlaybackError(mediaError(4))).toBe('network')
    expect(classifyPlaybackError('Failed to load audio track')).toBe('network')
    expect(classifyPlaybackError(domError('NotAllowedError'))).toBe('autoplay')
  })

  it('never shows raw error text, codes or library names', () => {
    const raw = [
      domError('NotAllowedError', "play() failed because the user didn't interact"),
      domError('AbortError', 'The play() request was interrupted'),
      new Error('ERROR_CODE_IO_NETWORK_CONNECTION_FAILED'),
      new Error('Gapless-5 module did not export Gapless5'),
      mediaError(3),
      'Failed to load audio track',
    ]
    for (const error of raw) {
      const message = playbackErrorMessage(error)
      expect(Object.values(PLAYBACK_MESSAGES)).toContain(message)
      expect(message).not.toMatch(/error|gapless|code|asm|wasm|exception/i)
    }
  })

  it('logs the raw error for debugging', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const error = new Error('ERROR_CODE_DECODING_FAILED decode')
    expect(describePlaybackError(error, 'play failed')).toBe(PLAYBACK_MESSAGES.decode)
    expect(warn).toHaveBeenCalledWith('[playback] play failed', error)
  })
})
