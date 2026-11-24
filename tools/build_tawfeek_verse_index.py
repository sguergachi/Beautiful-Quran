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


def _frames_ms(ms):
    return -(-ms_to_samples(ms) // SAMPLES_PER_FRAME)


def _seam_score(pcm, frame):
    """How loud the two frames that open a slice at `frame` are (they decode without overlap)."""
    return round(_frame_rms(pcm, frame, 2), 1)


def plan_cuts(surah, pcm, units, frames):
    """{verseKey: (wanted_frame, end_frame)}: where each verse's slice begins and ends.

    The invariant that matters: a verse ends no later than the next one begins, so playing
    verses back to back never replays source audio (slices that overlapped repeated the end of
    one verse at the start of the next). Where the pause between two verses is short they share
    one seam, cut where the recording is quietest; where it is long each keeps only a little
    room, never past its own sound.
    """
    F = SAMPLES_PER_FRAME
    spans = {}
    for lo, hi, kind, key in units:
        if kind == "verse":
            old = spans.get(key)
            spans[key] = (lo, hi) if old is None else (min(old[0], lo), max(old[1], hi))
    seq = sorted(
        [(lo, hi, "verse", key) for key, (lo, hi) in spans.items()]
        + [u for u in units if u[2] != "verse"]
    )

    def windows(i):
        """(start window, end window) in frames for the verse at seq[i]."""
        lo, hi = seq[i][0], seq[i][1]
        prev = seq[i - 1] if i else None
        nxt = seq[i + 1] if i + 1 < len(seq) else None
        s1 = max(2, ms_to_samples(lo - 40) // F - 2)               # shaped opening frames end before the sound
        s0 = max(s1 - _frames_ms(LEAD_CAP_MS), -(-ms_to_samples(prev[1]) // F) if prev else 2, 2)
        e0 = -(-ms_to_samples(hi) // F) + 1                         # one frame of decay past the detected end
        e1 = min(e0 + _frames_ms(TAIL_CAP_MS), ms_to_samples(nxt[0]) // F if nxt else frames, frames)
        return (min(s0, s1), s1), (e0, max(e0, e1))

    seam_cache = {}

    def shared_seam(i):
        """Seam between seq[i] and seq[i+1] (both verses)."""
        if i not in seam_cache:
            _, (e0, e1) = windows(i)
            (s0, s1), _ = windows(i + 1)
            lo_f, hi_f = max(e0, s0), min(e1, s1)
            if lo_f <= hi_f:
                best = min(range(lo_f, hi_f + 1),
                           key=lambda f: (_seam_score(pcm, f), abs(f - (lo_f + hi_f) / 2)))
            else:   # their sounds touch or overlap: the quietest frame near where they meet
                best = quiet_seam_frame(pcm, (e0 + s1) // 2)
            seam_cache[i] = (best, lo_f <= hi_f)
        return seam_cache[i]

    cuts = {}
    for i, (lo, hi, kind, key) in enumerate(seq):
        if kind != "verse":
            continue
        (s0, s1), (e0, e1) = windows(i)
        prev = seq[i - 1] if i else None
        nxt = seq[i + 1] if i + 1 < len(seq) else None
        if prev and prev[2] == "verse" and shared_seam(i - 1)[1]:
            wanted = shared_seam(i - 1)[0]
        elif prev and prev[2] == "verse":
            wanted = shared_seam(i - 1)[0] if windows(i - 1)[1][0] > s1 else min(
                range(s0, s1 + 1), key=lambda f: (_seam_score(pcm, f), abs(f - s1)))
        else:
            wanted = min(range(s0, s1 + 1), key=lambda f: (_seam_score(pcm, f), abs(f - s1)))
        if nxt and nxt[2] == "verse" and shared_seam(i)[1]:
            end = shared_seam(i)[0]
        elif nxt and nxt[2] == "verse" and windows(i + 1)[0][1] < e0:
            end = shared_seam(i)[0]
        else:
            end = min(range(e0, e1 + 1), key=lambda f: (round(_frame_rms(pcm, f - 1, 1), 1), f))
        cuts[key] = (wanted, min(frames, end))
    return cuts


def index_surah(surah, source_dir, record):
    path = source_dir / f"{surah:03d}.mp3"
    offsets, begin, payload = scan_frames(path)
    frames = len(offsets) - 1
    pcm = decode(path)
    if abs(len(pcm) - frames * SAMPLES_PER_FRAME) > 2 * SAMPLES_PER_FRAME:
        raise SystemExit(f"{path}: decoded {len(pcm)} samples vs {frames} frames")
    out = {}
    for key, (wanted, last) in plan_cuts(surah, pcm, units_of(surah, record), frames).items():
        wanted = min(wanted, frames - 1)
        first = max(0, wanted - lead_frames(begin, payload, wanted))
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
