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
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                                capture_output=True, text=True, check=True).stdout.strip())


def playlist(reciter, surah, ayah, seconds):
    """[(label, wav, start_s)] covering at least `seconds` of recitation."""
    slug = RECITERS[reciter]["slug"]
    items, t = [], 0.0
    if ayah == 1 and surah not in (1, 9):
        w = fetch(slug, 1, 1)
        items.append(("basmalah", w, t)); t += duration(w)
    a = ayah
    n = ayah_count(surah)
    while t < seconds and a <= n:
        w = fetch(slug, surah, a)
        items.append((f"{surah}:{a}", w, t)); t += duration(w)
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
