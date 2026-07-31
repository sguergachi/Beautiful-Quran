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
    adjust_qdc_segments,
    apply_boundary_repair,
    apply_clocked_timing_repair,
    apply_one_utterance,
    boundary_conflicts,
    clean_qdc_artifacts,
    complete_monotonic_row,
    erases_span_repeat,
    finalize_timing_rows,
    load_audio_durations,
    load_audio_onsets,
    offset_for_audio_onset,
    preserve_peer_repeats,
    recover_negative_opening,
    refit_displaced_rows,
    rebase_qdc_clock,
    rebase_timing_repair,
    suspicious_pacing,
    trim_to_next_start,
)
import detect_audio_onsets as onset_detector  # noqa: E402

CASES_DIR = TOOLS / "timing_patch_cases"
PIPELINES = frozenset(
    {
        "adjust_qdc_segments",
        "boundary_repair",
        "clean_qdc_artifacts",
        "clock_shifted_repair",
        "erases_span_repeat",
        "leading_silence_offset",
        "preserve_peer_repeats",
        "qdc_clock_rebase",
        "rebase_timing_repair",
        "recover_negative_opening",
        "timing_correction",
    }
)


def segs_from_positions(positions, dur=800):
    """Contiguous segments long enough that the split-fragment merge (which
    keys on sub-word durations) never fires — position-topology tests only."""
    return [[p, i * dur, i * dur + dur] for i, p in enumerate(positions)]


