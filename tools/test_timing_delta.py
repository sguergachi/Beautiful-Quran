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


def rebase_problem(new_segments, evidence, old_segments=None):
    """Return why the gate refuses a file_clock_rebase row, or None if it accepts."""
    old_segments = old_segments or [[1, 0, 1000], [2, 1000, 2000], [3, 2000, 3040]]
    entry = {
        "verdict": "accept",
        "kinds": ["timestamp"],
        "baselinePayloadHash": "a",
        "candidatePayloadHash": "b",
        "evidence": {
            "kind": "file_clock_rebase",
            "summary": "Every boundary moves by one offset onto the streamed file's clock.",
            "artifact": "fixture",
            "audioSha256": "0" * 64,
            **evidence,
        },
    }
    report = build_delta(
        {("beta", 1, 1): {"segments": old_segments, "audioOnsetMs": 0, "payloadHash": "a", "topologyHash": "t"}},
        {("beta", 1, 1): {"segments": new_segments, "audioOnsetMs": 0, "payloadHash": "b", "topologyHash": "t"}},
        {"beta:1:1": entry},
    )
    rejected = rejected_changes(report["changes"])
    return rejected[0][1] if rejected else None


def test_file_clock_rebase_evidence(temp: Path) -> None:
    # The true shape: one 60 ms offset everywhere, the opening restored from the
    # source's clamped 0, and a 20 ms final fade clipped to the measured length.
    accepted = [[1, 300, 1060], [2, 1060, 2060], [3, 2060, 3080]]
    good = {"clockOffsetMs": 60, "openingStartMs": 300, "measuredDurationMs": 3080}
    assert rebase_problem(accepted, good) is None

    # A row that is re-timed rather than moved: one boundary walks on its own.
    walked = [[1, 300, 1060], [2, 1060, 2075], [3, 2075, 3080]]
    assert "one offset" in rebase_problem(walked, good)

    # The tail may only land on the file's own measured duration.
    assert "measured duration" in rebase_problem(accepted, {**good, "measuredDurationMs": 3000})

    # And the fade it clips has to be a fade, not a second of recitation.
    over = [[1, 300, 1060], [2, 1060, 2060], [3, 2060, 3000]]
    assert "final fade of at most" in rebase_problem(over, {**good, "measuredDurationMs": 3000})

    # An opening off the shared offset is a judgement, so it must be written down,
    bare = {k: v for k, v in good.items() if k != "openingStartMs"}
    assert "recorded, one way" in rebase_problem(accepted, bare)
    # and a restoration may only move later than the offset put it.
    early = [[1, 30, 1060], [2, 1060, 2060], [3, 2060, 3080]]
    assert "only move later" in rebase_problem(early, {**good, "openingStartMs": 30})

    # The other lawful opening is the measured start of the voice, which sits
    # back towards the source. It may not be claimed for an arbitrary boundary.
    pinned = {**bare, "measuredOnsetMs": 30}
    assert rebase_problem(early, pinned) is None
    assert "towards the measured voice" in rebase_problem(accepted, {**bare, "measuredOnsetMs": 300})

    # Silence is not an offset, and a topology change is never a rebase.
    assert "non-zero integer" in rebase_problem(accepted, {**good, "clockOffsetMs": 0})
    grown = [[1, 300, 1060], [2, 1060, 2060], [2, 2060, 2070], [3, 2070, 3080]]
    assert "positions" in rebase_problem(grown, good) or "timestamps" in rebase_problem(grown, good)


def main() -> None:
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        test_classification_and_canonical_hashing(temporary)
        test_ledger_forms_and_fail_closed_lookup(temporary)
        test_file_clock_rebase_evidence(temporary)
        test_cli_requires_explicit_acceptance(temporary)
    print("all timing delta tests pass")


if __name__ == "__main__":
    main()
