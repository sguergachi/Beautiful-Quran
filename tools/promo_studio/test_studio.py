"""Offline regressions: python3 -m unittest discover -s tools/promo_studio -p 'test_*.py'."""
import glob
import http.client
import json
import math
import os
from pathlib import Path
import random
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import ThreadingHTTPServer

import numpy as np

import anim
import compose
import library
import phone
import server
import spec

SCRATCH = Path(spec.CACHE) / "test"
SCRATCH.mkdir(parents=True, exist_ok=True)


class ModelTests(unittest.TestCase):
    def test_existing_specs_and_migration_roundtrip(self):
        for path in glob.glob(os.path.join(spec.SPECS, "*.json")):
            raw = json.loads(Path(path).read_text())
            migrated = spec.migrate(raw)
            self.assertEqual(migrated, spec.migrate(migrated))
            self.assertEqual(spec.resolve(raw), spec.resolve(migrated))
        s = spec.resolve({"camera": [{"t": 0, "rot": 5}, {"t": 1, "rot": 0}], "duration": 3})
        self.assertEqual(anim.value_at(s, "rotate", 2), 0)
        resolved = spec.resolve({"edit": [{"in": 2, "out": 3}, {"in": 4, "out": 6}]})
        self.assertEqual(spec.resolve(resolved)["edit"], resolved["edit"], "derived duration must not erase a modern edit")

    def test_invalid_specs_fail_before_saving(self):
        for raw in ({"fps": 0}, {"fps": 1.5}, {"edit": []}, {"edit": [{"in": 4, "out": 3}]},
                    {"props": {"zoom": float("nan")}}, {"tracks": {"zoom": [{"t": 0, "v": 0}]}},
                    {"tracks": {"zoom": [{"t": 1, "v": 1}, {"t": 1, "v": 2}]}}, {"phone": {"height": -1}}):
            with self.subTest(raw=raw), self.assertRaises(ValueError): spec.resolve(raw)

    def test_editor_math_parity(self):
        rng, cases = random.Random(886), []
        for i in range(32):
            s = spec.resolve({"aspect": list(spec.ASPECTS)[i % 4], "edit": [{"in": .5, "out": 2}, {"in": .2, "out": 1}],
                              "tracks": {"zoom": [{"t": 0, "v": .7}, {"t": 2, "v": 4, "ease": spec.EASES[i % len(spec.EASES)]}]}})
            for name, p in spec.PROPS.items(): s["props"][name] = rng.uniform(p["min"], p["max"])
            t = [-.5, 0, .4, 1.5, 2, 2.3, 3][i % 7]
            W, H = spec.size(s)
            pw, ph, pk = library.plate_geometry(compose.plate_settings(s), W, H)
            cases.append(dict(spec=s, t=t, W=W, H=H, plate=dict(w=pw, h=ph, k=pk), a=.4, b=1.7))
        proc = subprocess.run(["node", str(Path(__file__).with_name("test_editor.cjs")), "--parity"],
                              input=json.dumps(cases), capture_output=True, text=True, check=True)
        for c, js in zip(cases, json.loads(proc.stdout), strict=True):
            s, t = c["spec"], c["t"]
            st = anim.state_at(s, t)
            for name in spec.PROPS: self.assertAlmostEqual(st[name], js["state"][name], places=10)
            r = compose.Renderer.__new__(compose.Renderer)
            r.s, r.W, r.H, r.g = s, c["W"], c["H"], phone.geometry(1080, 2400)
            r.base_s = s["phone"]["height"] * r.H / r.g["bh"]
            r.C0 = np.array([r.g["W"] / 2, r.g["H"] / 2]); r.O = np.array([r.W / 2, r.H / 2])
            r.pw, r.ph, r.pk = c["plate"].values()
            for fn, name in ((r.phone_matrix, "phone"), (r.bg_matrix, "bg")):
                m = fn(st); canvas = [m[0, 0], m[1, 0], m[0, 1], m[1, 1], m[0, 2], m[1, 2]]
                np.testing.assert_allclose(canvas, js[name], atol=1e-9)
            self.assertAlmostEqual(anim.source_time(s, t)[0], js["source"], places=10)
            np.testing.assert_allclose(anim.video_times(s, c["a"], c["b"]), js["spans"], atol=1e-10)


class MediaTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.d = Path(self.tmp.name)

    def tearDown(self): self.tmp.cleanup()

    def test_cuts_are_independent_of_parallel_chunk_boundaries(self):
        path = str(self.d / "counter.mp4")
        frames = np.stack([np.full((16, 16, 3), i * 2, np.uint8) for i in range(120)])
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-s", "16x16", "-pix_fmt", "rgb24", "-r", "60",
                        "-i", "-", "-c:v", "libx264rgb", "-crf", "0", path], input=frames.tobytes(), check=True)
        s = spec.resolve({"edit": [{"in": .007, "out": .532}, {"in": .759, "out": 1.234}, {"in": .1, "out": .4}]})
        def read(a, b, fps):
            d = compose.EditFrames(s, path, 16, 16, fps)
            try: return [int(d.read(f / fps)[0, 0, 0]) for f in range(a, b)]
            finally: d.close()
        for fps in (30, 60):
            n = math.ceil(s["duration"] * fps)
            self.assertEqual(read(0, n, fps), read(0, 9, fps) + read(9, 17, fps) + read(17, n, fps))
        self.assertEqual(int(compose.grab(path, 16, 16, 20)[0, 0, 0]), 238, "out-of-range still holds final frame")

    def test_audio_keeps_silence_for_out_of_range_pieces(self):
        wav = str(self.d / "audio.wav")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1", wav], check=True)
        s = spec.resolve({"edit": [{"in": .8, "out": 1.3}, {"in": 2, "out": 2.2}, {"in": 0, "out": .3}],
                          "audio": {"fade_in": 0, "fade_out": 0, "loudness": None}})
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", wav, "-filter_complex", compose.audio_graph(s),
                              "-map", "[cut]", "-f", "f32le", "-"], capture_output=True, check=True).stdout
        samples = np.frombuffer(raw, np.float32)
        self.assertEqual(len(samples), 48000)
        self.assertLess(float(np.max(np.abs(samples[10000:33000]))), 1e-6)
        self.assertGreater(float(np.max(np.abs(samples[35000:]))), .05)

    def test_silent_loudness_and_long_fades(self):
        wav = str(self.d / "audio.wav")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "1", wav], check=True)
        s = spec.resolve({"edit": [{"in": 0, "out": .5}], "audio": {"fade_in": 3, "fade_out": 4}})
        with patch.object(compose, "take_dir", return_value=str(self.d)):
            _, fc = compose.audio_filter(s)
        self.assertNotIn("measured_I=-inf", fc)
        self.assertIn("afade=t=out:st=0.000000:d=0.5", fc)
        subprocess.run(["ffmpeg", "-v", "error", "-i", wav, "-filter_complex", fc, "-map", "[aout]", "-f", "null", "-"], check=True)

    def test_motion_blur_does_not_accumulate_background_grading(self):
        s = spec.resolve({"phone": {"shadow": 0}, "props": {"bg_brightness": -.4},
                          "tracks": {"zoom": [{"t": 0, "v": 1}, {"t": 1, "v": 6}]}})
        r = compose.Renderer.__new__(compose.Renderer)
        r.s, r.W, r.H, r.fps, r.pk, r.output_scale = s, 4, 4, 60, 1, 1
        r.bg, r.vig = compose.plate_settings(s), np.ones((4, 4, 1), np.float32)
        r._phone_layers = lambda _: None
        r._warp_phone = lambda *_: (np.zeros((4, 4, 3), np.float32), np.zeros((4, 4), np.float32))
        r.phone_matrix = r.bg_matrix = lambda _: np.array([[1., 0, 0], [0, 1., 0]])
        r.screen_corners = lambda st: np.array([[st["zoom"] * 1000]])
        plate = np.full((4, 4, 3), 200, np.uint8)
        blurred = r.frame(.5, plate, plate)
        s["motion_blur"] = 0
        np.testing.assert_array_equal(blurred, r.frame(.5, plate, plate))
        self.assertTrue(np.all(plate == 200))

    def test_color_plate_is_atomic_and_media_safe(self):
        bg = dict(spec.DEFAULTS["background"], id="color:#192837", blur=8, brightness=0)
        with patch.object(library, "PLATES", str(self.d)):
            out = library.plate(bg, 64, 64)
            self.assertNotIn("#", os.path.basename(out))
            self.assertEqual(library.probe(out)[:2], library.plate_geometry(bg, 64, 64)[:2])
            self.assertFalse(list(self.d.glob("*.part.mp4")))


class ServerTests(unittest.TestCase):
    def test_atomic_put_and_media_ranges(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(spec, "SPECS", d), patch.object(spec, "CACHE", d):
            srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
            thread = threading.Thread(target=srv.serve_forever, daemon=True); thread.start()
            def req(method, path, body=None, headers=None):
                c = http.client.HTTPConnection(*srv.server_address)
                c.request(method, path, body, headers or {})
                r = c.getresponse(); data = r.read(); status, hdr = r.status, dict(r.getheaders()); c.close()
                return status, hdr, data
            try:
                self.assertEqual(req("PUT", "/api/spec/test", json.dumps({"props": {"zoom": 2}}))[0], 200)
                before = Path(d, "test.json").read_bytes()
                self.assertEqual(req("POST", "/api/new/foreign", headers={"Origin": "https://example.org"})[0], 403)
                self.assertFalse(Path(d, "foreign.json").exists())
                self.assertEqual(req("PUT", "/api/spec/test", '{"fps":0}')[0], 400)
                self.assertEqual(Path(d, "test.json").read_bytes(), before)
                self.assertFalse(list(Path(d).glob("*.part")))
                # Serving a spec through send_file makes a small, deterministic range fixture.
                class Fixture(server.Handler):
                    def route_get(self): self.send_file(str(Path(d, "test.json")))
                srv.RequestHandlerClass = Fixture
                n = len(before)
                for value, code, expected in (("bytes=0-4", 206, before[:5]), (f"bytes=-{n+10}", 206, before),
                                              (f"bytes={n}-", 416, b""), ("bytes=8-2", 416, b"")):
                    status, _, data = req("GET", "/", headers={"Range": value})
                    self.assertEqual(status, code); self.assertEqual(data, expected)
            finally: srv.shutdown(); srv.server_close(); thread.join()

    def test_duplicate_jobs_are_rejected(self):
        release = threading.Event()
        with patch.dict(server.JOBS, {}, clear=True):
            job = server.new_job("test", "fixture", lambda *_: release.wait(2))
            try:
                with self.assertRaises(ValueError): server.new_job("test", "fixture", lambda *_: None)
            finally: release.set()


if __name__ == "__main__": unittest.main()
