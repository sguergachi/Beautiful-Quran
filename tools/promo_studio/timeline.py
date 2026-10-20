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
import os
import urllib.request

import audio
from spec import CACHE, RECITERS

BASE = os.environ.get("BQ_TIMINGS_BASE", "https://beautiful-quran.sguergachi.workers.dev")
_ROWS = {}


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
    horizon = max(float(p["out"]) for p in s["edit"]) + 2
    items, _ = audio.playlist(a["reciter"], int(a["surah"]), int(a["ayah"]), max(horizon, float(a["seconds"])))
    rs = rows(a["reciter"])
    verses, reps, words = [], [], []
    for label, wav, r0 in items:
        dur = audio.duration(wav)
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
