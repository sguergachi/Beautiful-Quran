#!/usr/bin/env python3
"""Maintain private authenticated timing sources and publish reviewed runtime views.

QF credentials stay in Cloudflare. The operator key authorizes only the six
timing resources; neither snapshots nor normalized databases belong in Git or
release artifacts. New source hashes or unreviewed deltas fail closed in build_db.
"""

import argparse
import contextlib
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import urllib.request

import build_db as build
from qf_timing_parity import snapshot_rows, source_hash

ROOT = Path(__file__).resolve().parent.parent


def compact(value):
    return json.dumps(value, separators=(",", ":")).encode()


def normalizer_hash():
    """Fingerprint the code and every reviewed normalization input, not timestamps."""
    paths = [ROOT / "tools" / name for name in (
        "build_db.py", "qf_timing_parity.py", "timing_delta.py",
    )]
    for directory in ("timing_corrections", "timing_repairs", "timing_holds", "timing_verdicts", "audio_onsets"):
        paths.extend((ROOT / "tools" / directory).glob("*.json"))
    digest = hashlib.sha256()
    for path in sorted(set(paths)):
        digest.update(str(path.relative_to(ROOT)).encode() + b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def runtime_pack(db, app_id, snapshot, state, fingerprint):
    """Export the canonical database view without dropping repeated occurrences."""
    qf_id = build.QDC_REPEAT_RECITERS[app_id]
    source = source_hash(snapshot_rows(snapshot, qf_id))
    if source != build.QF_SOURCE_SHA256[qf_id]:
        raise ValueError(f"QF {qf_id} source changed; review before publishing")
    if state.get("deleted") or not state.get("sourceKey"):
        raise ValueError("Cannot publish a withdrawn or missing timing source")
    slug = db.execute("SELECT slug FROM reciters WHERE id=?", (app_id,)).fetchone()[0]
    canonical = {(s, a): count for s, a, count in db.execute(
        "SELECT surah_id, ayah_number, COUNT(*) FROM words GROUP BY surah_id, ayah_number"
    )}
    rows = []
    for s, a, segments, onset in db.execute(
        "SELECT surah_id, ayah_number, segments, audio_onset_ms FROM timings WHERE reciter_id=? ORDER BY surah_id, ayah_number",
        (app_id,),
    ):
        segments = json.loads(segments)
        count = canonical.get((s, a))
        positions = set()
        end = -1
        for segment in segments:
            if (len(segment) != 3 or any(type(n) is not int for n in segment) or
                    not 1 <= segment[0] <= count or segment[1] < end or segment[2] <= segment[1]):
                raise ValueError(f"Invalid canonical timing clock {app_id}/{s}:{a}")
            positions.add(segment[0])
            end = segment[2]
        if (len(positions) != count or type(onset) is not int or onset < 0 or
                not segments or onset > segments[0][1]):
            raise ValueError(f"Incomplete canonical timing row {app_id}/{s}:{a}")
        rows.append([s, a, segments, onset])
    keys = {(row[0], row[1]) for row in rows}
    payload = {"rows": rows, "withheldVerseKeys": [f"{s}:{a}" for s, a in sorted(canonical.keys() - keys)]}
    revision = hashlib.sha256(compact(payload)).hexdigest()
    metadata = {
        "schemaVersion": 1, "reciterId": app_id, "qfReciterId": qf_id,
        "audioSlug": slug, "clock": "everyayah-ms", "sourceSha256": source,
        "sourceSyncSequence": snapshot["sync_sequence"], "sourceSyncedAtMs": state["syncedAtMs"],
        "normalizerSha256": fingerprint, "revision": revision,
    }
    return metadata, payload


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, *args):
        raise ValueError("Private timing requests must not redirect")


