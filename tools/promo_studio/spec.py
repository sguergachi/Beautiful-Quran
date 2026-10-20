"""Promo video specs: one JSON file per video (see specs/ and README.md).

A video is three things: the emulator recording (`app`, cut by `edit`), the
phone model (`phone`) and a background (`background`). Everything that moves
is a property (`PROPS`): it has a static value in `props`, and once it has
keyframes in `tracks` those drive it instead. Missing keys take the defaults
below, so a spec only says what makes it different.

    "edit":   [{"in": 0.0, "out": 12.4}, {"in": 15.0, "out": 21.0}]
              pieces of the take, played back to back (take seconds; the
              take opens 1 s before the recitation). The video's length is
              their sum.
    "tracks": {"zoom": [{"t": 0, "v": 2.2}, {"t": 2.5, "v": 1, "ease": "inOutCubic"}]}
              t: video seconds; v: value; ease: how the move INTO this key
              is paced ("hold" = cut).
"""
import copy
import hashlib
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SPECS = os.path.join(HERE, "specs")
CACHE = os.environ.get("BQ_PROMO_CACHE", os.path.expanduser("~/.cache/bq-promo"))
OUT = os.environ.get("BQ_PROMO_OUT", os.path.expanduser("~/Videos/beautiful-quran-promo"))

RECITERS = {
    "alafasy": {"id": 1, "slug": "Alafasy_128kbps", "name": "Mishary Rashid Alafasy"},
    "hani": {"id": 7, "slug": "Hani_Rifai_192kbps", "name": "Hani Ar-Rifai"},
}
THEMES = {
    # app theme label -> reading background, gold, suggested phone colour
    "Paper": {"bg": "#FAF3E8", "gold": "#A8831D", "phone": "porcelain"},
    "Nightfall": {"bg": "#0C0B09", "gold": "#CAA547", "phone": "obsidian"},
    "Royal green": {"bg": "#062C24", "gold": "#D0AA4D", "phone": "obsidian"},
}
LAYOUTS = ("Scroll", "Mushaf")
VIEWS = ("Arabic", "English", "Both")
ASPECTS = {"1:1": (1080, 1080), "9:16": (1080, 1920), "4:5": (1080, 1350), "16:9": (1920, 1080)}
EASES = ("inOutCubic", "inOutSine", "outCubic", "inCubic", "outExpo", "linear", "hold")
LEAD = 1.0                 # seconds of still page a take keeps before the recitation

# Animatable properties. `log`: interpolate in log space (scales), so a
# 1 -> 4 zoom feels even rather than rushing at the start.
PROPS = {
    "zoom":          {"group": "Camera", "label": "Zoom", "min": 0.4, "max": 6, "step": 0.01, "default": 1.0, "log": True},
    "pan_x":         {"group": "Camera", "label": "Pan X", "min": -0.5, "max": 1.5, "step": 0.001, "default": 0.5},
    "pan_y":         {"group": "Camera", "label": "Pan Y", "min": -0.5, "max": 1.5, "step": 0.001, "default": 0.5},
    "rotate":        {"group": "Camera", "label": "Rotate °", "min": -30, "max": 30, "step": 0.1, "default": 0.0},
    "phone_x":       {"group": "Phone", "label": "Phone X", "min": -0.8, "max": 0.8, "step": 0.001, "default": 0.0},
    "phone_y":       {"group": "Phone", "label": "Phone Y", "min": -0.8, "max": 0.8, "step": 0.001, "default": 0.0},
    "phone_scale":   {"group": "Phone", "label": "Phone scale", "min": 0.2, "max": 3, "step": 0.01, "default": 1.0, "log": True},
    "phone_tilt":    {"group": "Phone", "label": "Phone tilt °", "min": -45, "max": 45, "step": 0.1, "default": 0.0},
    "bg_blur":       {"group": "Background", "label": "Blur", "min": 4, "max": 90, "step": 0.5, "default": 36.0},
    "bg_brightness": {"group": "Background", "label": "Brightness", "min": -0.6, "max": 0.4, "step": 0.005, "default": 0.0},
}

