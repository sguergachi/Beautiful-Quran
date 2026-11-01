"""Emulators for takes: find them, start more, get the app onto them.

Capture emulators are read-only instances of the pool's golden AVD
(scripts/emu.sh), cold-booted with a native 1440×3200 framebuffer and six cores, on
ports from 5650 up (the pool uses 5600-5606). Several can run at once, so
several takes can record in parallel; each needs the app installed and its
recitation timings downloaded once ("prepare"), because a read-only instance
starts from the golden data every boot.

Audio stays -no-audio: capture taps the emulator's PCM stream, so nothing
plays through the host speakers. The ordinary test pool keeps its warm snapshot.
"""
import io
import os
import queue
import re
import shutil
import socket
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from spec import CACHE, REPO

APP = "com.beautifulquran"
CAPTURE_BASE = 5650
POOL_PORTS = range(5600, 5608, 2)
DEFAULT_APK = os.path.join(REPO, "app", "build", "outputs", "apk", "release", "app-release.apk")
APK_SIDECAR = DEFAULT_APK + ".json"   # what revision a studio-built APK came from
BOOT_SECONDS = 25            # native capture cold boot, for the progress estimate
BUILD_SECONDS = 240          # release build, for the progress estimate


def adb(serial, *a, timeout=15, check=False):
    r = subprocess.run(["adb", "-s", serial, *a], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(f"adb {' '.join(a)}: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def serials():
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10).stdout
    return [l.split()[0] for l in out.splitlines()[1:] if l.startswith("emulator-") and l.split()[-1] == "device"]


def port_of(serial):
    return int(serial.split("-")[1])


def info(serial):
    """What the editor shows for one emulator; never raises."""
    port = port_of(serial)
    d = {"serial": serial, "port": port, "kind": "capture" if port >= CAPTURE_BASE else "pool",
         "booted": False, "app": False, "cores": None, "size": None, "error": None}
    try:
        d["booted"] = adb(serial, "shell", "getprop", "sys.boot_completed", timeout=5).strip() == "1"
        if d["booted"]:
            d["app"] = "package:" in adb(serial, "shell", "pm", "path", APP, timeout=8)
            d["cores"] = int(adb(serial, "shell", "nproc", timeout=5).strip() or 0) or None
            m = re.findall(r"(\d+)x(\d+)", adb(serial, "shell", "wm", "size", timeout=5))
            d["size"] = f"{m[-1][0]}x{m[-1][1]}" if m else None
    except Exception as e:                      # a wedged emulator still lists
        d["error"] = str(e)
    return d


def list_all():
    ss = serials()
    with ThreadPoolExecutor(max(1, len(ss))) as ex:
        return sorted(ex.map(info, ss), key=lambda d: d["port"])


def _port_free(port):
    with socket.socket() as s:
        return s.connect_ex(("127.0.0.1", port)) != 0


def next_port():
    used = {port_of(s) for s in serials()}
    for port in range(CAPTURE_BASE, CAPTURE_BASE + 40, 2):
        if port not in used and _port_free(port) and _port_free(port + 1):
            return port
    raise RuntimeError("no free emulator port")


def log_path(port):
    return os.path.join(CACHE, f"capture-emu-{port}.log")


def boot(port=None, cores=6, rep=None):
    """Start a capture emulator; returns its serial once Android has booted."""
    port = port or next_port()
    serial = f"emulator-{port}"
    if serial in serials():
        rep and rep.log(f"{serial} is already running")
        return serial
    if not 5650 <= port <= 5688 or port % 2 or not 1 <= cores <= 8:
        raise ValueError("capture ports must be even, 5650-5688; cores must be 1-8")
    os.makedirs(CACHE, exist_ok=True)
    log = log_path(port)
    rep and rep.stage("boot", f"Starting {serial} ({cores} cores)", 0.02)
    # The shared launcher owns GPU setup, the boot lock and the RAM guard.
    with open(log, "w") as out:
        proc = subprocess.Popen(["bash", os.path.join(REPO, "scripts", "emu.sh"), "capture", str(port)],
                                env=dict(os.environ, EMU_CORES=str(cores), EMU_CAPTURE_SIZE="1440x3200"), stdout=out, stderr=subprocess.STDOUT)
    t0 = time.monotonic()
    try:
        while proc.poll() is None:
            if time.monotonic() - t0 > 300:
                proc.terminate()
                raise RuntimeError(f"{serial} did not boot within 5 minutes; see {log}")
            rep and rep.progress(min(0.95, (time.monotonic() - t0) / BOOT_SECONDS))
            time.sleep(0.5)
        if proc.returncode:
            raise RuntimeError(f"{serial} failed to boot:\n{open(log).read()[-1500:]}")
        adb(serial, "shell", "svc", "power", "stayon", "true")
        rep and rep.progress(1.0, f"{serial} booted in {time.monotonic() - t0:.0f}s")
        return serial
    finally:
        if proc.poll() is None:
            proc.terminate()
        proc.wait()


def prepare(serial, apk=DEFAULT_APK, rep=None):
    """Install the app, compile it ahead of time, and wait for its first
    online launch to download the recitation timings (repeats need them)."""
    if port_of(serial) < CAPTURE_BASE:
        raise ValueError("test-pool emulators are managed by scripts/emu.sh; start a capture emulator")
    if not os.path.exists(apk):
        raise RuntimeError(f"no APK at {apk}: build one with ./gradlew assembleRelease")
    rep and rep.stage("install", f"Installing the app on {serial}", 0.02)
    out = adb(serial, "install", "-r", apk, timeout=600)
    if "Success" not in out:
        raise RuntimeError(f"install failed: {out.strip()[-500:]}")
    rep and rep.stage("compile", "Compiling the app ahead of time (smooth playback)", 0.35)
    adb(serial, "shell", "cmd", "package", "compile", "-m", "speed", "-f", APP, timeout=600)
    rep and rep.stage("timings", "First launch: downloading recitation timings", 0.6)
    adb(serial, "shell", "am", "start", "-n", f"{APP}/.MainActivity")
    t0 = time.monotonic()
    seen_cover = False
    while time.monotonic() - t0 < 240:
        time.sleep(2)
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=20)
        xml = adb(serial, "shell", "cat", "/sdcard/ui.xml", timeout=10)
        m = re.search(r"Downloading recitation timings[^\"]*?(\d+) of (\d+)", xml)
        if m:
            seen_cover = True
            done, total = int(m.group(1)), int(m.group(2))
            rep and rep.progress(0.6 + 0.38 * done / max(1, total), f"timings {done} of {total}")
        elif "Search concept" in xml or "Al-Fatihah" in xml:      # home, past the cover
            rep and rep.progress(1.0, "app ready" + ("" if seen_cover else " (timings already present)"))
            return serial
    raise RuntimeError("the app did not finish its first launch within 4 minutes")


