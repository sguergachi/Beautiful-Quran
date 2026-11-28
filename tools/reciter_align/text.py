"""Canonical Quran words and the letter form the acoustic models are given."""

from __future__ import annotations

import sqlite3
import unicodedata
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DB = Path(__file__).resolve().parents[2] / "data" / "quran.db"

# Rasm variants the models were never trained to tell apart.
_FOLD = {"ٱ": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي"}


SHADDA = "\u0651"


def starts_merged(word: str) -> bool:
    """Whether the word's first letter carries a shadda.

    It is written there only where the previous word's tanween or nun merges into
    it (idgham: مُّبِينٞ after عَدُوّٞ is said "ʿaduwwum-mubīn"), and the merged
    sound, with its ghunna, belongs to this word.
    """
    seen_letter = False
    for ch in unicodedata.normalize("NFKD", word):
        if ch == SHADDA:
            return seen_letter
        if not unicodedata.category(ch).startswith("M") and "ء" <= _FOLD.get(ch, ch) <= "ي":
            if seen_letter:
                return False
            seen_letter = True
    return False


def letters(word: str) -> str:
    """Bare consonant skeleton: no marks, tatweel or Quranic signs, hamza/alif folded.

    A merged first letter (see `starts_merged`) is the one shadda kept, as a
    doubled letter. Given one letter, the models start the word where the ghunna
    ends, up to 0.8 s late.
    """
    out = []
    for ch in unicodedata.normalize("NFKD", word):
        if unicodedata.category(ch).startswith("M") or ch == "ـ":
            continue
        ch = _FOLD.get(ch, ch)
        if "ء" <= ch <= "ي":
            out.append(ch)
    if out and starts_merged(word):
        out.insert(0, out[0])
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


def surah_words(surah: int, db_path: Path = DEFAULT_DB, merged: bool = True) -> list[Word]:
    """The surah's words in order. `merged=False` gives every word its bare letters,
    without the idgham forms of `spoken` (what the route search runs on, see pipeline)."""
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
    if not merged:
        return [Word(a, p, unmerged(w)) for a, p, w in rows]
    words = []
    for i, (a, p, w) in enumerate(rows):
        if i == 0 or rows[i - 1][0] != a:
            # A verse is begun after a stop, where nothing merges into it: the shadda some
            # verses carry on their first letter is not said doubled (4:79 مَّآ). Given one,
            # the models reach back into the previous verse's last word, up to a second.
            w = unmerged(w)
        following = rows[i + 1][2] if i + 1 < len(rows) and rows[i + 1][0] == a else None
        words.append(Word(a, p, spoken(w, following)))
    return words


def unmerged(word: str) -> str:
    """The word without a merged first letter's shadda: `letters` gives its plain skeleton."""
    return word.replace(SHADDA, "", 1) if starts_merged(word) else word


def spoken(word: str, following: str | None) -> str:
    """The word as the models should hear it before `following` in the same verse.

    A bare final nun (no sukun) before a word whose first letter carries a shadda
    is fully merged into that letter (idgham: مِن نُّطۡفَةٖ is "min-nuṭfa", one long
    nasal). That sound is the next word's doubled first letter, so this word stops
    at its vowel; otherwise the models give the merged nasal to the nun here and
    the next word starts after it, up to 0.8 s late.
    """
    if following is None or not word.endswith("\u0646") or not starts_merged(following):
        return word
    return word[:-1]


def opening_words(opening: tuple[str, ...]) -> list[Word]:
    words = []
    for kind in opening:
        for i, text in enumerate(OPENINGS[(kind,)], 1):
            words.append(Word(0, i, text, kind, inserted=True))
    return words
