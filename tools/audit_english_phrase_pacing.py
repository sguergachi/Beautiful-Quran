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
import com.beautifulquran.data.model.Segment
import com.beautifulquran.ui.reader.*
import java.io.File

private val coreWords = Regex("\\p{L}[\\p{L}\\p{M}]*(?:['’][\\p{L}\\p{M}]+)*")

fun main(args: Array<String>) {
    val corpus = File(args[0]).readLines().associate { line ->
        val row = line.split('\t'); row[0] to row
    }
    val cache = HashMap<String, EnglishAlignment>()
    var newCollapses = 0
    File(args[2], "core-collapses.tsv").bufferedWriter().use { cores ->
    File(args[2], "core-crossings.tsv").bufferedWriter().use { crossings ->
    File(args[2], "exposure.tsv").bufferedWriter().use { exposures ->
        File(args[2], "plans.tsv").bufferedWriter().use { plans ->
            exposures.appendLine("reciter\tverse\tword\ttext\tstart_ms\tend_ms\tsemantic_owner\tend_minus_owner_end_ms")
            plans.appendLine("reciter\tverse\tblocks\treordered_blocks\trepeat_fallbacks\tearly_words\tlate_words\tsource_overlap_fallbacks\tcontained_cores\tcrossing_cores\tcore_collapses\tnew_collapses\tsource_order_skips\trepeat_collapses\tother_legacy_collapses\twhitespace_collapses")
            cores.appendLine("reciter\tverse\ttext\tfrom\tto\tband_owner\tstart_ms\tend_ms\tlegacy_start_ms\tlegacy_end_ms\treason")
            crossings.appendLine("reciter\tverse\ttext\tfrom\tto\tplan_contained\tlegacy_contained")
            File(args[1]).forEachLine { line ->
                val (reciter, key, encoded, voiceStart) = line.split('\t')
                val row = corpus[key] ?: error("Missing verse $key")
                val text = row[1]
                val alignment = cache.getOrPut(key) {
                    EnglishWordAlignment.align(text, row[2].split('|'), row[3].split('|'), row[4].split('|'))!!
                }
                val segments = encoded.split(';').map {
                    val (p, s, e) = it.split(',').map(String::toLong); Segment(p.toInt(), s, e)
                }
                val plan = englishPresentationPlan(alignment, segments, voiceStart.toLong())
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
                plans.appendLine("$reciter\t$key\t${plan.spans.size}\t$reordered\t${plan.repeatFallbacks}\t$early\t$late\t${plan.sourceOverlapFallbacks}\t$coreCount\t$crossingCount\t$collapsed\t$introduced\t$skips\t$repeats\t$others\t$whitespaceCollapses")
            }
        }
    }
    }
    }
    check(newCollapses == 0) { "$newCollapses newly collapsed letter cores with positive legacy exposure" }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("timings_cache", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).resolve().parents[1] / "app/src/main/java/com/beautifulquran"
    names = ["domain/EnglishWordAlignment.kt", "domain/EnglishPhrase.kt", "domain/EnglishTypography.kt",
             "domain/ArabicNormalize.kt", "data/model/Models.kt", "ui/reader/EnglishWashPlan.kt",
             "ui/reader/EnglishWashWindow.kt"]
    sources = [source / name for name in names]
    with sqlite3.connect(f"file:{args.timings_cache.resolve()}?mode=ro", uri=True) as database:
        revisions = dict(database.execute("SELECT reciter_id, revision FROM timing_state"))
        with tempfile.TemporaryDirectory(prefix="bq-phrase-audit-") as directory:
            directory = Path(directory)
            timings = directory / "timings.tsv"
            with timings.open("w") as stream:
                for reciter, surah, ayah, encoded, onset in database.execute(
                    "SELECT reciter_id,surah_id,ayah_number,segments,audio_onset_ms FROM timing_rows ORDER BY reciter_id,surah_id,ayah_number"
                ):
                    segments = json.loads(encoded)
                    if not segments:
                        raise ValueError(f"Empty timing row: {reciter} {surah}:{ayah}")
                    stream.write(f"{reciter}\t{surah}:{ayah}\t" + ";".join(",".join(map(str, row)) for row in segments)
                                 + f"\t{max(onset, segments[0][1])}\n")
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
            subprocess.run(["java", "-jar", str(jar), str(args.corpus), str(timings), str(args.output_dir)], check=True)
    rows = [row.split("\t") for row in (args.output_dir / "plans.tsv").read_text().splitlines()[1:]]
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
        "semantic_accuracy_established": False,
        "readability_certified": False,
        "legacy_window_source_sha256": hashlib.sha256(clock.read_bytes()).hexdigest(),
        "timing_revisions": revisions,
        "corpus_sha256": hashlib.sha256(args.corpus.read_bytes()).hexdigest(),
        "source_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in zip(names, sources)},
    }
    (args.output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({key: value for key, value in summary.items() if key not in ("source_sha256", "timing_revisions")}))


if __name__ == "__main__":
    main()
