"""Canonical Quran words and the letter form the acoustic models are given."""

from __future__ import annotations

import sqlite3
import unicodedata
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DB = Path(__file__).resolve().parents[2] / "data" / "quran.db"

# Rasm variants the models were never trained to tell apart.
_FOLD = {"ٱ": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"}


def letters(word: str) -> str:
    """Bare consonant skeleton: no marks, tatweel or Quranic signs, hamza/alif folded."""
    out = []
    for ch in unicodedata.normalize("NFKD", word):
        if unicodedata.category(ch).startswith("M") or ch == "ـ":
            continue
        ch = _FOLD.get(ch, ch)
        if "ء" <= ch <= "ي":
            out.append(ch)
    return "".join(out)


# What a reciter may say before the first verse (the app stores neither as verse text).
ISTIADHA = ("أعوذ", "بالله", "من", "الشيطان", "الرجيم")
BASMALA = ("بسم", "الله", "الرحمن", "الرحيم")
OPENINGS = {
    (): (),
    ("basmala",): BASMALA,
    ("istiadha",): ISTIADHA,
    ("istiadha", "basmala"): ISTIADHA + BASMALA,
}


@dataclass(frozen=True)
class Word:
    """One spoken word of the route. ayah 0 = a spoken opening (istiadha / basmala)."""

    ayah: int
    pos: int
    text: str
    kind: str = "verse"  # "verse", "istiadha", "basmala", or "fragment" (a word broken off and restarted)
    inserted: bool = False  # found in the audio, not in the canonical text


def surah_words(surah: int, db_path: Path = DEFAULT_DB) -> list[Word]:
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        rows = db.execute(
            "SELECT ayah_number, position, arabic FROM words WHERE surah_id=? "
            "ORDER BY ayah_number, position",
            (surah,),
        ).fetchall()
    finally:
        db.close()
    if not rows:
        raise ValueError(f"surah {surah} has no words in {db_path}")
    return [Word(a, p, w) for a, p, w in rows]


def opening_words(opening: tuple[str, ...]) -> list[Word]:
    words = []
    for kind in opening:
        for i, text in enumerate(OPENINGS[(kind,)], 1):
            words.append(Word(0, i, text, kind, inserted=True))
    return words
