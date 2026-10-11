import { describe, expect, it } from 'vitest'
import type { Bookmark } from '../data/settings'
import { editJournal, emptyJournal, sameVerse, type BookmarkEdit, type BookmarkJournal } from './bookmarkJournal'
import { syncBookmarks, type BookmarkRemote } from './bookmarkSync'

const verse = { surahId: 2, ayah: 255 }
class Remote implements BookmarkRemote {
  saved: Bookmark[] = []
  writes: BookmarkEdit[] = []
  lostReply = false
  applyWrite = true
  onWrite = () => {}
  onRead = () => {}
  async collection() { return 'owned' }
  async bookmarks(id: string) {
    expect(id).toBe('owned')
    this.onRead()
    return [...this.saved]
  }
  async write(id: string, edit: BookmarkEdit) {
    expect(id).toBe('owned')
    this.writes.push(edit)
    if (this.applyWrite) {
      this.saved = this.saved.filter((v) => !sameVerse(v, edit.verse))
      if (edit.present) this.saved.push(edit.verse)
    }
    this.onWrite()
    if (this.lostReply) { this.lostReply = false; throw new Error('Lost accepted reply') }
  }
}
function library() {
  let value = { ...emptyJournal(), collectionId: 'owned' }
  return {
    read: () => value,
    save: (journal: BookmarkJournal) => { value = journal },
    edit: (present: boolean) => { value = editJournal(value, verse, present) },
    restart: () => { value = JSON.parse(JSON.stringify(value)) as BookmarkJournal },
  }
}

describe('durable QF bookmark synchronization', () => {
  it('recovers a lost accepted add after restart without toggling or duplicating it', async () => {
    const local = library()
    local.edit(true)
    const remote = new Remote()
    remote.lostReply = true
    await expect(syncBookmarks(remote, local.read, local.save)).rejects.toThrow()
    local.restart()
    expect(local.read().pending).toHaveLength(1)
    await syncBookmarks(remote, local.read, local.save)
    expect(local.read().saved).toEqual([verse])
    expect(local.read().pending).toEqual([])
    expect(remote.writes).toHaveLength(1)
  })
  it('keeps an offline removal through restart and a lost delete reply', async () => {
    const local = library()
    local.edit(false)
    local.restart()
    const remote = new Remote()
    remote.saved = [verse]
    remote.lostReply = true
    await expect(syncBookmarks(remote, local.read, local.save)).rejects.toThrow()
    expect(local.read().saved).toEqual([])
    expect(local.read().pending[0]?.present).toBe(false)
    await syncBookmarks(remote, local.read, local.save)
    expect(local.read().pending).toEqual([])
    expect(remote.saved).toEqual([])
    expect(remote.writes).toHaveLength(1)
  })
  it('never clears a newer edit of the same value with an older reply', async () => {
    const local = library()
    local.edit(true)
    const remote = new Remote()
    remote.onWrite = () => { local.edit(false); local.edit(true) }
    await syncBookmarks(remote, local.read, local.save)
    expect(local.read().pending[0]?.revision).toBe(3)
    await syncBookmarks(remote, local.read, local.save)
    expect(local.read().pending).toEqual([])
    expect(remote.writes).toHaveLength(1)
  })
  it('does not resurrect a removal made while an earlier add is in flight', async () => {
    const local = library()
    local.edit(true)
    const remote = new Remote()
    remote.onWrite = () => local.edit(false)
    await syncBookmarks(remote, local.read, local.save)
    expect(local.read().saved).toEqual([])
    expect(local.read().pending[0]?.present).toBe(false)
    remote.onWrite = () => {}
    await syncBookmarks(remote, local.read, local.save)
    expect(remote.saved).toEqual([])
    expect(local.read().pending).toEqual([])
  })
  it('requires a complete confirming read after a successful mutation', async () => {
    const local = library()
    local.edit(true)
    const remote = new Remote()
    remote.applyWrite = false
    await syncBookmarks(remote, local.read, local.save)
    expect(local.read().saved).toEqual([verse])
    expect(local.read().pending).toHaveLength(1)
    remote.onWrite = () => { remote.onRead = () => { throw new Error('Partial read') } }
    await expect(syncBookmarks(remote, local.read, local.save)).rejects.toThrow()
    expect(local.read().pending).toHaveLength(1)
  })
  it('does not upload when durable local persistence fails', async () => {
    const local = library()
    local.edit(true)
    const remote = new Remote()
    await expect(syncBookmarks(remote, local.read, () => { throw new Error('Quota full') })).rejects.toThrow()
    expect(remote.writes).toEqual([])
    expect(local.read().pending).toHaveLength(1)
  })
  it('drops old-account results before any write after switching accounts', async () => {
    const local = library()
    local.edit(true)
    const old = local.read()
    let sameAccount = true
    const remote = new Remote()
    remote.onRead = () => { sameAccount = false }
    await expect(syncBookmarks(remote, () => {
      if (!sameAccount) throw new Error('Account changed')
      return local.read()
    }, local.save)).rejects.toThrow()
    expect(local.read()).toEqual(old)
    expect(remote.writes).toEqual([])
  })
  it('persists erasure until verified empty, including unsent local additions', async () => {
    const local = library()
    local.edit(true)
    local.save({ ...local.read(), deleting: true })
    const remote = new Remote()
    remote.saved = [{ surahId: 1, ayah: 1 }]
    remote.lostReply = true
    await expect(syncBookmarks(remote, local.read, local.save)).rejects.toThrow()
    local.restart()
    expect(local.read().deleting).toBe(true)
    await syncBookmarks(remote, local.read, local.save)
    expect(local.read().deleting).toBe(false)
    expect(local.read().pending).toEqual([])
    expect(local.read().saved).toEqual([])
    expect(remote.saved).toEqual([])
    expect(remote.writes.every((edit) => !edit.present)).toBe(true)
  })
})
