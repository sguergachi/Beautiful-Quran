package com.beautifulquran.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Opens the prepackaged, read-only quran.db shipped in assets.
 * The asset is copied to local storage on first use (SQLite can't read
 * directly from a compressed APK entry).
 */
class QuranDatabase(private val context: Context) {

    val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openDatabase(
            ensureExtracted().absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    private fun ensureExtracted(): File {
        val file = File(context.noBackupFilesDir, DB_FILE_NAME)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$DB_FILE_NAME.tmp")
            context.assets.open("quran.db").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            // Fail on the rename rather than on the open: returning a path that
            // does not exist surfaces as an opaque SQLite error two frames
            // later. `lazy` does not cache a failed init, so a retry is still
            // possible — but only if the cause is legible.
            check(tmp.renameTo(file)) {
                "Could not install $DB_FILE_NAME from assets (rename failed)"
            }
            // Clean up databases extracted by older app versions.
            file.parentFile
                ?.listFiles { f -> f.name.startsWith("quran-v") && f.name != DB_FILE_NAME }
                ?.forEach { it.delete() }
        }
        return file
    }

    companion object {
        // Bump the suffix whenever the packaged database changes shape
        // (or content — e.g. a new reciter), so updated installs re-extract.
        private const val DB_FILE_NAME = "quran-v20.db"
    }
}
