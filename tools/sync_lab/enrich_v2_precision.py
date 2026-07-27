#!/usr/bin/env python3
"""Enrich V2 rows with letter-level CTC keyframes + audible wasl tags.

Preserves word start/end (Lab gold onset gate) while replacing sparse keyframes
with character-edge acoustic points from forced alignment. Detects wasl using
orthographic nūn rules + continuous energy across the boundary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
sys.path.insert(0, str(LAB))

from aligners import ctc_force_align_words, load_mono_16k  # noqa: E402
from generate_timing_v2 import MODEL, MODEL_REVISION, keyframed_segments  # noqa: E402
from wasl_acoustic import apply_wasl_links, detect_wasl_links  # noqa: E402

GENERATOR_NOTE = "enrich_v2_precision.py@1"


def load_words(db: Path, surah: int, ayah: int) -> list[str]:
    with sqlite3.connect(db) as con:
        return [
            row[0]
            for row in con.execute(
                "SELECT arabic FROM words WHERE surah_id=? AND ayah_number=? "
                "ORDER BY position",
                (surah, ayah),
            )
        ]


def densify_keyframes_from_ctc(
    audio: Path,
    words: list[str],
    word_segments: list[dict],
    model_id: str,
) -> list[dict] | None:
    """Keep Lab/V2 word spans; fill letter keyframes from full-ayah CTC."""
    try:
        ctc_segs, score, abs_kf = ctc_force_align_words(
            audio,
            words,
            model_id=model_id,
            return_keyframes=True,
            strict_target=True,
        )
    except Exception:
        return None
    if not ctc_segs or len(ctc_segs) != len(words) or score < -2.0:
        return None
    # Rebase CTC absolute letter times into the *authoritative* word spans.
    absolute = []
    for segs_word, points in zip(word_segments, abs_kf):
        start = int(segs_word["startMs"])
        end = int(segs_word["endMs"])
        clipped = []
        for abs_ms, progress in points:
            # Keep only points inside the authoritative span (with slight slack).
            if abs_ms < start - 30 or abs_ms > end + 30:
                continue
            clipped.append([max(start, min(end, round(abs_ms))), float(progress)])
        if not clipped:
            clipped = [[end, 1.0]]
        else:
            # Ensure terminal progress at span end.
            if clipped[-1][1] < 1.0 - 1e-6:
                clipped.append([end, 1.0])
            else:
                clipped[-1][0] = end
                clipped[-1][1] = 1.0
        absolute.append(clipped)

    spans = [
        [int(s["position"]), int(s["startMs"]), int(s["endMs"])]
        for s in word_segments
    ]
    rebuilt = keyframed_segments(spans, absolute)
    if not rebuilt or len(rebuilt) != len(word_segments):
        return None
    return rebuilt


def _already_letter_dense(segments: list[dict]) -> bool:
    """True when most spans already carry multi-point acoustic keyframes."""
    if not segments:
        return False
    multi = sum(1 for s in segments if len(s.get("keyframes") or []) > 1)
    return multi >= max(1, int(0.7 * len(segments)))


def enrich_row(
    row: dict,
    words: list[str],
    audio: Path,
    model_id: str,
    y: np.ndarray | None = None,
    sr: int | None = None,
    *,
    force_letter: bool = False,
) -> dict:
    segs = row["segments"]
    letter_ok = _already_letter_dense(segs)
    dense = None
    if force_letter or not letter_ok:
        dense = densify_keyframes_from_ctc(audio, words, segs, model_id)
        if dense is not None:
            segs = dense
            letter_ok = True
    if y is None:
        y, sr = load_mono_16k(audio)
    links = detect_wasl_links(y, sr, segs, words)
    segs = apply_wasl_links(segs, links)
    out = dict(row)
    out["segments"] = segs
    out["precision"] = {
        "letterKeyframes": letter_ok,
        "waslLinks": len(links),
        "tool": GENERATOR_NOTE,
    }
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--in", dest="inp", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument(
        "--model",
        default=f"{MODEL}@{MODEL_REVISION}",
    )
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--surah", type=int)
    parser.add_argument(
        "--force-letter",
        action="store_true",
        help="Re-run CTC letter densify even when multi-point keyframes already exist",
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        help="JSONL resume file (surah:ayah lines already done)",
    )
    args = parser.parse_args()

    payload = json.loads(args.inp.read_text(encoding="utf-8"))
    rows_in = payload["rows"]
    if args.surah:
        rows_in = [r for r in rows_in if int(r["surah"]) == args.surah]
    if args.limit:
        rows_in = rows_in[: args.limit]

    done: dict[tuple[int, int], dict] = {}
    if args.checkpoint and args.checkpoint.exists():
        for line in args.checkpoint.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            done[(int(rec["surah"]), int(rec["ayah"]))] = rec["row"]
        print(f"resumed {len(done)} rows from {args.checkpoint}")

    out_rows = []
    letter_ok = wasl_n = 0
    ckpt = args.checkpoint.open("a", encoding="utf-8") if args.checkpoint else None
    for i, row in enumerate(rows_in):
        surah, ayah = int(row["surah"]), int(row["ayah"])
        key = (surah, ayah)
        if key in done:
            enriched = done[key]
            out_rows.append(enriched)
            if enriched.get("precision", {}).get("letterKeyframes"):
                letter_ok += 1
            wasl_n += int(enriched.get("precision", {}).get("waslLinks") or 0)
            continue
        words = load_words(args.db, surah, ayah)
        audio = args.audio_dir / f"{surah:03d}{ayah:03d}.mp3"
        if not audio.exists() or len(words) == 0:
            out_rows.append(row)
            continue
        enriched = enrich_row(
            row, words, audio, args.model, force_letter=args.force_letter
        )
        if enriched.get("precision", {}).get("letterKeyframes"):
            letter_ok += 1
        wasl_n += int(enriched.get("precision", {}).get("waslLinks") or 0)
        # refresh audio hash if missing
        if not enriched.get("audioSha256"):
            enriched["audioSha256"] = hashlib.sha256(audio.read_bytes()).hexdigest()
        out_rows.append(enriched)
        if ckpt:
            ckpt.write(
                json.dumps({"surah": surah, "ayah": ayah, "row": enriched}) + "\n"
            )
            ckpt.flush()
        if (i + 1) % 50 == 0:
            print(
                f"progress {i+1}/{len(rows_in)} letter={letter_ok} wasl_links={wasl_n}",
                flush=True,
            )
    if ckpt:
        ckpt.close()

    payload = dict(payload)
    payload["rows"] = out_rows
    payload["precisionEnrichment"] = {
        "tool": GENERATOR_NOTE,
        "letterKeyframeRows": letter_ok,
        "waslLinkTotal": wasl_n,
        "rows": len(out_rows),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(
        f"Wrote {args.out}: {len(out_rows)} rows, "
        f"letter-keyframes={letter_ok}, wasl-links={wasl_n}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