def order(segs_):
    return [p for p, _, _ in segs_]


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
    if case.get("expected_invalid"):
        return (
            (True, None)
            if got_segs is None
            else (False, f"want invalid row, got segments {got_segs}")
        )
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
        }
        return clean_qdc_artifacts(segs, stats)
    if pipeline == "recover_negative_opening":
        recovered, _ = recover_negative_opening(segs)
        return recovered
    if pipeline == "adjust_qdc_segments":
        stats = {
            "zero_len": 0,
            "clamped": 0,
            "repeats": 0,
            "opening_shift": 0,
            "merged_splits": 0,
            "dropped_strays": 0,
            "noncontiguous_orphans": 0,
            "gap_phantoms": 0,
        }
        n_words = case.get("n_words") or max(p for p, _, _ in segs)
        return adjust_qdc_segments(segs, n_words, stats)
    if pipeline == "boundary_repair":
        return apply_boundary_repair(segs, resolve_repair(case))
    if pipeline == "clock_shifted_repair":
        offset = case.get("clock_offset_ms")
        if offset is None:
            raise SystemExit(f"{case.get('_path')}: need clock_offset_ms")
        return apply_clocked_timing_repair(segs, resolve_repair(case), offset)
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
    if pipeline == "timing_correction":
        positions = case.get("correction_positions")
        if positions is None:
            raise SystemExit(f"{case.get('_path')}: need correction_positions")
        return apply_one_utterance(segs, positions)
    if pipeline == "preserve_peer_repeats":
        repaired, _ = preserve_peer_repeats(segs, resolve_repair(case))
        return repaired
    if pipeline == "qdc_clock_rebase":
        reference = case.get("reference_segments")
        if reference is None:
            raise SystemExit(f"{case.get('_path')}: need reference_segments")
        rebased, _ = rebase_qdc_clock(segs, reference, case.get("audio_duration_ms"))
        return rebased
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
    onset &= offset_for_audio_onset([[1, 500, 900]], 850) == [[1, 850, 900]]
    onset &= offset_for_audio_onset([[1, 500, 900]], 1_000) == [[1, 1_000, 1_001]]
    onset &= offset_for_audio_onset(
        [[1, 1_300, 2_000]], 850
    ) == [[1, 850, 2_000]]
    onset &= offset_for_audio_onset(
        [[1, 0, 4_690], [2, 4_690, 5_410], [3, 5_410, 6_290]],
        1_951,
    ) == [[1, 1_951, 4_690], [2, 4_690, 5_410], [3, 5_410, 6_290]]
    onset &= offset_for_audio_onset(
        [[1, 0, 200], [2, 1_650, 2_370]],
        1_179,
    ) == [[1, 1_179, 1_650], [2, 1_650, 2_370]]
    onset &= offset_for_audio_onset(
        [[1, 0, 20], [2, 20, 100], [3, 100, 200]],
        250,
    ) is None
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

    # An MPEG1 Layer III 128 kbps 44.1 kHz stereo frame carrying a Xing count.
    xing = (
        b"ID3\x04\x00\x00\x00\x00\x00\x0a" + b"\x00" * 10
        + b"\xff\xfb\x90\x00" + b"\x00" * 32
        + b"Xing" + (1).to_bytes(4, "big") + (100).to_bytes(4, "big")
    )
    # 100 frames x 1152 samples at 44.1 kHz, not the 128 kbps the name implies.
    parser &= onset_detector.duration_ms(xing, 999_999) == 2_612
    cbr = b"\xff\xfb\x90\x00" + b"\x00" * 64
    parser &= onset_detector.duration_ms(cbr, 160_000) == 10_000

    with (
        patch.object(
            onset_detector,
            "fetch_prefix",
            side_effect=[(xing, 999_999), (xing, 999_999)],
        ) as fetch,
        patch.object(
            onset_detector,
            "analyze_prefix",
            side_effect=[onset_detector.IncompletePrefix("EOF"), 6_636],
        ),
    ):
        retry = onset_detector.detect_onset("Hani_Rifai_192kbps", (5, 109))
    retry_ok = retry == (6_636, 2_612) and [
        call.args[1] for call in fetch.call_args_list
    ] == [
        onset_detector.INITIAL_RANGE_BYTES,
        onset_detector.RETRY_RANGE_BYTES,
    ]

    with tempfile.TemporaryDirectory() as temp_dir:
        evidence = Path(temp_dir)
        (evidence / "test.json").write_text(json.dumps({
            "reciterId": 1,
            "reciterSlug": "Alafasy_128kbps",
            "offsets": {"2:253": 1_179},
            "durations": {"2:253": 53_820, "2:254": 2_000},
        }))
        onsets = load_audio_onsets(evidence)
        durations = load_audio_durations(evidence)
    evidence_ok = (
        onsets == {(1, 2, 253): 1_179}
        and durations == {(1, 2, 253): 53_820, (1, 2, 254): 2_000}
    )
    # A row sitting seconds past the voice that overruns the file is displaced,
    # so it re-anchors; one already on its onset keeps its correct opening.
    displaced = [[1, 7_110, 9_000], [2, 9_000, 21_500]]
    anchored = [[1, 1_500, 2_000], [2, 2_000, 2_600]]
    rows, refitted = refit_displaced_rows(
        [(1, 2, 253, displaced), (1, 2, 254, anchored)],
        {(1, 2, 253): 21_290, (1, 2, 254): 2_400},
        {(1, 2, 253): 190, (1, 2, 254): 1_500},
    )
    physical = refitted == [(1, 2, 253)]
    physical &= json.loads(rows[0][3]) == [[1, 190, 2_080], [2, 2_080, 14_580]]
    physical &= rows[1][3] == anchored
    physical &= trim_to_next_start(
        [[13, 11_950, 12_550], [15, 12_540, 15_040]]
    ) == [[13, 11_950, 12_540], [15, 12_540, 15_040]]
    return parser and retry_ok and evidence_ok and physical


