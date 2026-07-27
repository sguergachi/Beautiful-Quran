#!/usr/bin/env python3
"""Merge V2 lanes with Lab gold highest priority (no key collisions).

Priority (high → low):
  1. Lab gold (Timings Lab ground truth)
  2. QUA same-take repeats
  3. CTC mono auto

Writes non-overlapping JSON files into --out-dir for build_db load_timing_v2.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def is_repeat(row: dict) -> bool:
    pos = [int(s["position"]) for s in row["segments"]]
    return len(pos) != len(set(pos))


def keys_of(rows: list[dict]) -> set[tuple[int, int]]:
    return {(int(r["surah"]), int(r["ayah"])) for r in rows}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lab-gold", type=Path, required=True)
    parser.add_argument("--ctc", type=Path, required=True)
    parser.add_argument("--qua", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()

    lab = json.loads(args.lab_gold.read_text(encoding="utf-8"))
    ctc = json.loads(args.ctc.read_text(encoding="utf-8"))
    qua = json.loads(args.qua.read_text(encoding="utf-8"))

    lab_rows = lab["rows"]
    claimed = keys_of(lab_rows)

    qua_rep = [
        r for r in qua["rows"]
        if is_repeat(r) and (int(r["surah"]), int(r["ayah"])) not in claimed
    ]
    claimed |= keys_of(qua_rep)

    ctc_rows = [
        r for r in ctc["rows"]
        if (int(r["surah"]), int(r["ayah"])) not in claimed
    ]
    for r in ctc_rows:
        r.pop("confidence", None)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    lab_out = dict(lab)
    lab_out["rows"] = lab_rows
    (args.out_dir / "alafasy_lab_gold.json").write_text(
        json.dumps(lab_out, ensure_ascii=False, indent=2) + "\n"
    )
    qua_out = dict(qua)
    qua_out["rows"] = qua_rep
    (args.out_dir / "alafasy_qua_repeats.json").write_text(
        json.dumps(qua_out, ensure_ascii=False, indent=2) + "\n"
    )
    ctc_out = dict(ctc)
    ctc_out["rows"] = ctc_rows
    (args.out_dir / "alafasy_ctc_auto.json").write_text(
        json.dumps(ctc_out, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        f"lab_gold={len(lab_rows)} qua_repeats={len(qua_rep)} "
        f"ctc={len(ctc_rows)} total={len(lab_rows)+len(qua_rep)+len(ctc_rows)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
