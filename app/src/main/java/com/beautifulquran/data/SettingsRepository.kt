package com.beautifulquran.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK, ROYAL_GREEN }

/** What flows on the sheet: Arabic with English under each word, English only, or Arabic only. */
enum class ReadingMode { ARABIC_ENGLISH, ENGLISH_ONLY, ARABIC_ONLY }

/**
 * Continuous scroll, or a printed leaf. A leaf may be set in the page's own
 * hand or in English (`domain/EnglishLeaf.kt`) — never bilingual, which is a
 * scroll idea. See `MUSHAF_VIEW_MODES`.
 */
enum class ReadingLayout { SCROLL, MUSHAF }

/** Digit form of the trailing ﴿N﴾ / ﴾N﴿ verse mark. */
enum class VerseNumberScript { ARABIC, ENGLISH }

/**
 * Which English the leaf is set from.
 *
 * [TRANSLATION] is the published verse translation, which reads as a book but
 * only lines up with the recitation approximately — Arabic word order is not
 * English word order, so `EnglishWordAlignment` has to bridge the two and
 * anchors about 84 % of words. [GLOSS] is the word-by-word crib the scrolling
 * reader sets, stitched into a line: it reads less well, and every Arabic word
 * lights exactly its own English, because there is nothing to align.
 *
 * A reading preference rather than a right answer, so it is the reader's.
 */
enum class EnglishLeafText { TRANSLATION, GLOSS }

/** Folio figures on a mushaf page break: one script, or both. */
enum class PageNumberScript { BOTH, ARABIC, ENGLISH }

/** Which screen edge the ayah selector rail lives on. */
enum class AyahSelectorSide { LEFT, RIGHT }

/** Developer-selectable bookmark treatment on the Chapters sheet. */
enum class HomeBookmarkStyle { TOP_BOUND, SAVED_PASSAGES }

/** One-shot, dismissible lessons that teach a gesture in its own UI context. */
enum class EducationMoment(val preferenceKey: String) {
    BOOKMARK_NOTE("educationBookmarkNoteV1"),
    AYAH_RAIL("educationAyahRailV1"),
}

/**
 * Ink-brush circle variants for settings selectors. [BASELINE] is the shipped
 * mark; the rest are developer-only A/B options (see Settings → Developer).
 * Keep labels/params in lockstep with web `BrushCircleStyle` in brushMark.ts.
 */
enum class BrushCircleStyle {
    BASELINE,
    HAIRLINE,
    HEAVY,
    TIGHT,
    LOOSE,
    SHARP_NIB,
    SOFT_NIB,
    LONG_OVERSHOOT,
    CLOSED_RING,
    LIVELY,
    DRY_BRUSH,
}

data class Settings(
    val reciterId: Int = 1,
    val fontScale: Float = 1f,
    val readingMode: ReadingMode = ReadingMode.ARABIC_ENGLISH,
    val readingLayout: ReadingLayout = ReadingLayout.SCROLL,
    val verseNumberScript: VerseNumberScript = VerseNumberScript.ARABIC,
    val pageNumberScript: PageNumberScript = PageNumberScript.BOTH,
    /** Which English the mushaf's English leaf is set from. */
    val englishLeafText: EnglishLeafText = EnglishLeafText.TRANSLATION,
    val showWordGloss: Boolean = true,
    val showTransliteration: Boolean = false,
    val showTranslation: Boolean = false,
    /** Verse annotations — the reader's own ḥawāshī today, and any scholar's
     * gloss the app ships later. Off hides every annotation and its entry
     * gesture; stored writing is never deleted. See docs/ANNOTATIONS.md. */
    val annotationsEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val ayahSelectorSide: AyahSelectorSide = AyahSelectorSide.LEFT,
    /** Last verse recited — shared by Continue Listening and the green ribbon. */
    val lastSurah: Int = 0,
    val lastAyah: Int = 1,
    /** Unlocks the Timings Lab and the word-hold chooser. Toggled by
     *  repeatedly tapping the Settings logo; persisted so the reader can
     *  honour it. See docs/ROOT_VIEWER.md and docs/TIMINGS_LAB.md. */
    val developerModeEnabled: Boolean = false,
    /** Developer-only gate for contextual feature lessons. Off until their
     * visual language is approved for readers. Enabling rearms every lesson. */
    val educationGuidesEnabled: Boolean = false,
    /** Shows the Ink Lab overlay on the reader — live sliders over the
     *  highlight tuning (see docs/INK_ENGINE.md). Only honoured while
     *  [developerModeEnabled] is on. Lab numbers persist via
     *  [com.beautifulquran.ui.reader.InkLabStore] until Reset. */
    val inkLabEnabled: Boolean = false,
    /** Developer-selectable Chapters bookmark treatment. */
    val homeBookmarkStyle: HomeBookmarkStyle = HomeBookmarkStyle.TOP_BOUND,
    /** Developer-only: which ink-brush circle to paint around selected enums. */
    val brushCircleStyle: BrushCircleStyle = BrushCircleStyle.BASELINE,
    /** Developer-only: removes parenthetical and bracketed asides from English-only reading. */
    val hideEnglishParentheticals: Boolean = false,
)

