import { describe, expect, it } from 'vitest'
import { QfBookmarkApi, type QfRequest } from './qfBookmarkApi'

const row = (id: number, group = 'verses_6236') =>
  ({ id: String(id), type: 'ayah', group, key: 2, verseNumber: id })
const page = (rows: unknown[], count: number, more: boolean, cursor: string) => ({
  success: true, data: { collection: { id: 'owned', bookmarksCount: count }, bookmarks: rows },
  pagination: { hasNextPage: more, endCursor: cursor },
})
const api = (request: QfRequest) => new QfBookmarkApi(request, (verse) =>
  Number.isInteger(verse.surahId) && verse.surahId === 2 &&
  Number.isInteger(verse.ayah) && verse.ayah >= 1 && verse.ayah <= 286)

describe('QF collection protocol', () => {
  it('reads every cursor page while preserving only the selected mushaf', async () => {
    const paths: string[] = []
    const remote = api(async (_method, path) => {
      paths.push(path)
      return paths.length === 1 ? page([row(1)], 3, true, 'cursor +')
        : page([row(2), row(3, 'verses_other')], 3, false, 'end')
    })
    expect(await remote.bookmarks('owned')).toEqual([{ surahId: 2, ayah: 1 }, { surahId: 2, ayah: 2 }])
    expect(paths[1]).toContain('&after=cursor%20%2B')
  })
  it('rejects cycling cursors, missing pages and a changing count', async () => {
    await expect(api(async () => page([row(1)], 3, true, 'same')).bookmarks('owned')).rejects.toThrow()
    await expect(api(async () => page([row(1)], 2, false, 'end')).bookmarks('owned')).rejects.toThrow()
    let call = 0
    await expect(api(async () => call++ === 0 ? page([row(1)], 2, true, 'a')
      : page([row(2)], 3, false, 'b')).bookmarks('owned')).rejects.toThrow()
  })
  it('detaches collection membership with exact verse details', async () => {
    const calls: unknown[] = []
    await api(async (...args) => { calls.push(args); return { success: true } })
      .write('owned', { verse: { surahId: 2, ayah: 255 }, present: false, revision: 1 })
    expect(calls).toEqual([['DELETE', '/v1/collections/owned/bookmarks',
      { key: 2, type: 'ayah', verseNumber: 255, mushafId: 4 }]])
  })
  it('rejects failed mutation envelopes and noncanonical verses', async () => {
    await expect(api(async () => ({ success: false })).write('owned',
      { verse: { surahId: 2, ayah: 255 }, present: true, revision: 1 })).rejects.toThrow()
    await expect(api(async () => page([row(287)], 1, false, 'end')).bookmarks('owned')).rejects.toThrow()
  })
  it('refuses ambiguous collections and finds an already-created collection without another POST', async () => {
    const calls: string[] = []
    const remote = api(async (method) => {
      calls.push(method)
      return { success: true, data: [{ id: 'owned', name: 'Beautiful Quran' }],
        pagination: { hasNextPage: false } }
    })
    expect(await remote.collection()).toBe('owned')
    expect(calls).toEqual(['GET'])
    await expect(api(async () => ({ success: true, data: [
      { id: 'a', name: 'Beautiful Quran' }, { id: 'b', name: 'Beautiful Quran' },
    ], pagination: { hasNextPage: false } })).collection()).rejects.toThrow()
  })
})
