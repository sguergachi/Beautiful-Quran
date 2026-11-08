"""Where a video's edit lives, and why it cannot be lost.

Every change to a spec goes through here, and each is:

  atomic    written to a temp file beside the spec, fsynced, renamed over it,
            then the directory is fsynced: a crash or power cut leaves the old
            version or the new one, never a half-written or empty file
  versioned each save has a revision (a hash of the file); a save that was made
            against an older revision (a stale tab, a second window) is refused
            with the current version instead of silently overwriting it
  recorded  versions are kept outside the repo (~/.cache/bq-promo/spec-history),
            so a bad save, a deleted video, a `git checkout` or a corrupt file is
            always recoverable: thinned out over time (all of the last minutes,
            hourly for days, daily for weeks), and a version that is replaced,
            deleted or restored is always kept first

On start, recover() checks every spec, puts back the last good version of a broken
one, and removes the temp files a crash left behind.
"""
import hashlib
import json
import os
import re
import tempfile
import threading
import time

import spec as S

HISTORY = os.environ.get("BQ_PROMO_HISTORY") or os.path.join(S.CACHE, "spec-history")
LOCK = threading.RLock()
NAME = re.compile(r"[A-Za-z0-9._-]+")
THROTTLE_S = 15            # an autosave burst is snapshotted at most this often ...
SETTLE_S = 20              # ... and its final state this long after the last save
KEEP_RECENT = 40           # versions always kept
KEEP_HOURLY_S = 3 * 86400  # then one per hour back this far
KEEP_DAILY_S = 60 * 86400  # then one per day back this far
KEEP_MARKED_S = 400 * 86400  # deleted / replaced / restored / unsaved versions: kept this long
MARKED = ("deleted", "replaced", "before-restore", "unsaved", "corrupt", "created")

_last_snapshot = {}        # name -> monotonic time of the last throttled snapshot
_timers = {}               # name -> settle timer


class SpecConflict(Exception):
    """A save made against a revision that is no longer the current one."""
    def __init__(self, name, raw, rev):
        super().__init__(f"{name} was changed elsewhere")
        self.name, self.raw, self.rev = name, raw, rev


def path(name):
    if not NAME.fullmatch(name or ""):
        raise ValueError("names use letters, digits, '.', '-' and '_'")
    return os.path.join(S.SPECS, name + ".json")


