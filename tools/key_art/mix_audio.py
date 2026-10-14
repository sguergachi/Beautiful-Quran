#!/usr/bin/env python3
"""Rebuild the recitation a capture played, over a synthesised rain carriage.

    mix_audio.py AUDIO_LOG.json AUDIO_DIR START_S END_S OUT.wav [--files 001001,055001,...]

AUDIO_LOG is capture.mjs's log: every play/stop of a media element in virtual
time. Segments are matched to files in playback order (the reader plays the
basmalah from 1:1, then the chapter's verses). Each segment is placed at the
frame where the capture played it, from the same in-file position, so the ink
on screen and the voice stay locked. Ambience (no music, see docs/KEY_ART.md):
rain on glass, carriage rumble, and rail joints on the scene's 1.1 s sway.
"""
import json
import subprocess
import sys

import numpy as np
from scipy import signal

SR = 48000


def decode(path):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def segments(log):
    """Pair each play with the stop/end that closes it."""
    e0 = log["t"] - log["frames"] * 1000 / log["fps"]
    out, open_ = [], {}
    for e in log["log"]:
        key = e["src"]
        if e["kind"] == "play":
            open_[key] = e
        elif e["kind"] in ("stop", "end") and key in open_:
            p = open_.pop(key)
            out.append(((p["t"] - e0) / 1000, p["pos"], e["pos"]))
    for p in open_.values():  # still playing when the capture ended
        out.append(((p["t"] - e0) / 1000, p["pos"], None))
    return sorted(out)


def rain(n, rng):
    """Rain on glass: a soft hiss plus individual drop ticks, slightly wide."""
    out = np.zeros((n, 2), np.float32)
    for ch in range(2):
        w = rng.standard_normal(n).astype(np.float32)
        b, a = signal.butter(2, [500 / (SR / 2), 7000 / (SR / 2)], "band")
        hiss = signal.lfilter(b, a, w)
        swell = 1 + 0.25 * np.sin(2 * np.pi * np.arange(n) / SR * 0.11 + ch)
        out[:, ch] = hiss * swell * 0.18
    # drops: short filtered clicks, a few per second per channel
    for ch in range(2):
        times = np.cumsum(rng.exponential(1 / 14, int(n / SR * 20)))
        times = (times[times < n / SR - 0.05] * SR).astype(int)
        click = np.exp(-np.arange(int(0.012 * SR)) / (0.0018 * SR)) * rng.standard_normal(int(0.012 * SR))
        b, a = signal.butter(2, [1800 / (SR / 2), 9000 / (SR / 2)], "band")
        click = signal.lfilter(b, a, click).astype(np.float32)
        for t in times:
            out[t:t + len(click), ch] += click * rng.uniform(0.15, 0.6)
    return out


def carriage(n, rng):
    """Low rumble with slow movement, plus rail joints every 1.1 s (bogie pairs)."""
    w = rng.standard_normal(n).astype(np.float32)
    b, a = signal.butter(2, 160 / (SR / 2), "low")
    rumble = signal.lfilter(b, a, np.cumsum(w) * 0.02)
    rumble -= signal.lfilter(*signal.butter(1, 20 / (SR / 2), "low"), rumble)  # drop DC drift
    rumble /= np.max(np.abs(rumble)) + 1e-9
    t = np.arange(n) / SR
    rumble *= 0.55 + 0.15 * np.sin(2 * np.pi * 0.07 * t)
    joints = np.zeros(n, np.float32)
    thump_len = int(0.16 * SR)
    tt = np.arange(thump_len) / SR
    for k, start in enumerate(np.arange(0.25, n / SR - 0.4, 1.1)):
        for dt, gain in ((0.0, 1.0), (0.13, 0.75)):
            s = int((start + dt) * SR)
            f = 70 + 25 * rng.random()
            thump = np.sin(2 * np.pi * f * tt) * np.exp(-tt / 0.035)
            thump += 0.4 * rng.standard_normal(thump_len) * np.exp(-tt / 0.01)
            joints[s:s + thump_len] += thump * gain * (0.8 + 0.2 * rng.random())
    b, a = signal.butter(2, 900 / (SR / 2), "low")
    joints = signal.lfilter(b, a, joints)
    mono = rumble * 0.6 + joints * 0.35
    return np.stack([mono, mono], axis=1).astype(np.float32)


def main():
    log_path, audio_dir, start, end, out = sys.argv[1:6]
    start, end = float(start), float(end)
    files = "001001,055001,055002,055003,055004,055005,055006,055007,055008,055009,055010"
    if "--files" in sys.argv:
        files = sys.argv[sys.argv.index("--files") + 1]
    files = files.split(",")
    log = json.load(open(log_path))
    n = int((end - start) * SR)
    voice = np.zeros(n, np.float32)
    fade = int(0.008 * SR)
    for (t0, p0, p1), name in zip(segments(log), files):
        a = decode(f"{audio_dir}/{name}.mp3")
        i0 = int(p0 * SR)
        i1 = len(a) if p1 is None else min(len(a), int(p1 * SR))
        seg = a[i0:i1].copy()
        seg[:fade] *= np.linspace(0, 1, fade)
        seg[-fade:] *= np.linspace(1, 0, fade)
        o = int((t0 - start) * SR)
        lo, hi = max(0, o), min(n, o + len(seg))
        if hi > lo:
            voice[lo:hi] += seg[lo - o:hi - o]
    rng = np.random.default_rng(55)
    amb = rain(n, rng) * 1.0 + carriage(n, rng) * 0.9
    # ambience sits ~16 dB under the voice's speaking level
    speech = voice[np.abs(voice) > 0.01]
    v_rms = np.sqrt(np.mean(speech ** 2)) if len(speech) else 0.1
    a_rms = np.sqrt(np.mean(amb ** 2))
    amb *= v_rms * 10 ** (-16 / 20) / a_rms
    # fade the bed in over the first second and out over the last two
    env = np.ones(n, np.float32)
    env[:SR] = np.linspace(0.3, 1, SR)
    env[-2 * SR:] *= np.linspace(1, 0, 2 * SR)
    mix = amb * env[:, None] + voice[:, None]
    mix /= max(1.0, np.max(np.abs(mix)) / 0.89)
    pcm = (np.clip(mix, -1, 1) * 32767).astype("<i2")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "2", "-i", "-",
                    "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", str(SR), out],
                   input=pcm.tobytes(), check=True)


if __name__ == "__main__":
    main()
