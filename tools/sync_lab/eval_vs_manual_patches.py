#!/usr/bin/env python3
"""
Compare first-principles (audio-only) structure vs historical manual patches.

Gold = tools/sync_lab/historical_manual_patches.json (recovered from git
timing_overrides). No timing_repairs applied in the methods under test.

Methods:
  mono           — positions 1..N
  audio_only     — free-decode grammar candidates (no QDC, no repairs)
  audio+qdc_db   — same + shipped DB structure as candidate (has repairs; diagnostic)

Success for "do without manual patches": audio_only matches gold position sequence
(or at least backtrack multiset on repeat cases; mono exact on unsplit cases).
"""
from __future__ import annotations

import json
import sqlite3
import sys
import time
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
sys.path.insert(0, str(LAB))

from aligners import load_mono_16k  # noqa: E402
from grammar_structure import mono_positions, select_structure  # noqa: E402


def bt_counter(positions):
    c = Counter()
    hw = 0
    for p in positions:
        if p <= hw:
            c[p] += 1
        hw = max(hw, p)
    return c


def has_bt(positions):
    return bool(bt_counter(positions))


def ensure_audio(slug: str, surah: int, ayah: int) -> Path:
    dest_dir = LAB / "audio" / slug
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / f"{surah:03d}{ayah:03d}.mp3"
    if dest.exists() and dest.stat().st_size > 1000:
        return dest
    url = f"https://everyayah.com/data/{slug}/{surah:03d}{ayah:03d}.mp3"
    urllib.request.urlretrieve(url, dest)
    return dest


def load_words(db: sqlite3.Connection, surah: int, ayah: int) -> list[str]:
    rows = db.execute(
        "SELECT arabic FROM words WHERE surah_id=? AND ayah_number=? ORDER BY position",
        (surah, ayah),
    ).fetchall()
    return [r[0] for r in rows]


def load_db_positions(db: sqlite3.Connection, reciter_id: int, surah: int, ayah: int):
    row = db.execute(
        "SELECT segments FROM timings WHERE reciter_id=? AND surah_id=? AND ayah_number=?",
        (reciter_id, surah, ayah),
    ).fetchone()
    if not row:
        return None
    segs = json.loads(row[0])
    return [s[0] for s in segs]


