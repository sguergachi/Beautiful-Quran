#!/usr/bin/env python3
"""Build data/lexicon.db — Lane's Arabic-English Lexicon, keyed by QAC root.

Lane (1863–93) is the deepest Arabic–English lexicon in the public domain.
Perseus (Tufts) published a TEI XML edition of it; the laneslexicon project
mirrors those files with amendments. This script renders each root entry into
display text and keys it by the Quranic Arabic Corpus root spelling that
`quran.db` already uses, so the Root Viewer can show the classical entry for
the word under the reader's finger.

Why a separate database from quran.db:
  * quran.db is regenerated whenever timings change and is committed, so
    folding ~18 MB of lexicon into it would rewrite that blob in git history
    on every timing fix;
  * the lexicon carries its own attribution requirements (see MET_* below);
  * the apps load it lazily — nobody pays for it until they open a root.

Run `python3 tools/build_lexicon_db.py` only to deliberately rebuild the
asset, and bump `LexiconDatabase.DB_FILE_NAME` (Android) whenever you do.
"""

from __future__ import annotations

import sqlite3
import sys
import tarfile
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(__file__).resolve().parent / ".cache"
QURAN_DB = ROOT / "data" / "quran.db"
OUT = ROOT / "data" / "lexicon.db"

# The Perseus TEI XML, mirrored with amendments by the laneslexicon project.
# Pinned: this text is a fixed historical edition, not a moving target.
XML_COMMIT = "f3c19fb29f2cf2e12de3f97b7ce2b7a0d6682ea6"
XML_TGZ = f"https://codeload.github.com/laneslexicon/lexicon_xml/tar.gz/{XML_COMMIT}"

# Perseus asks for exactly this credit, and that their availability statement
# travels with the text. Both are stored in the DB and shown in the apps.
MET_CREDIT = (
    "Text provided by Perseus Digital Library, with funding from "
    "The U.S. Department of Education and The Max Planck Society."
)
MET_AVAILABILITY = (
    "This text may be freely distributed, subject to the following "
    "restrictions: You credit Perseus, as follows, whenever you use the "
    f"document: {MET_CREDIT} You leave this availability statement intact. "
    "You offer Perseus any modifications you make."
)

# Perseus writes the Arabic of Lane's entries in a Buckwalter variant. This is
# the table the laneslexicon parser applies to these exact files
# (lexicon_parser/lane.pl:330-345) — note O/W/I for the hamza carriers, where
# standard Buckwalter uses >/&/<, and the three combining marks at the end.
LANE_TRANSLITERATION = {
    **dict(zip("'|OWI}A", "ءآأؤإئا")),
    **dict(zip("bptvjHx", "بةتثجحخ")),
    **dict(zip("d*rzs$S", "دذرزسشص")),
    **dict(zip("DTZEg-f", "ضطظعغـف")),
    **dict(zip("qklmnhw", "قكلمنهو")),
    **dict(zip("YyFNKau", "ىيًٌٍَُ")),
    **dict(zip("i~o`{", "ِّْٰٱ")),
    **dict(zip("PJVG", "پچڤگ")),
    "^": "ٔ",  # hamza above, e.g. A^ -> أ once composed
    "=": "ٓ",  # maddah above
    "_": "ٕ",  # hamza below
    "@": "",  # bare-alef marker; carries no letter of its own
}

# QAC and Lane spell the same root differently in exactly two places, and
# matching may vary only these — anything looser starts binding roots to their
# neighbours (a *medial* ي/و is never swapped: شيء and شوى are different
# roots, and hollow roots like قول carry their real weak letter either way).
#
#   * the hamza carrier, which Lane's keys write as a carrier plus a combining
#     mark — شيء is filed under $yA^, رأى under rA^Y — where QAC writes a bare
#     alef (شيا, راي);
HAMZA_SPELLINGS = ["A", "A^", "A_", "A=", "'", "O", "I", "W", "}", "|", "{"]
#   * the final weak letter of a defective root: حيي is filed under Hyw, رمى
#     may be رمي or رما.
FINAL_WEAK_SPELLINGS = ["A", "Y", "y", "w", "A^", "'"]


