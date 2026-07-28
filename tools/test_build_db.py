"""Unit tests for build_db timing cleanup — driven by timing_patch_cases/.

Runnable in-repo with no network or CTC cache:

    python3 tools/test_build_db.py

Every Timings Lab / GitHub timing patch that is fixed *systematically* must
land a case under ``tools/timing_patch_cases/``. The case encodes the broken
input and the expected post-pipeline shape — that is the patch verification.
See ``tools/timing_patch_cases/README.md``.
"""
from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
from build_db import (  # noqa: E402
    AUDIO_ONSETS_DIR,
    _is_complete_timing_sequence,
    apply_audio_onsets,
    apply_boundary_repair,
    boundary_conflicts,
    clean_qdc_artifacts,
    erases_span_repeat,
    load_timing_v2,
    offset_for_audio_onset,
    rebase_timing_repair,
    suspicious_pacing,
)
import detect_audio_onsets as onset_detector  # noqa: E402

CASES_DIR = TOOLS / "timing_patch_cases"
PIPELINES = frozenset(
    {
        "boundary_repair",
        "clean_qdc_artifacts",
        "erases_span_repeat",
        "leading_silence_offset",
        "rebase_timing_repair",
    }
)


def segs_from_positions(positions, dur=800):
    """Contiguous segments long enough that the split-fragment merge (which
    keys on sub-word durations) never fires — position-topology tests only."""
    return [[p, i * dur, i * dur + dur] for i, p in enumerate(positions)]


def order(segs_):
    return [p for p, _, _ in segs_]


def check_v2_lab_gold_gate():
    """V2 must match Timings Lab ground truth at ≥99% (structure + onsets)."""
    sys.path.insert(0, str(TOOLS / "sync_lab"))
    from eval_v2_vs_lab_gold import evaluate, load_lab_gold, load_v2  # noqa: PLC0415

    db = ROOT / "data" / "quran.db"
    hist = TOOLS / "sync_lab" / "historical_manual_patches.json"
    if not db.exists() or not hist.exists():
        return False, "missing db or historical_manual_patches.json"
    report = evaluate(load_lab_gold(hist, db), load_v2(db))
    if report.get("pass"):
        return True, report.get("claim", "ok")
    return False, report.get("claim") or json.dumps(report, indent=2)


# Hard golden structures — wrong topology is a product failure (docs/V2_STRUCTURE.md).
V2_STRUCTURE_CASES = {
    (6, 10): [1, 2, 3, 4, 5, 6, 7, 8, 6, 7, 8, 9, 10, 11, 12, 13],
}


def check_v2_structure_gate():
    """Shipped V2 (and committed sources) must not flatten known phrase re-says."""
    db = ROOT / "data" / "quran.db"
    if not db.exists():
        return False, "missing data/quran.db"
    failures = []
    with sqlite3.connect(db) as conn:
        for (surah, ayah), want in V2_STRUCTURE_CASES.items():
            row = conn.execute(
                "SELECT segments FROM timings_v2 "
                "WHERE reciter_id=1 AND surah_id=? AND ayah_number=?",
                (surah, ayah),
            ).fetchone()
            if row is None:
                failures.append(f"{surah}:{ayah} missing from timings_v2")
                continue
            got = [int(s["position"]) for s in json.loads(row[0])]
            if got != want:
                failures.append(f"{surah}:{ayah} got={got} want={want}")
    if failures:
        return False, "; ".join(failures)
    return True, "structure cases exact"


