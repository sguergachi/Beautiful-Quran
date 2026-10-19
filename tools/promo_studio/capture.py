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

from spec import CACHE, HERE, RECITERS, REPO, take_dir

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


def boot(cores=6, log=print):
    """Boot the capture emulator: the pool's golden snapshot, read-only, more cores.

    The pool (scripts/emu.sh) runs 3-core slots, fine for tests but ~44 fps
    for takes. This instance shares the golden AVD (-read-only) on its own
    port. Audio stays -no-audio: playback capture taps the guest mixer, so
    nothing ever plays through the host speakers.
    """
    if is_up():
        log(f"{serial()} already up")
        return
    env = os.path.join(REPO, "scripts", "android_env.sh")
    script = f"""
set -euo pipefail
source "{env}"
for x in "/run/user/$(id -u)"/xauth_* "$HOME/.Xauthority"; do [[ -r "$x" ]] && {{ export XAUTHORITY="$x"; break; }}; done
export DISPLAY="${{DISPLAY:-:0}}"
setsid -f "$ANDROID_HOME/emulator/emulator" -avd "${{EMU_GOLD:-BeautifulQuran_gold_35}}" -port {CAPTURE_PORT} \\
  -no-window -no-audio -no-boot-anim -no-metrics -gpu host -accel on -feature -Vulkan \\
  -netdelay none -netspeed full -read-only -snapshot pool_ready -no-snapshot-save -cores {cores} \\
  > "{CACHE}/capture-emu.log" 2>&1 < /dev/null 7>&- 8>&-
"""
    os.makedirs(CACHE, exist_ok=True)
    subprocess.run(["bash", "-c", script], check=True,
                   env={"HOME": os.environ["HOME"], "PATH": "/usr/bin:/bin"})
    for _ in range(120):
        if is_up():
            log(f"{serial()} booted")
            adb("shell", "svc", "power", "stayon", "true", check=False)
            return
        time.sleep(2)
    raise SystemExit(f"capture emulator did not boot; see {CACHE}/capture-emu.log "
                     "(only one emulator may run at a time on this host: `scripts/emu.sh down` first)")


def install(apk, log=print):
    log(adb("install", "-r", apk, timeout=600).strip().splitlines()[-1])
    # interpreted code janks; AOT-compile like emu.sh run does
    adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", "com.beautifulquran", timeout=600)
    adb("shell", "am", "start", "-n", "com.beautifulquran/.MainActivity", check=False)
    log("warming up (recitation timings download on first launch)")
    time.sleep(30)


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


def scroll_top(anchor, max_swipes=6):
    """Screens keep their scroll position between visits: swipe back up to `anchor`."""
    w, h = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    for _ in range(max_swipes):
        if at.find(anchor):
            return True
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.45)), str(w // 2), str(int(h * 0.95)), "300")
        time.sleep(0.7)
    return bool(at.find(anchor))


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
    return (len(ts) - 1) / (ts[-1] - ts[0]) if len(ts) > 2 else 0.0


def playing():
    out = adb("shell", "dumpsys", "media_session", check=False)
    return "state=PlaybackState {state=PLAYING" in out


ARABIC_DIGITS = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")


def _is_arabic_word(t):
    return any("\u0621" <= ch <= "\u064a" for ch in t) and "﴿" not in t


def first_word_xy(ayah):
    """Centre of the opened verse's first word: the first Arabic word after
    the previous verse's end marker (﴿N﴾, Arabic-Indic digits)."""
    ns = at.nodes()
    want = "﴿" + str(ayah - 1).translate(ARABIC_DIGITS) + "﴾"
    idx = next((i for i, (t, d, b) in enumerate(ns) if t.replace("\u2060", "") == want), None)
    cands = ns[idx + 1:] if idx is not None else ns
    for t, d, b in cands:
        if _is_arabic_word(t):
            return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    return None


def start_playback(ayah=1, layout="Scroll"):
    """Start the opened verse without the reload a `?play=true` deep link
    causes (it crossfades and re-scrolls the page as the recitation begins).

    Ayah 1 (and the Mushaf): the bar's Play, which plays the verse being
    read, with the basmalah lead-in at a chapter's start. Other verses on
    the scroll: tap the verse's first word (ReaderViewModel.playFromAyahWord),
    since after a deep link the bar's Play still points at ayah 1.
    """
    for _ in range(3):
        xy = first_word_xy(ayah) if (ayah > 1 and layout == "Scroll") else at.find("Play")
        if xy:
            at.tap(xy)
        for _ in range(10):
            time.sleep(0.25)
            if playing():
                return
    raise SystemExit("playback did not start")


def record(s, log=print, force=False):
    """Record (or reuse) the take a spec needs. Returns the take directory."""
    _env()
    a = s["app"]
    d = take_dir(s)
    meta_p = os.path.join(d, "meta.json")
    need = float(a["start"]) + float(s["duration"]) + 0.5
    if not force and os.path.exists(meta_p):
        meta = json.load(open(meta_p))
        if meta["length"] >= need:
            log(f"take exists: {d}")
            return d
    if not is_up():
        raise SystemExit(f"no emulator at {serial()}: run `studio.py emu boot` (and `emu install`)")
    os.makedirs(d, exist_ok=True)
    secs = max(int(a["seconds"]), int(need - LEAD + 1))
    prep()
    set_reciter(a["reciter"], log)
    customize(a["theme"], a["layout"], a["view"], a["text_size"], log)
    # open the verse still, let it settle, then roll and start the recitation
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
    at.open_reader(int(a["surah"]), int(a["ayah"]))
    time.sleep(3.0)
    raw = os.path.join(d, "raw.mkv")
    if os.path.exists(raw):
        os.remove(raw)
    limit = int(math.ceil(secs + LEAD + 3))
    env = dict(os.environ, ADB=shutil.which("adb"))
    p = subprocess.Popen([scrcpy(), "--no-window", "--no-playback", f"--record={raw}",
                          "--audio-source=playback", "--audio-codec=flac", "--video-codec=h264",
                          "--video-bit-rate=40M", "--max-fps=60", f"--time-limit={limit}"],
                         env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(2.0)
    log(f"recording {limit}s: {a['reciter']} {a['surah']}:{a['ayah']} {a['theme']} {a['layout']} {a['view']}")
    start_playback(int(a["ayah"]), a["layout"])
    out, _ = p.communicate(timeout=limit + 60)
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
    if not os.path.exists(raw) or os.path.getsize(raw) < 10000:
        raise SystemExit("scrcpy recording failed:\n" + out[-2000:])
    conform(d, log)
    return d


def conform(d, log=print):
    """raw.mkv -> clip.mp4 (60 fps CFR) + audio.wav, both starting LEAD s before the recitation."""
    raw = os.path.join(d, "raw.mkv")
    onset = audio_onset(raw, after=1.0)
    v0 = first_video_pts(raw)
    start = max(0.0, onset - LEAD)
    fps = cadence(raw, onset, onset + 20)
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
    meta = {"onset_raw": onset, "video_first_pts": v0, "lead": LEAD, "length": length, "capture_fps": round(fps, 1),
            "width": w, "height": h, "recorded": time.strftime("%Y-%m-%d %H:%M")}
    json.dump(meta, open(os.path.join(d, "meta.json"), "w"), indent=1)
    log(f"take ready: {d} ({length:.1f}s, captured at {fps:.1f} fps)")
    return meta
