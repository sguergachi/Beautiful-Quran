#!/usr/bin/env python3
"""Produce a canonical, reviewable timing delta between two Quran databases.

The database is the shipped timing contract.  This tool deliberately compares
that contract rather than inferring changes from pipeline inputs.  A change is
classified by its observable effect:

* ``topology``: the ordered word-position occurrence sequence changed;
* ``timestamp``: positions stayed the same but one or more segment bounds did
  not;
* ``onset``: only the audio onset changed (or it changed alongside another
  kind);
* ``added`` / ``withheld``: a timing row appeared / disappeared.

Each payload is parsed and canonically re-encoded before hashing, so formatting
differences in SQLite JSON cannot hide a timing change.  A verdict ledger is
optional, but ``--require-accepted`` makes it a fail-closed review gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


RowKey = tuple[str, int, int]


def row_key_string(key: RowKey) -> str:
    """Return the stable ledger key for a reciter/ayah timing row."""
    slug, surah, ayah = key
    return f"{slug}:{surah}:{ayah}"


def canonical_json(value: Any) -> str:
    """Encode JSON in the one representation used for content hashing."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def payload_hash(payload: dict[str, Any]) -> str:
    """SHA-256 of a parsed timing payload, never its SQLite text formatting."""
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def topology_hash(segments: list[list[int]]) -> str:
    """SHA-256 of the ordered word-position occurrence sequence."""
    positions = [segment[0] for segment in segments]
    return hashlib.sha256(canonical_json(positions).encode("utf-8")).hexdigest()


def parse_segments(value: str, source: str) -> list[list[int]]:
    """Parse and validate one ``timings.segments`` value from SQLite."""
    try:
        raw_segments = json.loads(value)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{source}: invalid segments JSON: {exc.msg}") from exc
    if not isinstance(raw_segments, list):
        raise ValueError(f"{source}: segments must be a JSON array")

    segments: list[list[int]] = []
    for index, segment in enumerate(raw_segments):
        if (
            not isinstance(segment, list)
            or len(segment) != 3
            or any(isinstance(part, bool) or not isinstance(part, int) for part in segment)
        ):
            raise ValueError(f"{source}: segment {index} must be [position, startMs, endMs]")
        segments.append(segment)
    return segments


def make_payload(segments: list[list[int]], audio_onset_ms: int) -> dict[str, Any]:
    """Build the complete timing payload included in hashes and delta output."""
    if isinstance(audio_onset_ms, bool) or not isinstance(audio_onset_ms, int):
        raise ValueError("audio_onset_ms must be an integer")
    return {"audioOnsetMs": audio_onset_ms, "segments": segments}


def connect_read_only(path: Path) -> sqlite3.Connection:
    """Open a database without creating or mutating any SQLite sidecar files."""
    if not path.is_file():
        raise FileNotFoundError(path)
    return sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)


def read_timing_rows(path: Path) -> dict[RowKey, dict[str, Any]]:
    """Read timings keyed by reciter slug, avoiding unstable numeric IDs."""
    connection = connect_read_only(path)
    try:
        rows = connection.execute(
            """
            SELECT r.slug, t.surah_id, t.ayah_number, t.segments, t.audio_onset_ms
            FROM timings AS t
            JOIN reciters AS r ON r.id = t.reciter_id
            ORDER BY r.slug, t.surah_id, t.ayah_number
            """
        )
        result: dict[RowKey, dict[str, Any]] = {}
        for slug, surah, ayah, raw_segments, onset in rows:
            key = (str(slug), int(surah), int(ayah))
            if key in result:
                raise ValueError(f"{path}: duplicate timing key {row_key_string(key)}")
            segments = parse_segments(str(raw_segments), f"{path}:{row_key_string(key)}")
            payload = make_payload(segments, int(onset))
            result[key] = {
                **payload,
                "payloadHash": payload_hash(payload),
                "topologyHash": topology_hash(segments),
            }
        return result
    finally:
        connection.close()


