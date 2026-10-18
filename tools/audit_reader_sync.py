#!/usr/bin/env python3
"""Check every shipped timing boundary, seek order, repeat and English prefix.

Run audit_english_alignment.py first on an external runtime corpus; its
ownership.tsv is input here. Neither audit changes or packages runtime data.
This verifies timing/ink properties, not acoustic or translation accuracy.
"""
import argparse
import collections
import hashlib
import json
import sqlite3
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("corpus", type=Path)
parser.add_argument("ownership", type=Path)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--runtime-pack", type=Path, action="append", default=[], help="Reviewed canonical QF timing pack, kept outside Git; repeat for all six resources")
parser.add_argument("--v2-output", type=Path, help="Export an external TSV for HighlightCorpusTest; no restricted content is committed")
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
source = repo / "app/src/main/java/com/beautifulquran"
corpus = {key: glosses.split('|') for key, _, glosses in
          (line.split('\t') for line in args.corpus.read_text().splitlines())}
assert len(corpus) == 6236 and sum(map(len, corpus.values())) == 77429

probe = r'''
import com.beautifulquran.data.model.Segment
import com.beautifulquran.domain.HighlightEngine
import java.io.File

private data class Span(val from: Int, val to: Int, val owner: Int)
private fun spans(text: String) = text.split(';').map {
    val (a, b, p) = it.split(',').map(String::toInt)
    Span(a, b, p)
}

fun main(args: Array<String>) {
    val owners = File(args[0]).readLines().associate { line ->
        val (key, semantic, wash) = line.split('\t')
        key to (spans(semantic) to spans(wash))
    }
    var rows = 0L; var segmentsChecked = 0L; var queries = 0L; var repeats = 0L
    File(args[1]).forEachLine { line ->
        val (reciter, key, words, encoded) = line.split('\t')
        val count = words.toInt()
        val segments = encoded.split(';').map {
            val (p, start, end) = it.split(',').map(String::toLong)
            Segment(p.toInt(), start, end)
        }
        val label = "$reciter/$key"
        check(segments.all { it.position in 1..count && it.startMs >= 0 && it.endMs > it.startMs }) { "$label physics" }
        check(segments.zipWithNext().all { (a, b) -> a.startMs < b.startMs }) { "$label clock" }
        check(segments.map { it.position }.toSet() == (1..count).toSet()) { "$label missing Arabic word" }
        val prepared = HighlightEngine.PreparedTimings.prepare(segments)
        val (semantic, wash) = owners.getValue(key)
        val clocks = segments.flatMap { listOf(it.startMs - 1, it.startMs,
            (it.startMs + it.endMs) / 2, it.endMs - 1, it.endMs) }.distinct()
        // Reverse and scrambled queries model backward seeks and quick reversals;
        // their result must depend on the queried clock, never query history.
        for (order in listOf(clocks, clocks.reversed(), clocks.sortedBy { (it * 48271) % 2147483647 })) {
            for (clock in order) {
                val expectedIndex = if (clock >= segments.last().endMs) -1
                    else segments.indexOfLast { it.startMs <= clock }
                val actual = prepared.activeInfo(clock)
                if (expectedIndex < 0) {
                    check(actual == null) { "$label/$clock should wait" }
                } else {
                    val expected = segments[expectedIndex]
                    check(actual != null && actual.position == expected.position && actual.startMs == expected.startMs &&
                        actual.endMs == expected.endMs) { "$label/$clock word" }
                    val prefix = segments.take(expectedIndex + 1)
                    val highWater = prefix.maxOf { it.position }
                    val repeat = prefix.dropLast(1).any { it.position >= expected.position }
                    check(actual.highWater == highWater && actual.isRepeat == repeat) { "$label/$clock retained ink" }
                    val lastNew = prefix.indices.last { index ->
                        prefix.take(index).all { it.position < prefix[index].position }
                    }
                    val repeatStart = if (repeat) prefix.drop(lastNew + 1).minOf { it.position } else expected.position
                    check(actual.repeatStart == repeatStart) { "$label/$clock repeat chain" }
                    val holdEnd = segments.getOrNull(expectedIndex + 1)?.startMs ?: expected.endMs
                    check(actual.holdEndMs == holdEnd && actual.nextPosition == segments.getOrNull(expectedIndex + 1)?.position) {
                        "$label/$clock handoff"
                    }
                    val reached = wash.filter { it.owner <= highWater }
                    val expectedEnd = semantic.filter { it.owner <= highWater }.maxOfOrNull { it.to } ?: 0
                    check((reached.lastOrNull()?.to ?: 0) == expectedEnd &&
                        (reached.isEmpty() || reached.first().from == 0) &&
                        reached.zipWithNext().all { (a, b) -> a.to == b.from }) { "$label/$clock English gap" }
                }
                queries++
            }
        }
        repeats += segments.indices.count { index -> segments.take(index).any { it.position >= segments[index].position } }
        segmentsChecked += segments.size; rows++
    }
    println("""{"timing_rows":$rows,"segments":$segmentsChecked,"boundary_and_seek_queries":$queries,"repeat_occurrences":$repeats}""")
}
'''
with sqlite3.connect(f"file:{repo / 'data/quran.db'}?mode=ro", uri=True) as database:
    rows = database.execute("SELECT reciter_id,surah_id,ayah_number,segments FROM timings ORDER BY reciter_id,surah_id,ayah_number").fetchall()
