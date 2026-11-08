"""The editor's backend: specs, media, exact stills, emulators and jobs.

Local by default; --host selects a LAN bind address. The browser preview draws
the same scene the compositor renders, from its geometry (`/api/geometry`), so what you
place is what exports; `/api/still` returns the compositor's own frame
when you want to be sure.

Long work (captures, renders, emulator boots) runs as separate processes,
`studio.py job ...`, writing JSON progress lines (report.Report) to a file
the server tails. A job that crashes cannot take the server down, and a
server restart finds jobs that are still running and follows them again.
"""
import errno
import io
import json
import math
import mimetypes
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import audio
import emus
import formats
import gpu_env
import service
import spec_store
import library
import phone as phone_mod
import spec as S
import timeline

APP_DIR = os.path.join(S.HERE, "editor-app")
DIST = os.path.join(APP_DIR, "dist")
JOB_DIR = os.path.join(S.CACHE, "jobs")
SPEC_LOCK = threading.Lock()


def spec_path(name):
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        raise ValueError("names use letters, digits, '.', '-' and '_'")
    return os.path.join(S.SPECS, name + ".json")


def write_spec(name, raw, expect=None, force=False):
    """Save a spec through spec_store: atomic and fsynced, refused when it was
    made against an out-of-date revision, with the replaced version kept in the
    history. Returns the new revision."""
    return spec_store.write(name, raw, expect=expect, force=force)


def render_scale(value):
    scale = float(value)
    if not math.isfinite(scale) or not 0.05 <= scale <= 2:
        raise ValueError("scale must be 0.05..2")
    return scale


def take_meta(s):
    p = os.path.join(S.take_dir(s), "meta.json")
    return json.load(open(p)) if os.path.exists(p) else None


def editor_timeline(s):
    """Timeline for a saved spec or a live editor draft, on its take clock."""
    meta = take_meta(s)
    if meta:
        s["app"]["seconds"] = max(s["app"]["seconds"], max(0, meta["length"] - meta["lead"]))
    tl = timeline.build(s, meta["lead"] if meta else S.LEAD)
    return {"verses": tl["verses"], "repeats": tl["repeats"], "take": meta["length"] if meta else None}


EXPORT_EXT = "|".join(sorted({f["ext"] for f in formats.FORMATS.values()}))


def export_pattern(name):
    return re.compile(rf"{re.escape(name)}(-\d+p)?(-\d+fps)?\.({EXPORT_EXT})")


def export_revision(st):
    return f"{st.st_ino}:{st.st_mtime_ns}:{st.st_size}"


class ExportConflict(ValueError):
    """A displayed export changed, or an active render still owns its path."""


def exports(name):
    """A video's exported files (formats.filename), newest first."""
    pat = export_pattern(name)
    try:
        files = [f for f in os.listdir(S.OUT) if pat.fullmatch(f)]
    except FileNotFoundError:
        return []
    out = []
    for f in files:
        try:
            st = os.stat(os.path.join(S.OUT, f))
        except FileNotFoundError:
            continue
        if not os.path.isfile(os.path.join(S.OUT, f)): continue
        out.append({"file": f, "bytes": st.st_size, "mtime": st.st_mtime, "revision": export_revision(st)})
    return sorted(out, key=lambda e: -e["mtime"])


def spec_status(name):
    try:
        s = S.load(name)
    except Exception as e:                       # a broken spec still lists
        return {"name": name, "error": str(e)}
    meta = take_meta(s)
    files = exports(name)
    length = meta["length"] if meta else 0
    return {"name": name, "title": s.get("title", ""), "aspect": s["aspect"], "fps": s["fps"], "app": s["app"],
            "duration": s["duration"], "take": bool(meta), "take_length": length or None,
            "take_build": emus.build_check((meta or {}).get("build"))["state"] if meta else None,
            "take_short": bool(length) and S.take_needed(s) > length + 0.05, "video": bool(files),
            "video_mtime": files[0]["mtime"] if files else None, "exports": files}


PEAK_RATE = 100     # waveform columns per take second


