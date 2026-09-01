#!/usr/bin/env python3
"""Build the compact, offline Quran concept-search asset from pinned QSAC data."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import sqlite3
import urllib.request
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_COMMIT = "cb3852b127bfdda6668c5eec9e5c1d9cdcde3810"
SOURCE_ROOT = (
    "https://raw.githubusercontent.com/dev-ahmadbilal/"
    f"quran-semantic-annotation-corpus/{SOURCE_COMMIT}/data"
)
SOURCES = {
    "qsac-dataset.csv": "e242e15533eea643a5181006501d56fb95b8674362e7b18ea16c08793471b7a2",
    "qsac-ontology.json": "95637f6ee8be5d7eb234820a2149805eae51e43d5526343290cca51fec2a7524",
}


def source_bytes(name: str) -> bytes:
    cache = ROOT / "tools" / ".cache" / f"qsac-{SOURCE_COMMIT[:12]}-{name}"
    if not cache.is_file():
        cache.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(f"{SOURCE_ROOT}/{name}") as response:
            cache.write_bytes(response.read())
    data = cache.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != SOURCES[name]:
        raise ValueError(f"{name}: expected {SOURCES[name]}, got {digest}")
    return data


def main() -> None:
    ontology = json.loads(source_bytes("qsac-ontology.json"))
    concepts = {}
    for domain in ontology["domains"]:
        for category in domain["categories"]:
            for tag in category["tags"]:
                concepts[tag["name"]] = {
                    "n": tag["name"],
                    "p": tag["keywords"]["primary"],
                    "s": tag["keywords"]["secondary"],
                    "c": category["name"],
                    "d": domain["name"],
                    "a": [],
                }

    assignments: dict[str, list[int]] = defaultdict(list)
    text = source_bytes("qsac-dataset.csv").decode()
    rows = csv.DictReader(line for line in io.StringIO(text) if not line.startswith("#"))
    seen = set()
    for row in rows:
        key = int(row["surah"]) * 1_000 + int(row["ayah"])
        if key in seen:
            raise ValueError(f"duplicate ayah {row['surah']}:{row['ayah']}")
        seen.add(key)
        for tag in row["tags"].split("|"):
            if tag not in concepts:
                raise ValueError(f"unknown ontology tag: {tag}")
            assignments[tag].append(key)

    with sqlite3.connect(ROOT / "data" / "quran.db") as db:
        expected = {surah * 1_000 + ayah for surah, ayah in db.execute(
            "SELECT surah_id, ayah_number FROM ayahs"
        )}
    if seen != expected:
        raise ValueError(
            f"QSAC/Quran ayah mismatch: missing={sorted(expected - seen)[:5]}, "
            f"extra={sorted(seen - expected)[:5]}"
        )

    for name, ayahs in assignments.items():
        concepts[name]["a"] = ayahs
    asset = {
        "version": 1,
        "source": "QSAC 1.0",
        "sourceCommit": SOURCE_COMMIT,
        "concepts": list(concepts.values()),
    }
    output = ROOT / "data" / "search_concepts.json"
    output.write_text(json.dumps(asset, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(
        f"Wrote {output.relative_to(ROOT)}: {len(concepts)} concepts, "
        f"{len(seen)} ayahs, {sum(map(len, assignments.values()))} assignments"
    )


if __name__ == "__main__":
    main()
