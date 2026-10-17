"""Reproduce candidate rows through the pinned legacy timing pipeline.

This is diagnostic provenance, never a substitute for authenticated snapshots.
Generated/hand held-word merges are omitted so the detector sees its own input.
"""
import collections
import contextlib
import io
import json
from pathlib import Path
import tempfile

from word_split_evidence import merge_pair


def trace_candidates(db, candidates, cache):
    import build_db as b

    words = {}
    for s, a, pos, text in db.execute("SELECT surah_id,ayah_number,position,arabic FROM words"):
        words.setdefault((s, a), {})[pos] = text
    counts = {key: len(value) for key, value in words.items()}
    slugs = dict(db.execute("SELECT id,slug FROM reciters"))
    keys = {tuple(c["key"]) for c in candidates}
    durations, onsets = b.load_audio_durations(), b.load_audio_onsets()
    references, sources, traces = {}, {}, {}
    b.verify_source(cache / "quran-align-data.zip", b.ALIGN_ZIP_SHA256, "quran-align")
    for rid in sorted({k[0] for k in keys} & b.QDC_REPEAT_RECITERS.keys()):
        source_id = b.QDC_REPEAT_RECITERS[rid]
        path = cache / f"qdc_{source_id}.json"
        b.verify_source(path, b.QDC_SOURCE_SHA256[source_id], f"QDC {source_id}")
        sources[rid] = json.loads(path.read_text())
        references.update(b.alignment_reference(cache / "quran-align-data.zip", rid, slugs[rid], counts, words))
    offsets, clocked, rows = {}, set(), []
    for key in sorted(keys):
        rid, s, a = key
        traces[":".join(map(str, key))] = entry = {"sourceProfile": "source replay unavailable",
                                                   "corrections": [], "repairs": [], "reviewedHolds": []}
        if rid not in sources:
            continue
        raw = sources[rid][f"{s}:{a}"]
        stats = collections.defaultdict(int)
        cleaned = b.adjust_qdc_segments(raw, counts[s, a], stats, words=words[s, a])
        rebased, offset = b.rebase_qdc_clock(cleaned, references.get(key), durations.get(key))
        entry.update(raw=raw, cleaned=cleaned, clockRebased=rebased, clockOffsetMs=offset,
                     sourceProfile="pinned legacy replay; authenticated snapshots unavailable",
                     sourceSha256=b.QDC_SOURCE_SHA256[b.QDC_REPEAT_RECITERS[rid]],
                     quranAlign=references.get(key), cleanerStats=dict(stats))
        rows.append((*key, json.dumps(rebased)))
        if offset is not None:
            offsets[key] = offset
            clocked.add(key)
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        for kind, source_dir in [("corrections", b.CORRECTIONS_DIR), ("repairs", b.REPAIRS_DIR)]:
            for path in source_dir.glob("*.json"):
                payload = json.loads(path.read_text())
                if not isinstance(payload, dict):
                    continue
                for edit in payload.get("edits", []):
                    key = edit["reciterId"], edit["surahId"], edit["ayah"]
                    if key in keys:
                        traces[":".join(map(str, key))][kind].append({"file": path.name, "edit": edit})
                if kind == "corrections":
                    payload["edits"] = [e for e in payload.get("edits", [])
                                        if (e["reciterId"], e["surahId"], e["ayah"]) in keys
                                        and e["op"] != "merge_verified_word_split"]
                    (directory / path.name).write_text(json.dumps(payload))
        rows = b.apply_timing_corrections(rows, corrections_dir=directory)
        for rid, s, a, raw in rows:
            traces[f"{rid}:{s}:{a}"]["afterCorrections"] = json.loads(raw)
        rows = b.apply_timing_repairs(rows, counts, offsets, durations, only_keys=keys,
                                     word_text=words, references=references, file_clock_keys=clocked)
        for rid, s, a, raw in rows:
            traces[f"{rid}:{s}:{a}"]["afterRepairs"] = json.loads(raw)
        rows, _ = b.refit_displaced_rows(rows, durations, onsets, clocked)
        for rid, s, a, raw in rows:
            traces[f"{rid}:{s}:{a}"]["beforeGeneratedMerges"] = json.loads(raw)
        finalized, _ = b.finalize_timing_rows(
            rows, counts, {k: v for k, v in references.items() if k in keys},
            durations, onsets, clocked)
        for rid, s, a, raw in finalized:
            traces[f"{rid}:{s}:{a}"]["afterFinalizer"] = json.loads(raw)
    if any(k[0] == b.YASSER_RECITER_ID for k in keys):
        path = cache / "yasser-al-dosari-qua-v3.zip"
        b.verify_source(path, b.YASSER_QUA_SHA256, "Yasser QUA")
        qua = b.load_qua_timings(path, counts, words, durations)
        for rid, s, a in keys:
            if rid == b.YASSER_RECITER_ID:
                traces[f"{rid}:{s}:{a}"].update(sourceProfile="independent QUA, exact EveryAyah clock",
                    sourceSha256=b.YASSER_QUA_SHA256, beforeGeneratedMerges=qua[s, a])
    for entry in json.loads(b.AUDIT_HOLDS_FILE.read_text())["rows"]:
        key = f"{entry['reciterId']}:{entry['surahId']}:{entry['ayah']}"
        if key in traces:
            traces[key]["reviewedHolds"].append(entry)
    prior = json.loads((b.ROOT / "tools/timing_verdicts/qf-source-transition.json").read_text())["verdicts"]
    for rid, s, a in keys:
        entry = prior.get(f"{slugs[rid]}:{s}:{a}")
        if entry:
            traces[f"{rid}:{s}:{a}"]["authenticatedPriorReview"] = {
                k: v for k, v in entry.items() if k != "evidence"}
    # The finalizer may complete unrelated positions or normalize the opening.
    # Prove the entire generated output, rather than merely finding the pair.
    for item in candidates:
        key = tuple(item["key"])
        entry = traces[":".join(map(str, key))]
        source = entry.get("beforeGeneratedMerges", [])
        matches = [i for i in range(len(source) - 1) if source[i:i + 2] == item["sourcePair"]]
        if (key[0] not in sources or len(matches) != 1 or
                item["sourcePair"][0][2] != item["sourcePair"][1][1]):
            continue
        merged = merge_pair(source, matches[0])
        with contextlib.redirect_stdout(io.StringIO()):
            output, output_onsets = b.finalize_timing_rows(
                [(*key, json.dumps(merged))], counts,
                {key: references[key]} if key in references else {}, durations,
                onsets, clocked)
        entry.setdefault("candidateOutputs", {})[item["id"]] = {
            "segments": json.loads(output[0][3]) if len(output) == 1 else None,
            "audioOnsetMs": output_onsets.get(key, 0),
        }
    return traces
