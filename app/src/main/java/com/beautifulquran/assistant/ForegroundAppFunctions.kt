@file:OptIn(androidx.appfunctions.ExperimentalAppFunctionsApi::class)

package com.beautifulquran.assistant

import android.app.appfunctions.AppFunction
import android.app.appfunctions.AppFunctionException
import android.app.appfunctions.AppFunctionManager
import android.app.appfunctions.AppFunctionRegistration
import android.app.appfunctions.ExecuteAppFunctionResponse
import android.app.appfunctions.RegisterAppFunctionRequest
import android.app.appsearch.GenericDocument
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionSignature
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.lifecycle.lifecycleScope
import com.beautifulquran.MainActivity
import com.beautifulquran.QuranApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val READER_FUNCTIONS_XML = "reader_functions"

/** Foreground signature for context-dependent bookmarking. */
@AppFunctionSignature(
    scope = AppFunctionMetadata.SCOPE_ACTIVITY,
    appFunctionXmlFileName = READER_FUNCTIONS_XML,
    isDescribedByKDoc = true,
)
fun interface BookmarkCurrentVerseFunction {
    /**
     * Bookmark the verse currently in focus in the visible reader. This
     * resolves "this" to the focused verse in the foreground activity.
     *
     * @return The chapter and verse that were bookmarked.
     */
    fun bookmarkCurrentVerse(): String
}

/** Foreground signature for arbitrary reader navigation. */
@AppFunctionSignature(
    scope = AppFunctionMetadata.SCOPE_ACTIVITY,
    appFunctionXmlFileName = READER_FUNCTIONS_XML,
    isDescribedByKDoc = true,
)
fun interface OpenVerseFunction {
    /**
     * Open any Quran chapter and optional verse in the visible reader.
     *
     * @param chapterNumber Quran chapter or surah number from 1 through 114.
     * @param verseNumber Optional verse or ayah; omit to open verse 1.
     * @return The chapter and verse opened in the reader.
     */
    fun openVerse(chapterNumber: Int, verseNumber: Int?): String
}

/** Foreground signature for returning to the saved reading position. */
@AppFunctionSignature(
    scope = AppFunctionMetadata.SCOPE_ACTIVITY,
    appFunctionXmlFileName = READER_FUNCTIONS_XML,
    isDescribedByKDoc = true,
)
fun interface ContinueReadingFunction {
    /** Continue reading at the last focused Quran verse. */
    fun continueReading(): String
}

/** Foreground signature for Quran-wide text and chapter search. */
@AppFunctionSignature(
    scope = AppFunctionMetadata.SCOPE_ACTIVITY,
    appFunctionXmlFileName = READER_FUNCTIONS_XML,
    isDescribedByKDoc = true,
)
fun interface SearchQuranFunction {
    /**
     * Search chapters, verse references, Arabic text, and English translation
     * in the visible app.
     *
     * @param query Search text or a reference such as "2:255".
     * @return Confirmation that the search results are visible.
     */
    fun searchQuran(query: String): String
}

/** Foreground signature for opening the saved-verses sheet. */
@AppFunctionSignature(
    scope = AppFunctionMetadata.SCOPE_ACTIVITY,
    appFunctionXmlFileName = READER_FUNCTIONS_XML,
    isDescribedByKDoc = true,
)
fun interface OpenBookmarksFunction {
    /** Open the visible app's saved-verses and bookmarks sheet. */
    fun openBookmarks(): String
}

/** Foreground signature for opening the chapter index. */
@AppFunctionSignature(
    scope = AppFunctionMetadata.SCOPE_ACTIVITY,
    appFunctionXmlFileName = READER_FUNCTIONS_XML,
    isDescribedByKDoc = true,
)
fun interface OpenChaptersFunction {
    /** Open the visible app's Quran chapter index. */
    fun openChapters(): String
}

/** Foreground signature for opening reader preferences. */
@AppFunctionSignature(
    scope = AppFunctionMetadata.SCOPE_ACTIVITY,
    appFunctionXmlFileName = READER_FUNCTIONS_XML,
    isDescribedByKDoc = true,
)
fun interface OpenSettingsFunction {
    /** Open the visible app's reader and recitation settings. */
    fun openSettings(): String
}

/** Registers all activity-scoped tools for one visible activity lifetime. */
@RequiresApi(37)
class ForegroundAppFunctions(private val activity: MainActivity) {

    private var registration: AppFunctionRegistration? = null

