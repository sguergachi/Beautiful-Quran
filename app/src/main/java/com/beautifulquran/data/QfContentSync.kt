package com.beautifulquran.data

/** A QF Content Sync resource filter. It must remain identical across syncs. */
@JvmInline
value class QfResourceFilter(val value: String) {
    init {
        require(value.isNotBlank()) { "QF resource filter cannot be blank" }
    }
}

data class QfResource(val group: String, val id: Long)

data class QfCacheRow(
    val resource: QfResource,
    val recordType: String,
    val recordKey: String,
    /** The API record, preserved verbatim for the feature-specific mapper. */
    val payload: String,
    val updatedAt: String,
)

data class QfSyncState(val filter: QfResourceFilter, val token: String, val updatedAtMs: Long)

sealed interface QfSyncRequest {
    data class Bootstrap(val filter: QfResourceFilter) : QfSyncRequest
    data class Incremental(val filter: QfResourceFilter, val token: String) : QfSyncRequest
    data class NextPage(val relativePath: String) : QfSyncRequest
}

/** Server changes relevant to the local Content Sync cache. */
sealed interface QfContentChange {
    data class Snapshot(val resource: QfResource, val relativePath: String) : QfContentChange
    data class Upsert(val row: QfCacheRow) : QfContentChange
    data class DeleteRow(val resource: QfResource, val recordType: String, val recordKey: String) : QfContentChange
    data class DeleteResource(val resource: QfResource) : QfContentChange
    data object FreshnessMarker : QfContentChange
}

data class QfSyncPage(
    val changes: List<QfContentChange>,
    val nextPagePath: String?,
    /** Present only on the final page of a successful sync. */
    val nextSyncToken: String?,
)

data class QfSnapshot(val resource: QfResource, val rows: List<QfCacheRow>)

/** Network boundary. Authentication and JSON decoding live behind this interface. */
interface QfContentSyncApi {
    suspend fun sync(request: QfSyncRequest): QfSyncPage
    suspend fun snapshot(relativePath: String): QfSnapshot
}

/** Separate from the reader database: QF's cache can be replaced without mixing rows. */
interface QfContentSyncStore {
    fun state(filter: QfResourceFilter): QfSyncState?

    /** Deletes all QF content and private sync checkpoints on termination or revocation. */
    fun clear()

    /** Applies all data and the final checkpoint in one transaction. */
    fun apply(
        filter: QfResourceFilter,
        changes: List<QfContentChange>,
        snapshots: List<QfSnapshot>,
        nextToken: String,
        nowMs: Long,
    )
}

/**
 * Runs one complete Content Sync exchange. A failed page or snapshot never
 * advances the checkpoint; retry therefore safely repeats already-applied rows.
 */
class QfContentSyncer(
    private val api: QfContentSyncApi,
    private val store: QfContentSyncStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(filter: QfResourceFilter) {
        var request: QfSyncRequest = store.state(filter)?.let {
            QfSyncRequest.Incremental(filter, it.token)
        } ?: QfSyncRequest.Bootstrap(filter)
        val changes = mutableListOf<QfContentChange>()
        val snapshots = mutableListOf<QfSnapshot>()
        var finalToken: String? = null

        while (true) {
            val page = api.sync(request)
            changes += page.changes
            page.changes.filterIsInstance<QfContentChange.Snapshot>().forEach { change ->
                requireRelativeApiPath(change.relativePath)
                snapshots += api.snapshot(change.relativePath).also { snapshot ->
                    require(snapshot.resource == change.resource) { "QF snapshot resource mismatch" }
                }
            }
            val next = page.nextPagePath ?: run {
                finalToken = requireNotNull(page.nextSyncToken) { "Final QF sync page has no token" }
                break
            }
            requireRelativeApiPath(next)
            request = QfSyncRequest.NextPage(next)
        }
        store.apply(filter, changes, snapshots, requireNotNull(finalToken), nowMs())
    }
}

/** QF returns relative API paths; accepting absolute URLs would enable host injection. */
internal fun requireRelativeApiPath(path: String) {
    require(path.startsWith("/api/v4/")) { "Expected a QF relative /api/v4/ path" }
    require(!path.startsWith("//")) { "QF path must not name a host" }
}

/** The Terms require a successful next sync and changes applied within seven days. */
internal fun isQfContentFresh(lastSuccessfulSyncMs: Long?, nowMs: Long): Boolean =
    lastSuccessfulSyncMs != null && nowMs - lastSuccessfulSyncMs in 0..QF_MAX_CACHE_AGE_MS

internal const val QF_MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
