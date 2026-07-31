#!/usr/bin/env python3
"""Merge automated V2 lanes: CTC mono + QUA same-take repeats.

Priority (no human labels):
  1. CTC mono rows that pass auto-confidence (scale + acoustic clock)
  2. QUA same-take rows with non-mono structure (repeats CTC cannot encode)
  3. Else abstain → app V1

Writes one schema-2 payload build_db can load (pinned CTC or QUA generator
per source file — merge emits two files or a multi-file dir).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def is_repeat(row: dict) -> bool:
    pos = [int(s["position"]) for s in row["segments"]]
    return len(pos) != len(set(pos))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ctc", type=Path, required=True)
    parser.add_argument("--qua", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--prefer-ctc-on-mono-qua", action="store_true", default=True)
    args = parser.parse_args()

    ctc = json.loads(args.ctc.read_text(encoding="utf-8"))
    qua = json.loads(args.qua.read_text(encoding="utf-8"))
    ctc_map = {(r["surah"], r["ayah"]): r for r in ctc["rows"]}
    qua_map = {(r["surah"], r["ayah"]): r for r in qua["rows"]}

    # QUA keeps only true repeat structure (CTC mono cannot encode re-says).
    qua_rep = [row for row in qua["rows"] if is_repeat(row)]
    rep_keys = {(r["surah"], r["ayah"]) for r in qua_rep}

    # CTC keeps mono only; drop keys claimed by QUA repeats to avoid DB dups.
    ctc_out_rows = [
        row for row in ctc["rows"] if (row["surah"], row["ayah"]) not in rep_keys
    ]
    # strip bulky confidence diagnostics from shipped artifact
    for row in ctc_out_rows:
        row.pop("confidence", None)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    ctc_path = args.out_dir / "alafasy_ctc_auto.json"
    qua_path = args.out_dir / "alafasy_qua_repeats.json"
    ctc_payload = dict(ctc)
    ctc_payload["rows"] = ctc_out_rows
    ctc_path.write_text(json.dumps(ctc_payload, ensure_ascii=False, indent=2) + "\n")

    qua_payload = dict(qua)
    qua_payload["rows"] = qua_rep
    qua_path.write_text(json.dumps(qua_payload, ensure_ascii=False, indent=2) + "\n")

    print(
        f"CTC mono rows: {len(ctc_out_rows)}\n"
        f"QUA repeat rows: {len(qua_rep)}\n"
        f"dropped CTC for QUA-repeat keys: {len(rep_keys & set(ctc_map))}\n"
        f"union accepted: {len(ctc_out_rows) + len(qua_rep)}\n"
        f"wrote {ctc_path} + {qua_path}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