def private_request(base_url, key, path, refresh=False, body=None):
    request = urllib.request.Request(base_url.rstrip("/") + path,
        headers={"x-timing-maintenance-key": key, "User-Agent": "Beautiful-Quran/0.10 (timing maintenance)",
                 "Content-Type": "application/json"},
        data=compact(body) if body is not None else None,
        method="POST" if refresh or body is not None else "GET")
    with urllib.request.build_opener(NoRedirect).open(request, timeout=120) as response:
        body = response.read(25 * 1024 * 1024 + 1)
        if len(body) > 25 * 1024 * 1024:
            raise ValueError("Timing source exceeds its storage limit")
        return json.loads(body)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker-url", default="https://beautiful-quran.sguergachi.workers.dev")
    parser.add_argument("--key-file", type=Path, required=True, help="0600 private maintenance key")
    parser.add_argument("--work-dir", type=Path, required=True, help="private directory outside the checkout")
    parser.add_argument("--timing-baseline", type=Path, required=True, help="reviewed private baseline pinned by the current source profile")
    parser.add_argument("--source-cache", type=Path, required=True, help="existing pinned pipeline download cache")
    parser.add_argument("--wrangler", type=Path, help="installed Wrangler JS entrypoint; required for --publish")
    parser.add_argument("--publish", action="store_true", help="upload reviewed immutable views and then accepted manifests")
    parser.add_argument("--regrant", action="store_true", help="explicitly resume after restored QF access and completed purge")
    args = parser.parse_args(argv)
    if not args.worker_url.startswith("https://"):
        parser.error("The private timing API requires HTTPS")
    work = args.work_dir.resolve()
    if work.is_relative_to(ROOT) or args.timing_baseline.resolve().is_relative_to(ROOT):
        parser.error("QF snapshots, normalized views, and their baseline must stay outside the checkout")
    if args.key_file.stat().st_mode & 0o077:
        parser.error("The maintenance key file must have mode 0600")
    if args.publish and not args.wrangler:
        parser.error("--publish requires the installed --wrangler entrypoint")
    work.mkdir(mode=0o700, parents=True, exist_ok=True)
    if work.stat().st_mode & 0o077:
        parser.error("The private work directory must have mode 0700")
    key = args.key_file.read_text().strip()
    if not key:
        parser.error("The maintenance key is empty")
    if args.regrant:
        private_request(args.worker_url, key, "/internal/timings/regrant", True)
    snapshots, states = {}, {}
    for app_id, qf_id in build.QDC_REPEAT_RECITERS.items():
        private_request(args.worker_url, key, f"/internal/timings/{app_id}/refresh", True)
        state = private_request(args.worker_url, key, f"/internal/timings/{app_id}")["state"]
        snapshot = private_request(args.worker_url, key, f"/internal/timings/{app_id}/source")
        snapshots[app_id], states[app_id] = snapshot, state
        (work / f"snapshot-{qf_id}.json").write_bytes(compact(snapshot))
    output = work / "reviewed-runtime.db"
    build.main(["--qf-snapshots", str(work), "--output", str(output),
                "--timing-baseline", str(args.timing_baseline), "--source-cache", str(args.source_cache)])
    fingerprint = normalizer_hash()
    values, manifests, summary = [], [], []
    with contextlib.closing(sqlite3.connect(output)) as db:
        for app_id in build.QDC_REPEAT_RECITERS:
            metadata, payload = runtime_pack(db, app_id, snapshots[app_id], states[app_id], fingerprint)
            payload_key = f"timings/{app_id}/view/{metadata['revision']}"
            values.append({"key": payload_key, "value": compact(payload).decode()})
            manifests.append({"key": f"timings/{app_id}/accepted", "value": compact({
                "sourceKey": states[app_id]["sourceKey"], "payloadKey": payload_key, "metadata": metadata,
            }).decode()})
            summary.append({**metadata, "rowCount": len(payload["rows"]), "withheldVerseKeys": payload["withheldVerseKeys"]})
    # Values are written before manifests. Each manifest names one immutable
    # view and the exact current source; an overtaken publication stays held.
    for name, entries in (("views", values), ("manifests", manifests)):
        path = work / f"{name}.json"
        path.write_bytes(compact(entries))
        if args.publish and name == "views":
            subprocess.run(["node", str(args.wrangler), "kv", "bulk", "put", str(path),
                            "--binding", "QF_TIMING_STORE", "--remote", "--config", str(ROOT / "wrangler.jsonc")], check=True)
    if args.publish:
        for entry in manifests:
            app_id = int(entry["key"].split("/")[1])
            private_request(args.worker_url, key, f"/internal/timings/{app_id}/accept", body=json.loads(entry["value"]))
    (work / "runtime-review-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"{'Published' if args.publish else 'Prepared'} six reviewed timing views ({sum(x['rowCount'] for x in summary)} rows).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