def check_timing_v2_loader():
    """The committed V2 source gate must reject non-acoustic curve shapes."""
    if not _is_complete_timing_sequence([1, 2, 1, 2], 2):
        return False
    if _is_complete_timing_sequence([1, 3, 2, 3], 3):
        return False
    payload = {
        "schema": 2,
        "generator": "sync_lab/generate_timing_v2.py@3",
        "source": "jonatasgrosman/wav2vec2-large-xlsr-53-arabic",
        "sourceRevision": "af46c2d8531b8dcbb5e23b952f739b372c2e5d2d",
        "reciterId": 1,
        "minimumGateScore": -1.0,
        "rows": [{
            "surah": 1,
            "ayah": 1,
            "gateScore": 0.0,
            "audioSha256": "b" * 64,
            "segments": [{
                "position": 1,
                "startMs": 100,
                "endMs": 500,
                "keyframes": [{"offsetMs": 100, "progress": 1.0}],
            }],
        }],
    }

    with tempfile.TemporaryDirectory() as raw_dir:
        source_dir = Path(raw_dir)
        path = source_dir / "case.json"

        def loads(candidate):
            path.write_text(json.dumps(candidate), encoding="utf-8")
            return load_timing_v2({(1, 1): 1}, source_dir)

        if len(loads(payload)) != 1:
            return False
        for mutate in (
            lambda p: p["rows"][0].update(gateScore=-2.0),
            lambda p: p["rows"][0]["segments"][0]["keyframes"][0].update(offsetMs=0),
            lambda p: p["rows"][0]["segments"][0]["keyframes"][0].update(progress=0.5),
            lambda p: p["rows"][0].update(audioSha256="z" * 64),
            lambda p: p["rows"][0]["segments"][0]["keyframes"][0].update(
                progress=float("nan")
            ),
        ):
            candidate = json.loads(json.dumps(payload))
            mutate(candidate)
            try:
                loads(candidate)
                return False
            except ValueError:
                pass

        qua = json.loads(json.dumps(payload))
        qua.update({
            "generator": "sync_lab/generate_qua_timing_v2.py@1",
            "source": "Wider-Community/quranic-universal-audio@v2.3.0",
            "sourceRevision": "9b83ea5824d1f4de3921562f9d7282e279f05860",
            "sourceAssetSha256":
                "8a05209a022ad4410ce39f74f374ec09fb5bae6a019b1e2b054fda8342bf0df7",
            "minimumGateScore": 0.7,
            "minimumPeakMargin": 0.25,
        })
        qua["rows"][0].update({
            "gateScore": 0.8,
            "clockCorrelation": 0.8,
            "clockPeakMargin": 0.5,
            "sourceZeroMs": 120.0,
            "sourceAudioSha256": "c" * 64,
        })
        if len(loads(qua)) != 1:
            return False
        qua["rows"][0]["clockPeakMargin"] = 0.24
        try:
            loads(qua)
            return False
        except ValueError:
            pass
    return True


def load_cases():
    files = sorted(CASES_DIR.glob("*.json"))
    if not files:
        raise SystemExit(f"no cases in {CASES_DIR}")
    cases = []
    for path in files:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            raise SystemExit(f"cannot parse {path.name}: {e}") from e
        data["_path"] = path.name
        cases.append(data)
    return cases


def resolve_input(case):
    if case.get("input_segments"):
        return [list(s) for s in case["input_segments"]]
    if case.get("input_positions") is not None:
        return segs_from_positions(case["input_positions"])
    raise SystemExit(f"{case.get('_path')}: need input_segments or input_positions")


def resolve_repair(case):
    if case.get("repair_segments"):
        return [list(s) for s in case["repair_segments"]]
    if case.get("repair_positions") is not None:
        return segs_from_positions(case["repair_positions"])
    raise SystemExit(
        f"{case.get('_path')}: erases_span_repeat needs repair_segments or repair_positions"
    )


def check_expected(case, got_segs):
    """Return (ok, detail). Prefer full segments when expected_segments is set."""
    if case.get("expected_segments") is not None:
        want = [list(s) for s in case["expected_segments"]]
        got = [list(s) for s in got_segs]
        if got == want:
            return True, None
        return False, f"want segments {want}\n        got  segments {got}"
    if case.get("expected_positions") is not None:
        want = case["expected_positions"]
        got = order(got_segs)
        if got == want:
            return True, None
        return False, f"want positions {want}\n        got  positions {got}"
    raise SystemExit(
        f"{case.get('_path')}: need expected_segments or expected_positions"
    )


