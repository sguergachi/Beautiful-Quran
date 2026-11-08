"""A light copy of each take for the editor's preview (exports never use it).

A take is recorded at the phone's native 1440 x 3200 (or 1080 x 2400). The
editor shows it a few hundred pixels tall, so a browser decoding every native
frame spends four times the work for detail it then throws away with a cheap
bilinear shrink, which shimmers as the camera zooms. The preview copy is the
same frames on the same 60 fps clock, area-scaled once to 1600 tall, with a
keyframe every half second so scrubbing lands fast.

`ensure()` makes it in a background thread, niced, and never while a take is
being recorded (the recording needs the machine's full cadence).
"""
import os
import subprocess
import threading
import time

NAME = "preview.mp4"
HEIGHT = 1600
BUSY_POLL = 5          # seconds between checks while a recording keeps the machine

_lock = threading.Lock()
_making: set = set()


def path(d):
    return os.path.join(d, NAME)


def fresh(d):
    """The preview copy, when it exists and is newer than the take it was made from."""
    p, clip = path(d), os.path.join(d, "clip.mp4")
    try:
        return os.path.getmtime(p) >= os.path.getmtime(clip) and os.path.getsize(p) > 0
    except OSError:
        return False


def needed(d):
    """False for a take already small enough to play as it is."""
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=height",
                              "-of", "csv=p=0", os.path.join(d, "clip.mp4")], capture_output=True, text=True, timeout=20).stdout
        return int(out.strip() or 0) > HEIGHT
    except (OSError, ValueError, subprocess.SubprocessError):
        return False


def _encoders():
    try:
        out = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return ""
    return out


def make(d, log=print):
    """Write preview.mp4 beside clip.mp4 (atomically); returns its path."""
    clip, out = os.path.join(d, "clip.mp4"), path(d)
    tmp = out + f".{os.getpid()}.tmp.mp4"
    scale = ["-vf", f"scale=-2:{HEIGHT}:flags=area,format=yuv420p"]
    gop = ["-g", "30", "-bf", "0"]
    nvenc = "h264_nvenc" in _encoders()
    attempts = []
    if nvenc:
        attempts.append(["-c:v", "h264_nvenc", "-preset", "p4", "-rc", "constqp", "-qp", "19", *gop])
    attempts.append(["-c:v", "libx264", "-preset", "veryfast", "-crf", "18", *gop])
    last = None
    for codec in attempts:
        cmd = ["nice", "-n", "15", "ffmpeg", "-v", "error", "-y", "-threads", "4", "-i", clip, *scale,
               "-an", *codec, "-movflags", "+faststart", tmp]
        t0 = time.monotonic()
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0 and os.path.getsize(tmp) > 0:
            os.replace(tmp, out)
            log(f"preview copy ready: {out} ({os.path.getsize(out) / 1e6:.0f} MB, {time.monotonic() - t0:.0f} s)")
            return out
        last = r.stderr.strip()[-400:]
    try:
        os.remove(tmp)
    except OSError:
        pass
    raise RuntimeError(f"preview copy failed: {last}")


def ensure(d, busy=lambda: False, log=print):
    """Start making the preview copy if it is missing; True while it is being made."""
    if fresh(d):
        return False
    with _lock:
        if d in _making:
            return True
        if not os.path.exists(os.path.join(d, "clip.mp4")) or not needed(d):
            return False
        _making.add(d)

    def run():
        try:
            while busy():                     # a recording keeps the machine to itself
                time.sleep(BUSY_POLL)
            make(d, log)
        except Exception as e:                # the full recording keeps playing; try again next open
            log(f"preview copy skipped: {e}")
        finally:
            with _lock:
                _making.discard(d)
    threading.Thread(target=run, daemon=True, name="preview-copy").start()
    return True
