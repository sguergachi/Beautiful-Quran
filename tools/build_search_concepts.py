#!/usr/bin/env python3
"""Build the offline Quran concept and thesaurus search asset."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import re
import sqlite3
import urllib.request
import zipfile
from collections import Counter, defaultdict
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
WORDNET_RELEASE = "2025"
WORDNET_URL = f"https://en-word.net/static/english-wordnet-{WORDNET_RELEASE}.zip"
WORDNET_SHA256 = "38b16326159f51853626b7d24a44c453fa88ab33f06fce5ec8fc5996d1c2be93"
WORD_RE = re.compile(r"[A-Za-z][A-Za-z'-]*")
THESAURUS_FILLERS = {
    "all", "and", "are", "been", "being", "but", "can", "could", "for", "from",
    "have", "how", "may", "might", "not", "shall", "should", "that", "the", "their",
    "then", "there", "they", "this", "was", "were", "what", "when", "where", "who",
    "why", "will", "with", "would", "you", "your",
}
FOCUSED_THESAURUS_LINKS = {
    "calm": {"peace": 2, "stillness": 2},
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


def wordnet_bytes() -> bytes:
    cache = ROOT / "tools" / ".cache" / f"english-wordnet-{WORDNET_RELEASE}.zip"
    if not cache.is_file():
        cache.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(WORDNET_URL) as response:
            cache.write_bytes(response.read())
    data = cache.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != WORDNET_SHA256:
        raise ValueError(f"Open English WordNet: expected {WORDNET_SHA256}, got {digest}")
    return data


def build_thesaurus(db: sqlite3.Connection) -> dict[str, list[list[str | int]]]:
    """Map WordNet lemmas to focused related words that occur in this Quran."""
    synsets: dict[str, list[str]] = {}
    lemma_synsets: dict[str, set[str]] = defaultdict(set)
    relations: dict[str, dict[str, set[str]]] = {
        symbol: defaultdict(set) for symbol in ("&", "~")
    }
    lexical_pointers: list[tuple[str, str, str]] = []
    with zipfile.ZipFile(io.BytesIO(wordnet_bytes())) as archive:
        for part in ("noun", "verb", "adj", "adv"):
            for raw in archive.read(f"oewn{WORDNET_RELEASE}/data.{part}").decode().splitlines():
                if not raw or not raw[0].isdigit():
                    continue
                fields = raw.split("|", 1)[0].split()
                node = f"{'a' if fields[2] == 's' else fields[2]}:{fields[0]}"
                word_count = int(fields[3], 16)
                at = 4
                words = []
                for _ in range(word_count):
                    words.append(fields[at].lower())
                    at += 2
                synsets[node] = words
                for word in words:
                    if "_" not in word:
                        lemma_synsets[word].add(node)
                pointer_count = int(fields[at])
                at += 1
                for _ in range(pointer_count):
                    symbol, offset, pos, _source_target = fields[at:at + 4]
                    at += 4
                    other = f"{'a' if pos == 's' else pos}:{offset}"
                    if symbol == "+" and _source_target != "0000":
                        lexical_pointers.append((node, other, _source_target))
                    elif symbol == "&":
                        relations[symbol][node].add(other)
                        relations[symbol][other].add(node)
                    elif symbol == "~":
                        relations[symbol][node].add(other)

    lexical_relations: dict[str, set[str]] = defaultdict(set)
    for source, target, source_target in lexical_pointers:
        source_at = int(source_target[:2], 16) - 1
        target_at = int(source_target[2:], 16) - 1
        if source_at not in range(len(synsets[source])) or target_at not in range(len(synsets[target])):
            continue
        source_word = synsets[source][source_at]
        target_word = synsets[target][target_at]
        lexical_relations[source_word].add(target_word)
        lexical_relations[target_word].add(source_word)

    frequency = Counter(
        word.lower().strip("'-")
        for (text,) in db.execute(
            "SELECT translation_en FROM ayahs UNION ALL SELECT translation_en FROM words"
        )
        for word in WORD_RE.findall(text)
    )
    targets = {
        word for word in frequency
        if len(word) >= 3 and word not in THESAURUS_FILLERS and
        word in lemma_synsets and len(lemma_synsets[word]) <= 3
    }
    thesaurus = {}
    for query in sorted(lemma_synsets):
        if len(query) < 3:
            continue
        direct = lemma_synsets[query]
        related = {}
        for distance, nodes in (
            (0, direct),
            (1, {neighbor for node in direct for neighbor in relations["&"][node]}),
            (2, {neighbor for node in direct for neighbor in relations["~"][node]}),
        ):
            for node in nodes:
                for word in synsets.get(node, ()):
                    if word in targets and word != query:
                        related[word] = min(distance, related.get(word, distance))
        for word in lexical_relations[query]:
            if word in targets and word != query:
                related[word] = min(1, related.get(word, 1))
        for word, distance in FOCUSED_THESAURUS_LINKS.get(query, {}).items():
            if frequency[word] and word != query:
                related[word] = min(distance, related.get(word, distance))
        ranked = sorted(
            related.items(),
            key=lambda item: (
                item[1], len(lemma_synsets[item[0]]), -frequency[item[0]], item[0]
            ),
        )[:12]
        if ranked:
            thesaurus[query] = [[word, distance] for word, distance in ranked]
    return thesaurus


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
        thesaurus = build_thesaurus(db)
    if seen != expected:
        raise ValueError(
            f"QSAC/Quran ayah mismatch: missing={sorted(expected - seen)[:5]}, "
            f"extra={sorted(seen - expected)[:5]}"
        )

    for name, ayahs in assignments.items():
        concepts[name]["a"] = ayahs
    asset = {
        "version": 2,
        "source": "QSAC 1.0",
        "sourceCommit": SOURCE_COMMIT,
        "thesaurusSource": f"Open English WordNet {WORDNET_RELEASE}",
        "thesaurusSha256": WORDNET_SHA256,
        "concepts": list(concepts.values()),
        "thesaurus": thesaurus,
    }
    output = ROOT / "data" / "search_concepts.json"
    output.write_text(json.dumps(asset, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(
        f"Wrote {output.relative_to(ROOT)}: {len(concepts)} concepts, "
        f"{len(seen)} ayahs, {sum(map(len, assignments.values()))} assignments, "
        f"{len(thesaurus)} thesaurus entries"
    )


if __name__ == "__main__":
    main()
