#!/usr/bin/env python3
"""Deterministic unit tests for the shipped-DB timing delta verifier."""
from __future__ import annotations

import json
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

from timing_delta import (
    accepted_changes,
    build_delta,
    corpus_payload_hash,
    load_corpus_bootstraps,
    load_verdict_ledger,
    read_timing_rows,
    rejected_changes,
    row_key_string,
)


def make_db(path: Path, rows: list[tuple[str, int, int, list[list[int]], int]]) -> None:
    connection = sqlite3.connect(path)
    try:
        connection.executescript(
            """
            CREATE TABLE reciters (id INTEGER PRIMARY KEY, slug TEXT NOT NULL);
            CREATE TABLE timings (
                reciter_id INTEGER NOT NULL,
                surah_id INTEGER NOT NULL,
                ayah_number INTEGER NOT NULL,
                segments TEXT NOT NULL,
                audio_onset_ms INTEGER NOT NULL
            );
            """
        )
        slugs = sorted({row[0] for row in rows})
        identifiers = {slug: index + 1 for index, slug in enumerate(slugs)}
        connection.executemany(
            "INSERT INTO reciters(id, slug) VALUES (?, ?)",
            [(identifier, slug) for slug, identifier in identifiers.items()],
        )
        connection.executemany(
            "INSERT INTO timings VALUES (?, ?, ?, ?, ?)",
            [
                (identifiers[slug], surah, ayah, json.dumps(segments), onset)
                for slug, surah, ayah, segments, onset in rows
            ],
        )
        connection.commit()
    finally:
        connection.close()


def timing_rows():
    return [
        ("beta", 1, 1, [[1, 0, 100], [2, 100, 200]], 10),
        ("beta", 1, 2, [[1, 0, 100], [2, 100, 200]], 10),
        ("beta", 1, 3, [[1, 0, 100], [2, 100, 200]], 10),
        ("beta", 1, 4, [[1, 0, 100]], 10),
        ("beta", 1, 6, [[1, 0, 100]], 10),
    ]


def test_classification_and_canonical_hashing(temp: Path) -> None:
    old_path = temp / "old.db"
    new_path = temp / "new.db"
    make_db(old_path, timing_rows())
    make_db(
        new_path,
        [
            # Same semantic JSON as old: a whitespace-only serialization is not a delta.
            ("beta", 1, 1, [[1, 0, 100], [2, 100, 200]], 10),
            # Bounds changed with identical occurrence sequence.
            ("beta", 1, 2, [[1, 0, 120], [2, 120, 200]], 10),
            # A repeat is a topology change; onset may change too.
            ("beta", 1, 3, [[1, 0, 100], [2, 100, 200], [2, 200, 300]], 20),
            # A second unchanged row makes absence at 1:6 unambiguous.
            ("beta", 1, 4, [[1, 0, 100]], 10),
            # New row.
            ("beta", 1, 5, [[1, 0, 100]], 10),
            # 1:6 intentionally absent: withheld.
        ],
    )
    report = build_delta(read_timing_rows(old_path), read_timing_rows(new_path))
    assert report["summary"] == {
        "oldRows": 5,
        "newRows": 5,
        "unchangedRows": 2,
        "changedRows": 4,
        "byKind": {"added": 1, "onset": 1, "timestamp": 1, "topology": 1, "withheld": 1},
    }
    changes = {change["key"]: change for change in report["changes"]}
    assert changes["beta:1:2"]["kinds"] == ["timestamp"]
    assert changes["beta:1:3"]["kinds"] == ["topology", "onset"]
    assert changes["beta:1:5"]["kinds"] == ["added"]
    assert changes["beta:1:6"]["kinds"] == ["withheld"]
    assert changes["beta:1:2"]["old"]["topologyHash"] == changes["beta:1:2"]["new"]["topologyHash"]
    assert changes["beta:1:2"]["old"]["payloadHash"] != changes["beta:1:2"]["new"]["payloadHash"]
    assert row_key_string(("beta", 1, 2)) == "beta:1:2"


def test_ledger_forms_and_fail_closed_lookup(temp: Path) -> None:
    path = temp / "ledger.json"
    path.write_text(
        json.dumps(
            {
                "verdicts": {
                    "beta:1:2": {
                        "verdict": "accept",
                        "kinds": ["timestamp"],
                        "baselinePayloadHash": "a",
                        "candidatePayloadHash": "b",
                        "evidence": {
                            "kind": "duration_tail_clip",
                            "summary": "Only the final fade is past the measured PCM.",
                            "artifact": "fixture",
                            "audioSha256": "0" * 64,
                            "measuredDurationMs": 90,
                        },
                    },
                    "beta:1:3": {"verdict": "reject"},
                }
            }
        ),
        encoding="utf-8",
    )
    ledger = load_verdict_ledger(path)
    report = build_delta(
        {("beta", 1, 2): {"segments": [[1, 0, 100]], "audioOnsetMs": 0, "payloadHash": "a", "topologyHash": "a"}},
        {("beta", 1, 2): {"segments": [[1, 0, 90]], "audioOnsetMs": 0, "payloadHash": "b", "topologyHash": "a"}},
        ledger,
    )
    assert report["changes"][0]["verdict"]["evidence"]["kind"] == "duration_tail_clip"
    assert accepted_changes(report["changes"]) == []

    list_path = temp / "ledger-list.json"
    list_path.write_text(
        json.dumps([{"reciter": "beta", "surah": 1, "ayah": 3, "verdict": "accept"}]),
        encoding="utf-8",
    )
    assert load_verdict_ledger(list_path) == {"beta:1:3": {"verdict": "accept"}}


