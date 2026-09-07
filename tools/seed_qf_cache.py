#!/usr/bin/env python3
"""Bake a QF Content Sync checkpoint into a debug-build seed.

Reads the three reader snapshots plus the five transliteration supplements
through the authenticated Worker proxy (which holds the only credentials),
validates their shape, and writes them plus the live sync token to
app/build/generated/qfSeed/ for packaging as a DEBUG-only asset.

Nothing here is committed and nothing reaches release builds: build/ is
gitignored and only the debug source set mounts the output dir. The seed is
QF content — regenerating or shipping it anywhere is redistribution, which
still needs the written QF permission tracked in docs/QF_CONTENT_SYNC.md.

The app imports the seed only when its cache holds no mushaf words, inside
the same transactional apply() (with the same validation) as a network
sync, then continues with ordinary incremental refresh. A corrupt or stale
seed fails closed back to a network bootstrap.

Usage:
    python3 tools/seed_qf_cache.py [--base-url URL] [--out DIR]

Defaults to the branch preview Worker; pass the production URL explicitly.
"""

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request

RESOURCES = "mushafs:1;word_by_word_translations:59;word_by_word_transliterations:60"
SNAPSHOTS = [
    ("mushafs", 1),
    ("word_by_word_translations", 59),
    ("word_by_word_transliterations", 60),
]
SUPPLEMENT_VERSES = ["1:1", "2:181", "8:6", "9:1", "36:52"]
DEFAULT_BASE_URL = "https://agent-transitional-qdc-cache-backend-beautiful-quran.sguergachi.workers.dev"


def get(base_url, path):
    request = urllib.request.Request(
        base_url + path,
        headers={"Accept": "application/json", "User-Agent": "Beautiful-Quran-seed/0.7"},
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        if response.status != 200:
            raise SystemExit(f"GET {path} returned HTTP {response.status}")
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--out", default="app/build/generated/qfSeed")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    import os

    seed_dir = os.path.join(args.out, "qf-seed")
    os.makedirs(seed_dir, exist_ok=True)

    sync = get(
        base_url,
        "/api/v4/resources/sync?bootstrap=true&resources=" + urllib.parse.quote(RESOURCES, safe=""),
    )["sync"]
    mutations = sync.get("mutations", [])
    by_resource = {
        (m.get("resource_group"), m.get("resource_id")): m for m in mutations
    }
    for group, resource_id in SNAPSHOTS:
        mutation = by_resource.get((group, resource_id))
        if mutation is None or mutation.get("type") not in ("RESOURCE_CREATE", "RESOURCE_INVALIDATE"):
            raise SystemExit(f"bootstrap has no snapshot for {group}:{resource_id}")
        if not mutation.get("snapshot_url", "").startswith("/api/v4/resources/snapshots/"):
            raise SystemExit(f"bootstrap snapshot URL not allowlisted: {mutation.get('snapshot_url')}")
    token = sync.get("next_sync_token")
    if not token:
        raise SystemExit("bootstrap response has no final checkpoint")

    files = {}
    for group, resource_id in SNAPSHOTS:
        url = by_resource[(group, resource_id)]["snapshot_url"]
        snapshot = get(base_url, url)
        if snapshot.get("schema_version") != 1:
            raise SystemExit(f"unsupported snapshot schema for {group}:{resource_id}")
        if snapshot.get("resource_group") != group or snapshot.get("resource_id") != resource_id:
            raise SystemExit(f"snapshot metadata mismatch for {group}:{resource_id}")
        records = snapshot.get("records")
        if not records:
            raise SystemExit(f"snapshot has no records for {group}:{resource_id}")
        name = f"snapshot-{group}-{resource_id}.json"
        with open(os.path.join(seed_dir, name), "w", encoding="utf-8") as handle:
            json.dump(snapshot, handle)
        files[f"{group}:{resource_id}"] = f"qf-seed/{name}"
        print(f"{group}:{resource_id}: {len(records)} records")

    mushaf_words = sum(
        1
        for row in json.load(open(os.path.join(args.out, files["mushafs:1"]), encoding="utf-8"))["records"]
        if row.get("record_type") == "mushaf_word" and row.get("char_type_name") == "word"
    )
    if mushaf_words <= 0:
        raise SystemExit("mushaf snapshot carries no word rows")
    print(f"mushaf word rows: {mushaf_words}")

    supplements = {}
    for verse_key in SUPPLEMENT_VERSES:
        verse = get(base_url, f"/api/v4/verses/by_key/{verse_key}?words=true&language=en").get("verse", {})
        if verse.get("verse_key") != verse_key:
            raise SystemExit(f"supplement mismatch for {verse_key}")
        words = [
            {"word_id": word["id"], "text": word["transliteration"]["text"].strip()}
            for word in verse.get("words", [])
            if word.get("char_type_name") == "word"
        ]
        if not words or any(not entry["text"] for entry in words):
            raise SystemExit(f"supplement omitted transliteration for {verse_key}")
        supplements[verse_key] = words
    with open(os.path.join(seed_dir, "supplements.json"), "w", encoding="utf-8") as handle:
        json.dump(supplements, handle)
    print(f"supplements: {sum(len(words) for words in supplements.values())} words")

    manifest = {
        "format": 1,
        "generated_at_ms": int(time.time() * 1000),
        "resources": RESOURCES,
        "sync_token": token,
        "files": files,
        "supplements": "qf-seed/supplements.json",
    }
    with open(os.path.join(seed_dir, "manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle)
    print(f"seed written to {seed_dir} (token {token[:12]}...)")


if __name__ == "__main__":
    main()