/** Maps a persisted ordinal back to an enum entry, falling back to [default]
 * when it no longer maps (e.g. after an entry was removed in an update). */
internal fun <E : Enum<E>> enumForOrdinal(entries: List<E>, ordinal: Int, default: E): E =
    entries.getOrNull(ordinal) ?: default

/** Reads an enum stored by ordinal, tolerating stale ordinals. */
private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, default: E): E =
    enumForOrdinal(enumValues<E>().toList(), getInt(key, default.ordinal), default)

/** Reads the named v2 value, migrating the old five-way ordinal experiment. */
private fun SharedPreferences.homeBookmarkStyle(): HomeBookmarkStyle =
    getString("homeBookmarkStyleV2", null)?.let { stored ->
        runCatching { HomeBookmarkStyle.valueOf(stored) }.getOrNull()
    } ?: if (getInt("homeBookmarkStyle", -1) == 3) {
        HomeBookmarkStyle.SAVED_PASSAGES
    } else {
        HomeBookmarkStyle.TOP_BOUND
    }

/**
 * What the remembered leaf figures describe.
 *
 * A well and a measure are only meaningful against the grid that produced them,
 * and the grid moves: a foot band, a running head, a fore-edge. A figure kept
 * from a build whose leaf was a different shape is worse than no figure at all —
 * it paginates the whole book against a leaf that no longer exists, and the
 * symptom is a leaf that stops a few words short of its own last line. **Bump
 * this whenever anything in `MushafGrid`, `MushafLeafBands` or the leaf's
 * fore-edge changes**, and the next launch measures instead of remembering.
 */
