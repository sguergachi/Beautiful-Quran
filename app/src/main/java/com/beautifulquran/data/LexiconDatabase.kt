package com.beautifulquran.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Opens the prepackaged Lane's Lexicon database.
 *
 * Kept apart from [QuranDatabase] on purpose: the lexicon is an order of
 * magnitude rarer to change than the Quran asset (which is rebuilt whenever
 * timings move), and nobody pays for it until they open a root. Extraction is
 * therefore lazy — it happens on the first lookup, not at startup.
 *
 * The asset ships as `lexicon.sqlite` rather than `.db` so aapt compresses it
 * inside the APK (`noCompress += "db"` would store 20 MB raw); AssetManager
 * inflates it transparently on open.
 */
class LexiconDatabase(private val context: Context) {

    val db: SQLiteDatabase? by lazy {
        runCatching {
            SQLiteDatabase.openDatabase(
                ensureExtracted().absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        }.getOrNull()
    }

    private fun ensureExtracted(): File {
        val file = File(context.noBackupFilesDir, DB_FILE_NAME)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$DB_FILE_NAME.tmp")
            context.assets.open(ASSET_NAME).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            // Fail on the rename rather than on the open, as QuranDatabase does
            // — here the failure is swallowed into "no entry", so a legible
            // message is the only trace left.
            check(tmp.renameTo(file)) {
                "Could not install $DB_FILE_NAME from assets (rename failed)"
            }
            // Clean up lexicons extracted by older app versions.
            file.parentFile
                ?.listFiles { f -> f.name.startsWith("lexicon-v") && f.name != DB_FILE_NAME }
                ?.forEach { it.delete() }
        }
        return file
    }

    companion object {
        private const val ASSET_NAME = "lexicon.sqlite"

        // Bump whenever data/lexicon.db is rebuilt, so updated installs
        // re-extract instead of keeping the stale cached copy.
        // `data/lexicon.db.sha256` pins this to the asset it was bumped for;
        // DatabaseFingerprintTest fails if the two drift apart.
        internal const val DB_FILE_NAME = "lexicon-v2.db"
    }
}
