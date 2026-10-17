package com.beautifulquran.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SavedQfAccount(
    val partition: String,
    val subject: String,
    val auth: String,
    val consentedAt: Long,
    val journal: BookmarkJournal = BookmarkJournal(),
    val lastSyncedAt: Long = 0,
)

@Serializable
internal data class PendingQfLogin(val partition: String, val request: String, val startedAt: Long)

@Serializable
internal data class QfAccountRecord(
    val account: SavedQfAccount? = null,
    val login: PendingQfLogin? = null,
)

/** AES-GCM under AndroidKeyStore; tokens, account cache and PKCE state never enter backup. */
internal class QfAccountStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "qf-account"))
    private val alias = "beautiful-quran-qf-account"

    fun read(): QfAccountRecord {
        if (!file.baseFile.exists()) return QfAccountRecord()
        val bytes = file.readFully()
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return Json.decodeFromString(cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            .toString(Charsets.UTF_8))
    }

    fun write(record: QfAccountRecord) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + cipher.doFinal(Json.encodeToString(record).toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    /** Remove the key as well as the file so previous encrypted versions are no longer decryptable. */
    fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        check(!file.baseFile.exists()) { "Account cache could not be removed" }
    }

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
