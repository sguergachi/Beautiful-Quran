"""Time: keyframed properties and the edit (editor/editor.js mirrors this).

A track's value before its first key is that key's value, after its last
key the last one's; in between, the right-hand key's ease paces the move.
A property with no keys uses its static value in `props`.
"""
import math

from spec import PROPS


def ease(name, u):
    u = min(1.0, max(0.0, u))
    if name == "linear":
        return u
    if name == "hold":
        return 0.0 if u < 1.0 else 1.0
    if name == "inOutSine":
        return 0.5 - 0.5 * math.cos(math.pi * u)
    if name == "outCubic":
        return 1 - (1 - u) ** 3
    if name == "inCubic":
        return u ** 3
    if name == "outExpo":
        return 1.0 if u >= 1 else 1 - 2 ** (-10 * u)
    return 4 * u ** 3 if u < 0.5 else 1 - (-2 * u + 2) ** 3 / 2      # inOutCubic


def value_at(s, name, t):
    keys = s["tracks"].get(name)
    if not keys:
        return float(s["props"][name])
    if t <= keys[0]["t"]:
        return float(keys[0]["v"])
    for a, b in zip(keys, keys[1:]):
        if a["t"] <= t <= b["t"]:
            e = ease(b.get("ease", "inOutCubic"), (t - a["t"]) / max(1e-6, b["t"] - a["t"]))
            va, vb = float(a["v"]), float(b["v"])
            if PROPS[name].get("log") and va > 0 and vb > 0:
                return math.exp(math.log(va) + (math.log(vb) - math.log(va)) * e)
            return va + (vb - va) * e
    return float(keys[-1]["v"])


def state_at(s, t):
    return {k: value_at(s, k, t) for k in PROPS}


def source_time(s, t):
    """Video time -> (take time, piece); (0, None) in a silent picture gap."""
    t = max(0.0, t)
    acc = 0.0
    for p in s["edit"]:
        acc += float(p.get("gap", 0))
        if t < acc:
            return 0.0, None
        n = float(p["out"]) - float(p["in"])
        if t < acc + n:
            return float(p["in"]) + (t - acc), p
        acc += n
    last = s["edit"][-1]
    return float(last["out"]), last


def video_times(s, take_t0, take_t1):
    """Take span -> the video spans it appears in (a span can be cut or repeated)."""
    out, acc = [], 0.0
    for p in s["edit"]:
        acc += float(p.get("gap", 0))
        i, o = float(p["in"]), float(p["out"])
        a, b = max(i, take_t0), min(o, take_t1)
        if b > a:
            out.append((acc + a - i, acc + b - i))
        acc += o - i
    return out


def sound_onset(s, lead):
    """Video time of the first sound. A take opens on `lead` silent seconds
    before the recitation, so the fade in starts where the sound does."""
    if not s.get("app", {}).get("intro", {}).get("play", True): return 0.0
    acc = 0.0
    for p in s["edit"]:
        acc += float(p.get("gap", 0))
        i, o = float(p["in"]), float(p["out"])
        if o > lead:
            return acc + max(0.0, lead - i)
        acc += o - i
    return 0.0
