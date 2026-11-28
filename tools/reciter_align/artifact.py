"""The committed alignment file: one reciter, every surah, pinned to its source audio."""

from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path

SCHEMA = 1


def sha256_file(path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def surah_path(work: Path, surah: int) -> Path:
    return Path(work) / f"surah_{surah:03d}.json"


def save_surah(work: Path, surah: int, record: dict) -> None:
    Path(work).mkdir(parents=True, exist_ok=True)
    surah_path(work, surah).write_text(json.dumps(record, separators=(",", ":")), encoding="utf-8")


def load_surah(work: Path, surah: int) -> dict:
    return json.loads(surah_path(work, surah).read_text(encoding="utf-8"))


def pack(work: Path, out: Path, reciter: str, models: dict, sources: dict, surahs=range(1, 115)) -> dict:
    """Merge per-surah records into the single committed artifact (gzip JSON)."""
    payload = {
        "schema": SCHEMA,
        "reciter": reciter,
        "models": models,
        "sources": {str(s): sources[s] for s in surahs},
        "surahs": {str(s): load_surah(work, s) for s in surahs},
    }
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    Path(out).write_bytes(gzip.compress(raw, 9, mtime=0))
    return payload


def load(path: Path) -> dict:
    payload = json.loads(gzip.decompress(Path(path).read_bytes()))
    if payload.get("schema") != SCHEMA:
        raise ValueError(f"{path}: unsupported alignment schema {payload.get('schema')}")
    return payload