DEFAULTS = {
    "title": "",
    "aspect": "1:1",
    "fps": 60,
    "app": {
        "surah": 1,
        "ayah": 1,
        "reciter": "alafasy",
        "theme": "Paper",
        "layout": "Scroll",
        "view": "Both",
        "text_size": "default",  # default | max
        "seconds": 30,           # seconds of recitation to record
    },
    "edit": [{"in": 0.0, "out": 20.0}],
    "background": {
        "id": "dunes",
        "saturation": 1.0,
        "speed": 0.5,            # background playback speed (it loops back and forth)
        "parallax": 0.18,        # share of the camera's zoom/pan the background follows
        "vignette": 0.25,
    },
    "phone": {
        "color": "auto",         # auto follows the theme; see phone.COLORS
        "height": 0.84,          # body height as a share of the frame height (phone_scale 1, zoom 1)
        "shadow": 0.45,
    },
    "props": {k: p["default"] for k, p in PROPS.items()},
    "tracks": {},
    "motion_blur": 1.0,          # shutter as a share of the frame interval (0 = off)
    "audio": {"gain_db": 0.0, "fade_in": 0.0, "fade_out": 1.2, "loudness": -16.0},  # LUFS; null = as recorded
}


def merge(base, over):
    out = copy.deepcopy(base)
    for k, v in (over or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


LEGACY = ("camera", "captions", "end_card", "duration")


def migrate(raw):
    """Specs from before the timeline: camera keys (focus as a point on the
    phone screen) become zoom/pan tracks with the same framing, app.start +
    duration become one edit piece, background blur/brightness and phone
    offsets become properties. Captions and end cards are dropped."""
    if not any(k in raw for k in LEGACY) and "start" not in raw.get("app", {}):
        return raw
    from phone import geometry
    raw = copy.deepcopy(raw)
    W, H = ASPECTS[raw.get("aspect", "1:1")]
    g = geometry(1080, 2400)
    ph = raw.get("phone", {})
    height, px, py = ph.get("height", 0.84), ph.pop("x", 0.0), ph.pop("y", 0.0)
    s0 = height * H / g["bh"]
    P0 = (W / 2 + px * W, H / 2 + py * H)
    tracks = raw.setdefault("tracks", {})
    camera = sorted(raw.pop("camera", []), key=lambda k: k["t"])
    has_rotation = any(k.get("rot") for k in camera)
    for k in camera:
        z = float(k.get("zoom", 1.0))
        F = (P0[0] + s0 * (g["sx0"] + k.get("x", 0.5) * g["sw"] - g["W"] / 2),
             P0[1] + s0 * (g["sy0"] + k.get("y", 0.5) * g["sh"] - g["H"] / 2))
        C = (F[0] - (P0[0] - W / 2) / z, F[1] - (P0[1] - H / 2) / z)
        e = {"ease": k["ease"]} if k.get("ease") else {}
        tracks.setdefault("zoom", []).append({"t": k["t"], "v": round(z, 4), **e})
        tracks.setdefault("pan_x", []).append({"t": k["t"], "v": round(C[0] / W, 4), **e})
        tracks.setdefault("pan_y", []).append({"t": k["t"], "v": round(C[1] / H, 4), **e})
        if has_rotation:
            tracks.setdefault("rotate", []).append({"t": k["t"], "v": k.get("rot", 0), **e})
    props = raw.setdefault("props", {})
    bg = raw.setdefault("background", {})
    if "blur" in bg: props["bg_blur"] = bg.pop("blur")
    if "brightness" in bg: props["bg_brightness"] = bg.pop("brightness")
    if px: props["phone_x"] = px
    if py: props["phone_y"] = py
    start = float(raw.get("app", {}).pop("start", 0.0))
    if "duration" in raw:
        dur = float(raw.pop("duration"))
        raw.setdefault("edit", [{"in": round(start, 3), "out": round(start + dur, 3)}])
    raw.pop("captions", None)
    raw.pop("end_card", None)
    return raw


def load(path_or_name):
    path = path_or_name
    if not os.path.exists(path):
        path = os.path.join(SPECS, path_or_name if path_or_name.endswith(".json") else path_or_name + ".json")
    with open(path) as f:
        raw = json.load(f)
    s = resolve(raw)
    s["name"] = os.path.splitext(os.path.basename(path))[0]
    return s


def resolve(raw):
    s = merge(DEFAULTS, migrate(raw))
    validate(s)
    if s["phone"]["color"] == "auto":
        s["phone"]["color"] = THEMES[s["app"]["theme"]]["phone"]
    s["edit"] = [p for p in s["edit"] if float(p["out"]) - float(p["in"]) > 1e-3] or [{"in": 0.0, "out": 1.0}]
    s["tracks"] = {k: sorted(v, key=lambda k_: k_["t"]) for k, v in s["tracks"].items() if v}
    s["duration"] = duration(s)
    return s


def duration(s):
    return round(sum(float(p["out"]) - float(p["in"]) for p in s["edit"]), 6)


def take_needed(s):
    """Take seconds a spec reads (its furthest edit point)."""
    return max(float(p["out"]) for p in s["edit"])


def validate(s):
    a = s["app"]
    errs = []

    def number(v, label, minimum=None, maximum=None):
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
            raise ValueError(f"{label} must be a finite number")
        if minimum is not None and v < minimum or maximum is not None and v > maximum:
            raise ValueError(f"{label} outside {minimum}..{maximum}")
        return v

    number(s["fps"], "fps", 1, 120)
    if int(s["fps"]) != s["fps"]: errs.append("fps must be an integer")
    number(s["motion_blur"], "motion_blur", 0, 2)
    number(a["surah"], "app.surah", 1, 114)
    number(a["ayah"], "app.ayah", 1, 286)
    if int(a["surah"]) != a["surah"] or int(a["ayah"]) != a["ayah"]: errs.append("chapter and ayah must be integers")
    number(a["seconds"], "app.seconds", 1)
    for name, p in PROPS.items():
        number(s["props"][name], f"props.{name}")
        if p.get("log") and s["props"][name] <= 0: errs.append(f"{name} must be positive")
    for name in ("height", "shadow"):
        number(s["phone"][name], f"phone.{name}", 0 if name == "shadow" else 0.01, 1 if name == "shadow" else 3)
    from phone import COLORS
    if s["phone"]["color"] not in (*COLORS, "auto"): errs.append("unknown phone colour")
    for name in ("saturation", "speed", "parallax", "vignette"):
        number(s["background"][name], f"background.{name}", 0, 3 if name in ("saturation", "speed") else 1)
    number(s["background"].get("offset", 0), "background.offset")
    for name in ("gain_db", "fade_in", "fade_out"):
        number(s["audio"][name], f"audio.{name}", -60 if name == "gain_db" else 0, 60)
    if s["audio"].get("loudness") is not None: number(s["audio"]["loudness"], "audio.loudness", -70, -5)
    if not s["edit"]: errs.append("the video needs at least one edit piece")
    if a["reciter"] not in RECITERS: errs.append(f"app.reciter must be one of {sorted(RECITERS)}")
    if a["theme"] not in THEMES: errs.append(f"app.theme must be one of {sorted(THEMES)}")
    if a["layout"] not in LAYOUTS: errs.append(f"app.layout must be one of {LAYOUTS}")
    if a["view"] not in VIEWS: errs.append(f"app.view must be one of {VIEWS}")
    if not (1 <= int(a["surah"]) <= 114): errs.append("app.surah must be 1..114")
    if s["aspect"] not in ASPECTS: errs.append(f"aspect must be one of {sorted(ASPECTS)}")
    for p in s["edit"]:
        number(p["in"], "edit.in", 0)
        number(p["out"], "edit.out", 0)
        if p["out"] - p["in"] <= 1e-3: errs.append(f"empty or reversed edit piece {p}")
    for name, keys in s["tracks"].items():
        if name not in PROPS: errs.append(f"unknown track {name}")
        times = set()
        for k in keys:
            number(k["t"], f"{name}.t", 0)
            number(k["v"], f"{name}.v")
            if k["t"] in times: errs.append(f"{name}: duplicate key time {k['t']}")
            times.add(k["t"])
            if PROPS.get(name, {}).get("log") and k["v"] <= 0: errs.append(f"{name} must be positive")
            if k.get("ease", "inOutCubic") not in EASES: errs.append(f"{name}: ease {k.get('ease')} not in {EASES}")
    if errs:
        raise ValueError("; ".join(errs))


def size(s):
    return ASPECTS[s["aspect"]]


def take_key(s):
    """Takes are shared by every spec that records the same thing."""
    a = s["app"]
    k = f"{a['reciter']}-{int(a['surah']):03d}-{int(a['ayah']):03d}-{a['theme']}-{a['layout']}-{a['view']}-{a['text_size']}"
    return k.replace(" ", "_").lower()


def take_dir(s):
    return os.path.join(CACHE, "takes", take_key(s))


def digest(obj):
    return hashlib.sha1(json.dumps(obj, sort_keys=True).encode()).hexdigest()[:12]
