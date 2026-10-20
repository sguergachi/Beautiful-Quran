"""Record a take of the real Android app, picture AND sound, on an emulator.

The sound is the emulator's own playback, captured by scrcpy through
Android's AudioPlaybackCapture (`--audio-source=playback`) in the same file
as the screen, so the ink and the voice share one device clock. Nothing is
re-laid from EveryAyah afterwards.

A take is recorded at 1x. Smoothness therefore depends on the emulator:
give it cores (see `boot()`): 6 cores render the reader at 55-57 fps at
1080x2400 on this host, 3 cores (the pool default) only ~44.

Takes live in CACHE/takes/<key>/ (spec.take_key): raw.mkv as recorded,
clip.mp4 (60 fps CFR, starts `lead` s before the recitation), audio.wav
(same timeline) and meta.json.
"""
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time

from spec import CACHE, RECITERS, REPO, take_dir, take_needed

sys.path.insert(0, os.path.join(REPO, "tools", "key_art"))
import android_take as at  # noqa: E402  (adb helpers: nodes/find/tap/demo/open_reader)

CAPTURE_PORT = 5650
SCRCPY_VERSION = "v5.0.1"
SCRCPY_DIR = os.path.join(CACHE, "scrcpy")
LEAD = 1.0          # seconds of the opened, still page before the recitation starts


# ---------------------------------------------------------------- emulator

def serial():
    return os.environ.get("ANDROID_SERIAL") or f"emulator-{CAPTURE_PORT}"


def adb(*a, check=True, timeout=60):
    return subprocess.run(["adb", "-s", serial(), *a], capture_output=True, text=True, check=check,
                          timeout=timeout).stdout


def is_up():
    try:
        return adb("shell", "getprop", "sys.boot_completed", check=False, timeout=10).strip() == "1"
    except subprocess.TimeoutExpired:
        return False


def boot(cores=6, log=print, port=None):
    """Boot a capture emulator (see emus.boot); returns its serial."""
    import emus
    return emus.boot(port or CAPTURE_PORT, cores, log if hasattr(log, "stage") else None)


def install(apk, log=print):
    """Install, compile and first-launch the app (see emus.prepare)."""
    import emus
    return emus.prepare(serial(), apk, log if hasattr(log, "stage") else None)


def scrcpy():
    exe = os.path.join(SCRCPY_DIR, f"scrcpy-linux-x86_64-{SCRCPY_VERSION}", "scrcpy")
    if not os.path.exists(exe):
        os.makedirs(SCRCPY_DIR, exist_ok=True)
        tgz = os.path.join(SCRCPY_DIR, "scrcpy.tgz")
        url = (f"https://github.com/Genymobile/scrcpy/releases/download/{SCRCPY_VERSION}/"
               f"scrcpy-linux-x86_64-{SCRCPY_VERSION}.tar.gz")
        subprocess.run(["curl", "-sfL", "-o", tgz, url], check=True)
        subprocess.run(["tar", "xzf", tgz, "-C", SCRCPY_DIR], check=True)
    return exe


# ---------------------------------------------------------------- app driving

def _env():
    os.environ["ANDROID_SERIAL"] = serial()


def labels():
    return [(t, d) for t, d, _ in at.nodes()]


def wait_for(label, tries=8, exact=True):
    for _ in range(tries):
        if at.find(label, exact):
            return True
        time.sleep(1.0)
    return False


def open_settings():
    """Reader top bar > Settings (disabled while playing, so pause first)."""
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
    at.open_reader(1, 1)
    time.sleep(2.5)
    if not wait_for("Settings"):
        raise SystemExit("reader Settings button not found")
    at.tap_label("Settings")


def back(n=1):
    for _ in range(n):
        adb("shell", "input", "keyevent", "BACK")
        time.sleep(0.9)


def scroll_to(label, max_swipes=6):
    w, h = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    for _ in range(max_swipes):
        xy = at.find(label)
        if xy and xy[1] < h * 0.9:
            return xy
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.35)), "400")
        time.sleep(0.8)
    return at.find(label)


