"""Export settings: container, codec, quality, size, frame rate and sound.

Shared by compose.export (how to encode), the server (validation, the
editor's choices via /api/meta) and the CLI. No heavy imports.

Quality names map onto each encoder's own scale (CRF, or the ProRes
profile). For the editor's size estimate, `rate` is a share of H.264
"high"'s bitrate, measured on real renders of two drafts (03 and 05, 4 s
each, with camera moves); ProRes is Mbps at 1080 x 1080, 60 fps; a GIF is
kB/s at 540 x 540, 25 fps (the moving blur defeats GIF compression).
"""
import math

from spec import ASPECTS

QUALITIES = ("best", "high", "balanced", "small")

FORMATS = {
    "h264": {"label": "MP4 · H.264", "ext": "mp4", "audio": "aac",
             "hint": "Plays everywhere: social, messaging, slides.",
             "q": {"best": 11, "high": 14, "balanced": 18, "small": 23}, "q_label": "CRF",
             "rate": {"best": 1.53, "high": 1.0, "balanced": 0.58, "small": 0.31}},
    "hevc": {"label": "MP4 · HEVC (H.265)", "ext": "mp4", "audio": "aac",
             "hint": "Around 60% of H.264's size at the same look; Apple devices and most phones.",
             "q": {"best": 15, "high": 18, "balanced": 22, "small": 27}, "q_label": "CRF",
             "rate": {"best": 0.85, "high": 0.58, "balanced": 0.35, "small": 0.18}},
    "av1": {"label": "MP4 · AV1", "ext": "mp4", "audio": "aac",
            "hint": "Smallest files, 10-bit (no banding in the blur); newer browsers and phones.",
            "q": {"best": 20, "high": 26, "balanced": 32, "small": 38}, "q_label": "CRF",
            "rate": {"best": 0.63, "high": 0.42, "balanced": 0.29, "small": 0.21}},
    "vp9": {"label": "WebM · VP9", "ext": "webm", "audio": "opus",
            "hint": "For web pages: every browser plays it. Slower to encode.",
            "q": {"best": 18, "high": 24, "balanced": 31, "small": 37}, "q_label": "CRF",
            "rate": {"best": 1.0, "high": 0.7, "balanced": 0.38, "small": 0.23}},
    "prores": {"label": "MOV · ProRes 422", "ext": "mov", "audio": "pcm",
               "hint": "An editing master for Final Cut, Premiere or Resolve. Very large.",
               "q": {"best": 3, "high": 2, "balanced": 1, "small": 0}, "q_label": "profile",
               "q_names": {"best": "HQ", "high": "422", "balanced": "LT", "small": "Proxy"},
               "mbps": {"best": 175, "high": 125, "balanced": 92, "small": 35}},
    "gif": {"label": "GIF", "ext": "gif", "audio": None,
            "hint": "A silent loop for READMEs and chats. Very large (about 2 MB a second at 540p): keep it small and short.",
            "q": {"best": 256, "high": 256, "balanced": 192, "small": 128}, "q_label": "colours",
            "dither": {"best": "sierra2_4a", "high": "sierra2_4a", "balanced": "bayer:bayer_scale=3", "small": "bayer:bayer_scale=2"},
            "kbs": 1800, "rate": {"best": 1.0, "high": 1.0, "balanced": 0.87, "small": 0.76}},
}
FPS = (24, 25, 30, 50, 60)
GIF_FPS = (10, 20, 25)          # GIF delays are whole 1/100 s: these play at their true speed
AUDIO_KBPS = (0, 128, 192, 256, 320)
SCALES = (2.0, 4 / 3, 1.0, 2 / 3, 0.5, 1 / 3)
DEFAULTS = {"format": "h264", "quality": "high", "scale": 1.0, "fps": None, "audio_kbps": 256}
H264_HIGH_KBPS = 3000           # 1080 x 1080, 60 fps, measured on the drafts


def options(o=None):
    """Validated export settings: DEFAULTS overlaid with `o`. `fps` None
    keeps the video's own; a GIF always gets one of GIF_FPS."""
    o = {**DEFAULTS, **{k: v for k, v in (o or {}).items() if k in DEFAULTS}}
    if o["format"] not in FORMATS:
        raise ValueError(f"format must be one of {sorted(FORMATS)}")
    if o["quality"] not in QUALITIES:
        raise ValueError(f"quality must be one of {QUALITIES}")
    scale = o["scale"]
    if isinstance(scale, bool) or not isinstance(scale, (int, float)) or not math.isfinite(scale) or not 0.05 <= scale <= 2:
        raise ValueError("scale must be 0.05..2")
    o["scale"] = round(float(scale), 4)
    fps = o["fps"]
    if o["format"] == "gif":
        o["fps"] = fps if fps in GIF_FPS else 25
    elif fps is not None and fps not in FPS:
        raise ValueError(f"fps must be one of {FPS} (or null for the video's own)")
    if o["audio_kbps"] not in AUDIO_KBPS:
        raise ValueError(f"audio_kbps must be one of {AUDIO_KBPS}")
    if not FORMATS[o["format"]]["audio"]:
        o["audio_kbps"] = 0
    return o


def frame_size(s, scale):
    """Output pixels, even on both sides (compose.Renderer uses the same)."""
    W, H = ASPECTS[s["aspect"]]
    return int(round(W * scale / 2) * 2), int(round(H * scale / 2) * 2)


def filename(s, o):
    """<name>[-<short side>p][-<fps>fps].<ext>: one file per kind of export,
    so a 720p copy doesn't replace the full-size master. editor-app
    lib/exporting.ts mirrors this."""
    W, H = frame_size(s, o["scale"])
    suffix = f"-{min(W, H)}p" if abs(o["scale"] - 1) > 1e-3 else ""
    fps = o["fps"]
    if fps and fps != s["fps"] and o["format"] != "gif":
        suffix += f"-{fps}fps"
    return f"{s['name']}{suffix}.{FORMATS[o['format']]['ext']}"


def describe(s, o):
    """Short label for job titles: "HEVC · 720p · 30 fps"."""
    W, H = frame_size(s, o["scale"])
    name = {"h264": "H.264", "hevc": "HEVC", "av1": "AV1", "vp9": "WebM", "prores": "ProRes", "gif": "GIF"}[o["format"]]
    parts = [name, f"{min(W, H)}p"]
    if o["fps"] and o["fps"] != s["fps"]:
        parts.append(f"{o['fps']} fps")
    return " · ".join(parts)


def meta():
    """What the editor offers (/api/meta)."""
    keep = ("label", "ext", "audio", "hint", "q", "q_label", "q_names", "rate", "mbps", "kbs")
    return {"formats": {k: {f: v[f] for f in keep if f in v} for k, v in FORMATS.items()},
            "qualities": list(QUALITIES), "fps": list(FPS), "gif_fps": list(GIF_FPS),
            "audio_kbps": list(AUDIO_KBPS), "scales": list(SCALES), "defaults": DEFAULTS,
            "h264_high_kbps": H264_HIGH_KBPS}