def read_git_timing_rows(ref: str, database_path: str = "data/quran.db") -> dict[RowKey, dict[str, Any]]:
    """Read a committed database at ``ref`` without changing the worktree."""
    source = f"{ref}:{database_path}"
    result = subprocess.run(
        ["git", "show", source], text=False, capture_output=True, check=False
    )
    if result.returncode:
        message = result.stderr.decode("utf-8", "replace").strip()
        raise ValueError(f"cannot read {source}: {message or 'git show failed'}")
    with tempfile.TemporaryDirectory(prefix="timing-delta-") as directory:
        path = Path(directory) / "quran.db"
        path.write_bytes(result.stdout)
        return read_timing_rows(path)


def payload_summary(payload: dict[str, Any]) -> dict[str, Any]:
    """Keep all changed values visible while retaining canonical content hashes."""
    return {
        "payloadHash": payload["payloadHash"],
        "topologyHash": payload["topologyHash"],
        "audioOnsetMs": payload["audioOnsetMs"],
        "segments": payload["segments"],
    }


def classify_change(old: dict[str, Any], new: dict[str, Any]) -> list[str]:
    """Classify all observable dimensions changed in one existing timing row."""
    kinds: list[str] = []
    if old["topologyHash"] != new["topologyHash"]:
        kinds.append("topology")
    elif old["segments"] != new["segments"]:
        kinds.append("timestamp")
    if old["audioOnsetMs"] != new["audioOnsetMs"]:
        kinds.append("onset")
    return kinds


