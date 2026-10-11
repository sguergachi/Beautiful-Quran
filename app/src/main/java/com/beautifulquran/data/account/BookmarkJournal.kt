package com.beautifulquran.data.account

import kotlinx.serialization.Serializable

/** Account bookmarks deliberately contain verse references, never notes or playback state. */
@Serializable
data class SyncedVerse(val surahId: Int, val ayah: Int)

/** A desired collection membership. Its revision prevents a stale reply acknowledging a newer edit. */
@Serializable
data class BookmarkEdit(val verse: SyncedVerse, val present: Boolean, val revision: Long)

/** Persist the visible bookmarks and their outbox together before publishing either to the reader. */
@Serializable
data class BookmarkJournal(
    val collectionId: String = "",
    val saved: List<SyncedVerse> = emptyList(),
    val pending: List<BookmarkEdit> = emptyList(),
    val revision: Long = 0,
    val deleting: Boolean = false,
) {
    fun edit(verse: SyncedVerse, present: Boolean): BookmarkJournal {
        val nextRevision = revision + 1
        return copy(
            saved = membership(saved, verse, present),
            pending = pending.filterNot { it.verse == verse } +
                BookmarkEdit(verse, present, nextRevision),
            revision = nextRevision,
        )
    }

    /** A complete remote snapshot may replace the base, but never an unacknowledged local edit. */
    fun merge(remote: List<SyncedVerse>): BookmarkJournal = copy(
        saved = pending.fold(remote.distinct().sortedWith(order)) { verses, edit ->
            membership(verses, edit.verse, edit.present)
        },
    )

    fun acknowledge(edit: BookmarkEdit): BookmarkJournal = copy(
        pending = pending.filterNot { it == edit },
    )

    companion object {
        private val order = compareBy<SyncedVerse>({ it.surahId }, { it.ayah })
        private fun membership(verses: List<SyncedVerse>, verse: SyncedVerse, present: Boolean) =
            (verses.filterNot { it == verse } + if (present) listOf(verse) else emptyList())
                .sortedWith(order)
    }
}
