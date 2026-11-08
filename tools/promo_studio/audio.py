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


# ------------------------------------------------------------ dropout repair
#
# The emulator's audio tap sometimes misses a buffer under load, and the take's
# sound then falls to exact digital silence for ~30-40 ms in the middle of the
# recitation: an audible click at each edge, "scratchy" on playback. Real sound
# never holds an exact zero for that long, so the gaps are found, and filled
# from the voice's own waveform (below). Takes are healed in place once, keeping
# the original beside as audio.orig.wav.

HEAL_VERSION = 2
HEAL_MIN_RUN = 16           # samples (0.33 ms) of exact silence that count as a dropout
HEAL_LEVEL = 0.02           # RMS (-34 dBFS) of the 10 ms right either side: sound stopped mid-sentence, not a pause
HEAL_SHORT = 0.004          # a run under 3 ms can only be a glitch: it needs only this much sound around it
HEAL_BALANCE = 6.0          # ...and within this ratio of each other: a quiet lead-in then a loud start is not a dropout
HEAL_MAX_FILL = 0.12        # seconds; a longer hole is a missing part, not a glitch: faded, not invented
HEAL_FADE = 0.004           # seconds faded at the edges of a hole left unfilled


def find_dropouts(x, rate=48000):
    """[(start, end)] sample ranges of exact silence cut into sound, x as float (n, channels)."""
    import numpy as np
    env = np.abs(x).max(axis=1)
    zero = (env < 1.5 / 32768).astype(np.int8)
    edge = np.diff(np.concatenate([[0], zero, [0]]))
    starts, ends = np.where(edge == 1)[0], np.where(edge == -1)[0]
    w = rate // 100                                   # 10 ms

    def rms(a, b):
        seg = env[max(0, a):max(0, b)]
        return float(np.sqrt(np.mean(seg ** 2))) if len(seg) > w // 2 else 0.0

    out = []
    for s, e in zip(starts, ends):
        if e - s < HEAL_MIN_RUN:
            continue
        before, after = rms(s - w, s), rms(e, e + w)
        level = HEAL_SHORT if e - s < rate * 3 // 1000 else HEAL_LEVEL
        if min(before, after) >= level and max(before, after) <= HEAL_BALANCE * min(before, after):
            out.append((int(s), int(e)))
    return out


def _period(m, lo, hi, side):
    """The voice's pitch period in samples from the 40 ms of mono signal `m`
    before (side=-1) or after (side=+1) a gap, by normalised autocorrelation;
    None when nothing repeats (noise: any tail will do)."""
    import numpy as np
    seg = m[lo:hi] - float(np.mean(m[lo:hi]))
    n = len(seg)
    if n < 400:
        return None
    best, best_lag = 0.0, None
    for lag in range(96, min(960, n // 2)):          # 50-500 Hz
        a, b = seg[lag:], seg[:-lag]
        d = float(np.sqrt(np.dot(a, a) * np.dot(b, b)))
        c = float(np.dot(a, b)) / d if d > 0 else 0.0
        if c > best:
            best, best_lag = c, lag
    return best_lag if best >= 0.3 else None


def conceal(x, gaps, rate=48000):
    """Fill each short dropout from the sound around it: the last pitch period
    before the gap repeated forward, the first one after it repeated backward,
    cross-faded across the gap so both edges join smoothly. Longer holes just
    get short fades. Returns (healed copy, [(start s, ms, filled)])."""
    import numpy as np
    y, m, report = x.copy(), x.mean(axis=1), []
    w = rate * 40 // 1000
    for s, e in gaps:
        n = e - s
        report.append((s / rate, n * 1000 / rate, n <= HEAL_MAX_FILL * rate))
        if n > HEAL_MAX_FILL * rate:
            f = max(2, int(HEAL_FADE * rate))
            if s > f: y[s - f:s] *= np.linspace(1, 0, f, dtype=np.float32)[:, None]
            if e + f < len(y): y[e:e + f] *= np.linspace(0, 1, f, dtype=np.float32)[:, None]
            continue
        pf = _period(m, max(0, s - w), s, -1) or min(480, max(32, s))
        pb = _period(m, e, min(len(m), e + w), 1) or min(480, max(32, len(m) - e))
        k = np.arange(n)
        fwd = x[np.clip(s - pf + (k % pf), 0, len(x) - 1)]                  # last period, tiled forward
        bwd = x[np.clip(e + ((s + k - e) % pb), 0, len(x) - 1)]             # first period after, tiled backward
        t = (k + 0.5) / n
        wgt = (t * t * (3 - 2 * t)).astype(np.float32)[:, None]            # smoothstep
        y[s:e] = (1 - wgt) * fwd + wgt * bwd
    return y, report


def heal_wav(path, log=None):
    """Repair dropouts in a take's audio.wav in place (the original is kept as
    audio.orig.wav). Idempotent: a sidecar records the version and the file it
    healed, so an unchanged, healed take costs one stat. Returns the sidecar's
    dict ({"version", "dropouts": [[t, ms, filled]]}) or None if there is no wav."""
    if not os.path.isfile(path):
        return None
    d = os.path.dirname(path)
    side = os.path.join(d, "audio.heal.json")
    st = os.stat(path)
    try:
        with open(side) as f:
            rec = json.load(f)
        if rec.get("version") == HEAL_VERSION and rec.get("size") == st.st_size and rec.get("mtime_ns") == st.st_mtime_ns:
            return rec
    except (OSError, ValueError):
        pass
    import numpy as np
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            return None
        ch, rate, params = w.getnchannels(), w.getframerate(), w.getparams()
        pcm = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, ch)
    x = pcm.astype(np.float32) / 32768
    gaps = find_dropouts(x, rate)
    report = []
    if gaps:
        y, report = conceal(x, gaps, rate)
        orig = os.path.join(d, "audio.orig.wav")
        if not os.path.exists(orig):
            os.link(path, orig)                   # the original stays; the healed file replaces the name
        tmp = f"{path}.{os.getpid()}.heal"
        with wave.open(tmp, "wb") as out:
            out.setparams(params)
            out.writeframes(np.clip(np.round(y * 32768), -32768, 32767).astype(np.int16).tobytes())
        os.replace(tmp, path)
        st = os.stat(path)
        if log:
            log(f"audio: filled {sum(1 for r in report if r[2])} dropouts"
                + (f", faded {sum(1 for r in report if not r[2])} long ones" if any(not r[2] for r in report) else ""))
    rec = {"version": HEAL_VERSION, "size": st.st_size, "mtime_ns": st.st_mtime_ns,
           "dropouts": [[round(t, 3), round(ms, 1), bool(f)] for t, ms, f in report]}
    tmp = f"{side}.{os.getpid()}.tmp"
    with open(tmp, "w") as f:
        json.dump(rec, f)
    os.replace(tmp, side)
    return rec