def take_peaks(key):
    """The take's waveform for the timeline's Sound lane, as JSON: the
    loudest sample of each 10 ms, 0..255 on a square-root scale so quiet
    recitation still shows. Cached beside audio.wav."""
    d = os.path.join(S.CACHE, "takes", key)
    wav, out = os.path.join(d, "audio.wav"), os.path.join(d, "peaks.json")
    if not os.path.isfile(wav):
        return None
    if os.path.exists(out) and os.path.getmtime(out) >= os.path.getmtime(wav):
        return open(out, "rb").read()
    import wave
    import numpy as np
    with wave.open(wav) as w:
        if w.getsampwidth() != 2:
            raise ValueError("audio.wav is not 16-bit PCM")
        ch, step = w.getnchannels(), max(1, w.getframerate() // PEAK_RATE)
        x = np.abs(np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.int32)).reshape(-1, ch).max(axis=1)
    n = -(-len(x) // step)
    x = np.pad(x, (0, n * step - len(x))).reshape(n, step).max(axis=1)
    body = json.dumps({"rate": PEAK_RATE, "peaks": np.round(np.sqrt(x / 32768.0) * 255).astype(int).tolist()},
                      separators=(",", ":")).encode()
    tmp = f"{out}.{os.getpid()}.{threading.get_ident()}.tmp"
    open(tmp, "wb").write(body)
    os.replace(tmp, out)
    return body


def geometry(s):
    """Everything the browser needs to draw the scene like compose.Renderer."""
    W, H = S.size(s)
    meta = take_meta(s)
    sw, sh = (meta["width"], meta["height"]) if meta else (1080, 2400)
    fp, mp, g = phone_mod.ensure(os.path.join(S.CACHE, "phone"), sw, sh, s["phone"]["color"], (meta or {}).get('display'))
    from compose import plate_settings
    pw, ph, pk = library.plate_geometry(plate_settings(s), W, H)
    key = S.take_key(s)
    revision = str(os.stat(os.path.join(S.take_dir(s), "meta.json")).st_mtime_ns) if meta else ""
    heal = ""
    if meta:
        try:                      # the preview plays the repaired sound the export will use; a new URL once it is repaired
            rec = audio.heal_wav(os.path.join(S.take_dir(s), "audio.wav"))
            heal = f"-{rec['mtime_ns']}" if rec and rec["dropouts"] else ""
        except Exception as e:
            print(f"audio repair skipped: {e}", flush=True)
    return {"W": W, "H": H, "fps": s["fps"], "g": g, "take": meta,
            "build": emus.build_check(meta.get("build")) if meta else None,
            "frame": "/media/phone/" + os.path.basename(fp),
            "clip": f"/media/take/{key}/clip.mp4?v={revision}" if meta else None,
            "audio": f"/media/take/{key}/audio.wav?v={revision}{heal}" if meta else None,
            "peaks": f"/media/take/{key}/peaks.json?v={revision}" if meta else None,
            "plate": {"w": pw, "h": ph, "k": pk}}


# ---------------------------------------------------------------- jobs

class Jobs:
    """Subprocess jobs with stages, progress, logs and cancellation.

    Rules: one capture per emulator/shared take, one render at a time.
    Recording and export share the host budget and do not overlap. Queued
    jobs start as soon as their resource is free.
    """

    def __init__(self):
        self.lock = threading.RLock()
        self.jobs = {}
        os.makedirs(JOB_DIR, exist_ok=True)
        self._load()
        threading.Thread(target=self._loop, daemon=True).start()

    # -- persistence ------------------------------------------------------

    def _save(self):
        with self.lock:
            keep = sorted(self.jobs.values(), key=lambda j: -j["created"])[:60]
            data = [{k: v for k, v in j.items() if not k.startswith("_")} for j in keep]
            tmp = os.path.join(JOB_DIR, "jobs.json.part")
            with open(tmp, "w") as f:
                json.dump(data, f)
            os.replace(tmp, os.path.join(JOB_DIR, "jobs.json"))

    def _load(self):
        try:
            with open(os.path.join(JOB_DIR, "jobs.json")) as f:
                saved = json.load(f)
            for j in saved:
                if j["state"] == "running":
                    # Replay even if it finished while the server was down.
                    j.update(_offset=0, stage=None, stages=[], log=[], result=None, error=None)
                self.jobs[j["id"]] = j
        except (OSError, ValueError, KeyError):
            pass

    # -- public -----------------------------------------------------------

    def list(self):
        with self.lock:
            return [self.public(j) for j in sorted(self.jobs.values(), key=lambda j: -j["created"])[:40]]

    def public(self, j, full=False):
        d = {k: v for k, v in j.items() if not k.startswith("_")}
        if j["kind"] == "render" and j["state"] == "done" and isinstance(j.get("result"), str):
            d["export_exists"] = os.path.isfile(j["result"])
        if not full:
            d["log"] = d["log"][-6:]
        return d

    def get(self, jid):
        with self.lock:
            return self.jobs.get(jid)

    def busy_serials(self):
        with self.lock:
            return {j["serial"]: j for j in self.jobs.values() if j.get("serial") and j["state"] in ("queued", "running")}

    def submit(self, kind, name, args, serial=None, title=None, snapshot=None, export_file=None):
        with self.lock:
            for j in self.jobs.values():
                if j["kind"] == kind and j["name"] == name and j["state"] in ("queued", "running"):
                    raise ValueError(f"{title or kind} for {name} is already {j['state']}")
            jid = f"{kind}-{re.sub(r'[^A-Za-z0-9_-]', '_', name)}-{int(time.time() * 1000)}"
            j = {"id": jid, "kind": kind, "name": name, "title": title or kind, "args": args, "serial": serial,
                 "state": "queued", "stage": None, "stages": [], "progress": 0.0, "msg": "Waiting",
                 "log": [], "created": time.time(), "started": None, "ended": None, "result": None,
                 "error": None, "pid": None, "file": os.path.join(JOB_DIR, jid + ".jsonl")}
            if export_file is not None: j["export_file"] = export_file
            if snapshot is not None:
                folder = os.path.join(JOB_DIR, jid)
                os.makedirs(folder, exist_ok=True)
                path = os.path.join(folder, name + ".json")
                with open(path, "w") as f:
                    json.dump(snapshot, f)
                j["args"] = [args[0], path, *args[2:]]
                j["take_key"] = S.take_key(snapshot)
            self.jobs[jid] = j
        self._save()
        self._pump()
        return self.public(j)

    def delete_export(self, name, filename, revision):
        """Delete one listed file, excluding active or replaced versions."""
        if not export_pattern(name).fullmatch(filename):
            raise ValueError("This file is not an export of this video")
        with self.lock:    # a render cannot start between this check and unlink
            if any(j['kind'] == 'render' and j['state'] in ('queued', 'running') and
                   (j.get('export_file') == filename if j.get('export_file') else j['name'] == name)
                   for j in self.jobs.values()):
                raise ExportConflict("This file is queued or rendering. Wait for the export to finish.")
            path = os.path.join(S.OUT, filename)
            st = os.stat(path)
            if not isinstance(revision, str) or revision != export_revision(st):
                raise ExportConflict("This export was replaced. Refresh the list before deleting it.")
            os.unlink(path)

    def cancel(self, jid):
        with self.lock:
            j = self.jobs.get(jid)
            if not j:
                raise ValueError("no such job")
            was_running = j["state"] == "running"
            if j["state"] == "queued":
                j["state"], j["ended"], j["msg"] = "cancelled", time.time(), "Cancelled"
            elif j["state"] == "running" and j.get("pid"):
                j["_cancel"] = True
                try:
                    os.killpg(j["pid"], signal.SIGTERM)       # the job and its scrcpy/ffmpeg children
                except ProcessLookupError:
                    pass
        if was_running and j.get("serial") and j["kind"] == "capture":
            emus.adb(j["serial"], "shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
        if was_running and j["kind"] == "emu-boot":
            emus.stop(j["name"])
        self._save()
        return self.public(j)

    def clear_finished(self):
        with self.lock:
            for jid in [k for k, j in self.jobs.items() if j["state"] not in ("queued", "running")]:
                del self.jobs[jid]
        self._save()

    # -- scheduling -------------------------------------------------------

    def _can_start(self, j, running):
        # Recording must keep the emulator's cadence. Exporting on the same
        # host saturated CPU/memory and dropped capture/preview frames.
        # A Gradle build is the same kind of load, so it waits its turn too.
        BUILD = ("render", "apk-build")
        if j["kind"] in ("capture", *BUILD) and any({r["kind"], j["kind"]} in ({"capture", "render"}, {"capture", "apk-build"}) for r in running):
            return False
        if j.get("take_key") and any(r.get("take_key") == j["take_key"] and "capture" in (j["kind"], r["kind"]) for r in running):
            return False
        if j["kind"] in BUILD:
            return not any(r["kind"] in BUILD for r in running)
        if j.get("serial"):
            return not any(r.get("serial") == j["serial"] for r in running)
        return True

    def _pump(self):
        with self.lock:
            started = False
            running = [j for j in self.jobs.values() if j["state"] == "running"]
            for j in sorted((j for j in self.jobs.values() if j["state"] == "queued"), key=lambda j: j["created"]):
                if self._can_start(j, running):
                    self._start(j)
                    running.append(j)
                    started = True
                elif j["kind"] in ("capture", "render", "apk-build"):
                    kinds = {r["kind"] for r in running}
                    j["msg"] = "Waiting for recording to finish" if "capture" in kinds else "Waiting for a build to finish" if kinds & {"render", "apk-build"} else "Waiting for emulator to be ready"
            if started:
                self._save()

    def _start(self, j):
        env = dict(os.environ, PYTHONUNBUFFERED="1")
        if j.get("serial"):
            env["ANDROID_SERIAL"] = j["serial"]
        out = open(j["file"], "w")
        p = subprocess.Popen([sys.executable, os.path.join(S.HERE, "studio.py"), "job", *j["args"]],
                             cwd=S.HERE, env=env, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        out.close()
        j.update(state="running", started=time.time(), pid=p.pid, msg="Starting", _proc=p, _offset=0)

    def _loop(self):
        self._pump()
        while True:
            try:
                changed = False
                with self.lock:
                    running = [j for j in self.jobs.values() if j["state"] == "running"]
                for j in running:
                    changed |= self._follow(j)
                if changed:
                    self._save()
                    self._pump()
            except Exception as e:                   # the scheduler must outlive any one job
                print("jobs loop:", e, file=sys.stderr)
            time.sleep(0.3)

    def _follow(self, j):
        with self.lock:
            return self._read_job(j)

    def _read_job(self, j):
        changed = False
        try:
            with open(j["file"]) as f:
                f.seek(j.get("_offset", 0))
                data = f.read()
                # keep a partial last line for the next read
                cut = data.rfind("\n") + 1
                j["_offset"] = j.get("_offset", 0) + len(data[:cut].encode())
                lines = data[:cut].splitlines()
        except OSError:
            lines = []
        for line in lines:
            changed = True
            try:
                ev = json.loads(line)
            except ValueError:
                ev = {"type": "log", "msg": line}
            t = ev.get("type")
            if t == "stage":
                j["stage"] = {"key": ev["key"], "msg": ev.get("msg")}
                j["stages"].append({"key": ev["key"], "msg": ev.get("msg"), "at": time.time()})
                j["msg"] = ev.get("msg") or ev["key"]
            elif t == "progress":
                j["progress"] = ev["value"]
                if ev.get("msg"):
                    j["msg"] = ev["msg"]
            elif t == "result":
                j["result"] = ev.get("value")
            elif t == "error":
                j["error"] = ev.get("msg")
            if t in ("log", "error") or (t == "progress" and ev.get("msg") and not j["log"][-1:] == [ev["msg"]]):
                j["log"].append(ev.get("msg") if t != "progress" else ev["msg"])
                j["log"] = j["log"][-300:]
        proc = j.get("_proc")
        code = proc.poll() if proc else (None if _alive(j["pid"]) else (0 if j.get("result") is not None else -1))
        if code is not None:
            j["ended"] = time.time()
            if j.get("_cancel"):
                j["state"], j["msg"] = "cancelled", "Cancelled"
            elif code == 0 and j.get("error") is None:
                j["state"], j["progress"], j["msg"] = "done", 1.0, "Done"
            else:
                j["state"] = "error"
                j["error"] = j.get("error") or (j["log"][-1] if j["log"] else f"exited with code {code}")
                j["msg"] = j["error"].splitlines()[0][:200]
            changed = True
        return changed


def _alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except (ProcessLookupError, TypeError):
        return False
    except PermissionError:
        return True


JOBS = None
_EMU_CACHE = {"at": 0, "list": []}


def emulator_list(fresh=False):
    if fresh or time.monotonic() - _EMU_CACHE["at"] > 2.0:
        _EMU_CACHE.update(at=time.monotonic(), list=emus.list_all())
    busy = JOBS.busy_serials()
    out = []
    for d in _EMU_CACHE["list"]:
        d = dict(d)
        j = busy.get(d["serial"])
        d["job"] = JOBS.public(j) if j else None
        d["ready"] = d["kind"] == "capture" and d["booted"] and d["app"] and not j
        out.append(d)
    booting = [JOBS.public(j) for j in JOBS.jobs.values() if j["kind"] == "emu-boot" and j["state"] in ("queued", "running")]
    return {"emulators": out, "booting": booting}


def pick_emulator(requested=None):
    """The emulator a capture records on: the one asked for, else a ready idle one."""
    lst = [d for d in emulator_list(fresh=True)["emulators"] if d["kind"] == "capture"]
    if requested:
        d = next((d for d in lst if d["serial"] == requested), None)
        if not d:
            raise ValueError(f"{requested} is not running")
        if not d["app"]:
            raise ValueError(f"the app is not installed on {requested}: prepare it first")
        return requested                         # busy: the job queues behind its current one
    ready = [d for d in lst if d["ready"]]
    ready.sort(key=lambda d: (d["kind"] != "capture", -(d["cores"] or 0)))
    if ready:
        return ready[0]["serial"]
    if any(d["booted"] and d["app"] for d in lst):
        busy = [d for d in lst if d["booted"] and d["app"]]
        return min(busy, key=lambda d: d["port"])["serial"]     # queue behind the first busy one
    raise ValueError("no emulator with the app installed: start one and prepare it in the Emulators panel")


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "PromoStudio/2"

    def log_message(self, fmt, *args):
        pass

    def handle_error(self, request, client_address):
        pass                                     # a dropped connection is not the server's problem

    # -- helpers -----------------------------------------------------------

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send_bytes(self, body, ctype, cache="no-store"):
        try:
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", cache)
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send_file(self, path, ctype=None, cache="no-cache", download=False):
        if not os.path.isfile(path):
            return self.send_json({"error": "not found"}, 404)
        size = os.path.getsize(path)
        ctype = ctype or mimetypes.guess_type(path)[0] or "application/octet-stream"
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        try:
            if rng:
                m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng)
                if not m or not any(m.groups()):
                    return self.send_error(416)
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                else:
                    start = max(0, size - int(m.group(2)))
                end = min(end, size - 1)
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Cache-Control", cache)
            if path.endswith("sw.js"):
                self.send_header("Service-Worker-Allowed", "/")
            if download:
                self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(path)}"')
            self.end_headers()
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(1 << 20, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def local_request(self):
        origin = self.headers.get("Origin")
        if origin and origin not in (f"http://{self.headers.get('Host')}", "http://127.0.0.1:5173", "http://localhost:5173"):
            self.send_json({"error": "cross-origin changes are not allowed"}, 403)
            return False
        return True

    def body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            n = 0
        if n <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            raise ValueError("the request body is not JSON")

    def _wrap(self, fn):
        try:
            fn()
        except FileNotFoundError as e:
            self.send_json({"error": str(e)}, 404)
        except ExportConflict as e:
            self.send_json({"error": str(e)}, 409)
        except Exception as e:
            self.send_json({"error": str(e) or e.__class__.__name__}, 400 if isinstance(e, ValueError) else 500)

    def do_GET(self):
        self._wrap(self.route_get)

    def do_PUT(self):
        if self.local_request():
            self._wrap(self.route_put)

    def do_DELETE(self):
        if self.local_request():
            self._wrap(self.route_delete)

    def do_POST(self):
        if self.local_request():
            self._wrap(self.route_post)

    # -- routes ------------------------------------------------------------

    def route_put(self):
        m = re.fullmatch(r"/api/spec/([\w.-]+)", self.path)
        if not m:
            return self.send_json({"error": "not found"}, 404)
        name = m.group(1)
        if int(self.headers.get("Content-Length") or 0) <= 0:
            raise ValueError("an empty save was refused")           # never turn a video into defaults by accident
        body = self.body()
        if not isinstance(body, dict) or (not body and spec_store.current_revision(name)):
            raise ValueError("refusing to replace a video with an empty one")
        raw = S.migrate(body)                            # an old editor tab may still send v1
        S.resolve(raw)                                   # validate before saving
        try:
            rev = write_spec(name, raw, expect=self.headers.get("If-Match") or None, force=self.headers.get("X-Force") == "1")
        except spec_store.SpecConflict as c:
            return self.send_json({"error": str(c), "conflict": True, "rev": c.rev, "raw": c.raw}, 409)
        self.send_json({"ok": True, "rev": rev, "status": spec_status(name)})

    def route_delete(self):
        m = re.fullmatch(r"/api/exports/([\w.-]+)/([\w.-]+)", urllib.parse.unquote(self.path))
        if m:
            name, filename = m.groups()
            spec_path(name)    # apply the same video-name constraints as saving
            JOBS.delete_export(name, filename, self.body().get('revision'))
            return self.send_json({"ok": True, "exports": exports(name)})
        m = re.fullmatch(r"/api/spec/([\w.-]+)", self.path)
        if not m:
            return self.send_json({"error": "not found"}, 404)
        spec_path(m.group(1))
        if os.path.exists(spec_path(m.group(1))):
            spec_store.delete(m.group(1))          # kept in the history: restore it from there
        self.send_json({"ok": True})

    def route_get(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        p = u.path
        if not p.startswith(("/api/", "/media/")):
            return self.static(p)
        if p == "/api/meta":
            bgs = library.catalog()
            return self.send_json({
                "reciters": {k: v["name"] for k, v in S.RECITERS.items()},
                "themes": S.THEMES, "layouts": S.LAYOUTS, "views": S.VIEWS,
                "aspects": {k: list(v) for k, v in S.ASPECTS.items()}, "eases": S.EASES,
                "phones": sorted(phone_mod.COLORS), "defaults": S.DEFAULTS, "props": S.PROPS,
                "backgrounds": {k: {"title": v["title"], "label": v.get("label", v["title"]), "suits": v["suits"], "thumb": f"/media/thumb/{k}.jpg"}
                                for k, v in bgs.items()},
                "surahs": surahs(), "out_dir": S.OUT, "export": {**formats.meta(), "gpu": gpu_env.status(wait=False)}, "apk": emus.DEFAULT_APK,
                "apk_exists": os.path.exists(emus.DEFAULT_APK), "apk_info": emus.apk_info()})
        if p == "/api/health":
            return self.send_json({"ok": True, "jobs": len(JOBS.jobs)})
        if p == "/api/apk":
            return self.send_json(emus.apk_info())
        if p == "/api/specs":
            names = sorted(f[:-5] for f in os.listdir(S.SPECS) if f.endswith(".json"))
            return self.send_json([spec_status(n) for n in names])
        m = re.fullmatch(r"/api/spec/([\w.-]+)", p)
        if m:
            raw, rev = spec_store.read(m.group(1))
            return self.send_json({"raw": raw, "rev": rev, "status": spec_status(m.group(1))})
        m = re.fullmatch(r"/api/spec/([\w.-]+)/history", p)
        if m:
            return self.send_json({"versions": spec_store.history(m.group(1)), "rev": spec_store.current_revision(m.group(1))})
        if p == "/api/trash":
            return self.send_json({"deleted": spec_store.deleted()})
        m = re.fullmatch(r"/api/geometry/([\w.-]+)", p)
        if m:
            return self.send_json(geometry(S.load(m.group(1))))
        m = re.fullmatch(r"/api/timeline/([\w.-]+)", p)
        if m:
            return self.send_json(editor_timeline(S.load(m.group(1))))
        m = re.fullmatch(r"/api/still/([\w.-]+)", p)
        if m:
            import compose
            from PIL import Image
            s = S.load(m.group(1))
            t = float(q.get("t", ["0"])[0])
            if not math.isfinite(t):
                raise ValueError("time must be finite")
            t = min(max(0, t), max(0, s["duration"] - 1 / s["fps"]))
            img = Image.fromarray(compose.still(s, t, render_scale(q.get("scale", ["0.5"])[0])))
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=90)
            return self.send_bytes(buf.getvalue(), "image/jpeg")
        if p == "/api/jobs":
            return self.send_json(JOBS.list())
        m = re.fullmatch(r"/api/jobs/([\w.-]+)", p)
        if m:
            j = JOBS.get(m.group(1))
            return self.send_json(JOBS.public(j, full=True) if j else {"error": "no such job"}, 200 if j else 404)
        if p == "/api/emus":
            return self.send_json(emulator_list(fresh="fresh" in q))
        m = re.fullmatch(r"/api/emus/(emulator-\d+)/screen\.jpg", p)
        if m:
            return self.send_bytes(emus.screenshot(m.group(1)), "image/jpeg")
        m = re.fullmatch(r"/media/take/([\w.-]+)/peaks\.json", p)
        if m:
            body = take_peaks(m.group(1))
            return self.send_bytes(body, "application/json", cache="no-cache") if body else self.send_json({"error": "no take"}, 404)
        m = re.fullmatch(r"/media/take/([\w.-]+)/(clip\.mp4|audio\.wav)", p)
        if m:
            return self.send_file(os.path.join(S.CACHE, "takes", m.group(1), m.group(2)))
        m = re.fullmatch(r"/media/phone/([\w.-]+\.png)", p)
        if m:
            return self.send_file(os.path.join(S.CACHE, "phone", m.group(1)), cache="max-age=86400")
        m = re.fullmatch(r"/media/plate/([\w.-]+\.mp4)", p)
        if m:
            return self.send_file(os.path.join(library.PLATES, m.group(1)), cache="max-age=86400")
        m = re.fullmatch(rf"/media/out/([\w.-]+\.(?:{EXPORT_EXT}))", p)
        if m:
            return self.send_file(os.path.join(S.OUT, m.group(1)), download="download" in q)
        m = re.fullmatch(r"/media/thumb/([\w.-]+)\.jpg", p)
        if m:
            return self.send_file(thumb(m.group(1)), "image/jpeg", cache="max-age=86400")
        if p == "/media/icon.png":
            return self.send_file(os.path.join(S.REPO, "docs", "play_store_icon.png"), cache="max-age=86400")
        m = re.fullmatch(r"/media/fonts/([\w.-]+)", p)
        if m:
            return self.send_file(os.path.join(S.REPO, "web", "public", "fonts", m.group(1)), cache="max-age=86400")
        self.send_json({"error": "not found"}, 404)

    def static(self, p):
        """The built editor (editor-app/dist); every unknown path is the app."""
        if not os.path.isdir(DIST):
            body = (b"<h1>The editor is not built</h1><p>Run <code>studio.py serve</code> (it builds on start) "
                    b"or <code>npm --prefix tools/promo_studio/editor-app run build</code>.</p>")
            return self.send_bytes(body, "text/html; charset=utf-8")
        f = os.path.normpath(os.path.join(DIST, p.lstrip("/")))
        if os.path.commonpath([f, DIST]) != DIST:
            return self.send_json({"error": "forbidden"}, 403)
        if os.path.isfile(f):
            # hashed assets never change; index.html always revalidates
            return self.send_file(f, cache="max-age=31536000, immutable" if "/assets/" in p else "no-cache")
        return self.send_file(os.path.join(DIST, "index.html"), "text/html; charset=utf-8")

    def route_post(self):
        p = urllib.parse.urlparse(self.path).path
        if re.fullmatch(r"/api/timeline/[\w.-]+", p):
            return self.send_json(editor_timeline(S.resolve(self.body())))
        if re.fullmatch(r"/api/verse-range/[\w.-]+", p):
            body = self.body()
            s = S.resolve(body.get("raw", {}))
            contents = body.get("take", False)
            if not isinstance(contents, bool): raise ValueError("take must be a boolean")
            target = S.resolve({**s, "app": {**s["app"], "ayah": body.get("start")}}) if contents else s
            meta = take_meta(target)
            lead = meta["lead"] if meta else S.opening_estimate(s)
            return self.send_json(timeline.plan_range(s, body.get("start"), body.get("end"), lead, take=contents))
        m = re.fullmatch(r"/api/plate/([\w.-]+)", p)
        if m:
            # plate for the (possibly unsaved) background settings in the body
            from compose import plate_settings
            s = S.resolve(self.body())
            W, H = S.size(s)
            bg = plate_settings(s)
            path = library.plate(bg, W, H)
            pw, ph, pk = library.plate_geometry(bg, W, H)
            return self.send_json({"url": "/media/plate/" + os.path.basename(path), "w": pw, "h": ph, "k": pk,
                                   "blur": bg["blur"]})
        m = re.fullmatch(r"/api/spec/([\w.-]+)/restore", p)
        if m:
            raw, rev = spec_store.restore(m.group(1), self.body().get("id"))
            return self.send_json({"ok": True, "raw": raw, "rev": rev, "status": spec_status(m.group(1))})
        m = re.fullmatch(r"/api/spec/([\w.-]+)/stash", p)
        if m:                                           # keep a version that was not saved (a draft, another tab's edits) in the history only
            body = self.body()
            reason = body.get("reason") if body.get("reason") in ("unsaved", "replaced") else "unsaved"
            return self.send_json({"ok": True, "id": spec_store.stash(m.group(1), S.migrate(body.get("raw") or {}), reason)})
        m = re.fullmatch(r"/api/copy/([\w.-]+)/([\w.-]+)", p)
        if m:
            spec_path(m.group(1)), spec_path(m.group(2))
            try:
                spec_store.duplicate(m.group(1), m.group(2))
            except FileExistsError:
                return self.send_json({"error": f"{m.group(2)} already exists"}, 409)
            return self.send_json({"ok": True})
        m = re.fullmatch(r"/api/rename/([\w.-]+)/([\w.-]+)", p)
        if m:
            spec_path(m.group(1)), spec_path(m.group(2))
            try:
                spec_store.rename(m.group(1), m.group(2))
            except FileExistsError:
                return self.send_json({"error": f"{m.group(2)} already exists"}, 409)
            for e in exports(m.group(1)):                # every export follows its video
                renamed = m.group(2) + e["file"][len(m.group(1)):]
                if not os.path.exists(os.path.join(S.OUT, renamed)):
                    os.rename(os.path.join(S.OUT, e["file"]), os.path.join(S.OUT, renamed))
            return self.send_json({"ok": True})
        m = re.fullmatch(r"/api/new/([\w.-]+)", p)
        if m:
            dst = spec_path(m.group(1))
            body = self.body()
            base, title = body.get("from"), body.get("title")
            if title is not None and (not isinstance(title, str) or not title.strip()):
                raise ValueError("Give the video a title")
            raw = {"title": m.group(1).replace("-", " ")}
            if base:
                raw, _ = spec_store.read(base)
            if title is not None: raw["title"] = title.strip()
            try:
                spec_store.create(m.group(1), raw)
            except FileExistsError:
                return self.send_json({"error": f"{m.group(1)} already exists"}, 409)
            return self.send_json({"ok": True})
        m = re.fullmatch(r"/api/capture/([\w.-]+)", p)
        if m:
            name, opts = m.group(1), self.body()
            snapshot = S.load(name)                       # freeze the submitted edit
            serial = pick_emulator(opts.get("serial"))
            args = ["capture", name] + (["--force"] if opts.get("force") else [])
            return self.send_json(JOBS.submit("capture", name, args, serial=serial, title="Record take", snapshot=snapshot))
        m = re.fullmatch(r"/api/render/([\w.-]+)", p)
        if m:
            name, opts = m.group(1), self.body()
            snapshot = S.load(name)
            o = formats.options(opts)
            return self.send_json(JOBS.submit("render", name, ["render", name, "--options", json.dumps(o)],
                                              title=f"Export · {formats.describe(snapshot, o)}", snapshot=snapshot,
                                              export_file=formats.filename(snapshot, o)))
        m = re.fullmatch(r"/api/jobs/([\w.-]+)/cancel", p)
        if m:
            return self.send_json(JOBS.cancel(m.group(1)))
        if p == "/api/jobs/clear":
            JOBS.clear_finished()
            return self.send_json({"ok": True})
        if p == "/api/emus/boot":
            opts = self.body()
            cores = int(opts.get("cores", 6))
            if not 1 <= cores <= 8:
                raise ValueError("cores must be 1-8")
            with JOBS.lock:
                port = emus.next_port()
                taken = {j["name"] for j in JOBS.jobs.values() if j["kind"] == "emu-boot" and j["state"] in ("queued", "running")}
                while f"emulator-{port}" in taken or not emus._port_free(port) or not emus._port_free(port + 1):
                    port += 2
                if port > 5688:
                    raise ValueError("no free capture emulator port")
                name = f"emulator-{port}"
                j = JOBS.submit("emu-boot", name, ["emu-boot", "--cores", str(cores), "--port", str(port)], serial=name, title="Start emulator")
            _EMU_CACHE["at"] = 0
            return self.send_json(j)
        m = re.fullmatch(r"/api/emus/(emulator-\d+)/prepare", p)
        if m:
            apk = self.body().get("apk") or emus.DEFAULT_APK
            return self.send_json(JOBS.submit("emu-prepare", m.group(1), ["emu-prepare", m.group(1), "--apk", apk],
                                              serial=m.group(1), title="Prepare app"))
        m = re.fullmatch(r"/api/emus/(emulator-\d+)/stop", p)
        if m:
            if m.group(1) in JOBS.busy_serials():
                raise ValueError(f"{m.group(1)} is busy; cancel its job first")
            emus.stop(m.group(1))
            _EMU_CACHE["at"] = 0
            return self.send_json({"ok": True})
        if p == "/api/apk/build":
            return self.send_json(JOBS.submit("apk-build", "release APK", ["apk-build"], title="Build APK"))
        self.send_json({"error": "not found"}, 404)


_SURAHS = None


def surahs():
    global _SURAHS
    if _SURAHS is None:
        import sqlite3
        db = sqlite3.connect(os.path.join(S.REPO, "data", "quran.db"))
        try:
            _SURAHS = [{"id": i, "name": n, "ayahs": c}
                       for i, n, c in db.execute("select id, name_transliteration, ayah_count from surahs order by id")]
        finally:
            db.close()
    return _SURAHS


def thumb(bg_id):
    d = os.path.join(S.CACHE, "thumbs")
    os.makedirs(d, exist_ok=True)
    out = os.path.join(d, bg_id + ".jpg")
    if not os.path.exists(out):
        src = library.source(bg_id)
        start = float(library.catalog().get(bg_id, {}).get("start", 0)) + 1
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(start), "-i", src, "-frames:v", "1",
                        "-vf", "scale=240:240:force_original_aspect_ratio=increase,crop=240:240", out], check=True)
    return out