    fun register() {
        val app = activity.application as QuranApp
        val manager = activity.getSystemService(AppFunctionManager::class.java)
        registration = runCatching {
            manager.registerAppFunctions(
                listOf(
                    request(BOOKMARK_CURRENT_ID) {
                        val current = app.settings.settings.value
                        if (current.lastSurah !in 1..114) {
                            throw IllegalArgumentException("No Quran verse is currently focused")
                        }
                        val ayah = current.lastAyah.coerceAtLeast(1)
                        app.bookmarks.ensure(current.lastSurah, ayah)
                        "Bookmarked chapter ${current.lastSurah}, verse $ayah"
                    },
                    request(OPEN_VERSE_ID) { parameters ->
                        val chapter = parameters.requiredInt("chapterNumber")
                        val verse = parameters.optionalInt("verseNumber") ?: 1
                        val surah = app.repository.surahs().firstOrNull { it.id == chapter }
                            ?: throw IllegalArgumentException("Chapter must be from 1 through 114")
                        if (verse !in 1..surah.ayahCount) {
                            throw IllegalArgumentException(
                                "Chapter $chapter has verses 1 through ${surah.ayahCount}",
                            )
                        }
                        app.assistantActions.emit(AssistantAction.OpenVerse(chapter, verse))
                        "Opened chapter $chapter, verse $verse"
                    },
                    request(CONTINUE_READING_ID) {
                        app.assistantActions.emit(AssistantAction.ContinueReading())
                        "Opened the last-read verse"
                    },
                    request(SEARCH_QURAN_ID) { parameters ->
                        val query = parameters.requiredString("query")
                        app.assistantActions.emit(AssistantAction.Search(query))
                        "Showing Quran search results for $query"
                    },
                    request(OPEN_BOOKMARKS_ID) {
                        app.assistantActions.emit(AssistantAction.OpenBookmarks)
                        "Opened saved verses"
                    },
                    request(OPEN_CHAPTERS_ID) {
                        app.assistantActions.emit(AssistantAction.OpenChapters)
                        "Opened the Quran chapter index"
                    },
                    request(OPEN_SETTINGS_ID) {
                        app.assistantActions.emit(AssistantAction.OpenSettings)
                        "Opened reader settings"
                    },
                ),
            )
        }.onFailure { Log.e("AppFunctions", "Foreground registration failed", it) }
            .getOrNull()
    }

    fun unregister() {
        registration?.unregister()
        registration = null
    }

    private fun request(
        functionId: String,
        execute: suspend (GenericDocument) -> String,
    ): RegisterAppFunctionRequest = RegisterAppFunctionRequest(
        functionId,
        activity.mainExecutor,
        AppFunction { request, cancellationSignal, callback ->
            val job = activity.lifecycleScope.launch {
                try {
                    callback.onResult(execute(request.parameters).toResponse())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    callback.onError(
                        AppFunctionException(
                            if (error is IllegalArgumentException) {
                                AppFunctionException.ERROR_INVALID_ARGUMENT
                            } else {
                                AppFunctionException.ERROR_APP_UNKNOWN_ERROR
                            },
                            error.message ?: "Beautiful Quran could not complete the action",
                        ),
                    )
                }
            }
            cancellationSignal.setOnCancelListener { job.cancel() }
        },
    )

    private fun String.toResponse(): ExecuteAppFunctionResponse {
        val result = GenericDocument.Builder<GenericDocument.Builder<*>>(
            "appfunctions",
            "reader-result",
            "kotlin.String",
        )
            .setPropertyString(ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE, this)
            .build()
        return ExecuteAppFunctionResponse(result)
    }

    private fun GenericDocument.requiredInt(name: String): Int {
        if (name !in propertyNames) throw IllegalArgumentException("Missing $name")
        return getPropertyLong(name).toInt()
    }

    private fun GenericDocument.optionalInt(name: String): Int? =
        if (name in propertyNames) getPropertyLong(name).toInt() else null

    private fun GenericDocument.requiredString(name: String): String {
        if (name !in propertyNames) throw IllegalArgumentException("Missing $name")
        return getPropertyString(name)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$name cannot be empty")
    }

    private companion object {
        const val BOOKMARK_CURRENT_ID =
            "com.beautifulquran.assistant.BookmarkCurrentVerseFunction#bookmarkCurrentVerse"
        const val OPEN_VERSE_ID =
            "com.beautifulquran.assistant.OpenVerseFunction#openVerse"
        const val CONTINUE_READING_ID =
            "com.beautifulquran.assistant.ContinueReadingFunction#continueReading"
        const val SEARCH_QURAN_ID =
            "com.beautifulquran.assistant.SearchQuranFunction#searchQuran"
        const val OPEN_BOOKMARKS_ID =
            "com.beautifulquran.assistant.OpenBookmarksFunction#openBookmarks"
        const val OPEN_CHAPTERS_ID =
            "com.beautifulquran.assistant.OpenChaptersFunction#openChapters"
        const val OPEN_SETTINGS_ID =
            "com.beautifulquran.assistant.OpenSettingsFunction#openSettings"
    }
}
