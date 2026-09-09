package com.beautifulquran.ui.share

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beautifulquran.data.QuranRepository
import com.beautifulquran.playback.PlayerController
import com.beautifulquran.share.AyahRef
import com.beautifulquran.share.SHARE_SELECTION_MAX
import com.beautifulquran.share.ShareFiles
import com.beautifulquran.share.ShareImageRenderer
import com.beautifulquran.share.ShareUx
import com.beautifulquran.share.ShareUxAction
import com.beautifulquran.share.VerseTextComposer
import com.beautifulquran.share.stitchBitmaps
import com.beautifulquran.share.gatherOrdinals
import com.beautifulquran.share.toggleGatheredAyah
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One gathered verse resolved for the Send page list / image card. */
data class ShareVerseLine(
    val ref: AyahRef,
    val surahName: String,
    val arabic: String,
    val translation: String,
) {
    val reference: String
        get() {
            val name = surahName.ifBlank { "Surah ${ref.surahId}" }
            return "$name ${ref.surahId}:${ref.ayah}"
        }
}

/**
 * Gather-mode selection, text share, and full-ink image export.
 *
 * Held at activity scope so the ordered list survives chapter turns.
 */
data class ShareUiState(
    val gathering: Boolean = false,
    val sendOpen: Boolean = false,
    val selection: List<AyahRef> = emptyList(),
    /** 1-based ordinals derived from [selection] — read by ayah blocks only. */
    val ordinals: Map<AyahRef, Int> = emptyMap(),
    /** Resolved rows for the Send page (Arabic preview + reference). */
    val verseLines: List<ShareVerseLine> = emptyList(),
    /** One-shot payload for ACTION_SEND text; cleared after the chooser is fired. */
    val pendingShareText: String? = null,
    /** One-shot content:// URI for ACTION_SEND image. */
    val pendingShareImageUri: String? = null,
    val preparingText: Boolean = false,
    val preparingImage: Boolean = false,
    /** Quiet line on the Send page when load/format/render fails. */
    val error: String? = null,
)

