#!/usr/bin/env python3
"""Drive the Android app on an emulator for a marketing take (docs/KEY_ART.md).

    android_take.py prep                         # gesture nav + clean demo status bar
    android_take.py theme "Royal green"          # Settings > Customize > theme
    android_take.py textsize max|default         # Customize text-size slider
    android_take.py speed 0.5                    # cycle the transport speed label
    android_take.py anim 4                       # Android animation scales (use 1/speed)
    android_take.py ref OUT.png                  # 1x playback frame, chrome receded
    android_take.py record OUT.mp4 SURAH AYAH SECONDS [--size WxH]
    android_take.py customize THEME LAYOUT VIEW  # e.g. "Royal green" Scroll Both
    android_take.py header OUT.mp4 SECONDS REST  # chapter top framed, rest, then Play
    android_take.py restore                      # undo prep for the next tenant

Uses ANDROID_SERIAL. Elements are found by text / content-desc through
uiautomator, so takes are repeatable without hard-coded coordinates.
Slow-speed takes (speed < 1) give a busy emulator time to render every frame;
conform_take.py speeds them back up and the edit lays the recitation in. Set
`anim` to 1/speed for those takes: scrolls, fades and the glint's drying run
on Android's animation clock, not the audio, and would otherwise come out
too fast once the take is sped back up.
"""
import re
import subprocess
import sys
import time


def adb(*a, check=True):
    return subprocess.run(["adb", *a], capture_output=True, text=True, check=check).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n).group(1)
        d = re.search(r' content-desc="([^"]*)"', n).group(1)
        b = list(map(int, re.findall(r"\d+", re.search(r' bounds="([^"]*)"', n).group(1))))
        out.append((t, d, b))
    return out


def find(label, exact=True):
    for t, d, b in nodes():
        if (t == label or d == label) if exact else (label in t or label in d):
            return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    return None


def tap(xy):
    adb("shell", "input", "tap", str(xy[0]), str(xy[1]))


def tap_label(label, wait=1.2, exact=True):
    xy = find(label, exact)
    if not xy:
        raise SystemExit(f"not on screen: {label}")
    tap(xy)
    time.sleep(wait)


def open_reader(surah=55, ayah=1, play=False):
    q = "?play=true" if play else ""
    adb("shell", "am", "start", "-n", "com.beautifulquran/.MainActivity", "-a", "android.intent.action.VIEW",
        "-d", f"beautifulquran://verse/{surah}/{ayah}{q}", check=False)


def speed_text():
    for t, d, b in nodes():
        if re.fullmatch(r"[0-9.]+×", t):
            return t, ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    return None, None


def demo(*args):
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", *args, check=False)


