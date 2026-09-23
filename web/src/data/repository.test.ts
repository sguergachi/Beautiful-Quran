import { afterEach, describe, expect, it, vi } from 'vitest'
import { ensureTimings, parseSegments, timings } from './repository'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('parseSegments', () => {
  it('parses compact JSON timing rows', () => {
    expect(parseSegments('[[1,0,500],[2,510,900]]')).toEqual([
      { position: 1, startMs: 0, endMs: 500 },
      { position: 2, startMs: 510, endMs: 900 },
    ])
  })

  it('returns empty on malformed input', () => {
    expect(parseSegments('not-json')).toEqual([])
  })
})

describe('lazy timing corpora', () => {
  it('fetches one reciter corpus and materializes only the requested surah', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({
      1: { 1: [[1, 20, 300]] },
      2: { 5: [[2, 40, 500]] },
    })))
    vi.stubGlobal('fetch', fetch)

    expect(timings(99_901, 2).size).toBe(0)
    await ensureTimings(99_901)

    expect(fetch).toHaveBeenCalledWith('/timings/99901.json')
    expect(timings(99_901, 2).get(5)).toEqual([
      { position: 2, startMs: 40, endMs: 500 },
    ])
    expect(timings(99_901, 3).size).toBe(0)
  })

  it('rejects a missing corpus and permits a later retry', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 404 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ 1: { 1: [[1, 0, 1]] } })))
    vi.stubGlobal('fetch', fetch)

    await expect(ensureTimings(99_902)).rejects.toThrow(/404/)
    await expect(ensureTimings(99_902)).resolves.toBeUndefined()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
