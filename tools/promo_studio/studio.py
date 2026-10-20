#!/usr/bin/env python3
"""Beautiful Quran promo studio: app recordings in a Pixel frame, ready to post.

    studio.py serve [--host 0.0.0.0] [--port 8765]  # editor; --host enables LAN access
    studio.py list                         # specs and whether their takes exist
    studio.py emu list                     # emulators, booted / app installed
    studio.py emu boot [--cores 6] [--port P]   # start another capture emulator
    studio.py emu prepare [SERIAL] [--apk APK]  # install + first launch (timings)
    studio.py emu stop SERIAL
    studio.py capture SPEC... [--force]    # record the app (picture + sound)
    studio.py render SPEC... [-o OUT] [--format h264|hevc|av1|vp9|prores|gif]
                  [--quality best|high|balanced|small] [--scale 0.667] [--fps 30] [--audio 192|0]
                                           # export (needs the take); formats.py
    studio.py make SPEC...                 # capture if needed, then render
    studio.py remux SPEC...                # re-lay the sound only (after audio changes)
    studio.py still SPEC T OUT.png         # one exact frame
    studio.py new NAME [--from SPEC]       # start a spec

SPEC is a name in specs/ or a path; `all` means every spec. See README.md.
"""
import argparse
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import spec as S  # noqa: E402


def names(args):
    if args == ["all"]:
        return sorted(os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(S.SPECS, "*.json")))
    return args


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("serve"); p.add_argument("--port", type=int, default=8765)
    p.add_argument("--host", default="127.0.0.1", help="bind address; 0.0.0.0 enables LAN access")
    p.add_argument("--no-build", action="store_true", help="serve the last build of the editor as is")
    sub.add_parser("list")
    p = sub.add_parser("emu"); p.add_argument("what", choices=["list", "boot", "prepare", "install", "stop", "status"])
    p.add_argument("serial", nargs="?")
    p.add_argument("--apk", default=os.path.join(S.REPO, "app/build/outputs/apk/release/app-release.apk"))
    p.add_argument("--cores", type=int, default=6); p.add_argument("--port", type=int)
    # the editor runs long work as `studio.py job ...`: JSON progress lines on stdout
    p = sub.add_parser("job"); p.add_argument("kind", choices=["capture", "render", "emu-boot", "emu-prepare"])
    p.add_argument("target", nargs="?"); p.add_argument("--force", action="store_true")
    p.add_argument("--scale", type=float, default=1.0); p.add_argument("--cores", type=int, default=6)
    p.add_argument("--port", type=int); p.add_argument("--apk"); p.add_argument("--options", help="export settings, JSON")
    p = sub.add_parser("capture"); p.add_argument("specs", nargs="+"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("render"); p.add_argument("specs", nargs="+"); p.add_argument("-o", "--out")
    p.add_argument("--scale", type=float, default=1.0); p.add_argument("--workers", type=int)
    p.add_argument("--format", default="h264"); p.add_argument("--quality", default="high")
    p.add_argument("--fps", type=int); p.add_argument("--audio", type=int, default=256, help="AAC kbps; 0 = no sound")
    p = sub.add_parser("make"); p.add_argument("specs", nargs="+"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("remux"); p.add_argument("specs", nargs="+")
    p = sub.add_parser("still"); p.add_argument("spec"); p.add_argument("t", type=float); p.add_argument("out")
    p.add_argument("--scale", type=float, default=1.0)
    p = sub.add_parser("new"); p.add_argument("name"); p.add_argument("--from", dest="src")
    a = ap.parse_args()

    if a.cmd == "serve":
        import server
        server.serve(a.port, build=not a.no_build, host=a.host)
    elif a.cmd == "list":
        for n in names(["all"]):
            s = S.load(n)
            have = os.path.exists(os.path.join(S.take_dir(s), "meta.json"))
            out = os.path.exists(os.path.join(S.OUT, n + ".mp4"))
            ap_ = s["app"]
            print(f"{n:34} {ap_['reciter']:8} {ap_['surah']:>3}:{ap_['ayah']:<3} {ap_['theme']:11} "
                  f"{s['aspect']:5} take:{'yes' if have else 'no ':3} video:{'yes' if out else 'no'}")
    elif a.cmd == "emu":
        import emus
        from report import Report
        rep = Report()
        if a.what in ("list", "status"):
            for d in emus.list_all():
                print(f"{d['serial']:16} {d['kind']:8} {'booted' if d['booted'] else 'booting':8} "
                      f"app:{'yes' if d['app'] else 'no ':3} cores:{d['cores'] or '?'} {d['size'] or ''}")
        elif a.what == "boot":
            print(emus.boot(a.port, a.cores, rep))
        elif a.what in ("prepare", "install"):
            emus.prepare(a.serial or os.environ.get("ANDROID_SERIAL") or "emulator-5650", a.apk, rep)
        elif a.what == "stop":
            emus.stop(a.serial)
    elif a.cmd == "job":
        run_job(a)
    elif a.cmd in ("capture", "make"):
        import capture
        failed = []
        for n in names(a.specs):
            s = S.load(n)
            try:
                capture.record(s, force=a.force)
            except (SystemExit, Exception) as e:
                print(f"{n}: FAILED: {e}", flush=True)
                failed.append(n)
                continue
            if a.cmd == "make":
                import compose
                compose.export(s)
        if failed:
            raise SystemExit("failed: " + " ".join(failed))
    elif a.cmd == "render":
        import compose
        for n in names(a.specs):
            o = {"format": a.format, "quality": a.quality, "scale": a.scale, "fps": a.fps, "audio_kbps": a.audio}
            compose.export(S.load(n), out=a.out if len(a.specs) == 1 else None, workers=a.workers, options=o)
    elif a.cmd == "remux":
        import compose
        for n in names(a.specs):
            out = os.path.join(S.OUT, n + ".mp4")
            if os.path.exists(out):
                compose.mux(S.load(n), ["-i", out], out)     # writes beside, then replaces
                print("remuxed", out)
    elif a.cmd == "still":
        import compose
        from PIL import Image
        Image.fromarray(compose.still(S.load(a.spec), a.t, a.scale)).save(a.out)
        print(a.out)
    elif a.cmd == "new":
        base = json.load(open(os.path.join(S.SPECS, a.src + ".json"))) if a.src else {"title": a.name}
        path = os.path.join(S.SPECS, a.name + ".json")
        if os.path.exists(path):
            raise SystemExit(f"{path} exists")
        json.dump(base, open(path, "w"), indent=2, ensure_ascii=False)
        print(path)


def run_job(a):
    """One unit of editor work, reporting JSON lines (report.Report) for server.Jobs."""
    from report import Report
    rep = Report(json_mode=True)
    try:
        if a.kind == "capture":
            import capture
            rep.result(capture.record(S.load(a.target), log=rep, force=a.force))
        elif a.kind == "render":
            import compose
            o = json.loads(a.options) if a.options else {"scale": a.scale}    # older queued jobs pass --scale
            rep.result(compose.export(S.load(a.target), log=rep, options=o))
        elif a.kind == "emu-boot":
            import emus
            rep.result(emus.boot(a.port, a.cores, rep))
        elif a.kind == "emu-prepare":
            import emus
            rep.result(emus.prepare(a.target, a.apk or emus.DEFAULT_APK, rep))
    except BaseException as e:                 # SystemExit carries the user-facing errors
        rep.error(str(e) or e.__class__.__name__)
        if not isinstance(e, SystemExit):
            import traceback
            rep.log(traceback.format_exc(limit=4))
        sys.exit(1)


if __name__ == "__main__":
    main()
