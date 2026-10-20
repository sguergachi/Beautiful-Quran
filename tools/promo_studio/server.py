"""The editor's backend: specs, media, exact stills and background jobs.

Local only (binds 127.0.0.1). The browser preview draws the same scene the
compositor renders, from the same geometry (`/api/geometry`), so what you
place is what exports; `/api/still` returns the compositor's own frame
when you want to be sure.
"""
import io
from contextlib import nullcontext
import json
import mimetypes
import os
import re
import shutil
import threading
import tempfile
import math
import time
import traceback
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import library
import phone as phone_mod
import spec as S
import timeline

EDITOR = os.path.join(S.HERE, "editor")
JOBS = {}
JOB_LOCK = threading.Lock()
SPEC_LOCK = threading.Lock()
RENDER_LOCK = threading.Lock()       # export-all queues, rather than multiplying worker pools
CAPTURE_LOCK = threading.Lock()      # one emulator: one take at a time


def spec_path(name):
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        raise ValueError("bad spec name")
    return os.path.join(S.SPECS, name + ".json")


def write_spec(name, raw):
    """Readers see either complete revision, never a half-written JSON file."""
    path = spec_path(name)
    with SPEC_LOCK:
        tmp = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", dir=S.SPECS, suffix=".json.part", delete=False) as f:
                tmp = f.name
                json.dump(raw, f, indent=2, ensure_ascii=False, allow_nan=False)
                f.write("\n")
            os.replace(tmp, path)
        finally:
            if tmp and os.path.exists(tmp): os.remove(tmp)


def render_scale(value):
    scale = float(value)
    if not math.isfinite(scale) or not 0.05 <= scale <= 2:
        raise ValueError("scale must be 0.05..2")
    return scale


def spec_status(name):
    try:
        s = S.load(name)
    except Exception as e:                       # a broken spec still lists
        return {"name": name, "error": str(e)}
    take = os.path.join(S.take_dir(s), "meta.json")
    out = os.path.join(S.OUT, name + ".mp4")
    length = json.load(open(take))["length"] if os.path.exists(take) else 0
    return {"name": name, "title": s.get("title", ""), "aspect": s["aspect"], "app": s["app"],
            "duration": s["duration"], "take": os.path.exists(take),
            "take_short": bool(length) and S.take_needed(s) > length + 0.05, "video": os.path.exists(out),
            "video_mtime": os.path.getmtime(out) if os.path.exists(out) else None}


def geometry(s):
    """Everything the browser needs to draw the scene like compose.Renderer."""
    W, H = S.size(s)
    meta_p = os.path.join(S.take_dir(s), "meta.json")
    meta = json.load(open(meta_p)) if os.path.exists(meta_p) else None
    sw, sh = (meta["width"], meta["height"]) if meta else (1080, 2400)
    fp, mp, g = phone_mod.ensure(os.path.join(S.CACHE, "phone"), sw, sh, s["phone"]["color"])
    from compose import plate_settings
    pw, ph, pk = library.plate_geometry(plate_settings(s), W, H)
    key = S.take_key(s)
    return {"W": W, "H": H, "fps": s["fps"], "g": g, "take": meta,
            "frame": "/media/phone/" + os.path.basename(fp),
            "clip": f"/media/take/{key}/clip.mp4" if meta else None,
            "audio": f"/media/take/{key}/audio.wav" if meta else None,
            "plate": {"w": pw, "h": ph, "k": pk}}


def new_job(kind, name, fn, lock=None):
    jid = f"{kind}-{name}-{int(time.time() * 1000)}"
    job = {"id": jid, "kind": kind, "name": name, "state": "queued", "log": [], "progress": 0.0,
           "started": time.time(), "result": None}
    with JOB_LOCK:
        if any(j["kind"] == kind and j["name"] == name and j["state"] in ("queued", "running") for j in JOBS.values()):
            raise ValueError(f"{kind} for {name} is already running")
        JOBS[jid] = job

    def log(msg):
        job["log"].append(str(msg))
        job["log"] = job["log"][-200:]

    def run():
        with lock or nullcontext():
            job["state"] = "running"
            try:
                job["result"] = fn(log, lambda p: job.__setitem__("progress", p))
                job["state"] = "done"
                job["progress"] = 1.0
            except BaseException as e:            # SystemExit carries our user-facing errors
                job["state"] = "error"
                log(str(e) or e.__class__.__name__)
                log(traceback.format_exc(limit=3))
    threading.Thread(target=run, daemon=True).start()
    return job


