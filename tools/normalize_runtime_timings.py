#!/usr/bin/env python3
"""Normalize one runtime recitation snapshot into the reader timing contract.

The backend feeds this script raw chapter-timing responses.  It deliberately
reuses the build pipeline's cleaner, clock rebase, repairs and physical gates:
the runtime cache must produce the same rows the karaoke engine historically
read from quran.db.  The committed database supplies only the independently
licensed quran-align reference clock and never receives the normalized output.
"""

from __future__ import annotations

import argparse
from contextlib import redirect_stdout
import json
from pathlib import Path
import sqlite3
import sys

from build_db import (
    COVERAGE_THRESHOLD,
    QDC_REPEAT_RECITERS,
    _covers_all_words,
    adjust_qdc_segments,
    apply_timing_corrections,
    apply_timing_repairs,
    finalize_timing_rows,
    fits_audio,
    load_audio_durations,
    load_audio_onsets,
    preserve_complete_repeat_topology,
    rebase_qdc_clock,
    refit_displaced_rows,
    sanitize_timing_row,
    trim_to_next_start,
)

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATABASE = ROOT / "data" / "quran.db"


def parse_source_chapters(chapters):
    """Accept legacy QDC, authenticated chapter-reciter, or snapshot records."""
    out = {}
    for chapter in chapters:
        if not isinstance(chapter, dict):
            raise ValueError("chapter response must be an object")
        if chapter.get("records") is not None:
            timing_rows = [
                row for row in chapter["records"]
                if row.get("record_type") == "audio_file" and row.get("segments")
            ]
        else:
            audio_file = chapter.get("audio_file")
            if audio_file is None:
                files = chapter.get("audio_files") or []
                audio_file = files[0] if files else None
            if not isinstance(audio_file, dict):
                raise ValueError("chapter response has no audio timing file")
            timing_rows = audio_file.get("timestamps") or audio_file.get("verse_timings") or []

        for row in timing_rows:
            verse_key = row.get("verse_key")
            if not isinstance(verse_key, str) or ":" not in verse_key:
                raise ValueError("timing row has no valid verse_key")
            surah, ayah = (int(part) for part in verse_key.split(":", 1))
            base = int(row.get("timestamp_from", 0))
            segments = [
                [int(segment[0]), int(segment[1]) - base, int(segment[2]) - base]
                for segment in (row.get("segments") or [])
                if len(segment) >= 3
            ]
            if segments:
                out[(surah, ayah)] = segments
    return out


def load_reference(database_path, reciter_id):
    db = sqlite3.connect(f"file:{database_path}?mode=ro", uri=True)
    provenance = dict(db.execute("SELECT key,value FROM data_provenance"))
    if provenance.get("qdc_delivery") != "runtime cache only":
        raise ValueError("reference database is not the QDC-free runtime baseline")
    word_counts = {
        (surah, ayah): count
        for surah, ayah, count in db.execute(
            "SELECT surah_id,ayah_number,COUNT(*) FROM words GROUP BY 1,2"
        )
    }
    word_text = {}
    for surah, ayah, position, arabic in db.execute(
        "SELECT surah_id,ayah_number,position,arabic FROM words"
    ):
        word_text.setdefault((surah, ayah), {})[position] = arabic
    references = {
        (reciter_id, surah, ayah): json.loads(segments)
        for surah, ayah, segments in db.execute(
            "SELECT surah_id,ayah_number,segments FROM timings "
            "WHERE reciter_id=?",
            (reciter_id,),
        )
    }
    db.close()
    return word_counts, word_text, references


