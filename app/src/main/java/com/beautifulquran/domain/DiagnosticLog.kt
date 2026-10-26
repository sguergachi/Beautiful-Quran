package com.beautifulquran.domain

/** Immutable capture detached from the live recorder before formatting off the UI thread. */
data class DiagnosticLog(
    val marks: List<String>,
    val highlight: HighlightTrace?,
    val droppedMarks: Int = 0,
) {
    /** Keep replay delimiters compatible with the existing offline highlight replay reader. */
    fun encodedLines(): List<String> = marks + listOf("dropped marks=$droppedMarks") +
        if (highlight == null) emptyList() else listOf(
            "highlight-sync-trace-begin", highlight.encodeForReplay(), "highlight-sync-trace-end",
        )

    /** One readable text attachment with exact timing inputs embedded for deterministic replay. */
    fun report(metadata: Map<String, String>): String = buildString {
        appendLine("Beautiful Quran log export")
        metadata.forEach { (key, value) -> appendLine("$key: $value") }
        appendLine("Clock samples describe app playback and rendering; no microphone audio is recorded.")
        appendLine()
        appendLine("== events and highlight replay ==")
        encodedLines().forEach(::appendLine)
    }
}
