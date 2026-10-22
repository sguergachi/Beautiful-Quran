"""Progress for long work (captures, renders, emulator boots).

The same calls serve both front ends: on a terminal they print readable
lines; under the editor's job runner (`--json`) each event is one JSON line
on stdout, which server.Jobs parses into the job's stage, progress and log.

    rep.stage("record", "Recording 32 s")   # a named step of the job
    rep.progress(0.4)                       # 0..1 within the job
    rep.log("scrcpy started")               # detail
"""
import json
import sys
import time


class Report:
    def __init__(self, json_mode=False, out=None):
        self.json = json_mode
        self.out = out or sys.stdout
        self._last_progress = -1.0
        self._last_at = 0.0

    def _emit(self, **event):
        if self.json:
            self.out.write(json.dumps(event) + "\n")
        else:
            kind = event.get("type")
            if kind == "stage":
                self.out.write(f"▸ {event.get('msg') or event['key']}\n")
            elif kind == "progress":
                if event.get("msg"):
                    self.out.write(f"  {event['value'] * 100:3.0f}%  {event['msg']}\n")
            elif kind in ("log", "error"):
                self.out.write(("error: " if kind == "error" else "  ") + str(event.get("msg")) + "\n")
            elif kind == "result":
                self.out.write(f"✓ {event.get('value')}\n")
        self.out.flush()

    def stage(self, key, msg=None, progress=None):
        self._emit(type="stage", key=key, msg=msg or key)
        if progress is not None:
            self.progress(progress)

    def progress(self, value, msg=None):
        value = max(0.0, min(1.0, float(value)))
        now = time.monotonic()
        # throttle: the editor polls about once a second; a line per frame is noise
        if msg is None and abs(value - self._last_progress) < 0.005 and now - self._last_at < 0.5:
            return
        self._last_progress, self._last_at = value, now
        self._emit(type="progress", value=round(value, 4), msg=msg)

    def log(self, msg):
        self._emit(type="log", msg=str(msg))

    __call__ = log            # code that takes a plain `log` callable can take a Report

    def result(self, value):
        self._emit(type="result", value=value)

    def error(self, msg):
        self._emit(type="error", msg=str(msg))
