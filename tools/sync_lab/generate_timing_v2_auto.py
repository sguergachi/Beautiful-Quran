#!/usr/bin/env python3
"""Full-reciter automated Timing V2 from everyayah audio + CTC — no human labels.

Pipeline per ayah (mono structure 1..N):
  1. Pinned Arabic CTC forced alignment → word spans + acoustic keyframes
  2. Lead-in snap + energy refine + trailing trim (reconciled to CTC evidence)
  3. Automated confidence vs independent energy onsets
  4. Abstain below gate (app keeps V1)

Accuracy is measured automatically with post-pause energy gold
(`eval_v2_postpause_gold.py`), not human ear taps. Tune
`--min-onset-match-frac` until that metric clears the product bar on accepted
rows, then report accuracy and coverage separately.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
import time
from pathlib import Path

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
sys.path.insert(0, str(LAB))

from aligners import load_mono_16k  # noqa: E402
from auto_confidence import accept_row, row_confidence  # noqa: E402
from generate_timing_v2 import (  # noqa: E402
    GENERATOR as BASE_GENERATOR,
    MODEL,
    MODEL_REVISION,
    align_ayah,
    audio_file,
    load_words,
)

GENERATOR = "sync_lab/generate_timing_v2_auto.py@1"


def all_ayahs(db: Path) -> list[tuple[int, int]]:
    with sqlite3.connect(db) as con:
        return [
            (int(s), int(a))
            for s, a in con.execute(
                "SELECT DISTINCT surah_id, ayah_number FROM words ORDER BY 1, 2"
            )
        ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reciter", default="Alafasy_128kbps")
    parser.add_argument("--reciter-id", type=int, default=1)
    parser.add_argument("--db", type=Path, default=ROOT / "data/quran.db")
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=LAB / "audio" / "Alafasy_128kbps",
    )
    parser.add_argument("--surah", type=int, help="optional single surah")
    parser.add_argument("--ayah-from", type=int, default=1)
    parser.add_argument("--ayah-to", type=int)
    parser.add_argument("--min-path-score", type=float, default=-1.5)
    parser.add_argument(
        "--min-onset-match-frac",
        type=float,
        default=0.85,
        help="Fraction of word starts that must sit within 40ms of an energy onset",
    )
    parser.add_argument("--onset-match-ms", type=float, default=40.0)
    parser.add_argument("--checkpoint", type=Path, help="JSONL progress file")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    if args.surah:
        last = args.ayah_to
        if last is None:
            with sqlite3.connect(args.db) as con:
                last = int(
                    con.execute(
                        "SELECT MAX(ayah_number) FROM words WHERE surah_id=?",
                        (args.surah,),
                    ).fetchone()[0]
                )
        work = [(args.surah, a) for a in range(args.ayah_from, last + 1)]
    else:
        work = all_ayahs(args.db)
    if args.limit:
        work = work[: args.limit]

    done: dict[tuple[int, int], dict] = {}
    if args.checkpoint and args.checkpoint.exists():
        for line in args.checkpoint.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("status") == "accepted":
                done[(row["surah"], row["ayah"])] = row["payload"]
        print(f"resumed {len(done)} accepted from {args.checkpoint}")

    rows: list[dict] = list(done.values())
    accepted = len(rows)
    abstained = 0
    t0 = time.time()
    ckpt = args.checkpoint.open("a", encoding="utf-8") if args.checkpoint else None

    for index, (surah, ayah) in enumerate(work):
        if (surah, ayah) in done:
            continue
        words = load_words(args.db, surah, ayah)
        audio = audio_file(args.audio_dir, args.reciter, surah, ayah)
        try:
            segments, score = align_ayah(audio, words)
        except Exception as exc:
            record = {
                "surah": surah,
                "ayah": ayah,
                "status": "error",
                "error": type(exc).__name__,
            }
            if ckpt:
                ckpt.write(json.dumps(record) + "\n")
                ckpt.flush()
            abstained += 1
            continue

        ok = (
            score >= args.min_path_score
            and segments
            and len(segments) == len(words)
        )
        conf = None
        if ok:
            y, sr = load_mono_16k(audio)
            starts = [int(s["startMs"]) for s in segments]
            conf = row_confidence(
                starts,
                y,
                sr,
                match_ms=args.onset_match_ms,
            )
            ok = accept_row(conf, min_onset_match_frac=args.min_onset_match_frac)

        if ok:
            payload = {
                "surah": surah,
                "ayah": ayah,
                "gateScore": float(score),
                "audioSha256": hashlib.sha256(audio.read_bytes()).hexdigest(),
                "confidence": conf,
                "segments": segments,
            }
            rows.append(payload)
            accepted += 1
            status = "accepted"
        else:
            abstained += 1
            status = "abstained"
            payload = None

        if ckpt:
            ckpt.write(
                json.dumps(
                    {
                        "surah": surah,
                        "ayah": ayah,
                        "status": status,
                        "score": score if segments else None,
                        "confidence": conf,
                        "payload": payload,
                    }
                )
                + "\n"
            )
            ckpt.flush()

        if (index + 1) % 25 == 0 or index == 0:
            rate = (index + 1) / max(time.time() - t0, 1e-6)
            eta = (len(work) - index - 1) / max(rate, 1e-6)
            print(
                f"{surah}:{ayah} {status} score={score if segments else float('nan'):.3f} "
                f"acc={accepted} abs={abstained} "
                f"{index+1}/{len(work)} {rate:.2f}/s eta={eta/60:.1f}m",
                flush=True,
            )

    if ckpt:
        ckpt.close()

    # de-dupe by surah:ayah keep last
    by_key = {(r["surah"], r["ayah"]): r for r in rows}
    final = [by_key[k] for k in sorted(by_key)]

    out = {
        "schema": 2,
        "reciterId": args.reciter_id,
        "reciter": args.reciter,
        "generator": BASE_GENERATOR,  # build_db pin for CTC source
        "source": MODEL,
        "sourceRevision": MODEL_REVISION,
        "minimumGateScore": args.min_path_score,
        "autoGate": {
            "tool": GENERATOR,
            "minOnsetMatchFrac": args.min_onset_match_frac,
            "onsetMatchMs": args.onset_match_ms,
            "baseGenerator": BASE_GENERATOR,
        },
        "rows": final,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    print(
        f"Wrote {args.out}: {len(final)} accepted / {len(work)} attempted "
        f"({100*len(final)/max(1,len(work)):.1f}%)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
