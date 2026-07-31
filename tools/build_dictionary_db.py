#!/usr/bin/env python3
"""Build data/dictionary.db — English Wiktionary Arabic senses for QAC lemmas.

Source: kaikki.org's English-Wiktionary Arabic extract (wiktextract JSONL),
CC-BY-SA / GFDL. We keep only lemmas that appear in quran.db so the committed
asset stays small; the apps load it lazily like lexicon.db.

Run `python3 tools/build_dictionary_db.py` only to deliberately rebuild, and
bump `DictionaryDatabase.DB_FILE_NAME` whenever you do.
"""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import sys
import unicodedata
import urllib.request
from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(__file__).resolve().parent / ".cache"
QURAN_DB = ROOT / "data" / "quran.db"
OUT = ROOT / "data" / "dictionary.db"
SHA_OUT = ROOT / "data" / "dictionary.db.sha256"

KAIKKI_URL = (
    "https://kaikki.org/dictionary/Arabic/kaikki.org-dictionary-Arabic.jsonl"
)
KAIKKI_NAME = "kaikki-dictionary-Arabic.jsonl"

MET_CREDIT = (
    "Dictionary text from English Wiktionary, extracted via wiktextract / "
    "kaikki.org (Tatu Ylonen). Available under CC BY-SA and GFDL."
)
MET_LICENSE = "CC-BY-SA and GFDL (same as Wiktionary)"
MET_SOURCE = "https://kaikki.org/dictionary/Arabic/"

# Combining marks + Quranic / Arabic diacritics we strip for fuzzy matching.
_TASHKEEL = re.compile(
    "["
    "\u064B-\u065F"  # fathatan..sukun + Quranic marks
    "\u0670"  # superscript alef
    "\u06D6-\u06ED"  # Quranic annotation signs
    "\u08E3-\u08FF"  # Arabic Extended-A marks
    "\u0610-\u061A"  # Arabic marks
    "\u0640"  # tatweel
    "]"
)

# Pure cross-reference glosses with no standalone meaning.
_NOISE_GLOSS = re.compile(
    r"^(?:"
    r"alternative (?:form|spelling) of|"
    r"spelling of|"
    r"soft form of|"
    r"rare spelling of|"
    r"obsolete spelling of|"
    r"misspelling of|"
    r"plural of|"
    r"feminine (?:singular|plural) of|"
    r"masculine (?:singular|plural) of|"
    r"dual of|"
    r"verbal noun of|"
    r"instance noun of|"
    r"active participle of|"
    r"passive participle of|"
    r"imperative of|"
    r"synonym of"
    r")\b",
    re.IGNORECASE,
)


def strip_tashkeel(text: str) -> str:
    """Bare skeleton for fuzzy matching.

    QAC often writes long-ā with dagger alef (كِتَٰب); Wiktionary writes a
    plain alef (كِتَاب). Promote the dagger to ا *before* stripping marks so
    both skeletons meet at كتاب rather than كتب vs كتاب.
    """
    text = unicodedata.normalize("NFC", text)
    text = text.replace("ٱ", "ا")
    # Dagger after ى/ي is a long-ā mark on the maqsūra, not a second alef
    # (بُشْرَىٰ ↔ بشرى). Elsewhere promote dagger to ا (كِتَٰب ↔ كتاب).
    text = text.replace("ىٰ", "ى").replace("يٰ", "ي")
    text = text.replace("ٰ", "ا").replace("\u0670", "ا")
    text = _TASHKEEL.sub("", text)
    return text


