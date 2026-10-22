"""Record a take of the real Android app, picture AND sound, on an emulator.

The picture and PCM sound come from the emulator's authenticated host API.
Frames and samples keep the same emulator host clock, and NVIDIA hardware
encodes the full native framebuffer. Nothing is re-laid from EveryAyah.

A take is recorded at 1x, at 1080×2400 or native 1440×3200. Smoothness still
depends on the emulator; give it six cores and record one take at a time.

Takes live in CACHE/takes/<key>/ (spec.take_key): raw.mkv as recorded,
clip.mp4 (60 fps CFR, starts `lead` s before the recitation), audio.wav
(same timeline) and meta.json.
"""
import json
import math
import os
import re
import shlex
import subprocess
import sys
import time

from spec import CACHE, RECITERS, REPO, take_dir, take_needed

sys.path.insert(0, os.path.join(REPO, "tools", "key_art"))
import android_take as at  # noqa: E402  (adb helpers: nodes/find/tap/demo/open_reader)

CAPTURE_PORT = 5650
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


def centered_cutout(path, bounds=False):
    """Keep the SDK Pixel profile's shape, centering its absolute 1080p path.

    Pixel 9's resource is anchored at x=540 with @left. On a native QHD
    framebuffer that places the camera off-center; Android also accepts a
    center-relative path, preserving the profile's radius and status insets.
    """
    if '@left' not in path: return path
    radius = re.search(r'\ba\s+([\d.]+),', path)
    width = re.search(r'\bh\s+([\d.]+)', path)
    if bounds and width:
        x = -float(width[1]) / 2
    elif not bounds and radius:
        x = float(radius[1])
    else:
        raise ValueError('Unrecognized Pixel display cutout path')
    return re.sub(r'^(\s*m\s+)[-\d.]+', lambda m: m[1] + f'{x:g}', path).replace('@left', '').strip()


def prep():
    """Match the Pixel display shape; clean 9:41 status bar and full media volume."""
    _env()
    overlays = adb("shell", "cmd", "overlay", "list")
    if 'com.android.internal.emulation.pixel_9' in overlays:
        for prefix in ('com.android.internal', 'com.android.systemui'):
            for model in re.findall(r'\[x\] (' + re.escape(prefix) + r'\.emulation\.pixel_[\w]+)', overlays):
                adb('shell', 'cmd', 'overlay', 'disable', model)
            adb('shell', 'cmd', 'overlay', 'enable', prefix + '.emulation.pixel_9')
        for resource, bounds in (('config_mainBuiltInDisplayCutout', False), ('config_mainBuiltInDisplayCutoutRectApproximation', True)):
            path = adb('shell', 'cmd', 'overlay', 'lookup', 'android', 'android:string/' + resource).strip()
            name = 'bq_capture_' + ('bounds' if bounds else 'cutout')
            adb('shell', 'su', '0', 'cmd', 'overlay', 'fabricate', '--target', 'android', '--name', name,
                'android:string/' + resource, '0x03', shlex.quote(centered_cutout(path, bounds)))
            adb('shell', 'cmd', 'overlay', 'enable', 'com.android.shell:' + name)
    adb("shell", "cmd", "overlay", "enable", "com.android.internal.systemui.navbar.gestural", check=False)
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    # API 35 no longer enables demo mode from the `enter` broadcast alone.
    # An asynchronous `exit` broadcast can also undo a following enable.
    adb('shell', 'settings', 'put', 'global', 'sysui_tuner_demo_on', '1')
    time.sleep(.5)
    at.demo('clock', '-e', 'hhmm', '0941')
    at.demo("battery", "-e", "level", "100", "-e", "plugged", "false")
    at.demo("network", "-e", "sims", "0", "-e", "nosim", "hide", "-e", "mobile", "hide",
            "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
    at.demo("notifications", "-e", "visible", "false")
    # Host capture retains the full status-bar layer, including the clock.
    adb("shell", "settings", "delete", "secure", "icon_blacklist")
    # `cmd media_session volume --set` reports success but does nothing on this
    # image. Hardware key events change the real mixer; verify before recording.
    adb('shell', 'input', 'keyevent', *(['KEYCODE_VOLUME_UP'] * 15))
    volume = adb('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get')
    if 'volume is 15 in range' not in volume:
        raise RuntimeError('Could not set the capture emulator media volume to 15: ' + volume.strip())
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


# ---------------------------------------------------------------- going to a verse on camera

def screen_px():
    return tuple(map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1]))


def px_per_dp():
    found = re.findall(r"density: (\d+)", adb("shell", "wm", "density"))
    return int(found[-1]) / 160 if found else 2.625      # the override, when set, is listed last


