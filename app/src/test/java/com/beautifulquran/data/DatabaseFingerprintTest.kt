package com.beautifulquran.data

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards invariant #1: a change to a packaged database must come with a
 * version bump on the constant that keys its extracted copy.
 *
 * Each asset is cached under its `DB_FILE_NAME`, so a content change without a
 * bump leaves every existing install on the stale file — silently, and only for
 * users who already had the app. Commit 1e1128df shipped that exact bug for
 * `quran.db`. Pinning the digest turns the omission into a failing test.
 */
class DatabaseFingerprintTest {

    /** A committed database asset and the constant that versions it. */
    private data class Asset(val name: String, val version: String)

    @Test
    fun `packaged databases match their recorded fingerprints`() {
        listOf(
            Asset("quran.db", QuranDatabase.DB_FILE_NAME),
            Asset("lexicon.db", LexiconDatabase.DB_FILE_NAME),
        ).forEach(::assertFingerprintMatches)
    }

    private fun assertFingerprintMatches(asset: Asset) {
        val recorded = Properties().apply {
            repoFile("data/${asset.name}.sha256").inputStream().use { load(it) }
        }

        assertEquals(
            "data/${asset.name}.sha256 records a version that is not the one the " +
                "app extracts. Bump both together.",
            asset.version,
            recorded.getProperty("version"),
        )

        assertEquals(
            "data/${asset.name} no longer matches its recorded digest. Regenerating " +
                "it means bumping its DB_FILE_NAME (currently ${asset.version}) and " +
                "the `version` + `sha256` lines in data/${asset.name}.sha256, or " +
                "existing installs keep the stale copy.",
            recorded.getProperty("sha256"),
            sha256Of(repoFile("data/${asset.name}")),
        )
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Resolves a repo-root-relative path. Unit tests inherit Gradle's working
     * directory, which differs between the CLI, the IDE and CI, so walk up to
     * the checkout instead of assuming one.
     */
    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not find $path above ${File("").absolutePath}")
    }
}
