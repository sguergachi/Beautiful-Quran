import type { Bookmark } from '../data/settings'
import type { BookmarkEdit } from './bookmarkJournal'

type Row = Record<string, unknown>
export type QfRequest = (method: string, path: string, body?: Row) => Promise<Row>
export const QF_COLLECTION = 'Beautiful Quran'

function object(value: unknown): Row {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid QF response')
  return value as Row
}
function text(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error('Missing QF field')
  return value
}
function array(value: unknown): Row[] {
  if (!Array.isArray(value)) throw new Error('Missing QF rows')
  return value.map(object)
}

/** Never flatten the account's other collections into Beautiful Quran's local Boolean bookmarks. */
export class QfBookmarkApi {
  constructor(private request: QfRequest, private valid: (verse: Bookmark) => boolean) {}

  async collection(): Promise<string> {
    const rows = await this.pages('/v1/collections?first=20&sortBy=alphabetical',
      (response) => array(response.data))
    const matches = [...new Map(rows.map((row) => [text(row.id), row])).values()]
      .filter((row) => text(row.name) === QF_COLLECTION)
    if (matches.length > 1) throw new Error('Duplicate Beautiful Quran collections')
    if (matches.length === 1) return text(matches[0]!.id)
    const created = await this.request('POST', '/v1/collections', { name: QF_COLLECTION })
    if (created.success !== true) throw new Error('Collection creation failed')
    return text(object(created.data).id)
  }

  /** A partial page, cycling cursor or changing count must never become a destructive empty snapshot. */
  async bookmarks(id: string): Promise<Bookmark[]> {
    let count: number | undefined
    const rows = await this.pages(this.path(id) + '?first=20&sortBy=verseKey', (response) => {
      const data = object(response.data)
      const collection = object(data.collection)
      if (text(collection.id) !== id || !Number.isInteger(collection.bookmarksCount) ||
          (collection.bookmarksCount as number) < 0 ||
          (count !== undefined && count !== collection.bookmarksCount)) {
        throw new Error('Collection changed while loading')
      }
      count = collection.bookmarksCount as number
      return array(data.bookmarks)
    })
    const unique = [...new Map(rows.map((row) => [text(row.id), row])).values()]
    if (unique.length !== count) throw new Error('Incomplete QF collection')
    return unique.filter((row) => text(row.type) === 'ayah' && text(row.group) === 'verses_6236')
      .map((row) => {
        const verse = { surahId: row.key as number, ayah: row.verseNumber as number }
        if (!this.valid(verse)) throw new Error('Invalid QF verse')
        return verse
      })
  }

  /** Attach/detach desired membership, never toggle or delete an account-wide bookmark. */
  async write(id: string, edit: BookmarkEdit): Promise<void> {
    if (!this.valid(edit.verse)) throw new Error('Invalid verse')
    const response = await this.request(edit.present ? 'POST' : 'DELETE', this.path(id) + '/bookmarks',
      { key: edit.verse.surahId, type: 'ayah', verseNumber: edit.verse.ayah, mushafId: 4 })
    if (response.success !== true) throw new Error('QF rejected the change')
  }

  private path(id: string) { return '/v1/collections/' + encodeURIComponent(id) }

  private async pages(path: string, rows: (response: Row) => Row[]): Promise<Row[]> {
    const result: Row[] = []
    const cursors = new Set<string>()
    let cursor: string | undefined
    for (let page = 0; page < 512; page++) {
      const response = await this.request('GET', path +
        (cursor ? '&after=' + encodeURIComponent(cursor) : ''))
      if (response.success !== true) throw new Error('QF rejected the request')
      result.push(...rows(response))
      const pagination = object(response.pagination)
      if (pagination.hasNextPage === false) return result
      if (pagination.hasNextPage !== true) throw new Error('Missing QF pagination')
      cursor = text(pagination.endCursor)
      if (cursors.has(cursor)) throw new Error('Repeated QF cursor')
      cursors.add(cursor)
    }
    throw new Error('QF pagination did not finish')
  }
}