def main():
    inv = json.loads((LAB / "historical_manual_patches.json").read_text())
    edits = inv["edits"]
    # optional limit for smoke
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    if limit:
        edits = edits[:limit]

    db = sqlite3.connect(ROOT / "data" / "quran.db")

    # prefetch audio
    print(f"Prefetching audio for {len(edits)} patches...", flush=True)
    jobs = [(e["reciterSlug"], e["surahId"], e["ayah"]) for e in edits]
    with ThreadPoolExecutor(16) as ex:
        futs = [ex.submit(ensure_audio, *j) for j in jobs]
        for i, f in enumerate(as_completed(futs), 1):
            f.result()
            if i % 50 == 0:
                print(f"  audio {i}/{len(jobs)}", flush=True)

    results = []
    t0 = time.time()
    for i, e in enumerate(edits):
        slug, surah, ayah = e["reciterSlug"], e["surahId"], e["ayah"]
        rid = e.get("reciterId")
        gold_pos = [s[0] for s in e["segments"]]
        words = load_words(db, surah, ayah)
        if not words:
            continue
        path = ensure_audio(slug, surah, ayah)
        y, sr = load_mono_16k(path)

        mono = mono_positions(len(words))
        # pure audio
        win, dec, scored = select_structure(y, sr, words, priors=None)
        audio_pos = win.positions

        # diagnostic: shipped DB (includes repairs)
        db_pos = load_db_positions(db, rid, surah, ayah) if rid else None
        priors = [("db", db_pos)] if db_pos else None
        win_db, _, _ = select_structure(y, sr, words, priors=priors) if priors else (None, None, None)

        gold_bt = has_bt(gold_pos)
        row = {
            "key": f"{slug} {surah}:{ayah}",
            "source_file": e["source_file"],
            "gold_has_bt": gold_bt,
            "gold_pos": gold_pos,
            "n_words": len(words),
            "methods": {
                "mono": {
                    "exact": mono == gold_pos,
                    "bt_match": bt_counter(mono) == bt_counter(gold_pos),
                    "pos": mono,
                },
                "audio_only": {
                    "exact": audio_pos == gold_pos,
                    "bt_match": bt_counter(audio_pos) == bt_counter(gold_pos),
                    "pos": audio_pos,
                    "source": win.source,
                    "score": win.score,
                    "sim": win.decode_sim,
                },
            },
        }
        if win_db:
            row["methods"]["audio+db_candidate"] = {
                "exact": win_db.positions == gold_pos,
                "bt_match": bt_counter(win_db.positions) == bt_counter(gold_pos),
                "pos": win_db.positions,
                "source": win_db.source,
            }
        if db_pos is not None:
            row["methods"]["shipped_db"] = {
                "exact": db_pos == gold_pos,
                "bt_match": bt_counter(db_pos) == bt_counter(gold_pos),
                "pos": db_pos,
            }
        results.append(row)
        if (i + 1) % 25 == 0 or i == 0:
            print(f"  done {i+1}/{len(edits)} {slug} {surah}:{ayah}", flush=True)

    # aggregates
    def agg(method, subset=None):
        rows = results if subset is None else [r for r in results if subset(r)]
        if not rows:
            return {}
        mrows = [r["methods"][method] for r in rows if method in r["methods"]]
        n = len(mrows)
        return {
            "n": n,
            "exact": sum(1 for m in mrows if m["exact"]),
            "exact_pct": 100 * sum(1 for m in mrows if m["exact"]) / n,
            "bt_match": sum(1 for m in mrows if m["bt_match"]),
            "bt_match_pct": 100 * sum(1 for m in mrows if m["bt_match"]) / n,
        }

    summary = {
        "n_patches": len(results),
        "n_gold_with_bt": sum(1 for r in results if r["gold_has_bt"]),
        "n_gold_mono": sum(1 for r in results if not r["gold_has_bt"]),
        "all": {m: agg(m) for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")},
        "gold_was_mono_unsplit": {
            m: agg(m, lambda r: not r["gold_has_bt"])
            for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")
        },
        "gold_had_repeat": {
            m: agg(m, lambda r: r["gold_has_bt"])
            for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")
        },
        "by_source_file": {},
        "elapsed_s": round(time.time() - t0, 2),
    }
    files = sorted({r["source_file"] for r in results})
    for f in files:
        summary["by_source_file"][f] = {
            m: agg(m, lambda r, ff=f: r["source_file"] == ff)
            for m in ("mono", "audio_only")
        }

    out = {"summary": summary, "results": results}
    out_path = LAB / "results" / "eval_vs_manual_patches.json"
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2))

    print("\n======== VS HISTORICAL MANUAL PATCHES ========")
    print(f"n={summary['n_patches']}  mono-structure gold={summary['n_gold_mono']}  "
          f"repeat gold={summary['n_gold_with_bt']}")
    for label, block in [
        ("ALL", summary["all"]),
        ("gold was mono (mostly false-split unsplits)", summary["gold_was_mono_unsplit"]),
        ("gold had repeat", summary["gold_had_repeat"]),
    ]:
        print(f"\n--- {label} ---")
        for m, s in block.items():
            if not s:
                continue
            print(
                f"  {m:22} exact {s['exact']}/{s['n']} ({s['exact_pct']:.1f}%)  "
                f"bt_match {s['bt_match']}/{s['n']} ({s['bt_match_pct']:.1f}%)"
            )
    print(f"\nwrote {out_path} ({summary['elapsed_s']}s)")


if __name__ == "__main__":
    main()
