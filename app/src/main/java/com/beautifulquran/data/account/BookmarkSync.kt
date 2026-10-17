package com.beautifulquran.data.account

/** A collection snapshot is complete or throws; no partial result can mean a remote deletion. */
interface QfBookmarkRemote {
    suspend fun collection(): String
    suspend fun bookmarks(collectionId: String): List<SyncedVerse>
    suspend fun write(collectionId: String, edit: BookmarkEdit)
}

/** Save before sending, and acknowledge only a complete confirming read of the desired membership. */
suspend fun syncBookmarks(
    api: QfBookmarkRemote,
    read: () -> BookmarkJournal,
    save: (BookmarkJournal) -> Unit,
) {
    if (read().collectionId.isEmpty()) {
        val id = api.collection()
        save(read().copy(collectionId = id))
    }
    val id = read().collectionId
    val remote = api.bookmarks(id)
    var journal = read().merge(remote)
    if (journal.deleting) {
        (remote + journal.saved).distinct().forEach { journal = journal.edit(it, false) }
    }
    save(journal)
    val edits = journal.pending
    for (edit in edits) {
        if (read().pending.none { it == edit }) continue
        if ((edit.verse in remote) != edit.present) api.write(id, edit)
    }
    val verified = api.bookmarks(id)
    journal = read()
    for (edit in edits) {
        if ((edit.verse in verified) == edit.present) journal = journal.acknowledge(edit)
    }
    journal = journal.merge(verified)
    save(journal.copy(deleting = journal.deleting &&
        (verified.isNotEmpty() || journal.pending.isNotEmpty())))
}