def ensure_built(force=False):
    """Build the editor when its sources are newer than the last build. A build
    that fails (a half-saved source, no network for `npm install`) never stops
    the server: the last good build is served and the problem is printed."""
    index = os.path.join(DIST, "index.html")
    src = [os.path.join(r, f) for r, _, fs in os.walk(os.path.join(APP_DIR, "src")) for f in fs]
    if os.path.isdir(os.path.join(APP_DIR, "public")):
        src += [os.path.join(r, f) for r, _, fs in os.walk(os.path.join(APP_DIR, "public")) for f in fs]
    src += [os.path.join(APP_DIR, f) for f in ("index.html", "package.json", "vite.config.ts")]
    newest = max((os.path.getmtime(f) for f in src if os.path.exists(f)), default=0)
    if not force and os.path.exists(index) and os.path.getmtime(index) >= newest:
        return
    if not shutil.which("npm"):
        print("warning: npm not found; the editor cannot be built", file=sys.stderr)
        return
    try:
        if not os.path.isdir(os.path.join(APP_DIR, "node_modules")):
            print("installing the editor's dependencies…", flush=True)
            subprocess.run(["npm", "install", "--no-audit", "--no-fund"], cwd=APP_DIR, check=True, timeout=600)
        print("building the editor…", flush=True)
        subprocess.run(["npm", "run", "build"], cwd=APP_DIR, check=True, timeout=600)
    except (subprocess.SubprocessError, OSError) as e:
        if not os.path.exists(index):
            raise
        print(f"warning: the editor did not build ({e}); serving the last build", file=sys.stderr, flush=True)


