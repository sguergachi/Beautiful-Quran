package com.beautifulquran.assistant

import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.media3.common.Player
import com.beautifulquran.QuranApp
import com.beautifulquran.data.AyahSelectorSide
import com.beautifulquran.data.ReadingMode
import com.beautifulquran.data.ThemeMode
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.model.Surah
import kotlin.math.abs
import kotlin.math.roundToInt

/** Android 17 on-device Quran tools exposed to authorized system agents. */
@AppFunctionServiceEntryPoint(
    serviceName = "QuranAppFunctionService",
    appFunctionXmlFileName = "beautiful_quran",
)
abstract class BaseQuranAppFunctionService : AppFunctionService() {

    /**
     * Play any numbered Quran chapter or surah with the user's selected reciter.
     *
     * @param chapterNumber Quran chapter or surah number from 1 through 114.
     * @param verseNumber Optional verse or ayah to start from; omit to start at
     * the beginning of the chapter.
     * @return The chapter and verse where recitation started.
     * @throws AppFunctionInvalidArgumentException If the chapter or verse is
     * outside the Quran; ask the user for a valid chapter or verse number.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playChapter(chapterNumber: Int, verseNumber: Int? = null): String {
        val app = quranApp
        val (surah, ayah) = app.resolveVerse(chapterNumber, verseNumber ?: 1)
        app.startRecitation(surah, ayah, app.selectedReciter())
        return "Playing ${surah.nameTransliteration}, chapter $chapterNumber, from verse $ayah"
    }

    /**
     * Resume paused recitation, or continue from the last-read verse when no
     * recitation is loaded.
     *
     * @return A short description of the resumed recitation.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun resumeRecitation(): String {
        val app = quranApp
        if (app.player.state.value.nowPlaying != null) {
            app.player.resume()
            return "Resumed Quran recitation"
        }
        val last = app.settings.settings.value
        val chapter = last.lastSurah.takeIf { it in 1..114 } ?: 1
        return playChapter(chapter, last.lastAyah.coerceAtLeast(1))
    }

    /** Pause the current recitation without losing its position. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun pauseRecitation(): String {
        quranApp.player.pause()
        return "Paused Quran recitation"
    }

    /** Stop recitation and clear the active playback queue. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun stopRecitation(): String {
        quranApp.player.stop()
        return "Stopped Quran recitation"
    }

    /** Move playback to the next verse in the loaded chapter. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun nextVerse(): String {
        quranApp.player.next()
        return "Moved to the next verse"
    }

    /** Move playback to the previous verse in the loaded chapter. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun previousVerse(): String {
        quranApp.player.previous()
        return "Moved to the previous verse"
    }

    /**
     * Set recitation speed.
     *
     * @param speed Playback multiplier: 0.75, 1.0, 1.25, or 1.5.
     * @return The selected playback speed.
     * @throws AppFunctionInvalidArgumentException If the speed is unsupported;
     * ask the user to choose one of the supported multipliers.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setPlaybackSpeed(speed: Float): String {
        val selected = PLAYBACK_SPEEDS.firstOrNull { abs(it - speed) < 0.01f }
            ?: throw AppFunctionInvalidArgumentException(
                "Speed must be 0.75, 1.0, 1.25, or 1.5",
            )
        quranApp.player.setSpeed(selected)
        return "Set recitation speed to ${selected}x"
    }

    /**
     * Play and continuously repeat any verse or inclusive verse range.
     *
     * @param chapterNumber Quran chapter or surah number from 1 through 114.
     * @param firstVerse First verse in the repeat range.
     * @param lastVerse Optional last verse; omit to repeat only the first verse.
     * @return The active repeat range.
     * @throws AppFunctionInvalidArgumentException If the chapter or range is
     * invalid; ask the user for verses that exist in the same chapter.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun repeatVerses(
        chapterNumber: Int,
        firstVerse: Int,
        lastVerse: Int? = null,
    ): String {
        val app = quranApp
        val (surah, start) = app.resolveVerse(chapterNumber, firstVerse)
        val end = lastVerse ?: start
        if (end !in start..surah.ayahCount) {
            throw AppFunctionInvalidArgumentException(
                "Chapter $chapterNumber repeat range must end from $start through ${surah.ayahCount}",
            )
        }
        app.startRecitation(surah, start, app.selectedReciter())
        app.player.setRepeatRange(start, end)
        return "Repeating chapter $chapterNumber, verses $start through $end"
    }

    /** Disable verse, range, and chapter repeat while preserving playback. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun disableRepeat(): String {
        quranApp.player.run {
            clearRepeatRange()
            setRepeatMode(Player.REPEAT_MODE_OFF)
        }
        return "Repeat is off"
    }

    /**
     * Select a reciter by full or unambiguous partial name for future and
     * current recitation.
     *
     * @param reciterName Reciter name, such as Mishary, Husary, Minshawi,
     * AbdulBaset, Sudais, Shuraym, or Rifai.
     * @return The selected reciter's full name.
     * @throws AppFunctionInvalidArgumentException If no unique reciter matches;
     * ask the user to choose from the names in the error.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun selectReciter(reciterName: String): String {
        val app = quranApp
        val reciters = app.repository.reciters()
        val query = reciterName.normalizedName()
        val exact = reciters.filter {
            it.name.normalizedName() == query || it.slug.normalizedName() == query
        }
        val partial = reciters.filter {
            it.name.normalizedName().contains(query) || it.slug.normalizedName().contains(query)
        }
        val matches = exact.ifEmpty { partial }
        val reciter = matches.singleOrNull() ?: throw AppFunctionInvalidArgumentException(
            when {
                query.isEmpty() -> "Reciter name cannot be empty"
                matches.isEmpty() -> "No reciter matched. Available reciters: ${reciters.names()}"
                else -> "Reciter name is ambiguous. Matches: ${matches.names()}"
            },
        )
        app.settings.update { it.copy(reciterId = reciter.id) }
        app.player.state.value.nowPlaying?.let { nowPlaying ->
            val surah = app.repository.surahs().firstOrNull { it.id == nowPlaying.surahId }
                ?: return@let
            app.player.playSurah(
                surahId = surah.id,
                ayahCount = surah.ayahCount,
                startAyah = nowPlaying.ayah.coerceAtLeast(1),
                reciter = reciter,
                surahName = surah.nameTransliteration,
                startWithBasmalah = nowPlaying.ayah == 0,
            )
        }
        return "Selected ${reciter.name}"
    }

    /**
     * Save any Quran verse as a bookmark without opening the app UI.
     *
     * @param chapterNumber Quran chapter or surah number from 1 through 114.
     * @param verseNumber Verse or ayah number within that chapter.
     * @return Whether the verse was newly saved or already bookmarked.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun bookmarkVerse(chapterNumber: Int, verseNumber: Int): String {
        val app = quranApp
        app.resolveVerse(chapterNumber, verseNumber)
        val added = app.bookmarks.ensure(chapterNumber, verseNumber)
        return if (added) {
            "Bookmarked chapter $chapterNumber, verse $verseNumber"
        } else {
            "Chapter $chapterNumber, verse $verseNumber was already bookmarked"
        }
    }

    /**
     * Remove a bookmark from any Quran verse without affecting other bookmarks.
     *
     * @param chapterNumber Quran chapter or surah number from 1 through 114.
     * @param verseNumber Verse or ayah number within that chapter.
     * @return Whether an existing bookmark was removed.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun removeBookmark(chapterNumber: Int, verseNumber: Int): String {
        val app = quranApp
        app.resolveVerse(chapterNumber, verseNumber)
        val removed = app.bookmarks.remove(chapterNumber, verseNumber)
        return if (removed) {
            "Removed bookmark from chapter $chapterNumber, verse $verseNumber"
        } else {
            "Chapter $chapterNumber, verse $verseNumber was not bookmarked"
        }
    }

    /**
     * Configure user-facing reader preferences; omitted values remain unchanged.
     *
     * @param readingMode Optional "Arabic and English" or "English only" mode.
     * @param fontScale Optional text scale from 0.8 through 1.6, rounded to the
     * nearest reader-supported 0.1 step.
     * @param showWordGloss Optional word-by-word translation visibility.
     * @param showTransliteration Optional transliteration visibility.
     * @param showTranslation Optional full-verse translation visibility.
     * @param selectorSide Optional "left" or "right" ayah selector position.
     * @param theme Optional "system", "paper", "nightfall", or "royal green" theme.
     * @return A short confirmation that reader preferences were updated.
     * @throws AppFunctionInvalidArgumentException If a supplied value is not
     * supported; ask the user to choose a value documented on that parameter.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun configureReader(
        readingMode: String? = null,
        fontScale: Float? = null,
        showWordGloss: Boolean? = null,
        showTransliteration: Boolean? = null,
        showTranslation: Boolean? = null,
        selectorSide: String? = null,
        theme: String? = null,
    ): String {
        val mode = readingMode?.let(::parseReadingMode)
        val scale = fontScale?.let {
            if (it !in 0.8f..1.6f) {
                throw AppFunctionInvalidArgumentException("Font scale must be from 0.8 through 1.6")
            }
            (it * 10f).roundToInt() / 10f
        }
        val side = selectorSide?.let(::parseSelectorSide)
        val themeMode = theme?.let(::parseTheme)
        quranApp.settings.update { current ->
            current.copy(
                readingMode = mode ?: current.readingMode,
                fontScale = scale ?: current.fontScale,
                showWordGloss = showWordGloss ?: current.showWordGloss,
                showTransliteration = showTransliteration ?: current.showTransliteration,
                showTranslation = showTranslation ?: current.showTranslation,
                ayahSelectorSide = side ?: current.ayahSelectorSide,
                themeMode = themeMode ?: current.themeMode,
            )
        }
        return "Updated Beautiful Quran reader preferences"
    }

    private val quranApp: QuranApp
        get() = application as QuranApp

    private suspend fun QuranApp.resolveVerse(chapter: Int, verse: Int): Pair<Surah, Int> {
        val surah = repository.surahs().firstOrNull { it.id == chapter }
            ?: throw AppFunctionInvalidArgumentException("Chapter must be from 1 through 114")
        if (verse !in 1..surah.ayahCount) {
            throw AppFunctionInvalidArgumentException(
                "Chapter $chapter has verses 1 through ${surah.ayahCount}",
            )
        }
        return surah to verse
    }

    private suspend fun QuranApp.selectedReciter(): Reciter {
        val reciters = repository.reciters()
        return reciters.firstOrNull { it.id == settings.settings.value.reciterId }
            ?: reciters.first()
    }

    private fun QuranApp.startRecitation(surah: Surah, ayah: Int, reciter: Reciter) {
        settings.updateListeningPosition(surah.id, ayah)
        player.playSurah(
            surahId = surah.id,
            ayahCount = surah.ayahCount,
            startAyah = ayah,
            reciter = reciter,
            surahName = surah.nameTransliteration,
            preserveRepeatRange = false,
            startWithBasmalah = ayah == 1,
        )
        assistantActions.tryEmit(AssistantAction.OpenVerse(surah.id, ayah))
    }

    private fun parseReadingMode(value: String): ReadingMode = when (value.normalizedName()) {
        "arabic and english", "arabic english", "bilingual", "arabic" ->
            ReadingMode.ARABIC_ENGLISH
        "english", "english only" -> ReadingMode.ENGLISH_ONLY
        else -> throw AppFunctionInvalidArgumentException(
            "Reading mode must be Arabic and English or English only",
        )
    }

    private fun parseSelectorSide(value: String): AyahSelectorSide = when (value.normalizedName()) {
        "left", "left side" -> AyahSelectorSide.LEFT
        "right", "right side" -> AyahSelectorSide.RIGHT
        else -> throw AppFunctionInvalidArgumentException("Selector side must be left or right")
    }

    private fun parseTheme(value: String): ThemeMode = when (value.normalizedName()) {
        "system", "automatic" -> ThemeMode.SYSTEM
        "paper", "light" -> ThemeMode.LIGHT
        "nightfall", "dark" -> ThemeMode.DARK
        "royal green", "green" -> ThemeMode.ROYAL_GREEN
        else -> throw AppFunctionInvalidArgumentException(
            "Theme must be system, paper, nightfall, or royal green",
        )
    }

    private fun String.normalizedName(): String =
        lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun List<Reciter>.names(): String = joinToString { it.name }

    private companion object {
        val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f)
    }
}