def run_pipeline(case, segs):
    pipeline = case.get("pipeline") or "clean_qdc_artifacts"
    if pipeline not in PIPELINES:
        raise SystemExit(
            f"{case.get('_path')}: unknown pipeline {pipeline!r}; "
            f"supported: {sorted(PIPELINES)}"
        )
    if pipeline == "clean_qdc_artifacts":
        stats = {
            "merged_splits": 0,
            "dropped_strays": 0,
            "noncontiguous_orphans": 0,
            "gap_phantoms": 0,
            "false_phrase_loops": 0,
        }
        words = {int(pos): word for pos, word in case.get("words", {}).items()}
        return clean_qdc_artifacts(segs, stats, words)
    if pipeline == "boundary_repair":
        return apply_boundary_repair(segs, resolve_repair(case))
    if pipeline == "erases_span_repeat":
        repair = resolve_repair(case)
        got = erases_span_repeat(segs, repair)
        want = case.get("expected_erases")
        if want is None:
            raise SystemExit(f"{case.get('_path')}: need expected_erases bool")
        if got == bool(want):
            return True, None
        return False, f"want erases_span_repeat={want!r} got {got!r}"
    if pipeline == "rebase_timing_repair":
        return rebase_timing_repair(segs, resolve_repair(case))
    if pipeline == "leading_silence_offset":
        onset = case.get("audio_onset_ms")
        if onset is None:
            raise SystemExit(f"{case.get('_path')}: need audio_onset_ms")
        return offset_for_audio_onset(segs, onset)
    raise AssertionError("unreachable")


def check_confidence():
    words = {24: "نَصۡرُ", 25: "ٱللَّهِۗ", 26: "أَلَآ", 27: "إِنَّ", 28: "نَصۡرَ"}
    bad = [[24, 24460, 25000], [25, 25000, 25240], [26, 25240, 27200]]
    fixed = [[24, 24460, 25000], [25, 25000, 27160], [26, 27160, 28740]]
    baseline = bad + [[27, 27200, 30300], [28, 30300, 30800]]
    reference = [
        [24, 24440, 25030], [25, 25030, 27170], [26, 27170, 28740],
        [27, 28740, 30290], [28, 30290, 30790],
    ]
    conflict = [
        [24, 24460, 25000], [25, 25000, 25240], [26, 26981, 29443],
        [27, 29443, 30300], [28, 30300, 30800],
    ]
    pacing = [x[0][0] for x in suspicious_pacing(bad, words)] == [25]
    pacing &= suspicious_pacing(fixed, words) == []
    boundaries = [
        x[0] for x in boundary_conflicts(
            conflict, {"bundled": baseline, "quran-align": reference}
        )
    ] == [27]
    onset = offset_for_audio_onset([], 850) == []
    onset &= offset_for_audio_onset([[1, 850, 1_250]], 850) == [[1, 850, 1_250]]
    onset &= offset_for_audio_onset([[1, 500, 900]], 850) == [[1, 850, 1_250]]
    repeats = [[1, 0, 500], [2, 500, 1000], [1, 1000, 1500]]
    return pacing and boundaries and onset and boundary_conflicts(
        repeats, {"quran-align": reference}
    ) == []


def check_audio_onset_pipeline():
    normal_log = "silence_start: 0\nsilence_end: 1.179 | silence_duration: 1.179"
    eof_log = "silence_start: 0\nsilence_end: 4.101224 | silence_duration: 4.101224"
    parser = onset_detector.parse_onset(normal_log, "out_time_us=8000000") == 1_179
    parser &= onset_detector.parse_onset("no opening silence", "") == 0
    try:
        onset_detector.parse_onset(eof_log, "out_time_us=4101224")
        parser = False
    except onset_detector.IncompletePrefix:
        pass

    with (
        patch.object(onset_detector, "fetch_prefix", side_effect=[b"short", b"long"]) as fetch,
        patch.object(
            onset_detector,
            "analyze_prefix",
            side_effect=[onset_detector.IncompletePrefix("EOF"), 6_636],
        ),
    ):
        retry = onset_detector.detect_onset("Hani_Rifai_192kbps", (5, 109))
    retry_ok = retry == ((5, 109), 6_636) and [
        call.args[1] for call in fetch.call_args_list
    ] == [
        onset_detector.INITIAL_RANGE_BYTES,
        onset_detector.RETRY_RANGE_BYTES,
    ]

    segments = [[1, 500, 900], [2, 900, 1_200], [1, 1_200, 1_500]]
    with tempfile.TemporaryDirectory() as temp_dir:
        evidence = Path(temp_dir)
        (evidence / "test.json").write_text(json.dumps({
            "reciterId": 1,
            "reciterSlug": "Alafasy_128kbps",
            "offsets": {"2:253": 1_179},
        }))
        rows, onsets = apply_audio_onsets([(1, 2, 253, segments)], evidence)
    shifted = json.loads(rows[0][3])
    integration = onsets == {(1, 2, 253): 1_179} and shifted == [
        [1, 1_179, 1_579],
        [2, 1_579, 1_879],
        [1, 1_879, 2_179],
    ]
    return parser and retry_ok and integration


