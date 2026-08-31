package com.beautifulquran.data

enum class RuntimeCachePhase { EMPTY, FRESH, REFRESH_DUE, EXPIRED, REFRESHING, ERROR }

data class RuntimeCacheStatus(
    val phase: RuntimeCachePhase,
    val updatedAtMs: Long?,
    val refreshAtMs: Long?,
    val expiresAtMs: Long?,
    val apiCalls: Long,
    val lastError: String?,
    val lastRefreshApiCalls: Long? = null,
)

internal data class RuntimeCacheResource(
    val updatedAtMs: Long? = null,
    val refreshing: Boolean = false,
    val lastError: String? = null,
    val lastRefreshApiCalls: Long? = null,
)

class RuntimeCacheDiagnostics internal constructor(
    val apiCalls: Long = 0,
    internal val resources: Map<Int, RuntimeCacheResource> = emptyMap(),
    internal val requestsSettled: Boolean = false,
    internal val syncProgress: QfSyncProgress? = null,
)
