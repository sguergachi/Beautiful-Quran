package com.beautifulquran.data.account

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Public client configuration is shared with web. Content API credentials are never used here. */
@Serializable
data class QfAccountConfig(
    val enabled: Boolean = false,
    val environment: String = "prelive",
    val androidClientId: String = "",
    val webClientId: String = "",
    val webOrigins: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
    val consentVersion: String = "",
) {
    val issuer: String get() = when (environment) {
        "production" -> "https://oauth2.quran.foundation"
        "prelive" -> "https://prelive-oauth2.quran.foundation"
        else -> error("Unknown QF account environment")
    }
    val apiBase: String get() = when (environment) {
        "production" -> "https://apis.quran.foundation/auth"
        "prelive" -> "https://apis-prelive.quran.foundation/auth"
        else -> error("Unknown QF account environment")
    }
    val ready: Boolean get() = enabled && environment in listOf("prelive", "production") &&
        androidClientId.isNotBlank() && "openid" in scopes && consentVersion.isNotBlank()
    val partition: String get() = issuer + "|" + androidClientId + "|" + consentVersion +
        "|" + scopes.sorted().joinToString(" ")

    companion object {
        const val REDIRECT = "com.beautifulquran:/oauth2redirect/qf"
        const val COLLECTION = "Beautiful Quran"
        fun load(context: Context): QfAccountConfig = context.assets.open("qf_account.json")
            .bufferedReader().use { Json.decodeFromString(it.readText()) }
    }
}
