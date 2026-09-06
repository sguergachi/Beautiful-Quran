package com.beautifulquran.data

import java.io.File

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

data class QfSyncState(
    val filter: QfResourceFilter,
    val token: String,
    val updatedAtMs: Long,
    val lastRefreshApiCalls: Long? = null,
)

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

data class QfSnapshot(
    val resource: QfResource,
    val rows: List<QfCacheRow> = emptyList(),
    /** Large production snapshots are spooled to disk instead of occupying the app heap. */
    val file: File? = null,
)

/** Network boundary. Authentication and JSON decoding live behind this interface. */
interface QfContentSyncApi {
    suspend fun sync(request: QfSyncRequest): QfSyncPage
    suspend fun snapshot(relativePath: String): QfSnapshot
}

/** Lets a direct provider adapter report physical HTTP calls to Developer Mode. */
internal interface QfNetworkCallReporter {
    fun setNetworkCallReporter(reporter: () -> Unit)
}

/** Optional coarse progress for providers whose bootstrap has known work units. */
internal interface QfSyncProgressReporter {
    fun setSyncProgressReporter(reporter: (QfSyncProgress) -> Unit)
}

data class QfSyncProgress(val completed: Int, val total: Int) {
    init {
        require(total > 0 && completed in 0..total)
    }

    val fraction: Float get() = completed.toFloat() / total
}

/** Separate from the reader database: QF's cache can be replaced without mixing rows. */
interface QfContentSyncStore {
    fun state(filter: QfResourceFilter): QfSyncState?
    fun rows(
        resource: QfResource,
        recordType: String,
        recordKeyPrefix: String? = null,
    ): List<QfCacheRow>

    /** Deletes all QF content and private sync checkpoints on termination or revocation. */
    fun clear()

    /** Deletes one cached resource without changing its Content Sync checkpoint. */
    fun deleteResource(resource: QfResource)

    /** Applies all data and the final checkpoint in one transaction. */
    fun apply(
        filter: QfResourceFilter,
        changes: List<QfContentChange>,
        snapshots: List<QfSnapshot>,
        nextToken: String,
        nowMs: Long,
        lastRefreshApiCalls: Long?,
        /** Replaces every cached resource inside the same transaction. */
        reset: Boolean = false,
        /** Runs against the uncommitted rows; throwing rolls the transaction back. */
        validate: () -> Unit = {},
    )
}

/** QF asks clients to discard an unusable checkpoint and bootstrap again. */
class QfResyncRequiredException : Exception("QF Content Sync requires a fresh bootstrap")

/** Credentials or content access were revoked; retained QF content must be deleted. */
class QfAccessRevokedException : Exception("QF content access was revoked")

/**
 * Runs one complete Content Sync exchange. A failed page or snapshot never
 * advances the checkpoint; retry therefore safely repeats already-applied rows.
 */
class QfContentSyncer(
    private val api: QfContentSyncApi,
    private val store: QfContentSyncStore,
    private val beforeApply: () -> Unit = {},
    private val validate: (List<QfContentChange>) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(filter: QfResourceFilter, apiCalls: (() -> Long)? = null) {
        val callsBefore = apiCalls?.invoke()
        val initial = store.state(filter)?.let {
            QfSyncRequest.Incremental(filter, it.token)
        } ?: QfSyncRequest.Bootstrap(filter)
        try {
            exchange(filter, initial, callsBefore, apiCalls)
        } catch (_: QfResyncRequiredException) {
            check(initial is QfSyncRequest.Incremental) { "QF rejected a fresh bootstrap" }
            exchange(filter, QfSyncRequest.Bootstrap(filter), callsBefore, apiCalls)
        }
    }

    private suspend fun exchange(
        filter: QfResourceFilter,
        firstRequest: QfSyncRequest,
        callsBefore: Long?,
        apiCalls: (() -> Long)?,
    ) {
        var request = firstRequest
        val changes = mutableListOf<QfContentChange>()
        val snapshots = mutableListOf<QfSnapshot>()
        var finalToken: String? = null
        var pageCount = 0

        try {
            while (true) {
                check(++pageCount <= MAX_SYNC_PAGES) { "Content Sync exceeded page limit" }
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
            beforeApply()
            store.apply(
                filter,
                changes,
                snapshots,
                requireNotNull(finalToken),
                nowMs(),
                callsBefore?.let { apiCalls?.invoke()?.minus(it) },
                reset = firstRequest is QfSyncRequest.Bootstrap,
                validate = { validate(changes) },
            )
        } finally {
            snapshots.forEach { it.file?.delete() }
        }
    }
}

private const val MAX_SYNC_PAGES = 100

/** QF returns relative API paths; accepting absolute URLs would enable host injection. */
internal fun requireRelativeApiPath(path: String) {
    require(path.startsWith("/api/v4/")) { "Expected a QF relative /api/v4/ path" }
    require(!path.startsWith("//")) { "QF path must not name a host" }
}

/** The Terms require a successful next sync and changes applied within seven days. */
internal fun isQfContentFresh(lastSuccessfulSyncMs: Long?, nowMs: Long): Boolean =
    lastSuccessfulSyncMs != null && nowMs - lastSuccessfulSyncMs in 0..QF_MAX_CACHE_AGE_MS

internal const val QF_MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
internal const val QF_REVALIDATE_AFTER_MS = 6L * 24 * 60 * 60 * 1000
