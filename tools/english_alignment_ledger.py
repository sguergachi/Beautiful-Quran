#!/usr/bin/env python3
"""Export the complete printed corpus; compile only explicitly reviewed verses.

No network calls. Optional word-card input stays in the private export. This
tool measures review coverage, never semantic accuracy or model agreement.
"""
import argparse
import base64
import collections
from contextlib import closing
import hashlib
import json
import sqlite3
import subprocess
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / "app/src/main/java/com/beautifulquran"
FORMAT = "english-alignments-v1"
APPROVED = {"reviewed", "blind_gold"}
STATUSES = APPROVED | {"unresolved", "model_draft", "model_reviewed", "consensus_silver", "disputed"}
COMPONENTS = [
    "data/quran.db", "tools/ExportEnglishAlignment.kt", "tools/english_alignment_ledger.py",
    "tools/review_english_chapters.py", "tools/english_alignment_evidence.py", "tools/english_chapter_review.md",
    "docs/ENGLISH_ALIGNMENT_LEDGER.md",
    *[f"app/src/main/java/com/beautifulquran/{name}" for name in (
        "domain/EnglishTypography.kt", "domain/ArabicNormalize.kt", "domain/EnglishWordAlignment.kt", "domain/EnglishPhrase.kt",
        "domain/ReviewedEnglishAlignment.kt", "data/model/Models.kt",
    )],
]
BINDING_COMPONENTS = [f"app/src/main/java/com/beautifulquran/domain/{name}" for name in
    ("EnglishTypography.kt", "ReviewedEnglishAlignment.kt")]


def digest(value):
    return hashlib.sha256(value).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical_keys():
    with closing(sqlite3.connect(f"file:{REPO / 'data/quran.db'}?mode=ro", uri=True)) as db:
        return [f"{s}:{a}" for s, a in db.execute("select surah_id,ayah_number from ayahs order by surah_id,ayah_number")]


def content_digest():
    """Timing, morphology and other unrelated DB edits do not invalidate review."""
    with closing(sqlite3.connect(f"file:{REPO / 'data/quran.db'}?mode=ro", uri=True)) as db:
        return digest(encoded([
            db.execute("select surah_id,ayah_number,translation_en from ayahs order by surah_id,ayah_number").fetchall(),
            db.execute("select surah_id,ayah_number,position,arabic from words order by surah_id,ayah_number,position").fetchall(),
        ]))


def validate_manifest(manifest):
    require(manifest["format"] == FORMAT and manifest["mode"] == "published", "Unsupported ledger edition")
    keys = canonical_keys()
    require(len(keys) == 6236 and list(manifest["verses"]) == keys, "A subset cannot be the approved population")
    counts = manifest["counts"]
    require(counts == {"verses": len(keys),
        "arabic_slots": sum(v["arabic_slots"] for v in manifest["verses"].values()),
        "printed_tokens": sum(v["printed_tokens"] for v in manifest["verses"].values())}, "Manifest counts differ")
    require(counts["arabic_slots"] == 77429, "Canonical Arabic slots differ")
    require(set(manifest["components"]) == set(COMPONENTS), "Incomplete component fingerprint")
    require(manifest["content_sha256"] == content_digest(), "Printed corpus/Arabic surfaces changed; export a new manifest")
    require(set(manifest["binding_components"]) == set(BINDING_COMPONENTS), "Missing text binding components")
    for path, expected in manifest["binding_components"].items():
        require(digest((REPO / path).read_bytes()) == expected, f"Stale component: {path}; export a new corpus")


