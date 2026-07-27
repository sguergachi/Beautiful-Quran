"""Pure Timing V2 accuracy summaries shared by evaluation scripts."""
from __future__ import annotations

import math
import statistics


def summarize_errors(errors: list[int]) -> dict:
    ordered = sorted(abs(error) for error in errors)
    if not ordered:
        return {"count": 0, "medianMs": None, "p90Ms": None, "within100Pct": None}
    p90 = ordered[max(0, math.ceil(0.9 * len(ordered)) - 1)]
    return {
        "count": len(ordered),
        "medianMs": statistics.median(ordered),
        "p90Ms": p90,
        "within100Pct": 100 * sum(error <= 100 for error in ordered) / len(ordered),
    }


def summarize_clock_conventions(error_rows: list[list[int]]) -> dict:
    """Expose row clock bias instead of hiding it inside absolute error."""
    nonempty = [row for row in error_rows if row]
    row_medians = [statistics.median(row) for row in nonempty]
    signed = [error for row in nonempty for error in row]
    centered = [
        error - row_median
        for row, row_median in zip(nonempty, row_medians)
        for error in row
    ]
    interior = [
        error - row_median
        for row, row_median in zip(nonempty, row_medians)
        for error in row[1:-1]
    ]
    return {
        "signedMedianMs": statistics.median(signed) if signed else None,
        "perRowSignedMedianMs": (
            statistics.median(row_medians) if row_medians else None
        ),
        "rowCentered": summarize_errors(centered),
        "rowCenteredInterior": summarize_errors(interior),
    }
