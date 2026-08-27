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

    @Volatile
    private var warmed = false

    val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openDatabase(
            ensureExtracted().absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    /** Open, verify, and retain the complete database in SQLite's native page cache. */
    fun warmEntireDatabase() {
        if (warmed) return
        synchronized(this) {
            if (warmed) return
            val database = db
            val cacheKiB = quranDatabaseCacheKiB(File(database.path).length())
            database.execPerConnectionSQL("PRAGMA cache_size = -$cacheKiB", emptyArray())
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Bundled Quran database failed its startup check"
                }
            }
            warmed = true
        }
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
        // `data/quran.db.sha256` pins this to the asset it was bumped for;
        // DatabaseFingerprintTest fails if the two drift apart.
        internal const val DB_FILE_NAME = "quran-v56.db"
    }
}

/** Database bytes plus 1 MiB for SQLite's schema and working pages. */
internal fun quranDatabaseCacheKiB(fileBytes: Long): Long {
    require(fileBytes >= 0)
    return (fileBytes + 1023L) / 1024L + 1024L
}
