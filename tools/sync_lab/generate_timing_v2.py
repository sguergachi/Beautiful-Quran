#!/usr/bin/env python3
"""Generate confidence-gated acoustic sub-word timings; no manual patches.

The CTC path aligns canonical Quran words directly to recitation audio and
retains every character end as a reveal keyframe. Rows below the confidence
gate are omitted so the app can fall back to bundled V1.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
import urllib.request
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
MODEL = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic"
MODEL_REVISION = "af46c2d8531b8dcbb5e23b952f739b372c2e5d2d"
GENERATOR = "sync_lab/generate_timing_v2.py@3"


def load_words(db: Path, surah: int, ayah: int) -> list[str]:
    with sqlite3.connect(db) as con:
        return [
            row[0] for row in con.execute(
                "SELECT arabic FROM words WHERE surah_id=? AND ayah_number=? ORDER BY position",
                (surah, ayah),
            )
        ]


def keyframed_segments(
    segments: list[list[int]],
    absolute_keyframes: list[list[list[float]]],
) -> list[dict]:
    """Rebase acoustic ends onto final spans, abstaining instead of clamping."""
    if len(segments) != len(absolute_keyframes):
        return []
    out = []
    for (position, start, end), raw in zip(segments, absolute_keyframes):
        duration = end - start
        if duration <= 0 or not raw:
            return []
        points = []
        previous_offset = 0
        previous_progress = 0.0
        for absolute_ms, progress in raw:
            offset = round(absolute_ms) - start
            progress = round(float(progress), 6)
            if offset == 0 and progress == 0.0 and not points:
                continue
            if (
                offset <= previous_offset
                or offset > duration
                or progress < previous_progress
                or progress > 1.0
            ):
                return []
            point = {"offsetMs": offset, "progress": round(float(progress), 6)}
            points.append(point)
            previous_offset, previous_progress = offset, progress
        if points[-1]["progress"] != 1.0:
            return []
        out.append({
            "position": position,
            "startMs": start,
            "endMs": end,
            "keyframes": points,
        })
    return out


def reconcile_acoustic_boundaries(
    ctc_segments: list[list[int]],
    refined_segments: list[list[int]],
    absolute_keyframes: list[list[list[float]]],
) -> list[list[int]]:
    """Keep onset/silence refinements only while they contain CTC evidence."""
    if not (
        len(ctc_segments) == len(refined_segments) == len(absolute_keyframes)
    ):
        return []
    if any(not points for points in absolute_keyframes):
        return []

    out = [segment[:] for segment in refined_segments]
    first_start = round(absolute_keyframes[0][0][0])
    if out[0][1] > first_start:
        out[0][1] = ctc_segments[0][1]
    if out[0][1] > first_start:
        return []

    for i in range(len(out) - 1):
        boundary = out[i + 1][1]
        previous_end = round(absolute_keyframes[i][-1][0])
        next_first_start = round(absolute_keyframes[i + 1][0][0])
        if not previous_end <= boundary <= next_first_start:
            boundary = ctc_segments[i + 1][1]
        if not previous_end <= boundary <= next_first_start:
            return []
        out[i][2] = boundary
        out[i + 1][1] = boundary

    last_end = round(absolute_keyframes[-1][-1][0])
    if out[-1][2] < last_end:
        out[-1][2] = ctc_segments[-1][2]
    if out[-1][2] < last_end:
        return []
    return out


def audio_file(directory: Path, slug: str, surah: int, ayah: int) -> Path:
    path = directory / f"{surah:03d}{ayah:03d}.mp3"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(
            f"https://everyayah.com/data/{slug}/{surah:03d}{ayah:03d}.mp3",
            path,
        )
    return path


def align_ayah(audio: Path, words: list[str]) -> tuple[list[dict], float]:
    sys.path.insert(0, str(LAB))
    from aligners import (  # noqa: PLC0415
        ctc_force_align_words,
        load_mono_16k,
        refine_starts_to_onsets,
        snap_lead_in,
        trim_trailing_silence,
    )

    ctc_segments, score, keyframes = ctc_force_align_words(
        audio,
        words,
        model_id=f"{MODEL}@{MODEL_REVISION}",
        return_keyframes=True,
        strict_target=True,
    )
    wave, rate = load_mono_16k(audio)
    segments = snap_lead_in(ctc_segments, audio, y=wave, sr=rate)
    segments = refine_starts_to_onsets(
        segments, audio, window_ms=40, y=wave, sr=rate
    )
    segments = trim_trailing_silence(segments, audio)
    segments = reconcile_acoustic_boundaries(ctc_segments, segments, keyframes)
    return keyframed_segments(segments, keyframes), score


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reciter", default="Alafasy_128kbps")
    parser.add_argument("--reciter-id", type=int, default=1)
    parser.add_argument("--surah", type=int, required=True)
    parser.add_argument("--ayah-from", type=int, default=1)
    parser.add_argument("--ayah-to", type=int, required=True)
    parser.add_argument("--min-path-score", type=float, default=-1.0)
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=None,
    )
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    rows = []
    audio_dir = args.audio_dir or LAB / "audio" / args.reciter
    for ayah in range(args.ayah_from, args.ayah_to + 1):
        words = load_words(args.db, args.surah, ayah)
        audio = audio_file(audio_dir, args.reciter, args.surah, ayah)
        segments, score = align_ayah(audio, words)
        accepted = score >= args.min_path_score and len(segments) == len(words)
        print(
            f"{args.surah}:{ayah} score={score:.3f} "
            f"{'accepted' if accepted else 'abstained'}"
        )
        if accepted:
            rows.append({
                "surah": args.surah,
                "ayah": ayah,
                "gateScore": score,
                "audioSha256": hashlib.sha256(audio.read_bytes()).hexdigest(),
                "segments": segments,
            })

    payload = {
        "schema": 2,
        "reciterId": args.reciter_id,
        "reciter": args.reciter,
        "generator": GENERATOR,
        "source": MODEL,
        "sourceRevision": MODEL_REVISION,
        "minimumGateScore": args.min_path_score,
        "rows": rows,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    print(f"Wrote {args.out} ({len(rows)} accepted rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
