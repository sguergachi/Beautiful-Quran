"""Where the GPU export's Python lives, and whether it works.

The studio itself runs on the system Python. CuPy (the GPU compositor's
dependency) is installed once into a venv (setup_gpu.sh) that shares the
system packages, and render jobs re-exec into it. No heavy imports here: the
server imports this on every start.
"""
import json
import os
import subprocess
import sys
import threading

VENV = os.environ.get("BQ_PROMO_GPU_VENV", os.path.expanduser("~/.cache/bq-promo/gpu-venv"))
GUARD = "BQ_PROMO_GPU_PYTHON"          # set once we are inside the venv, so exec never loops


def python():
    """The GPU venv's interpreter, or None when it has not been set up."""
    p = os.path.join(VENV, "bin", "python")
    return p if os.path.isfile(p) else None


def reexec_if_needed():
    """Re-run this command under the GPU venv's Python when there is one.
    Called first thing by commands that render; a no-op inside the venv, and
    without the venv (the CPU path then runs as before)."""
    py = python()
    if not py or os.environ.get(GUARD) or os.path.realpath(sys.prefix) == os.path.realpath(VENV):
        return
    os.environ[GUARD] = "1"
    os.execv(py, [py, os.path.abspath(sys.argv[0]), *sys.argv[1:]])


_PROBE = ("import json,sys; sys.path.insert(0, %r); import gpu_compose as G; ok, info = G.available(); "
          "print(json.dumps({'ok': ok, 'info': info, 'h264': ok and G.nvenc_ok('h264_nvenc'), 'hevc': ok and G.nvenc_ok('hevc_nvenc')}))")
_status = None
_lock = threading.Lock()


def status(refresh=False, wait=True):
    """What the editor shows: {available, device, reason, nvenc: [codecs]}.
    Probed in the venv (a few seconds the first time) and remembered. With
    wait=False, a probe still running reads as "checking" instead of blocking."""
    global _status
    if refresh:
        _status = None
    if _status is not None:
        return _status
    if not wait and not _lock.acquire(blocking=False):
        return {"available": False, "device": None, "nvenc": [], "reason": None, "checking": True}
    if wait:
        _lock.acquire()
    try:
        return _probe()
    finally:
        _lock.release()


def _probe():
    global _status
    if _status is not None:
        return _status
    py, here = python(), os.path.dirname(os.path.abspath(__file__))
    if not py:
        _status = {"available": False, "device": None, "nvenc": [],
                   "reason": "GPU export is not set up: run tools/promo_studio/setup_gpu.sh"}
        return _status
    try:
        r = subprocess.run([py, "-c", _PROBE % here], capture_output=True, text=True, timeout=120, cwd=here)
        d = json.loads(r.stdout.strip().splitlines()[-1])
        _status = {"available": d["ok"], "device": d["info"] if d["ok"] else None, "reason": None if d["ok"] else d["info"],
                   "nvenc": [c for c in ("h264", "hevc") if d[c]]}
    except Exception as e:
        _status = {"available": False, "device": None, "nvenc": [], "reason": f"GPU probe failed: {str(e)[:200]}"}
    return _status
