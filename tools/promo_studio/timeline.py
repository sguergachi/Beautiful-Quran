"""Where things happen in a spec's video: verses, repeats, words.

Uses the same data the app shows: EveryAyah file lengths for the playlist
clock (audio.playlist) and the runtime word timings the app downloads from
the Worker (`/api/timings/<id>`, cached here). A segment is a repeat when
its word position is not past the furthest word already heard in that
verse, the app's own rule (docs/REPEAT_HIGHLIGHTING.md).

Times are in TAKE seconds (take t = recitation time + lead, the still
second the take keeps before the recitation starts); the edit maps them to
video time (anim.video_times), live in the editor.
"""
import json
import math
import os
import urllib.request

import audio
from spec import CACHE, RECITERS

BASE = os.environ.get("BQ_TIMINGS_BASE", "https://beautiful-quran.sguergachi.workers.dev")
_ROWS = {}


def plan_range(s, start, end, lead=1.0):
    """Whole-verse edit bounds on the take clock, including basmalah/lead.

    Keep the existing recording origin when possible. An earlier start needs
    a new take; shift identifies the old origin on that new take's clock so
    timeline edge actions can preserve the cuts between its ends.
    """
    a = s["app"]
    if a.get("repeat", "off") != "off" or not a.get("intro", {}).get("play", True):
        raise ValueError("Choose the repeat range under Recording sequence; trim this take in seconds.")
    count = audio.ayah_count(int(a["surah"]))
    if any(isinstance(v, bool) or not isinstance(v, int) for v in (start, end)) or not 1 <= start <= end <= count:
        raise ValueError(f"choose verses within 1..{count}, with start before stop")
    old_anchor = int(a["ayah"])
    anchor = min(start, old_anchor)
    slug = RECITERS[a["reciter"]]["slug"]
    t, verses = float(lead), []
    if anchor == 1 and a["surah"] not in (1, 9):
        t += audio.duration(audio.fetch(slug, 1, 1))
        verses.append({"label": "basmalah", "t0": round(lead, 3), "t1": round(t, 3)})
    for ayah in range(anchor, end + 1):
        t0 = t
        t += audio.duration(audio.fetch(slug, int(a["surah"]), ayah))
        verses.append({"label": f"{a['surah']}:{ayah}", "t0": round(t0, 3), "t1": round(t, 3)})
    by_label = {v["label"]: v for v in verses}
    old = by_label.get(f"{a['surah']}:{old_anchor}")
    # A replace-range ending before the old origin needs no retained pieces.
    shift = round(old["t0"] - lead, 3) if anchor < old_anchor and old else 0
    return {"start": start, "end": end, "anchor": anchor, "shift": shift,
            "in": 0.0 if start == anchor else by_label[f"{a['surah']}:{start}"]["t0"],
            "out": round(t, 3), "seconds": max(5, math.ceil(t - lead + 0.5)), "verses": verses}


def rows(reciter):
    rid = RECITERS[reciter]["id"]
    if rid in _ROWS:
        return _ROWS[rid]
    d = os.path.join(CACHE, "timings")
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, f"{rid}.json")
    if not os.path.exists(p):
        req = urllib.request.Request(f"{BASE}/api/timings/{rid}",
                                     headers={"Accept": "application/json", "User-Agent": "Beautiful-Quran/0.10"})
        with urllib.request.urlopen(req) as r:
            open(p, "wb").write(r.read())
    data = json.load(open(p))
    _ROWS[rid] = {(r[0], r[1]): r[2] for r in data["rows"]}
    return _ROWS[rid]


def repeats_in(segs):
    out, furthest = [], 0
    for pos, st, en in segs:
        if pos <= furthest:
            out.append((pos, st / 1000, en / 1000))
        furthest = max(furthest, pos)
    return out


def build(s, lead=1.0):
    """Verse and repeat spans for a spec, in take seconds."""
    a = s["app"]
    if not a.get("intro", {}).get("play", True): return {"verses": [], "repeats": [], "words": []}
    horizon = max(0, max(float(p["out"]) for p in s["edit"]) - lead) + 2
    rs = rows(a["reciter"])
    last = a["ayah"] if a.get("repeat") == "ayah" else a.get("repeat_to")
    tail = rs.get((a["surah"], last), [])
    end_at = tail[-1][2] / 1000 if tail else None
    items, _ = audio.playlist(a["reciter"], int(a["surah"]), int(a["ayah"]), max(horizon, float(a["seconds"])),
                              a.get("repeat", "off"), a.get("repeat_to"), end_at)
    verses, reps, words = [], [], []
    for label, wav, r0 in items:
        dur = audio.duration(wav)
        if a.get("repeat", "off") != "off" and label == f"{a['surah']}:{last}" and end_at is not None: dur = min(dur, end_at)
        t0 = r0 + lead
        verses.append({"label": label, "t0": round(t0, 3), "t1": round(t0 + dur, 3)})
        if label == "basmalah":
            key = (1, 1)
        else:
            sur, ay = map(int, label.split(":"))
            key = (sur, ay)
        segs = rs.get(key, [])
        for pos, st, en in segs:
            words.append({"verse": label, "pos": pos, "t0": round(t0 + st / 1000, 3), "t1": round(t0 + en / 1000, 3)})
        if label != "basmalah":
            for pos, st, en in repeats_in(segs):
                reps.append({"verse": label, "pos": pos, "t0": round(t0 + st, 3), "t1": round(t0 + en, 3)})
    return {"verses": verses, "repeats": reps, "words": words}


if __name__ == "__main__":
    import sys
    from anim import video_times
    from spec import load
    s = load(sys.argv[1])
    tl = build(s)
    print("video seconds (take seconds)")
    for kind, items in (("", tl["verses"]), ("repeat ", tl["repeats"])):
        for v in items:
            for a, b in video_times(s, v["t0"], v["t1"]):
                what = v["label"] if not kind else f"{v['verse']} word {v['pos']}"
                print(f"{a:7.2f}-{b:6.2f}  {kind}{what}   ({v['t0']:.2f}-{v['t1']:.2f})")
