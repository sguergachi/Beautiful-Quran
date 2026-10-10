import { afterEach, describe, expect, it, vi } from 'vitest'
import { ensureTimings, parseSegments, timings } from './repository'
import { runtimeTimingsCache, type RuntimeTimingResource } from './runtimeTimings'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
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
  it('uses the authenticated resource for QF reciters and keeps repeats and basmalah', async () => {
    const ensure = vi.spyOn(runtimeTimingsCache, 'ensure').mockResolvedValueOnce(undefined)
    vi.spyOn(runtimeTimingsCache, 'resource').mockReturnValue({
      rows: [
        [1, 1, [[1, 10, 20]], 10],
        [114, 1, [[1, 10, 20], [2, 20, 30], [1, 40, 50]], 10],
      ],
    } as RuntimeTimingResource)
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    await ensureTimings(7)
    expect(ensure).toHaveBeenCalledExactlyOnceWith(7)
    expect(fetch).not.toHaveBeenCalled()
    expect(timings(7, 114).get(1)?.map((segment) => segment.position)).toEqual([1, 2, 1])
    expect(timings(7, 1).get(1)).toEqual([{ position: 1, startMs: 10, endMs: 20 }])
  })

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
