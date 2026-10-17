package com.beautifulquran.data.account

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BookmarkSyncTest {
    private val verse = SyncedVerse(2, 255)
    private var journal = BookmarkJournal(collectionId = "owned")

    private class Remote : QfBookmarkRemote {
        val saved = mutableSetOf<SyncedVerse>()
        val writes = mutableListOf<BookmarkEdit>()
        var lostReply = false
        var applyWrite = true
        var onWrite: () -> Unit = {}
        var onRead: () -> Unit = {}
        override suspend fun collection() = "owned"
        override suspend fun bookmarks(collectionId: String): List<SyncedVerse> {
            assertEquals("owned", collectionId)
            onRead()
            return saved.toList()
        }
        override suspend fun write(collectionId: String, edit: BookmarkEdit) {
            assertEquals("owned", collectionId)
            writes += edit
            if (applyWrite) {
                if (edit.present) saved += edit.verse else saved -= edit.verse
            }
            onWrite()
            if (lostReply) {
                lostReply = false
                throw IOException("Connection dropped after server acceptance")
            }
        }
    }

    private suspend fun sync(remote: Remote) =
        syncBookmarks(remote, { journal }, { journal = it })

    @Test fun acceptedAddWithLostReplySurvivesRestartAndDoesNotToggleOnRetry() = runBlocking {
        journal = journal.edit(verse, true)
        val remote = Remote().apply { lostReply = true }
        assertThrows(IOException::class.java) { runBlocking { sync(remote) } }
        journal = Json.decodeFromString(Json.encodeToString(journal))
        assertEquals(listOf(verse), journal.saved)
        assertEquals(1, journal.pending.size)
        sync(remote)
        assertEquals(setOf(verse), remote.saved)
        assertTrue(journal.pending.isEmpty())
        assertEquals(1, remote.writes.size)
    }

    @Test fun offlineRemovalSurvivesRestartAndLostDeleteResponse() = runBlocking {
        journal = journal.copy(saved = listOf(verse)).edit(verse, false)
        journal = Json.decodeFromString(Json.encodeToString(journal))
        val remote = Remote().apply { saved += verse; lostReply = true }
        assertThrows(IOException::class.java) { runBlocking { sync(remote) } }
        assertTrue(journal.saved.isEmpty())
        assertFalse(journal.pending.single().present)
        sync(remote)
        assertTrue(remote.saved.isEmpty())
        assertTrue(journal.pending.isEmpty())
        assertEquals(1, remote.writes.size)
    }

    @Test fun olderAddCannotClearNewerAddOfSameValue() = runBlocking {
        journal = journal.edit(verse, true)
        val remote = Remote().apply {
            onWrite = { journal = journal.edit(verse, false).edit(verse, true) }
        }
        sync(remote)
        assertEquals(3L, journal.pending.single().revision)
        sync(remote)
        assertTrue(journal.pending.isEmpty())
        assertEquals(1, remote.writes.size)
    }

    @Test fun olderAddCannotResurrectNewerRemoval() = runBlocking {
        journal = journal.edit(verse, true)
        val remote = Remote().apply { onWrite = { journal = journal.edit(verse, false) } }
        sync(remote)
        assertTrue(journal.saved.isEmpty())
        assertFalse(journal.pending.single().present)
        remote.onWrite = {}
        sync(remote)
        assertTrue(remote.saved.isEmpty())
        assertTrue(journal.pending.isEmpty())
    }

    @Test fun successWithoutConfirmingMembershipPreservesOutbox() = runBlocking {
        journal = journal.edit(verse, true)
        sync(Remote().apply { applyWrite = false })
        assertEquals(listOf(verse), journal.saved)
        assertEquals(1, journal.pending.size)
    }

    @Test fun failedConfirmingReadNeverAcknowledgesAcceptedMutation() = runBlocking {
        journal = journal.edit(verse, true)
        val remote = Remote().apply {
            onWrite = { onRead = { throw IOException("Incomplete snapshot") } }
        }
        assertThrows(IOException::class.java) { runBlocking { sync(remote) } }
        assertEquals(1, journal.pending.size)
        assertEquals(listOf(verse), journal.saved)
    }

    @Test fun failedPersistenceStopsBeforeUploadingEdits() = runBlocking {
        journal = journal.edit(verse, true)
        val remote = Remote()
        assertThrows(IOException::class.java) { runBlocking {
            syncBookmarks(remote, { journal }, { throw IOException("Disk full") })
        } }
        assertTrue(remote.writes.isEmpty())
        assertEquals(1, journal.pending.size)
    }

    @Test fun accountSwitchDuringReadPreventsOldResultsAndWrites() = runBlocking {
        val old = journal.edit(verse, true)
        journal = old
        var sameAccount = true
        val remote = Remote().apply { onRead = { sameAccount = false } }
        assertThrows(IllegalStateException::class.java) { runBlocking {
            syncBookmarks(remote, { check(sameAccount); journal }, { journal = it })
        } }
        assertEquals(old, journal)
        assertTrue(remote.writes.isEmpty())
    }

    @Test fun erasureSurvivesRestartAndRequiresConfirmedEmptyCollection() = runBlocking {
        journal = journal.copy(deleting = true).edit(SyncedVerse(1, 1), true)
        val remote = Remote().apply { saved += verse; lostReply = true }
        assertThrows(IOException::class.java) { runBlocking { sync(remote) } }
        assertTrue(journal.deleting)
        journal = Json.decodeFromString(Json.encodeToString(journal))
        sync(remote)
        assertFalse(journal.deleting)
        assertTrue(journal.saved.isEmpty())
        assertTrue(journal.pending.isEmpty())
        assertTrue(remote.saved.isEmpty())
        assertTrue(remote.writes.all { !it.present })
    }
}