def verse_markers():
    """Verse-end markers on screen: [(ayah, centre y)] (﴿N﴾ ends ayah N)."""
    out = []
    for t, d, b in at.nodes():
        m = re.search(r"﴿([٠-٩]+)﴾", t.replace("\u2060", ""))   # word joiners sit between the digits
        if m:
            out.append((int(m.group(1).translate(str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789"))), (b[1] + b[3]) // 2))
    return out


def _swipe_by(dy, speed):
    """Scroll the page by dy px (positive = forward) with one drag at `speed` px/ms."""
    w, h = screen_px()
    y0 = int(h * 0.8) if dy > 0 else int(h * 0.2)
    adb("shell", "input", "swipe", str(w // 2), str(y0), str(w // 2), str(y0 - dy), str(max(250, int(abs(dy) / speed))))


SCROLL_GLIDE = 1.4          # px/ms: an unhurried reader's swipe; the list coasts ~7% beyond it
SCROLL_SETTLE = 0.6         # px/ms: the final placement drags without coasting


def scroll_to_verse(ayah, log=print):
    """Scroll the Scroll reader to `ayah` the way a reader would.

    From the verse ends in view it estimates the way to go, glides most of it
    in back-to-back swipes (no pauses to look), then places the verse with
    slow drags: the previous verse's end marker settles in the upper quarter."""
    w, h = screen_px()
    goal, reach = int(h * 0.24), int(h * 0.6)
    if ayah == 1:
        return scroll_top("بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ", max_swipes=12, exact=False)
    blind = 0
    for _ in range(16):
        marks = sorted(verse_markers())
        prev = next((y for n, y in marks if n == ayah - 1), None)
        if prev is not None:
            dy = prev - goal
            if abs(dy) < h * 0.05:
                return True
            _swipe_by(max(-reach, min(reach, dy)), SCROLL_SETTLE)
            time.sleep(0.5)
            continue
        if not marks:
            blind += 1
            if blind > 3:                             # no verse ends in sight: not a Scroll reader
                break
            _swipe_by(reach // 2, SCROLL_GLIDE); time.sleep(0.4)
            continue
        blind = 0
        (n0, y0), (n1, y1) = marks[0], marks[-1]
        per = (y1 - y0) / (n1 - n0) if n1 > n0 else h * 0.3     # px a verse takes on this page
        if n1 < ayah - 1:
            way = (ayah - 1 - n1) * per + (y1 - goal)
        else:                                          # already past it: back up
            way = -((n0 - (ayah - 1)) * per + (goal - y0))
        way *= 0.85 / 1.07                             # stop short of it; the coast covers some
        while abs(way) > h * 0.1:
            step = max(-reach, min(reach, way))
            _swipe_by(int(step), SCROLL_GLIDE)
            way -= step
            time.sleep(0.12)
        time.sleep(0.5)
    log(f"scroll_to_verse: {ayah} not reached")
    return False


RAIL_TICK_DP = 14            # AyahSelectorRail: one ayah per 14 dp of drag
RAIL_DECAY = 1.85 * 4.2      # exponentialDecay(frictionMultiplier = 1.85): coasts velocity / 7.77 ayahs


def reading_ayah():
    """The verse at the page's reading line (a fifth of the way down): the one
    after the last verse end above that line, else the first verse whose end
    is in view (a chapter's heading pushes verse 1's end below the line)."""
    w, h = screen_px()
    marks = sorted(verse_markers(), key=lambda m: m[1])
    above = [n for n, y in marks if y < h * 0.2]
    return above[-1] + 1 if above else (marks[0][0] if marks else None)


def rail_to_verse(ayah, current, log=print):
    """Scrub the reader's ayah rail (left edge, Scroll layout) to `ayah`.

    A vertical drag on the collapsed dashes opens the wheel and scrubs it;
    dragging up moves forward. On release the wheel coasts (compensated
    here), settles, and commits after its 1.5 s grace. Each pass is one drag
    from the middle of the edge; a long way, or an off-by-one, takes
    another pass from where the page landed."""
    w, h = screen_px()
    tick = RAIL_TICK_DP * px_per_dp()
    x, y = int(22 * px_per_dp()), h // 2              # inside the 44 dp strip, clear of the Back edge
    most = int(h * 0.4 / tick)                        # ayahs one drag can cover
    for _ in range(8):
        delta = ayah - current
        if delta == 0:
            return True
        n = min(abs(delta), most)
        secs = min(4.0, max(0.9, n / 6))              # about six ayahs a second, at most
        travel = (n - (n / secs) / RAIL_DECAY) * tick  # the release coast covers the rest
        sign = 1 if delta > 0 else -1                  # forward: finger moves up
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(int(y - sign * travel)), str(int(secs * 1000)))
        time.sleep(1.5 + 1.1)                          # settle, grace, then the page jumps
        landed = reading_ayah()
        log(f"ayah rail: aimed {current + sign * n}, page opens at {landed}")
        if landed is None:
            return False
        current = landed
    return current == ayah


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
    from recording import Sequence, remaining_recording_seconds
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
    at.demo('clock', '-e', 'hhmm', '0941')
    raw = os.path.join(d, "raw.mkv")
    if os.path.exists(raw):
        os.remove(raw)
    # The recorder gets a generous limit; the take ends a fixed time after the
    # recitation actually starts (Hani's first file can buffer for seconds)
    limit = int(math.ceil(secs + opening_estimate(s) + 300))
    import capture_host
    runtime = capture_host.python(CACHE, log)
    width, height = (1440, 3200) if a['capture'] == '2k' else (1080, 2400)
    ready, capture_meta = os.path.join(d, 'capture-ready.json'), os.path.join(d, 'capture.json')
    for path in (ready, capture_meta):
        if os.path.exists(path): os.remove(path)
    p = subprocess.Popen([runtime, os.path.join(os.path.dirname(__file__), 'capture_host.py'),
                          '--serial', serial(), '--cache', CACHE, '--output', raw, '--ready', ready,
                          '--metadata', capture_meta, '--width', str(width), '--height', str(height), '--limit', str(limit)],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline = time.monotonic() + 45
    while not os.path.exists(ready):
        if p.poll() is not None:
            raise RuntimeError('Host recording failed: ' + p.stdout.read()[-2000:])
        if time.monotonic() > deadline:
            p.terminate(); p.wait(timeout=20)
            raise RuntimeError('Host recording did not become ready within 45 seconds')
        time.sleep(.05)
    info = json.load(open(ready))
    recording_start = time.monotonic() - (time.time_ns() // 1000 - info['started_wall_us']) / 1_000_000
    time.sleep(2.0)
    marks = {"dwell": a["intro"]["dwell"], "scene_only": not a["intro"]["play"]}
    def mark(key):
        marks[key] = time.monotonic() - recording_start
    try:
        if opening_steps(s): mark("intro")
        sequence.run(mark, stage)
        stage("record", "Starting playback" if a['intro']['play'] else "Holding the last screen", 0.25)
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
    want = remaining_recording_seconds(s, t0 - recording_start, marks)
    stage("record", f"Recording {want:.1f} s remaining", 0.25)
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
                    raise RuntimeError("host recorder did not close within 20 seconds")
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
        raise SystemExit("host recording failed:\n" + out[-2000:])
    stage("conform", "Finding the recitation's start and conforming to 60 fps", 0.88)
    info = json.load(open(capture_meta))
    window = adb('shell', 'dumpsys', 'display')
    radius = re.search(r'RoundedCorner\{position=TopLeft, radius=(\d+)', window)
    scale = width / info['native_width']
    info['display'] = {'camera_baked': True, 'corner_radius': round(int(radius[1]) * scale, 3) if radius else None}
    conform(d, log, marks, info)
    if rep:
        rep.progress(1.0, "take ready")
    return d


def conform(d, log=print, marks=None, capture_info=None):
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
    # The emulator emits frames when the screen changes: fps=60 repeats the
    # last one, it never invents in-between frames
    codec = ["-c:v", "h264_nvenc", "-preset", "p4", "-rc", "constqp", "-qp", "12"] if capture_info else ["-c:v", "libx264", "-preset", "slow", "-crf", "12"]
    # A VFR still may have no image at the seek point. Input seeking drops
    # that held image and shortens the clip; trim after decoding and fps fill.
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-threads", "2", "-filter_threads", "1", "-i", raw, "-ss", f"{start:.3f}", "-map", "0:v",
                    "-vf", "fps=60,format=yuv420p", *codec,
                    "-g", "30", "-an", clip], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", raw, "-ss", f"{start:.3f}", "-map", "0:a",
                    "-ar", "48000", "-ac", "2", wav], check=True)
    length = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                   clip], capture_output=True, text=True, check=True).stdout.strip())
    w, h = map(int, subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                    "stream=width,height", "-of", "csv=p=0", clip],
                                   capture_output=True, text=True, check=True).stdout.strip().split(","))
    meta = {"onset_raw": onset, "video_first_pts": v0, "lead": round(onset - start, 3) if onset is not None else length, "length": length, "marks": marks, **{k: round(v, 1) for k, v in rates.items()},
            "width": w, "height": h, "recorded": time.strftime("%Y-%m-%d %H:%M")}
    if capture_info: meta.update(capture=capture_info, display=capture_info['display'])
    with open(os.path.join(d, "meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
    log(f"take ready: {d} ({length:.1f}s, active cadence {rates['active_fps']:.1f} fps, p90 frame interval {rates['frame_p90_ms']:.0f} ms)")
    return meta
