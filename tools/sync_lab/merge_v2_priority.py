#!/usr/bin/env python3
"""Merge V2 lanes with Lab gold highest priority (no key collisions).

Priority (high → low):
  1. Lab gold (Timings Lab ground truth)
  2. Full QUA Alafasy (structure + letters; phrase re-says preserved)
  3. CTC mono auto — gap-fill only (never invents structure over QUA)

Mono CTC must not override QUA/Lab structure — see docs/V2_STRUCTURE.md.

Writes non-overlapping JSON files into --out-dir for build_db load_timing_v2.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path


def has_repeat(positions: list[int]) -> bool:
    return len(positions) != len(set(positions))


def is_repeat(row: dict) -> bool:
    return has_repeat([int(segment["position"]) for segment in row["segments"]])


def keys_of(rows: list[dict]) -> set[tuple[int, int]]:
    return {(int(r["surah"]), int(r["ayah"])) for r in rows}


def load_v1_replays(db: Path) -> tuple[dict[tuple[int, int], list[list[int]]], str]:
    """Return Alafasy's repeat topology and a reproducible source revision."""
    with sqlite3.connect(db) as connection:
        source_rows = list(connection.execute(
            """
            SELECT t.surah_id, t.ayah_number, t.segments, COUNT(w.position)
            FROM timings t JOIN words w
              ON w.surah_id=t.surah_id AND w.ayah_number=t.ayah_number
            WHERE t.reciter_id=1
            GROUP BY t.surah_id, t.ayah_number, t.segments
            """
        ))
    rows = {}
    for surah, ayah, raw, word_count in source_rows:
        segments = json.loads(raw)
        positions = [segment[0] for segment in segments]
        firsts = []
        for position in positions:
            if position not in firsts:
                firsts.append(position)
        if has_repeat(positions) and firsts == list(range(1, word_count + 1)):
            rows[(surah, ayah)] = segments
    revision = hashlib.sha256(
        json.dumps(sorted(rows.items()), separators=(",", ":")).encode()
    ).hexdigest()
    return rows, revision


def v1_fallback(row: dict, segments: list[list[int]]) -> dict:
    """Keep a V1 re-say when an acoustic lane flattened its topology."""
    return {
        "surah": row["surah"],
        "ayah": row["ayah"],
        "gateScore": 1.0,
        "audioSha256": row["audioSha256"],
        "segments": [
            {
                "position": position,
                "startMs": start,
                "endMs": end,
                "keyframes": [{"offsetMs": end - start, "progress": 1.0}],
            }
            for position, start, end in segments
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lab-gold", type=Path, required=True)
    parser.add_argument("--ctc", type=Path, required=True)
    parser.add_argument("--qua", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument(
        "--v1-db",
        type=Path,
        help="Preserve V1 re-says when an acoustic lane is mono",
    )
    args = parser.parse_args()

    lab = json.loads(args.lab_gold.read_text(encoding="utf-8"))
    ctc = json.loads(args.ctc.read_text(encoding="utf-8"))
    qua = json.loads(args.qua.read_text(encoding="utf-8"))

    lab_rows = lab["rows"]
    claimed = keys_of(lab_rows)
    v1_replays, v1_revision = (
        load_v1_replays(args.v1_db) if args.v1_db else ({}, "")
    )
    fallback_rows = []

    def retain(row: dict) -> bool:
        key = int(row["surah"]), int(row["ayah"])
        v1 = v1_replays.get(key)
        if v1 and not is_repeat(row):
            fallback_rows.append(v1_fallback(row, v1))
            return False
        return True

    # Full QUA structure+letters (not repeats-only). Lab still wins ties.
    qua_rows = [
        r for r in qua["rows"]
        if (int(r["surah"]), int(r["ayah"])) not in claimed and retain(r)
    ]
    claimed |= keys_of(qua["rows"])
    n_qua_repeats = sum(1 for r in qua_rows if is_repeat(r))

    # CTC only fills keys neither Lab nor QUA claimed.
    ctc_rows = [
        r for r in ctc["rows"]
        if (int(r["surah"]), int(r["ayah"])) not in claimed and retain(r)
    ]
    claimed |= keys_of(ctc["rows"])
    for r in ctc_rows:
        r.pop("confidence", None)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    lab_out = dict(lab)
    lab_out["rows"] = lab_rows
    (args.out_dir / "alafasy_lab_gold.json").write_text(
        json.dumps(lab_out, ensure_ascii=False, indent=2) + "\n"
    )
    qua_out = dict(qua)
    qua_out["rows"] = qua_rows
    (args.out_dir / "alafasy_qua_full.json").write_text(
        json.dumps(qua_out, ensure_ascii=False, indent=2) + "\n"
    )
    # Retire the old repeats-only artifact so load_timing_v2 does not double-load.
    stale = args.out_dir / "alafasy_qua_repeats.json"
    if stale.exists():
        stale.unlink()
    ctc_out = dict(ctc)
    ctc_out["rows"] = ctc_rows
    (args.out_dir / "alafasy_ctc_auto.json").write_text(
        json.dumps(ctc_out, ensure_ascii=False, indent=2) + "\n"
    )
    fallback_out = args.out_dir / "alafasy_v1_replay_fallback.json"
    if fallback_rows:
        fallback_out.write_text(json.dumps({
            "schema": 2,
            "generator": "sync_lab/merge_v2_priority.py@1",
            "source": "quran-v1 repeat topology",
            "sourceRevision": v1_revision,
            "reciterId": 1,
            "minimumGateScore": 1.0,
            "rows": fallback_rows,
        }, ensure_ascii=False, indent=2) + "\n")
    elif fallback_out.exists():
        fallback_out.unlink()
    total = len(lab_rows) + len(qua_rows) + len(ctc_rows) + len(fallback_rows)
    print(
        f"lab_gold={len(lab_rows)} qua_full={len(qua_rows)} "
        f"(repeats={n_qua_repeats}) ctc_gap={len(ctc_rows)} "
        f"v1_replay_fallback={len(fallback_rows)} total={total}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
