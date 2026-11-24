#!/usr/bin/env python3
"""Prove Tawfeek's verses, played back to back, are the surah recording.

For each verse in tools/timing_sources/tawfeek-verse-index.json this takes the
byte range the app will request, silences its reservoir-only lead as the app
does, decodes it on its own, drops the first `trimSamples` (as the player's
gapless trim does) and compares what is left with the same stretch of the fully
decoded surah. It must be bit-identical from its first sample to its last, and
each verse must begin exactly where the one before it ended: no sample heard
twice, none skipped, so a seam inside connected recitation is inaudible.

    tools/verify_tawfeek_slices.py --source DIR_OF_NNN.mp3 [--surahs 1-114]
"""

import argparse
import hashlib
import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_tawfeek_verse_index as index_tool  # noqa: E402
from reciter_align import artifact as alignment_artifact  # noqa: E402

SPF = index_tool.SAMPLES_PER_FRAME
SEAM_TOLERANCE_MS = 150


def decode(data, fmt):
    cmd = ["ffmpeg", "-v", "error"] + (["-f", "mp3", "-i", "-"] if fmt == "stdin" else ["-i", fmt])
    cmd += ["-map", "0:a:0", "-f", "s16le", "-ac", "1", "-ar", str(index_tool.RATE), "-"]
    return np.frombuffer(
        subprocess.run(cmd, input=data, capture_output=True, check=True).stdout, dtype=np.int16
    )


def cut_level_db(heard, rate=index_tool.RATE):
    """How loud a verse's first and last 20 ms are, relative to its own loud level (dB)."""
    hop = int(0.02 * rate)
    body = heard.astype(np.float64)
    if len(body) < 4 * hop:
        return 0.0, 0.0
    frames = body[:len(body) // hop * hop].reshape(-1, hop)
    db = 10 * np.log10(np.mean(frames * frames, axis=1) + 1e-9)
    level = np.percentile(db, 90)
    return float(db[0] - level), float(db[-1] - level)


def verify_surah(surah, source_dir, index, record):
    path = source_dir / f"{surah:03d}.mp3"
    raw = path.read_bytes()
    pinned = index["sources"][str(surah)]
    problems = []
    if hashlib.sha256(raw).hexdigest() != pinned["sha256"] or len(raw) != pinned["bytes"]:
        return surah, [("source", "does not match the pinned hash")], {}
    full = decode(None, str(path))
    stats = {}
    ordered = sorted((c["originSamples"], k, c) for k, c in index["clips"].items() if c["surah"] == surah)
    for (_, ka, a), (_, kb, b) in zip(ordered, ordered[1:]):
        gap = b["originSamples"] - (a["originSamples"] + a["durationSamples"])
        if gap < 0:
            problems.append((kb, f"begins {round(-gap * 1000 / index_tool.RATE)} ms before {ka} ends: "
                                 "that sound would play twice"))
    for key, clip in index["clips"].items():
        if clip["surah"] != surah:
            continue
        data = bytearray(raw[clip["startByte"]:clip["endByte"]])
        index_tool.silence_lead(data, clip["leadBytes"])
        out = decode(bytes(data), "stdin")
        trim, origin, duration = clip["trimSamples"], clip["originSamples"], clip["durationSamples"]
        heard = out[trim:]
        if len(heard) != duration:
            problems.append((key, f"decodes to {len(heard)} heard samples, expected {duration}"))
            continue
        ref = full[origin:origin + duration]
        if len(ref) != duration:
            problems.append((key, "runs past the end of the recording"))
            continue
        if not np.array_equal(heard, ref):
            diff = np.nonzero(heard != ref)[0]
            problems.append((key, f"differs from the recording at {len(diff)} samples, first at {diff[0]}"
                                  f" (max error {int(np.abs(heard.astype(int) - ref.astype(int)).max())})"))
        # Every sound of the verse, as the alignment measured it, must lie inside what is heard.
        all_runs = record["runs"]
        begin_ms = origin * 1000 / index_tool.RATE
        finish_ms = (origin + duration) * 1000 / index_tool.RATE
        words = record["words"]
        for i, r in enumerate(all_runs):
            if f"{surah}:{r['ayah']}" != key:
                continue
            # A neighbour's sound bounds this verse's detected edge, and so do its own words:
            # the edge of a verse that runs into the next is never later than 250 ms past its last word.
            onset = max(r["onsetMs"], words[r["first"]][2] - 250)
            offset = min(r["offsetMs"], words[r["last"]][3] + 250)
            if i:
                onset = max(onset, all_runs[i - 1]["offsetMs"])
            if i + 1 < len(all_runs):
                offset = min(offset, all_runs[i + 1]["onsetMs"])
            if onset < begin_ms - SEAM_TOLERANCE_MS or offset > finish_ms + SEAM_TOLERANCE_MS:
                problems.append((key, f"sound {onset}-{offset} ms outside slice "
                                      f"{begin_ms:.0f}-{finish_ms:.0f} ms"))
        cut_in, cut_out = cut_level_db(heard)
        stats[key] = {"trimMs": round(trim * 1000 / index_tool.RATE), "cutIn": cut_in, "cutOut": cut_out}
    return surah, problems, stats


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--surahs", default="1-114")
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--index", type=Path, default=index_tool.INDEX)
    parser.add_argument("--alignment", type=Path, default=index_tool.ALIGNMENT)
    args = parser.parse_args()
    index = json.loads(args.index.read_text())
    aligned = alignment_artifact.load(args.alignment)
    surahs = []
    for part in args.surahs.split(","):
        lo, _, hi = part.partition("-")
        surahs.extend(range(int(lo), int(hi or lo) + 1))
    all_problems, all_stats = [], {}
    with ThreadPoolExecutor(args.jobs) as pool:
        for surah, problems, stats in pool.map(
                lambda s: verify_surah(s, args.source, index, aligned["surahs"][str(s)]), surahs):
            all_problems += [(f"{surah}", *p) for p in problems]
            all_stats.update(stats)
            print(f"  surah {surah}: {len(stats)} verses, {len(problems)} problems", flush=True)
    print(f"{len(all_stats)} verses verified, {len(all_problems)} problems")
    trims = sorted(v["trimMs"] for v in all_stats.values())
    print(f"decoded before each verse and trimmed: median {trims[len(trims) // 2]} ms, max {trims[-1]} ms")
    for edge in ("cutIn", "cutOut"):
        loud = [k for k, v in all_stats.items() if v[edge] > -12]
        print(f"{len(loud)} slices have their {edge[3:].lower()}side cut inside sound within 12 dB of the verse's level"
              f" (verses that run together share an exact seam)")
    for problem in all_problems[:20]:
        print("  !!", *problem)
    return 1 if all_problems else 0


if __name__ == "__main__":
    sys.exit(main())