def encode(raw):
    return (json.dumps(raw, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode()


def revision(data):
    return hashlib.sha256(data).hexdigest()[:16]


def _fsync_dir(d):
    try:
        fd = os.open(d, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def atomic_write(target, data):
    """Write `data` to `target` so that a crash leaves the old file or the new one."""
    d = os.path.dirname(target)
    os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d, prefix="." + os.path.basename(target) + ".", suffix=".part")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, target)
        tmp = None
        _fsync_dir(d)
    finally:
        if tmp and os.path.exists(tmp):
            os.remove(tmp)


# ---------------------------------------------------------------- reading

def read(name):
    """(raw spec, revision) of the file on disk; raises FileNotFoundError."""
    with open(path(name), "rb") as f:
        data = f.read()
    return S.migrate(json.loads(data)), revision(data)


def current_revision(name):
    try:
        with open(path(name), "rb") as f:
            return revision(f.read())
    except FileNotFoundError:
        return None


# ---------------------------------------------------------------- history

def _dir(name):
    path(name)
    return os.path.join(HISTORY, name)


def _entries(name):
    d = _dir(name)
    out = []
    if os.path.isdir(d):
        for f in os.listdir(d):
            m = re.fullmatch(r"(\d+)-([0-9a-f]{12,16})-([a-z-]+)\.json", f)
            if m:
                out.append({"id": f[:-5], "at": int(m[1]) / 1000, "rev": m[2], "reason": m[3], "file": os.path.join(d, f)})
    return sorted(out, key=lambda e: e["at"], reverse=True)


def snapshot(name, data, reason="save", at=None):
    """Keep `data` as a version of `name` (once per revision per reason)."""
    rev = revision(data)
    with LOCK:
        for e in _entries(name)[:3]:
            if e["rev"] == rev[:len(e["rev"])] and e["reason"] == reason:
                return e["id"]
        ts = int((at or time.time()) * 1000)
        entry = f"{ts}-{rev}-{reason}"
        atomic_write(os.path.join(_dir(name), entry + ".json"), data)
        prune(name)
        return entry


def prune(name, now=None):
    now = now or time.time()
    ents = _entries(name)
    keep, hour, day = set(), set(), set()
    for i, e in enumerate(ents):
        age = now - e["at"]
        if e["reason"] in MARKED:
            if age < KEEP_MARKED_S: keep.add(e["id"])
        elif i < KEEP_RECENT:
            keep.add(e["id"])
        elif age < KEEP_HOURLY_S and int(e["at"] // 3600) not in hour:
            hour.add(int(e["at"] // 3600)); keep.add(e["id"])
        elif age < KEEP_DAILY_S and int(e["at"] // 86400) not in day:
            day.add(int(e["at"] // 86400)); keep.add(e["id"])
    for e in ents:
        if e["id"] not in keep:
            try: os.remove(e["file"])
            except OSError: pass


def summary(data):
    """What a version holds, for the history list."""
    try:
        raw = json.loads(data)
        return {"title": raw.get("title"), "pieces": len(raw.get("edit") or []),
                "keys": sum(len(v) for v in (raw.get("tracks") or {}).values()), "size": len(data)}
    except ValueError:
        return {"title": None, "pieces": 0, "keys": 0, "size": len(data)}


def history(name, limit=200):
    out = []
    for e in _entries(name)[:limit]:
        try:
            with open(e["file"], "rb") as f:
                data = f.read()
        except OSError:
            continue
        out.append({"id": e["id"], "at": e["at"], "rev": e["rev"], "reason": e["reason"], **summary(data)})
    return out


def _settle(name):
    """Snapshot the live file once saving has been quiet for SETTLE_S: the final
    state of an editing burst is always kept, however fast the autosaves came."""
    def run():
        _timers.pop(name, None)
        try:
            with open(path(name), "rb") as f:
                snapshot(name, f.read(), "save")
        except OSError:
            pass
    with LOCK:
        t = _timers.pop(name, None)
        if t: t.cancel()
        t = _timers[name] = threading.Timer(SETTLE_S, run)
        t.daemon = True
        t.start()


# ---------------------------------------------------------------- writing

def write(name, raw, expect=None, reason="save", force=False):
    """Save `raw` as `name`'s spec; returns the new revision. `expect` is the
    revision the edit was made against: when the file has moved on, SpecConflict
    carries the current version and nothing is written, unless `force`, which
    keeps the version it replaces first. Whatever is replaced is kept in the
    history (autosave bursts: at most every THROTTLE_S, plus their final state)."""
    data = encode(raw)
    target = path(name)
    with LOCK:
        old = None
        if os.path.exists(target):
            with open(target, "rb") as f:
                old = f.read()
        if old is not None:
            if expect is not None and revision(old) != expect:
                if not force:
                    raise SpecConflict(name, S.migrate(json.loads(old)), revision(old))
                snapshot(name, old, "replaced")
            elif old == data:
                return revision(data)
            elif reason == "restore":
                snapshot(name, old, "before-restore")
            elif reason != "save":
                snapshot(name, old, "replaced")
            elif time.monotonic() - _last_snapshot.get(name, -1e9) >= THROTTLE_S:
                snapshot(name, old, "save")
                _last_snapshot[name] = time.monotonic()
        atomic_write(target, data)
        if reason == "save":
            _settle(name)
        return revision(data)


def create(name, raw):
    """A new spec, exclusively: fails (FileExistsError) rather than replace one."""
    target = path(name)
    with LOCK:
        if os.path.exists(target):
            raise FileExistsError(name)
        data = encode(raw)
        atomic_write(target, data)
        snapshot(name, data, "created")
        return revision(data)


def duplicate(src, dst):
    with LOCK:
        with open(path(src), "rb") as f:
            data = f.read()
        if os.path.exists(path(dst)):
            raise FileExistsError(dst)
        atomic_write(path(dst), data)
        snapshot(dst, data, "created")
        return revision(data)


def rename(src, dst):
    """Rename keeping the history: the old name's versions move with it."""
    with LOCK:
        if os.path.exists(path(dst)):
            raise FileExistsError(dst)
        os.rename(path(src), path(dst))
        _fsync_dir(S.SPECS)
        if os.path.isdir(_dir(src)) and not os.path.exists(_dir(dst)):
            os.rename(_dir(src), _dir(dst))


def delete(name):
    """Remove a video, keeping its last version for good (restore it from the history)."""
    with LOCK:
        target = path(name)
        with open(target, "rb") as f:
            data = f.read()
        snapshot(name, data, "deleted")
        os.remove(target)
        _fsync_dir(S.SPECS)


def stash(name, raw, reason="unsaved"):
    """Keep a version that was not saved as the spec (another tab's edits, a draft)
    in the history only."""
    return snapshot(name, encode(raw), reason)


def restore(name, version):
    """Make a history version the spec again (the current one is kept first).
    Works for a deleted video too. Returns (raw, revision)."""
    if not re.fullmatch(r"\d+-[0-9a-f]{12,16}-[a-z-]+", version or ""):
        raise ValueError("unknown version")
    with open(os.path.join(_dir(name), version + ".json"), "rb") as f:
        data = f.read()
    raw = json.loads(data)
    S.resolve(raw)                                   # only ever restore something valid
    with LOCK:
        if os.path.exists(path(name)):
            rev = write(name, raw, reason="restore")
        else:
            atomic_write(path(name), encode(raw))
            rev = revision(encode(raw))
    return S.migrate(raw), rev


def deleted():
    """[{name, at, title}]: videos with history but no spec any more, newest first."""
    out = []
    if os.path.isdir(HISTORY):
        for name in os.listdir(HISTORY):
            if NAME.fullmatch(name) and not os.path.exists(path(name)):
                last = next((e for e in _entries(name) if e["reason"] == "deleted"), None)
                if last:
                    with open(last["file"], "rb") as f:
                        out.append({"name": name, "at": last["at"], "id": last["id"], **summary(f.read())})
    return sorted(out, key=lambda e: e["at"], reverse=True)


# ---------------------------------------------------------------- recovery

def valid(data):
    try:
        S.resolve(json.loads(data))
        return True
    except Exception:
        return False


def recover(log=print):
    """On start: remove temp files a crash left behind; check every spec and put
    back the last good version of a broken one (keeping the broken file in the
    history); give every spec a version to return to. Returns what it fixed."""
    fixed = []
    if not os.path.isdir(S.SPECS):
        return fixed
    now = time.time()
    for f in os.listdir(S.SPECS):
        full = os.path.join(S.SPECS, f)
        if f.endswith(".part") and now - os.path.getmtime(full) > 30:
            os.remove(full)
    for f in sorted(os.listdir(S.SPECS)):
        if not f.endswith(".json") or not NAME.fullmatch(f[:-5]):
            continue
        name = f[:-5]
        with open(path(name), "rb") as fh:
            data = fh.read()
        if not valid(data):
            snapshot(name, data or b"{}", "corrupt")
            good = None
            for e in _entries(name):
                if e["reason"] == "corrupt":
                    continue
                with open(e["file"], "rb") as fh:
                    if valid(fh.read()):
                        good = e
                        break
            if good:
                with open(good["file"], "rb") as fh:
                    atomic_write(path(name), fh.read())
                fixed.append((name, good["id"]))
                log(f"spec {name} was broken; restored the version from {time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(good['at']))}")
            else:
                log(f"spec {name} is broken and has no earlier version")
        elif not _entries(name):
            snapshot(name, data, "created")
    return fixed
