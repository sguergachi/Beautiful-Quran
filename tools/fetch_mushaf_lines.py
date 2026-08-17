"""Fetch the authoritative Madinah line assignment for every mushaf page.

Where the words of a page fall across its fifteen lines is not something to
infer: it is the print's own layout, and it is published alongside the QCF
fonts the print was cut for. Quran.com's v4 API returns a `line_number` per
word, keyed by `location` ("surah:ayah:word"), which is the same layout the
page fonts assume.

This exists as a cross-check on the layout we ship. Run it when a page looks
wrong and compare, word for word, against `qcf_line` in data/quran.db: when
that was done for page 281 the two agreed exactly, which is what ruled the data
out and sent the search back to the composition (see MUSHAF_DESIGN_LINE_EM).

    python3 tools/fetch_mushaf_lines.py            # all 604 pages
    python3 tools/fetch_mushaf_lines.py 281 3      # named pages, for spot checks

Output: tools/.cache/mushaf-lines.json — {"surah:ayah:word": line_number}.
"""

from __future__ import annotations

import json
import sys
import time
import urllib.request
from pathlib import Path

API = (
    "https://api.quran.com/api/v4/verses/by_page/{page}"
    "?words=true&word_fields=location,line_number,char_type_name&per_page=50"
)
CACHE = Path("tools/.cache")
OUT = CACHE / "mushaf-lines.json"
PAGES = 604


# The API refuses a bare urllib request; it wants a client that looks like one.
HEADERS = {"User-Agent": "beautiful-quran-build/1.0", "Accept": "application/json"}


def fetch_page(page: int) -> dict[str, int]:
    request = urllib.request.Request(API.format(page=page), headers=HEADERS)
    with urllib.request.urlopen(request, timeout=30) as response:
        data = json.load(response)
    lines: dict[str, int] = {}
    for verse in data.get("verses", []):
        for word in verse.get("words", []):
            # The circled ayah mark is a word to the API; it belongs to the
            # word before it for our purposes and carries no position of its own.
            if word.get("char_type_name") == "end":
                continue
            location = word.get("location")
            line = word.get("line_number")
            if location and line:
                lines[location] = int(line)
    return lines


def main(argv: list[str]) -> int:
    pages = [int(a) for a in argv[1:]] or list(range(1, PAGES + 1))
    CACHE.mkdir(parents=True, exist_ok=True)
    out: dict[str, int] = {}
    if OUT.exists() and len(pages) < PAGES:
        out = json.loads(OUT.read_text())

    for i, page in enumerate(pages, 1):
        for attempt in range(4):
            try:
                out.update(fetch_page(page))
                break
            except Exception as error:  # noqa: BLE001 - a retry loop wants them all
                if attempt == 3:
                    raise
                print(f"  page {page}: {error} — retrying")
                time.sleep(2 * (attempt + 1))
        if i % 25 == 0 or i == len(pages):
            print(f"  {i}/{len(pages)} pages, {len(out)} words")
        time.sleep(0.05)

    OUT.write_text(json.dumps(out, separators=(",", ":")))
    print(f"wrote {OUT} — {len(out)} words")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
