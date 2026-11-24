#!/usr/bin/env python3
"""Index Tawfeek As-Sawaigh's whole-surah recordings as per-verse byte ranges.

He is not on EveryAyah, and the app plays one clip per verse. Rather than host
re-cut clips, each verse is a *byte range of the one public surah MP3* on
mp3quran.net's CDN: the player asks for `Range: bytes=a-b` and receives a few
hundred whole MP3 frames that decode exactly as the original did. This tool
computes those ranges from our own word alignment of the same recordings
(tools/reciter_align, committed as timing_sources/tawfeek-alignment.json.gz),
whose times rebase onto a slice's own clock with one subtraction.

For every verse it:

  1. scans the surah MP3 frame by frame (MPEG-1 Layer III, 44.1 kHz, one
     constant bitrate per file; anything else aborts) so a cut can only fall on a frame boundary;
  2. places the cut where the audio is quiet: inside the silence between two
     verses, or at the quietest instant within +-40 ms when verses touch;
  3. backs the start up by the frames that hold the bit reservoir the first
     wanted frame draws on (`main_data_begin`). Their bytes must be present
     but their sound must not be: the app zeroes those frames' granule data
     (`silence_lead`, mirrored in Kotlin and TypeScript) so they decode as
     silence instead of replaying the previous verse's tail;
  4. records start/end byte, the slice's origin in source samples, its length
     and how many leading bytes are reservoir-only, in
     tools/timing_sources/tawfeek-verse-index.json.

The source MP3 hashes and byte sizes are pinned. The app refuses a source
whose size differs, because a re-encoded upstream file would silently shift
every range.

    tools/build_tawfeek_verse_index.py --source DIR_OF_NNN.mp3 [--alignment FILE]
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
from reciter_align import artifact as alignment_artifact  # noqa: E402

SLUG = "Tawfeek_As-Sawaigh_128kbps"  # catalogue slug (kept in step with build_db.TAWFEEK_SLUG)
RATE = 44_100
SAMPLES_PER_FRAME = 1_152
ALIGNMENT = Path(__file__).resolve().parent / "timing_sources" / "tawfeek-alignment.json.gz"
SOURCE_URL = "https://cdn.mp3quran.net/audio/tawfeeq-sayegh/r1/{surah:03d}.mp3"
INDEX = Path(__file__).resolve().parent / "timing_sources" / "tawfeek-verse-index.json"
ONSETS_DIR = Path(__file__).resolve().parent / "audio_onsets"
RECITER_ID = 20  # kept in step with build_db.TAWFEEK_RECITER_ID

# Cut policy, all in ms.
TOUCH_MS = 40          # a gap this small is "touching": the two verses share one cut
SNAP_MS = 40           # search +-SNAP_MS around a shared boundary for the quietest frame
HOP_MS = 2             # energy resolution of that search
LEAD_CAP_MS = 150      # at most this much silence before a verse
TAIL_CAP_MS = 250      # at most this much room after a verse (lets a madd decay)

# MPEG-1 Layer III bitrates (kbps) by header index. Each source file is CBR at one of them.
BITRATES_K = (0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)


def ms_to_samples(ms):
    return int(round(ms * RATE / 1000))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def id3_size(data):
    if data[:3] != b"ID3":
        return 0
    size = 0
    for byte in data[6:10]:
        size = (size << 7) | (byte & 0x7F)
    return 10 + size + (10 if data[5] & 0x10 else 0)


def scan_frames(path):
    """Return (frame_offsets, main_data_begin, payload_bytes) for a CBR MP3.

    Only MPEG-1 Layer III at 44.1 kHz with one constant bitrate per file is
    accepted, so a whole-file walk must land exactly on every header.
    """
    data = Path(path).read_bytes()
    pos = id3_size(data)
    bitrate_idx0 = (int.from_bytes(data[pos:pos + 4], "big") >> 12) & 15
    offsets, begin, payload = [], [], []
    while pos + 4 <= len(data):
        h = int.from_bytes(data[pos:pos + 4], "big")
        sync, version, layer = h >> 21, (h >> 19) & 3, (h >> 17) & 3
        bitrate_idx, rate_idx, padding = (h >> 12) & 15, (h >> 10) & 3, (h >> 9) & 1
        channels_mono = ((h >> 6) & 3) == 3
        if ((h >> 16) & 1) == 0 or channels_mono:
            raise SystemExit(f"{path}: frame at byte {pos} has a CRC or is mono; silence_lead needs neither")
        if sync != 0x7FF or version != 3 or layer != 1 or bitrate_idx != bitrate_idx0 or rate_idx != 0 or not bitrate_idx0 or bitrate_idx0 == 15:
            if data[pos:pos + 3] == b"TAG" and len(data) - pos == 128:
                break  # ID3v1 footer
            if not any(data[pos:]):
                break  # trailing zeros
            raise SystemExit(f"{path}: unexpected frame at byte {pos}: {h:08x}")
        length = 144 * BITRATES_K[bitrate_idx0] * 1000 // RATE + padding
        if pos + length > len(data):
            break  # a truncated final frame is never part of a verse
        has_crc = ((h >> 16) & 1) == 0
        side_at = pos + 4 + (2 if has_crc else 0)
        side_len = 17 if channels_mono else 32
        offsets.append(pos)
        begin.append(((data[side_at] << 1) | (data[side_at + 1] >> 7)) & 0x1FF)
        payload.append(length - 4 - (2 if has_crc else 0) - side_len)
        pos += length
    offsets.append(pos)  # end sentinel
    return offsets, begin, payload


def decode(path):
    """Whole surah -> mono int16 at 44.1 kHz; sample n lies in frame n // 1152."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0", "-f", "s16le",
         "-ac", "1", "-ar", str(RATE), "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.int16)