def check_completion_pipeline():
    words = {1: "مِن", 2: "سَمِّ", 3: "ٱلۡخِيَاطِ"}
    reference = complete_monotonic_row(
        [[1, 0, 900], [3, 910, 1_500]], 3, words
    )
    if reference != [
        [1, 0, 450], [2, 450, 900], [3, 910, 1_500]
    ]:
        return False

    rows, onsets = finalize_timing_rows(
        [
            (7, 1, 1, [[1, 0, 20], [2, 20, 100], [3, 100, 200]]),
            (7, 1, 3, [[1, 0, 100], [2, 300, 400]]),
            (
                7,
                1,
                4,
                [
                    [1, 0, 100],
                    [2, 100, 200],
                    [1, 200, 300],
                    [2, 300, 400],
                    [4, 500, 600],
                ],
            ),
        ],
        {(1, 1): 3, (1, 2): 2, (1, 3): 3, (1, 4): 4},
        {
            (7, 1, 1): reference,
            (7, 1, 2): [[1, 0, 400], [2, 400, 900]],
            (7, 1, 3): [[1, 0, 100], [2, 100, 200], [3, 200, 300]],
            (7, 1, 4): [
                [1, 0, 100],
                [2, 100, 200],
                [3, 400, 500],
                [4, 500, 600],
            ],
        },
        {
            (7, 1, 1): 1_500,
            (7, 1, 2): 1_500,
            (7, 1, 3): 1_000,
            (7, 1, 4): 1_000,
        },
        {(7, 1, 1): 250, (7, 1, 2): 500},
        {(7, 1, 3), (7, 1, 4)},
    )
    built = {
        (rid, sid, ay): json.loads(raw) for rid, sid, ay, raw in rows
    }
    return (
        onsets == {(7, 1, 1): 250, (7, 1, 2): 500}
        and built[(7, 1, 1)]
        == [[1, 250, 450], [2, 450, 900], [3, 910, 1_500]]
        and built[(7, 1, 2)]
        == [[1, 500, 900], [2, 900, 1_400]]
        and built[(7, 1, 3)]
        == [[1, 0, 100], [2, 100, 200], [3, 200, 300]]
        and order(built[(7, 1, 4)]) == [1, 2, 1, 2, 3, 4]
    )


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
        if (
            not segs
            or {x[0] for x in segs} != set(range(1, counts[(s, a)] + 1))
            or starts != sorted(set(starts))
            or any(segs[i][2] > segs[i + 1][1] for i in range(len(segs) - 1))
            or any(
            len(x) != 3 or x[1] < 0
            or not 1 <= x[0] <= counts[(s, a)] or x[2] <= x[1]
            for x in segs
            )
        ):
            bad.append((rid, s, a))
    row = db.execute(
        "SELECT segments FROM timings WHERE reciter_id=1 "
        "AND surah_id=2 AND ayah_number=214"
    ).fetchone()
    starts = {x[0]: x[1] for x in json.loads(row[0])}
    exact = [starts[p] for p in (25, 26, 27, 28)] == [
        24_940, 27_160, 29_190, 30_270
    ]
    row = db.execute(
        "SELECT segments FROM timings WHERE reciter_id=1 "
        "AND surah_id=5 AND ayah_number=52"
    ).fetchone()
    starts = {x[0]: x[1] for x in json.loads(row[0])}
    exact &= [starts[p] for p in (11, 12)] == [14_360, 15_600]
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
        [16, 20_310, 21_140], [17, 22_715, 23_740]
    ]
    exact &= order(repaired[(2, 235)]) == list(range(1, 48))
    exact &= [s for s in repaired[(2, 235)] if s[0] in (22, 23)] == [
        [22, 23_230, 24_001], [23, 26_235, 27_010]
    ]
    exact &= [s for s in repaired[(5, 66)] if s[0] == 13] == [
        [13, 14_791, 15_870]
    ]
    exact &= not any(
        order(repaired[(4, 19)])[i : i + 4] == [17, 18, 17, 18]
        for i in range(len(repaired[(4, 19)]) - 3)
    )
    exact &= order(repaired[(6, 145)]) == list(range(1, 40))
    exact &= timings[(1, 2, 253)][:8] == [
        [1, 1_179, 1_650],
        [2, 1_650, 2_370],
        [3, 2_370, 3_500],
        [4, 3_500, 4_400],
        [5, 4_400, 4_970],
        [6, 4_970, 5_970],
        [7, 5_970, 6_710],
        [8, 6_710, 7_540],
    ]
    exact &= timings[(7, 4, 148)][:3] == [
        [1, 1_951, 4_690],
        [2, 4_690, 5_410],
        [3, 5_410, 6_290],
    ]
    exact &= {
        key: timings[key][0][1]
        for key in ((4, 3, 113), (4, 4, 88), (7, 5, 109))
    } == {
        (4, 3, 113): 6_009,
        (4, 4, 88): 5_968,
        (7, 5, 109): 6_636,
    }
    exact &= {
        (s, a) for rid, s, a in timings if rid == 7
    } == set(counts)
    exact &= set(counts) - {
        (s, a) for rid, s, a in timings if rid == 1
    } == {(37, 152)}
    for key, subsequence in {
        (7, 9, 33): [13, 9, 10, 14],
        (7, 22, 55): [15, 7, 8, 16],
        (7, 44, 22): [6, 1, 2, 3],
        (7, 74, 52): [7, 4, 8],
        (7, 4, 4): [12, 12],
    }.items():
        positions = order(timings[key])
        exact &= any(
            positions[i : i + len(subsequence)] == subsequence
            for i in range(len(positions) - len(subsequence) + 1)
        )
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
        # Every scanned ayah carries the duration ceiling the build gates on,
        # and no shipped row spans more time than its own recording holds.
        exact &= len(payload["durations"]) == payload["scannedAyahs"]
        for verse, length in payload["durations"].items():
            s, a = map(int, verse.split(":"))
            row = timings.get((payload["reciterId"], s, a))
            exact &= row is None or (
                row[-1][2] <= length
                and row[-1][2] - row[0][1] <= length
            )
        rid = payload["reciterId"]
        for verse, onset in payload["offsets"].items():
            s, a = map(int, verse.split(":"))
            row = timings.get((rid, s, a))
            if row is None:
                continue
            exact &= row[0][1] >= onset
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
                got_order = order(got) if got is not None else None
        except SystemExit as e:
            failures.append((label, str(e), None))
            print(f"  FAIL {label}: {e}")
            continue
        if not ok:
            failures.append((label, detail, got_order))
        print(f"  {'ok  ' if ok else 'FAIL'} {label}")
        if case.get("input_positions") is not None:
            print(f"        in={case['input_positions']}")
        if pipeline in {
            "boundary_repair",
            "erases_span_repeat",
            "preserve_peer_repeats",
            "rebase_timing_repair",
        }:
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
    completion_ok = check_completion_pipeline()
    database_ok = audit_bundled_db()
    print(f"  {'ok  ' if confidence_ok else 'FAIL'} weighted 2:214 confidence checks")
    print(f"  {'ok  ' if audio_onset_ok else 'FAIL'} audio evidence and onset checks")
    print(f"  {'ok  ' if completion_ok else 'FAIL'} complete fallback and physics checks")
    print(f"  {'ok  ' if database_ok else 'FAIL'} bundled timing database invariants")
    if not confidence_ok:
        failures.append(("weighted confidence", "2:214 checks failed", None))
    if not audio_onset_ok:
        failures.append(("audio onsets", "evidence/onset checks failed", None))
    if not completion_ok:
        failures.append(("timing finalizer", "completion/fallback checks failed", None))
    if not database_ok:
        failures.append(("bundled database", "timing audit failed", None))
    print()
    if failures:
        print(f"{len(failures)} FAILURE(S):")
        for label, detail, _got in failures:
            print(f"  {label}")
            if detail:
                for line in str(detail).splitlines():
                    print(f"    {line}")
        return 1
    print(f"all {len(cases) + 4} cases pass ({CASES_DIR.relative_to(Path.cwd())})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