def main():
    cmd = sys.argv[1]
    if cmd == "prep":
        adb("shell", "cmd", "overlay", "enable", "com.android.internal.systemui.navbar.gestural", check=False)
        adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
        demo("enter"); demo("clock", "-e", "hhmm", "0900")
        demo("battery", "-e", "level", "100", "-e", "plugged", "false")
        demo("network", "-e", "mobile", "hide")
        demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
        demo("notifications", "-e", "visible", "false")
    elif cmd == "anim":
        for k in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
            adb("shell", "settings", "put", "global", k, sys.argv[2])
    elif cmd == "restore":
        for k in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
            adb("shell", "settings", "put", "global", k, "1")
        demo("exit")
        adb("shell", "settings", "put", "global", "sysui_demo_allowed", "0")
        adb("shell", "cmd", "overlay", "disable", "com.android.internal.systemui.navbar.gestural", check=False)
        adb("shell", "wm", "size", "reset"); adb("shell", "wm", "density", "reset")
    elif cmd in ("theme", "textsize"):
        # playing hides the reader chrome: pause, reopen, wait for Settings
        adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
        open_reader(); time.sleep(3)
        for _ in range(5):
            if find("Settings"):
                break
            time.sleep(1.5)
        tap_label("Settings"); tap_label("Customize")
        if cmd == "theme":
            tap_label(sys.argv[2])
        else:
            small = [b for t, d, b in nodes() if t == "A"]
            small.sort(key=lambda b: b[0])
            y = (small[0][1] + small[0][3]) // 2
            x0, x1 = small[0][2] + 10, small[-1][0] - 10
            target = x1 if sys.argv[2] == "max" else x0 + int((x1 - x0) * 0.28)
            adb("shell", "input", "swipe", str((x0 + x1) // 2), str(y), str(target), str(y), "600")
            time.sleep(1)
        adb("shell", "input", "keyevent", "BACK"); time.sleep(0.8)
        adb("shell", "input", "keyevent", "BACK"); time.sleep(1.5)
    elif cmd == "speed":
        want = sys.argv[2].rstrip("×x") + "×"
        want = want.replace("1.0×", "1×")
        for _ in range(8):
            t, xy = speed_text()
            if t is None:
                raise SystemExit("speed control not visible")
            if t == want:
                print("speed", t); return
            tap(xy); time.sleep(0.8)
        raise SystemExit(f"could not reach {want}")
    elif cmd == "ref":
        open_reader(55, 1, play=True); time.sleep(4.5)
        subprocess.run(f"adb exec-out screencap -p > {sys.argv[2]}", shell=True, check=True)
        xy = find("Pause")
        if xy: tap(xy)
    elif cmd == "customize":
        theme, layout, view = sys.argv[2], sys.argv[3], sys.argv[4]
        adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
        open_reader(); time.sleep(3)
        for _ in range(5):
            if find("Settings"):
                break
            time.sleep(1.5)
        tap_label("Settings"); tap_label("Customize")
        tap_label(theme); tap_label(layout)
        for _ in range(3):                       # VIEW sits below the fold
            hits = sorted((b for t, d, b in nodes() if t == view), key=lambda b: b[1])
            if hits and any(t == "VIEW" for t, d, b in nodes()):
                b = hits[0]; tap(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)); time.sleep(1.2); break
            adb("shell", "input", "swipe", "540", "1800", "540", "900", "400"); time.sleep(1)
        adb("shell", "input", "keyevent", "BACK"); time.sleep(0.8)
        adb("shell", "input", "keyevent", "BACK"); time.sleep(1.5)
    elif cmd == "header":
        # Scroll is continuous across chapters: one measured swipe from the
        # verse link lands on this chapter's header; verify before rolling.
        out, secs, rest = sys.argv[2], sys.argv[3], float(sys.argv[4])
        adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
        open_reader(55, 1); time.sleep(4)
        w, h = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.29)), str(w // 2), str(int(h * 0.83)), "300")
        time.sleep(2.5)
        texts = [t for t, d, b in nodes()]
        if not any("Ar-Rahman" in t for t in texts) or any("Al-Qamar" in t for t in texts):
            raise SystemExit("header not framed: " + ", ".join(t for t in texts if t)[:200])
        adb("shell", "rm", "-f", "/sdcard/take.mp4", check=False)
        p = subprocess.Popen(["adb", "shell", "screenrecord", "--bit-rate", "30000000", "--time-limit", secs,
                              "/sdcard/take.mp4"])
        time.sleep(rest)
        # the verse link starts the chapter fresh (basmalah first) and lets the
        # app scroll to its reading place; the Play button would resume
        # wherever the last playback stopped and leave a return pill showing
        open_reader(55, 1, play=True)
        p.wait(); time.sleep(1)
        adb("pull", "/sdcard/take.mp4", out)
        adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE", check=False)
        print("pulled", out)
    elif cmd == "record":
        out, surah, ayah, secs = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
        size = sys.argv[sys.argv.index("--size") + 1] if "--size" in sys.argv else None
        adb("shell", "rm", "-f", "/sdcard/take.mp4", check=False)
        rec = ["adb", "shell", "screenrecord", "--bit-rate", "30000000", "--time-limit", secs]
        if size: rec += ["--size", size]
        p = subprocess.Popen(rec + ["/sdcard/take.mp4"])
        time.sleep(2)
        open_reader(int(surah), int(ayah), play=True)
        p.wait()
        time.sleep(1)
        adb("pull", "/sdcard/take.mp4", out)
        print("pulled", out)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
