#!/usr/bin/env python3
"""Check authenticated QF timing snapshots against pinned offline source inputs.

This is a source-parity gate, not a timing importer or a redistribution grant.
It never changes the reader database. A different source must pass the existing
offline pipeline and acoustic verdict gate before it can replace shipped rows.
"""

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import sys

from build_db import CACHE, QDC_REPEAT_RECITERS, QDC_SOURCE_SHA256, RECITERS


def source_hash(rows):
    """Use the same numeric verse ordering and encoding as the pinned QDC files."""
    ordered = dict(sorted(rows.items(), key=lambda item: tuple(map(int, item[0].split(":")))))
    return hashlib.sha256(json.dumps(ordered, separators=(",", ":")).encode()).hexdigest()


def number(value):
    """Accept QF's integer-valued floats without accepting bools or nonfinite times."""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError("QF timing value must be a finite number")
    return int(value)


def snapshot_rows(snapshot, reciter_id):
    """Rebase chapter-clock tuples exactly as the legacy loader does; keep every occurrence."""
    if (snapshot.get("resource_group") != "chapter_recitations" or
            snapshot.get("resource_id") != reciter_id or snapshot.get("schema_version") != 1):
        raise ValueError(f"Unexpected QF snapshot identity/schema for reciter {reciter_id}")
    rows, seen = {}, set()
    for row in snapshot["records"]:
        if row["record_type"] == "chapter_audio_file":
            continue
        if row["record_type"] != "audio_segment":
            raise ValueError(f"Unexpected QF timing record type: {row['record_type']}")
        key = row["verse_key"]
        surah, ayah = map(int, key.split(":"))
        if (key != f"{surah}:{ayah}" or not 1 <= surah <= 114 or ayah < 1 or
                row["audio_recitation_id"] != reciter_id or
                row["chapter_id"] != surah or row["verse_number"] != ayah):
            raise ValueError(f"QF timing owner mismatch: {key}")
        if key in seen:
            raise ValueError(f"Duplicate QF timing verse: {key}")
        seen.add(key)
        base = number(row["timestamp_from"])
        if base != row["timestamp_from"]:
            raise ValueError(f"Nonintegral QF verse origin: {key}")
        segments = row["segments"]
        if segments is None or segments == []:
            continue
        if not isinstance(segments, list):
            raise ValueError(f"QF segments must be an array: {key}")
        rebased = []
        for segment in segments:
            if not isinstance(segment, list):
                raise ValueError(f"QF segment must be an array: {key}")
            # QF includes unfinished labels with no end time; the pinned loader
            # skips those too. Never invent their missing boundaries.
            if len(segment) < 3:
                continue
            position, start, end = map(number, segment[:3])
            if position != segment[0]:
                raise ValueError(f"Nonintegral QF word position: {key}")
            rebased.append([position, start - base, end - base])
        if rebased:
            rows[key] = rebased
    return rows


def repeat_count(segments):
    """Count repeat-pass occurrences using the reader's high-water definition."""
    high_water, repeats = 0, 0
    for position, _, _ in segments:
        repeats += position <= high_water
        high_water = max(high_water, position)
    return repeats


def compare_sources(baseline, candidate):
    """Report every missing, added, reordered, relabeled, or retimed source row."""
    changes = {kind: [] for kind in ("missing", "added", "topology", "timestamp")}
    for key in sorted(baseline.keys() | candidate.keys(), key=lambda k: tuple(map(int, k.split(":")))):
        before, after = baseline.get(key), candidate.get(key)
        if before == after:
            continue
        kind = ("missing" if after is None else "added" if before is None else
                "timestamp" if [s[0] for s in before] == [s[0] for s in after] else "topology")
        changes[kind].append(key)
    return {
        "matchesPinnedSource": not any(changes.values()),
        "baselineRows": len(baseline),
        "candidateRows": len(candidate),
        "baselineRepeatRows": sum(repeat_count(s) > 0 for s in baseline.values()),
        "candidateRepeatRows": sum(repeat_count(s) > 0 for s in candidate.values()),
        "baselineRepeatOccurrences": sum(map(repeat_count, baseline.values())),
        "candidateRepeatOccurrences": sum(map(repeat_count, candidate.values())),
        "changedRows": sum(map(len, changes.values())),
        "changes": changes,
    }


def audit(snapshots_dir, qdc_cache):
    """Compare all six repeat-source reciters; reject a modified reference cache."""
    reciters = []
    names = {rid: name for rid, _, name, _ in RECITERS}
    for app_id, qf_id in QDC_REPEAT_RECITERS.items():
        reference = (qdc_cache / f"qdc_{qf_id}.json").read_bytes()
        if hashlib.sha256(reference).hexdigest() != QDC_SOURCE_SHA256[qf_id]:
            raise ValueError(f"Reference cache does not match the pinned QDC source: {qf_id}")
        snapshot = (snapshots_dir / f"snapshot-{qf_id}.json").read_bytes()
        body = json.loads(snapshot)
        rows = snapshot_rows(body, qf_id)
        reciters.append({
            "appReciterId": app_id,
            "qfReciterId": qf_id,
            "name": names[app_id],
            "snapshotSha256": hashlib.sha256(snapshot).hexdigest(),
            "syncSequence": body["sync_sequence"],
            "recordCounts": dict(Counter(r["record_type"] for r in body["records"])),
            "incompleteSourceSegments": sum(
                len(segment) < 3
                for row in body["records"] if row["record_type"] == "audio_segment"
                for segment in row["segments"] or []
            ),
            "pinnedSourceSha256": QDC_SOURCE_SHA256[qf_id],
            "candidateSourceSha256": source_hash(rows),
            **compare_sources(json.loads(reference), rows),
        })
    return {
        "matchesPinnedSource": all(r["matchesPinnedSource"] for r in reciters),
        "comparedRows": sum(r["baselineRows"] for r in reciters),
        "changedRows": sum(r["changedRows"] for r in reciters),
        "reciters": reciters,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshots", type=Path, required=True, help="Directory of snapshot-<QF id>.json files")
    parser.add_argument("--qdc-cache", type=Path, default=CACHE, help="Existing hash-pinned qdc_<id>.json inputs")
    parser.add_argument("--report", type=Path, help="Write hashes, counts, and changed verse keys; no raw timing data")
    args = parser.parse_args(argv)
    try:
        report = audit(args.snapshots, args.qdc_cache)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"QF timing parity audit failed: {error}", file=sys.stderr)
        return 2
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    for reciter in report["reciters"]:
        counts = {kind: len(keys) for kind, keys in reciter["changes"].items() if keys}
        print(f"{reciter['name']}: {reciter['baselineRows']} rows, {reciter['changedRows']} changed {counts}")
    print(f"{report['comparedRows']} source rows compared; {report['changedRows']} changed. Reader DB untouched.")
    return 0 if report["matchesPinnedSource"] else 1


if __name__ == "__main__":
    sys.exit(main())