def _normalise_ledger_entry(value: Any, source: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{source}: each verdict must be an object")
    verdict = value.get("verdict")
    if not isinstance(verdict, str):
        raise ValueError(f"{source}: verdict object needs a string 'verdict'")
    return dict(value)


def load_verdict_ledger(path: Path | None) -> dict[str, dict[str, Any]]:
    """Load a ledger keyed by ``reciter:surah:ayah``.

    The canonical form is ``{"verdicts": {"slug:1:1": {"verdict":
    "accept"}}}``.  A top-level mapping of keys or a list of objects carrying
    ``reciter``, ``surah``, and ``ayah`` is also accepted to keep review exports
    easy to consume.
    """
    if path is None:
        return {}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ValueError(f"cannot read verdict ledger {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path}: invalid verdict ledger JSON: {exc.msg}") from exc

    if isinstance(raw, dict):
        entries = raw.get("verdicts", raw)
        if not isinstance(entries, dict):
            raise ValueError(f"{path}: 'verdicts' must be an object")
        return {
            str(key): _normalise_ledger_entry(value, f"{path}:{key}")
            for key, value in entries.items()
        }
    if isinstance(raw, list):
        ledger: dict[str, dict[str, Any]] = {}
        for index, value in enumerate(raw):
            entry = _normalise_ledger_entry(value, f"{path}[{index}]")
            try:
                key = row_key_string(
                    (str(entry.pop("reciter")), int(entry.pop("surah")), int(entry.pop("ayah")))
                )
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError(
                    f"{path}[{index}]: list entries need reciter, surah, and ayah"
                ) from exc
            if key in ledger:
                raise ValueError(f"{path}: duplicate verdict for {key}")
            ledger[key] = entry
        return ledger
    raise ValueError(f"{path}: verdict ledger must be an object or array")


def load_corpus_bootstraps(path: Path) -> list[dict[str, Any]]:
    """Load locked full-corpus approvals for entirely new reciters."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ValueError(f"cannot read corpus bootstrap {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path}: invalid corpus bootstrap JSON: {exc.msg}") from exc
    entries = raw.get("corpora") if isinstance(raw, dict) else None
    if not isinstance(entries, list) or not all(isinstance(entry, dict) for entry in entries):
        raise ValueError(f"{path}: 'corpora' must be an array of objects")
    return [dict(entry) for entry in entries]


def corpus_payload_hash(changes: Iterable[dict[str, Any]]) -> str:
    """Bind every row key and payload hash in one imported reciter corpus."""
    rows = sorted(
        (change["key"], change["new"]["payloadHash"])
        for change in changes
    )
    return hashlib.sha256(canonical_json(rows).encode("utf-8")).hexdigest()


def build_delta(
    old_rows: dict[RowKey, dict[str, Any]],
    new_rows: dict[RowKey, dict[str, Any]],
    ledger: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Compare parsed rows and return only changes plus complete summary counts."""
    ledger = ledger or {}
    changes: list[dict[str, Any]] = []
    counts: Counter[str] = Counter()
    old_reciter_counts = Counter(key[0] for key in old_rows)
    new_reciter_counts = Counter(key[0] for key in new_rows)
    unchanged = 0

    for key in sorted(set(old_rows) | set(new_rows)):
        old = old_rows.get(key)
        new = new_rows.get(key)
        if old is not None and new is not None and old["payloadHash"] == new["payloadHash"]:
            unchanged += 1
            continue

        stable_key = row_key_string(key)
        change: dict[str, Any] = {
            "key": stable_key,
            "reciter": key[0],
            "surah": key[1],
            "ayah": key[2],
            "oldReciterRows": old_reciter_counts[key[0]],
            "newReciterRows": new_reciter_counts[key[0]],
            "old": payload_summary(old) if old is not None else None,
            "new": payload_summary(new) if new is not None else None,
        }
        if old is None:
            kinds = ["added"]
        elif new is None:
            kinds = ["withheld"]
        else:
            kinds = classify_change(old, new)
            if not kinds:
                raise AssertionError(f"changed hashes without a classification: {stable_key}")
        change["kinds"] = kinds
        change["verdict"] = ledger.get(stable_key)
        changes.append(change)
        counts.update(kinds)

    return {
        "summary": {
            "oldRows": len(old_rows),
            "newRows": len(new_rows),
            "unchangedRows": unchanged,
            "changedRows": len(changes),
            "byKind": dict(sorted(counts.items())),
        },
        "changes": changes,
    }


def _entry_problem(change: dict[str, Any]) -> str | None:
    """Return why a changed row lacks a current, auditable acceptance verdict."""
    entry = change.get("verdict")
    if not isinstance(entry, dict) or entry.get("verdict") != "accept":
        return "missing verdict: accept"
    if entry.get("kinds") != change["kinds"]:
        return "ledger kinds do not match the database delta"
    old_hash = change["old"]["payloadHash"] if change["old"] else None
    new_hash = change["new"]["payloadHash"] if change["new"] else None
    if entry.get("baselinePayloadHash") != old_hash:
        return "ledger baseline payload hash is stale"
    if entry.get("candidatePayloadHash") != new_hash:
        return "ledger candidate payload hash is stale"
    evidence = entry.get("evidence")
    if not isinstance(evidence, dict):
        return "missing evidence object"
    if not all(isinstance(evidence.get(field), str) and evidence[field] for field in ("kind", "summary", "artifact")):
        return "evidence needs non-empty kind, summary, and artifact"
    if not isinstance(evidence.get("audioSha256"), str) or len(evidence["audioSha256"]) != 64:
        return "evidence needs the exact audio SHA-256"

    # A corrected duration can constrain only the unreachable tail.  It must
    # never smuggle in a full-row re-clock: all positions, starts, and internal
    # boundaries have to remain byte-for-byte at the baseline.
    if evidence["kind"] == "duration_tail_clip":
        if change["kinds"] != ["timestamp"]:
            return "duration tail clip may only change timestamps"
        old = change["old"]
        new = change["new"]
        if old["audioOnsetMs"] != new["audioOnsetMs"]:
            return "duration tail clip may not change audio onset"
        old_segments, new_segments = old["segments"], new["segments"]
        if len(old_segments) != len(new_segments) or old_segments[:-1] != new_segments[:-1]:
            return "duration tail clip may only alter the final segment end"
        if (
            not old_segments
            or old_segments[-1][:2] != new_segments[-1][:2]
            or not new_segments[-1][2] < old_segments[-1][2]
        ):
            return "duration tail clip must shorten only the final end"
        if evidence.get("measuredDurationMs") != new_segments[-1][2]:
            return "duration tail clip must end at its measured duration"
        return None

    # Structural and ordinary boundary changes need the two independent
    # witnesses plus a waveform veto described in the methodology.  This gate
    # validates their presence and exact DB binding; producing the evidence is
    # deliberately an offline review step, never a CI guess.
    if any(kind in {"topology", "added", "withheld"} for kind in change["kinds"]):
        if evidence["kind"] != "dual_model_topology":
            return "topology changes need dual_model_topology evidence"
    else:
        if evidence["kind"] != "dual_model_boundary":
            return "boundary changes need dual_model_boundary evidence"
    models = evidence.get("models")
    if not isinstance(models, list) or len(models) < 2:
        return "evidence needs two independent model witnesses"
    if evidence.get("waveformVeto") is not False:
        return "evidence needs an explicit clear waveform veto"
    return None


def _accepted_corpus_keys(
    changes: list[dict[str, Any]], bootstraps: Iterable[dict[str, Any]]
) -> set[str]:
    """Approve exact, complete imports without weakening existing-row review."""
    by_reciter: dict[str, list[dict[str, Any]]] = {}
    for change in changes:
        by_reciter.setdefault(change["reciter"], []).append(change)
    accepted: set[str] = set()
    seen: set[str] = set()
    for entry in bootstraps:
        reciter = entry.get("reciter")
        if not isinstance(reciter, str) or not reciter or reciter in seen:
            continue
        seen.add(reciter)
        corpus = by_reciter.get(reciter, [])
        evidence = entry.get("evidence")
        sources = evidence.get("sources") if isinstance(evidence, dict) else None
        valid_sources = isinstance(sources, list) and bool(sources) and all(
            isinstance(source, dict)
            and isinstance(source.get("url"), str)
            and source["url"].startswith("https://")
            and isinstance(source.get("sha256"), str)
            and len(source["sha256"]) == 64
            for source in sources
        )
        valid = (
            entry.get("verdict") == "accept"
            and isinstance(entry.get("expectedRows"), int)
            and entry["expectedRows"] > 0
            and len(corpus) == entry["expectedRows"]
            and all(
                change["kinds"] == ["added"]
                and change["old"] is None
                and change["new"] is not None
                and change["oldReciterRows"] == 0
                and change["newReciterRows"] == entry["expectedRows"]
                for change in corpus
            )
            and isinstance(entry.get("corpusPayloadSha256"), str)
            and len(entry["corpusPayloadSha256"]) == 64
            and corpus_payload_hash(corpus) == entry["corpusPayloadSha256"]
            and isinstance(evidence, dict)
            and evidence.get("kind") == "pinned_corpus_bootstrap"
            and isinstance(evidence.get("summary"), str)
            and bool(evidence["summary"])
            and isinstance(evidence.get("artifact"), str)
            and bool(evidence["artifact"])
            and valid_sources
        )
        if valid:
            accepted.update(change["key"] for change in corpus)
    return accepted


def rejected_changes(
    changes: Iterable[dict[str, Any]],
    corpus_bootstraps: Iterable[dict[str, Any]] = (),
) -> list[tuple[dict[str, Any], str]]:
    """Return all changed rows that cannot safely leave the baseline."""
    materialized = list(changes)
    corpus_keys = _accepted_corpus_keys(materialized, corpus_bootstraps)
    return [
        (change, problem)
        for change in materialized
        if change["key"] not in corpus_keys and (problem := _entry_problem(change))
    ]


def accepted_changes(changes: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Compatibility helper returning only the changed rows the gate rejects."""
    return [change for change, _ in rejected_changes(changes)]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old_db", type=Path, help="baseline quran.db")
    parser.add_argument("new_db", type=Path, help="candidate quran.db")
    parser.add_argument("--ledger", type=Path, help="JSON verdict ledger")
    parser.add_argument(
        "--bootstrap",
        action="append",
        default=[],
        type=Path,
        help="locked full-corpus approval for a new reciter (repeatable)",
    )
    parser.add_argument(
        "--require-accepted",
        action="store_true",
        help="exit non-zero unless every changed row has verdict: accept",
    )
    parser.add_argument("--output", type=Path, help="write JSON report to this file")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        report = build_delta(
            read_timing_rows(args.old_db),
            read_timing_rows(args.new_db),
            load_verdict_ledger(args.ledger),
        )
    except (FileNotFoundError, sqlite3.Error, ValueError) as exc:
        print(f"timing delta failed: {exc}", file=sys.stderr)
        return 1

    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output is not None:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)

    try:
        bootstraps = [
            entry for path in args.bootstrap for entry in load_corpus_bootstraps(path)
        ]
    except ValueError as exc:
        print(f"timing delta failed: {exc}", file=sys.stderr)
        return 1
    rejected = rejected_changes(report["changes"], bootstraps)
    if args.require_accepted and rejected:
        details = "; ".join(f"{change['key']}: {problem}" for change, problem in rejected)
        print(
            f"timing delta rejected: {len(rejected)} changed row(s) lack a valid acceptance: {details}",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