def normalize_snapshot(app_reciter_id, chapters, database_path=DEFAULT_DATABASE):
    if app_reciter_id not in QDC_REPEAT_RECITERS:
        raise ValueError(f"reciter {app_reciter_id} has no runtime QDC source")
    raw = parse_source_chapters(chapters)
    word_counts, word_text, references = load_reference(database_path, app_reciter_id)
    covered = len(raw.keys() & word_counts.keys())
    if covered < COVERAGE_THRESHOLD:
        raise ValueError(
            f"runtime provider returned only {covered} recognized timed ayahs; "
            f"need at least {COVERAGE_THRESHOLD}"
        )
    durations = load_audio_durations()
    onsets = load_audio_onsets()
    timing_rows = []
    clock_offsets = {}
    file_clock_rows = set()
    singleton_gap_candidates = {}
    stats = {
        "zero_len": 0,
        "clamped": 0,
        "repeats": 0,
        "missing": 0,
        "opening_shift": 0,
        "merged_splits": 0,
        "dropped_strays": 0,
        "noncontiguous_orphans": 0,
        "gap_phantoms": 0,
        "clock_rebased": 0,
        "clock_abstained": 0,
        "quran_align_fallback": 0,
        "repeat_tail_clamped": 0,
    }

    for (surah, ayah), word_count in sorted(word_counts.items()):
        key = (app_reciter_id, surah, ayah)
        source = raw.get((surah, ayah))
        cleaned = adjust_qdc_segments(
            source,
            word_count,
            stats,
            words=word_text.get((surah, ayah)),
        )
        reference = references.get(key)
        duration = durations.get(key)
        if reference is None and not _covers_all_words(cleaned, word_count):
            rescue_stats = {name: 0 for name in stats}
            rescued = adjust_qdc_segments(
                source,
                word_count,
                rescue_stats,
                recover_singleton_gap=True,
                words=word_text.get((surah, ayah)),
            )
            if _covers_all_words(rescued, word_count) and fits_audio(rescued, duration):
                singleton_gap_candidates[key] = rescued
        rebased, offset = rebase_qdc_clock(cleaned, reference, duration)
        if offset is not None:
            file_clock_rows.add(key)
            if offset:
                stats["clock_rebased"] += 1
                clock_offsets[key] = offset
        elif cleaned and reference:
            stats["clock_abstained"] += 1
        preserved_repeat = None
        if offset is None and reference and not fits_audio(cleaned, duration):
            preserved_repeat = preserve_complete_repeat_topology(
                cleaned,
                word_count,
                onsets.get(key),
                duration,
            )
            if preserved_repeat:
                rebased = preserved_repeat
                stats["repeat_tail_clamped"] += 1
                file_clock_rows.add(key)
        if (
            offset is None
            and preserved_repeat is None
            and reference
            and not fits_audio(cleaned, duration)
            and fits_audio(reference, duration)
        ):
            rebased = sanitize_timing_row(trim_to_next_start(reference))
            if rebased:
                stats["quran_align_fallback"] += 1
                file_clock_rows.add(key)
        if rebased:
            timing_rows.append(
                (app_reciter_id, surah, ayah, json.dumps(rebased, separators=(",", ":")))
            )
        else:
            stats["missing"] += 1

    only_reciter = {app_reciter_id}
    timing_rows = apply_timing_corrections(
        timing_rows,
        only_reciter_ids=only_reciter,
    )
    timing_rows = apply_timing_repairs(
        timing_rows,
        word_counts,
        clock_offsets,
        durations,
        only_reciter_ids=only_reciter,
        skip_missing_boundary_keys=set(singleton_gap_candidates),
        word_text=word_text,
    )
    rescued_keys = set()
    if singleton_gap_candidates:
        recovered_rows = []
        for rid, surah, ayah, segments in timing_rows:
            key = (rid, surah, ayah)
            current = json.loads(segments) if isinstance(segments, str) else segments
            candidate = singleton_gap_candidates.get(key)
            if candidate and not _covers_all_words(current, word_counts[(surah, ayah)]):
                segments = json.dumps(candidate, separators=(",", ":"))
                rescued_keys.add(key)
            recovered_rows.append((rid, surah, ayah, segments))
        timing_rows = recovered_rows
    if rescued_keys:
        timing_rows = apply_timing_repairs(
            timing_rows,
            word_counts,
            clock_offsets,
            durations,
            only_reciter_ids=only_reciter,
            only_keys=rescued_keys,
            only_kinds={"boundary"},
            word_text=word_text,
        )
    timing_rows, _ = refit_displaced_rows(
        timing_rows,
        durations,
        onsets,
        file_clock_rows,
    )
    timing_rows, shipped_onsets = finalize_timing_rows(
        timing_rows,
        word_counts,
        references,
        durations,
        onsets,
        file_clock_rows,
    )

    return {
        "schema_version": 1,
        "resource_group": "recitations",
        "resource_id": app_reciter_id,
        "records": [
            {
                "record_type": "timing",
                "record_key": f"{surah}:{ayah}",
                "surah_id": surah,
                "ayah_number": ayah,
                "segments": json.loads(segments),
                "audio_onset_ms": shipped_onsets.get((rid, surah, ayah), 0),
            }
            for rid, surah, ayah, segments in timing_rows
        ],
        "diagnostics": stats,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, default=DEFAULT_DATABASE)
    args = parser.parse_args()
    request = json.load(sys.stdin)
    # Pipeline diagnostics remain useful in host logs, but stdout is a strict
    # machine-readable response consumed by the Node service.
    with redirect_stdout(sys.stderr):
        snapshot = normalize_snapshot(
            int(request["app_reciter_id"]),
            request["chapters"],
            args.database,
        )
    json.dump(snapshot, sys.stdout, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
