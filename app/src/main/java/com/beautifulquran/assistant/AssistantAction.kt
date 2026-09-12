package com.beautifulquran.assistant

import android.app.SearchManager
import android.content.Intent
import android.net.Uri

/**
 * Actions the reader can fulfill from Android intents, deep links, launcher
 * shortcuts, system search, media voice search, or a Gemini AppFunction.
 */
sealed class AssistantAction {
    /**
     * Open the reader at [surahId]:[ayah]. When [play] is true, start recitation
     * there (for example, an Android media play-from-search request).
     */
    data class OpenVerse(
        val surahId: Int,
        val ayah: Int,
        val play: Boolean = false,
    ) : AssistantAction()
    data object OpenBookmarks : AssistantAction()
    data object OpenChapters : AssistantAction()
    data object OpenSettings : AssistantAction()
    data class Search(val query: String) : AssistantAction()

    /** Reopen the last-read verse; [play] resumes recitation there. */
    data class ContinueReading(val play: Boolean = false) : AssistantAction()
    data object SaveBookmark : AssistantAction()
}

/**
 * Intent extras, schemes, and feature ids shared with `shortcuts.xml`.
 */
object AssistantIntents {
    const val SCHEME = "beautifulquran"
    const val APP_NAME = "beautiful quran"

    const val ACTION_CONTINUE = "com.beautifulquran.action.CONTINUE"
    const val ACTION_BOOKMARKS = "com.beautifulquran.action.OPEN_BOOKMARKS"
    const val ACTION_SAVE_BOOKMARK = "com.beautifulquran.action.SAVE_BOOKMARK"
    const val ACTION_OPEN_VERSE = "com.beautifulquran.action.OPEN_VERSE"

    const val EXTRA_FEATURE = "feature"
    const val EXTRA_QUERY = "q"
    const val EXTRA_SURAH = "surah"
    const val EXTRA_AYAH = "ayah"

    const val FEATURE_CONTINUE = "continue"
    const val FEATURE_BOOKMARKS = "bookmarks"
    const val FEATURE_SAVE_BOOKMARK = "save_bookmark"
    const val FEATURE_VERSE = "verse"

    private val schemePrefix = "$SCHEME://"
    private val pathVerse = Regex("""^verse(?:/(\d{1,3})(?:/(\d{1,3}))?)?$""", RegexOption.IGNORE_CASE)

