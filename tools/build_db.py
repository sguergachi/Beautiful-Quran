#!/usr/bin/env python3
"""Build the prepackaged quran.db SQLite asset for the Beautiful Quran app.

Sources (all fetched over HTTPS, cached in tools/.cache):
  * quran-json (npm)  — Uthmani Unicode text + Saheeh International translation
                        + surah metadata.  https://github.com/risan/quran-json
  * @kmaslesa/holy-quran-word-by-word-full-data (npm)
                      — per-word English gloss + transliteration (quran.com data).
  * Quranic Arabic Corpus morphology 0.4
                      — per-word root / lemma / POS (mirrored for unattended fetch).
  * cpfair/quran-align release zip
                      — word-level timing segments for everyayah.com recitations
                        (CC-BY 4.0).  Skipped with --skip-timings (e.g. in a
                        sandbox without GitHub access); the app then falls back
                        to whole-ayah highlighting.
  * tools/audio_onsets — generated voice onsets from the streamed everyayah MP3s
                        (only the compact committed measurements are consumed).

Output: data/quran.db (the canonical asset consumed by Android and web builds)

The word segmentation canon is the space-split of the Uthmani text; the WBW
gloss and the timing data are mapped onto it by position and clamped when a
source disagrees (10 known ayahs differ by one word — logged, not fatal).
"""

import argparse
from difflib import SequenceMatcher
import hashlib
import io
import json
import re
import sqlite3
import sys
import tarfile
import unicodedata
import urllib.request
import zipfile
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(__file__).resolve().parent / ".cache"
OUT = ROOT / "data" / "quran.db"
OVERRIDES_DIR = Path(__file__).resolve().parent / "timing_overrides"
# Auto-generated CTC-arbitrated structural repairs (tools/gen_repairs.py). These
# strip qdc alignment artifacts (split/mislabel false-repeats), restore flattened
# re-recitations, and fill dropped words. The override layer after this is local
# reproduction scratch only; CI rejects committed override JSON.
REPAIRS_DIR = Path(__file__).resolve().parent / "timing_repairs"
# Narrow, ear/acoustic-verified verdicts for shapes the sources cannot decide.
# Unlike repairs, these name one operation and cannot replace an ayah row.
CORRECTIONS_DIR = Path(__file__).resolve().parent / "timing_corrections"
# Audio-grounded leading-silence measurements produced by
# tools/detect_audio_onsets.py. Applied after structural repairs so the opening
# wash uses final topology, and before Lab overrides (whose marks use file time).
AUDIO_ONSETS_DIR = Path(__file__).resolve().parent / "audio_onsets"
MAX_AUDIO_ONSET_MS = 7_900
# Matching quran-align boundaries are independent witnesses of one per-ayah
# clock translation, so they cluster tightly when the witness is sound. Past a
# quarter second of median disagreement the reference row is broken and no
# translation is trustworthy (the 99th percentile of real rows is 240 ms).
MAX_CLOCK_DISAGREEMENT_MS = 250

QURAN_JSON_TGZ = "https://registry.npmjs.org/quran-json/-/quran-json-3.1.2.tgz"
WBW_TGZ = (
    "https://registry.npmjs.org/@kmaslesa/holy-quran-word-by-word-full-data"
    "/-/holy-quran-word-by-word-full-data-1.0.6.tgz"
)
ALIGN_ZIP = (
    "https://github.com/cpfair/quran-align/releases/download"
    "/release-2016-11-24/quran-align-data-2016-11-24.zip"
)
ALIGN_ZIP_SHA256 = "5eeb045d8a7895208c94d2d7ec243567f8f550728835411527c4ffa1e789c9b7"
# Official QAC morphology 0.4, mirrored for unattended fetch (email gate on
# corpus.quran.com/download). Verbatim copy — do not alter the source file.
QAC_MORPHOLOGY_URL = (
    "https://raw.githubusercontent.com/cltk/arabic_morphology_quranic-corpus"
    "/master/quranic-corpus-morphology-0.4.txt"
)
MUSHAF_LAYOUT_PAGE_URL = (
    "https://raw.githubusercontent.com/zonetecde/mushaf-layout"
    "/refs/heads/main/mushaf/page-{page:03d}.json"
)

# id, everyayah slug (audio dir + timing file key), display name, style
RECITERS = [
    (1, "Alafasy_128kbps", "Mishary Rashid Alafasy", "Murattal"),
    (2, "Husary_64kbps", "Mahmoud Khalil Al-Husary", "Murattal"),
    (3, "Abdul_Basit_Murattal_64kbps", "AbdulBaset AbdulSamad", "Murattal"),
    (4, "Minshawy_Murattal_128kbps", "Mohamed Siddiq El-Minshawi", "Murattal"),
    (5, "Abdurrahmaan_As-Sudais_192kbps", "Abdurrahman As-Sudais", "Murattal"),
    (6, "Saood_ash-Shuraym_128kbps", "Saud Ash-Shuraym", "Murattal"),
    (7, "Hani_Rifai_192kbps", "Hani Ar-Rifai", "Murattal"),
]

BASMALAH_WORDS = 4  # words in bismillah, prefixed to audio of every first ayah

# quran.com's `qdc` audio API serves segment data that PRESERVES repeats: when a
# reciter re-recites a phrase, later segments point back at an earlier word index
# (the reader renders these as a second, orange fade). quran-align cannot express
# this (one monotonic span per word), so for the reciters below we take timings
# from quran.com instead. The audio is the same everyayah recording we stream, so
# the per-verse windows line up; we rebase each verse's gapless-file offsets to
# ayah-relative ms. Map: our reciter id -> quran.com recitation id.
QDC_URL = (
    "https://api.quran.com/api/qdc/audio/reciters/{rid}"
    "/audio_files?chapter_number={ch}&segments=true"
)
QDC_REPEAT_RECITERS = {
    1: 7,  # Mishary Alafasy (murattal)
    2: 6,  # Mahmoud Khalil Al-Husary (murattal)
    3: 2,  # AbdulBaset AbdulSamad (murattal)
    4: 9,  # Mohamed Siddiq El-Minshawi (murattal)
    5: 3,  # Abdurrahman As-Sudais (also fills the missing quran-align timings)
    7: 5,  # Hani ar-Rifai (murattal)
    # Saud Ash-Shuraym (qdc 10) is one-pass on quran.com too — no repeats to add,
    # so he stays on quran-align.
}

# The qdc endpoint is live rather than versioned. Lock the accepted assembled
# payloads so a rebuild can never silently change the corpus under our rules.
QDC_SOURCE_SHA256 = {
    2: "1893d19fcf91d60ee0011b22855bd4d232acdafb06fc3520a87836f16fb0237a",
    3: "cef161c719204cb5a571631e9af68fb2eb121fb77abe9b196a774bc0882afd6e",
    5: "aac71cabf2c73163d7793d71edc7e6adcc9acf840a0b7c7a2c80feb56ca4d79c",
    6: "cf085127574416c1224a7178775486eb28d0d169d2261530eeaca4e84d7d4e07",
    7: "f6249a65b9c0aeedb99a5ae8594d415d77828265f8085b4a7392d7036fb1a36e",
    9: "692f055e3898784da093f948dd765b25403cfdd021f555d00b4d5c19d4b96562",
}


def verify_source(path, expected_sha256, label):
    """Refuse silent upstream drift in timing inputs."""
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected_sha256:
        raise SystemExit(
            f"{label} changed: expected sha256 {expected_sha256}, got {digest}. "
            "Audit the full corpus before updating the source lock."
        )