def test_cli_requires_explicit_acceptance(temp: Path) -> None:
    old_path = temp / "cli-old.db"
    new_path = temp / "cli-new.db"
    ledger_path = temp / "cli-ledger.json"
    make_db(old_path, [("beta", 1, 1, [[1, 0, 100]], 0)])
    make_db(new_path, [("beta", 1, 1, [[1, 0, 99]], 0)])
    command = [sys.executable, str(Path(__file__).with_name("timing_delta.py")), str(old_path), str(new_path), "--require-accepted"]
    rejected = subprocess.run(command, text=True, capture_output=True, check=False)
    assert rejected.returncode == 2
    assert "lack a valid acceptance" in rejected.stderr

    change = json.loads(rejected.stdout)["changes"][0]
    ledger_path.write_text(
        json.dumps(
            {
                "beta:1:1": {
                    "verdict": "accept",
                    "kinds": ["timestamp"],
                    "baselinePayloadHash": change["old"]["payloadHash"],
                    "candidatePayloadHash": change["new"]["payloadHash"],
                    "evidence": {
                        "kind": "duration_tail_clip",
                        "summary": "Only the final segment is shortened.",
                        "artifact": "fixture",
                        "audioSha256": "0" * 64,
                        "measuredDurationMs": 99,
                    },
                }
            }
        ),
        encoding="utf-8",
    )
    accepted = subprocess.run(command + ["--ledger", str(ledger_path)], text=True, capture_output=True, check=False)
    assert accepted.returncode == 0
    assert json.loads(accepted.stdout)["summary"]["changedRows"] == 1


def test_locked_new_reciter_corpus(temp: Path) -> None:
    old_path = temp / "corpus-old.db"
    new_path = temp / "corpus-new.db"
    manifest_path = temp / "corpus.json"
    make_db(old_path, [("beta", 1, 1, [[1, 0, 100]], 0)])
    make_db(
        new_path,
        [
            ("beta", 1, 1, [[1, 0, 100]], 0),
            ("new_voice", 1, 1, [[1, 0, 100]], 0),
            ("new_voice", 1, 2, [[1, 10, 110]], 0),
        ],
    )
    report = build_delta(read_timing_rows(old_path), read_timing_rows(new_path))
    corpus = report["changes"]
    entry = {
        "reciter": "new_voice",
        "verdict": "accept",
        "expectedRows": 2,
        "corpusPayloadSha256": corpus_payload_hash(corpus),
        "evidence": {
            "kind": "pinned_corpus_bootstrap",
            "summary": "Complete fixture corpus from a locked source.",
            "artifact": "fixture",
            "sources": [{"url": "https://example.test/source.zip", "sha256": "0" * 64}],
        },
    }
    manifest_path.write_text(json.dumps({"corpora": [entry]}), encoding="utf-8")
    bootstraps = load_corpus_bootstraps(manifest_path)
    assert rejected_changes(corpus, bootstraps) == []

    stale = json.loads(json.dumps(entry))
    stale["corpusPayloadSha256"] = "f" * 64
    assert len(rejected_changes(corpus, [stale])) == 2

    partial = json.loads(json.dumps(entry))
    partial["expectedRows"] = 1
    assert len(rejected_changes(corpus, [partial])) == 2

    extended_path = temp / "corpus-extended.db"
    make_db(
        extended_path,
        [
            ("beta", 1, 1, [[1, 0, 100]], 0),
            ("beta", 1, 2, [[1, 0, 100]], 0),
        ],
    )
    extension = build_delta(read_timing_rows(old_path), read_timing_rows(extended_path))["changes"]
    partial_existing = json.loads(json.dumps(entry))
    partial_existing["reciter"] = "beta"
    partial_existing["expectedRows"] = 1
    partial_existing["corpusPayloadSha256"] = corpus_payload_hash(extension)
    assert len(rejected_changes(extension, [partial_existing])) == 1

    changed_path = temp / "corpus-changed.db"
    make_db(changed_path, [("beta", 1, 1, [[1, 0, 99]], 0)])
    existing = build_delta(read_timing_rows(old_path), read_timing_rows(changed_path))["changes"]
    forged = json.loads(json.dumps(entry))
    forged["reciter"] = "beta"
    forged["expectedRows"] = 1
    forged["corpusPayloadSha256"] = corpus_payload_hash(existing)
    assert len(rejected_changes(existing, [forged])) == 1


def main() -> None:
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        test_classification_and_canonical_hashing(temporary)
        test_ledger_forms_and_fail_closed_lookup(temporary)
        test_cli_requires_explicit_acceptance(temporary)
        test_locked_new_reciter_corpus(temporary)
    print("all timing delta tests pass")


if __name__ == "__main__":
    main()