def export_corpus(output, draft_corpus=None):
    output.mkdir(parents=True, exist_ok=True)
    require(not (output / "ledger.jsonl").exists(), "Export into a new directory; preserve existing review decisions")
    cards = {}
    if draft_corpus:
        for line in draft_corpus.read_text().splitlines():
            key, _, glosses = line.split("\t")[:3]
            require(key not in cards, f"Duplicate draft verse {key}")
            cards[key] = glosses.split("|")
        require(set(cards) == set(canonical_keys()), "Draft cards must cover the full corpus")
    with closing(sqlite3.connect(f"file:{REPO / 'data/quran.db'}?mode=ro", uri=True)) as db:
        verses = db.execute("select surah_id,ayah_number,translation_en from ayahs order by surah_id,ayah_number").fetchall()
        words = collections.defaultdict(list)
        for s, a, p, surface, lemma in db.execute("""select w.surah_id,w.ayah_number,w.position,w.arabic,m.lemma
            from words w left join word_morphology m using(surah_id,ayah_number,position)
            order by w.surah_id,w.ayah_number,w.position"""):
            require(p == len(words[f"{s}:{a}"]) + 1, "Non-contiguous reader slots")
            words[f"{s}:{a}"].append((surface, lemma or ""))
    with tempfile.TemporaryDirectory(prefix="english-ledger-") as temporary:
        directory = Path(temporary)
        b64 = lambda text: base64.b64encode(text.encode()).decode()
        lines = []
        for s, a, raw in verses:
            key = f"{s}:{a}"
            require(not cards or len(cards[key]) == len(words[key]), f"Draft segmentation differs: {key}")
            lines.append("\t".join([key, b64(raw), b64("\0".join(w[0] for w in words[key])),
                b64("\0".join(cards.get(key, [""] * len(words[key])))), b64("\0".join(w[1] for w in words[key]))]))
        source = directory / "input.tsv"
        source.write_text("\n".join(lines) + "\n")
        jar = directory / "export.jar"
        kotlin = [SOURCE / f for f in ("domain/EnglishWordAlignment.kt", "domain/EnglishPhrase.kt", "domain/EnglishTypography.kt",
            "domain/ArabicNormalize.kt", "domain/ReviewedEnglishAlignment.kt", "data/model/Models.kt")]
        subprocess.run(["kotlinc", *map(str, kotlin), str(REPO / "tools/ExportEnglishAlignment.kt"),
            "-include-runtime", "-d", str(jar)], check=True)
        result = subprocess.run(["java", "-jar", str(jar), str(source)], capture_output=True, text=True, check=True)
    bindings = {}; records = []
    for line in result.stdout.splitlines():
        key, text, fingerprint, tokens = line.split("\t")
        token_rows = []
        for item in tokens.split(";"):
            start, end, word, head, ink = item.split(",")
            token_rows.append({"start": int(start), "end": int(end), "text": base64.b64decode(word).decode(),
                "head": int(head) if head else None, "ink_anchor": int(ink) if ink else None,
                "contributors": None, "meaning": None, "unit": None, "review": None,
                "status": "model_draft" if head else "unresolved"})
        binding = {"fingerprint": fingerprint, "arabic_slots": len(words[key]), "printed_tokens": len(token_rows),
            "tokens_sha256": digest(encoded([[t["start"], t["end"], t["text"]] for t in token_rows]))}
        bindings[key] = binding
        records.append({"verse": key, "fingerprint": fingerprint, "text": base64.b64decode(text).decode(),
            "arabic": [w[0] for w in words[key]], "qac_trust": "unreviewed",
            "tokens": token_rows})
    manifest = {"format": FORMAT, "mode": "published", "edition": "bundled Sahih International, reader typesetting",
        "offset_unit": "UTF-16", "counts": {"verses": len(bindings), "arabic_slots": sum(len(w) for w in words.values()),
        "printed_tokens": sum(v["printed_tokens"] for v in bindings.values())}, "verses": bindings,
        "components": {path: digest((REPO / path).read_bytes()) for path in COMPONENTS},
        "binding_components": {path: digest((REPO / path).read_bytes()) for path in BINDING_COMPONENTS},
        "content_sha256": content_digest(),
        "draft_source_sha256": digest(draft_corpus.read_bytes()) if draft_corpus else None}
    validate_manifest(manifest)
    header = json.dumps({k: v for k, v in manifest.items() if k != "verses"}, ensure_ascii=False, indent=2)[:-2]
    entries = ",\n".join(f'    "{key}": {json.dumps(value, separators=(",", ":"))}' for key, value in bindings.items())
    (output / "manifest.json").write_text(header + ',\n  "verses": {\n' + entries + '\n  }\n}\n')
    manifest_hash = digest(encoded(manifest))
    (output / "ledger.jsonl").write_text("".join(json.dumps(dict(r, source_manifest_sha256=manifest_hash), ensure_ascii=False) + "\n" for r in records))
    return manifest


