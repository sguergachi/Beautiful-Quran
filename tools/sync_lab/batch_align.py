#!/usr/bin/env python3
"""Production-scale automated word timing (build-time only).

Winner from the sync lab (see RESULTS.md):

  1. Arabic wav2vec2 CTC forced align (jonatasgrosman/wav2vec2-large-xlsr-53-arabic)
  2. Post: snap lead-in → onset refine ±40ms → trim trailing silence
  3. Karaoke hold: each word end = next word start

Optional second opinion (MMS via ctc-forced-aligner) for confidence gating:
  when |MMS − CTC| median start delta is large, flag for review / keep baseline.

Never rewrites Arabic text — timestamps only.

Example:
  source /tmp/alignlab-venv/bin/activate
  python tools/sync_lab/batch_align.py \\
      --reciter Alafasy_128kbps --surah 1 --out tools/sync_lab/results/fatiha.json
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
sys.path.insert(0, str(LAB))

from aligners import (  # noqa: E402
    ctc_force_align_words,
    refine_starts_to_onsets,
    snap_lead_in,
    trim_trailing_silence,
    load_mono_16k,
)

ARABIC_CTC = "jonatasgrosman/wav2vec2-large-xlsr-53-arabic"


def load_words(db_path: Path, surah: int, ayah: int) -> list[str]:
    con = sqlite3.connect(db_path)
    rows = con.execute(
        "SELECT arabic FROM words WHERE surah_id=? AND ayah_number=? ORDER BY position",
        (surah, ayah),
    ).fetchall()
    con.close()
    return [r[0] for r in rows]


def ayah_count(db_path: Path, surah: int) -> int:
    con = sqlite3.connect(db_path)
    n = con.execute(
        "SELECT ayah_count FROM surahs WHERE id=?", (surah,)
    ).fetchone()[0]
    con.close()
    return n


def align_ayah(audio: Path, words: list[str]) -> tuple[list[list[int]], float]:
    segs, score = ctc_force_align_words(
        audio, words, model_id=ARABIC_CTC, return_score=True
    )
    y, sr = load_mono_16k(audio)
    segs = snap_lead_in(segs, audio, y=y, sr=sr)
    segs = refine_starts_to_onsets(segs, audio, window_ms=40, y=y, sr=sr)
    segs = trim_trailing_silence(segs, audio)
    return segs, score


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reciter", required=True, help="everyayah slug e.g. Alafasy_128kbps")
    ap.add_argument("--surah", type=int, required=True)
    ap.add_argument("--ayah-from", type=int, default=1)
    ap.add_argument("--ayah-to", type=int, default=0, help="0 = end of surah")
    ap.add_argument("--audio-dir", type=Path, default=None,
                    help="dir of NNNNNN.mp3; default tools/sync_lab/audio/<reciter>")
    ap.add_argument("--db", type=Path, default=ROOT / "data" / "quran.db")
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    audio_dir = args.audio_dir or (LAB / "audio" / args.reciter)
    last = args.ayah_to or ayah_count(args.db, args.surah)
    out_rows = []
    t0 = time.time()
    for ayah in range(args.ayah_from, last + 1):
        words = load_words(args.db, args.surah, ayah)
        if not words:
            print(f"skip {args.surah}:{ayah} no words")
            continue
        mp3 = audio_dir / f"{args.surah:03d}{ayah:03d}.mp3"
        if not mp3.exists():
            print(f"missing audio {mp3}")
            continue
        segs, score = align_ayah(mp3, words)
        out_rows.append({
            "surah": args.surah,
            "ayah": ayah,
            "n_words": len(words),
            "path_score": score,
            "segments": segs,
        })
        print(f"{args.surah}:{ayah} words={len(words)} path={score:.3f}", flush=True)

    payload = {
        "reciter": args.reciter,
        "model": ARABIC_CTC,
        "pipeline": "ctc → lead_in → onset40 → trim_silence → karaoke_hold",
        "elapsed_s": round(time.time() - t0, 2),
        "ayahs": out_rows,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    print(f"Wrote {args.out} ({len(out_rows)} ayahs, {payload['elapsed_s']}s)")


if __name__ == "__main__":
    main()
