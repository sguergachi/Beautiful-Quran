#!/usr/bin/env python3
"""Beautiful Quran promo studio: app recordings in a Pixel frame, ready to post.

    studio.py serve [--port 8765]          # the editor: preview, zooms, export
    studio.py list                         # specs and whether their takes exist
    studio.py emu boot | install [APK]     # the capture emulator (6 cores)
    studio.py capture SPEC... [--force]    # record the app (picture + sound)
    studio.py render SPEC... [-o OUT]      # export mp4 (needs the take)
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
    sub.add_parser("list")
    p = sub.add_parser("emu"); p.add_argument("what", choices=["boot", "install", "status"])
    p.add_argument("apk", nargs="?", default=os.path.join(S.REPO, "app/build/outputs/apk/release/app-release.apk"))
    p.add_argument("--cores", type=int, default=6)
    p = sub.add_parser("capture"); p.add_argument("specs", nargs="+"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("render"); p.add_argument("specs", nargs="+"); p.add_argument("-o", "--out")
    p.add_argument("--scale", type=float, default=1.0); p.add_argument("--workers", type=int)
    p = sub.add_parser("make"); p.add_argument("specs", nargs="+"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("remux"); p.add_argument("specs", nargs="+")
    p = sub.add_parser("still"); p.add_argument("spec"); p.add_argument("t", type=float); p.add_argument("out")
    p.add_argument("--scale", type=float, default=1.0)
    p = sub.add_parser("new"); p.add_argument("name"); p.add_argument("--from", dest="src")
    a = ap.parse_args()

    if a.cmd == "serve":
        import server
        server.serve(a.port)
    elif a.cmd == "list":
        for n in names(["all"]):
            s = S.load(n)
            have = os.path.exists(os.path.join(S.take_dir(s), "meta.json"))
            out = os.path.exists(os.path.join(S.OUT, n + ".mp4"))
            ap_ = s["app"]
            print(f"{n:34} {ap_['reciter']:8} {ap_['surah']:>3}:{ap_['ayah']:<3} {ap_['theme']:11} "
                  f"{s['aspect']:5} take:{'yes' if have else 'no ':3} video:{'yes' if out else 'no'}")
    elif a.cmd == "emu":
        import capture
        if a.what == "boot":
            capture.boot(a.cores)
        elif a.what == "install":
            capture.install(a.apk)
        else:
            print(capture.serial(), "up" if capture.is_up() else "down")
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
            compose.export(S.load(n), out=a.out if len(a.specs) == 1 else None, scale=a.scale, workers=a.workers)
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


if __name__ == "__main__":
    main()
