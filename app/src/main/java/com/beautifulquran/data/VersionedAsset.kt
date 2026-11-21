package com.beautifulquran.data

import android.content.Context
import java.io.File

/**
 * Installs a packaged database asset as `noBackupFilesDir/[fileName]` and
 * returns it. The versioned [fileName] pins the content (bump it whenever the
 * asset changes; `DatabaseFingerprintTest` enforces this), so an existing copy
 * is reused unless it cannot be the asset ([needsReextract]). The copy lands
 * through a temp file and a rename, and copies left by older versions (names
 * starting with [oldVersionPrefix]) are deleted.
 */
internal fun extractVersionedAsset(
    context: Context,
    assetName: String,
    fileName: String,
    oldVersionPrefix: String,
): File {
    val file = File(context.noBackupFilesDir, fileName)
    if (needsReextract(file.exists(), file.length(), context.assetLength(assetName))) {
        file.delete()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$fileName.tmp")
        context.assets.open(assetName).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        // Fail on the rename rather than on the open: returning a path that
        // does not exist surfaces as an opaque SQLite error two frames later
        // (or, for the lazily opened lexicons, is swallowed into "no entry").
        // `lazy` does not cache a failed init, so a retry is still possible —
        // but only if the cause is legible.
        check(tmp.renameTo(file)) {
            "Could not install $fileName from assets (rename failed)"
        }
        file.parentFile
            ?.listFiles { f -> f.name.startsWith(oldVersionPrefix) && f.name != fileName }
            ?.forEach { it.delete() }
    }
    return file
}

/** Length of a packaged asset when Android can report it without reading it. */
private fun Context.assetLength(assetName: String): Long? = try {
    assets.openFd(assetName).use { fd -> fd.length.takeIf { it > 0 } }
} catch (_: Exception) {
    // Compressed assets expose no fd length; trust the versioned file.
    null
}

/**
 * Whether the versioned copy must be dropped and extracted again.
 *
 * A copy that cannot be the asset it was extracted from is never opened:
 * SQLite happily opens an empty file, and every query then dies with "no such
 * table", so the app crash-loops until its data is cleared. Observed twice as
 * a 0-byte quran-v57.db left behind by an install raced with its own first
 * launch. Length is a sufficient proxy — the versioned name already pins the
 * content (see [QuranDatabase.DB_FILE_NAME]).
 */
internal fun needsReextract(fileExists: Boolean, fileLength: Long, assetLength: Long?): Boolean =
    !fileExists || fileLength == 0L || (assetLength != null && fileLength != assetLength)