def fetch(url: str, name: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / name
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    print(f"  downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "beautiful-quran-build/1.0"})
    tmp = dest.with_suffix(dest.suffix + ".tmp")
    with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
        f.write(r.read())
    tmp.replace(dest)
    return dest


def to_arabic(text: str) -> str:
    """Perseus transliteration -> Arabic script.

    NFC matters: Perseus writes أ as alef + combining hamza, which only reads
    as one letter once composed. A handful of rare letters are left as Perl
    escapes (\\x{698} for ژ) by the source; resolve those first.
    """
    import re

    text = re.sub(
        r"\\x\{([0-9a-fA-F]+)\}", lambda m: chr(int(m.group(1), 16)), text
    )
    # In a paradigm form Perseus writes the doubled radical as "3": rabu3a is
    # رَبُبَ, the aorist stem of رَبَّ. Spell the radical back out.
    text = re.sub(r"([A-Za-z$*'|}{^_=])([aiu])3", r"\1\2\1", text)
    return unicodedata.normalize(
        "NFC", "".join(LANE_TRANSLITERATION.get(c, c) for c in text)
    )


def to_lane_key(root: str) -> str:
    """A QAC root (Arabic) in the transliteration Lane's entries are keyed by."""
    reverse = {v: k for k, v in LANE_TRANSLITERATION.items() if len(v) == 1}
    return "".join(reverse.get(c, c) for c in root)


def key_variants(key: str) -> list[str]:
    """Spellings of one root that Lane might have filed it under.

    Ordered by how far each strays from the QAC spelling — exact key first,
    then one respelt letter, then two — so a root always binds to its closest
    entry rather than a neighbour's. Contractions come last: Lane files رَبَّ
    under rb, and the defective يدي under yd, where QAC writes both out.
    """
    import itertools

    pools: list[list[tuple[str, int]]] = []
    for index, char in enumerate(key):
        # (spelling, distance) — distance 0 keeps the QAC letter as written.
        options = [(char, 0)]
        if char in "A'|OWI}{":
            options += [(s, 1) for s in HAMZA_SPELLINGS if s != char]
        if index == len(key) - 1 and char in "AYyw":
            options += [(s, 1) for s in FINAL_WEAK_SPELLINGS if s != char]
        seen: set[str] = set()
        pools.append([o for o in options if not (o[0] in seen or seen.add(o[0]))])

    scored: dict[str, int] = {}
    for combo in itertools.product(*pools):
        candidate = "".join(spelling for spelling, _ in combo)
        distance = sum(step for _, step in combo)
        if distance < scored.get(candidate, 99):
            scored[candidate] = distance
    ordered = sorted(scored, key=lambda c: (scored[c], len(c), c))

    if len(key) >= 3 and key[-1] == key[-2]:
        ordered.append(key[:-1])  # geminate: ربب -> rb
    if len(key) >= 3 and key[-1] in "AYyw":
        ordered.append(key[:-1])  # defective: يدي -> yd
    return ordered


def render_entry(div2: ET.Element) -> tuple[str, int]:
    """One root's TEI subtree -> display text, plus its first printed page.

    Keeps Lane's structure — an entry per headword, his sense divisions, his
    quoted verse lines — and drops only what is meaningless off the page
    (page breaks, the typographic marker he sets after a headword).
    """
    # The source wraps lines mid-sentence, so every literal newline is noise.
    # Structure is emitted as sentinels and restored after whitespace collapse.
    PARAGRAPH, LINE = "\x00P", "\x00L"
    parts: list[str] = []
    page = 0

    def emit(text: str) -> None:
        if text:
            parts.append(text)

    def walk(node: ET.Element, in_arabic: bool) -> None:
        nonlocal page
        tag = node.tag
        arabic = in_arabic or (
            tag in ("foreign", "orth", "term") and node.get("lang") == "ar"
        )

        if tag == "head":
            return  # the root headword; the caller already knows the root
        if tag == "pb":
            if not page:
                try:
                    page = int(node.get("n") or 0)
                except ValueError:
                    page = 0
            emit(node.tail or "")
            return
        if tag == "entryFree":
            emit(PARAGRAPH)
        elif tag in ("quote", "L"):
            emit(LINE)
        elif tag == "itype":
            # Lane's verb-measure number: keep it as a label, not a bare digit.
            measure = "".join(node.itertext()).strip()
            emit(f"Form {measure}. " if measure.isdigit() else "")
            emit(node.tail or "")
            return

        text = node.text or ""
        if arabic:
            plain = text.strip()
            # A lone "*" is the mark Lane sets after a headword, not a word.
            text = "" if plain in ("*", "") else f" {to_arabic(text)} "
        emit(text)

        for child in node:
            walk(child, arabic)

        if tag in ("quote", "L"):
            emit(LINE)
        tail = node.tail or ""
        emit(to_arabic(tail) if in_arabic else tail)

    walk(div2, False)
    return _collapse("".join(parts), PARAGRAPH, LINE), page


def _collapse(body: str, paragraph: str, line: str) -> str:
    """Reflow to one space, then restore Lane's own divisions as breaks."""
    import re

    body = re.sub(r"\s+", " ", body)
    # Lane's sense divisions: -A2- opens a new sense of the headword itself,
    # -b2- splits the senses beneath one.
    body = re.sub(r"\s*-A\d+-\s*", paragraph, body)
    body = re.sub(r"\s*―\s*", " ", body)
    body = re.sub(r"\s*-b\d+-\s*", f"{line}• ", body)
    body = body.replace(paragraph, "\n\n").replace(line, "\n")
    body = re.sub(r" *\n *", "\n", body)
    body = re.sub(r"\n{3,}", "\n\n", body)
    # A break that landed before punctuation belonged mid-sentence.
    body = re.sub(r"\n+(?=[,.;:)\]])", " ", body)
    return re.sub(r" {2,}", " ", _tighten(body)).strip()


def _tighten(body: str) -> str:
    """Close the gaps the inline-Arabic padding opens around punctuation.

    `render_entry` sets every Arabic word off with a space on both sides so it
    never fuses to the English around it, which leaves Lane's own punctuation
    floating away from the word it belongs to — "كَتَبَهُ , (S,)" instead of
    "كَتَبَهُ, (S,)", "( إِسْتَمْلَاهُ )" instead of "(إِسْتَمْلَاهُ)". The
    padding is a rendering artefact, not Lane; close it here, at build time,
    so the apps read a clean entry (architecture invariant #2).

    Only the space is removed. Which side of the Arabic the comma displays on
    is decided by bidi over the whole paragraph, not by this whitespace.
    """
    import re

    body = re.sub(r" +([,;:.!?)\]])", r"\1", body)
    body = re.sub(r"([(\[]) +", r"\1", body)
    return re.sub(r"“ +", "“", re.sub(r" +”", "”", body))


def load_lane_entries(tgz: Path) -> dict[str, tuple[str, int]]:
    """Lane key -> (rendered entry, page). Keys can list several roots."""
    import re

    entries: dict[str, tuple[str, int]] = {}
    with tarfile.open(tgz) as tf:
        for member in tf.getmembers():
            if not member.name.endswith(".xml"):
                continue
            handle = tf.extractfile(member)
            assert handle is not None, f"unreadable member {member.name}"
            tree = ET.fromstring(handle.read().decode("utf-8"))
            for div2 in tree.iter("div2"):
                name = div2.get("n")
                if not name or div2.get("type") != "root":
                    continue
                text, page = render_entry(div2)
                if len(text) < 40:
                    continue  # letter-heading stubs, not root entries
                for key in re.split(r"\s+and\s+|,\s*", name):
                    key = key.strip()
                    if key and key not in entries:
                        entries[key] = (text, page)
    return entries


def match_roots(entries: dict[str, tuple[str, int]]) -> list[tuple[str, str, int, str]]:
    """Join QAC roots to Lane entries. Returns rows for root_entries."""
    con = sqlite3.connect(QURAN_DB)
    roots = [(r, n) for r, n in con.execute("SELECT root, occurrence_count FROM roots")]
    con.close()

    rows: list[tuple[str, str, int, str]] = []
    missed: list[tuple[str, int]] = []
    for root, occurrences in roots:
        key = to_lane_key(root)
        hit = next((k for k in key_variants(key) if k in entries), None)
        if hit is None:
            missed.append((root, occurrences))
            continue
        text, page = entries[hit]
        rows.append((root, hit, page, text))

    total_words = sum(n for _, n in roots)
    covered = total_words - sum(n for _, n in missed)
    print(f"  roots matched: {len(rows)}/{len(roots)} ({len(rows) / len(roots):.1%})")
    print(f"  word coverage: {covered}/{total_words} ({covered / total_words:.1%})")
    if missed:
        worst = ", ".join(f"{r} ({n}×)" for r, n in sorted(missed, key=lambda m: -m[1])[:8])
        print(f"  no Lane entry for {len(missed)} roots, e.g. {worst}")
    return rows


def write_db(rows: list[tuple[str, str, int, str]]) -> None:
    if OUT.exists():
        OUT.unlink()
    con = sqlite3.connect(OUT)
    con.executescript(
        """
        CREATE TABLE meta (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        );
        CREATE TABLE root_entries (
          root TEXT PRIMARY KEY,
          lane_key TEXT NOT NULL,
          page INTEGER NOT NULL,
          entry TEXT NOT NULL
        );
        """
    )
    con.executemany(
        "INSERT INTO meta VALUES (?,?)",
        [
            ("title", "An Arabic-English Lexicon"),
            ("author", "Edward William Lane"),
            ("credit", MET_CREDIT),
            ("availability", MET_AVAILABILITY),
            ("source_xml", f"github.com/laneslexicon/lexicon_xml @ {XML_COMMIT}"),
            ("generated", date.today().isoformat()),
        ],
    )
    con.executemany("INSERT INTO root_entries VALUES (?,?,?,?)", rows)
    con.commit()
    con.execute("VACUUM")
    con.close()


def validate() -> None:
    """The apps assume a clean asset — fail the build here, not on a phone."""
    con = sqlite3.connect(OUT)
    count, blanks, longest = con.execute(
        "SELECT COUNT(*), SUM(entry = ''), MAX(LENGTH(entry)) FROM root_entries"
    ).fetchone()
    assert count >= 1500, f"only {count} root entries — source or matching broke"
    assert not blanks, f"{blanks} empty entries"
    stray = con.execute(
        "SELECT COUNT(*) FROM root_entries WHERE entry LIKE '%-b2-%' OR entry LIKE '%-A2-%'"
    ).fetchone()[0]
    assert not stray, f"{stray} entries still carry raw sense markers"
    assert con.execute("SELECT COUNT(*) FROM meta").fetchone()[0] == 6, "meta incomplete"
    con.close()
    size_mb = OUT.stat().st_size / 1e6
    print(f"  validated {count} entries, longest {longest} chars, {size_mb:.1f} MB")


def main() -> int:
    if not QURAN_DB.exists():
        print(f"missing {QURAN_DB} — build it first with tools/build_db.py")
        return 1
    print("Lane's Lexicon -> data/lexicon.db")
    tgz = fetch(XML_TGZ, f"lexicon_xml-{XML_COMMIT[:7]}.tar.gz")
    entries = load_lane_entries(tgz)
    print(f"  parsed {len(entries)} Lane root keys")
    rows = match_roots(entries)
    write_db(rows)
    validate()
    print(f"  wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