def fetch(url: str, name: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / name
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    print(f"  downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "beautiful-quran-build/1.0"})
    tmp = dest.with_suffix(dest.suffix + ".partial")
    # Resume a previous interrupted download when present.
    start = tmp.stat().st_size if tmp.exists() else 0
    headers = {"User-Agent": "beautiful-quran-build/1.0"}
    if start:
        headers["Range"] = f"bytes={start}-"
        print(f"  resuming from byte {start}")
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=600) as r, open(tmp, "ab" if start else "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    tmp.replace(dest)
    return dest


def load_qac_lemmas() -> set[str]:
    con = sqlite3.connect(QURAN_DB)
    lemmas = {
        row[0]
        for row in con.execute(
            "SELECT DISTINCT lemma FROM word_morphology WHERE lemma != ''"
        )
    }
    con.close()
    return lemmas


def build_lemma_indexes(
    lemmas: set[str],
) -> tuple[dict[str, str], dict[str, list[str]]]:
    """exact lemma → itself, and stripped skeleton → every QAC lemma that shares it."""
    exact = {lemma: lemma for lemma in lemmas}
    stripped: dict[str, list[str]] = defaultdict(list)
    for lemma in lemmas:
        stripped[strip_tashkeel(lemma)].append(lemma)
    return exact, {bare: sorted(values) for bare, values in stripped.items()}


def clean_gloss(gloss: str) -> str | None:
    text = " ".join(gloss.split()).strip()
    if len(text) < 2:
        return None
    if _NOISE_GLOSS.match(text):
        return None
    return text


def sense_glosses(senses: list[dict]) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for sense in senses:
        for gloss in sense.get("glosses") or []:
            cleaned = clean_gloss(str(gloss))
            if cleaned is None or cleaned in seen:
                continue
            seen.add(cleaned)
            out.append(cleaned)
    return out


def _is_arabic_script(text: str) -> bool:
    return any("\u0600" <= c <= "\u06FF" for c in text)


# Person-marked conjugations — matching these by stripped skeleton falsely
# pins senses on the wrong QAC lemma (أُبَشَّرَ → أَبْشِرُ).
_PERSON_TAGS = frozenset({"first-person", "second-person", "third-person"})


def _rank_keys(keys: set[str]) -> list[str]:
    return sorted((k for k in keys if k), key=lambda k: (-len(k), k))


def entry_match_keys(obj: dict) -> list[str]:
    """Keys safe for exact vocalized lookup (excludes person conjugations)."""
    keys = {str(obj.get("word") or "")}
    skip_tags = {"romanization", "table-tags", "class", "informal"}
    for form in obj.get("forms") or []:
        tags = set(form.get("tags") or [])
        value = str(form.get("form") or "")
        if not value or value == "no-table-tags" or tags & skip_tags:
            continue
        if tags & _PERSON_TAGS and "canonical" not in tags:
            continue
        if "canonical" in tags or _is_arabic_script(value):
            keys.add(value)
    return _rank_keys(keys)


def entry_stripped_keys(obj: dict) -> list[str]:
    """Headword + canonical citation forms only — for bare-skeleton matching.

    Participles / masdars in the forms table must not win stripped matching
    before the headword (مُبَشِّر starving بُشِّرَ of "bring good news").
    """
    keys = {str(obj.get("word") or "")}
    for form in obj.get("forms") or []:
        tags = set(form.get("tags") or [])
        value = str(form.get("form") or "")
        if value and "canonical" in tags and _is_arabic_script(value):
            keys.add(value)
    return _rank_keys(keys)


def resolve_lemmas(
    exact_keys: list[str],
    stripped_keys: list[str],
    exact: dict[str, str],
    stripped: dict[str, list[str]],
) -> list[str]:
    """Map Wiktionary headword keys onto one or more QAC lemmas.

    Exact vocalized hits win alone. Otherwise the stripped skeleton may land
    on several QAC lemmas (بُشِّرَ / بَشَر / بُشْر all bare to بشر) — attach
    the entry to every candidate so Form-II verbs are not left empty while a
    sibling noun absorbs the only Wiktionary row.
    """
    for key in exact_keys:
        if key in exact:
            return [exact[key]]
    for key in stripped_keys:
        bare = strip_tashkeel(key)
        if bare in exact:
            return [exact[bare]]
        if bare in stripped:
            return list(stripped[bare])
    # Derived forms (participles, masdars) may still uniquely identify a lemma.
    for key in exact_keys:
        bare = strip_tashkeel(key)
        cands = stripped.get(bare)
        if cands and len(cands) == 1:
            return list(cands)
        if bare in exact:
            return [exact[bare]]
    return []


def extract_entries(
    jsonl: Path,
    exact: dict[str, str],
    stripped: dict[str, list[str]],
) -> dict[str, dict]:
    """lemma -> {word, groups: [{pos, glosses}]}"""
    matched: dict[str, dict] = {}
    lines = 0
    arabic_entries = 0
    with open(jsonl, "r", encoding="utf-8") as fh:
        for line in fh:
            lines += 1
            if lines % 50_000 == 0:
                print(f"  … scanned {lines:,} lines, matched {len(matched)} lemmas")
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("lang") != "Arabic" and obj.get("lang_code") != "ar":
                continue
            arabic_entries += 1
            glosses = sense_glosses(obj.get("senses") or [])
            if not glosses:
                continue
            lemmas = resolve_lemmas(
                entry_match_keys(obj),
                entry_stripped_keys(obj),
                exact,
                stripped,
            )
            if not lemmas:
                continue
            pos = str(obj.get("pos") or "unknown")
            word = str(obj.get("word") or lemmas[0])
            for lemma in lemmas:
                bucket = matched.setdefault(lemma, {"word": word, "groups": {}})
                # Prefer a more vocalized Wiktionary headword when we see one.
                if len(word) > len(bucket["word"]):
                    bucket["word"] = word
                group = bucket["groups"].setdefault(pos, [])
                for gloss in glosses:
                    if gloss not in group:
                        group.append(gloss)
    print(f"  scanned {lines:,} lines / {arabic_entries:,} Arabic entries")
    return matched


def rows_from_matched(matched: dict[str, dict]) -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    for lemma, data in sorted(matched.items()):
        payload = [
            {"pos": pos, "glosses": glosses}
            for pos, glosses in sorted(data["groups"].items())
            if glosses
        ]
        if not payload:
            continue
        rows.append((lemma, data["word"], json.dumps(payload, ensure_ascii=False)))
    return rows


def write_db(rows: list[tuple[str, str, str]]) -> None:
    if OUT.exists():
        OUT.unlink()
    con = sqlite3.connect(OUT)
    con.executescript(
        """
        CREATE TABLE meta (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        );
        CREATE TABLE lemma_entries (
          lemma TEXT PRIMARY KEY,
          word TEXT NOT NULL,
          payload TEXT NOT NULL
        );
        """
    )
    con.executemany(
        "INSERT INTO meta VALUES (?,?)",
        [
            ("title", "English Wiktionary — Arabic"),
            ("credit", MET_CREDIT),
            ("license", MET_LICENSE),
            ("source", MET_SOURCE),
            ("extracted_from", KAIKKI_URL),
            ("generated", date.today().isoformat()),
        ],
    )
    con.executemany("INSERT INTO lemma_entries VALUES (?,?,?)", rows)
    con.commit()
    con.execute("VACUUM")
    con.close()


def write_fingerprint(version: str = "dictionary-v2.db") -> None:
    digest = hashlib.sha256(OUT.read_bytes()).hexdigest()
    SHA_OUT.write_text(
        "# Fingerprint of data/dictionary.db, enforced by DatabaseFingerprintTest.\n"
        "#\n"
        "# Installs cache the extracted dictionary under DictionaryDatabase.DB_FILE_NAME,\n"
        "# so a dictionary whose content changed without a version bump is silently\n"
        "# ignored by everyone who already has the app.\n"
        "#\n"
        "# Rebuilt the dictionary? Change BOTH lines below:\n"
        "#   1. bump `version` and DictionaryDatabase.DB_FILE_NAME together\n"
        "#   2. record the new digest: sha256sum data/dictionary.db\n"
        f"version={version}\n"
        f"sha256={digest}\n"
    )


def validate(lemma_count: int) -> None:
    con = sqlite3.connect(OUT)
    count, blanks = con.execute(
        "SELECT COUNT(*), SUM(payload = '' OR payload = '[]') FROM lemma_entries"
    ).fetchone()
    assert count >= max(200, lemma_count // 10), (
        f"only {count} lemma entries — matching likely broke"
    )
    assert not blanks, f"{blanks} empty payloads"
    assert con.execute("SELECT COUNT(*) FROM meta").fetchone()[0] == 6
    # Spot-check a few high-frequency Quran lemmas when present.
    for probe in ("قَالَ", "كِتَاب", "رَحْمَة", "ٱللَّه", "مِن"):
        row = con.execute(
            "SELECT payload FROM lemma_entries WHERE lemma = ?", (probe,)
        ).fetchone()
        if row:
            payload = json.loads(row[0])
            assert payload and payload[0]["glosses"], f"{probe} has empty glosses"
            print(f"  spot-check {probe}: {payload[0]['glosses'][0][:80]}")
    con.close()
    size_mb = OUT.stat().st_size / 1e6
    print(f"  validated {count} entries, {size_mb:.2f} MB")


def main() -> int:
    if not QURAN_DB.exists():
        print(f"missing {QURAN_DB} — build it first with tools/build_db.py")
        return 1
    print("English Wiktionary (kaikki) -> data/dictionary.db")
    jsonl = fetch(KAIKKI_URL, KAIKKI_NAME)
    lemmas = load_qac_lemmas()
    print(f"  QAC lemmas: {len(lemmas)}")
    exact, stripped = build_lemma_indexes(lemmas)
    matched = extract_entries(jsonl, exact, stripped)
    rows = rows_from_matched(matched)
    hit = len(rows) / len(lemmas) if lemmas else 0
    print(f"  lemmas matched: {len(rows)}/{len(lemmas)} ({hit:.1%})")
    if hit < 0.40:
        print(
            "  WARNING: coverage under 40% — reassess matching before shipping UI",
            file=sys.stderr,
        )
    write_db(rows)
    validate(len(lemmas))
    write_fingerprint()
    print(f"  wrote {OUT}")
    print(f"  wrote {SHA_OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