class ShareViewModel(
    private val repository: QuranRepository,
    private val player: PlayerController,
) : ViewModel() {

    private val _ui = MutableStateFlow(ShareUiState())
    val ui: StateFlow<ShareUiState> = _ui.asStateFlow()

    /** Transliterated chapter names for Send-page labels (id → name). */
    private val _surahNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val surahNames: StateFlow<Map<Int, String>> = _surahNames.asStateFlow()

    private var previewJob: Job? = null
    private var textJob: Job? = null
    private var imageJob: Job? = null

    init {
        viewModelScope.launch {
            _surahNames.value = repository.surahs()
                .associate { it.id to it.nameTransliteration }
        }
    }

    /**
     * Verse-first enter: that ayah is already selected (`1`), playback paused.
     * Mark tap uses this when gather is off.
     */
    fun enterShare(surahId: Int, ayah: Int) {
        if (surahId < 1 || ayah < 1) return
        val ref = AyahRef(surahId, ayah)
        if (_ui.value.gathering) {
            if (ref !in _ui.value.selection) toggle(surahId, ayah)
            return
        }
        player.pause()
        val selection = listOf(ref)
        _ui.update {
            it.copy(
                gathering = true,
                sendOpen = false,
                selection = selection,
                ordinals = gatherOrdinals(selection),
                error = null,
                pendingShareText = null,
                pendingShareImageUri = null,
            )
        }
    }

    fun onMarkTap(surahId: Int, ayah: Int) {
        if (surahId < 1 || ayah < 1) return
        apply(
            ShareUx.onMarkTap(
                gathering = _ui.value.gathering,
                ref = AyahRef(surahId, ayah),
            ),
        )
    }

    /** Cancel on the share ribbon: leave gather. */
    fun onChromeCancel() {
        exitGather()
    }

    /** Cover / Bookmarks / Settings — not a mushaf page or chapter turn. */
    fun onLeaveReaderSheet() {
        apply(ShareUx.onLeaveReaderSheet(_ui.value.gathering))
    }

    private fun apply(action: ShareUxAction) {
        when (action) {
            is ShareUxAction.EnterShare -> enterShare(action.ref.surahId, action.ref.ayah)
            is ShareUxAction.ToggleVerse -> toggle(action.ref.surahId, action.ref.ayah)
            ShareUxAction.ExitShare -> exitGather()
            ShareUxAction.None -> Unit
        }
    }

    /** Back while gathering (Send closed): drop the list and leave the mode. */
    fun exitGather() {
        previewJob?.cancel()
        textJob?.cancel()
        imageJob?.cancel()
        _ui.value = ShareUiState()
    }

    fun openSend() {
        if (_ui.value.selection.isEmpty()) return
        _ui.update {
            it.copy(
                sendOpen = true,
                error = null,
                pendingShareText = null,
                pendingShareImageUri = null,
            )
        }
        refreshVerseLines()
    }

    /** Back on the Send page: return to gather with the list intact. */
    fun closeSend() {
        previewJob?.cancel()
        textJob?.cancel()
        imageJob?.cancel()
        _ui.update {
            it.copy(
                sendOpen = false,
                preparingText = false,
                preparingImage = false,
                pendingShareText = null,
                pendingShareImageUri = null,
                error = null,
                verseLines = emptyList(),
            )
        }
    }

    fun toggle(surahId: Int, ayah: Int) {
        if (!_ui.value.gathering || surahId < 1 || ayah < 1) return
        val ref = AyahRef(surahId, ayah)
        val next = toggleGatheredAyah(_ui.value.selection, ref, SHARE_SELECTION_MAX)
        _ui.update {
            it.copy(
                selection = next,
                ordinals = gatherOrdinals(next),
                error = null,
            )
        }
    }

    fun remove(ref: AyahRef) {
        val next = _ui.value.selection.filterNot { it == ref }
        _ui.update {
            it.copy(
                selection = next,
                ordinals = gatherOrdinals(next),
                error = null,
            )
        }
        if (next.isEmpty()) {
            closeSend()
        } else if (_ui.value.sendOpen) {
            refreshVerseLines()
        }
    }

    /** Load verse text in selection order and stage plain text for the OS chooser. */
    fun shareAsText(includeTranslation: Boolean = true) {
        if (!_ui.value.gathering) return
        if (_ui.value.preparingText || _ui.value.preparingImage) return
        if (_ui.value.selection.isEmpty()) return
        _ui.update { it.copy(preparingText = true, error = null, pendingShareText = null) }
        val lines = _ui.value.verseLines
        if (lines.isNotEmpty()) {
            stageShareText(
                lines.map {
                    VerseTextComposer.Verse(
                        arabic = it.arabic,
                        translation = it.translation,
                        surahNameTransliteration = it.surahName,
                        surahId = it.ref.surahId,
                        ayah = it.ref.ayah,
                    )
                },
                includeTranslation,
            )
            return
        }
        textJob?.cancel()
        textJob = viewModelScope.launch {
            try {
                val verses = loadComposerVerses(_ui.value.selection)
                if (!_ui.value.gathering) return@launch
                if (verses.isEmpty()) {
                    _ui.update {
                        it.copy(preparingText = false, error = "Could not load those verses.")
                    }
                    return@launch
                }
                stageShareText(verses, includeTranslation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!_ui.value.gathering) return@launch
                _ui.update {
                    it.copy(
                        preparingText = false,
                        error = e.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "Could not prepare the share.",
                    )
                }
            }
        }
    }

    /**
     * Renders a fixed Paper-theme full-ink PNG offscreen and stages a
     * content:// URI for ACTION_SEND. Needs a live [Activity] for Compose
     * measure/layout.
     */
    fun shareAsImage(activity: Activity, includeTranslation: Boolean = true) {
        if (!_ui.value.gathering) return
        if (_ui.value.preparingText || _ui.value.preparingImage) return
        if (_ui.value.selection.isEmpty()) return
        imageJob?.cancel()
        _ui.update {
            it.copy(
                preparingImage = true,
                error = null,
                pendingShareImageUri = null,
            )
        }
        imageJob = viewModelScope.launch {
            var bitmap: Bitmap? = null
            try {
                val lines = _ui.value.verseLines.ifEmpty {
                    loadVerseLines(_ui.value.selection)
                }
                if (!_ui.value.gathering) return@launch
                if (lines.isEmpty()) {
                    _ui.update {
                        it.copy(preparingImage = false, error = "Could not load those verses.")
                    }
                    return@launch
                }
                var versesBmp: Bitmap? = null
                var footerBmp: Bitmap? = null
                try {
                    versesBmp = ShareImageRenderer.renderSegments(
                        activity = activity,
                        segmentCount = lines.size,
                    ) { index ->
                        ShareImageVerseStrip(
                            verse = lines[index],
                            includeTranslation = includeTranslation,
                            padTop = if (index == 0) {
                                ShareImagePadTop
                            } else {
                                ShareImagePadBetween
                            },
                            padBottom = if (index == lines.lastIndex) {
                                0.dp
                            } else {
                                ShareImagePadBetween
                            },
                        )
                    }
                    footerBmp = ShareImageRenderer.render(activity) {
                        ShareImageFooterStrip(shareFooterCopy(lines))
                    }
                    bitmap = stitchBitmaps(listOf(versesBmp, footerBmp))
                } finally {
                    versesBmp?.recycle()
                    footerBmp?.recycle()
                }
                if (!_ui.value.gathering) return@launch
                val uri = ShareFiles.writePng(activity.applicationContext, bitmap)
                _ui.update {
                    it.copy(
                        preparingImage = false,
                        pendingShareImageUri = uri.toString(),
                        error = null,
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (!_ui.value.gathering) return@launch
                _ui.update {
                    it.copy(
                        preparingImage = false,
                        error = t.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "Could not render the image.",
                    )
                }
            } finally {
                bitmap?.recycle()
            }
        }
    }

    fun consumePendingShareText() {
        _ui.update { it.copy(pendingShareText = null) }
    }

    fun consumePendingShareImage() {
        _ui.update { it.copy(pendingShareImageUri = null) }
    }

    private fun stageShareText(
        verses: List<VerseTextComposer.Verse>,
        includeTranslation: Boolean,
    ) {
        if (!_ui.value.gathering) return
        val text = VerseTextComposer.compose(verses, includeTranslation)
        _ui.update {
            it.copy(preparingText = false, pendingShareText = text, error = null)
        }
    }

    private fun refreshVerseLines() {
        val refs = _ui.value.selection
        if (refs.isEmpty()) {
            _ui.update { it.copy(verseLines = emptyList()) }
            return
        }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val lines = loadVerseLines(refs)
                _ui.update { it.copy(verseLines = lines, error = null) }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        verseLines = emptyList(),
                        error = e.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "Could not load those verses.",
                    )
                }
            }
        }
    }

    private suspend fun loadComposerVerses(refs: List<AyahRef>): List<VerseTextComposer.Verse> {
        val uniqueSurahIds = refs.map { it.surahId }.distinct()
        val contents = uniqueSurahIds.associateWith { repository.surahContent(it) }
        return refs.mapNotNull { ref ->
            val content = contents[ref.surahId] ?: return@mapNotNull null
            val ayah = content.ayahs.firstOrNull { it.number == ref.ayah }
                ?: return@mapNotNull null
            VerseTextComposer.Verse(
                arabic = ayah.text,
                translation = ayah.translation,
                surahNameTransliteration = content.surah.nameTransliteration,
                surahId = ref.surahId,
                ayah = ref.ayah,
            )
        }
    }

    private suspend fun loadVerseLines(refs: List<AyahRef>): List<ShareVerseLine> =
        loadComposerVerses(refs).map { v ->
            ShareVerseLine(
                ref = AyahRef(v.surahId, v.ayah),
                surahName = v.surahNameTransliteration,
                arabic = v.arabic,
                translation = v.translation,
            )
        }
}