def units_of(surah, record):
    """Every audible unit of one surah as (onset_ms, offset_ms, kind, key), by time.

    Edges are the measured start and end of the sound (reciter_align.acoustics),
    not the aligner's token frames, so a cut never lands inside a word's tail.
    """
    units = [(run["onsetMs"], run["offsetMs"], "verse", f"{surah}:{run['ayah']}") for run in record["runs"]]
    for opening in record["openings"]:
        units.append((opening["onsetMs"], opening["offsetMs"], opening["kind"], opening["kind"]))
    units.sort()
    return units


def quietest(pcm, center, radius):
    """Sample index of the quietest 2 ms frame within +-radius of center."""
    hop = ms_to_samples(HOP_MS)
    lo = max(0, center - radius)
    hi = min(len(pcm), center + radius)
    best, best_score = center, None
    for start in range(lo, max(lo + 1, hi - hop), max(1, hop // 2)):
        frame = pcm[start:start + hop].astype(np.float64)
        energy = float(np.sqrt(np.mean(frame * frame))) if len(frame) else 0.0
        score = (round(energy, 1), abs(start + hop // 2 - center))  # ties: nearest boundary
        if best_score is None or score < best_score:
            best_score, best = score, start + hop // 2
    return best


def plan_surah(surah, pcm, units):
    """Return {verseKey: (cut_in, cut_out)} in source samples, one span per verse."""
    n = len(pcm)
    spans = {}
    for i, (start, end, kind, key) in enumerate(units):
        if kind != "verse":
            continue
        lo, hi, first, last = spans.get(key, (start, end, i, i))
        spans[key] = (min(lo, start), max(hi, end), min(first, i), max(last, i))
    plans = {}
    for key, (lo_ms, hi_ms, first, last) in spans.items():
        before = [u[1] for j, u in enumerate(units) if j < first and u[1] <= lo_ms + 1]
        after = [u[0] for j, u in enumerate(units) if j > last and u[0] >= hi_ms - 1]
        prev_end = max(before) if before else 0
        next_start = min(after) if after else n * 1000 // RATE
        gap_before, gap_after = lo_ms - prev_end, next_start - hi_ms
        touching_in = gap_before <= TOUCH_MS
        touching_out = gap_after <= TOUCH_MS and bool(after)
        cut_in = (quietest(pcm, ms_to_samples(lo_ms), ms_to_samples(SNAP_MS)) if touching_in
                  else ms_to_samples(lo_ms - min(gap_before * 0.5, LEAD_CAP_MS)))
        cut_out = (quietest(pcm, ms_to_samples(hi_ms), ms_to_samples(SNAP_MS)) if touching_out
                   else ms_to_samples(hi_ms + min(gap_after * 0.5, TAIL_CAP_MS)))
        plans[key] = [max(0, cut_in), min(n, cut_out), touching_in, touching_out,
                      ms_to_samples(lo_ms), ms_to_samples(hi_ms)]
    # Touching neighbours share one cut sample so a seam neither repeats nor drops audio.
    ordered = sorted(plans, key=lambda k: plans[k][0])
    for a, b in zip(ordered, ordered[1:]):
        pa, pb = plans[a], plans[b]
        if pa[3] and pb[2] and abs(pa[1] - pb[0]) <= ms_to_samples(2 * SNAP_MS):
            pa[1] = pb[0]
    return {k: tuple(v) for k, v in plans.items()}


def lead_frames(begin, payload, wanted):
    """Frames before `wanted` whose bytes feed its bit reservoir (main_data_begin)."""
    need, count = begin[wanted], 0
    while need > 0 and wanted - count - 1 >= 0:
        count += 1
        need -= payload[wanted - count]
    return count


SEAM_SEARCH = 3   # frames either side a shared cut may move to find the quietest start


def silence_lead(data, lead_bytes):
    """Zero the granule data of every frame in data[:lead_bytes], in place.

    The lead frames exist only so the bit reservoir the first real frame reads
    from is present. Their own audio would replay the previous verse's tail, so
    each granule's part2_3_length and big_values (the first 21 of its 59 side-info
    bits) are cleared; the decoder then emits silence for them while still
    keeping their bytes in the reservoir. Needs MPEG-1 stereo frames, no CRC.
    """
    pos = 0
    while pos < lead_bytes:
        header = int.from_bytes(data[pos:pos + 4], "big")
        if header >> 21 != 0x7FF or ((header >> 16) & 1) == 0 or ((header >> 6) & 3) == 3:
            raise ValueError(f"cannot silence frame at {pos}: {header:08x}")
        for i in range(4):
            bit = 20 + i * 59
            for k in range(21):
                byte, shift = pos + 4 + (bit + k) // 8, 7 - (bit + k) % 8
                data[byte] &= ~(1 << shift) & 0xFF
        length = 144 * BITRATES_K[(header >> 12) & 15] * 1000 // RATE + ((header >> 9) & 1)
        pos += length
    if pos != lead_bytes:
        raise ValueError(f"lead of {lead_bytes} bytes is not whole frames")
    return data


def quiet_seam_frame(pcm, nominal):
    """Frame at which two touching verses meet, chosen where it is quietest.

    The first frame of a slice decodes without its predecessor's overlap, so
    its opening milliseconds are slightly shaped; make that happen where there
    is least sound to shape.
    """
    best, best_score = nominal, None
    for w in range(max(2, nominal - SEAM_SEARCH), nominal + SEAM_SEARCH + 1):
        window = pcm[w * SAMPLES_PER_FRAME:(w + 2) * SAMPLES_PER_FRAME].astype(np.float64)
        if len(window) < 2 * SAMPLES_PER_FRAME:
            continue
        score = (round(float(np.sqrt(np.mean(window * window))), 1), abs(w - nominal))
        if best_score is None or score < best_score:
            best_score, best = score, w
    return best


def _frame_rms(pcm, first, count):
    window = pcm[first * SAMPLES_PER_FRAME:(first + count) * SAMPLES_PER_FRAME].astype(np.float64)
    return float(np.sqrt(np.mean(window * window))) if len(window) else 1e9


def quiet_start_frame(pcm, nominal, sound_start):
    """Where a verse that follows silence begins: the quietest of a few frames before its sound.

    The first frame of a slice decodes without its predecessor's overlap, so its opening
    milliseconds are shaped. That is inaudible only if little is playing there; the nominal
    cut is a fixed lead before the detected sound, and room tone or a decaying tail can still
    be audible at that point. Pick the quietest start that still leaves 40 ms before the sound.
    """
    latest = max(2, sound_start // SAMPLES_PER_FRAME - 2 - (ms_to_samples(40) // SAMPLES_PER_FRAME))
    best, best_score = None, None
    for w in range(max(2, nominal - 6), min(latest, nominal + 3) + 1):
        score = (round(_frame_rms(pcm, w, 2), 1), abs(w - nominal))
        if best_score is None or score < best_score:
            best_score, best = score, w
    return best if best is not None else min(nominal, latest)


def quiet_end_frame(pcm, nominal, sound_end):
    """Where a verse followed by silence ends: the quietest frame boundary after its sound."""
    earliest = -(-sound_end // SAMPLES_PER_FRAME) + 1
    best, best_score = None, None
    for w in range(max(nominal, earliest), max(nominal, earliest) + 7):
        score = (round(_frame_rms(pcm, w - 1, 1), 1), w)
        if best_score is None or score < best_score:
            best_score, best = score, w
    return best


def index_surah(surah, source_dir, record):
    path = source_dir / f"{surah:03d}.mp3"
    offsets, begin, payload = scan_frames(path)
    frames = len(offsets) - 1
    pcm = decode(path)
    if abs(len(pcm) - frames * SAMPLES_PER_FRAME) > 2 * SAMPLES_PER_FRAME:
        raise SystemExit(f"{path}: decoded {len(pcm)} samples vs {frames} frames")
    out = {}
    seams = {}

    def seam(sample):
        if sample not in seams:
            seams[sample] = quiet_seam_frame(pcm, sample // SAMPLES_PER_FRAME)
        return seams[sample]

    for key, (cut_in, cut_out, touching_in, touching_out, sound_in, sound_out) in plan_surah(
            surah, pcm, units_of(surah, record)).items():
        wanted = seam(cut_in) if touching_in else quiet_start_frame(
            pcm, min(frames - 1, cut_in // SAMPLES_PER_FRAME), sound_in)
        first = max(0, wanted - lead_frames(begin, payload, wanted))
        if touching_out:
            last = min(frames, seam(cut_out))  # the next verse's wanted frame: no overlap, no gap
        else:
            last = min(frames, quiet_end_frame(pcm, -(-cut_out // SAMPLES_PER_FRAME) + 1, sound_out))
        out[key] = {
            "startByte": offsets[first],
            "endByte": offsets[last],
            "originSamples": first * SAMPLES_PER_FRAME,
            "durationSamples": (last - first) * SAMPLES_PER_FRAME,
            "leadBytes": offsets[wanted] - offsets[first],
        }
    return surah, {"sourceBytes": offsets[-1], "clips": out}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--source", type=Path, required=True, help="dir of NNN.mp3 surah files")
    parser.add_argument("--alignment", type=Path, default=ALIGNMENT,
                        help="reciter_align artifact for these recordings")
    parser.add_argument("--surahs", default="1-114", help="e.g. 1-114 or 1,2,112")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--write", action="store_true", help="write the committed index (needs all 114)")
    parser.add_argument("--out", type=Path, help="write the index here instead")
    args = parser.parse_args()

    aligned = alignment_artifact.load(args.alignment)
    if aligned["reciter"] != SLUG:
        sys.exit(f"{args.alignment} aligns {aligned['reciter']}, not {SLUG}")
    surahs = []
    for part in args.surahs.split(","):
        lo, _, hi = part.partition("-")
        surahs.extend(range(int(lo), int(hi or lo) + 1))

    sources = {}
    for s in surahs:
        path = args.source / f"{s:03d}.mp3"
        if not path.is_file() or path.stat().st_size == 0:
            sys.exit(f"missing source {path}")
        sources[s] = {"sha256": sha256_file(path), "bytes": path.stat().st_size}
        pinned = aligned["sources"][str(s)]
        if (sources[s]["sha256"], sources[s]["bytes"]) != (pinned["sha256"], pinned["bytes"]):
            sys.exit(f"{path} is not the recording the alignment was made from")

    clips = {}
    with ThreadPoolExecutor(args.jobs) as pool:
        futures = [pool.submit(index_surah, s, args.source, aligned["surahs"][str(s)]) for s in surahs]
        for future in futures:
            surah, result = future.result()
            sources[surah]["audioBytes"] = result["sourceBytes"]
            for key, clip in result["clips"].items():
                clips[key] = {**clip, "surah": surah}
            print(f"  surah {surah}: {len(result['clips'])} verses", flush=True)

    index = {
        "schema": 2,
        "reciterSlug": SLUG,
        "sampleRateHz": RATE,
        "samplesPerFrame": SAMPLES_PER_FRAME,
        "format": "MPEG-1 Layer III 44.1 kHz CBR (128 kbps; surahs 8 and 9 are 96 kbps); byte ranges start on a frame header",
        "alignment": {"file": ALIGNMENT.name, "sha256": sha256_file(args.alignment)},
        "sourceUrlTemplate": SOURCE_URL,
        "sources": {str(s): sources[s] for s in sorted(sources)},
        "clips": {k: clips[k] for k in sorted(clips, key=lambda k: tuple(map(int, k.split(":"))))},
    }
    target = INDEX if args.write else args.out
    if args.write:
        durations = {
            k: c["durationSamples"] * 1000 // RATE for k, c in index["clips"].items()
        }
        (ONSETS_DIR / f"{SLUG}.json").write_text(json.dumps({
            "schema": 2,
            "reciterId": RECITER_ID,
            "reciterSlug": SLUG,
            "detector": {"source": "tools/build_tawfeek_verse_index.py", "minimumOffsetMs": 250},
            "scannedAyahs": len(durations),
            "offsets": {},
            "durations": durations,
        }, separators=(",", ":")) + "\n")
    if args.write and len(clips) != 6236:
        sys.exit(f"refusing to write a partial index: {len(clips)}/6236 verses")
    if target:
        target.write_text(json.dumps(index, separators=(",", ":")) + "\n")
        print(f"wrote {target}")
    print(f"{len(clips)} verses, {sum(c['endByte'] - c['startByte'] for c in clips.values()) / 1e6:.0f} MB of ranges")


if __name__ == "__main__":
    main()