class Handler(BaseHTTPRequestHandler):
    server_version = "PromoStudio/1"

    def log_message(self, fmt, *args):
        pass

    # -- helpers -----------------------------------------------------------

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path, ctype=None):
        if not os.path.isfile(path):
            return self.send_error(404)
        size = os.path.getsize(path)
        ctype = ctype or mimetypes.guess_type(path)[0] or "application/octet-stream"
        rng = self.headers.get("Range")
        start, end = 0, size - 1
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
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
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
        if origin and origin != f"http://{self.headers.get('Host')}":
            self.send_json({"error": "cross-origin changes are not allowed"}, 403)
            return False
        return True

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    # -- routes ------------------------------------------------------------

    def do_GET(self):
        try:
            self.route_get()
        except Exception as e:
            self.send_json({"error": str(e)}, 500)

    def do_PUT(self):
        if not self.local_request(): return
        try:
            m = re.fullmatch(r"/api/spec/([\w.-]+)", self.path)
            if not m:
                return self.send_error(404)
            raw = S.migrate(self.body())                     # an old editor tab may still send v1
            S.resolve(raw)                                   # validate before saving
            write_spec(m.group(1), raw)
            self.send_json({"ok": True, "status": spec_status(m.group(1))})
        except Exception as e:
            self.send_json({"error": str(e)}, 400)

    def do_DELETE(self):
        if not self.local_request(): return
        try:
            m = re.fullmatch(r"/api/spec/([\w.-]+)", self.path)
            if not m: return self.send_error(404)
            p = spec_path(m.group(1))
            with SPEC_LOCK:
                if os.path.exists(p): os.remove(p)
            self.send_json({"ok": True})
        except Exception as e:
            self.send_json({"error": str(e)}, 400)

    def do_POST(self):
        if not self.local_request(): return
        try:
            self.route_post()
        except Exception as e:
            self.send_json({"error": str(e)}, 400)

    def route_get(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        p = u.path
        if p in ("/", "/index.html"):
            return self.send_file(os.path.join(EDITOR, "index.html"), "text/html; charset=utf-8")
        if p.startswith("/editor/"):
            f = os.path.normpath(os.path.join(EDITOR, p[len("/editor/"):]))
            if os.path.commonpath([f, EDITOR]) != EDITOR:
                return self.send_error(403)
            return self.send_file(f)
        if p.startswith("/fonts/"):
            return self.send_file(os.path.join(S.REPO, "web", "public", "fonts", os.path.basename(p)))
        if p == "/api/meta":
            import phone
            bgs = library.catalog()
            return self.send_json({
                "reciters": {k: v["name"] for k, v in S.RECITERS.items()},
                "themes": S.THEMES, "layouts": S.LAYOUTS, "views": S.VIEWS,
                "aspects": {k: list(v) for k, v in S.ASPECTS.items()}, "eases": S.EASES,
                "phones": sorted(phone.COLORS), "defaults": S.DEFAULTS, "props": S.PROPS,
                "backgrounds": {k: {"title": v["title"], "suits": v["suits"], "thumb": f"/media/thumb/{k}.jpg"}
                                for k, v in bgs.items()},
                "surahs": surahs(), "out_dir": S.OUT})
        if p == "/api/specs":
            names = sorted(f[:-5] for f in os.listdir(S.SPECS) if f.endswith(".json"))
            return self.send_json([spec_status(n) for n in names])
        m = re.fullmatch(r"/api/spec/([\w.-]+)", p)
        if m:
            raw = S.migrate(json.load(open(spec_path(m.group(1)))))
            return self.send_json({"raw": raw, "resolved": S.load(m.group(1))})
        m = re.fullmatch(r"/api/geometry/([\w.-]+)", p)
        if m:
            return self.send_json(geometry(S.load(m.group(1))))
        m = re.fullmatch(r"/api/timeline/([\w.-]+)", p)
        if m:
            s = S.load(m.group(1))
            meta_p = os.path.join(S.take_dir(s), "meta.json")
            lead = json.load(open(meta_p))["lead"] if os.path.exists(meta_p) else 1.0
            if os.path.exists(meta_p): s["app"]["seconds"] = max(s["app"]["seconds"], json.load(open(meta_p))["length"])
            tl = timeline.build(s, lead)
            return self.send_json({"verses": tl["verses"], "repeats": tl["repeats"],
                                   "take": json.load(open(meta_p))["length"] if os.path.exists(meta_p) else None})
        m = re.fullmatch(r"/api/still/([\w.-]+)", p)
        if m:
            import compose
            from PIL import Image
            s = S.load(m.group(1))
            t = float(q.get("t", ["0"])[0])
            if not math.isfinite(t): raise ValueError("time must be finite")
            t = min(max(0, t), max(0, s["duration"] - 1 / s["fps"]))
            scale = render_scale(q.get("scale", ["0.5"])[0])
            img = Image.fromarray(compose.still(s, t, scale))
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=90)
            body = buf.getvalue()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if p == "/api/jobs":
            with JOB_LOCK:
                jobs = sorted(JOBS.values(), key=lambda j: -j["started"])[:30]
            return self.send_json(jobs)
        if p == "/api/emu":
            import capture
            return self.send_json({"serial": capture.serial(), "up": capture.is_up()})
        m = re.fullmatch(r"/media/take/([\w.-]+)/(clip\.mp4|audio\.wav)", p)
        if m:
            return self.send_file(os.path.join(S.CACHE, "takes", m.group(1), m.group(2)))
        m = re.fullmatch(r"/media/phone/([\w.-]+\.png)", p)
        if m:
            return self.send_file(os.path.join(S.CACHE, "phone", m.group(1)))
        m = re.fullmatch(r"/media/plate/([\w.-]+\.mp4)", p)
        if m:
            return self.send_file(os.path.join(library.PLATES, m.group(1)))
        m = re.fullmatch(r"/media/out/([\w.-]+\.mp4)", p)
        if m:
            return self.send_file(os.path.join(S.OUT, m.group(1)))
        m = re.fullmatch(r"/media/thumb/([\w.-]+)\.jpg", p)
        if m:
            return self.send_file(thumb(m.group(1)), "image/jpeg")
        if p.startswith("/icon.png"):
            return self.send_file(os.path.join(S.REPO, "docs", "play_store_icon.png"))
        self.send_error(404)

    def route_post(self):
        p = urllib.parse.urlparse(self.path).path
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
        m = re.fullmatch(r"/api/copy/([\w.-]+)/([\w.-]+)", p)
        if m:
            src, dst = spec_path(m.group(1)), spec_path(m.group(2))
            with SPEC_LOCK:
                if os.path.exists(dst): return self.send_json({"error": "exists"}, 409)
                with open(src, "rb") as source, open(dst, "xb") as dest: shutil.copyfileobj(source, dest)
            return self.send_json({"ok": True})
        m = re.fullmatch(r"/api/new/([\w.-]+)", p)
        if m:
            dst = spec_path(m.group(1))
            with SPEC_LOCK:
                if os.path.exists(dst): return self.send_json({"error": "exists"}, 409)
                with open(dst, "x") as f: json.dump({"title": m.group(1)}, f, indent=2)
            return self.send_json({"ok": True})
        m = re.fullmatch(r"/api/capture/([\w.-]+)", p)
        if m:
            name = m.group(1)
            force = bool(self.body().get("force"))
            spec = S.load(name)

            def run(log, progress):
                import capture
                with CAPTURE_LOCK:
                    return capture.record(spec, log=log, force=force)
            return self.send_json(new_job("capture", name, run))
        m = re.fullmatch(r"/api/render/([\w.-]+)", p)
        if m:
            name = m.group(1)
            opts = self.body()
            spec = S.load(name)
            scale = render_scale(opts.get("scale", 1.0))

            def run(log, progress):
                import compose
                return compose.export(spec, scale=scale, log=log, progress=progress)
            return self.send_json(new_job("render", name, run, RENDER_LOCK))
        if p == "/api/emu/boot":
            def run(log, progress):
                import capture
                capture.boot(log=log)
                return capture.serial()
            return self.send_json(new_job("emu", "boot", run))
        if p == "/api/emu/install":
            apk = self.body().get("apk") or os.path.join(S.REPO, "app/build/outputs/apk/release/app-release.apk")

            def run(log, progress):
                import capture
                capture.install(apk, log=log)
                return apk
            return self.send_json(new_job("emu", "install", run))
        self.send_error(404)


_SURAHS = None


def surahs():
    global _SURAHS
    if _SURAHS is None:
        import sqlite3
        db = sqlite3.connect(os.path.join(S.REPO, "data", "quran.db"))
        _SURAHS = [{"id": i, "name": n, "ayahs": c}
                   for i, n, c in db.execute("select id, name_transliteration, ayah_count from surahs order by id")]
    return _SURAHS


def thumb(bg_id):
    d = os.path.join(S.CACHE, "thumbs")
    os.makedirs(d, exist_ok=True)
    out = os.path.join(d, bg_id + ".jpg")
    if not os.path.exists(out):
        import subprocess
        src = library.source(bg_id)
        start = float(library.catalog().get(bg_id, {}).get("start", 0)) + 1
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(start), "-i", src, "-frames:v", "1",
                        "-vf", "scale=240:240:force_original_aspect_ratio=increase,crop=240:240", out], check=True)
    return out


def serve(port=8765):
    os.makedirs(S.SPECS, exist_ok=True)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"promo studio: http://127.0.0.1:{port}/  (specs in {S.SPECS}, videos to {S.OUT})")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