def compile_verse(row, binding, ai_reviewed=False):
    require(isinstance(row, dict) and {"verse", "text", "arabic", "tokens", "fingerprint"} <= row.keys(), "Incomplete verse record")
    text, arabic, tokens = row["text"], row["arabic"], row["tokens"]
    require(isinstance(tokens, list) and all(isinstance(t, dict) and
        {"start", "end", "text", "head", "ink_anchor", "contributors", "meaning", "unit", "review", "status"} <= t.keys() for t in tokens), "Incomplete token record")
    require(len(arabic) == binding["arabic_slots"] and all(isinstance(w, str) and w and "\0" not in w for w in arabic), "Arabic surfaces differ")
    require("\0" not in text and digest("\0".join([FORMAT, text, *arabic]).encode()) == binding["fingerprint"] == row["fingerprint"], "Stale printed text or surfaces")
    require(len(tokens) == binding["printed_tokens"] and digest(encoded([[t["start"], t["end"], t["text"]] for t in tokens])) == binding["tokens_sha256"], "Token offsets/text differ")
    require(all(t["status"] in STATUSES for t in tokens), "Unknown review status")
    eligible = APPROVED | ({"model_reviewed"} if ai_reviewed else set())
    units = collections.defaultdict(list)
    for index, token in enumerate(tokens):
        for field in ("head", "ink_anchor"):
            value = token[field]
            require(value is None or (type(value) is int and 1 <= value <= len(arabic)), f"Invalid {field}")
        if token["status"] not in eligible:
            continue
        require(token["head"] is not None and token["ink_anchor"] is not None, "Approved token has no interaction/wash anchor")
        contributors = token["contributors"]
        require(isinstance(contributors, list) and len(set(contributors)) == len(contributors) and
            all(type(p) is int and 1 <= p <= len(arabic) for p in contributors), "Invalid contributors")
        require(not contributors or token["ink_anchor"] in contributors, "Ink anchor is outside the meaning contributors")
        require(not contributors or token["head"] in contributors, "Interaction head is outside the meaning contributors")
        require(isinstance(token["meaning"], str) and token["meaning"].strip() and isinstance(token["unit"], str) and token["unit"].strip(), "Missing meaning/unit decision")
        review = token["review"]
        model = token["status"] == "model_reviewed"
        if model:
            require(row.get("qac_trust") == "surface_verified", "AI review needs the actual Arabic surface crosswalk")
        method = "model_review" if model else "blind_adjudication" if token["status"] == "blind_gold" else "human_review"
        require(isinstance(review, dict) and review.get("method") == method and isinstance(review.get("reviewer"), str) and review["reviewer"].strip() and
            isinstance(review.get("evidence"), list) and review["evidence"] and
            all(isinstance(e, str) and e.strip() for e in review["evidence"]), "Missing review provenance")
        units[token["unit"]].append(index)
    if not all(t["status"] in eligible for t in tokens):
        return None
    for indices in units.values():
        unit = [tokens[i] for i in indices]
        require(indices[-1] - indices[0] + 1 == len(indices), "Unit is not contiguous; use explicit sub-units")
        require(all(sorted(t["contributors"]) == sorted(unit[0]["contributors"]) for t in unit), "Unit mixes different contributors")
        if len(unit[0]["contributors"]) == 1:
            require(len({t["ink_anchor"] for t in unit}) == 1, "A one-word expansion must share ink")
    ink = ",".join(str(t["ink_anchor"]) for t in tokens)
    heads = ",".join(str(t["head"]) for t in tokens)
    linked = "".join("1" if t["contributors"] else "0" for t in tokens)
    return "\t".join([row["verse"], row["fingerprint"], ink, "=" if heads == ink else heads, linked])


def compile_ledger(ledger, manifest, allow_partial=False, ai_reviewed=False):
    validate_manifest(manifest)
    manifest_hash = digest(encoded(manifest))
    rows = {}; compiled = []; statuses = collections.Counter()
    for line in ledger.read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        row = json.loads(line); key = row["verse"]
        require(key in manifest["verses"] and key not in rows, f"Unknown/duplicate verse: {key}")
        # Decisions bind to the verse below, not unrelated changes elsewhere.
        rows[key] = row
    for key, binding in manifest["verses"].items():
        row = rows.get(key)
        if row is None:
            statuses["unresolved"] += binding["printed_tokens"]
            continue
        result = compile_verse(row, binding, ai_reviewed)
        statuses.update(t["status"] for t in row["tokens"])
        if result:
            compiled.append(result)
    approved_keys = {line.split("\t")[0] for line in compiled}
    coverage = {**manifest["counts"], "manifest_sha256": manifest_hash, "approved_verses": len(compiled),
        "approved_tokens": sum(manifest["verses"][k]["printed_tokens"] for k in approved_keys),
        "fallback_verses": len(manifest["verses"]) - len(compiled), "statuses": dict(statuses),
        "approval_policy": "ai_reviewed" if ai_reviewed else "human_review",
        "ai_reviewed_verses": sum(any(t["status"] == "model_reviewed" for t in rows[k]["tokens"]) for k in approved_keys),
        "semantic_accuracy_established": False}
    require(allow_partial or coverage["fallback_verses"] == 0, f"{coverage['fallback_verses']} fallback verses; use --allow-partial for a development pilot")
    artifact = "# " + FORMAT + "\n" + "".join(line + "\n" for line in compiled)
    coverage["artifact_sha256"] = digest(artifact.encode())
    return artifact, coverage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export")
    export.add_argument("--output-dir", type=Path, required=True)
    export.add_argument("--draft-corpus", type=Path)
    compile = commands.add_parser("compile")
    compile.add_argument("--ledger", type=Path, required=True)
    compile.add_argument("--manifest", type=Path, required=True)
    compile.add_argument("--output", type=Path, required=True)
    compile.add_argument("--coverage", type=Path, required=True)
    compile.add_argument("--allow-partial", action="store_true")
    compile.add_argument("--ai-reviewed", action="store_true", help="Developer artifact only: also accept fully model-reviewed verses")
    args = parser.parse_args()
    if args.command == "export":
        print(json.dumps(export_corpus(args.output_dir, args.draft_corpus)["counts"]))
    else:
        require(not args.ai_reviewed or args.output.name != "english-alignments.tsv", "AI policy needs the separate developer artifact")
        artifact, coverage = compile_ledger(args.ledger, json.loads(args.manifest.read_text()), args.allow_partial, args.ai_reviewed)
        args.output.write_text(artifact)
        args.coverage.write_text(json.dumps(coverage, indent=2) + "\n")
        print(json.dumps(coverage))


if __name__ == "__main__":
    main()
