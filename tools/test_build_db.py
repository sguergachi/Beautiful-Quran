"""Unit tests for build_db qdc-artifact cleaning.

Runnable in-repo with no network or CTC cache:  python3 tools/test_build_db.py

Each case is a real defect (or a shape that must survive untouched). The
forward-spike cases came from Timings-Lab reports — e.g. Alafasy 16:61, where
qdc stamps words [17,18] at ~9.5 s (right after word 10) and then backtracks to
word 11, so the ink teleports to جَآءَ أَجَلُهُمْ and jumps back.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_db import clean_qdc_artifacts  # noqa: E402


def segs(positions, dur=800):
    """Contiguous segments long enough that the split-fragment merge (which
    keys on sub-word durations) never fires — we are testing spike/stray only."""
    return [[p, i * dur, i * dur + dur] for i, p in enumerate(positions)]


def order(segs_):
    return [p for p, _, _ in segs_]


def repeats(positions):
    mx, out = -1, []
    for p in positions:
        if p < mx:
            out.append(p)
        mx = max(mx, p)
    return out


# (label, input positions, expected output positions)
CASES = [
    # --- forward-spike runs that must be removed (folded into the prior word) ---
    ("16:61 spike [17,18] before 11",
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 17, 18, 11, 12, 13, 14, 15, 16, 17, 18, 19],
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]),
    ("28:32 spike [16,17,18] before 8",
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 17, 18, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19],
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]),
    ("60:10 spike [29,30] before 16",
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 29, 30, 15, 16, 17, 18],
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18]),
    ("single-segment spike (original rule still holds)",
     [1, 2, 3, 4, 5, 20, 6, 7, 8],
     [1, 2, 3, 4, 5, 6, 7, 8]),
    # --- shapes that must survive untouched ---
    ("real backward span-repeat 2:33",
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 8, 9, 10, 11, 12, 13, 14],
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 8, 9, 10, 11, 12, 13, 14]),
    ("real dropped word (forward jump, no retreat)",
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 14, 15, 16, 17, 18],
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 14, 15, 16, 17, 18]),
    ("plain monotonic",
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
     [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]),
]


def main():
    failures = []
    for label, pos_in, want in CASES:
        stats = {"merged_splits": 0, "dropped_strays": 0}
        got = order(clean_qdc_artifacts(segs(pos_in), stats))
        ok = got == want
        if not ok:
            failures.append((label, want, got))
        print(f"  {'ok  ' if ok else 'FAIL'} {label}")
        print(f"        in={pos_in}")
        print(f"        out={got}")
    print()
    if failures:
        print(f"{len(failures)} FAILURE(S):")
        for label, want, got in failures:
            print(f"  {label}\n    want {want}\n    got  {got}")
        return 1
    print(f"all {len(CASES)} cases pass")
    return 0


if __name__ == "__main__":
    sys.exit(main())