def stop(serial):
    if port_of(serial) < CAPTURE_BASE:
        raise ValueError("test-pool emulators are managed by scripts/emu.sh")
    adb(serial, "emu", "kill", timeout=10)


# ---------------------------------------------------------------- release APK

def _git(*args, timeout=15):
    """One git command in the repo, or None when git cannot answer."""
    try:
        r = subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip() if r.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def gradle_version():
    """The app's version as `name (code)`, e.g. `0.12 (13)`, or None."""
    try:
        text = open(os.path.join(REPO, "app", "build.gradle.kts")).read()
    except OSError:
        return None
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    if not name or not code:
        return None
    return f"{name.group(1)} ({code.group(1)})"


def apk_info():
    """Everything the editor shows about the release APK: which version and
    commit the file on disk came from, and what HEAD has moved to since.

    A studio build leaves a sidecar (APK_SIDECAR) recording its exact
    revision; a hand-built APK is dated by its mtime instead (`exact`
    is False, and the commit is the newest one older than the file). Dirt is
    scoped to the paths a rebuild reads, so editing the studio itself does
    not mark the APK stale."""
    import json
    head_full = _git("rev-parse", "HEAD")
    head_short = _git("rev-parse", "--short=9", "HEAD")
    head_msg = _git("log", "-1", "--format=%s", "HEAD")
    head_version = gradle_version()
    dirty = bool(_git("status", "--porcelain=v1", "--", "app", "data", "gradle", "baselineprofile",
                       "build.gradle.kts", "settings.gradle.kts", "gradle.properties"))
    info = {"path": DEFAULT_APK, "exists": False, "version": None, "commit": None,
            "commit_message": None, "built_at": None, "size_bytes": None, "exact": False,
            "head_version": head_version, "head_commit": head_short,
            "head_commit_message": head_msg, "head_dirty": dirty, "up_to_date": False}
    try:
        st = os.stat(DEFAULT_APK)
    except OSError:
        return info
    info.update(exists=True, built_at=st.st_mtime, size_bytes=st.st_size)
    try:
        side = json.load(open(APK_SIDECAR))
        fresh = (side.get("mtime_ns") == st.st_mtime_ns and side.get("size_bytes") == st.st_size) or \
                (abs(float(side.get("built_at", 0)) - st.st_mtime) < 0.01 and side.get("size_bytes") == st.st_size)
    except (OSError, ValueError):
        side, fresh = {}, False
    if fresh:
        info.update(version=side.get("version"), commit=side.get("commit"),
                    commit_message=side.get("commit_message"), exact=True)
        full = side.get("commit_full") or ""
    else:
        before = _git("log", "-1", f"--before={int(st.st_mtime) + 1}", "--format=%H%x00%h%x00%s")
        if before and "\x00" in before:
            full, short, msg = before.split("\x00", 2)
            info.update(commit=short, commit_message=msg)
        else:
            full = ""
    if info["commit"] and head_full and (head_full == full or head_full.startswith(info["commit"])):
        info["up_to_date"] = not dirty
    return info


