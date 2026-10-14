#!/usr/bin/env python3
"""One Rain carriage Reel from one capture: cues from the log, mix, render.

    make_reel.py VARIANT CAPTURE_DIR AUDIO_DIR OUT_DIR

Cue times come from the capture's audio log, so the light-break always lands
on 55:5, the stars on 55:6 and the open view on 55:10 (docs/KEY_ART.md).
Segment 0 is the basmalah, segment k is 55:k.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    variant, cap, audio_dir, out_dir = sys.argv[1:5]
    os.makedirs(out_dir, exist_ok=True)
    log = json.load(open(os.path.join(cap, "audio-log.json")))
    e0 = log["t"] - log["frames"] * 1000 / log["fps"]
    plays = [(e["t"] - e0) / 1000 for e in log["log"] if e["kind"] == "play"]
    stops = [(e["t"] - e0) / 1000 for e in log["log"] if e["kind"] in ("stop", "end")]
    start, end = plays[0] - 0.5, stops[10] + 2.6
    cues = {"break": plays[5], "stars": plays[6], "open": plays[10]}
    print(variant, f"start {start:.2f} end {end:.2f}", cues)
    wav = os.path.join(out_dir, f"{variant}.wav")
    subprocess.run([sys.executable, os.path.join(HERE, "mix_audio.py"), os.path.join(cap, "audio-log.json"),
                    audio_dir, str(start), str(end), wav], check=True)
    cue_args = [x for k, v in cues.items() for x in (f"--{k}", f"{v:.3f}")]
    subprocess.run(["node", os.path.join(HERE, "render_scene.mjs"), "--variant", variant, "--frames", cap,
                    "--start", f"{start:.3f}", "--end", f"{end:.3f}", "--audio", wav,
                    "--out", os.path.join(out_dir, f"rain-carriage-{variant}.mp4"),
                    "--still", f"{cues['break'] + 3.2:.3f}",
                    "--still-out", os.path.join(out_dir, f"rain-carriage-{variant}-cover.jpg"), *cue_args],
                   check=True)


if __name__ == "__main__":
    main()