private const val LEAF_METRICS_VERSION = 3

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings

    private fun read() = Settings(
        reciterId = prefs.getInt("reciterId", 1),
        fontScale = prefs.getFloat("fontScale", 1f),
        readingMode = prefs.enum("readingMode", ReadingMode.ARABIC_ENGLISH),
        readingLayout = prefs.enum("readingLayout", ReadingLayout.SCROLL),
        verseNumberScript = prefs.enum("verseNumberScript", VerseNumberScript.ARABIC),
        pageNumberScript = prefs.enum("pageNumberScript", PageNumberScript.BOTH),
        englishLeafText = prefs.enum("englishLeafText", EnglishLeafText.TRANSLATION),
        showWordGloss = prefs.getBoolean("showWordGloss", true),
        showTransliteration = prefs.getBoolean("showTransliteration", false),
        showTranslation = prefs.getBoolean("showTranslation", false),
        annotationsEnabled = prefs.getBoolean("annotationsEnabled", true),
        themeMode = prefs.enum("themeMode", ThemeMode.SYSTEM),
        ayahSelectorSide = prefs.enum("ayahSelectorSide", AyahSelectorSide.LEFT),
        lastSurah = prefs.getInt("lastSurah", 0),
        lastAyah = prefs.getInt("lastAyah", 1),
        developerModeEnabled = prefs.getBoolean("developerModeEnabled", false),
        educationGuidesEnabled = prefs.getBoolean("educationGuidesEnabled", false),
        inkLabEnabled = prefs.getBoolean("inkLabEnabled", false),
        homeBookmarkStyle = prefs.homeBookmarkStyle(),
        brushCircleStyle = prefs.enum("brushCircleStyle", BrushCircleStyle.BASELINE),
        hideEnglishParentheticals = prefs.getBoolean("hideEnglishParentheticals", false),
    )

    /**
     * Shared Continue Listening / green-ribbon target, written on every audio
     * ayah advance. [update] rewrites every settings key per call, which is
     * needless write amplification for two frequently changing integers.
     */
    /**
     * The English leaf's well and measure, as last laid out, with the window
     * they were laid out in.
     *
     * The book is paginated by measuring a leaf ([EnglishLeafRuler]), and a leaf
     * has no size until it has been composed — so the first time the reader ever
     * opens, the book has to open on the character estimate and repaginate a
     * frame later. Only the first time. Remembering the figures means every
     * launch after it can measure the whole book before anything is opened, and
     * the reader never sees the estimate at all.
     *
     * Kept beside the settings rather than in them: they are a fact about the
     * screen, not a preference, and they change on nobody's decision. The window
     * is stored with them because a leaf on a folded phone is not the leaf on an
     * unfolded one, and a remembered figure that no longer describes the screen
     * is worse than none.
     */
    fun rememberLeafMetrics(wellPx: Float, measurePx: Float, windowWidthPx: Int, windowHeightPx: Int) {
        if (wellPx <= 0f || measurePx <= 0f) return
        prefs.edit {
            putFloat("leafWellPx", wellPx)
            putFloat("leafMeasurePx", measurePx)
            putInt("leafWindowW", windowWidthPx)
            putInt("leafWindowH", windowHeightPx)
            putInt("leafMetricsVersion", LEAF_METRICS_VERSION)
        }
    }

    /** The remembered well and measure, if they still describe this window. */
    fun leafMetrics(windowWidthPx: Int, windowHeightPx: Int): FloatArray? {
        if (prefs.getInt("leafMetricsVersion", 0) != LEAF_METRICS_VERSION) return null
        if (prefs.getInt("leafWindowW", 0) != windowWidthPx) return null
        if (prefs.getInt("leafWindowH", 0) != windowHeightPx) return null
        val well = prefs.getFloat("leafWellPx", 0f)
        val measure = prefs.getFloat("leafMeasurePx", 0f)
        return if (well > 0f && measure > 0f) floatArrayOf(well, measure) else null
    }

    fun updateListeningPosition(surah: Int, ayah: Int) {
        val current = _settings.value
        if (current.lastSurah == surah && current.lastAyah == ayah) return
        _settings.value = current.copy(lastSurah = surah, lastAyah = ayah)
        prefs.edit {
            putInt("lastSurah", surah)
            putInt("lastAyah", ayah)
        }
    }

    /** Persists a live reader resize without rewriting every unrelated setting. */
    fun updateFontScale(fontScale: Float) {
        val current = _settings.value
        if (current.fontScale == fontScale) return
        _settings.value = current.copy(fontScale = fontScale)
        prefs.edit { putFloat("fontScale", fontScale) }
    }

    /** Whether the reader has explicitly put this contextual lesson away. */
    fun isEducationDismissed(moment: EducationMoment): Boolean =
        prefs.getBoolean(moment.preferenceKey, false)

    /** Persists dismissal without rewriting the user-facing settings state. */
    fun dismissEducation(moment: EducationMoment) {
        prefs.edit { putBoolean(moment.preferenceKey, true) }
    }

    /** Developer tool: lets every contextual lesson run on its next eligible gesture. */
    fun rearmEducation() {
        prefs.edit { EducationMoment.entries.forEach { remove(it.preferenceKey) } }
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        prefs.edit {
            putInt("reciterId", next.reciterId)
            putFloat("fontScale", next.fontScale)
            putInt("readingMode", next.readingMode.ordinal)
            putInt("readingLayout", next.readingLayout.ordinal)
            putInt("verseNumberScript", next.verseNumberScript.ordinal)
            putInt("pageNumberScript", next.pageNumberScript.ordinal)
            putInt("englishLeafText", next.englishLeafText.ordinal)
            putBoolean("showWordGloss", next.showWordGloss)
            putBoolean("showTransliteration", next.showTransliteration)
            putBoolean("showTranslation", next.showTranslation)
            putBoolean("annotationsEnabled", next.annotationsEnabled)
            putInt("themeMode", next.themeMode.ordinal)
            putInt("ayahSelectorSide", next.ayahSelectorSide.ordinal)
            putInt("lastSurah", next.lastSurah)
            putInt("lastAyah", next.lastAyah)
            putBoolean("developerModeEnabled", next.developerModeEnabled)
            putBoolean("educationGuidesEnabled", next.educationGuidesEnabled)
            putBoolean("inkLabEnabled", next.inkLabEnabled)
            putString("homeBookmarkStyleV2", next.homeBookmarkStyle.name)
            remove("homeBookmarkStyle")
            putInt("brushCircleStyle", next.brushCircleStyle.ordinal)
            putBoolean("hideEnglishParentheticals", next.hideEnglishParentheticals)
        }
    }
}
