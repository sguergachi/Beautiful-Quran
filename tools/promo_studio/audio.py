"""The recitation, exactly as the app plays it.

The app streams EveryAyah files (Models.kt `Reciter.audioUrl`) as one gapless
playlist and, when a chapter opens at ayah 1, prepends 1:1 as the basmalah
(PlayerController.playSurah; never for 1 Al-Fatihah, whose ayah 1 *is* the
basmalah, nor 9 At-Tawbah). Each file is decoded on its own so ffmpeg honours
its LAME gapless header, then the PCM is joined: the result has the same
clock as the app's media timeline, which is what the ink follows.
"""
import json
import os
import subprocess
import urllib.request
import wave

from spec import CACHE, RECITERS

AYAH_COUNTS = None


def ayah_count(surah):
    global AYAH_COUNTS
    if AYAH_COUNTS is None:
        import sqlite3
        from spec import REPO
        db = sqlite3.connect(os.path.join(REPO, "data", "quran.db"))
        cols = [r[1] for r in db.execute("pragma table_info(surahs)")]
        col = "ayah_count" if "ayah_count" in cols else next(c for c in cols if "ayah" in c and "count" in c)
        AYAH_COUNTS = dict(db.execute(f"select id, {col} from surahs"))
        db.close()
    return AYAH_COUNTS[surah]


def fetch(slug, surah, ayah):
    d = os.path.join(CACHE, "audio", slug)
    os.makedirs(d, exist_ok=True)
    name = f"{surah:03d}{ayah:03d}"
    mp3, wav = os.path.join(d, name + ".mp3"), os.path.join(d, name + ".wav")
    if not os.path.exists(mp3):
        req = urllib.request.Request(f"https://everyayah.com/data/{slug}/{name}.mp3",
                                     headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req) as r, open(mp3 + ".part", "wb") as f:
            f.write(r.read())
        os.replace(mp3 + ".part", mp3)
    if not os.path.exists(wav):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", mp3, "-ar", "48000", "-ac", "2", wav], check=True)
    return wav


def duration(path):
    # Cached playlist audio is PCM WAV: its header already has the exact
    # sample count. Spawning ffprobe per ayah stalled every timeline load.
    if str(path).lower().endswith(".wav"):
        with wave.open(str(path), "rb") as wav:
            return wav.getnframes() / wav.getframerate()
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                                capture_output=True, text=True, check=True).stdout.strip())


def playlist(reciter, surah, ayah, seconds, repeat="off", repeat_to=None, end_at=None):
    """[(label, wav, start_s)] covering at least `seconds` of recitation.
    With the app's verse repeat the ayah (or ayah..repeat_to) loops."""
    slug = RECITERS[reciter]["slug"]
    items, t = [], 0.0
    if ayah == 1 and surah not in (1, 9) and repeat == "off":
        w = fetch(slug, 1, 1)
        items.append(("basmalah", w, t)); t += duration(w)
    n = ayah_count(surah)
    if not 1 <= ayah <= n or repeat not in ("off", "ayah", "range"): raise ValueError("invalid playlist range")
    if repeat == "range" and (repeat_to is None or not ayah <= int(repeat_to) <= n): raise ValueError("invalid repeat endpoint")
    last = ayah if repeat == "ayah" else min(n, int(repeat_to)) if repeat == "range" else n
    a = ayah
    while t < seconds:
        if a > last:
            if repeat == "off":
                break
            a = ayah
        w = fetch(slug, surah, a)
        span = duration(w)
        if a == last and repeat != "off" and end_at is not None:
            span = min(span, max(0.001, end_at))
        items.append((f"{surah}:{a}", w, t)); t += span
        a += 1
    return items, t


def recitation(reciter, surah, ayah, seconds, lead, out):
    """WAV of the recitation starting `lead` s in (the take's clip t=0 is `lead` s before playback)."""
    items, total = playlist(reciter, surah, ayah, seconds)
    lst = out + ".txt"
    with open(lst, "w") as f:
        for _, w, _ in items:
            f.write(f"file '{w}'\n")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst,
                    "-af", f"adelay={int(lead * 1000)}:all=1", "-ar", "48000", "-ac", "2", out], check=True)
    os.remove(lst)
    with open(out + ".json", "w") as f:
        json.dump({"lead": lead, "items": [(l, s + lead) for l, _, s in items], "total": total + lead}, f, indent=1)
    return out