def bind(host, port, patience=30.0):
    """The HTTP server on host:port, waiting up to `patience` s for the port to
    free up (a previous instance still closing) instead of dying on the spot."""
    t0 = time.monotonic()
    while True:
        try:
            return ThreadingHTTPServer((host, port), Handler)
        except OSError as e:
            if e.errno != errno.EADDRINUSE or time.monotonic() - t0 > patience:
                raise
            time.sleep(0.5)


def serve(port=8765, build=True, host="127.0.0.1"):
    """Run the studio. Built not to die: a hangup (the launching terminal or
    session closing) and a broken pipe are ignored, a failed build serves the
    last good one, a busy port is waited for, an error that escapes the HTTP loop
    restarts the loop, and under systemd (service.py) a hung server is restarted."""
    for sig in (getattr(signal, "SIGHUP", None), getattr(signal, "SIGPIPE", None)):
        if sig is not None:
            signal.signal(sig, signal.SIG_IGN)
    threading.Thread(target=gpu_env.status, daemon=True).start()     # probe the GPU once, off the request path
    global JOBS
    os.makedirs(S.SPECS, exist_ok=True)
    if build:
        ensure_built()
    for name, version in spec_store.recover(lambda m: print(m, flush=True)):    # a broken spec is put back before anything reads it
        pass
    JOBS = Jobs()
    httpd = bind(host, port)
    httpd.daemon_threads = True
    print(f"promo studio: http://{host}:{port}/  (specs in {S.SPECS}, videos to {S.OUT})", flush=True)
    service.notify("READY=1")
    service.start_watchdog(host, port)
    try:
        failures = 0
        while True:
            try:
                httpd.serve_forever()
                break
            except KeyboardInterrupt:
                break
            except Exception as e:                       # the loop itself failing: log it and keep serving
                failures += 1
                print(f"server loop error ({failures}): {e!r}", file=sys.stderr, flush=True)
                if failures > 20:
                    raise                                # something is badly wrong: let the supervisor restart us
                time.sleep(min(5.0, 0.2 * failures))
    finally:
        service.notify("STOPPING=1")
        httpd.server_close()
