package com.beautifulquran.data.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.beautifulquran.data.Bookmark
import com.beautifulquran.data.BookmarkRepository
import com.beautifulquran.data.QuranRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

data class QfAccountStatus(
    val available: Boolean,
    val environment: String,
    val connected: Boolean = false,
    val pending: Int = 0,
    val busy: Boolean = false,
    val deleting: Boolean = false,
    val lastSyncedAt: Long = 0,
    val message: String = "",
)

/** Optional public PKCE login and a single collection. All reader state stays on the local read path. */
class QfAccount(
    context: Context,
    private val bookmarks: BookmarkRepository,
    private val repository: QuranRepository,
    private val scope: CoroutineScope,
) {
    val config = QfAccountConfig.load(context)
    private val store = QfAccountStore(context)
    private val service by lazy { AuthorizationService(context) }
    private val refreshLock = Mutex()
    private var record = QfAccountRecord()
    private var generation = 0L
    private var syncJob: Job? = null
    private var retryRequested = false
    private val _status = MutableStateFlow(QfAccountStatus(config.ready, config.environment))
    val status: StateFlow<QfAccountStatus> = _status

    init {
        try {
            record = store.read()
            if (!config.ready || record.account?.partition?.let { it != config.partition } == true) {
                val hadAccount = record.account != null || record.login != null
                record = QfAccountRecord()
                if (hadAccount) store.clear()
            }
            publish()
        } catch (_: Exception) {
            _status.value = _status.value.copy(message =
                "Account storage could not be opened. Your device bookmarks are available.")
        }
    }

    /** Hosted login uses an external browser. PKCE, state and nonce are supplied by AppAuth. */
    suspend fun signInIntent(): Intent {
        check(config.ready && !status.value.connected && !status.value.busy)
        val configuration = suspendCancellableCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer)) { value, error ->
                if (continuation.isActive) {
                    if (value != null) continuation.resume(value)
                    else continuation.resumeWithException(error ?: Exception("QF discovery failed"))
                }
            }
        }
        val request = AuthorizationRequest.Builder(configuration, config.androidClientId,
            ResponseTypeValues.CODE, Uri.parse(QfAccountConfig.REDIRECT))
            .setScopes(config.scopes)
            .build()
        synchronized(this) {
            save(record.copy(login = PendingQfLogin(config.partition,
                request.jsonSerializeString(), System.currentTimeMillis())))
            _status.value = _status.value.copy(busy = true, message = "Waiting for QF sign-in…")
        }
        return service.getAuthorizationRequestIntent(request)
    }

    /** Validate the saved transaction before exchanging, and obtain identity from QF's HTTPS userinfo. */
    suspend fun completeSignIn(response: AuthorizationResponse) {
        val login = synchronized(this) { record.login } ?: error("No pending sign-in")
        check(login.partition == config.partition &&
            System.currentTimeMillis() - login.startedAt in 0..600_000)
        val expected = AuthorizationRequest.jsonDeserialize(login.request)
        check(response.state == expected.state && response.request.state == expected.state &&
            response.request.nonce == expected.nonce &&
            response.request.codeVerifier == expected.codeVerifier &&
            response.request.clientId == expected.clientId &&
            response.request.redirectUri == expected.redirectUri &&
            response.request.configuration.tokenEndpoint == expected.configuration.tokenEndpoint &&
            response.request.configuration.authorizationEndpoint == expected.configuration.authorizationEndpoint)
        val auth = AuthState(response.request.configuration).apply { update(response, null) }
        val token = suspendCancellableCoroutine { continuation ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { result, error ->
                auth.update(result, error)
                if (continuation.isActive) {
                    if (result != null) continuation.resume(result)
                    else continuation.resumeWithException(error ?: Exception("QF token exchange failed"))
                }
            }
        }
        check(!token.accessToken.isNullOrBlank() && !token.idToken.isNullOrBlank())
        val subject = qfJsonRequest(config.issuer + "/userinfo", "GET",
            mapOf("Authorization" to "Bearer " + token.accessToken))
            .getValue("sub").jsonPrimitive.content
        check(subject.isNotBlank() && subject.length <= 1024)
        synchronized(this) {
            check(record.login == login)
            generation++
            save(QfAccountRecord(account = SavedQfAccount(config.partition, subject,
                auth.jsonSerializeString(), System.currentTimeMillis())))
            publish(message = "Connected. Loading your QF bookmarks…")
        }
        retry()
    }

    @Synchronized
    fun signInFailed() {
        runCatching { save(record.copy(login = null)) }
        publish(message = "Sign-in did not finish. Your device bookmarks are unchanged.")
    }

    /** Adding the guest library is a separate, explicit action after account consent. */
    @Synchronized
    fun importDeviceBookmarks() {
        val saved = change { account ->
            if (account.journal.deleting) account else account.copy(journal =
                bookmarks.deviceBookmarks().fold(account.journal) { journal, verse ->
                    journal.edit(SyncedVerse(verse.surahId, verse.ayah), true)
                })
        }
        if (saved) retry()
    }

    /** Persist the erasure request, including offline, and verify the collection before declaring success. */
    @Synchronized
    fun eraseSyncedBookmarks() {
        if (change { it.copy(journal = it.journal.copy(deleting = true)) }) retry()
    }

    /** Stop sync immediately, purge the local copy, then attempt public token revocation. */
    @Synchronized
    fun disconnect() {
        val oldAuth = record.account?.auth?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
        generation++
        syncJob?.cancel()
        syncJob = null
        retryRequested = false
        record = QfAccountRecord()
        val cleared = runCatching { store.clear() }.isSuccess
        val epoch = generation
        publish(busy = oldAuth != null, message = when {
            !cleared -> "Sync stopped. Clear this app's storage to finish removing the local QF copy."
            oldAuth != null -> "Local QF copy cleared. Revoking this device's QF tokens…"
            else -> "Disconnected from this device. Your QF collection is kept."
        })
        if (oldAuth != null) scope.launch {
            val tokens = listOfNotNull(
                oldAuth.refreshToken?.let { it to "refresh_token" },
                oldAuth.accessToken?.let { it to "access_token" },
            )
            val revoked = tokens.isNotEmpty() && tokens.map { (token, hint) ->
                qfRevokeToken(config.issuer, config.androidClientId, token, hint)
            }.all { it }
            synchronized(this@QfAccount) {
                if (epoch == generation) publish(message = if (!cleared) status.value.message
                    else if (revoked) "Disconnected. QF tokens revoked; your QF collection is kept."
                    else "Local QF copy cleared. Token revocation could not be confirmed; remove app access in QF.")
            }
        }
    }

    fun exportBookmarks(): String = JsonObject(mapOf(
        "format" to JsonPrimitive("beautiful-quran/bookmarks-v1"),
        "scope" to JsonPrimitive(if (status.value.connected) "qf" else "device"),
        "bookmarks" to JsonArray(bookmarks.bookmarks.value.map { verse -> JsonObject(mapOf(
            "surahId" to JsonPrimitive(verse.surahId), "ayah" to JsonPrimitive(verse.ayah),
        )) }),
    )).toString()

    @Synchronized
    fun retry() {
        if (record.account == null || !config.ready) return
        if (syncJob?.isActive == true) {
            retryRequested = true
            return
        }
        val epoch = generation
        retryRequested = false
        publish(busy = true, message = "Syncing bookmarks…")
        syncJob = scope.launch {
            delay(350)
            var succeeded = false
            try {
                sync(epoch)
                succeeded = true
            } catch (_: CancellationException) {
                // An account change invalidates every outstanding result.
            } catch (error: Exception) {
                synchronized(this@QfAccount) {
                    if (epoch == generation) publish(message = when (error) {
                        is QfHttpError -> when (error.status) {
                            401 -> "QF sign-in has expired. Reconnect to continue."
                            403 -> "QF has not granted bookmark access to this app yet."
                            404 -> "The QF collection is unavailable. Check it in QF, then retry."
                            else -> "QF could not sync. Your edits are saved here for retry."
                        }
                        else -> "Sync did not finish. Your edits are saved here; reconnect and retry."
                    })
                }
            } finally {
                synchronized(this@QfAccount) {
                    if (epoch == generation) {
                        syncJob = null
                        publish(busy = false, message = if (succeeded) {
                            if (record.account?.journal?.deleting == true)
                                "Removal is waiting for QF confirmation. Retry to finish."
                            else if (record.account?.journal?.pending?.isNotEmpty() == true)
                                "New edits are waiting to sync." else "Bookmarks are up to date."
                        } else status.value.message)
                        if (retryRequested && succeeded) retry()
                    }
                }
            }
        }
    }

    private suspend fun sync(epoch: Long) {
        val counts = repository.surahs().associate { it.id to it.ayahCount }
        val api = QfBookmarkApi(
            request = { method, path, body ->
                val token = freshToken(epoch)
                try {
                    qfJsonRequest(config.apiBase + path, method, mapOf(
                        "x-auth-token" to token, "x-client-id" to config.androidClientId), body)
                } catch (error: QfHttpError) {
                    if (error.status != 401) throw error
                    val renewed = freshToken(epoch, force = true)
                    qfJsonRequest(config.apiBase + path, method, mapOf(
                        "x-auth-token" to renewed, "x-client-id" to config.androidClientId), body)
                }
            },
            valid = { it.ayah in 1..(counts[it.surahId] ?: 0) },
        )
        syncBookmarks(api, read = { account(epoch).journal },
            save = { journal -> update(epoch) { it.copy(journal = journal) } })
        update(epoch) { it.copy(lastSyncedAt = System.currentTimeMillis()) }
    }

    private suspend fun freshToken(epoch: Long, force: Boolean = false): String = refreshLock.withLock {
        val auth = AuthState.jsonDeserialize(account(epoch).auth)
        if (force) auth.setNeedsTokenRefresh(true)
        val token = suspendCancellableCoroutine { continuation ->
            auth.performActionWithFreshTokens(service) { access, _, error ->
                if (continuation.isActive) {
                    if (access != null) continuation.resume(access)
                    else continuation.resumeWithException(error ?: Exception("QF session expired"))
                }
            }
        }
        update(epoch) { it.copy(auth = auth.jsonSerializeString()) }
        token
    }

    @Synchronized
    private fun account(epoch: Long): SavedQfAccount {
        if (epoch != generation) throw CancellationException("Account changed")
        return record.account ?: throw CancellationException("Disconnected")
    }

    @Synchronized
    private fun update(epoch: Long, transform: (SavedQfAccount) -> SavedQfAccount) {
        val next = transform(account(epoch))
        save(record.copy(account = next))
        publish(busy = status.value.busy)
    }

    private fun change(transform: (SavedQfAccount) -> SavedQfAccount): Boolean {
        val current = record.account ?: return false
        return try {
            save(record.copy(account = transform(current)))
            publish(busy = status.value.busy)
            true
        } catch (_: Exception) {
            publish(message = "This device could not save the change. Free some storage and retry.")
            false
        }
    }

    private fun save(next: QfAccountRecord) {
        store.write(next)
        record = next
    }

    private fun publish(busy: Boolean = false, message: String = status.value.message) {
        val account = record.account
        if (account == null) bookmarks.showDeviceBookmarks()
        else {
            val epoch = generation
            bookmarks.showAccountBookmarks(account.journal.saved.map { Bookmark(it.surahId, it.ayah) }) {
                verse, present ->
                synchronized(this) {
                    if (epoch != generation || record.account?.journal?.deleting != false) false
                    else {
                        val saved = change { it.copy(journal = it.journal.edit(
                            SyncedVerse(verse.surahId, verse.ayah), present)) }
                        if (saved) retry()
                        saved
                    }
                }
            }
        }
        _status.value = QfAccountStatus(config.ready, config.environment, account != null,
            account?.journal?.pending?.size ?: 0, busy, account?.journal?.deleting ?: false,
            account?.lastSyncedAt ?: 0, message)
    }
}