def audit_bundled_db():
    db = sqlite3.connect(ROOT / "data/quran.db")
    counts = {
        (s, a): n for s, a, n in db.execute(
            "SELECT surah_id,ayah_number,COUNT(*) FROM words GROUP BY 1,2"
        )
    }
    bad = []
    timings = {}
    for rid, s, a, raw in db.execute(
        "SELECT reciter_id,surah_id,ayah_number,segments FROM timings"
    ):
        segs = json.loads(raw)
        timings[(rid, s, a)] = segs
        starts = [x[1] for x in segs]
        if not segs or starts != sorted(set(starts)) or any(
            len(x) != 3 or not 1 <= x[0] <= counts[(s, a)] or x[2] <= x[1]
            for x in segs
        ):
            bad.append((rid, s, a))
    v2_rows = list(db.execute(
        "SELECT reciter_id,surah_id,ayah_number,segments FROM timings_v2"
    ))
    expected_v2 = {
        (rid, surah, ayah)
        for rid, surah, ayah, _ in load_timing_v2(counts)
    }
    exact = {(rid, s, a) for rid, s, a, _ in v2_rows} == expected_v2
    for rid, s, a, raw in v2_rows:
        segments = json.loads(raw)
        exact &= rid == 1 and bool(segments)
        for segment in segments:
            points = segment["keyframes"]
            exact &= bool(points) and points[-1]["progress"] == 1.0
            exact &= [p["offsetMs"] for p in points] == sorted(
                p["offsetMs"] for p in points
            )
    row = db.execute(
        "SELECT segments FROM timings WHERE reciter_id=1 "
        "AND surah_id=2 AND ayah_number=214"
    ).fetchone()
    starts = {x[0]: x[1] for x in json.loads(row[0])}
    exact &= [starts[p] for p in (25, 26, 27, 28)] == [
        24_925, 27_145, 29_175, 30_255
    ]
    row = db.execute(
        "SELECT segments FROM timings WHERE reciter_id=1 "
        "AND surah_id=5 AND ayah_number=52"
    ).fetchone()
    starts = {x[0]: x[1] for x in json.loads(row[0])}
    exact &= [starts[p] for p in (11, 12)] == [14_365, 15_605]
    repaired = {}
    for s, a in ((2, 229), (2, 235), (4, 19), (5, 66), (6, 145)):
        row = db.execute(
            "SELECT segments FROM timings WHERE reciter_id=1 "
            "AND surah_id=? AND ayah_number=?",
            (s, a),
        ).fetchone()
        repaired[(s, a)] = json.loads(row[0])
    exact &= order(repaired[(2, 229)]) == list(range(1, 47))
    exact &= [s for s in repaired[(2, 229)] if s[0] in (16, 17)] == [
        [16, 20_275, 21_105], [17, 22_680, 23_705]
    ]
    exact &= order(repaired[(2, 235)]) == list(range(1, 48))
    exact &= [s for s in repaired[(2, 235)] if s[0] in (22, 23)] == [
        [22, 23_195, 23_966], [23, 26_200, 26_975]
    ]
    exact &= [s for s in repaired[(5, 66)] if s[0] == 13] == [
        [13, 14_786, 15_865]
    ]
    exact &= not any(
        order(repaired[(4, 19)])[i : i + 4] == [17, 18, 17, 18]
        for i in range(len(repaired[(4, 19)]) - 3)
    )
    exact &= order(repaired[(6, 145)]) == list(range(1, 40))
    exact &= timings[(1, 2, 253)][:2] == [
        [1, 1_179, 2_094], [2, 2_094, 2_814]
    ]
    exact &= {
        key: timings[key][0][1]
        for key in ((4, 3, 113), (4, 4, 88), (7, 5, 109))
    } == {
        (4, 3, 113): 6_009,
        (4, 4, 88): 5_968,
        (7, 5, 109): 6_636,
    }
    for path in AUDIO_ONSETS_DIR.glob("*.json"):
        payload = json.loads(path.read_text(encoding="utf-8"))
        exact &= payload["detector"] == {
            "noiseDb": onset_detector.NOISE_DB,
            "sustainedMs": onset_detector.SUSTAINED_MS,
            "minimumOffsetMs": onset_detector.MIN_OFFSET_MS,
            "analysisMs": onset_detector.ANALYSIS_SECONDS * 1000,
            "initialRangeBytes": onset_detector.INITIAL_RANGE_BYTES,
            "retryRangeBytes": onset_detector.RETRY_RANGE_BYTES,
        }
        rid = payload["reciterId"]
        for verse, onset in payload["offsets"].items():
            s, a = map(int, verse.split(":"))
            exact &= timings[(rid, s, a)][0][1] >= onset
            exact &= db.execute(
                "SELECT audio_onset_ms FROM timings "
                "WHERE reciter_id=? AND surah_id=? AND ayah_number=?",
                (rid, s, a),
            ).fetchone() == (onset,)
    overrides = list((TOOLS / "timing_overrides").glob("*.json"))
    return not bad and exact and not overrides and db.execute(
        "PRAGMA integrity_check"
    ).fetchone()[0] == "ok"


