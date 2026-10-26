#!/usr/bin/env python3
"""Exercise the production phrase plan against an authorized local corpus/timing cache.

The corpus TSV has key, translation, glosses, QAC lemmas and Arabic (| separated).
Outputs remain local: word exposure offsets are diagnostics, not semantic certification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile


PROBE = r'''
import com.beautifulquran.domain.EnglishWordAlignment
import com.beautifulquran.domain.EnglishAlignment
import com.beautifulquran.domain.ReviewedEnglishAlignment
import com.beautifulquran.domain.EnglishVerseAlignments
import com.beautifulquran.data.model.*
import com.beautifulquran.domain.AudibleSilence
import com.beautifulquran.data.model.Segment
import com.beautifulquran.ui.reader.*
import java.io.File

private val coreWords = Regex("\\p{L}[\\p{L}\\p{M}]*(?:['’][\\p{L}\\p{M}]+)*")

fun main(args: Array<String>) {
    val corpus = File(args[0]).readLines().associate { line ->
        val row = line.split('\t'); row[0] to row
    }
    val cache = HashMap<String, EnglishAlignment>()
    val reviewed = if (args.size > 3) File(args[3]).bufferedReader().use { ReviewedEnglishAlignment.read(it.lineSequence()) } else emptyMap()
    check(reviewed.keys.all { it in corpus }) { "Reviewed artifact contains verses outside the corpus" }
    var cardChecks = 0
    var newCollapses = 0
    File(args[2], "word-finishes.tsv").bufferedWriter().use { finishes ->
    File(args[2], "pace-diagnostics.tsv").bufferedWriter().use { paces ->
    finishes.appendLine("reciter\tverse\tword\towner\tdesired_ms\teffective_ms\tactual_ms\tsource_end_ms\tvoiced_end_ms\tlead_reason\tforward_finish_ms\tforward_phase\tforward_tolerance_chars")
    paces.appendLine("reciter\tverse\tliteral_matches\taccepted\tdeferred\tminimum_velocity\tmaximum_velocity\trejections")
    File(args[2], "core-collapses.tsv").bufferedWriter().use { cores ->
    File(args[2], "core-crossings.tsv").bufferedWriter().use { crossings ->
    File(args[2], "exposure.tsv").bufferedWriter().use { exposures ->
        File(args[2], "plans.tsv").bufferedWriter().use { plans ->
            exposures.appendLine("reciter\tverse\tword\ttext\tstart_ms\tend_ms\tsemantic_owner\tend_minus_owner_end_ms")
            plans.appendLine("reciter\tverse\tblocks\treordered_blocks\trepeat_fallbacks\tearly_words\tlate_words\tsource_overlap_fallbacks\tcontained_cores\tcrossing_cores\tcore_collapses\tnew_collapses\tsource_order_skips\trepeat_collapses\tother_legacy_collapses\twhitespace_collapses\treading_flow\treading_fallback\trate_checks\tpause_checks\tblank_compression\tblank_compression_fallback")
            cores.appendLine("reciter\tverse\ttext\tfrom\tto\tband_owner\tstart_ms\tend_ms\tlegacy_start_ms\tlegacy_end_ms\treason")
            crossings.appendLine("reciter\tverse\ttext\tfrom\tto\tplan_contained\tlegacy_contained")
            File(args[1]).forEachLine { line ->
                val (reciter, key, encoded, voiceStart, quiet) = line.split('\t')
                val row = corpus[key] ?: error("Missing verse $key")
                val text = row[1]
                val alignment = cache.getOrPut(key) {
                    reviewed[key]?.let { record ->
                        val arabic = row[4].split('|'); val glosses = row[2].split('|'); val lemmas = row[3].split('|')
                        val direct = ReviewedEnglishAlignment.alignment(text, arabic, record, glosses)
                            ?: error("Stale reviewed record $key")
                        val (surah, ayah) = key.split(':').map(String::toInt)
                        val verse = Ayah(surah, ayah, arabic.joinToString(" "), text,
                            words = arabic.mapIndexed { index, value -> Word(index + 1, value, glosses[index], "", lemma = lemmas[index]) })
                        val content = SurahContent(Surah(surah, "", "", "", "", 1), listOf(verse),
                            aiEnglishAlignmentRecords = mapOf(ayah to record))
                        val selected = EnglishVerseAlignments(content, useAiReviewed = true).alignment(ayah)!!
                        check(selected.usesAiReviewedRecord && selected.spans == direct.spans &&
                            selected.phrases == direct.phrases && selected.timingWords == direct.timingWords) { "$key lookup bypass" }
                        val cards = EnglishVerseAlignments(content, wordByWord = true).alignment(ayah)!!
                        val toggledCards = EnglishVerseAlignments(content, wordByWord = true, useAiReviewed = true).alignment(ayah)!!
                        check(!toggledCards.usesAiReviewedRecord && cards.phrases == null && toggledCards.phrases == null &&
                            cards.spans == toggledCards.spans && cards.wordEnds.contentEquals(toggledCards.wordEnds)) { "$key word-card change" }
                        cardChecks++
                        selected
                    } ?: EnglishWordAlignment.align(text, row[2].split('|'), row[3].split('|'), row[4].split('|'))!!
                }
                val segments = encoded.split(';').map {
                    val (p, s, e) = it.split(',').map(String::toLong); Segment(p.toInt(), s, e)
                }
                val silence = if (quiet.isEmpty()) emptyList() else quiet.split(';').map {
                    val (s, e) = it.split(',').map(String::toLong); AudibleSilence(s, e)
                }
                val plan = englishPresentationPlan(alignment, segments, voiceStart.toLong(), silence)
                check(plan.spans.first().from == 0f && plan.spans.last().to == 1f) { "$reciter $key ends" }
                plan.spans.zipWithNext().forEach { (a, b) -> check(a.to == b.from) { "$key hole" } }
                val windows = plan.spans.map { plan.windows.getValue(it.position) }
                windows.zipWithNext().forEach { (a, b) -> check(a.endMs <= b.startMs) {
                    "$reciter $key overlap $a $b; phrases=${alignment.presentationSpans}; effective=${plan.spans}"
                } }
                var last = 0f
                for (step in 0..100) {
                    val at = segments.last().endMs * step / 100
                    val edge = englishWashThrough(plan, at)!!
                    check(edge >= last) { "$reciter $key retreat" }; last = edge
                }
                var rateChecks = 0; var pauseChecks = 0
                plan.readingFlow?.let { flow ->
                    val clock = flow.clock
                    val audible = clock.spoken.orEmpty()
                    val duration = audible.sumOf { it.last - it.first }
                    val pace = flow.pace
                    check(pace?.hasPositiveVelocity() != false) { "$reciter $key nonpositive pace" }
                    val boundaries = (segments.map { it.startMs } + plan.windows.values.map { it.endMs } +
                        pace?.finishes.orEmpty().map { it.deadlineMs }).distinct()
                    for (at in boundaries) {
                        if (audible.none { at - 1 >= it.first && at + 1 <= it.last }) continue
                        val delta = flow.ink(englishWashThrough(plan, at + 1)!!) - flow.ink(englishWashThrough(plan, at - 1)!!)
                        val before = clock.progress(at - 1)
                        val after = clock.progress(at + 1)
                        val expected = (pace?.through(after) ?: after) - (pace?.through(before) ?: before)
                        check(delta >= 0 && kotlin.math.abs(delta - expected) < .00001f) { "$reciter $key projection reset at $at: $delta vs $expected" }
                        if (pace?.finishes.isNullOrEmpty()) check(kotlin.math.abs(delta - 2f / duration) < .00001f)
                        rateChecks++
                    }
                    audible.zipWithNext().forEach { (a, b) ->
                        val held = englishWashThrough(plan, a.last)!!
                        for (at in listOf(a.last, (a.last + b.first) / 2, b.first)) {
                            check(kotlin.math.abs(englishWashThrough(plan, at)!! - held) < .000001f) { "$reciter $key quiet moves" }
                        }
                        check(kotlin.math.abs(flow.mediaAt(held) - a.last) <= 1) { "$reciter $key earliest pause finish" }
                        check(kotlin.math.abs(flow.mediaAt(held, afterGap = true) - b.first) <= 1) { "$reciter $key latest pause start ${flow.mediaAt(held, afterGap = true)} vs ${b.first}" }
                        pauseChecks++
                    }
                    for (finish in pace?.finishes.orEmpty()) {
                        val word = finish.word
                        val actual = englishWordExposure(plan, word.from, word.to)!!.endMs
                        check(kotlin.math.abs(actual - finish.deadlineMs) <= 1) { "$reciter $key finish drift $finish vs $actual" }
                        val start = kotlin.math.round(word.from * text.length).toInt()
                        val end = kotlin.math.round(word.to * text.length).toInt()
                        fun phase(at: Long) = englishPaperInkWordProgress(englishWashThrough(plan, at)!! * text.length, start, end)
                        // Normalized Float coordinates are projected back to
                        // long-verse character offsets before word division.
                        val toleranceChars = 4f * Math.ulp(word.to) * text.length
                        val complete = 1f - toleranceChars / (end - start).coerceAtLeast(1)
                        val value = phase(finish.deadlineMs)
                        check(value >= complete) { "$reciter $key forward word incomplete $finish: $value vs $complete" }
                        var low = clock.startMs
                        var high = clock.endMs
                        while (low < high) {
                            val mid = (low + high) ushr 1
                            if (phase(mid) >= complete) high = mid else low = mid + 1
                        }
                        check(kotlin.math.abs(low - finish.deadlineMs) <= 1) { "$reciter $key forward finish drift $finish vs $low" }
                        val label = text.substring(kotlin.math.round(word.from * text.length).toInt(),
                            kotlin.math.round(word.to * text.length).toInt())
                        finishes.appendLine("$reciter\t$key\t$label\t${word.position}\t${finish.desiredMs}\t${finish.deadlineMs}\t$actual\t${finish.sourceEndMs}\t${finish.voicedEndMs}\t${finish.leadReason}\t$low\t$value\t$toleranceChars")
                    }
                    paces.appendLine("$reciter\t$key\t${alignment.timingWords.size}\t${pace?.finishes?.size ?: 0}\t${pace?.deferredMatches ?: 0}\t${pace?.velocityBounds?.first ?: 1}\t${pace?.velocityBounds?.second ?: 1}\t${pace?.rejected.orEmpty().entries.joinToString(";") { "${it.key}=${it.value}" }}")
                }
                val legacy = EnglishWashPlan(alignment.washSpans,
                    englishWashWindows(alignment.washSpans.map { it.position }, segments).mapValues { (owner, window) ->
                        if (owner == 1) window.copy(startMs = maxOf(window.startMs, voiceStart.toLong()).coerceAtMost(window.endMs)) else window
                    })
                var coreCount = 0; var crossingCount = 0; var collapsed = 0; var introduced = 0
                var skips = 0; var repeats = 0; var others = 0
                coreWords.findAll(text).forEach { word ->
                    val from = word.range.first.toFloat() / text.length
                    val to = (word.range.last + 1f) / text.length
                    fun contained(candidate: EnglishWashPlan) = candidate.spans.any {
                        from >= it.from - .00001f && to <= it.to + .00001f
                    }
                    val inPlan = contained(plan); val inLegacy = contained(legacy)
                    if (!inPlan || !inLegacy) {
                        crossingCount++
                        crossings.appendLine("$reciter\t$key\t${word.value}\t$from\t$to\t$inPlan\t$inLegacy")
                        return@forEach
                    }
                    coreCount++
                    val current = englishWordExposure(plan, from, to)!!
                    if (current.endMs > current.startMs) return@forEach
                    collapsed++
                    val old = englishWordExposure(legacy, from, to)!!
                    val band = plan.spans.first { to <= it.to + .00001f }
                    val first = segments.indexOfFirst { it.position >= band.position }
                    val reason = when {
                        old.endMs > old.startMs -> { introduced++; "NEW_COLLAPSE" }
                        first >= 0 && segments[first].position > band.position &&
                            segments.drop(first + 1).any { it.position == band.position } -> {
                            skips++; "legacy_source_order_skip"
                        }
                        segments.zipWithNext().any { (a, b) -> b.position <= a.position } -> {
                            repeats++; "legacy_repeat_control"
                        }
                        else -> { others++; "legacy_other" }
                    }
                    cores.appendLine("$reciter\t$key\t${word.value}\t$from\t$to\t${band.position}\t${current.startMs}\t${current.endMs}\t${old.startMs}\t${old.endMs}\t$reason")
                }
                newCollapses += introduced
                var early = 0; var late = 0; var whitespaceCollapses = 0
                Regex("\\S+").findAll(text).forEachIndexed { index, word ->
                    val from = word.range.first.toFloat() / text.length
                    val to = (word.range.last + 1f) / text.length
                    val window = englishWordExposure(plan, from, to)!!
                    check(window.endMs >= window.startMs) { "$reciter $key negative exposure ${word.value}" }
                    if (window.endMs == window.startMs) whitespaceCollapses++
                    val owner = alignment.positionAt((from + to) / 2)
                    val sourceEnd = segments.firstOrNull { it.position >= owner }?.endMs
                    val delta = sourceEnd?.let { window.endMs - it }
                    if (delta != null && delta < -100) early++
                    if (delta != null && delta > 100) late++
                    exposures.appendLine("$reciter\t$key\t${index + 1}\t${word.value}\t${window.startMs}\t${window.endMs}\t$owner\t${delta ?: ""}")
                }
                val reordered = alignment.presentationSpans.count { it.lastPosition > it.position }
                plans.appendLine("$reciter\t$key\t${plan.spans.size}\t$reordered\t${plan.repeatFallbacks}\t$early\t$late\t${plan.sourceOverlapFallbacks}\t$coreCount\t$crossingCount\t$collapsed\t$introduced\t$skips\t$repeats\t$others\t$whitespaceCollapses\t${if (plan.readingFlow != null) 1 else 0}\t${plan.readingFlowFallback ?: ""}\t$rateChecks\t$pauseChecks\t${if (plan.readingFlow?.extent != null) 1 else 0}\t${plan.readingFlow?.extentFallback ?: ""}")
            }
        }
    }
    }
    }
    }
    }
    check(newCollapses == 0) { "$newCollapses newly collapsed letter cores with positive legacy exposure" }
    val loaded = reviewed.keys.filter { it in cache }
    File(args[2], "lookup.json").writeText("""{"reviewed_verses_loaded":${loaded.size},"card_isolation_checks":$cardChecks,"unexercised_reviewed_verses":${reviewed.size - loaded.size}}""")
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("timings_cache", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--alignments", type=Path, help="Exercise a compiled ledger through the actual reviewed-record loader")
    parser.add_argument("--chapters", help="Comma-separated chapter numbers; omitted means the complete corpus")
    parser.add_argument("--include-bundled", action="store_true", help="Also exercise independent bundled voices; never fill withheld runtime rows")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).resolve().parents[1] / "app/src/main/java/com/beautifulquran"
    names = ["domain/EnglishWordAlignment.kt", "domain/ReviewedEnglishAlignment.kt", "domain/EnglishPhrase.kt", "domain/EnglishTypography.kt",
             "domain/ArabicNormalize.kt", "data/model/Models.kt", "ui/reader/EnglishWashPlan.kt",
             "ui/reader/EnglishWashWindow.kt", "ui/reader/EnglishReadingFlow.kt", "ui/reader/EnglishReadingExtent.kt",
             "ui/reader/EnglishReadingPace.kt", "domain/VerseOpening.kt"]
    names.append("ui/reader/PaperInkEnglishWindows.kt")
    sources = [source / name for name in names]
    chapters = set(map(int, args.chapters.split(','))) if args.chapters else None
    with sqlite3.connect(f"file:{args.timings_cache.resolve()}?mode=ro", uri=True) as database:
        revisions = dict(database.execute("SELECT reciter_id, revision FROM timing_state"))
        timing_rows = list(database.execute(
            "SELECT reciter_id,surah_id,ayah_number,segments,audio_onset_ms FROM timing_rows ORDER BY reciter_id,surah_id,ayah_number"))
        if args.include_bundled:
            bundled_path = Path(__file__).resolve().parents[1] / "data/quran.db"
            bundled_revision = "bundled:" + hashlib.sha256(bundled_path.read_bytes()).hexdigest()
            with sqlite3.connect(f"file:{bundled_path}?mode=ro", uri=True) as bundled:
                runtime_voices = set(revisions)
                for row in bundled.execute("SELECT reciter_id,surah_id,ayah_number,segments,audio_onset_ms FROM timings"):
                    if row[0] not in runtime_voices:
                        timing_rows.append(row)
                        revisions[row[0]] = bundled_revision
            timing_rows.sort(key=lambda row: row[:3])
        silence_by_key = {}
        silence_sources = {}
        for reciter in revisions:
            asset = source.parents[2] / "assets/silence" / f"{reciter}.txt"
            if not asset.exists():
                silence_sources[reciter] = None
                continue
            silence_sources[reciter] = hashlib.sha256(asset.read_bytes()).hexdigest()
            for line in asset.read_text().splitlines():
                if not line or line.startswith("#"):
                    continue
                surah, ayah, start, end = map(int, line.split())
                silence_by_key.setdefault((reciter, surah, ayah), []).append((start, end))
        with tempfile.TemporaryDirectory(prefix="bq-phrase-audit-") as directory:
            directory = Path(directory)
            timings = directory / "timings.tsv"
            with timings.open("w") as stream:
                for reciter, surah, ayah, encoded, onset in timing_rows:
                    if chapters is not None and surah not in chapters:
                        continue
                    segments = json.loads(encoded)
                    if not segments:
                        raise ValueError(f"Empty timing row: {reciter} {surah}:{ayah}")
                    stream.write(f"{reciter}\t{surah}:{ayah}\t" + ";".join(",".join(map(str, row)) for row in segments)
                                 + f"\t{max(onset, segments[0][1])}\t"
                                 + ";".join(f"{start},{end}" for start, end in silence_by_key.get((reciter, surah, ayah), [])) + "\n")
            # Compile the actual legacy function, not a second implementation
            # of its repeat/next-owner fences. Its surrounding file needs UI types.
            clock = source / "ui/reader/EnglishWashClock.kt"
            legacy_source = clock.read_text().split("internal fun englishWashWindows(", 1)[1].split(
                "/** A word onset", 1)[0]
            legacy = directory / "Legacy.kt"
            legacy.write_text("package com.beautifulquran.ui.reader\nimport com.beautifulquran.data.model.Segment\n"
                              + "internal fun englishWashWindows(" + legacy_source)
            probe = directory / "Probe.kt"
            probe.write_text(PROBE)
            jar = directory / "probe.jar"
            subprocess.run(["kotlinc", *map(str, sources), str(legacy), str(probe), "-include-runtime", "-d", str(jar)], check=True)
            subprocess.run(["java", "-jar", str(jar), str(args.corpus), str(timings), str(args.output_dir),
                            *([str(args.alignments)] if args.alignments else [])], check=True)
    rows = [row.split("\t") for row in (args.output_dir / "plans.tsv").read_text().splitlines()[1:]]
    pace_rows = [row.split("\t") for row in (args.output_dir / "pace-diagnostics.tsv").read_text().splitlines()[1:]]
    finish_rows = [row.split("\t") for row in (args.output_dir / "word-finishes.tsv").read_text().splitlines()[1:]]
    rejected = {}
    for row in pace_rows:
        for item in filter(None, row[7].split(";")):
            reason, count = item.split("=")
            rejected[reason] = rejected.get(reason, 0) + int(count)
    summary = {
        "timing_rows": len(rows), "verses": len({row[1] for row in rows}), "reciters": sorted(revisions),
        "reordered_blocks": sum(int(row[3]) for row in rows),
        "repeat_fallbacks": sum(int(row[4]) for row in rows),
        "source_overlap_fallback_rows": sum(int(row[7]) for row in rows),
        "source_overlap_fallback_verses": len({row[1] for row in rows if int(row[7])}),
        "contained_letter_cores": sum(int(row[8]) for row in rows),
        "crossing_letter_cores": sum(int(row[9]) for row in rows),
        "collapsed_letter_cores": sum(int(row[10]) for row in rows),
        "new_positive_legacy_collapses": sum(int(row[11]) for row in rows),
        "legacy_source_order_skips": sum(int(row[12]) for row in rows),
        "legacy_repeat_collapses": sum(int(row[13]) for row in rows),
        "other_legacy_collapses": sum(int(row[14]) for row in rows),
        "collapsed_whitespace_tokens": sum(int(row[15]) for row in rows),
        "words_over_100ms_before_semantic_owner_end": sum(int(row[5]) for row in rows),
        "words_over_100ms_after_semantic_owner_end": sum(int(row[6]) for row in rows),
        "steady_reading_rows": sum(int(row[16]) for row in rows),
        "reading_flow_fallback_rows": {reason: sum(row[17] == reason for row in rows)
                                       for reason in sorted({row[17] for row in rows if row[17]})},
        "shared_projection_checks": sum(int(row[18]) for row in rows),
        "pause_plateau_checks": sum(int(row[19]) for row in rows),
        "blank_compression_rows": sum(int(row[20]) for row in rows),
        "blank_compression_fallback_rows": {reason: sum(row[21] == reason for row in rows)
                                             for reason in sorted({row[21] for row in rows if row[21]})},
        "literal_matches_in_reading_rows": sum(int(row[2]) for row in pace_rows),
        "accepted_word_finishes": len(finish_rows),
        "deferred_literal_matches": rejected,
        "accepted_finish_reasons": {reason: sum(row[9] == reason for row in finish_rows)
                                    for reason in sorted({row[9] for row in finish_rows})},
        "maximum_finish_error_ms": max((abs(int(row[5]) - int(row[6])) for row in finish_rows), default=0),
        "maximum_forward_finish_error_ms": max((abs(int(row[5]) - int(row[10])) for row in finish_rows), default=0),
        "minimum_forward_word_phase_at_deadline": min((float(row[11]) for row in finish_rows), default=1),
        "maximum_forward_tolerance_chars": max((float(row[12]) for row in finish_rows), default=0),
        "minimum_relative_velocity": min((float(row[5]) for row in pace_rows), default=1),
        "maximum_relative_velocity": max((float(row[6]) for row in pace_rows), default=1),
        "measured_silence_sources": silence_sources,
        "semantic_accuracy_established": False,
        "readability_certified": False,
        "legacy_window_source_sha256": hashlib.sha256(clock.read_bytes()).hexdigest(),
        "timing_revisions": revisions,
        "corpus_sha256": hashlib.sha256(args.corpus.read_bytes()).hexdigest(),
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in zip(names, sources)},
        "alignment_artifact_sha256": hashlib.sha256(args.alignments.read_bytes()).hexdigest() if args.alignments else None,
        **json.loads((args.output_dir / "lookup.json").read_text()),
    }
    (args.output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    if args.alignments and not args.chapters and summary["unexercised_reviewed_verses"]:
        raise ValueError("Compiled records were not all exercised by the supplied timing population")
    print(json.dumps({key: value for key, value in summary.items() if key not in ("source_sha256", "timing_revisions")}))


if __name__ == "__main__":
    main()