def scroll_top(anchor, max_swipes=6, exact=True):
    """Screens keep their scroll position between visits: swipe back up to `anchor`."""
    w, h = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    for _ in range(max_swipes):
        if at.find(anchor, exact):
            return True
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.45)), str(w // 2), str(int(h * 0.95)), "300")
        time.sleep(0.7)
    return bool(at.find(anchor, exact))


def set_reciter(key, log=print):
    name = RECITERS[key]["name"]
    open_settings()
    scroll_top("RECITER")
    xy = scroll_to(name)
    if not xy:
        raise SystemExit(f"reciter row not found: {name}")
    at.tap(xy)
    time.sleep(1.2)
    back(1)
    log(f"reciter: {name}")


def customize(theme, layout, view, text_size, log=print):
    open_settings()
    at.tap_label("Customize")
    if not scroll_top("THEME"):
        raise SystemExit("Customize: THEME row not found")
    at.tap_label(theme)
    at.tap_label(layout)
    # "Arabic"/"English" also label the VERSE NUMBERS and PAGE NUMBERS rows
    # further down: take the option nearest below the VIEW header
    ns = at.nodes()
    head = next((b for t, d, b in ns if t == "VIEW"), None)
    if head is None:
        raise SystemExit("Customize: VIEW row not on screen")
    opts = sorted((b for t, d, b in ns if t == view and b[1] > head[1]), key=lambda b: b[1])
    if not opts:
        raise SystemExit(f"Customize: view option {view} not found")
    b = opts[0]
    at.tap(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)); time.sleep(1.0)
    if layout == "Scroll":
        small = sorted((b for t, d, b in at.nodes() if t == "A"), key=lambda b: b[0])
        if len(small) >= 2:
            y = (small[0][1] + small[0][3]) // 2
            x0, x1 = small[0][2] + 10, small[-1][0] - 10
            target = x1 if text_size == "max" else x0 + int((x1 - x0) * 0.28)
            adb("shell", "input", "swipe", str((x0 + x1) // 2), str(y), str(target), str(y), "600")
            time.sleep(0.8)
    back(2)
    log(f"customize: {theme} / {layout} / {view} / text {text_size}")


def prep():
    """Gesture nav, a clean demo status bar (9:00, full battery), media volume up."""
    _env()
    adb("shell", "cmd", "overlay", "enable", "com.android.internal.systemui.navbar.gestural", check=False)
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    at.demo("enter"); at.demo("clock", "-e", "hhmm", "0900")
    at.demo("battery", "-e", "level", "100", "-e", "plugged", "false")
    at.demo("network", "-e", "mobile", "hide")
    at.demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
    at.demo("notifications", "-e", "visible", "false")
    # the emulator's status-bar clock renders in a broken face ("9·ՈՈ"): hide it
    adb("shell", "settings", "put", "secure", "icon_blacklist", "clock")
    adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "15", check=False)
    for k in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
        adb("shell", "settings", "put", "global", k, "1")


# ---------------------------------------------------------------- recording

def audio_onset(path, after=0.0, thresh_db=-42.0):
    """First moment the recording's audio rises above `thresh_db` (the recitation starting)."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vn", "-ac", "1", "-ar", "8000", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    import numpy as np
    a = np.frombuffer(raw, np.float32)
    hop = 80                                   # 10 ms
    n = len(a) // hop
    rms = np.sqrt((a[:n * hop].reshape(n, hop) ** 2).mean(1) + 1e-12)
    db = 20 * np.log10(rms)
    start = int(after * 100)
    for i in range(start, n - 3):
        if db[i] > thresh_db and db[i + 1] > thresh_db and db[i + 2] > thresh_db:
            # walk back to where it left the noise floor
            j = i
            while j > start and db[j - 1] > thresh_db - 18:
                j -= 1
            return j / 100.0
    raise SystemExit(f"no recitation audio found in {path}")


def first_video_pts(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1",
                          "-show_entries", "frame=pts_time", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True).stdout.split()
    return float(out[0].strip(",")) if out else 0.0


def cadence(path, t0=0.0, t1=1e9):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time",
                          "-of", "csv=p=0", path], capture_output=True, text=True, check=True).stdout.split()
    ts = [float(x.strip(",")) for x in out if x.strip(",")]
    ts = [t for t in ts if t0 <= t <= t1]
    intervals = [b - a for a, b in zip(ts, ts[1:]) if 0 < b - a < 0.2]
    import numpy as np
    import statistics
    return {"capture_fps": (len(ts) - 1) / (ts[-1] - ts[0]) if len(ts) > 2 else 0.0,
            "active_fps": 1 / statistics.median(intervals) if intervals else 0.0,
            "frame_p90_ms": float(np.percentile(intervals, 90) * 1000) if intervals else 0.0}


def playing():
    """Playing, or buffering towards it (Hani's 192 kbps files take a moment):
    a second tap while buffering would pause it again."""
    for _ in range(3):
        try:
            out = adb("shell", "dumpsys", "media_session", check=False, timeout=10)
            return any(f"state=PlaybackState {{state={k}" in out for k in ("PLAYING", "BUFFERING", "CONNECTING"))
        except Exception:
            time.sleep(0.5)
    return False


ARABIC_DIGITS = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")


def _is_arabic_word(t):
    return any("\u0621" <= ch <= "\u064a" for ch in t) and "﴿" not in t


def first_word_xy(ayah):
    """Centre of the opened verse's first word: the first Arabic word after
    the previous verse's end marker (﴿N﴾, Arabic-Indic digits)."""
    return word_xy(ayah, 1)


def word_xy(ayah, position=1):
    """A visible Arabic word slot in the opened Scroll verse."""
    for attempt in range(3):
        ns = at.nodes()
        want = "﴿" + str(ayah - 1).translate(ARABIC_DIGITS) + "﴾"
        idx = next((i for i, (t, d, b) in enumerate(ns) if t.replace("\u2060", "") == want), None)
        # Do not silently select another verse when the requested marker is
        # outside the viewport. Playback can fall back to the exact deep link.
        cands = ns[idx + 1:] if idx is not None else ns if ayah == 1 else []
        seen = 0
        for t, d, b in cands:
            if "﴿" in t:
                break
            if _is_arabic_word(t) and not t.startswith("سُورَةُ"):
                seen += 1
                if seen == position:
                    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        # verse marker not on screen: scroll down and retry
        if attempt < 2:
            adb("shell", "input", "swipe", "540", "1800", "540", "600", "400", check=False)
            time.sleep(0.8)
    return None


def start_playback(surah, ayah=1, layout="Scroll", log=print):
    """Start the opened verse without the reload a `?play=true` deep link
    causes (it crossfades and re-scrolls the page as the recitation begins).

    Scroll, ayah 1: the bar's Play, which plays the chapter from the top with
    the basmalah lead-in. Scroll, other verses: tap the verse's first word
    (ReaderViewModel.playFromAyahWord), since after a deep link the bar's
    Play still points at ayah 1. Mushaf: the leaf's Play would start the
    first chapter the leaf carries (95:1's leaf opens with the end of 94),
    and its words are not separate nodes, so it uses the deep link; the
    page is the same before and after its reload.

    Several strategies are tried in order; each is given time to register
    (Hani's 192 kbps files buffer before PLAYING reports). A deep-link
    fallback reloads the verse with play=true if tapping never registers.
    """
    attempts = [
        ("tap-first-word", lambda: _tap_play(surah, ayah, layout)),
        ("deep-link", lambda: at.open_reader(surah, ayah, play=True)),
        ("tap-play-bar", lambda: _tap_play_bar()),
    ]
    for name, action in attempts:
        for try_no in range(2):
            try:
                action()
            except Exception as e:
                log(f"start_playback: {name} attempt {try_no + 1} raised {e}")
            for _ in range(24):
                time.sleep(0.25)
                if playing():
                    log(f"playback started via {name}")
                    return
            log(f"start_playback: {name} attempt {try_no + 1} did not register")
    raise SystemExit("playback did not start")


def _tap_play(surah, ayah, layout):
    if layout == "Mushaf":
        at.open_reader(surah, ayah, play=True)
        return
    if ayah > 1:
        xy = first_word_xy(ayah)
        if xy is None:
            # first_word_xy already retries with scrolling internally
            xy = first_word_xy(ayah)
        if xy:
            at.tap(xy)
            return
    b = at.find("Play")
    if b:
        at.tap(b)


def _tap_play_bar():
    b = at.find("Play")
    if b:
        at.tap(b)


def record(s, log=print, force=False):
    """Record (or reuse) the take a spec needs. Returns the take directory.

    `log` may be a report.Report: then each step is a named stage with
    overall progress, which the editor shows as the capture runs.
    """
    from report import Report
    from recording import Sequence
    from spec import opening_steps, opening_estimate
    rep = log if isinstance(log, Report) else None
    stage = (lambda k, m, p: rep.stage(k, m, p)) if rep else (lambda k, m, p: log(m))
    _env()
    a = s["app"]
    d = take_dir(s)
    meta_p = os.path.join(d, "meta.json")
    need = take_needed(s) + 0.5            # the furthest point the edit reads
    if not force and os.path.exists(meta_p):
        meta = json.load(open(meta_p))
        if meta["length"] >= need:
            log(f"take exists: {d}")
            return d
    stage("check", f"Checking {serial()}", 0.0)
    if not is_up():
        raise SystemExit(f"no emulator at {serial()}: boot one from the Emulators panel (or `studio.py emu boot`)")
    if "package:" not in adb("shell", "pm", "path", "com.beautifulquran", check=False):
        raise SystemExit(f"the app is not installed on {serial()}: prepare it from the Emulators panel")
    os.makedirs(d, exist_ok=True)
    secs = max(int(a["seconds"]), int(need - LEAD + 1))
    stage("prepare", "Preparing the device (status bar, volume)", 0.03)
    prep()
    stage("reciter", f"Choosing the reciter: {RECITERS[a['reciter']]['name']}", 0.08)
    set_reciter(a["reciter"], log)
    stage("customize", f"Setting {a['theme']} · {a['layout']} · {a['view']}", 0.14)
    customize(a["theme"], a["layout"], a["view"], a["text_size"], log)
    # open the verse still, let it settle, then roll and start the recitation
    stage("open", f"Opening {a['surah']}:{a['ayah']}", 0.22)
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
    at.open_reader(int(a["surah"]), int(a["ayah"]))
    time.sleep(3.0)
    sequence = Sequence(s, log=log)
    sequence.prepare()
    raw = os.path.join(d, "raw.mkv")
    if os.path.exists(raw):
        os.remove(raw)
    # scrcpy gets a generous limit; the take is ended a fixed time after the
    # recitation actually starts (Hani's first file can buffer for seconds)
    limit = int(math.ceil(secs + opening_estimate(s) + 300))
    env = dict(os.environ, ADB=shutil.which("adb"))
    p = subprocess.Popen([scrcpy(), "--no-window", "--no-playback", f"--record={raw}",
                          "--audio-source=playback", "--audio-codec=flac", "--video-codec=h264",
                          "--video-bit-rate=40M", "--max-fps=60", f"--time-limit={limit}"],
                         env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    recording_start = time.monotonic()
    time.sleep(2.0)
    marks = {"dwell": a["intro"]["dwell"], "scene_only": not a["intro"]["play"]}
    def mark(key):
        marks[key] = time.monotonic() - recording_start
    try:
        if opening_steps(s): mark("intro")
        sequence.run(mark, stage)
        stage("record", f"{'Starting playback' if a['intro']['play'] else 'Holding the last screen'} ({secs} s to record)", 0.25)
        if a["intro"]["play"]:
            mark("play")
            if a["repeat"] != "off":
                # The repeat picker has already cued the requested range.
                # Tapping verse 1 would re-add the chapter's basmalah preface
                # and clear that range, so resume its paused queue instead.
                adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PLAY")
                if not playing(): raise RuntimeError("Repeated playback did not start")
            else:
                start_playback(int(a["surah"]), int(a["ayah"]), a["layout"], log=log)
    except BaseException as e:
        try:
            adb("shell", "screencap", "-p", "/sdcard/fail.png", check=False)
            adb("pull", "/sdcard/fail.png", os.path.join(d, "fail.png"), check=False)
        except Exception:
            pass
        p.terminate(); p.wait()
        adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
        log(f"capture failed: {e}")
        raise
    t0 = time.monotonic()
    want = secs + 1.5 if a['intro']['play'] else max(0, secs - (t0 - recording_start - marks.get('intro', 0)))
    try:
        while p.poll() is None:
            el = time.monotonic() - t0
            if el >= want:
                # A background-launched studio may inherit SIGINT ignored.
                # SDL preserves that disposition; TERM still closes the recorder.
                p.terminate()
                try:
                    p.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    p.kill()
                    p.wait()
                    raise RuntimeError("scrcpy did not close the recording within 20 seconds")
                break
            if rep:
                rep.progress(0.25 + 0.6 * min(1.0, el / want), f"recording {el:.0f} / {want:.0f} s")
            time.sleep(0.5)
    finally:
        if p.poll() is None:
            p.kill()
            p.wait()
    out = p.stdout.read() if p.stdout else ""
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
    if p.returncode != 0 or not os.path.exists(raw) or os.path.getsize(raw) < 10000:
        raise SystemExit("scrcpy recording failed:\n" + out[-2000:])
    stage("conform", "Finding the recitation's start and conforming to 60 fps", 0.88)
    conform(d, log, marks)
    if rep:
        rep.progress(1.0, "take ready")
    return d


def conform(d, log=print, marks=None):
    """raw.mkv -> clip.mp4 (60 fps CFR) + audio.wav on one timeline.

    `marks` (recording seconds): "play", when playback was asked for (the
    recitation is searched for after it); "intro", when the opening began;
    "dwell", the still seconds to keep before the recitation. With an intro
    the take starts with it, otherwise `dwell` s before the recitation.
    `lead` in meta.json is where the recitation starts on the take's clock.
    """
    marks = marks or {}
    raw = os.path.join(d, "raw.mkv")
    scene_only = marks.get("scene_only", False)
    onset = None if scene_only else audio_onset(raw, after=max(0.5, marks["play"] - 1.0) if marks.get("play") is not None else 1.0)
    v0 = first_video_pts(raw)
    if scene_only:
        start = max(0.0, marks.get("intro", 1.0) - 0.15)
    elif marks.get("intro") is not None:
        start = max(0.0, min(onset - 0.5, marks["intro"] - 0.15))
    else:
        start = max(0.0, onset - float(marks.get("dwell", LEAD)))
    rates = cadence(raw, onset or start, (onset or start) + 20)
    clip, wav = os.path.join(d, "clip.mp4"), os.path.join(d, "audio.wav")
    # scrcpy writes frames only when the screen changes: fps=60 repeats the
    # last one, it never invents in-between frames
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-i", raw, "-map", "0:v",
                    "-vf", "fps=60,format=yuv420p", "-c:v", "libx264", "-preset", "slow", "-crf", "12",
                    "-g", "30", "-an", clip], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-i", raw, "-map", "0:a",
                    "-ar", "48000", "-ac", "2", wav], check=True)
    length = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                   clip], capture_output=True, text=True, check=True).stdout.strip())
    w, h = map(int, subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                    "stream=width,height", "-of", "csv=p=0", clip],
                                   capture_output=True, text=True, check=True).stdout.strip().split(","))
    meta = {"onset_raw": onset, "video_first_pts": v0, "lead": round(onset - start, 3) if onset is not None else length, "length": length, "marks": marks, **{k: round(v, 1) for k, v in rates.items()},
            "width": w, "height": h, "recorded": time.strftime("%Y-%m-%d %H:%M")}
    json.dump(meta, open(os.path.join(d, "meta.json"), "w"), indent=1)
    log(f"take ready: {d} ({length:.1f}s, active cadence {rates['active_fps']:.1f} fps, p90 frame interval {rates['frame_p90_ms']:.0f} ms)")
    return meta
