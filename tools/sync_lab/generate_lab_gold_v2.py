#!/usr/bin/env python3
"""Emit Timing V2 rows from Timings Lab ground truth (historical patches).

Lab edits are the product ear-verified ground truth for those ayahs. This lane
does not invent times: it freezes grammar-valid Alafasy Lab segments into V2
keyframe form so the parallel timings_v2 fork can be scored against Lab as
unit tests at ≥99% within 100 ms.

Grammar-invalid bulk imports are excluded (they violate 1..N first-pass).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
GENERATOR = "sync_lab/generate_lab_gold_v2.py@1"
SOURCE = "timing_lab/historical_manual_patches"
HIST = LAB / "historical_manual_patches.json"


def grammar_valid(positions: list[int], word_count: int) -> bool:
    expected, seen = 1, set()
    for position in positions:
        if position not in seen:
            if position != expected:
                return False
            seen.add(position)
            expected += 1
    return expected == word_count + 1 and all(1 <= p <= word_count for p in positions)


def linear_keyframes(start_ms: int, end_ms: int) -> list[dict]:
    """Minimal acoustic curve: single end anchor (wash uses exact Lab span)."""
    duration = end_ms - start_ms
    if duration <= 0:
        return []
    return [{"offsetMs": duration, "progress": 1.0}]


def audio_sha(audio_dir: Path, surah: int, ayah: int) -> str | None:
    path = audio_dir / f"{surah:03d}{ayah:03d}.mp3"
    if not path.exists():
        return None
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument(
        "--hist",
        type=Path,
        default=HIST,
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=ROOT / "tools/timing_v2/alafasy_lab_gold.json",
    )
    args = parser.parse_args()

    hist_bytes = args.hist.read_bytes()
    source_revision = hashlib.sha256(hist_bytes).hexdigest()
    payload_in = json.loads(hist_bytes.decode("utf-8"))

    with sqlite3.connect(args.db) as con:
        word_counts = {
            (int(s), int(a)): int(c)
            for s, a, c in con.execute(
                "SELECT surah_id, ayah_number, COUNT(*) FROM words GROUP BY 1, 2"
            )
        }

    rows = []
    skipped = {"not_alafasy": 0, "grammar": 0, "bad_span": 0, "no_audio": 0}
    for edit in payload_in["edits"]:
        if int(edit.get("reciterId", 1)) != 1:
            skipped["not_alafasy"] += 1
            continue
        surah, ayah = int(edit["surahId"]), int(edit["ayah"])
        raw = edit["segments"]
        positions = [int(seg[0]) for seg in raw]
        n = word_counts.get((surah, ayah))
        if n is None or not grammar_valid(positions, n):
            skipped["grammar"] += 1
            continue
        segments = []
        ok = True
        last_start = -1
        for pos, start, end in raw:
            start, end = int(start), int(end)
            if start <= last_start or end <= start:
                ok = False
                break
            kfs = linear_keyframes(start, end)
            if not kfs:
                ok = False
                break
            segments.append({
                "position": int(pos),
                "startMs": start,
                "endMs": end,
                "keyframes": kfs,
            })
            last_start = start
        if not ok:
            skipped["bad_span"] += 1
            continue
        sha = audio_sha(args.audio_dir, surah, ayah)
        if sha is None:
            skipped["no_audio"] += 1
            continue
        rows.append({
            "surah": surah,
            "ayah": ayah,
            "gateScore": 1.0,
            "audioSha256": sha,
            "labSource": edit.get("source_file") or edit.get("commit") or "historical",
            "segments": segments,
        })

    rows.sort(key=lambda r: (r["surah"], r["ayah"]))
    out = {
        "schema": 2,
        "reciterId": 1,
        "reciter": "Alafasy_128kbps",
        "generator": GENERATOR,
        "source": SOURCE,
        "sourceRevision": source_revision,
        "minimumGateScore": 1.0,
        "labGold": {
            "historicalPath": str(args.hist.relative_to(ROOT)),
            "grammarValidRows": len(rows),
            "skipped": skipped,
            "note": (
                "Timings Lab ground truth for unit tests. Structure + starts are "
                "frozen Lab segments; keyframes are end-anchors only."
            ),
        },
        "rows": rows,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    print(
        f"Wrote {args.out}: {len(rows)} Lab-gold rows "
        f"(skipped {skipped})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
