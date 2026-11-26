package com.beautifulquran.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Cache-dir handoff for share exports: images, and Tarjīʿ Lab samples.
 * Plain-text shares (verse text) never touch this.
 */
object ShareFiles {

    const val AUTHORITY_SUFFIX = ".share"

    fun authority(context: Context): String =
        context.packageName + AUTHORITY_SUFFIX

    fun shareDir(context: Context): File =
        File(context.cacheDir, "share").also { it.mkdirs() }

    /**
     * Writes [bitmap] as PNG, returns a content:// URI for ACTION_SEND.
     * Callers should recycle the bitmap after this returns.
     */
    fun writePng(context: Context, bitmap: Bitmap, namePrefix: String = "verse"): Uri {
        cleanup(context, keepNewest = 4)
        val file = File(shareDir(context), "${namePrefix}-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("PNG compress failed")
            }
        }
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /** Writes [text] under its own [fileName] so the receiver keeps the name. */
    fun writeText(context: Context, fileName: String, text: String): Uri {
        cleanup(context, keepNewest = 4)
        val file = File(shareDir(context), fileName)
        file.writeText(text)
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /** Drop old exports so cacheDir/share/ does not grow without bound. */
    fun cleanup(context: Context, keepNewest: Int = 4) {
        val dir = shareDir(context)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(keepNewest.coerceAtLeast(0)).forEach { it.delete() }
    }
}
