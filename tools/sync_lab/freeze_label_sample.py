#!/usr/bin/env python3
"""Freeze the independent ear-label sample *before* any threshold tuning.

Writes a split membership file with audio hashes. Labels themselves are filled
later by tools/sync_lab/label_onsets.py — this only locks which ayahs are test
vs validation so 99% cannot be p-hacked.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import sqlite3
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]


def audio_sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=20260727)
    parser.add_argument("--random-ayahs", type=int, default=100)
    parser.add_argument("--repeat-ayahs", type=int, default=20)
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--v2",
        type=Path,
        default=ROOT / "tools/timing_v2/alafasy_qua.json",
    )
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=LAB / "independent_labels" / "frozen_sample_v1.json",
    )
    args = parser.parse_args()

    payload = json.loads(args.v2.read_text(encoding="utf-8"))
    mono, repeats = [], []
    for row in payload["rows"]:
        pos = [s["position"] for s in row["segments"]]
        item = (int(row["surah"]), int(row["ayah"]))
        if len(pos) != len(set(pos)):
            repeats.append(item)
        else:
            mono.append(item)

    rng = random.Random(args.seed)
    # Also sample from full mushaf for structure coverage outside V2
    with sqlite3.connect(args.db) as con:
        all_ayahs = [
            (int(s), int(a))
            for s, a in con.execute(
                "SELECT DISTINCT surah_id, ayah_number FROM words ORDER BY 1, 2"
            )
        ]
    rng.shuffle(all_ayahs)
    rng.shuffle(mono)
    rng.shuffle(repeats)

    chosen_random = []
    for key in all_ayahs:
        if len(chosen_random) >= args.random_ayahs:
            break
        path = args.audio_dir / f"{key[0]:03d}{key[1]:03d}.mp3"
        if path.exists():
            chosen_random.append(key)

    chosen_repeat = []
    for key in repeats + mono:
        if len(chosen_repeat) >= args.repeat_ayahs:
            break
        # prefer known V2 repeat rows, else any
        if key in chosen_random:
            continue
        path = args.audio_dir / f"{key[0]:03d}{key[1]:03d}.mp3"
        if path.exists():
            chosen_repeat.append(key)

    # 70/30 test/val by hash of key (stable)
    edits = []
    for split_name, keys in (("random", chosen_random), ("repeat_challenge", chosen_repeat)):
        for surah, ayah in keys:
            path = args.audio_dir / f"{surah:03d}{ayah:03d}.mp3"
            digest = audio_sha(path)
            bucket = "test" if int(digest[:8], 16) % 10 < 7 else "validation"
            edits.append({
                "reciterId": 1,
                "reciterSlug": "Alafasy_128kbps",
                "surahId": surah,
                "ayah": ayah,
                "stratum": split_name,
                "split": bucket,
                "audioSha256": digest,
                "segments": None,
                "labelStatus": "pending",
            })

    payload_out = {
        "schema": 1,
        "independent": True,
        "frozen": True,
        "seed": args.seed,
        "protocol": "docs/V2_99_PROTOCOL.md",
        "rules": [
            "Do not retune generator thresholds against the test split.",
            "Label on waveform/spectrogram; no real-time ear tapping.",
            "Never show V2 predicted starts while labeling.",
            "Double-label at least 15% of test rows.",
        ],
        "edits": edits,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload_out, indent=2) + "\n")
    n_test = sum(1 for e in edits if e["split"] == "test")
    n_val = sum(1 for e in edits if e["split"] == "validation")
    print(
        f"Wrote {args.out}: {len(edits)} ayahs "
        f"(test={n_test}, validation={n_val}, "
        f"random={len(chosen_random)}, repeat={len(chosen_repeat)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
