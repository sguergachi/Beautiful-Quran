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

HEAL_VERSION = 7
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


def _read_pcm(path):
    import numpy as np
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("not 16-bit PCM")
        ch, rate = w.getnchannels(), w.getframerate()
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, ch).astype(np.float32) / 32768, rate, w.getparams()


def clean_recitation(app, seconds):
    """The recitation exactly as the app plays it (EveryAyah files, gapless),
    from its own start, as float (n, 2) at 48 kHz; None when the take's
    settings are not a plain one-pass playlist (repeats are timed by the app)."""
    import numpy as np
    if app.get("repeat", "off") != "off" or app.get("reciter") not in RECITERS:
        return None
    items, _ = playlist(app["reciter"], int(app["surah"]), int(app["ayah"]), seconds)
    parts = []
    for _, wav, _ in items:
        x, rate, _p = _read_pcm(wav)
        if rate != 48000:
            raise ValueError("playlist audio is not 48 kHz")
        parts.append(x if x.shape[1] == 2 else np.repeat(x, 2, axis=1))
    return np.concatenate(parts) if parts else None


XFADE = 0.006               # seconds: how long the fill takes to join the sound after the gap


def conceal(x, gaps, rate=48000, donors=None):
    """Fill each short dropout without moving anything in time. The fill runs on
    from the sound before the gap (`donors[i]`, the voice's real continuation
    when known, else the last pitch period repeated) and only in its last few
    ms joins the sound after the gap (the first period after it, repeated
    backward), so the level never pinches in the middle of the gap. Longer holes
    just get short fades. Returns (healed copy, [(start s, ms, filled)])."""
    import numpy as np
    y, m, report = x.copy(), x.mean(axis=1), []
    w = rate * 40 // 1000
    for i, (s, e) in enumerate(gaps):
        n = e - s
        report.append((s / rate, n * 1000 / rate, n <= HEAL_MAX_FILL * rate))
        if n > HEAL_MAX_FILL * rate:
            f = max(2, int(HEAL_FADE * rate))
            if s > f: y[s - f:s] *= np.linspace(1, 0, f, dtype=np.float32)[:, None]
            if e + f < len(y): y[e:e + f] *= np.linspace(0, 1, f, dtype=np.float32)[:, None]
            continue
        k = np.arange(n)
        donor = donors[i] if donors and donors[i] is not None else None
        if donor is None:
            pf = _period(m, max(0, s - w), s, -1) or min(480, max(32, s))
            donor = x[np.clip(s - pf + (k % pf), 0, len(x) - 1)]                  # last period, tiled forward
        pb = _period(m, e, min(len(m), e + w), 1) or min(480, max(32, len(m) - e))
        bwd = x[np.clip(e + ((s + k - e) % pb), 0, len(x) - 1)]                   # first period after, tiled backward
        xf = max(1, min(int(XFADE * rate), n // 3))
        t = np.clip((k - (n - xf) + 0.5) / xf, 0, 1)
        wgt = (t * t * (3 - 2 * t)).astype(np.float32)[:, None]                  # 0 until the last xf samples, then smoothstep to 1
        y[s:e] = (1 - wgt) * donor + wgt * bwd
    return y, report


LAG_WINDOW = 0.8            # seconds compared at a time when aligning the take to the clean recitation
LAG_MIN_CORR = 0.995        # a window only counts when the take really is that recitation
LAG_DRIFT_MS = 8.0          # how far the take may stray from its own starting offset before it is put back on the app's clock


def recitation_lags(take, ref, lead, rate=48000, first=1.0, step=1.0):
    """[(take time s, ms the take runs ahead of the recitation on the app's clock `lead` (+) or behind (-), gain)]
    for every window of `take` that really is the clean recitation `ref`."""
    import numpy as np
    from numpy.fft import irfft, rfft
    m, r = take.mean(axis=1), ref.mean(axis=1)
    w, pad = int(LAG_WINDOW * rate), int(0.6 * rate)
    n = 1 << int(np.ceil(np.log2(3 * w + 2 * pad)))
    out = []
    for t in np.arange(lead + first, len(m) / rate - LAG_WINDOW - 0.2, step):
        a = m[int(t * rate):int(t * rate) + w]
        r0 = int((t - lead) * rate)
        if r0 - pad < 0 or r0 + w + pad > len(r) or float(np.sqrt(np.mean(a ** 2))) < 0.02:
            continue
        seg = r[r0 - pad:r0 + w + pad]
        cc = irfft(rfft(seg, n) * np.conj(rfft(a, n)), n)[:len(seg) - w + 1]
        k = int(cc.argmax())
        b = seg[k:k + w]
        if float(np.dot(a, b) / np.sqrt(np.dot(a, a) * np.dot(b, b) + 1e-12)) >= LAG_MIN_CORR:
            out.append((float(t), (k - pad) * 1000.0 / rate, float(np.dot(a, b) / np.dot(b, b))))
    return out


ANCHOR_RUN = 2              # windows in a row that must agree on the take's starting offset


def _anchor(lags):
    """(offset ms, gain) the take began with, before any slip, or None.

    A drop only ever pushes the recorded sound ahead of the app (the lag grows),
    so the take's own offset is the earliest stretch that holds steady, and it is
    the lowest lag of the opening windows. On a busy host the emulator can drop
    a chunk every couple of seconds (1.1% on a 2K take), so the opening is not
    steady for long: a steady first pair is enough, and anything that is not
    the lowest of the opening is refused (no anchor is better than a wrong one)."""
    import numpy as np
    head = lags[:8]
    for i in range(len(head) - ANCHOR_RUN + 1):
        run = [l for _, l, _ in head[i:i + ANCHOR_RUN]]
        if max(run) - min(run) <= LAG_DRIFT_MS:
            base = float(np.median(run))
            if min(l for _, l, _ in head) < base - LAG_DRIFT_MS:
                return None                                # something earlier sat lower: not the take's start
            return base, float(np.median([g for _, _, g in head[i:i + ANCHOR_RUN]]))
    return None


DEAD_MIN_S = 0.25           # dead sound shorter than this is left to the dropout fill


def dead_stretches(take, ref, lead, base_ms, rate=48000):
    """[(take s, ms)] where the recitation was being played but the take holds
    digital silence. The emulator's sound path can die mid-take and stay dead
    (the app goes on reciting, its words still light, its player still
    playing); a dropout finder only sees silence with sound after it, so a dead
    tail went unnoticed. Aligned through the take's starting offset `base_ms`."""
    import numpy as np
    at = int(round((lead - base_ms / 1000.0) * rate))           # take sample = recitation sample + at
    blk = rate // 10
    m, r = np.abs(take).max(axis=1), np.abs(ref).mean(axis=1)
    out, run = [], None
    for k in range(max(0, at), len(m) - blk, blk):
        j = k - at
        if j < 0 or j + blk > len(r):
            continue
        voiced = float(np.sqrt(np.mean(r[j:j + blk] ** 2))) > 0.01
        silent = float(m[k:k + blk].max()) < 1.5 / 32768
        if voiced and silent:
            run = [k, k + blk] if run is None else [run[0], k + blk]
        elif run is not None and not silent:
            out.append(run); run = None
    if run is not None:
        out.append(run)
    return [(round(a / rate, 3), round((b - a) * 1000 / rate)) for a, b in out if b - a >= DEAD_MIN_S * rate]


def _on_the_apps_clock(take, ref, lead, rate=48000):
    """The take's sound put back on the app's clock, or None when it never left it.
    The emulator hands the recorder fewer audio samples than real time passes
    (it drops ~22 ms chunks: every ~20 s on a quiet host, every couple of
    seconds on a busy one), so the recorded sound slips
    ahead of the app's own clock, and so of the picture, and each drop is a click. The recitation
    it was recording is known exactly (the files the app plays), so the sound is
    rebuilt from them at the offset the take began with, before any slip: sync
    stays what the recording started with and never drifts. Returns (sound, info)."""
    import numpy as np
    lags = recitation_lags(take, ref, lead, rate)
    if len(lags) < 6:
        return None
    found = _anchor(lags)
    if found is None:
        return None                                        # not steady at the start: nothing to anchor to
    base, gain = found
    off = [abs(l - base) for _, l, _ in lags]
    dead = dead_stretches(take, ref, lead, base, rate)
    if sum(1 for o in off if o > LAG_DRIFT_MS) < 3 and not dead:
        return None                                        # it stayed on the app's clock, all of it heard: leave the recording alone
    at = int(round((lead - base / 1000.0) * rate))
    out = np.zeros_like(take)
    n = min(len(ref), len(out) - at) if at >= 0 else min(len(ref) + at, len(out))
    if n <= 0:
        return None
    src0, dst0 = max(0, -at), max(0, at)
    out[dst0:dst0 + n] = gain * ref[src0:src0 + n, :out.shape[1]]
    return out, {"offset_ms": round(base, 1), "drift_ms": round(max(l for _, l, _ in lags) - base, 1), "windows": len(lags), "gain": round(gain, 3),
                 "dead": dead}


def _gap_donors(take, ref, gaps, lead, rate=48000):
    """For each gap, the clean recitation's real continuation at the take's own
    local timing (found just before the gap), or None where it cannot be matched.
    Only ever used to fill the gap itself: no sample outside a gap is replaced."""
    import numpy as np
    from numpy.fft import irfft, rfft
    m, r = take.mean(axis=1), ref.mean(axis=1)
    w, pad = int(0.15 * rate), int(0.4 * rate)
    n = 1 << int(np.ceil(np.log2(3 * w + 2 * pad)))
    out = []
    for s, e in gaps:
        a = m[max(0, s - w - 480):max(0, s - 480)]
        if len(a) < w or e - s > HEAL_MAX_FILL * rate or float(np.sqrt(np.mean(a ** 2))) < 0.02:
            out.append(None); continue
        r0 = (s - w - 480) - int(lead * rate)
        if r0 - pad < 0 or r0 + w + pad > len(r) - (e - s) - 480:
            out.append(None); continue
        seg = r[r0 - pad:r0 + w + pad]
        cc = irfft(rfft(seg, n) * np.conj(rfft(a, n)), n)[:len(seg) - w + 1]
        k = int(cc.argmax()); b = seg[k:k + w]
        corr = float(np.dot(a, b) / np.sqrt(np.dot(a, a) * np.dot(b, b) + 1e-12))
        if corr < 0.93:                                          # 0.15 s of a noisy fricative can sit a little lower
            out.append(None); continue
        g = float(np.dot(a, b) / np.dot(b, b))
        start = r0 - pad + k + w + 480                          # where the voice stands at the gap's first sample
        d = g * ref[start:start + (e - s)]
        out.append(d[:, :take.shape[1]] if d.shape[1] >= take.shape[1] else np.repeat(d[:, :1], take.shape[1], axis=1))
    return out


def _mute_intro(y, meta, rate):
    """Silence the quiet ticks Android plays for the recorder's own taps before
    the recitation starts, when that stretch holds nothing louder. Nothing moves:
    samples are only zeroed, ending 0.3 s before the recitation with a short fade."""
    import numpy as np
    lead = meta.get("lead")
    if lead is None or lead < 1.0 or lead >= len(y) / rate or (meta.get("marks") or {}).get("scene_only"):
        return 0
    end = int((lead - 0.3) * rate)
    if end < rate // 2:
        return 0
    loud = max(float(np.sqrt(np.mean(y[i:i + rate // 10] ** 2))) for i in range(0, end, rate // 10))
    if loud > 0.02:
        return 0                                                # there is real sound in the intro: leave it
    f = rate // 50
    y[:end - f] = 0
    y[end - f:end] *= np.linspace(1, 0, f, dtype=np.float32)[:, None]
    return end / rate


def heal_wav(path, log=None):
    """Make a take's audio.wav free of the capture's glitches, in place, without
    moving any sound in time (the original is kept as audio.orig.wav and is
    always the source). Dropouts are filled (see conceal), from the clean
    recitation's own continuation where it can be matched; the quiet ticks of
    the recorder's taps before the recitation are silenced. Idempotent: a
    sidecar records the version and the file it wrote, so an unchanged, healed
    take costs one stat. Returns the sidecar's dict or None without a wav."""
    if not os.path.isfile(path):
        return None
    d = os.path.dirname(path)
    side, orig = os.path.join(d, "audio.heal.json"), os.path.join(d, "audio.orig.wav")
    st = os.stat(path)
    use_orig = False
    try:
        with open(side) as f:
            rec = json.load(f)
        if rec.get("size") != st.st_size or rec.get("mtime_ns") != st.st_mtime_ns:
            if os.path.exists(orig):                # the file is not the one we wrote: a new take's sound,
                os.remove(orig)                     # and an earlier take's backup must never stand in for it
        elif rec.get("version") == HEAL_VERSION:
            return rec
        else:
            use_orig = os.path.exists(orig)         # healed by an older version: redo it from the original
    except (OSError, ValueError):
        pass
    import numpy as np
    x, rate, params = _read_pcm(orig if use_orig else path)
    gaps = find_dropouts(x, rate)
    try:
        with open(os.path.join(d, "meta.json")) as f:
            meta = json.load(f)
    except (OSError, ValueError):
        meta = {}
    donors, matched, ref, rebuilt = None, 0, None, None
    if meta.get("app") and meta.get("lead") is not None:
        try:                                    # the recitation as the app played it, where it can be had
            ref = clean_recitation(meta["app"], len(x) / rate + 3)
            if ref is not None:
                rebuilt = _on_the_apps_clock(x, ref, float(meta["lead"]), rate)
                if rebuilt is None and gaps:
                    donors = _gap_donors(x, ref, gaps, float(meta["lead"]), rate)
                    matched = sum(1 for dn in donors if dn is not None)
        except Exception as e:                  # offline, an unfetched ayah: fill from the voice around the gaps instead
            if log:
                log(f"audio: clean recitation unavailable ({str(e)[:80]}); filling from the voice around the gaps")
    if rebuilt is not None:
        y, info = rebuilt
        report = []                             # nothing left over: every gap and every slip is gone
        if log:
            dead = "".join(f", its sound went dead at {a:.1f} s for {ms / 1000:.1f} s" for a, ms in info.get("dead", []))
            log(f"audio: rebuilt from the recitation on the app's clock (it had slipped {info['drift_ms']:+.0f} ms{dead}; offset {info['offset_ms']:+.0f} ms kept)")
    else:
        y, report = conceal(x, gaps, rate, donors) if gaps else (x.copy(), [])
        info = {}
    muted = _mute_intro(y, meta, rate) if rebuilt is None else 0
    if gaps and rebuilt is None and log:
        log(f"audio: filled {sum(1 for r in report if r[2])} dropouts ({matched} from the recitation's own continuation)"
            + (f", faded {sum(1 for r in report if not r[2])} long ones" if any(not r[2] for r in report) else "")
            + (f", silenced the recorder's ticks before {muted:.1f} s" if muted else ""))
    if gaps or muted or rebuilt is not None:
        if not os.path.exists(orig):
            os.link(path, orig)                   # the original stays; the new file replaces the name
        tmp = f"{path}.{os.getpid()}.heal"
        with wave.open(tmp, "wb") as out:
            out.setparams(params)
            out.writeframes(np.clip(np.round(y * 32768), -32768, 32767).astype(np.int16).tobytes())
        os.replace(tmp, path)
        st = os.stat(path)
    rec = {"version": HEAL_VERSION, "size": st.st_size, "mtime_ns": st.st_mtime_ns, "muted_before_s": round(muted, 2),
           "from_recitation": matched, "app_clock": info, "dropouts": [[round(t, 3), round(ms, 1), bool(f)] for t, ms, f in report]}
    tmp = f"{side}.{os.getpid()}.tmp"
    with open(tmp, "w") as f:
        json.dump(rec, f)
    os.replace(tmp, side)
    return rec
