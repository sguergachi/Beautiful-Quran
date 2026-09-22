#!/usr/bin/env python3
"""Map EveryAyah's Yasser clips onto the exact QUA whole-surah clock.

EveryAyah cut its per-ayah MP3s directly from the same CBR recordings used by
Qur'anic Universal Audio.  Their published split text is only an editing hint;
some entries differ from the MP3 frame actually shipped by hundreds of
milliseconds.  This tool byte-matches each clip's first audio frames back to
the pinned source recording and emits the exact source-clock origin consumed
by ``build_db.py``.

Inputs are deliberately local directories so regeneration never hides a
1.4 GB download inside the database build:

    python3 tools/build_yasser_clock_map.py \
      --whole-root /tmp/yasser-whole \
      --prefix-root /tmp/yasser-prefixes

``whole-root`` contains ``001.mp3`` … ``114.mp3`` from the QUA catalog.
``prefix-root`` contains the first 8192 bytes of each EveryAyah file, named
``001001.bin`` … ``114006.bin``.  Five known clips do not byte-match and stay
behind independent dual-model timing supplements.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from detect_audio_onsets import (
    frame_size,
    has_following_frame,
    id3_size,
    parse_frame_header,
)


TOOLS = Path(__file__).resolve().parent
DEFAULT_OUTPUT = TOOLS / "timing_sources" / "yasser-everyayah-clock.json"
SAMPLE_RATE = 44_100
SAMPLES_PER_FRAME = 1_152
MATCH_BYTES = 1_500
EXPECTED_UNMATCHED = ["1:1", "39:47", "39:50", "50:21", "92:16"]


def first_audio_frame(data: bytes) -> int:
    """Return the first MPEG frame backed by a second valid frame."""
    offset = id3_size(data)
    while offset + 4 < len(data):
        header = parse_frame_header(data, offset)
        if header and has_following_frame(data, offset, header):
            return offset
        offset += 1
    raise ValueError("no consecutive MPEG frames")


def source_frame_indexes(data: bytes) -> dict[int, int]:
    """Map source byte offsets to their zero-based decoded frame index."""
    offset = first_audio_frame(data)
    indexes: dict[int, int] = {}
    frame = 0
    while offset + 4 < len(data):
        header = parse_frame_header(data, offset)
        if header is None:
            break
        if (header[0], header[2]) != (3, SAMPLE_RATE):
            raise ValueError("Yasser source is not MPEG-1 Layer III at 44.1 kHz")
        indexes[offset] = frame
        offset += frame_size(header)
        frame += 1
    return indexes


def matching_frames(source: bytes, fragment: bytes, indexes: dict[int, int]) -> list[int]:
    """Return source-frame offsets carrying an exact clip byte fragment."""
    matches = []
    offset = source.find(fragment)
    while offset >= 0:
        if offset in indexes:
            matches.append(offset)
        offset = source.find(fragment, offset + 1)
    return matches


def corpus_hash(items: list[tuple[str, str]]) -> str:
    """Bind a sorted corpus of file identifiers and byte hashes."""
    encoded = json.dumps(sorted(items), separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(encoded.encode()).hexdigest()


def build_clock_map(whole_root: Path, prefix_root: Path) -> dict:
    origins: dict[str, int] = {}
    unmatched: list[str] = []
    ambiguous: list[str] = []
    whole_hashes: list[tuple[str, str]] = []
    prefix_hashes: list[tuple[str, str]] = []
    for surah in range(1, 115):
        source_path = whole_root / f"{surah:03}.mp3"
        source = source_path.read_bytes()
        whole_hashes.append((source_path.name, hashlib.sha256(source).hexdigest()))
        indexes = source_frame_indexes(source)
        for prefix_path in sorted(prefix_root.glob(f"{surah:03}[0-9][0-9][0-9].bin")):
            ayah = int(prefix_path.stem[3:])
            key = f"{surah}:{ayah}"
            prefix = prefix_path.read_bytes()
            prefix_hashes.append((prefix_path.name, hashlib.sha256(prefix).hexdigest()))
            clip_start = first_audio_frame(prefix)
            matches = matching_frames(
                source, prefix[clip_start:clip_start + MATCH_BYTES], indexes
            )
            if not matches:
                unmatched.append(key)
                continue
            if len(matches) != 1:
                ambiguous.append(key)
                continue
            frame = indexes[matches[0]]
            origins[key] = round(frame * SAMPLES_PER_FRAME * 1000 / SAMPLE_RATE)
    if ambiguous:
        raise SystemExit(f"ambiguous clip matches: {ambiguous}")
    if sorted(unmatched, key=lambda key: tuple(map(int, key.split(":")))) != EXPECTED_UNMATCHED:
        raise SystemExit(f"unexpected unmatched clips: {unmatched}")
    if len(origins) + len(unmatched) != 6_236:
        raise SystemExit(f"incomplete clip corpus: {len(origins)} matched + {len(unmatched)} unmatched")
    return {
        "schema": 1,
        "reciterSlug": "Yasser_Ad-Dussary_128kbps",
        "sampleRateHz": SAMPLE_RATE,
        "samplesPerFrame": SAMPLES_PER_FRAME,
        "matchBytes": MATCH_BYTES,
        "matchedAyahs": len(origins),
        "unmatchedAyahs": EXPECTED_UNMATCHED,
        "wholeAudioCorpusSha256": corpus_hash(whole_hashes),
        "clipPrefixCorpusSha256": corpus_hash(prefix_hashes),
        "sourceOriginsMs": origins,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--whole-root", type=Path, required=True)
    parser.add_argument("--prefix-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    payload = build_clock_map(args.whole_root, args.prefix_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {payload['matchedAyahs']} origins to {args.output}")


if __name__ == "__main__":
    main()
