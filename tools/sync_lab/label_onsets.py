#!/usr/bin/env python3
"""Fill frozen independent onset labels (waveform protocol).

Usage:
  # list pending
  python tools/sync_lab/label_onsets.py --list

  # write labels for one ayah (starts are audio-file ms; positions 1..N, repeats allowed)
  python tools/sync_lab/label_onsets.py --surah 1 --ayah 1 \\
      --segments '[[1,60,600],[2,600,1380],[3,1380,2510],[4,2510,5970]]'

  # export a CSV scaffold for offline spectrogram tools
  python tools/sync_lab/label_onsets.py --export-csv /tmp/label_queue.csv

Never pass V2 predictions into --segments. Label from waveform only.
"""
from __future__ import annotations

import argparse
import csv
import json
import sqlite3
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
DEFAULT = LAB / "independent_labels" / "frozen_sample_v1.json"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def save(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2) + "\n")


def word_count(db: Path, surah: int, ayah: int) -> int:
    with sqlite3.connect(db) as con:
        row = con.execute(
            "SELECT COUNT(*) FROM words WHERE surah_id=? AND ayah_number=?",
            (surah, ayah),
        ).fetchone()
    return int(row[0]) if row else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=DEFAULT)
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--export-csv", type=Path)
    parser.add_argument("--surah", type=int)
    parser.add_argument("--ayah", type=int)
    parser.add_argument("--segments", type=str, help="JSON list of [pos,startMs,endMs]")
    parser.add_argument("--annotator", type=str, default="human")
    args = parser.parse_args()

    if not args.file.exists():
        raise SystemExit(f"missing {args.file}; run freeze_label_sample.py first")
    payload = load(args.file)

    if args.list or args.export_csv:
        rows = payload["edits"]
        pending = [e for e in rows if e.get("labelStatus") != "done"]
        if args.list:
            for e in pending:
                print(
                    f"{e['surahId']}:{e['ayah']} split={e['split']} "
                    f"stratum={e['stratum']} status={e.get('labelStatus')}"
                )
            print(f"{len(pending)} pending / {len(rows)} total")
        if args.export_csv:
            with args.export_csv.open("w", newline="", encoding="utf-8") as fh:
                w = csv.writer(fh)
                w.writerow(
                    [
                        "surah",
                        "ayah",
                        "split",
                        "stratum",
                        "audioSha256",
                        "wordCount",
                        "labelStatus",
                        "segments_json",
                    ]
                )
                for e in rows:
                    n = word_count(args.db, e["surahId"], e["ayah"])
                    w.writerow(
                        [
                            e["surahId"],
                            e["ayah"],
                            e["split"],
                            e["stratum"],
                            e["audioSha256"],
                            n,
                            e.get("labelStatus"),
                            json.dumps(e.get("segments")),
                        ]
                    )
            print(f"Wrote {args.export_csv}")
        return 0

    if args.surah is None or args.ayah is None or not args.segments:
        raise SystemExit("need --surah --ayah --segments, or --list / --export-csv")

    segments = json.loads(args.segments)
    n = word_count(args.db, args.surah, args.ayah)
    positions = [int(s[0]) for s in segments]
    if not positions or min(positions) < 1:
        raise SystemExit("invalid positions")
    # first pass must cover 1..n in order (grammar)
    expected, seen = 1, set()
    for p in positions:
        if p not in seen:
            if p != expected:
                raise SystemExit(f"grammar: expected new position {expected}, got {p}")
            seen.add(p)
            expected += 1
    if expected != n + 1:
        raise SystemExit(f"grammar: first pass ends at {expected - 1}, need {n}")
    for a, b in zip(segments, segments[1:]):
        if int(a[1]) >= int(b[1]) or int(a[2]) <= int(a[1]):
            raise SystemExit("non-monotonic or empty span")

    found = False
    for edit in payload["edits"]:
        if edit["surahId"] == args.surah and edit["ayah"] == args.ayah:
            edit["segments"] = segments
            edit["labelStatus"] = "done"
            edit["annotator"] = args.annotator
            found = True
            break
    if not found:
        raise SystemExit("ayah not in frozen sample")
    save(args.file, payload)
    print(f"Labeled {args.surah}:{args.ayah} ({len(segments)} spans)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