def _find_jdk21():
    """A JDK 21 home for Gradle, mirroring scripts/android_env.sh."""
    candidates = [os.environ.get("JAVA_HOME") or ""]
    java = shutil.which("java")
    if java:
        try:
            home = os.path.dirname(os.path.dirname(os.path.realpath(java)))
            candidates.append(home)
        except OSError:
            pass
    candidates += ["/usr/lib/jvm/java-21-openjdk", "/usr/lib/jvm/java-21-openjdk-amd64",
                   os.path.expanduser("~/.local/share/android-dev/jdk-21")]
    for home in candidates:
        exe = os.path.join(home, "bin", "java")
        if not home or not os.path.isfile(exe):
            continue
        try:
            out = subprocess.run([exe, "-version"], capture_output=True, text=True, timeout=10)
            if re.search(r'version "21', (out.stderr or "") + (out.stdout or "")):
                return home
        except (OSError, subprocess.SubprocessError):
            continue
    return None


def build_apk(rep=None):
    """Build the release APK (`./gradlew assembleRelease`); returns its path.

    Gradle runs with JDK 21 resolved like scripts/android_env.sh, because the
    server's environment cannot be assumed to have it. Interesting output
    lines stream to the job log while a time-based estimate fills the bar,
    since Gradle stays quiet for minutes during Kotlin compilation."""
    import json
    java_home = _find_jdk21()
    if not java_home:
        raise RuntimeError("JDK 21 is required to build the APK, and none was found "
                           "(JAVA_HOME, java on PATH, /usr/lib/jvm, ~/.local/share/android-dev)")
    env = dict(os.environ, JAVA_HOME=java_home,
               PATH=os.path.join(java_home, "bin") + os.pathsep + os.environ.get("PATH", ""))
    android_home = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Android/Sdk")
    env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = android_home
    rep and rep.stage("build", "Building the release APK", 0.02)
    proc = subprocess.Popen(["./gradlew", "assembleRelease", "--console=plain"], cwd=REPO, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines: queue.Queue = queue.Queue()
    reader = threading.Thread(target=_drain_lines, args=(proc.stdout, lines), daemon=True)
    reader.start()
    t0, tail = time.monotonic(), []
    try:
        while proc.poll() is None:
            _forward_build_lines(lines, tail, rep)
            elapsed = time.monotonic() - t0
            rep and rep.progress(min(0.95, 0.02 + 0.93 * elapsed / BUILD_SECONDS))
            time.sleep(0.5)
    finally:
        if proc.poll() is None:                       # cancelled: the job runner kills our group next
            proc.terminate()
        reader.join(timeout=5)
    _forward_build_lines(lines, tail, rep, final=True)
    if proc.returncode:
        raise RuntimeError("build failed:\n" + "\n".join(tail[-12:])[-800:])
    st = os.stat(DEFAULT_APK)
    side = {"version": gradle_version(), "commit": _git("rev-parse", "--short=9", "HEAD"),
            "commit_full": _git("rev-parse", "HEAD"), "commit_message": _git("log", "-1", "--format=%s", "HEAD"),
            "built_at": st.st_mtime, "mtime_ns": st.st_mtime_ns, "size_bytes": st.st_size}
    tmp = APK_SIDECAR + f".{os.getpid()}.tmp"
    with open(tmp, "w") as f:
        json.dump(side, f, indent=2)
    os.replace(tmp, APK_SIDECAR)
    rep and rep.progress(1.0, "APK ready")
    return DEFAULT_APK


def _drain_lines(stream, out):
    try:
        for line in stream:
            out.put(line.rstrip())
    finally:
        out.put(None)


def _forward_build_lines(lines, tail, rep, final=False):
    while True:
        try:
            line = lines.get_nowait()
        except queue.Empty:
            return
        if line is None:
            if final:
                return
            lines.put(None)
            return
        tail.append(line)
        if rep and (line.startswith("> Task ") or "FAILED" in line or line.startswith("e: ") or "error:" in line.lower()):
            rep.log(line if len(line) < 300 else line[:300] + "…")


_SHOTS = {}


def screenshot(serial, width=360, max_age=0.8):
    """Small JPEG of the emulator's screen, cached briefly (several viewers poll it)."""
    hit = _SHOTS.get(serial)
    if hit and time.monotonic() - hit[0] < max_age:
        return hit[1]
    png = subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=8).stdout
    if not png.startswith(b"\x89PNG"):
        raise RuntimeError("no screenshot")
    from PIL import Image
    im = Image.open(io.BytesIO(png)).convert("RGB")
    im = im.resize((width, round(im.height * width / im.width)), Image.BILINEAR)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=78)
    _SHOTS[serial] = (time.monotonic(), buf.getvalue())
    return buf.getvalue()