pack_revisions={}
occupied={row[:3] for row in rows}
for path in args.runtime_pack:
    pack=json.loads(path.read_text())
    reciter=pack['reciterId']
    assert pack['schemaVersion']==1 and pack['clock']=='everyayah-ms' and reciter in {1,2,3,4,5,7}
    assert reciter not in pack_revisions,(reciter,'duplicate runtime resource')
    canonical=json.dumps({'rows':pack['rows'],'withheldVerseKeys':pack['withheldVerseKeys']},separators=(',',':'))
    assert hashlib.sha256(canonical.encode()).hexdigest()==pack['revision'],(reciter,'runtime revision mismatch')
    pack_revisions[reciter]=pack['revision']
    for surah,ayah,segments,onset in pack['rows']:
        key=(reciter,surah,ayah)
        assert key not in occupied,(key,'mixed timing generations')
        assert segments and segments[0][1]>=onset>=0,(key,'runtime onset')
        occupied.add(key)
        rows.append((*key,json.dumps(segments)))
if args.v2_output:
    args.v2_output.parent.mkdir(parents=True, exist_ok=True)
    with args.v2_output.open('w') as output:
        for reciter, surah, ayah, encoded in sorted(rows):
            output.write(f'{reciter}\t{surah}\t{ayah}\t' +
                         ';'.join(','.join(map(str, segment)) for segment in json.loads(encoded)) + '\n')
reciters = sorted({row[0] for row in rows})
assert len(reciters) == 13
present = {row[:3] for row in rows}
missing = [(reciter, key) for reciter in reciters for key in corpus
           if (reciter, *map(int, key.split(':'))) not in present]
with tempfile.TemporaryDirectory(prefix="reader-sync-audit-") as temporary:
    directory = Path(temporary)
    (directory / "Probe.kt").write_text(probe)
    with (directory / "timings.tsv").open('w') as output:
        for reciter, surah, ayah, encoded in rows:
            key = f'{surah}:{ayah}'
            segments = json.loads(encoded)
            assert segments, (reciter, key, 'empty timings')
            output.write(f'{reciter}\t{key}\t{len(corpus[key])}\t' +
                         ';'.join(','.join(map(str, segment)) for segment in segments) + '\n')
    subprocess.run(["kotlinc", str(source / "domain/HighlightEngine.kt"),
                    str(source / "data/model/Models.kt"), str(directory / "Probe.kt"),
                    "-include-runtime", "-d", str(directory / "probe.jar")], check=True)
    result = subprocess.run(["java", "-jar", str(directory / "probe.jar"),
                             str(args.ownership), str(directory / "timings.tsv")],
                            capture_output=True, text=True, check=True)
    summary = json.loads(result.stdout)
summary['reciters'] = reciters
summary['unavailable_timing_rows'] = missing
summary['rows_per_reciter'] = dict(collections.Counter(row[0] for row in rows))
summary['runtime_pack_revisions'] = pack_revisions
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
