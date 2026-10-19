"""Promo video specs: one JSON file per video (see specs/ and README.md).

A spec names what to record from the app (`app`), the world around the
phone (`background`, `phone`), how the camera moves (`camera`), any words
on screen (`captions`, `end_card`) and the export (`aspect`, `fps`,
`duration`). Missing keys take the defaults below, so a spec only says what
makes it different.
"""
import copy
import hashlib
import json
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

DEFAULTS = {
    "title": "",
    "aspect": "1:1",
    "fps": 60,
    "duration": 20.0,          # seconds of finished video
    "app": {
        "surah": 1,
        "ayah": 1,
        "reciter": "alafasy",
        "theme": "Paper",
        "layout": "Scroll",
        "view": "Both",
        "text_size": "default",  # default | max
        "seconds": 30,           # seconds of recitation to record
        "start": 0.0,            # where the finished video starts in the take (s after the clip's t=0)
    },
    "background": {
        "id": "dunes",
        "blur": 36,              # gaussian sigma in output pixels
        "brightness": 0.0,       # -1..1 added in linear-ish space
        "saturation": 1.0,
        "speed": 0.5,            # background playback speed (it loops back and forth)
        "parallax": 0.18,        # share of the camera's zoom/pan the background follows
        "vignette": 0.25,
    },
    "phone": {
        "color": "auto",         # auto follows the theme; see phone.COLORS
        "height": 0.84,          # body height as a share of the frame height at zoom 1
        "x": 0.0, "y": 0.0,      # offset from the centre, shares of the frame
        "shadow": 0.45,
    },
    # Camera keyframes. t: video seconds; zoom: 1 = whole phone; x, y: the
    # point on the phone screen (0..1) the camera centres on; rot: degrees;
    # ease: how the move INTO this keyframe is paced.
    "camera": [{"t": 0, "zoom": 1.0, "x": 0.5, "y": 0.5}],
    "motion_blur": 1.0,          # shutter as a share of the frame interval (0 = off)
    "captions": [],              # {t0, t1, text, pos: top|bottom, size}
    "end_card": None,            # {t, title, subtitle}
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
    s = merge(DEFAULTS, raw)
    validate(s)
    if s["phone"]["color"] == "auto":
        s["phone"]["color"] = THEMES[s["app"]["theme"]]["phone"]
    s["camera"] = sorted(s["camera"], key=lambda k: k["t"]) or [{"t": 0, "zoom": 1, "x": 0.5, "y": 0.5}]
    return s


def validate(s):
    a = s["app"]
    errs = []
    if a["reciter"] not in RECITERS: errs.append(f"app.reciter must be one of {sorted(RECITERS)}")
    if a["theme"] not in THEMES: errs.append(f"app.theme must be one of {sorted(THEMES)}")
    if a["layout"] not in LAYOUTS: errs.append(f"app.layout must be one of {LAYOUTS}")
    if a["view"] not in VIEWS: errs.append(f"app.view must be one of {VIEWS}")
    if not (1 <= int(a["surah"]) <= 114): errs.append("app.surah must be 1..114")
    if s["aspect"] not in ASPECTS: errs.append(f"aspect must be one of {sorted(ASPECTS)}")
    for k in s["camera"]:
        if k.get("ease", "inOutCubic") not in EASES: errs.append(f"camera ease {k.get('ease')} not in {EASES}")
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
