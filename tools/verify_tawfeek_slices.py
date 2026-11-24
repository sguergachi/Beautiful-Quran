#!/usr/bin/env python3
"""Prove every Tawfeek verse slice decodes exactly as the surah MP3 does.

For each verse in tools/timing_sources/tawfeek-verse-index.json this takes the
byte range the app will request, decodes it on its own, and compares it with
the same stretch of the fully decoded surah. Past the decoder's warm-up frames
the two must be bit-identical; before them the slice is allowed to differ (it
only primes the decoder). It also reports how loud that warm-up region is, which
is the only audible cost of cutting a surah file into verses.

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


def lead_samples(raw, clip):
    """Samples of reservoir-only lead: walk the frames in clip's lead bytes."""
    pos, frames = clip["startByte"], 0
    while pos < clip["startByte"] + clip["leadBytes"]:
        header = int.from_bytes(raw[pos:pos + 4], "big")
        pos += 144 * index_tool.BITRATES_K[(header >> 12) & 15] * 1000 // index_tool.RATE + ((header >> 9) & 1)
        frames += 1
    return frames * SPF


def cut_level_db(out, lead, rate=index_tool.RATE):
    """How loud the slice's last 20 ms are, relative to the verse's own loud level (dB)."""
    hop = int(0.02 * rate)
    body = out[lead:].astype(np.float64)
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
    # Playing verses back to back must never replay source audio: a verse ends no later than
    # the next one's first audible frame (the silenced lead before it is not audible).
    ordered = sorted((c["ayah"] if "ayah" in c else int(k.split(":")[1]), k, c)
                     for k, c in index["clips"].items() if c["surah"] == surah)
    for (_, ka, a), (_, kb, b) in zip(ordered, ordered[1:]):
        end = a["originSamples"] + a["durationSamples"]
        audible = b["originSamples"] + lead_samples(raw, b)
        if end > audible:
            problems.append((kb, f"opens {round((end - audible) * 1000 / index_tool.RATE)} ms before {ka} ends: "
                                 "the previous verse's last sound would play twice"))
    for key, clip in index["clips"].items():
        if clip["surah"] != surah:
            continue
        data = bytearray(raw[clip["startByte"]:clip["endByte"]])
        index_tool.silence_lead(data, clip["leadBytes"])
        out = decode(bytes(data), "stdin")
        origin = clip["originSamples"]
        lead = lead_samples(raw, clip)
        warm = lead + 2 * SPF  # the first frame or two after the lead lack their predecessor's overlap
        ref = full[origin:origin + len(out)]
        n = min(len(out), len(ref))
        if n != len(out) or abs(len(out) - clip["durationSamples"]) > SPF:
            problems.append((key, f"decoded {len(out)} samples, expected {clip['durationSamples']}"))
            continue
        exact = np.array_equal(out[warm:n], ref[warm:n])
        if not exact:
            bad = int(np.abs(out[warm:n].astype(int) - ref[warm:n].astype(int)).max())
            problems.append((key, f"not bit-exact after warm-up (max error {bad})"))
        # Every sound of the verse, as the alignment measured it, must lie inside the slice.
        all_runs = record["runs"]
        begin_ms = (origin + lead) * 1000 / index_tool.RATE
        finish_ms = (origin + len(out)) * 1000 / index_tool.RATE
        for i, r in enumerate(all_runs):
            if f"{surah}:{r['ayah']}" != key:
                continue
            # Where a neighbour's sound begins, the sound beyond that point is the neighbour's: the
            # detected edge of a verse that touches the next is only bounded by it.
            # A verse's own words say how far its sound can reach: the detected edge of one that runs
            # into the next is only a bound, never later than a quarter second past its last word.
            words = record["words"]
            onset = max(r["onsetMs"], words[r["first"]][2] - 250)
            offset = min(r["offsetMs"], words[r["last"]][3] + 250)
            if i:
                onset = max(onset, all_runs[i - 1]["offsetMs"])
            if i + 1 < len(all_runs):
                offset = min(offset, all_runs[i + 1]["onsetMs"])
            if onset < begin_ms - SEAM_TOLERANCE_MS or offset > finish_ms + SEAM_TOLERANCE_MS:
                problems.append((key, f"sound {onset}-{offset} ms outside slice "
                                      f"{begin_ms:.0f}-{finish_ms:.0f} ms"))
        silent = out[:lead]
        if lead and int(np.abs(silent).max()):
            problems.append((key, f"lead not silent (peak {int(np.abs(silent).max())})"))
        shaped = out[lead:warm].astype(np.float64) - ref[lead:warm].astype(np.float64)
        stats[key] = {
            "leadMs": round(lead * 1000 / index_tool.RATE),
            "shapedRms": float(np.sqrt(np.mean(shaped * shaped))) if len(shaped) else 0.0,
            "refRms": float(np.sqrt(np.mean(ref[lead:warm].astype(np.float64) ** 2))) if warm > lead else 0.0,
            "cutIn": cut_level_db(out, lead)[0],
            "cutOut": cut_level_db(out, lead)[1],
        }
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
    leads = sorted(v["leadMs"] for v in all_stats.values())
    print(f"silent lead: median {leads[len(leads) // 2]} ms, max {leads[-1]} ms")
    # How much the two frames after the lead differ from the full decode, relative to their level.
    ratios = sorted(
        (v["shapedRms"] / v["refRms"] if v["refRms"] > 50 else 0.0, k) for k, v in all_stats.items()
    )
    print(f"shaped opening error/level: median {ratios[len(ratios) // 2][0]:.3f}, "
          f"p99 {ratios[int(len(ratios) * .99)][0]:.3f}, max {ratios[-1]}")
    voiced = [(k, v) for k, v in all_stats.items() if v["refRms"] > 800 and v["shapedRms"] > 0.2 * v["refRms"]]
    print(f"{len(voiced)} verses open on voiced sound that the missing overlap shapes noticeably")
    for k, v in sorted(voiced, key=lambda kv: -kv[1]["refRms"])[:10]:
        print(f"  {k}: opening level {v['refRms']:.0f}, error {v['shapedRms']:.0f}")
    for edge in ("cutIn", "cutOut"):
        loud = [k for k, v in all_stats.items() if v[edge] > -12]
        print(f"{len(loud)} slices have their {edge[3:].lower()}side cut inside sound within 12 dB of the verse's level"
              f" (touching verses share a seam; the rest need review)")
    for problem in all_problems[:20]:
        print("  !!", *problem)
    return 1 if all_problems else 0


if __name__ == "__main__":
    sys.exit(main())
