#!/usr/bin/env python3
"""
Compare first-principles structure with historical timing overrides.

The recovered corpus is mixed-provenance regression evidence, not independent
gold. Rows incompatible with the canonical grammar are reported separately.
No timing_repairs are applied in the methods under test.

Methods:
  mono           — positions 1..N
  audio_only     — free-decode grammar candidates (no QDC, no repairs)
  audio+qdc_db   — same + shipped DB structure as candidate (has repairs; diagnostic)

Independent ear labels remain the release gate for a 99% claim.
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
from grammar_structure import (  # noqa: E402
    DecodeResult,
    is_grammar_valid,
    mono_positions,
    score_candidate,
    select_structure_from_decode,
    timed_free_decode,
)
from structure_engine import MODEL_ID  # noqa: E402


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
    limit = next((int(a) for a in sys.argv[1:] if a.isdigit()), 0)
    if limit:
        edits = edits[:limit]
    reuse = "--reuse-decodes" in sys.argv
    out_path = LAB / "results" / "eval_vs_manual_patches.json"
    cached = {}
    if reuse and out_path.exists():
        old = json.loads(out_path.read_text())
        if old["summary"].get("model_id", MODEL_ID) == MODEL_ID:
            cached = {
                r["key"]: r["decode"] for r in old["results"] if "decode" in r
            }

    db = sqlite3.connect(ROOT / "data" / "quran.db")

    # prefetch audio
    jobs = [
        (e["reciterSlug"], e["surahId"], e["ayah"])
        for e in edits
        if f"{e['reciterSlug']} {e['surahId']}:{e['ayah']}" not in cached
    ]
    print(f"Prefetching audio for {len(jobs)} patches...", flush=True)
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
        mono = mono_positions(len(words))
        # pure audio
        key = f"{slug} {surah}:{ayah}"
        saved = cached.get(key)
        if saved:
            dec = DecodeResult(saved["text"], [], saved["path_mean"])
        else:
            y, sr = load_mono_16k(ensure_audio(slug, surah, ayah))
            dec = timed_free_decode(y, sr)
        win, scored = select_structure_from_decode(dec, words)
        audio_pos = win.positions

        # diagnostic: shipped DB (includes repairs)
        db_pos = load_db_positions(db, rid, surah, ayah) if rid else None
        priors = [("db", db_pos)] if db_pos else None
        win_db, _ = (
            select_structure_from_decode(dec, words, priors=priors)
            if priors
            else (None, None)
        )

        gold_bt = has_bt(gold_pos)
        gold_valid = is_grammar_valid(gold_pos, len(words))
        mono_score = next(c.score for c in scored if c.source == "mono")
        best_nonmono = next((c for c in scored if c.source != "mono"), None)
        gold_gain = (
            score_candidate(gold_pos, words, dec.text, source="gold").score
            - mono_score
            if gold_valid and gold_bt
            else None
        )
        row = {
            "key": key,
            "source_file": e["source_file"],
            "gold_has_bt": gold_bt,
            "gold_grammar_valid": gold_valid,
            "gold_gain_vs_mono": gold_gain,
            "gold_pos": gold_pos,
            "n_words": len(words),
            "decode": {
                "text": dec.text,
                "path_mean": dec.path_mean,
            },
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
                    "best_nonmono_gain": (
                        best_nonmono.score - mono_score if best_nonmono else None
                    ),
                    "best_nonmono_pos": (
                        best_nonmono.positions if best_nonmono else None
                    ),
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
        "model_id": MODEL_ID,
        "n_patches": len(results),
        "n_gold_with_bt": sum(1 for r in results if r["gold_has_bt"]),
        "n_gold_mono": sum(1 for r in results if not r["gold_has_bt"]),
        "n_gold_grammar_valid": sum(1 for r in results if r["gold_grammar_valid"]),
        "n_gold_quarantined": sum(1 for r in results if not r["gold_grammar_valid"]),
        "grammar_ceiling_pct": 100
        * sum(1 for r in results if r["gold_grammar_valid"])
        / len(results),
        "all": {m: agg(m) for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")},
        "gold_was_mono_unsplit": {
            m: agg(m, lambda r: not r["gold_has_bt"])
            for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")
        },
        "gold_had_repeat": {
            m: agg(m, lambda r: r["gold_has_bt"])
            for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")
        },
        "grammar_valid_gold": {
            m: agg(m, lambda r: r["gold_grammar_valid"])
            for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")
        },
        "quarantined_incompatible_gold": {
            m: agg(m, lambda r: not r["gold_grammar_valid"])
            for m in ("mono", "audio_only", "audio+db_candidate", "shipped_db")
        },
        "by_source_file": {},
        "elapsed_s": round(time.time() - t0, 2),
    }
    valid_repeat = [
        r for r in results if r["gold_grammar_valid"] and r["gold_has_bt"]
    ]
    summary["oracle_repeat_objective"] = {
        "n": len(valid_repeat),
        "gold_beats_mono": sum(r["gold_gain_vs_mono"] >= 0 for r in valid_repeat),
    }
    valid = [r for r in results if r["gold_grammar_valid"]]
    summary["margin_sweep"] = {}
    for margin in (0.0, 0.01, 0.05, 0.1, 0.15):
        correct = clean_correct = repeat_correct = predicted_repeat = 0
        for r in valid:
            method = r["methods"]["audio_only"]
            gain = method["best_nonmono_gain"]
            use_repeat = gain is not None and gain >= margin
            predicted = method["best_nonmono_pos"] if use_repeat else mono_positions(r["n_words"])
            hit = predicted == r["gold_pos"]
            correct += hit
            clean_correct += hit and not r["gold_has_bt"]
            repeat_correct += hit and r["gold_has_bt"]
            predicted_repeat += use_repeat
        summary["margin_sweep"][str(margin)] = {
            "exact": correct,
            "clean_exact": clean_correct,
            "repeat_exact": repeat_correct,
            "predicted_repeat": predicted_repeat,
        }
    files = sorted({r["source_file"] for r in results})
    for f in files:
        summary["by_source_file"][f] = {
            m: agg(m, lambda r, ff=f: r["source_file"] == ff)
            for m in ("mono", "audio_only")
        }

    out = {"summary": summary, "results": results}
    if not limit:
        out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2))

    print("\n======== VS HISTORICAL MANUAL PATCHES ========")
    print(f"n={summary['n_patches']}  mono-structure gold={summary['n_gold_mono']}  "
          f"repeat gold={summary['n_gold_with_bt']}")
    print(
        f"grammar-valid gold={summary['n_gold_grammar_valid']}  "
        f"quarantined incompatible gold={summary['n_gold_quarantined']}  "
        f"all-row exact ceiling={summary['grammar_ceiling_pct']:.1f}%"
    )
    oracle = summary["oracle_repeat_objective"]
    print(
        f"oracle repeat candidate beats mono under current objective: "
        f"{oracle['gold_beats_mono']}/{oracle['n']}"
    )
    for label, block in [
        ("ALL", summary["all"]),
        ("gold was mono (mostly false-split unsplits)", summary["gold_was_mono_unsplit"]),
        ("gold had repeat", summary["gold_had_repeat"]),
        ("grammar-valid gold", summary["grammar_valid_gold"]),
        ("quarantined incompatible gold", summary["quarantined_incompatible_gold"]),
    ]:
        print(f"\n--- {label} ---")
        for m, s in block.items():
            if not s:
                continue
            print(
                f"  {m:22} exact {s['exact']}/{s['n']} ({s['exact_pct']:.1f}%)  "
                f"bt_match {s['bt_match']}/{s['n']} ({s['bt_match_pct']:.1f}%)"
            )
    destination = f"wrote {out_path}" if not limit else "smoke run; results not written"
    print(f"\n{destination} ({summary['elapsed_s']}s)")


if __name__ == "__main__":
    main()
