#!/usr/bin/env python3
"""Choose the verses worth a model review; keep the rest at their baseline.

The measured defect population is the one the reader complains about: an Arabic
word that the baseline mapping names as no printed token's head has no English
word to advance when it is recited. On the 392 reviewed verses that gap
predicted every "Arabic word the model had to name" and two thirds of the
larger ownership moves, while a fully covered verse still moved 28% of the
time — so covered verses are not certified, they are bounded by a deterministic
audit sample instead of a per-verse review. See docs/ENGLISH_ALIGNMENT_COVERAGE.md.
"""
import hashlib

AUDIT_SEED = "english-review-selection-v1"


def uncovered_words(row):
    """Arabic word positions (1-based) that no printed token claims as its head."""
    named = {token.get("head") for token in row["tokens"]}
    return [position for position in range(1, len(row["arabic"]) + 1) if position not in named]


def needs_review(row):
    """A verse whose baseline skips an Arabic word is structurally incomplete."""
    return bool(uncovered_words(row))


def audited(key, rate, seed=AUDIT_SEED):
    """Stable draw over the verse key alone: the same sample every run and selection."""
    drawn = int.from_bytes(hashlib.sha256(f"{seed}\0{key}".encode()).digest()[:8], "big")
    return drawn < rate * 2 ** 64


def review_queue(rows, selected, selection="risky", audit_rate=0.1, must_review=()):
    """Corpus order, filtered to the selected chapters and to the review policy."""
    keys = [key for key in rows if int(key.split(":")[0]) in selected]
    if selection == "all":
        return keys
    return [key for key in keys if key in must_review or needs_review(rows[key]) or audited(key, audit_rate)]
