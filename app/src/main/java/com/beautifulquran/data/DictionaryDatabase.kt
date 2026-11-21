package com.beautifulquran.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Opens the prepackaged English-Wiktionary Arabic dictionary (kaikki extract).
 *
 * Kept apart from [QuranDatabase] and [LexiconDatabase]: it is lemma-keyed,
 * changes on its own schedule, and nobody pays for it until they open a root.
 * Extraction is lazy — first lookup, not startup.
 *
 * The asset ships as `dictionary.sqlite` so aapt compresses it inside the APK.
 */
class DictionaryDatabase(private val context: Context) {

    val db: SQLiteDatabase? by lazy {
        runCatching {
            SQLiteDatabase.openDatabase(
                ensureExtracted().absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        }.getOrNull()
    }

    private fun ensureExtracted(): File =
        extractVersionedAsset(context, ASSET_NAME, DB_FILE_NAME, oldVersionPrefix = "dictionary-v")

    companion object {
        private const val ASSET_NAME = "dictionary.sqlite"

        // Bump whenever data/dictionary.db is rebuilt.
        // `data/dictionary.db.sha256` pins this to the asset it was bumped for.
        internal const val DB_FILE_NAME = "dictionary-v2.db"
    }
}