    /** `2:255`, `2 255`, `surah 2 ayah 255` — separator required so "18" ≠ 1:8. */
    private val spokenVerse = Regex(
        """^(?:(?:surah|sura|chapter|ch\.?)\s+)?(\d{1,3})(?:\s*:\s*|\s+(?:ayah|aya|verse)\s+|\s*,\s*|\s+)(\d{1,3})$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Natural chapter opens: "open chapter 2", "go to surah 18", "chapter 2",
     * "open surah 2 ayah 255", including trailing "on Beautiful Quran".
     */
    private val openChapter = Regex(
        """^(?:(?:please\s+)?(?:open|go to|show|read|jump to)\s+)?(?:chapter|surah|sura|ch\.?)\s+(\d{1,3})(?:\s+(?:ayah|aya|verse)\s+(\d{1,3}))?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Play / recite prefix supplied by Android media search or App Actions. */
    private val playPrefix = Regex(
        """^(?:please\s+)?(?:play|recite|listen to)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "open 2", "go to 2" → chapter opening. */
    private val openNumber = Regex(
        """^(?:open|go to|show|read|jump to)\s+(\d{1,3})$""",
        RegexOption.IGNORE_CASE,
    )

    /** Assistant often appends the app name; strip it before matching. */
    private val appNameSuffix = Regex(
        """\s+(?:on|in|using|with|via|from)\s+(?:the\s+)?(?:app\s+)?beautiful\s+quran(?:\s+app)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    const val ACTION_MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

    fun parse(intent: Intent?): AssistantAction? {
        if (intent == null) return null
        if (intent.action == ACTION_MEDIA_PLAY_FROM_SEARCH) {
            return parsePlaySearch(intent.getStringExtra(SearchManager.QUERY))
        }
        parseAction(intent.action)?.let { return it }
        intent.dataString?.let { parseDeepLink(it) }?.let { return it }
        parseFeature(intent.getStringExtra(EXTRA_FEATURE))?.let { return it }

        // Free-form candidates: system SEARCH and custom extras.
        sequenceOf(
            intent.getStringExtra(EXTRA_QUERY),
            intent.getStringExtra(SearchManager.QUERY),
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra("query"),
            intent.getStringExtra("name"),
        ).mapNotNull { it?.takeIf(String::isNotBlank) }
            .forEach { candidate ->
                parseSpokenCommand(candidate)?.let { return it }
            }

        // Custom intent / url-template parameters (open chapter $surah).
        val surah = intent.getIntExtra(EXTRA_SURAH, 0)
            .takeIf { it > 0 }
            ?: intent.getStringExtra(EXTRA_SURAH)?.toIntOrNull()
            ?: 0
        if (surah in 1..114) {
            val ayah = intent.getIntExtra(EXTRA_AYAH, 0)
                .takeIf { it > 0 }
                ?: intent.getStringExtra(EXTRA_AYAH)?.toIntOrNull()
                ?: 1
            return AssistantAction.OpenVerse(surah, ayah.coerceAtLeast(1))
        }
        return null
    }

    fun parseData(uri: Uri?): AssistantAction? = uri?.toString()?.let(::parseDeepLink)

    fun parseAction(action: String?): AssistantAction? = when (action) {
        ACTION_CONTINUE -> AssistantAction.ContinueReading()
        ACTION_BOOKMARKS -> AssistantAction.OpenBookmarks
        ACTION_SAVE_BOOKMARK -> AssistantAction.SaveBookmark
        Intent.ACTION_SEARCH -> null // query in extras
        ACTION_OPEN_VERSE -> null
        else -> null
    }

    /**
     * Deep links: `beautifulquran://continue`, `…/bookmarks`, `…/bookmark/save`,
     * `…/verse/2/255`, `…/verse?surah=2&ayah=255`.
     */
    fun parseDeepLink(uriString: String): AssistantAction? {
        val raw = uriString.trim()
        if (!raw.startsWith(schemePrefix, ignoreCase = true)) return null
        val afterScheme = raw.substring(schemePrefix.length)
        val queryStart = afterScheme.indexOf('?')
        val pathPart = if (queryStart >= 0) afterScheme.substring(0, queryStart) else afterScheme
        val queryPart = if (queryStart >= 0) afterScheme.substring(queryStart + 1) else null
        val route = pathPart.trim('/').lowercase()
        if (route.isEmpty()) return null

        return when {
            route == FEATURE_CONTINUE || route == "resume" || route == "last" ->
                AssistantAction.ContinueReading(play = parsePlayQuery(queryPart))
            route == FEATURE_BOOKMARKS || route == "bookmark" ->
                AssistantAction.OpenBookmarks
            route == "bookmark/save" || route == FEATURE_SAVE_BOOKMARK ||
                route == "save-bookmark" ->
                AssistantAction.SaveBookmark
            else -> parseVerseRoute(route, queryPart)
        }
    }

    fun parseFeature(feature: String?): AssistantAction? = when (
        normalizeSpoken(feature ?: "")
    ) {
        "" -> null
        FEATURE_CONTINUE, "resume", "continue reading", "continue listening",
        "last read", "last verse",
        -> AssistantAction.ContinueReading()
        FEATURE_BOOKMARKS, "bookmark", "saved", "saved passages", "marked verses",
        -> AssistantAction.OpenBookmarks
        FEATURE_SAVE_BOOKMARK, "save bookmark", "bookmark verse", "save verse",
        "mark verse", "add bookmark", "bookmark this",
        -> AssistantAction.SaveBookmark
        FEATURE_VERSE -> null
        else -> parseSpokenCommand(feature ?: "")
    }

    /** Convert a media voice-search request into an action that always plays. */
    fun parsePlaySearch(query: String?): AssistantAction = when (
        val parsed = query?.takeIf(String::isNotBlank)?.let(::parseSpokenCommand)
    ) {
        is AssistantAction.OpenVerse -> parsed.copy(play = true)
        is AssistantAction.ContinueReading -> parsed.copy(play = true)
        is AssistantAction -> parsed
        null -> AssistantAction.ContinueReading(play = true)
    }

    /** Free-form phrases supplied by Android search or Assistant intents. */
    fun parseSpokenCommand(raw: String): AssistantAction? {
        val q = normalizeSpoken(raw)
        if (q.isEmpty()) return null

        when {
            q.matches(
                Regex(
                    """^(continue|resume|continue reading|continue listening|last read|where i left off)$""",
                ),
            ) -> return AssistantAction.ContinueReading()
            q.matches(
                Regex(
                    """^(bookmarks?|saved( passages| verses)?|marked verses|open bookmarks?)$""",
                ),
            ) -> return AssistantAction.OpenBookmarks
            q.matches(
                Regex(
                    """^(save(?: a)? bookmark|bookmark(?: this)?(?: verse)?(?: it)?|save(?: this)? verse|mark(?: this)? verse|add bookmark)$""",
                ),
            ) -> return AssistantAction.SaveBookmark
        }

        return parseVerseQuery(q)
    }

    fun parseVerseQuery(raw: String): AssistantAction.OpenVerse? {
        val q = normalizeSpoken(raw)
        if (q.isEmpty()) return null

        playPrefix.matchEntire(q)?.let { m ->
            return parseVerseReference(m.groupValues[1])?.copy(play = true)
        }
        return parseVerseReference(q)
    }

    /** A bare verse reference: "chapter 2", "2:255", or "surah 2 ayah 255". */
    private fun parseVerseReference(reference: String): AssistantAction.OpenVerse? {
        val q = reference.trim()
        if (q.isEmpty()) return null

        openChapter.matchEntire(q)?.let { m ->
            val surah = m.groupValues[1].toIntOrNull() ?: return null
            val ayah = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            return openVerseOrNull(surah, ayah)
        }

        openNumber.matchEntire(q)?.let { m ->
            val surah = m.groupValues[1].toIntOrNull() ?: return null
            return openVerseOrNull(surah, 1)
        }

        val colon = q.indexOf(':')
        if (colon > 0) {
            val surah = q.substring(0, colon).trim().toIntOrNull() ?: return null
            val ayahPart = q.substring(colon + 1).trim()
            val ayah = if (ayahPart.isEmpty()) 1 else ayahPart.toIntOrNull() ?: return null
            return openVerseOrNull(surah, ayah)
        }

        spokenVerse.matchEntire(q)?.let { m ->
            val surah = m.groupValues[1].toIntOrNull() ?: return null
            val ayah = m.groupValues[2].toIntOrNull() ?: return null
            return openVerseOrNull(surah, ayah)
        }

        q.toIntOrNull()?.takeIf { it in 1..114 }?.let { return openVerseOrNull(it, 1) }
        return null
    }

    /**
     * Lowercase, strip punctuation, and drop trailing "on Beautiful Quran"
     * (how Assistant often phrases App Action / search queries).
     */
    internal fun normalizeSpoken(raw: String): String =
        raw.trim()
            .lowercase()
            .replace(appNameSuffix, "")
            .replace(Regex("[\"'“”.,!?…]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseVerseRoute(route: String, rawQuery: String?): AssistantAction.OpenVerse? {
        val match = pathVerse.matchEntire(route) ?: return null
        val pathSurah = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val pathAyah = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val query = parseQuery(rawQuery)
        val surah = pathSurah ?: query[EXTRA_SURAH]?.toIntOrNull() ?: return null
        val ayah = pathAyah ?: query[EXTRA_AYAH]?.toIntOrNull() ?: 1
        return openVerseOrNull(surah, ayah, play = parsePlayQuery(rawQuery))
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            pair.substring(0, eq) to pair.substring(eq + 1)
        }.toMap()
    }

    private fun parsePlayQuery(raw: String?): Boolean =
        parseQuery(raw)["play"] in setOf("1", "true")

    private fun openVerseOrNull(
        surah: Int,
        ayah: Int,
        play: Boolean = false,
    ): AssistantAction.OpenVerse? {
        if (surah !in 1..114 || ayah < 1) return null
        return AssistantAction.OpenVerse(surah, ayah, play = play)
    }
}
