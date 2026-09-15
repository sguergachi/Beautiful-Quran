#!/usr/bin/env python3
"""Normalize legacy by-page responses into the stable ``mushafs:1`` snapshot."""

import argparse
import json
import sqlite3
import sys

from build_db import align_qcf_words, assert_qcf_v2_runs, normalize_text


def source_rows(pages):
    glosses, page_of, layout = {}, {}, {}
    seen_words, seen_layout = set(), set()
    for page_number, page in enumerate(pages, start=1):
        for verse in page.get("verses", []):
            previous = None
            for word in verse.get("words", []):
                location = str(word.get("location") or "")
                parts = location.split(":")
                if len(parts) != 3:
                    continue
                key = (int(parts[0]), int(parts[1]))
                if word.get("char_type_name") == "end":
                    glyph = normalize_text(word.get("code_v2") or "")
                    if glyph and previous is not None:
                        previous_key, index = previous
                        text, mark, mark_page, mark_line = layout[previous_key][index]
                        layout[previous_key][index] = (
                            text,
                            f"{mark} {glyph}",
                            mark_page,
                            mark_line,
                        )
                    continue
                if word.get("char_type_name") != "word":
                    continue
                if location not in seen_words:
                    seen_words.add(location)
                    glosses.setdefault(key, []).append(
                        (
                            normalize_text((word.get("translation") or {}).get("text") or ""),
                            normalize_text((word.get("transliteration") or {}).get("text") or ""),
                        )
                    )
                    page_of.setdefault(key, page_number)
                glyph = normalize_text(word.get("code_v2") or "")
                if not glyph or location in seen_layout:
                    previous = None
                    continue
                seen_layout.add(location)
                layout.setdefault(key, []).append(
                    (
                        normalize_text(word.get("text_uthmani") or ""),
                        glyph,
                        int(word.get("page_number") or page_number),
                        int(word.get("line_number") or 0),
                    )
                )
                previous = (key, len(layout[key]) - 1)
    return glosses, page_of, layout


def normalize(database, pages):
    glosses, page_of, layout = source_rows(pages)
    assert_qcf_v2_runs(layout)
    db = sqlite3.connect(database)
    canonical = {}
    for surah, ayah, position, arabic in db.execute(
        "SELECT surah_id,ayah_number,position,arabic FROM words "
        "ORDER BY surah_id,ayah_number,position"
    ):
        canonical.setdefault((surah, ayah), []).append((position, arabic))
    db.close()

    records = []
    for (surah, ayah), words in canonical.items():
        arabic = [word for _position, word in words]
        aligned = align_qcf_words(arabic, layout.get((surah, ayah), []), surah, ayah)
        word_glosses = glosses.get((surah, ayah), [])
        if not word_glosses:
            raise ValueError(f"missing word content for {surah}:{ayah}")
        for position, _arabic in words:
            translation, transliteration = word_glosses[min(position - 1, len(word_glosses) - 1)]
            glyph, qcf_page, qcf_line, span_end = aligned.get(position, ("", 0, 0, position))
            records.append(
                {
                    "record_type": "mushaf_word",
                    "record_key": f"{surah}:{ayah}:{position}",
                    "surah_id": surah,
                    "ayah_number": ayah,
                    "position": position,
                    "translation_en": translation,
                    "transliteration": transliteration,
                    "qcf_v2": glyph,
                    "qcf_page": qcf_page,
                    "qcf_line": qcf_line,
                    "qcf_span_end": span_end,
                    "ayah_page": page_of.get((surah, ayah), qcf_page),
                }
            )
    if len(records) != 77_429:
        raise ValueError(f"expected 77429 word records, got {len(records)}")
    return {
        "schema_version": 1,
        "resource_group": "mushafs",
        "resource_id": 1,
        "records": records,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="data/quran.db")
    args = parser.parse_args()
    payload = json.load(sys.stdin)
    pages = payload.get("pages")
    if not isinstance(pages, list) or len(pages) != 604:
        raise ValueError("expected all 604 legacy mushaf pages")
    json.dump(normalize(args.database, pages), sys.stdout, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