def main():
    cases = load_cases()
    failures = []
    for case in cases:
        label = case.get("label") or case.get("id") or case["_path"]
        stem = Path(case["_path"]).stem
        if case.get("id") and case["id"] != stem:
            failures.append(
                (label, f"id {case['id']!r} must match filename stem {stem!r}", None)
            )
            print(f"  FAIL {label} (id/filename mismatch)")
            continue
        pipeline = case.get("pipeline") or "clean_qdc_artifacts"
        try:
            segs = resolve_input(case)
            if pipeline == "erases_span_repeat":
                ok, detail = run_pipeline(case, segs)
                got_order = None
            else:
                got = run_pipeline(case, segs)
                ok, detail = check_expected(case, got)
                got_order = order(got)
        except SystemExit as e:
            failures.append((label, str(e), None))
            print(f"  FAIL {label}: {e}")
            continue
        if not ok:
            failures.append((label, detail, got_order))
        print(f"  {'ok  ' if ok else 'FAIL'} {label}")
        if case.get("input_positions") is not None:
            print(f"        in={case['input_positions']}")
        if pipeline in {"boundary_repair", "erases_span_repeat", "rebase_timing_repair"}:
            print(
                f"        repair={case.get('repair_positions') or case.get('repair_segments')}"
            )
            if pipeline == "erases_span_repeat":
                print(f"        erases={case.get('expected_erases')}")
        elif got_order is not None:
            print(f"        out={got_order}")
        if not ok and detail:
            for line in detail.splitlines():
                print(f"        {line}")
    confidence_ok = check_confidence()
    audio_onset_ok = check_audio_onset_pipeline()
    timing_v2_ok = check_timing_v2_loader()
    database_ok = audit_bundled_db()
    lab_gold_ok, lab_gold_detail = check_v2_lab_gold_gate()
    structure_ok, structure_detail = check_v2_structure_gate()
    print(f"  {'ok  ' if confidence_ok else 'FAIL'} weighted 2:214 confidence checks")
    print(f"  {'ok  ' if audio_onset_ok else 'FAIL'} audio-onset detector and apply checks")
    print(f"  {'ok  ' if timing_v2_ok else 'FAIL'} Timing V2 source validation")
    print(f"  {'ok  ' if database_ok else 'FAIL'} bundled timing database invariants")
    print(f"  {'ok  ' if lab_gold_ok else 'FAIL'} V2 vs Timings Lab gold (≥99%)")
    print(f"  {'ok  ' if structure_ok else 'FAIL'} V2 structure gate (6:10 phrase re-say)")
    if not confidence_ok:
        failures.append(("weighted confidence", "2:214 checks failed", None))
    if not audio_onset_ok:
        failures.append(("audio onsets", "detector/apply checks failed", None))
    if not timing_v2_ok:
        failures.append(("Timing V2 loader", "source validation checks failed", None))
    if not database_ok:
        failures.append(("bundled database", "timing audit failed", None))
    if not lab_gold_ok:
        failures.append(("V2 Lab gold gate", lab_gold_detail, None))
    if not structure_ok:
        failures.append(("V2 structure gate", structure_detail, None))
    print()
    if failures:
        print(f"{len(failures)} FAILURE(S):")
        for label, detail, _got in failures:
            print(f"  {label}")
            if detail:
                for line in str(detail).splitlines():
                    print(f"    {line}")
        return 1
    print(f"all {len(cases) + 6} cases pass ({CASES_DIR.relative_to(Path.cwd())})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
