"""python -m reciter_align {align,pack,report} ..."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from . import artifact
from .text import DEFAULT_DB


def parse_surahs(spec: str):
    out = []
    for part in spec.split(","):
        lo, _, hi = part.partition("-")
        out.extend(range(int(lo), int(hi or lo) + 1))
    return out


def audio_for(args, surah):
    return Path(args.audio) / args.pattern.format(surah=surah)


def cmd_align(args):
    from .backends import BACKENDS
    from .pipeline import align_surah

    primary = BACKENDS[args.model]()
    check = BACKENDS[args.check]() if args.check else None
    third = BACKENDS[args.third]() if args.third and check else None
    for surah in parse_surahs(args.surahs):
        if artifact.surah_path(args.work, surah).exists() and not args.force:
            print(f"surah {surah}: done", flush=True)
            continue
        started = time.time()
        record = align_surah(surah, audio_for(args, surah), primary, check, third, args.db,
                             log=lambda *a: print(*a, flush=True))
        artifact.save_surah(args.work, surah, record)
        extra = record.get("check", {})
        print(f"surah {surah}: {len(record['words'])} words, {len(record['openings'])} openings, "
              f"{len(record['review']['inserted'])} inserted, "
              f"{len(record['review']['unexplainedSpeech'])} unexplained gaps, "
              f"check median {extra.get('medianMs')} ms, >200 ms: {extra.get('over200Ms')} "
              f"({time.time() - started:.0f}s)", flush=True)


def cmd_pack(args):
    surahs = parse_surahs(args.surahs)
    sources = {}
    for s in surahs:
        path = audio_for(args, s)
        sources[s] = {"bytes": path.stat().st_size, "sha256": artifact.sha256_file(path)}
    models = {"primary": args.model_name, "check": args.check_name}
    payload = artifact.pack(args.work, args.out, args.reciter, models, sources, surahs)
    print(f"wrote {args.out}: {len(payload['surahs'])} surahs")


def cmd_report(args):
    total = {"words": 0, "inserted": 0, "unexplained": 0, "weak": 0, "over200": 0, "over500": 0, "checked": 0}
    for s in parse_surahs(args.surahs):
        r = artifact.load_surah(args.work, s)
        c = r.get("check", {})
        total["words"] += len(r["words"])
        total["inserted"] += len(r["review"]["inserted"])
        total["unexplained"] += len(r["review"]["unexplainedSpeech"])
        total["weak"] += len(r["review"]["weakWords"])
        total["over200"] += c.get("over200Ms", 0)
        total["over500"] += c.get("over500Ms", 0)
        total["checked"] += c.get("words", 0)
    print(json.dumps(total))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="reciter_align", description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("align", "pack", "report"):
        p = sub.add_parser(name)
        p.add_argument("--work", type=Path, required=True, help="directory of per-surah records")
        p.add_argument("--surahs", default="1-114")
        if name in ("align", "pack"):
            p.add_argument("--audio", type=Path, required=True, help="directory of surah audio files")
            p.add_argument("--pattern", default="{surah:03d}.mp3", help="file name per surah")
    a = sub.choices["align"]
    a.add_argument("--model", default="mms")
    a.add_argument("--check", default="xlsr", help="independent audit model ('' to skip)")
    a.add_argument("--third", default="mms1b", help="arbiter when the two models disagree by over 500 ms ('' to skip)")
    a.add_argument("--db", type=Path, default=DEFAULT_DB)
    a.add_argument("--force", action="store_true")
    a.set_defaults(func=cmd_align)
    k = sub.choices["pack"]
    k.add_argument("--reciter", required=True, help="catalogue slug, e.g. Tawfeek_As-Sawaigh_128kbps")
    k.add_argument("--out", type=Path, required=True)
    k.add_argument("--model-name", default="MahmoudAshraf/mms-300m-1130-forced-aligner")
    k.add_argument("--check-name", default="jonatasgrosman/wav2vec2-large-xlsr-53-arabic")
    k.set_defaults(func=cmd_pack)
    sub.choices["report"].set_defaults(func=cmd_report)
    args = parser.parse_args(argv)
    if getattr(args, "check", None) == "":
        args.check = None
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