def fetch(url: str, name: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / name
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    print(f"  downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "beautiful-quran-build/1.0"})
    # Download to a tmp file and rename: an interrupted download must never
    # leave a partial file that the cache check above would serve on rerun.
    tmp = dest.with_suffix(dest.suffix + ".tmp")
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        f.write(r.read())
    tmp.replace(dest)
    return dest


def fetch_text(url: str, name: str) -> str:
    path = fetch(url, name)
    return path.read_text(encoding="utf-8")


def read_tar_member(tgz: Path, member: str) -> bytes:
    with tarfile.open(tgz) as tf:
        f = tf.extractfile(member)
        assert f is not None, f"{member} missing from {tgz.name}"
        return f.read()


def normalize_text(s: str) -> str:
    return s.replace(" ", " ").replace(" ", " ").strip()


def normalize_for_alignment(s: str) -> str:
    out = []
    char_map = {"ٱ": "ا", "أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي"}
    for ch in unicodedata.normalize("NFKD", s):
        if unicodedata.category(ch).startswith("M"):
            continue
        if ch == "ـ":
            continue
        ch = char_map.get(ch, ch)
        if "\u0621" <= ch <= "\u064a":
            out.append(ch)
    return "".join(out)


def load_text_and_meta(tgz: Path):
    surahs, ayahs = [], {}
    for s in range(1, 115):
        ch = json.loads(read_tar_member(tgz, f"package/dist/chapters/en/{s}.json"))
        surahs.append(
            (
                s,
                ch["name"],
                ch["transliteration"],
                ch["translation"],
                ch["type"],
                ch["total_verses"],
            )
        )
        for v in ch["verses"]:
            ayahs[(s, v["id"])] = (normalize_text(v["text"]), v["translation"])
    return surahs, ayahs


def load_wbw(tgz: Path):
    """verse_key -> list of (gloss, transliteration) ordered by word position."""
    pages = json.loads(read_tar_member(tgz, "package/data.json"))
    out = {}
    page_of = {}
    for page in pages:
        pnum = page["page"]
        for ay in page["ayahs"]:
            for w in ay["words"]:
                if w["char_type_name"] != "word":
                    continue
                sk, ak = w["parentAyahVerseKey"].split(":")
                key = (int(sk), int(ak))
                out.setdefault(key, []).append(
                    (
                        w.get("translation", {}).get("text") or "",
                        w.get("transliteration", {}).get("text") or "",
                    )
                )
                if key not in page_of:
                    page_of[key] = pnum
    return out, page_of


def load_qcf_v2_layout():
    """Return {(surah, ayah): [(word, glyph, page, line), ...]} from the public
    precomputed Madani Mushaf layout. Some visual words intentionally cover
    multiple canonical timing words; they are aligned later per ayah."""
    out = {}
    for page in range(1, 605):
        raw = fetch_text(MUSHAF_LAYOUT_PAGE_URL.format(page=page), f"mushaf-page-{page:03d}.json")
        data = json.loads(raw)
        for line in data.get("lines", []):
            if line.get("type") != "text":
                continue
            line_number = int(line.get("line") or 0)
            for word in line.get("words", []):
                location = word.get("location", "")
                parts = location.split(":")
                if len(parts) != 3:
                    continue
                glyph = normalize_text(word.get("qpcV2") or "")
                if not glyph:
                    continue
                key = (int(parts[0]), int(parts[1]))
                out.setdefault(key, []).append(
                    (
                        normalize_text(word.get("word") or ""),
                        glyph,
                        page,
                        line_number,
                    )
                )
    return out


def align_qcf_words(arabic_words, qcf_words, surah, ayah):
    aligned = {}
    canonical_norms = [normalize_for_alignment(w) for w in arabic_words]
    qcf_norms = [normalize_for_alignment(w[0]) for w in qcf_words]
    canonical_index = 0
    qcf_index = 0

    def loosely_equal(a, b):
        return a == b or a.replace("ي", "ا") == b.replace("ي", "ا")

    while canonical_index < len(canonical_norms) and qcf_index < len(qcf_words):
        start = canonical_index
        glyphs = []
        page = qcf_words[qcf_index][2]
        line = qcf_words[qcf_index][3]
        combined_canonical = ""
        combined_qcf = ""
        while True:
            if not combined_canonical and canonical_index < len(canonical_norms):
                combined_canonical += canonical_norms[canonical_index]
                canonical_index += 1
            if not combined_qcf and qcf_index < len(qcf_words):
                glyphs.append(qcf_words[qcf_index][1])
                combined_qcf += qcf_norms[qcf_index]
                qcf_index += 1
            if combined_canonical and combined_qcf and loosely_equal(combined_canonical, combined_qcf):
                break
            if canonical_index >= len(canonical_norms) and qcf_index >= len(qcf_words):
                break
            if len(combined_canonical) <= len(combined_qcf) and canonical_index < len(canonical_norms):
                combined_canonical += canonical_norms[canonical_index]
                canonical_index += 1
            elif qcf_index < len(qcf_words):
                glyphs.append(qcf_words[qcf_index][1])
                combined_qcf += qcf_norms[qcf_index]
                qcf_index += 1
            else:
                combined_canonical += canonical_norms[canonical_index]
                canonical_index += 1
        if not loosely_equal(combined_canonical, combined_qcf):
            raise ValueError(
                f"cannot align qcf v2 word {surah}:{ayah}: "
                f"canonical {combined_canonical!r}, qcf {combined_qcf!r}"
            )
        aligned[start + 1] = (" ".join(glyphs), page, line, canonical_index)
    if canonical_index != len(canonical_norms) or qcf_index != len(qcf_words):
        raise ValueError(
            f"qcf v2 alignment ended early for {surah}:{ayah}: "
            f"canonical {canonical_index}/{len(canonical_norms)}, "
            f"qcf {qcf_index}/{len(qcf_words)}"
        )
    return aligned


def load_timings(zip_path: Path, slug: str):
    """Return {(surah, ayah): [[word_idx0, start_ms, end_ms], ...]} for a reciter,
    or None if no parseable timing file exists (the reciter then ships without
    word highlighting rather than failing the whole build)."""
    with zipfile.ZipFile(zip_path) as zf:
        cand = [
            n
            for n in zf.namelist()
            if slug.lower() in n.lower()
            and n.endswith(".json")
            and not n.startswith("__MACOSX")
            and not n.rsplit("/", 1)[-1].startswith("._")
        ]
        if not cand:
            print(f"  !! no timing file for {slug}; zip contains: {zf.namelist()}", flush=True)
            return None
        # Prefer an exact "<slug>.json" basename over looser matches.
        cand.sort(key=lambda n: (n.rsplit("/", 1)[-1].lower() != f"{slug.lower()}.json", len(n)))
        raw = None
        for name in cand:
            payload = zf.read(name).decode("utf-8-sig", errors="replace")
            try:
                raw = json.loads(payload)
                break
            except json.JSONDecodeError as e:
                info = zf.getinfo(name)
                print(
                    f"  !! cannot parse {name} ({info.file_size} bytes): {e}; "
                    f"head: {payload[:80]!r}",
                    flush=True,
                )
        if raw is None:
            return None
    out = {}
    entries = raw if isinstance(raw, list) else raw.get("data", [])
    for e in entries:
        segs = []
        for seg in e.get("segments", []):
            # quran-align format: [word_start_idx, word_end_idx, start_ms, end_ms]
            if len(seg) >= 4:
                start_idx, _end_idx, start_ms, end_ms = seg[0], seg[1], seg[2], seg[3]
            else:  # defensive: [idx, start, end]
                start_idx, start_ms, end_ms = seg[0], seg[1], seg[2]
            segs.append([int(start_idx), int(start_ms), int(end_ms)])
        out[(int(e["surah"]), int(e["ayah"]))] = segs
    return out


def load_qdc_timings(qdc_id: int):
    """Fetch quran.com qdc segments for all 114 surahs and return
    {(surah, ayah): [[word_pos, start_ms, end_ms], ...]} with times rebased to
    ayah-relative ms and repeated words preserved (word_pos may go backward).
    The assembled result is cached so a rebuild needs no network."""
    cache = CACHE / f"qdc_{qdc_id}.json"
    if cache.exists() and cache.stat().st_size > 0:
        verify_source(cache, QDC_SOURCE_SHA256[qdc_id], f"qdc reciter {qdc_id}")
        raw = json.loads(cache.read_text(encoding="utf-8"))
        return {tuple(int(x) for x in k.split(":")): v for k, v in raw.items()}
    out = {}
    for ch in range(1, 115):
        path = fetch(QDC_URL.format(rid=qdc_id, ch=ch), f"qdc_{qdc_id}_ch{ch}.json")
        data = json.loads(path.read_text(encoding="utf-8"))
        afs = data.get("audio_files") or []
        if not afs:
            continue
        for vt in afs[0].get("verse_timings", []):
            s, a = (int(x) for x in vt["verse_key"].split(":"))
            base = vt["timestamp_from"]
            segs = [
                [int(seg[0]), int(seg[1]) - base, int(seg[2]) - base]
                for seg in (vt.get("segments") or [])
                if len(seg) >= 3
            ]
            if segs:
                out[(s, a)] = segs
    CACHE.mkdir(parents=True, exist_ok=True)
    cache.write_text(
        json.dumps({f"{s}:{a}": v for (s, a), v in out.items()}, separators=(",", ":")),
        encoding="utf-8",
    )
    verify_source(cache, QDC_SOURCE_SHA256[qdc_id], f"qdc reciter {qdc_id}")
    # clean up the per-chapter files now that they're assembled
    for ch in range(1, 115):
        (CACHE / f"qdc_{qdc_id}_ch{ch}.json").unlink(missing_ok=True)
    return out


# The qdc aligner's output carries artifacts that read as false repeats once
# HighlightEngine treats any non-forward word position as a backtrack:
#   * split slivers — a word whose onset or tail the aligner emits as a tiny
#     extra segment sharing that word's position; the sliver looks like an
#     instant re-say. These are sub-word fragments (< QDC_SPLIT_FRAGMENT_MS),
#     NOT two utterances — a real word is never that short;
#   * mislabeled strays — a single segment carrying the wrong word index
#     (often a sound-alike of an earlier word, e.g. 49:9 فَإِن tagged as وَإِن),
#     an isolated backjump the recitation never follows up on;
#   * non-contiguous span phantoms — the aligner stamps an early function word
#     at the *onset* of a real re-say (Alafasy 5:54: long يُجَٰهِدُونَ labeled
#     as word 4 مَن, so the chain reads [4, 21, 22, 23] after high-water 23).
#     HighlightEngine then paints orange from 4 through 23. The real re-say is
#     the contiguous component nearest the high water; the isolated earlier
#     index is a mislabel and is relabeled onto that component's start;
#   * gap phantoms — a backtrack run of early positions that does *not* re-cover
#     the high-water tip, immediately followed by a first-pass resume that
#     *skips* one or more words (Alafasy 5:59 after HW 11: [8, 9, 13…] with
#     word 12 missing). The early labels sit on the skipped words' time and are
#     not a re-say (a real re-say of the tip re-covers HW; a real earlier re-say
#     resumes at HW+1). Relabel the run onto the gap (issue #570);
#   * forward-gap duplicates — after a skipped word, the destination label is
#     duplicated exactly enough times to cover the gap (`1,3,3,4` with word 2
#     absent everywhere). Relabel onto `2,3`; abstain if word 2 occurs later;
#   * forward spikes — the same mislabel in the other direction; the too-large
#     index inflates the high-water mark so every following normal word until
#     that index reads as a repeat.
#
# A genuine single-word repeat also appears as two same-position segments, but
# BOTH are full utterances (each typically 0.5–2 s; the shorter half's median
# is ~1.2 s across all six reciters). Keying the sliver merge on duration — not
# on the gap, which is ~0 ms for a real immediate repeat too — is what separates
# the two: only a fragment too short to be a spoken word is folded away; two
# substantial utterances are preserved as a repeat. (An earlier version merged
# on gap alone and silently ate real repeats such as Hani 4:163 word 20,
# 1180 ms + 1510 ms — the bug the duration test fixes.)
#
# A flat 200 ms floor, though, is too low: the aligner also emits split slivers
# in the 200–450 ms range (e.g. Hani 4:143 word 10 = a 210 ms onset + a 1290 ms
# body — issue #123), which slip past the floor and bloom as false repeats. What
# still separates these from a real repeat is the *ratio* to their neighbour: a
# split is one tiny fragment against a full body (shorter/longer well under a
# third — the same word split by the aligner, often at the same spot across
# reciters, e.g. 27:20 and 15:7), whereas a real repeat is two comparable
# utterances. So above the flat floor we fold a same-position span only when it
# is BOTH under QDC_SPLIT_FRAGMENT_CEIL_MS and a small fraction of its
# neighbour. Real repeats — whose shorter half is ≥ ~500 ms and comparable to
# its partner — are untouched by construction.
QDC_SPLIT_MERGE_GAP_MS = 150  # a sliver sits flush against its word (0–50 ms)
QDC_SPLIT_FRAGMENT_MS = 200  # shorter than this, a same-position span is always
#                              a sub-word fragment, not a second utterance
QDC_SPLIT_FRAGMENT_CEIL_MS = 500  # in [FRAGMENT_MS, CEIL) it is a fragment only
#                                   when dwarfed by its neighbour (see ratio);
#                                   a real repeat's shorter half is ≥ ~500 ms
QDC_SPLIT_FRAGMENT_RATIO = 0.35  # shorter/longer below this = a split fragment,
#                                  not a peer utterance
QDC_SPIKE_JUMP = 3  # a forward jump this large that instantly retreats is noise
# Positions in a backtrack run within this distance count as one contiguous
# span-repeat (allows one dropped word inside a real re-say, e.g. 9,10,12,13).
QDC_SPAN_CONNECT_GAP = 2


def _position_components(positions, gap=QDC_SPAN_CONNECT_GAP):
    """Connected components of word positions under |a−b| ≤ gap adjacency."""
    uniq = sorted(set(positions))
    if not uniq:
        return []
    comps = []
    cur = {uniq[0]}
    for p in uniq[1:]:
        if p <= max(cur) + gap:
            cur.add(p)
        else:
            comps.append(cur)
            cur = {p}
    comps.append(cur)
    return comps


def _dephantom_noncontiguous_run(run_segs, stats):
    """Within one time-contiguous backtrack run, keep the component nearest the
    high water and relabel orphan components onto it.

    A real span-repeat is a near-contiguous block of positions (allowing one
    dropped word). An early isolated index in the same run is a mislabel of the
    real chain's onset — relabel it to the next kept segment so the time stays
    on the word being said (not folded into the previous first-pass word).
    Single-position runs (same-word re-say) are left alone.
    """
    if len(run_segs) <= 1:
        return run_segs
    positions = [s[0] for s in run_segs]
    comps = _position_components(positions)
    if len(comps) <= 1:
        return run_segs
    def score(c):
        return sum(1 for p in positions if p in c)
    # Prefer the component nearest high water: real re-says restart near where
    # the reciter left off; phantoms jump to early function words.
    keep = max(comps, key=lambda c: (max(c), score(c)))
    out = []
    for pos, start, end in run_segs:
        if pos in keep:
            out.append([pos, start, end])
            continue
        stats["noncontiguous_orphans"] += 1
        later = [s for s in run_segs if s[0] in keep and s[1] >= start]
        earlier = [s for s in run_segs if s[0] in keep and s[1] < start]
        if later:
            out.append([later[0][0], start, end])
        elif earlier:
            out.append([earlier[-1][0], start, end])
        # else drop (no kept peer — should not happen)
    if not out:
        return run_segs
    merged = [out[0]]
    for pos, start, end in out[1:]:
        if pos == merged[-1][0] and start <= merged[-1][2] + QDC_SPLIT_MERGE_GAP_MS:
            merged[-1][2] = max(merged[-1][2], end)
        else:
            merged.append([pos, start, end])
    return merged


def _map_run_onto_gap(run, gap):
    """Assign backtrack-run segments onto skipped first-pass positions in order."""
    if len(gap) == 1:
        g = gap[0]
        return [[g, s, e] for _, s, e in run]
    out = []
    for i, (_, s, e) in enumerate(run):
        out.append([gap[i] if i < len(gap) else gap[-1], s, e])
    return out


def _merge_adjacent_same_pos(segs):
    if not segs:
        return segs
    merged = [list(segs[0])]
    for pos, start, end in segs[1:]:
        if pos == merged[-1][0] and start <= merged[-1][2] + QDC_SPLIT_MERGE_GAP_MS:
            merged[-1][2] = max(merged[-1][2], end)
        else:
            merged.append([pos, start, end])
    return merged


def adjudicate_backtrack_runs(segs, stats):
    """Apply the two structural laws local to each backtrack run.

    A re-say is one near-contiguous component. Disconnected labels are mapped
    onto that component. A run followed by a skipped first-pass gap owns that
    gap rather than an earlier word. Neither law requires a genuine re-say to
    revisit the previous high-water tip.
    """
    if not segs:
        return segs, False
    out = []
    running_max = -1
    i = 0
    changed = False
    present = {seg[0] for seg in segs}
    while i < len(segs):
        pos, start, end = segs[i]
        if running_max >= 0 and pos > running_max + 1:
            j = i + 1
            while j < len(segs) and segs[j][0] == pos:
                j += 1
            gap = list(range(running_max + 1, pos))
            # A duplicated destination exactly covers words absent everywhere
            # else in the row: the labels slid forward across the gap. A true
            # repeat may also follow a jump, but its skipped word reappears.
            if j - i == len(gap) + 1 and not present.intersection(gap):
                out.extend(
                    [replacement, segs[k][1], segs[k][2]]
                    for replacement, k in zip([*gap, pos], range(i, j))
                )
                stats["gap_phantoms"] = stats.get("gap_phantoms", 0) + 1
                running_max = pos
                changed = True
                i = j
                continue
        if running_max >= 0 and pos <= running_max:
            j = i
            while j < len(segs) and segs[j][0] <= running_max:
                j += 1
            run = segs[i:j]
            fixed = _dephantom_noncontiguous_run(run, stats)
            changed |= [s[0] for s in fixed] != [s[0] for s in run]
            next_pos = segs[j][0] if j < len(segs) else None
            run_max = max(s[0] for s in fixed)
            if (
                next_pos is not None
                and next_pos > running_max + 1
                and run_max < running_max
            ):
                gap = list(range(running_max + 1, next_pos))
                out.extend(_merge_adjacent_same_pos(_map_run_onto_gap(fixed, gap)))
                stats["gap_phantoms"] = stats.get("gap_phantoms", 0) + 1
                changed = True
            else:
                out.extend(fixed)
            i = j
            continue
        out.append([pos, start, end])
        running_max = max(running_max, pos)
        i += 1
    return out, changed


def multi_position_span_repeat(segs):
    """True if any backtrack run covers two or more distinct word positions.

    Matches the generator's span-repeat protection invariant (see
    tools/timing_repairs/README.md): a multi-word re-cover must not be erased.
    """
    if not segs:
        return False
    running_max = -1
    i = 0
    while i < len(segs):
        pos = segs[i][0]
        if running_max >= 0 and pos <= running_max:
            j = i
            seen = set()
            while j < len(segs) and segs[j][0] <= running_max:
                seen.add(segs[j][0])
                j += 1
            if len(seen) >= 2:
                return True
            i = j
            continue
        running_max = max(running_max, pos)
        i += 1
    return False


def is_split_fragment(dur_a_ms, dur_b_ms):
    """True when a same-position pair is an aligner mid-word split, not a re-say.

    Same discriminator as [clean_qdc_artifacts]'s merge gate: a flat short floor,
    or a medium fragment dwarfed by its neighbour. Peer utterances (both halves
    substantial) are real single-word repeats and must survive.
    """
    shorter = min(dur_a_ms, dur_b_ms)
    longer = max(dur_a_ms, dur_b_ms)
    return shorter < QDC_SPLIT_FRAGMENT_MS or (
        shorter < QDC_SPLIT_FRAGMENT_CEIL_MS
        and shorter < QDC_SPLIT_FRAGMENT_RATIO * longer
    )


def erases_span_repeat(pre_segs, repair_segs):
    """True when a repair flattens a verified multi-word re-cover."""
    return multi_position_span_repeat(pre_segs) and not multi_position_span_repeat(
        repair_segs
    )


def preserve_peer_repeats(current, repaired):
    """Keep substantial same-word re-says while applying unrelated repairs.

    Whole-row CTC evidence can fix a missing word elsewhere while presenting a
    repeated word only once. Match repaired occurrences to the nearest source
    occurrences, then restore only the unmatched peer utterances. Split
    fragments are deliberately excluded because the cleaner owns those.
    """
    peer_positions = {
        current[i][0]
        for i in range(len(current) - 1)
        if current[i][0] == current[i + 1][0]
        and not is_split_fragment(
            current[i][2] - current[i][1],
            current[i + 1][2] - current[i + 1][1],
        )
    }
    extras = []
    for pos in peer_positions:
        source = [list(seg) for seg in current if seg[0] == pos]
        unmatched = set(range(len(source)))
        for segment in (seg for seg in repaired if seg[0] == pos):
            if unmatched:
                closest = min(unmatched, key=lambda i: abs(source[i][1] - segment[1]))
                unmatched.remove(closest)
        extras.extend(source[i] for i in sorted(unmatched))
    if not extras:
        return repaired, 0
    out = sorted([*[list(seg) for seg in repaired], *extras], key=lambda seg: seg[1])
    if len({seg[1] for seg in out}) != len(out):
        return current, 0
    return trim_to_next_start(out), len(extras)


def clean_qdc_artifacts(segs, stats):
    """Remove aligner artifacts (see above) from one ayah's time-sorted
    segments. Dropped spans are folded into the neighbouring segment so the
    karaoke sweep has no holes. Runs to a fixpoint because a dropped spike can
    reunite a word with its stray sliver."""
    stats.setdefault("noncontiguous_orphans", 0)
    stats.setdefault("gap_phantoms", 0)
    if not segs:
        return segs
    changed = True
    while changed:
        changed = False
        merged = [list(segs[0])]
        for pos, start, end in segs[1:]:
            last = merged[-1]
            if (
                pos == last[0]
                and start - last[2] <= QDC_SPLIT_MERGE_GAP_MS
                and is_split_fragment(last[2] - last[1], end - start)
            ):
                last[2] = max(last[2], end)
                stats["merged_splits"] += 1
                changed = True
            else:
                merged.append([pos, start, end])
        kept = []
        running_max = -1
        i = 0
        while i < len(merged):
            pos, start, end = merged[i]
            prev = kept[-1] if kept else None
            # Forward-spike RUN: one or more ascending segments that leap
            # >= QDC_SPIKE_JUMP past the running max and are then followed by a
            # retreat below where the run began — words the aligner emitted
            # prematurely that the recitation actually reaches later (they
            # re-appear, in order, further on). A single-segment run is the
            # original spike; a multi-word run needs the lookahead (16:61 emits
            # [17,18] before word 11; 28:32 emits [16,17,18] before word 8). A
            # real dropped word makes a forward jump too, but it keeps going
            # forward — only a jump that RETREATS is spurious. A smaller +2
            # jump is removed only when the aligner duplicates that premature
            # position and the retreat then walks canonically back through it
            # (16:106: 12,[14,14],12,13,14).
            if prev is not None and pos >= running_max + 2:
                j = i
                while (
                    j + 1 < len(merged)
                    and merged[j + 1][0] > running_max
                    and merged[j + 1][0] >= merged[j][0]
                ):
                    j += 1
                after = merged[j + 1][0] if j + 1 < len(merged) else None
                replay = (
                    [s[0] for s in merged[j + 1 : j + pos - after + 2]]
                    if after is not None
                    else []
                )
                confirmed_near_spike = (
                    after is not None
                    and pos == running_max + 2
                    and j > i
                    and all(merged[k][0] == pos for k in range(i, j + 1))
                    and after <= running_max
                    and replay == list(range(after, pos + 1))
                )
                if (
                    after is not None
                    and after < pos
                    and (
                        pos >= running_max + QDC_SPIKE_JUMP
                        or confirmed_near_spike
                    )
                ):
                    for k in range(i, j + 1):
                        prev[2] = max(prev[2], merged[k][2])
                        stats["dropped_strays"] += 1
                    changed = True
                    i = j + 1
                    continue
            next_pos = merged[i + 1][0] if i + 1 < len(merged) else None
            stray = (
                prev is not None
                and pos < prev[0]
                and (next_pos is None or next_pos > running_max)
            )
            if stray:
                prev[2] = max(prev[2], end)
                stats["dropped_strays"] += 1
                changed = True
                i += 1
                continue
            kept.append([pos, start, end])
            running_max = max(running_max, pos)
            i += 1
        kept, run_changed = adjudicate_backtrack_runs(kept, stats)
        changed |= run_changed
        segs = kept
    return segs


def recover_negative_opening(segs):
    """Translate a row so words entirely before ayah t=0 are not erased.

    Gapless qdc often places the first word(s) before ``timestamp_from``
    (negative ayah-relative times). The old clamp-to-zero path then saw
    ``end <= start`` and dropped them as zero-length — Alafasy 3:6 lost هُوَ
    so its ink only appeared when word 2 lit. One constant shift keeps the
    topology and lands the earliest start at 0 (everyayah still holds that
    audio). Partial openers (start < 0 < end) are left for the clamp below.
    """
    if not segs:
        return segs, False
    if not any(end <= 0 for _, _, end in segs):
        return segs, False
    min_start = min(start for _, start, _ in segs)
    if min_start >= 0:
        return segs, False
    shifted = [
        [pos, start - min_start, end - min_start] for pos, start, end in segs
    ]
    return shifted, True


def adjust_qdc_segments(segs, n_words, stats):
    """Clamp quran.com segments (already 1-based, ayah-relative) to our canonical
    word count while PRESERVING repeats; scrub aligner artifacts that would read
    as repeats that aren't in the audio; count the re-recited spans."""
    if not segs:
        return None
    stats.setdefault("opening_shift", 0)
    segs, shifted = recover_negative_opening(segs)
    if shifted:
        stats["opening_shift"] += 1
    adjusted = []
    for pos, start, end in sorted(segs, key=lambda s: s[1]):
        if start < 0:
            start = 0
        if end <= start:
            stats["zero_len"] += 1
            continue
        if pos < 1 or pos > n_words:
            stats["clamped"] += 1
            pos = max(1, min(pos, n_words))
        adjusted.append([pos, start, end])
    if not adjusted:
        return None
    adjusted = clean_qdc_artifacts(adjusted, stats)
    running_max = -1
    for pos, _, _ in adjusted:
        if pos <= running_max:
            stats["repeats"] += 1
        running_max = max(running_max, pos)
    return adjusted


def translate_segments(segs, offset_ms):
    """Shift a whole row along its own clock, keeping every span positive."""
    out = []
    for pos, start, end in segs:
        shifted_start = max(0, start + offset_ms)
        out.append([pos, shifted_start, max(shifted_start + 1, end + offset_ms)])
    return out


def strictly_increasing(segs):
    starts = [start for _, start, _ in segs]
    return starts == sorted(set(starts))


def fits_audio(segs, duration_ms):
    """True when a row ends inside its recording, or nothing was measured."""
    return not duration_ms or not segs or segs[-1][2] <= duration_ms


def describes_audio(segs, duration_ms):
    """True unless the row is longer than the whole recording.

    A row that spans more time than the file holds cannot be a description of
    it at any offset: some of its words could never be reached, so the wash
    would stall mid-ayah and the words before it would already be wrong.
    """
    return not duration_ms or not segs or segs[-1][2] - segs[0][1] <= duration_ms


def every_word_is_reachable(segs, duration_ms):
    """True when playback can reach the start of every word in the row."""
    return not duration_ms or not segs or segs[-1][1] < duration_ms


def trim_to_next_start(segs):
    """Clip each end at the following start so neighbouring spans never overlap."""
    out = [list(seg) for seg in segs]
    for i in range(len(out) - 1):
        out[i][2] = min(out[i][2], out[i + 1][1])
    return out


def qdc_clock_offset(segs, reference):
    """Return the robust qdc-to-everyayah clock translation for one ayah.

    Every matching quran-align boundary is one witness of the same constant
    translation, so real witnesses cluster. When they scatter instead, the
    reference row is itself broken (quran-align stretches a word across a long
    pause and its later boundaries drift), and no single translation is true —
    abstain rather than shift the ayah by whatever the median happened to be.
    """
    if not segs or not reference:
        return None
    qdc_first = {}
    for pos, start, end in segs:
        qdc_first.setdefault(pos, (start, end))
    reference_first = {pos: (start, end) for pos, start, end in reference}
    first_position = segs[0][0]
    offsets = [
        reference_first[pos][0] - start
        for pos, (start, _) in qdc_first.items()
        if pos != first_position and pos in reference_first
    ]
    if first_position in reference_first:
        offsets.append(reference_first[first_position][1] - segs[0][2])
    offsets.sort()
    if not offsets:
        return None
    if len(offsets) == 2 and offsets[0] != offsets[1]:
        return min(offsets, key=abs)
    candidate = offsets[len(offsets) // 2]
    disagreement = median([abs(o - candidate) for o in offsets])
    return None if disagreement > MAX_CLOCK_DISAGREEMENT_MS else candidate


def rebase_qdc_clock(segs, reference, duration_ms=None):
    """Translate a repeat-aware qdc row onto its everyayah MP3 clock.

    Quran-align cannot preserve repeats, but its monotonic boundaries use the
    exact files streamed by the app. The median first-pass boundary difference
    gives one robust per-ayah translation without altering qdc topology. A
    translation that would push the row past the end of the recording, or
    collapse its starts, is dropped in favour of the untranslated source row.

    Returns the row and the translation it carries, or None when the row is
    left on its source clock.
    """
    offset = qdc_clock_offset(segs, reference)
    if offset is None:
        return segs, None
    rebased = translate_segments(segs, offset)
    if not strictly_increasing(rebased):
        return segs, None
    reference_by_pos = {pos: (start, end) for pos, start, end in reference}
    first_reference = reference_by_pos.get(rebased[0][0])
    if first_reference and rebased[0][1] < first_reference[0] < rebased[0][2]:
        # qdc sometimes clamps a negative first-word start to zero. Restore
        # only that opening boundary; every later boundary keeps one offset.
        rebased[0][1] = first_reference[0]
    if not strictly_increasing(rebased) or not fits_audio(rebased, duration_ms):
        return segs, None
    return rebased, offset


def adjust_segments(segs, n_words, surah, ayah, stats):
    """Map quran-align 0-based word indices onto 1-based positions of our
    canonical words; strip basmalah words prefixed to first-ayah audio."""
    if not segs:
        return None
    max_idx = max(s[0] for s in segs)
    offset = 0
    if ayah == 1 and surah not in (1, 9) and max_idx >= n_words:
        offset = BASMALAH_WORDS  # audio starts with bismillah; text does not
        stats["basmalah_shift"] += 1
    adjusted = []
    for idx, start, end in segs:
        pos = idx - offset + 1  # -> 1-based
        if pos < 1:
            continue  # basmalah word: no text word to highlight
        if pos > n_words:
            stats["clamped"] += 1
            pos = n_words
        if end <= start:
            continue
        adjusted.append([pos, start, end])
    return adjusted or None


# A reciter shipping with fewer timed ayahs than this indicates a broken
# source file; fail the build instead of shipping silent gaps.
COVERAGE_THRESHOLD = 6000

# A short word window running at less than a quarter of its immediate
# neighbour's milliseconds-per-letter is usually a boundary stamped inside
# the word. Keep this deliberately conservative: it is a review gate for
# hand-authored overrides, not an attempt to retime source data.
PACING_RATIO = 4
PACING_SHORT_MS = 400


def suspicious_pacing(segs, words):
    """Return high-confidence adjacent word-duration outliers.

    Each result is ``(fast_segment, fast_word, fast_units, slow_segment,
    slow_word, slow_units)``. Repeats and non-consecutive positions are skipped
    because their neighbouring timeline spans need not be neighbouring text.
    """
    found = []
    windows = [
        (seg, segs[i + 1][1] if i + 1 < len(segs) else seg[2])
        for i, seg in enumerate(segs)
    ]
    for (left, left_end), (right, right_end) in zip(windows, windows[1:]):
        if right[0] != left[0] + 1:
            continue
        left_word, right_word = words.get(left[0], ""), words.get(right[0], "")
        left_units = max(1, len(normalize_for_alignment(left_word)))
        right_units = max(1, len(normalize_for_alignment(right_word)))
        left_pace = (left_end - left[1]) / left_units
        right_pace = (right_end - right[1]) / right_units
        fast, fast_word, fast_units, fast_pace, slow, slow_word, slow_units, slow_pace = (
            ([left[0], left[1], left_end], left_word, left_units, left_pace,
             [right[0], right[1], right_end], right_word, right_units, right_pace)
            if left_pace < right_pace
            else ([right[0], right[1], right_end], right_word, right_units, right_pace,
                  [left[0], left[1], left_end], left_word, left_units, left_pace)
        )
        if fast[2] - fast[1] <= PACING_SHORT_MS and fast_pace * PACING_RATIO < slow_pace:
            found.append((fast, fast_word, fast_units, slow, slow_word, slow_units))
    return found


BOUNDARY_SUPPORT_MS = 250
BOUNDARY_CONFLICT_MS = 500
BOUNDARY_SOURCE_WEIGHTS = {"bundled": 1, "quran-align": 2}


def boundary_evidence(delta):
    if abs(delta) <= BOUNDARY_SUPPORT_MS:
        return "supports"
    return "conflicts" if abs(delta) > BOUNDARY_CONFLICT_MS else "uncertain"


def boundary_conflicts(segs, sources):
    """Find proposed word starts that strongly conflict with an independent source.

    Sources are compared after removing their median per-ayah clock offset.
    Repeat rows are skipped: one-pass quran-align cannot arbitrate a backtrack.
    Results are ``(position, proposed_start, evidence)``, where evidence maps
    each source name to its signed residual from the proposed boundary.
    """
    positions = [s[0] for s in segs]
    if positions != sorted(set(positions)):
        return []
    proposed = {pos: start for pos, start, _ in segs}
    residuals = {}
    for name, source in sources.items():
        source_positions = [s[0] for s in source]
        if source_positions != sorted(set(source_positions)):
            continue
        starts = {pos: start for pos, start, _ in source}
        common = proposed.keys() & starts.keys()
        if len(common) < 3:
            continue
        offset = median(proposed[pos] - starts[pos] for pos in common)
        residuals[name] = {
            pos: proposed[pos] - starts[pos] - offset for pos in common
        }
    found = []
    for pos in positions[1:]:
        evidence = {name: values[pos] for name, values in residuals.items() if pos in values}
        support = sum(
            BOUNDARY_SOURCE_WEIGHTS.get(name, 1)
            for name, delta in evidence.items()
            if abs(delta) <= BOUNDARY_SUPPORT_MS
        )
        conflict = sum(
            BOUNDARY_SOURCE_WEIGHTS.get(name, 1)
            for name, delta in evidence.items()
            if abs(delta) > BOUNDARY_CONFLICT_MS
        )
        if conflict > support:
            found.append((pos, proposed[pos], evidence))
    return found


def ingest_reciter_timings(rid, word_counts, timing_rows, stats, adjust):
    """Adjust + store one reciter's segments; returns ayahs covered.

    ``adjust((surah, ayah), n_words)`` returns the cleaned segments for that
    ayah or None; None counts as missing.
    """
    covered = 0
    for key, n in word_counts.items():
        segs = adjust(key, n)
        if segs:
            timing_rows.append((rid, key[0], key[1], json.dumps(segs, separators=(",", ":"))))
            covered += 1
        else:
            stats["missing"] += 1
    return covered


def _split_segment(seg, positions, words):
    """Split one aligner span across canonical words it merged or omitted."""
    if len(positions) == 1:
        return [list(seg)]
    pos, start, end = seg
    if end - start < len(positions):
        return None
    weights = [
        max(1, len(normalize_for_alignment(words.get(p, "")))) for p in positions
    ]
    total = sum(weights)
    boundaries = [start]
    consumed = 0
    for weight in weights[:-1]:
        consumed += weight
        boundaries.append(start + (end - start) * consumed // total)
    boundaries.append(end)
    if boundaries != sorted(set(boundaries)):
        return None
    return [
        [position, boundaries[i], boundaries[i + 1]]
        for i, position in enumerate(positions)
    ]


def complete_monotonic_row(segs, n_words, words=None):
    """Expand a monotonic source row so every canonical word has one span.

    quran-align occasionally merges adjacent Quran tokens. Its surviving span
    then owns the complete audio window, so divide that window by normalized
    word length instead of leaving one canonical word permanently unlit.
    """
    if not segs:
        return None
    words = words or {}
    merged = []
    for pos, start, end in sorted(segs, key=lambda s: s[1]):
        if merged and pos == merged[-1][0]:
            merged[-1][2] = max(merged[-1][2], end)
        else:
            merged.append([pos, start, end])
    positions = [seg[0] for seg in merged]
    if positions != sorted(set(positions)):
        return None

    out = []
    first_pos = positions[0]
    if first_pos > 1:
        split = _split_segment(merged[0], range(1, first_pos + 1), words)
        if split is None:
            return None
        out.extend(split)
        start_index = 1
    else:
        start_index = 0

    for i in range(start_index, len(merged)):
        seg = merged[i]
        next_pos = merged[i + 1][0] if i + 1 < len(merged) else n_words + 1
        if next_pos <= seg[0]:
            return None
        split = _split_segment(seg, range(seg[0], next_pos), words)
        if split is None:
            return None
        out.extend(split)
    return out if [seg[0] for seg in out] == list(range(1, n_words + 1)) else None


def alignment_reference(zip_path, rid, slug, word_counts, word_text=None):
    """Load quran-align as a complete monotonic boundary witness and fallback."""
    data = load_timings(zip_path, slug)
    if data is None:
        return {}
    word_text = word_text or {}
    out = {}
    stats = {"basmalah_shift": 0, "clamped": 0, "missing": 0}
    for (surah, ayah), n_words in word_counts.items():
        segs = adjust_segments(data.get((surah, ayah)), n_words, surah, ayah, stats)
        completed = complete_monotonic_row(
            segs, n_words, word_text.get((surah, ayah))
        )
        if completed:
            out[(rid, surah, ayah)] = completed
    return out


def rebase_timing_repair(current, repaired):
    """Apply a structural repair without replacing unrelated current timings."""
    current_positions = [s[0] for s in current]
    repaired_positions = [s[0] for s in repaired]
    use_repair = set()
    current_index = {}
    for tag, i1, i2, j1, j2 in SequenceMatcher(
        a=current_positions, b=repaired_positions, autojunk=False
    ).get_opcodes():
        if tag == "equal":
            current_index.update((j1 + offset, i1 + offset) for offset in range(j2 - j1))
        else:
            use_repair.update(range(max(0, j1 - 1), min(len(repaired), j2 + 1)))
    merged = [
        list(repaired[j] if j in use_repair or j not in current_index else current[current_index[j]])
        for j in range(len(repaired))
    ]
    starts = [max(0, seg[1]) for seg in merged]
    if starts != sorted(set(starts)):
        return repaired
    for i, seg in enumerate(merged):
        seg[1] = starts[i]
        next_start = starts[i + 1] if i + 1 < len(starts) else None
        if next_start is not None and seg[2] > next_start:
            seg[2] = next_start
        if seg[2] <= seg[1]:
            seg[2] = next_start if next_start is not None else seg[1] + 1
    return merged


def apply_boundary_repair(current, repaired):
    """Replace only the explicitly supplied segments in a timing row."""
    if not repaired:
        raise ValueError("boundary repair must name at least one position")
    replacement = {seg[0]: list(seg) for seg in repaired}
    if len(replacement) != len(repaired):
        raise ValueError("boundary repair positions must be unique")
    current_positions = [seg[0] for seg in current]
    if any(current_positions.count(pos) != 1 for pos in replacement):
        raise ValueError("boundary repair positions must occur once in the source row")
    out = [replacement.get(seg[0], list(seg)) for seg in current]
    starts = [seg[1] for seg in out]
    if starts != sorted(set(starts)):
        raise ValueError("boundary repair must preserve unique start order")
    if any(out[i][2] > out[i + 1][1] for i in range(len(out) - 1)):
        raise ValueError("boundary repair overlaps its neighbouring segment")
    if any(seg[2] <= seg[1] for seg in out):
        raise ValueError("boundary repair creates an empty segment")
    return out


def sanitize_timing_row(segs):
    """Clamp a translated opening to zero, or reject a collapsed timing row."""
    out = []
    for pos, start, end in segs:
        start = max(0, start)
        out.append([pos, start, max(start + 1, end)])
    starts = [seg[1] for seg in out]
    valid = starts == sorted(set(starts)) and not any(
        out[i][2] > out[i + 1][1] for i in range(len(out) - 1)
    )
    return out if valid else None


def apply_clocked_timing_repair(current, repaired, clock_offset):
    """Merge one structural repair on the current clock, failing open safely."""
    translated = translate_segments(repaired, clock_offset)
    merged = rebase_timing_repair(current, translated) if current else translated
    return sanitize_timing_row(merged) or current


def rows_past_audio(timing_rows, durations):
    """Every ayah whose last mark falls beyond the end of its recording."""
    return [
        (rid, sid, ay)
        for rid, sid, ay, segs in timing_rows
        if not fits_audio(
            json.loads(segs) if isinstance(segs, str) else segs,
            durations.get((rid, sid, ay)),
        )
    ]


def refit_displaced_rows(timing_rows, durations, onsets, eligible_rows=None):
    """Re-anchor a row that overruns its recording because it starts too late.

    A row whose marks run off the end may simply sit at the wrong offset —
    quran-align occasionally places an ayah seconds into a file that opens on
    the voice. Pulling it back to the measured onset (or to the start, which
    the absent onset evidence puts within `MIN_OFFSET_MS`) makes every word
    reachable again. Rows already sitting on their onset do not move, so a row
    that merely trails a little past the end keeps its correct opening.
    """
    out = []
    refitted = []
    for rid, sid, ay, segs in timing_rows:
        key = (rid, sid, ay)
        row = json.loads(segs) if isinstance(segs, str) else segs
        duration = durations.get(key)
        shift = onsets.get(key, 0) - row[0][1] if row else 0
        if (
            (eligible_rows is not None and key not in eligible_rows)
            or fits_audio(row, duration)
            or shift >= 0
        ):
            out.append((rid, sid, ay, segs))
            continue
        shifted = translate_segments(row, shift)
        if fits_audio(shifted, duration) and strictly_increasing(shifted):
            out.append((rid, sid, ay, json.dumps(shifted, separators=(",", ":"))))
            refitted.append(key)
        else:
            out.append((rid, sid, ay, segs))
    return out, refitted


def drop_rows_longer_than_audio(timing_rows, durations):
    """Withhold word marks that cannot all play inside their recording.

    A handful of source rows describe a longer recitation than the file the app
    streams — a different take, or an ayah the publisher split differently. The
    reader falls back to lighting the whole ayah for these, which stays honest,
    rather than washing words at times the audio never reaches. The same
    fallback applies when the final word itself starts after playback ends.
    """
    kept = []
    dropped = []
    for rid, sid, ay, segs in timing_rows:
        row = json.loads(segs) if isinstance(segs, str) else segs
        duration = durations.get((rid, sid, ay))
        if describes_audio(row, duration) and every_word_is_reachable(row, duration):
            kept.append((rid, sid, ay, segs))
        else:
            dropped.append((rid, sid, ay))
    return kept, dropped


def apply_one_utterance(segs, positions):
    """Collapse one verified ``A,B,A,B`` aligner loop to a single utterance."""
    if len(positions) != 2 or positions[1] != positions[0] + 1:
        raise ValueError("one_utterance needs two consecutive positions")
    pattern = positions * 2
    matches = [
        i
        for i in range(len(segs) - 3)
        if [seg[0] for seg in segs[i : i + 4]] == pattern
    ]
    if len(matches) != 1:
        raise ValueError(
            f"one_utterance expected one {pattern} loop, found {len(matches)}"
        )
    i = matches[0]
    return [
        *[list(seg) for seg in segs[:i]],
        list(segs[i]),
        list(segs[i + 3]),
        *[list(seg) for seg in segs[i + 4 :]],
    ]


def apply_timing_corrections(timing_rows, corrections_dir=CORRECTIONS_DIR):
    """Apply narrow typed verdicts that cannot be inferred from row topology."""
    by_key = {
        (rid, sid, ay): json.loads(segs) if isinstance(segs, str) else segs
        for rid, sid, ay, segs in timing_rows
    }
    applied = 0
    files = sorted(corrections_dir.glob("*.json")) if corrections_dir.is_dir() else []
    for path in files:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(f"  !! cannot parse correction {path.name}: {e}", file=sys.stderr)
            sys.exit(1)
        for edit in payload.get("edits") or []:
            key = (
                int(edit["reciterId"]),
                int(edit["surahId"]),
                int(edit["ayah"]),
            )
            if key not in by_key:
                print(
                    f"  !! correction {path.name}: source row "
                    f"{key[1]}:{key[2]} missing",
                    file=sys.stderr,
                )
                sys.exit(1)
            op = edit.get("op")
            try:
                if op == "one_utterance":
                    by_key[key] = apply_one_utterance(
                        by_key[key], [int(p) for p in edit.get("positions") or []]
                    )
                else:
                    raise ValueError(f"unknown op {op!r}")
            except ValueError as e:
                print(
                    f"  !! correction {path.name}: {key[1]}:{key[2]}: {e}",
                    file=sys.stderr,
                )
                sys.exit(1)
            applied += 1
    print(f"  typed corrections: {applied} verdict(s) across {len(files)} file(s)")
    return [
        (rid, sid, ay, json.dumps(segs, separators=(",", ":")))
        for (rid, sid, ay), segs in sorted(by_key.items())
    ]


def apply_timing_repairs(timing_rows, word_counts, clock_offsets=None, durations=None):
    """Apply auto-generated CTC-arbitrated repairs (tools/timing_repairs/*.json)
    on top of the current source rows. Structural differences and their
    immediate neighbours use the repair; matching segments retain current
    timing so stale full-row patches cannot overwrite unrelated improvements.
    Repairs that erase an existing multi-position span-repeat are skipped.
    Substantial same-position re-says are restored per position, so an
    unrelated repair can still land without flattening a genuine repeat."""
    clock_offsets = clock_offsets or {}
    durations = durations or {}
    slug_by_id = {r[0]: r[1] for r in RECITERS}
    by_key = {(rid, sid, ay): segs for (rid, sid, ay, segs) in timing_rows}
    files = sorted(REPAIRS_DIR.glob("*.json")) if REPAIRS_DIR.is_dir() else []
    files = [f for f in files if not f.name.endswith(".flagged.json")]
    if not files:
        return timing_rows
    by_kind = {}
    applied = 0
    span_protected = 0
    peer_protected = 0
    rebased = 0
    clock_rejected = 0
    clock_untranslated = 0
    for path in files:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(f"  !! cannot parse repair {path.name}: {e}", file=sys.stderr)
            sys.exit(1)
        for edit in payload.get("edits") or []:
            rid, sid, ay = int(edit["reciterId"]), int(edit["surahId"]), int(edit["ayah"])
            if rid not in slug_by_id:
                print(f"  !! repair {path.name}: unknown reciterId {rid}", file=sys.stderr)
                sys.exit(1)
            n_words = word_counts.get((sid, ay))
            if n_words is None:
                print(f"  !! repair {path.name}: surah {sid} ayah {ay} not in corpus", file=sys.stderr)
                sys.exit(1)
            segs = []
            for s in edit.get("segments") or []:
                pos, start, end = int(s[0]), int(s[1]), int(s[2])
                if pos < 1 or pos > n_words or start < 0 or end <= start:
                    print(f"  !! repair {path.name}: surah {sid} ayah {ay} bad segment {s}", file=sys.stderr)
                    sys.exit(1)
                segs.append([pos, start, end])
            segs.sort(key=lambda s: s[1])
            key = (rid, sid, ay)
            offset = clock_offsets.get(key, 0)
            pre_raw = by_key.get(key)
            kind = edit.get("kind", "repair")
            if kind == "boundary":
                if pre_raw is None:
                    print(
                        f"  !! repair {path.name}: boundary source row {sid}:{ay} missing",
                        file=sys.stderr,
                    )
                    sys.exit(1)
                current = json.loads(pre_raw) if isinstance(pre_raw, str) else pre_raw
                try:
                    merged = sanitize_timing_row(
                        apply_boundary_repair(current, translate_segments(segs, offset))
                    )
                    if merged is None:
                        raise ValueError("clock translation collapses segment starts")
                except ValueError as e:
                    print(f"  !! repair {path.name}: surah {sid} ayah {ay}: {e}", file=sys.stderr)
                    sys.exit(1)
                by_key[key] = json.dumps(merged, separators=(",", ":"))
                by_kind[kind] = by_kind.get(kind, 0) + 1
                applied += 1
                continue
            if pre_raw is not None:
                pre = json.loads(pre_raw) if isinstance(pre_raw, str) else pre_raw
                if erases_span_repeat(pre, segs):
                    span_protected += 1
                    continue
            current = json.loads(pre_raw) if isinstance(pre_raw, str) else pre_raw or []
            duration = durations.get(key)
            merged = apply_clocked_timing_repair(current, segs, offset)
            if offset and not fits_audio(merged, duration):
                # This repair was already written on the file clock: translating
                # it would run the ayah past the end of its own recording.
                untranslated = apply_clocked_timing_repair(current, segs, 0)
                if fits_audio(untranslated, duration):
                    merged = untranslated
                    clock_untranslated += 1
            if merged is current:
                # The translation collapsed the row; the source timings stand.
                clock_rejected += 1
                continue
            merged, protected = preserve_peer_repeats(current, merged)
            peer_protected += protected
            if merged != translate_segments(segs, offset):
                rebased += 1
            by_key[key] = json.dumps(merged, separators=(",", ":"))
            by_kind[kind] = by_kind.get(kind, 0) + 1
            applied += 1
    new_rows = [(rid, sid, ay, segs) for (rid, sid, ay), segs in sorted(by_key.items())]
    print(
        f"  repairs: {applied} ayah(s) across {len(files)} file(s), "
        f"{rebased} rebased, {span_protected} span-protected, "
        f"{peer_protected} peer repeat(s) preserved, "
        f"{clock_rejected} unsafe-clock skipped, "
        f"{clock_untranslated} kept on the file clock — {by_kind}"
    )
    return new_rows


def offset_for_audio_onset(segs, onset_ms):
    """Hold the first wash until the first voiced sample of its everyayah file.

    Only the first boundary may move. If word two predates the measured voice,
    the row is on an unsafe clock and must fall back to a file-clock reference;
    onset evidence must never translate an entire uncertain row.
    """
    if not segs:
        return segs
    onset_ms = int(onset_ms)
    if len(segs) > 1 and onset_ms >= segs[1][1]:
        return None
    out = [list(seg) for seg in segs]
    out[0][1] = onset_ms
    if out[0][2] <= onset_ms:
        next_start = out[1][1] if len(out) > 1 else onset_ms + 1
        out[0][2] = max(onset_ms + 1, next_start)
    return out


def audio_evidence(evidence_dir=AUDIO_ONSETS_DIR):
    """Yield (path, reciter id, payload) for each measured-audio evidence file."""
    slug_by_id = {r[0]: r[1] for r in RECITERS}
    files = sorted(evidence_dir.glob("*.json")) if evidence_dir.is_dir() else []
    for path in files:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            rid = int(payload["reciterId"])
            slug = payload["reciterSlug"]
        except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as e:
            print(f"  !! audio evidence {path.name}: invalid file ({e})", file=sys.stderr)
            sys.exit(1)
        if slug_by_id.get(rid) != slug:
            print(
                f"  !! audio evidence {path.name}: reciter {rid}/{slug!r} does not match",
                file=sys.stderr,
            )
            sys.exit(1)
        yield path, rid, payload


def load_audio_durations(evidence_dir=AUDIO_ONSETS_DIR):
    """Playable length of every measured everyayah recording, keyed per ayah.

    This is the hard ceiling for a timing row: marks past the end of the file
    can never be reached, so a clock translation that crosses it is wrong.
    """
    durations = {}
    for path, rid, payload in audio_evidence(evidence_dir):
        for verse_key, raw_duration in (payload.get("durations") or {}).items():
            try:
                sid, ay = (int(part) for part in verse_key.split(":"))
                duration = int(raw_duration)
            except (AttributeError, TypeError, ValueError) as e:
                print(
                    f"  !! audio duration {path.name}: bad entry "
                    f"{verse_key!r}: {raw_duration!r} ({e})",
                    file=sys.stderr,
                )
                sys.exit(1)
            if duration <= 0:
                print(
                    f"  !! audio duration {path.name}: {verse_key} is {duration} ms",
                    file=sys.stderr,
                )
                sys.exit(1)
            durations[(rid, sid, ay)] = duration
    return durations


def load_audio_onsets(evidence_dir=AUDIO_ONSETS_DIR):
    """Measured first sustained voice, keyed by reciter and ayah."""
    onsets = {}
    for path, rid, payload in audio_evidence(evidence_dir):
        for verse_key, raw_onset in (payload.get("offsets") or {}).items():
            try:
                sid, ay = (int(part) for part in verse_key.split(":"))
                onset = int(raw_onset)
            except (AttributeError, TypeError, ValueError) as e:
                print(
                    f"  !! audio onset {path.name}: bad entry "
                    f"{verse_key!r}: {raw_onset!r} ({e})",
                    file=sys.stderr,
                )
                sys.exit(1)
            if onset < 0 or onset > MAX_AUDIO_ONSET_MS:
                print(
                    f"  !! audio onset {path.name}: {verse_key} onset "
                    f"{onset} ms out of range",
                    file=sys.stderr,
                )
                sys.exit(1)
            onsets[(rid, sid, ay)] = onset
    return onsets


def apply_audio_onsets(timing_rows, evidence_dir=AUDIO_ONSETS_DIR):
    """Clamp safe first washes; leave unsafe rows for the fallback stage."""
    by_key = {(rid, sid, ay): segs for rid, sid, ay, segs in timing_rows}
    onsets = load_audio_onsets(evidence_dir)
    aligned = 0
    refused = 0
    for key, onset in onsets.items():
        raw = by_key.get(key)
        if raw is None:
            continue
        current = json.loads(raw) if isinstance(raw, str) else raw
        corrected = offset_for_audio_onset(current, onset)
        if corrected is None:
            refused += 1
        elif corrected != current:
            aligned += 1
            by_key[key] = json.dumps(corrected, separators=(",", ":"))
    print(f"  audio onsets: {aligned} first wash(es) aligned, {refused} refused")
    rows = [(rid, sid, ay, segs) for (rid, sid, ay), segs in sorted(by_key.items())]
    return rows, onsets


def _complete_from_reference(segs, reference, n_words, same_clock):
    """Fill source holes from its file-clock witness, or use the witness whole."""
    if not segs:
        return reference
    present = {seg[0] for seg in segs}
    missing = set(range(1, n_words + 1)) - present
    if not missing:
        return [list(seg) for seg in segs]
    if not reference:
        return None
    if not same_clock:
        return [list(seg) for seg in reference]
    additions = [list(seg) for seg in reference if seg[0] in missing]
    if {seg[0] for seg in additions} != missing:
        return [list(seg) for seg in reference]
    return sorted([list(seg) for seg in segs] + additions, key=lambda seg: seg[1])


def project_onto_reference(segs, reference):
    """Affine-fit repeat topology into the monotonic file-clock window."""
    if not segs or not reference:
        return None
    source_start, source_end = segs[0][1], segs[-1][2]
    target_start, target_end = reference[0][1], reference[-1][2]
    source_span = source_end - source_start
    target_span = target_end - target_start
    if source_span <= 0 or target_span <= 0:
        return None

    def project(value):
        return target_start + (value - source_start) * target_span // source_span

    out = [
        [pos, project(start), project(end)] for pos, start, end in segs
    ]
    starts = [seg[1] for seg in out]
    if starts != sorted(set(starts)) or any(seg[2] <= seg[1] for seg in out):
        return None
    return out


def normalize_timing_row(
    segs, onset_ms=None, duration_ms=None, monotonic_fallback=False
):
    """Return one playable file-clock row, or ``None`` when it cannot be safe."""
    if not segs:
        return None
    row = [list(seg) for seg in sorted(segs, key=lambda seg: seg[1])]
    starts = [seg[1] for seg in row]
    if starts != sorted(set(starts)):
        return None
    if onset_ms is not None:
        corrected = offset_for_audio_onset(row, onset_ms)
        if corrected is None and monotonic_fallback:
            corrected = translate_segments(row, onset_ms - row[0][1])
        if corrected is None:
            return None
        row = corrected
    row = trim_to_next_start(row)
    if duration_ms:
        if any(seg[1] >= duration_ms for seg in row):
            return None
        row[-1][2] = min(row[-1][2], duration_ms)
    if any(seg[1] < 0 or seg[2] <= seg[1] for seg in row):
        return None
    return row


def finalize_timing_rows(
    timing_rows,
    word_counts,
    references,
    durations,
    onsets,
    file_clock_rows=None,
):
    """Complete, normalize, and physically validate every shippable timing row.

    A bad repeat-aware candidate falls back to complete quran-align timing.
    Only when neither candidate is playable is the row withheld, which makes
    the reader use honest whole-ayah highlighting.
    """
    file_clock_rows = file_clock_rows or set()
    current = {
        (rid, sid, ay): json.loads(segs) if isinstance(segs, str) else segs
        for rid, sid, ay, segs in timing_rows
    }
    out = []
    completed = 0
    projected = 0
    fallback = 0
    withheld = 0
    for key in sorted(current.keys() | references.keys()):
        rid, sid, ay = key
        n_words = word_counts.get((sid, ay))
        if n_words is None:
            continue
        source = current.get(key)
        reference = references.get(key)
        projection = (
            project_onto_reference(source, reference)
            if source is not None and reference is not None
            else None
        )
        candidate = _complete_from_reference(
            source,
            reference,
            n_words,
            key in file_clock_rows,
        )
        if (
            source is not None
            and key not in file_clock_rows
            and {seg[0] for seg in source} != set(range(1, n_words + 1))
            and projection is not None
        ):
            candidate = _complete_from_reference(
                projection, reference, n_words, True
            )
            projected += 1
        if candidate is not None and source is not None:
            completed += {seg[0] for seg in source} != set(range(1, n_words + 1))
        normalized = normalize_timing_row(
            candidate, onsets.get(key), durations.get(key)
        )
        if normalized is None and projection is not None:
            projected_candidate = _complete_from_reference(
                projection, reference, n_words, True
            )
            normalized = normalize_timing_row(
                projected_candidate, onsets.get(key), durations.get(key)
            )
            projected += normalized is not None
        if normalized is None or {seg[0] for seg in normalized} != set(
            range(1, n_words + 1)
        ):
            normalized = normalize_timing_row(
                reference,
                onsets.get(key),
                durations.get(key),
                monotonic_fallback=True,
            )
            fallback += normalized is not None
        if normalized is None or {seg[0] for seg in normalized} != set(
            range(1, n_words + 1)
        ):
            withheld += 1
            continue
        out.append((rid, sid, ay, json.dumps(normalized, separators=(",", ":"))))
    print(
        f"  timing finalizer: {completed} row(s) completed, "
        f"{projected} topology projection(s), "
        f"{fallback} monotonic fallback(s), {withheld} withheld"
    )
    shipped = {(rid, sid, ay) for rid, sid, ay, _ in out}
    return out, {key: onset for key, onset in onsets.items() if key in shipped}


def apply_timing_overrides(
    timing_rows, reciter_rows, word_counts, word_text, alignment_references=None
):
    """Apply every JSON file in tools/timing_overrides/ on top of the built
    timing rows, replacing or adding (reciter, surah, ayah) rows.

    The patch shape is exactly what the in-app Timings Lab exports (see
    docs/TIMINGS_LAB.md):

      {"schema": 1, "device": "...", "appVersion": "...",
       "edits": [
         {"reciterId": 1, "reciterSlug": "Alafasy_128kbps",
          "surahId": 2, "ayah": 14,
          "segments": [[pos, start_ms, end_ms], ...]}, ...]}

    Positions and suspicious adjacent duration/word-length ratios are
    validated; either fails the build rather than shipping a bad patch.
    Ear-verified pacing outliers may be listed in ``reviewedShortPositions``;
    independent-source conflicts use ``reviewedBoundaryPositions``.
    Slug mismatches warn but still apply — the reciterId is authoritative.
    """
    slug_by_id = {r[0]: r[1] for r in RECITERS}
    by_key = {(rid, sid, ay): segs for (rid, sid, ay, segs) in timing_rows}
    alignment_references = alignment_references or {}
    applied = 0
    files = sorted(OVERRIDES_DIR.glob("*.json")) if OVERRIDES_DIR.is_dir() else []
    if not files:
        return timing_rows, reciter_rows
    for path in files:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(f"  !! cannot parse override {path.name}: {e}", file=sys.stderr)
            sys.exit(1)
        for edit in payload.get("edits") or []:
            try:
                rid = int(edit["reciterId"])
                sid = int(edit["surahId"])
                ay = int(edit["ayah"])
            except (KeyError, TypeError, ValueError) as e:
                print(f"  !! override {path.name}: missing reciterId/surahId/ayah ({e})", file=sys.stderr)
                sys.exit(1)
            expected_slug = slug_by_id.get(rid)
            if expected_slug is None:
                print(f"  !! override {path.name}: unknown reciterId {rid}", file=sys.stderr)
                sys.exit(1)
            slug = edit.get("reciterSlug")
            if slug and slug != expected_slug:
                print(f"  !  override {path.name}: reciterSlug {slug!r} != {expected_slug!r} for id {rid}; applying by id")
            n_words = word_counts.get((sid, ay))
            if n_words is None:
                print(f"  !! override {path.name}: surah {sid} ayah {ay} not in corpus", file=sys.stderr)
                sys.exit(1)
            raw = edit.get("segments") or []
            if not raw:
                print(f"  !! override {path.name}: surah {sid} ayah {ay} has no segments", file=sys.stderr)
                sys.exit(1)
            segs = []
            for s in raw:
                if len(s) < 3:
                    print(f"  !! override {path.name}: segment {s} needs [pos, start, end]", file=sys.stderr)
                    sys.exit(1)
                pos, start, end = int(s[0]), int(s[1]), int(s[2])
                if pos < 1 or pos > n_words:
                    print(f"  !! override {path.name}: surah {sid} ayah {ay} position {pos} out of [1,{n_words}]", file=sys.stderr)
                    sys.exit(1)
                if start < 0 or end <= start:
                    print(f"  !! override {path.name}: surah {sid} ayah {ay} segment {s} has bad start/end", file=sys.stderr)
                    sys.exit(1)
                segs.append([pos, start, end])
            segs.sort(key=lambda s: s[1])
            reviewed = {int(pos) for pos in edit.get("reviewedShortPositions", [])}
            words = word_text[(sid, ay)]
            unreviewed = [
                outlier for outlier in suspicious_pacing(segs, words)
                if outlier[0][0] not in reviewed
            ]
            if unreviewed:
                for fast, fast_word, fast_units, slow, slow_word, slow_units in unreviewed:
                    fast_ms, slow_ms = fast[2] - fast[1], slow[2] - slow[1]
                    print(
                        f"  !! override {path.name}: surah {sid} ayah {ay} "
                        f"position {fast[0]} {fast_word} has {fast_ms} ms/{fast_units} letters "
                        f"beside position {slow[0]} {slow_word} with {slow_ms} ms/{slow_units} letters",
                        file=sys.stderr,
                    )
                print(
                    "     Re-listen and fix the boundary, or add the intentional "
                    "position to reviewedShortPositions after ear verification.",
                    file=sys.stderr,
                )
                sys.exit(1)
            key = (rid, sid, ay)
            sources = {}
            if key in by_key:
                sources["bundled"] = json.loads(by_key[key])
            if key in alignment_references:
                sources["quran-align"] = alignment_references[key]
            reviewed_boundaries = {
                int(pos) for pos in edit.get("reviewedBoundaryPositions", [])
            }
            conflicts = [
                conflict for conflict in boundary_conflicts(segs, sources)
                if conflict[0] not in reviewed_boundaries
            ]
            if conflicts:
                for pos, start, evidence in conflicts:
                    detail = ", ".join(
                        f"{name} {delta:+.0f} ms"
                        f" ({boundary_evidence(delta)}, "
                        f"weight {BOUNDARY_SOURCE_WEIGHTS.get(name, 1)})"
                        for name, delta in evidence.items()
                    )
                    print(
                        f"  !! override {path.name}: surah {sid} ayah {ay} "
                        f"boundary {pos - 1}->{pos} at {start} ms: {detail}",
                        file=sys.stderr,
                    )
                print(
                    "     Re-listen to each boundary; if intentional, add its "
                    "destination position to reviewedBoundaryPositions.",
                    file=sys.stderr,
                )
                sys.exit(1)
            by_key[key] = json.dumps(segs, separators=(",", ":"))
            applied += 1
            print(f"  override {path.name}: {expected_slug} surah {sid} ayah {ay} -> {len(segs)} segments")
    new_rows = [(rid, sid, ay, segs) for (rid, sid, ay), segs in sorted(by_key.items())]
    # A reciter with any timing row (DB or override) offers word highlighting.
    has = {rid for (rid, _sid, _ay, _segs) in new_rows}
    new_reciter_rows = [
        (r[0], r[1], r[2], r[3], 1 if r[0] in has else r[4]) for r in reciter_rows
    ]
    print(f"  overrides: {applied} ayah(s) across {len(files)} file(s)")
    return new_rows, new_reciter_rows


DDL = """
CREATE TABLE surahs (
  id INTEGER PRIMARY KEY,
  name_arabic TEXT NOT NULL,
  name_transliteration TEXT NOT NULL,
  name_translation TEXT NOT NULL,
  revelation_place TEXT NOT NULL,
  ayah_count INTEGER NOT NULL
);
CREATE TABLE ayahs (
  surah_id INTEGER NOT NULL,
  ayah_number INTEGER NOT NULL,
  text_uthmani TEXT NOT NULL,
  translation_en TEXT NOT NULL,
  page INTEGER NOT NULL,
  PRIMARY KEY (surah_id, ayah_number)
);
CREATE TABLE words (
  surah_id INTEGER NOT NULL,
  ayah_number INTEGER NOT NULL,
  position INTEGER NOT NULL,
  arabic TEXT NOT NULL,
  translation_en TEXT NOT NULL,
  transliteration TEXT NOT NULL,
  qcf_v2 TEXT NOT NULL,
  qcf_page INTEGER NOT NULL,
  qcf_line INTEGER NOT NULL,
  qcf_span_end INTEGER NOT NULL,
  PRIMARY KEY (surah_id, ayah_number, position)
);
CREATE TABLE reciters (
  id INTEGER PRIMARY KEY,
  slug TEXT NOT NULL,
  name TEXT NOT NULL,
  style TEXT NOT NULL,
  has_timings INTEGER NOT NULL
);
CREATE TABLE timings (
  reciter_id INTEGER NOT NULL,
  surah_id INTEGER NOT NULL,
  ayah_number INTEGER NOT NULL,
  segments TEXT NOT NULL,
  audio_onset_ms INTEGER NOT NULL,
  PRIMARY KEY (reciter_id, surah_id, ayah_number)
);
CREATE TABLE word_morphology (
  surah_id INTEGER NOT NULL,
  ayah_number INTEGER NOT NULL,
  position INTEGER NOT NULL,
  root TEXT NOT NULL,
  lemma TEXT NOT NULL,
  pos TEXT NOT NULL,
  features TEXT NOT NULL,
  PRIMARY KEY (surah_id, ayah_number, position)
);
CREATE TABLE roots (
  root TEXT PRIMARY KEY,
  occurrence_count INTEGER NOT NULL
);
CREATE TABLE root_occurrences (
  root TEXT NOT NULL,
  surah_id INTEGER NOT NULL,
  ayah_number INTEGER NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (root, surah_id, ayah_number, position)
);
"""


# Buckwalter extended → Arabic (letters used in QAC ROOT:/LEM: values).
_BW_TO_AR = {
    "'": "ء",
    "|": "آ",
    ">": "أ",
    "&": "ؤ",
    "<": "إ",
    "}": "ئ",
    "A": "ا",
    "b": "ب",
    "t": "ت",
    "v": "ث",
    "j": "ج",
    "H": "ح",
    "x": "خ",
    "d": "د",
    "*": "ذ",
    "r": "ر",
    "z": "ز",
    "s": "س",
    "$": "ش",
    "S": "ص",
    "D": "ض",
    "T": "ط",
    "Z": "ظ",
    "E": "ع",
    "g": "غ",
    "f": "ف",
    "q": "ق",
    "k": "ك",
    "l": "ل",
    "m": "م",
    "n": "ن",
    "h": "ه",
    "w": "و",
    "y": "ي",
    "Y": "ى",
    "p": "ة",
    "{": "ٱ",
    "`": "ٰ",
    "^": "ٓ",
    "@": "ۥ",
    "#": "ۦ",
    ":": "ۜ",
    '"': "۟",
    "[": "ۢ",
    ";": "ۣ",
    ",": "ۥ",
    ".": "ۥ",
    "!": "ۥ",
    "-": "-",
    "_": "ـ",
    "~": "ّ",
    "o": "ْ",
    "a": "َ",
    "u": "ُ",
    "i": "ِ",
    "F": "ً",
    "N": "ٌ",
    "K": "ٍ",
}


def buckwalter_to_arabic(text: str) -> str:
    """Decode a Buckwalter (extended) string to Arabic letters."""
    return "".join(_BW_TO_AR.get(ch, ch) for ch in text)


def _feature_map(features: str) -> dict[str, str]:
    out = {}
    for part in features.split("|"):
        if ":" in part:
            k, _, v = part.partition(":")
            out[k] = v
        elif part:
            out[part] = ""
    return out


def load_qac_morphology(path: Path, word_counts: dict) -> tuple[list, list, list]:
    """Collapse QAC sub-word segments onto our space-split word positions.

    Returns (word_morphology_rows, roots_rows, root_occurrence_rows).
    Positions that exist in our canon but not in QAC (the known 10 count
    mismatches) are simply omitted — the viewer handles a missing row.
    """
    # (s,a,w) -> list of (seg, tag, features)
    by_word: dict[tuple[int, int, int], list] = {}
    with path.open(encoding="utf-8", newline="") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("LOCATION"):
                continue
            parts = line.split("\t")
            if len(parts) < 4:
                continue
            loc, _form, tag, features = parts[0], parts[1], parts[2], parts[3]
            m = re.match(r"\((\d+):(\d+):(\d+):(\d+)\)", loc)
            if not m:
                continue
            s, a, w, seg = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
            by_word.setdefault((s, a, w), []).append((seg, tag, features))

    morph_rows = []
    occ_rows = []
    root_counts: dict[str, int] = {}
    for (s, a), n in word_counts.items():
        for pos in range(1, n + 1):
            segs = by_word.get((s, a, pos))
            if not segs:
                # QAC short on this ayah (known count mismatches) — omit the row.
                continue
            segs = sorted(segs, key=lambda x: x[0])
            # Prefer the STEM segment for root/lemma/POS; else first with ROOT.
            stem = next((x for x in segs if x[2].startswith("STEM") or "POS:" in x[2]), None)
            if stem is None:
                stem = next((x for x in segs if "ROOT:" in x[2]), segs[0])
            _seg, tag, features = stem
            fmap = _feature_map(features)
            root_bw = fmap.get("ROOT", "")
            lemma_bw = fmap.get("LEM", "")
            pos_tag = fmap.get("POS", tag)
            root_ar = buckwalter_to_arabic(root_bw) if root_bw else ""
            lemma_ar = buckwalter_to_arabic(lemma_bw) if lemma_bw else ""
            # Keep a compact leftover feature string (drop keys we already lifted).
            leftover = "|".join(
                p for p in features.split("|")
                if p and not p.startswith("POS:") and not p.startswith("LEM:")
                and not p.startswith("ROOT:") and p != "STEM"
            )
            morph_rows.append((s, a, pos, root_ar, lemma_ar, pos_tag, leftover))
            if root_ar:
                root_counts[root_ar] = root_counts.get(root_ar, 0) + 1
                occ_rows.append((root_ar, s, a, pos))

    # Ayahs where QAC word max != ours (same family of mismatches as WBW).
    qac_max = {}
    for (s, a, w) in by_word:
        qac_max[(s, a)] = max(qac_max.get((s, a), 0), w)
    mismatch_ayahs = [
        (s, a, word_counts[(s, a)], qac_max[(s, a)])
        for (s, a) in word_counts
        if (s, a) in qac_max and word_counts[(s, a)] != qac_max[(s, a)]
    ]
    roots_rows = [(r, c) for r, c in sorted(root_counts.items())]
    print(
        f"  morphology rows: {len(morph_rows)}; roots: {len(roots_rows)}; "
        f"occurrences: {len(occ_rows)}; word-count mismatches (clamped): {len(mismatch_ayahs)}"
    )
    for s, a, ours, qac in mismatch_ayahs:
        print(f"    surah {s} ayah {a}: text={ours} qac={qac}")
    return morph_rows, roots_rows, occ_rows


def write_morphology(db: sqlite3.Connection, morph_rows, roots_rows, occ_rows):
    db.executemany(
        "INSERT INTO word_morphology VALUES (?,?,?,?,?,?,?)",
        morph_rows,
    )
    db.executemany("INSERT INTO roots VALUES (?,?)", roots_rows)
    db.executemany(
        "INSERT INTO root_occurrences VALUES (?,?,?,?)",
        occ_rows,
    )
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_morph_ayah ON word_morphology(surah_id, ayah_number)"
    )
    db.execute(
        "CREATE INDEX IF NOT EXISTS idx_root_occ ON root_occurrences(root)"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-timings", action="store_true")
    args = ap.parse_args()

    print("[1/6] fetching text + metadata (quran-json)")
    qj = fetch(QURAN_JSON_TGZ, "quran-json.tgz")
    surahs, ayahs = load_text_and_meta(qj)
    assert len(surahs) == 114 and len(ayahs) == 6236, "unexpected corpus shape"

    print("[2/6] fetching word-by-word gloss")
    wbw_tgz = fetch(WBW_TGZ, "wbw.tgz")
    wbw, page_of = load_wbw(wbw_tgz)

    print("[3/6] fetching QCF V2 mushaf layout")
    qcf_v2 = load_qcf_v2_layout()
    print(f"  qcf v2 ayahs: {len(qcf_v2)}")

    print("[4/6] building words table")
    words = []
    gloss_mismatch = []
    for (s, a), (text, _tr) in sorted(ayahs.items()):
        arabic_words = text.split()
        glosses = wbw.get((s, a), [])
        if len(glosses) != len(arabic_words):
            gloss_mismatch.append((s, a, len(arabic_words), len(glosses)))
        try:
            qcf_aligned = align_qcf_words(arabic_words, qcf_v2.get((s, a), []), s, a)
        except ValueError as e:
            print(f"  !! {e}", file=sys.stderr)
            sys.exit(1)
        for i, w in enumerate(arabic_words):
            g, t = glosses[min(i, len(glosses) - 1)] if glosses else ("", "")
            qcf = qcf_aligned.get(i + 1)
            if qcf is None:
                qcf = ("", 0, 0, i + 1)
            words.append((s, a, i + 1, w, g, t, qcf[0], qcf[1], qcf[2], qcf[3]))
    print(f"  words: {len(words)}; gloss count mismatches (clamped): {len(gloss_mismatch)}")
    for m in gloss_mismatch:
        print(f"    surah {m[0]} ayah {m[1]}: text={m[2]} wbw={m[3]}")

    word_counts = {}
    word_text = {}
    for s, a, pos, arabic, *_ in words:
        word_counts[(s, a)] = max(word_counts.get((s, a), 0), pos)
        word_text.setdefault((s, a), {})[pos] = arabic

    print("[4b/6] fetching Quranic Arabic Corpus morphology")
    qac_path = fetch(QAC_MORPHOLOGY_URL, "quranic-corpus-morphology-0.4.txt")
    morph_rows, roots_rows, occ_rows = load_qac_morphology(qac_path, word_counts)

    timing_rows = []
    reciter_rows = []
    alignment_references = {}
    timing_clock_offsets = {}
    file_clock_rows = set()
    audio_durations = load_audio_durations()
    audio_onsets = load_audio_onsets()
    if args.skip_timings:
        print("[5/6] SKIPPING timings (--skip-timings)")
        reciter_rows = [(r[0], r[1], r[2], r[3], 0) for r in RECITERS]
    else:
        print("[5/6] fetching + normalizing word timings (quran-align)")
        zp = fetch(ALIGN_ZIP, "quran-align-data.zip")
        verify_source(zp, ALIGN_ZIP_SHA256, "quran-align release")
        for rid, slug, name, style in RECITERS:
            reciter_alignment = alignment_reference(
                zp, rid, slug, word_counts, word_text
            )
            alignment_references.update(reciter_alignment)
            qdc_id = QDC_REPEAT_RECITERS.get(rid)
            if qdc_id is not None:
                # Repeat-aware timings from quran.com instead of quran-align.
                print(f"  {slug}: repeat-aware timings from quran.com (qdc {qdc_id})")
                data = load_qdc_timings(qdc_id)
                stats = {
                    "zero_len": 0, "clamped": 0, "repeats": 0, "missing": 0,
                    "opening_shift": 0,
                    "merged_splits": 0, "dropped_strays": 0,
                    "noncontiguous_orphans": 0, "gap_phantoms": 0,
                    "clock_rebased": 0,
                    "clock_abstained": 0, "quran_align_fallback": 0,
                }

                def adjust_qdc(key, n):
                    cleaned = adjust_qdc_segments(
                        data.get(key),
                        n,
                        stats,
                    )
                    row_key = (rid, key[0], key[1])
                    reference = reciter_alignment.get(row_key)
                    duration = audio_durations.get(row_key)
                    rebased, offset = rebase_qdc_clock(cleaned, reference, duration)
                    if offset is not None:
                        file_clock_rows.add(row_key)
                        if offset:
                            stats["clock_rebased"] += 1
                            timing_clock_offsets[row_key] = offset
                        return rebased
                    if cleaned and reference:
                        stats["clock_abstained"] += 1
                    if (
                        reference
                        and not fits_audio(cleaned, duration)
                        and fits_audio(reference, duration)
                    ):
                        # No translation reconciles this row with the recording,
                        # but quran-align was aligned against the very file we
                        # stream. Trade this ayah's repeat topology for a row
                        # that actually tracks the voice.
                        witness = sanitize_timing_row(trim_to_next_start(reference))
                        if witness:
                            stats["quran_align_fallback"] += 1
                            file_clock_rows.add(row_key)
                            return witness
                    return rebased

                covered = ingest_reciter_timings(
                    rid, word_counts, timing_rows, stats,
                    adjust_qdc,
                )
                print(
                    f"  {slug}: ayahs covered {covered}/6236, "
                    f"repeat spans {stats['repeats']}, clamped {stats['clamped']}, "
                    f"zero-len {stats['zero_len']}, missing {stats['missing']}, "
                    f"opening-shift {stats.get('opening_shift', 0)}, "
                    f"split-words merged {stats['merged_splits']}, "
                    f"stray mislabels dropped {stats['dropped_strays']}, "
                    f"noncontiguous orphans {stats['noncontiguous_orphans']}, "
                    f"gap phantoms {stats.get('gap_phantoms', 0)}, "
                    f"clock-rebased {stats['clock_rebased']}, "
                    f"clock-abstained {stats['clock_abstained']}, "
                    f"quran-align fallback {stats['quran_align_fallback']}"
                )
            else:
                if not reciter_alignment:
                    print(f"  !! no timing file matched slug {slug}")
                    reciter_rows.append((rid, slug, name, style, 0))
                    continue
                stats = {"basmalah_shift": 0, "clamped": 0, "missing": 0}

                def adjust_aligned(key, n):
                    segs = reciter_alignment.get((rid, key[0], key[1]))
                    if segs:
                        file_clock_rows.add((rid, key[0], key[1]))
                    return segs

                covered = ingest_reciter_timings(
                    rid, word_counts, timing_rows, stats,
                    adjust_aligned,
                )
                print(
                    f"  {slug}: ayahs covered {covered}/6236, "
                    f"basmalah-shifted {stats['basmalah_shift']}, "
                    f"clamped segs {stats['clamped']}, missing {stats['missing']}"
                )
            if covered < COVERAGE_THRESHOLD:
                print(f"  !! coverage below threshold for {slug}", file=sys.stderr)
                sys.exit(1)
            reciter_rows.append((rid, slug, name, style, 1))

    print("[typed corrections] applying irreducible timing verdicts")
    timing_rows = apply_timing_corrections(timing_rows)

    print("[repairs] applying tools/timing_repairs/*.json")
    timing_rows = apply_timing_repairs(
        timing_rows, word_counts, timing_clock_offsets, audio_durations
    )

    print("[overrides] applying tools/timing_overrides/*.json")
    timing_rows, reciter_rows = apply_timing_overrides(
        timing_rows, reciter_rows, word_counts, word_text, alignment_references
    )

    if audio_durations:
        timing_rows, refitted = refit_displaced_rows(
            timing_rows, audio_durations, audio_onsets, file_clock_rows
        )
        if refitted:
            print(f"[audit] {len(refitted)} displaced row(s) re-anchored on the voice")
            for rid, sid, ay in refitted:
                print(f"    re-anchored: reciter {rid} {sid}:{ay}")
    print("[timing finalizer] completing coverage and enforcing file physics")
    timing_rows, audio_onsets = finalize_timing_rows(
        timing_rows,
        word_counts,
        alignment_references,
        audio_durations,
        audio_onsets,
        file_clock_rows,
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    if OUT.exists():
        OUT.unlink()
    db = sqlite3.connect(OUT)
    db.executescript(DDL)
    db.executemany("INSERT INTO surahs VALUES (?,?,?,?,?,?)", surahs)
    db.executemany(
        "INSERT INTO ayahs VALUES (?,?,?,?,?)",
        [(s, a, t, tr, page_of.get((s, a), 0)) for (s, a), (t, tr) in sorted(ayahs.items())],
    )
    db.executemany("INSERT INTO words VALUES (?,?,?,?,?,?,?,?,?,?)", words)
    db.executemany("INSERT INTO reciters VALUES (?,?,?,?,?)", reciter_rows)
    db.executemany(
        "INSERT INTO timings VALUES (?,?,?,?,?)",
        [
            (rid, sid, ay, segments, audio_onsets.get((rid, sid, ay), 0))
            for rid, sid, ay, segments in timing_rows
        ],
    )
    write_morphology(db, morph_rows, roots_rows, occ_rows)
    db.execute("CREATE INDEX idx_words_ayah ON words(surah_id, ayah_number)")
    db.execute("CREATE INDEX idx_timings ON timings(reciter_id, surah_id)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    size_mb = OUT.stat().st_size / 1e6
    print(f"OK -> {OUT} ({size_mb:.1f} MB, {len(timing_rows)} timing rows, {len(morph_rows)} morph rows)")


if __name__ == "__main__":
    main()
