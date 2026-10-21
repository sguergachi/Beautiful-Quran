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
import audio
import compose
import formats
import library
import phone
import server
import spec
import capture

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
        payload = {"props": spec.PROPS, "g": phone.geometry(1080, 2400), "cases": cases}
        proc = subprocess.run(["node", str(Path(__file__).with_name("editor-app") / "tests" / "parity.ts")],
                              input=json.dumps(payload), capture_output=True, text=True, check=True)
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

    def test_pcm_duration_uses_exact_samples_without_a_subprocess(self):
        import wave
        path = self.d / "fractional.wav"
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(2); wav.setsampwidth(2); wav.setframerate(48000)
            wav.writeframes(bytes(481 * 4))
        with patch.object(audio.subprocess, "run", side_effect=AssertionError("no ffprobe needed")):
            self.assertEqual(audio.duration(path), 481 / 48000)

    def test_capture_cadence_excludes_still_holds_without_hiding_uneven_frames(self):
        # Smooth active frames, a still hold, then some missed frames.
        pts = [i / 60 for i in range(61)] + [2 + i / 30 for i in range(16)]
        with patch.object(capture.subprocess, "run", return_value=type("Probe", (), {"stdout": "\n".join(map(str, pts))})()):
            rates = capture.cadence("take.mkv")
        self.assertLess(rates["capture_fps"], 35)
        self.assertAlmostEqual(rates["active_fps"], 60)
        self.assertAlmostEqual(rates["frame_p90_ms"], 1000 / 30)

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

    def test_fade_in_starts_with_the_first_sound(self):
        from anim import sound_onset
        on = lambda edit: sound_onset(spec.resolve({"edit": edit}), 1.0)
        self.assertEqual(on([{"in": 0, "out": 10}]), 1.0)
        self.assertEqual(on([{"in": 1.5, "out": 10}]), 0.0)
        self.assertAlmostEqual(on([{"in": .2, "out": .8}, {"in": .5, "out": 5}]), 1.1)
        wav = str(self.d / "audio.wav")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=f=440:r=48000", "-t", "4", wav], check=True)
        s = spec.resolve({"edit": [{"in": .4, "out": 4}], "audio": {"fade_in": .5, "fade_out": 0, "loudness": None}})
        with patch.object(compose, "take_dir", return_value=str(self.d)):
            _, fc = compose.audio_filter(s)
        self.assertIn("afade=t=in:st=0.600000:d=0.500000", fc)

    def test_motion_blur_does_not_accumulate_background_grading(self):
        s = spec.resolve({"phone": {"shadow": 0}, "props": {"bg_brightness": -.4},
                          "tracks": {"zoom": [{"t": 0, "v": 1}, {"t": 1, "v": 6}]}})
        r = compose.Renderer.__new__(compose.Renderer)
        r.s, r.W, r.H, r.fps, r.pk, r.output_scale = s, 4, 4, 60, 1, 1
        r.bg, r.vig = compose.plate_settings(s), np.ones((4, 4, 1), np.float32)
        r._vig_rgb = np.repeat(r.vig, 3, axis=2)
        r._blend = np.empty((4, 4, 3), np.float32)
        r._acc = np.empty((4, 4, 3), np.float32)
        r._phone_layers = lambda _: np.zeros((4, 4, 3), np.float32)
        r._warp_phone = lambda *_: (np.zeros((4, 4, 3), np.float32), np.zeros((4, 4), np.float32))
        r.phone_matrix = r.bg_matrix = lambda _: np.array([[1., 0, 0], [0, 1., 0]])
        r.screen_corners = lambda st: np.array([[st["zoom"] * 1000]])
        plate = np.full((4, 4, 3), 200, np.uint8)
        blurred = r.frame(.5, plate, plate)
        s["motion_blur"] = 0
        np.testing.assert_array_equal(blurred, r.frame(.5, plate, plate))
        self.assertTrue(np.all(plate == 200))

    def test_conform_retains_a_still_that_straddles_the_opening_cut(self):
        raw = str(self.d / 'raw.mkv')
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=blue:s=32x32:r=10:d=5',
                        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=5',
                        '-filter_complex', "[0:v]drawbox=color=red:t=fill:enable='gte(t,3)',select='eq(n,0)+gte(t,3)'[v];[1:a]volume=0:enable='lt(t,3)'[a]",
                        '-map', '[v]', '-map', '[a]', '-fps_mode', 'vfr', '-c:v', 'libx264', '-g', '1', '-bf', '0', '-c:a', 'flac', raw], check=True)
        meta = capture.conform(str(self.d), log=lambda _: None, marks={'intro': .5, 'play': 2.9, 'dwell': 1})
        self.assertAlmostEqual(meta['length'], 4.65, delta=.04)
        self.assertLess(meta['lead'], meta['length'])
        first = compose.grab(str(self.d / 'clip.mp4'), 32, 32, 0)[16, 16]
        self.assertGreater(int(first[2]), int(first[0]) + 100, 'the held blue frame precedes the later red image')
        self.assertAlmostEqual(audio.duration(str(self.d / 'audio.wav')), meta['length'], delta=.04)

    def test_renderer_reuse_keeps_cuts_and_new_geometry_independent(self):
        meta = {'width': 96, 'height': 212, 'length': 8, 'lead': 1}
        (self.d / 'meta.json').write_text(json.dumps(meta))
        s = spec.resolve({'motion_blur': 2, 'tracks': {
            'pan_x': [{'t': 0, 'v': .2}, {'t': 1, 'v': .8}],
            'zoom': [{'t': 1, 'v': 1}, {'t': 2, 'v': 1.5}],
        }})
        def renderer():
            with patch.object(compose, 'take_dir', return_value=str(self.d)), patch.object(compose, 'CACHE', str(self.d)), patch.object(library, 'plate', return_value='unused'), patch.object(library, 'probe', return_value=(64, 64, 1)):
                return compose.Renderer(s, .06)
        r = renderer()
        dark = np.zeros((212, 96, 3), np.uint8)
        light = np.full_like(dark, 255)
        plate = np.full((r.ph, r.pw, 3), 120, np.uint8)
        first = r.frame(.5, dark, plate)
        saved = first.copy()
        # Repeated geometry across a cut must use the new screen. A changed
        # zoom and a later return must also match independent renders.
        for t, pixels in [(.5, light), (1.5, light), (.5, dark)]:
            np.testing.assert_array_equal(r.frame(t, pixels, plate), renderer().frame(t, pixels, plate))
        np.testing.assert_array_equal(first, saved)
        self.assertFalse(np.array_equal(saved, r.frame(.5, light, plate)))

    def test_color_plate_is_atomic_and_media_safe(self):
        bg = dict(spec.DEFAULTS["background"], id="color:#192837", blur=8, brightness=0)
        with patch.object(library, "PLATES", str(self.d)):
            out = library.plate(bg, 64, 64)
            self.assertNotIn("#", os.path.basename(out))
            self.assertEqual(library.probe(out)[:2], library.plate_geometry(bg, 64, 64)[:2])
            self.assertFalse(list(self.d.glob("*.part.mp4")))

    def test_zero_blur_keeps_image_detail_and_is_not_baked_into_animated_looks(self):
        from PIL import Image
        source = self.d / 'stripes.png'
        pattern = np.tile(((np.arange(128) // 4) % 2 * 255).astype(np.uint8), (128, 1))
        Image.fromarray(np.repeat(pattern[..., None], 3, axis=2)).save(source)
        s = spec.resolve({'background': {'id': 'file:' + str(source)}, 'props': {'bg_blur': 0}})
        sharp = compose.plate_settings(s)
        self.assertEqual(sharp['blur'], 0)
        self.assertEqual(spec.PROPS['bg_blur']['min'], 0)
        s['props']['bg_blur'] = 36
        s['tracks']['bg_blur'] = [{'t': 0, 'v': 36}, {'t': 1, 'v': 0}]
        self.assertEqual(compose.plate_settings(s)['blur'], 0)
        with patch.object(library, 'PLATES', str(self.d)):
            clear = library.plate(sharp, 128, 128)
            soft = library.plate(dict(sharp, blur=8), 128, 128)
            w, h, _ = library.plate_geometry(sharp, 128, 128)
            clear_pixels = compose.grab(clear, w, h, 0)[16:-16, 16:-16].astype(float)
            soft_pixels = compose.grab(soft, w, h, 0)[16:-16, 16:-16].astype(float)
            self.assertGreater(clear_pixels.std(), soft_pixels.std() * 4)


class FormatTests(unittest.TestCase):
    def test_options_validate_and_name_one_file_per_kind(self):
        s = spec.resolve({"aspect": "9:16"}); s["name"] = "v"
        o = formats.options(None)
        self.assertEqual(formats.filename(s, o), "v.mp4")
        o = formats.options({"format": "hevc", "scale": 2 / 3, "fps": 30})
        self.assertEqual(formats.filename(s, o), "v-720p-30fps.mp4")
        self.assertEqual(formats.describe(s, o), "HEVC · 720p · 30 fps")
        g = formats.options({"format": "gif", "fps": 60, "audio_kbps": 256})
        self.assertEqual((g["fps"], g["audio_kbps"]), (25, 0), "GIFs play at whole-centisecond rates, silently")
        self.assertEqual(formats.filename(s, formats.options({"format": "prores"})), "v.mov")
        for bad in ({"format": "avi"}, {"quality": "max"}, {"scale": 3}, {"fps": 48}, {"audio_kbps": 100}, {"scale": True}):
            with self.assertRaises(ValueError, msg=str(bad)):
                formats.options(bad)

    def test_encoders_and_sound_per_format(self):
        o = lambda f, **k: formats.options({"format": f, **k})
        self.assertIn("libx265", compose.video_args(o("hevc"), 60))
        self.assertIn("yuv420p10le", compose.video_args(o("av1"), 60))
        self.assertIn("prores_ks", compose.video_args(o("prores", quality="best"), 60))
        self.assertEqual(compose.video_args(o("h264", quality="small"), 60)[5], "23")
        self.assertIn("libopus", compose.audio_args(o("vp9", audio_kbps=320)))
        self.assertIn("256k", compose.audio_args(o("vp9", audio_kbps=320)), "Opus tops out at 256 kbps here")
        self.assertIn("pcm_s16le", compose.audio_args(o("prores")))

    def test_exports_list_every_kind_and_follow_a_rename(self):
        d = SCRATCH / "out"
        d.mkdir(exist_ok=True)
        for f in d.iterdir():
            f.unlink()
        for f in ("a.mp4", "a-720p.webm", "a-720p-30fps.gif", "a-b.mp4", "a.txt", "ab.mp4"):
            (d / f).write_bytes(b"x")
        with patch.object(spec, "OUT", str(d)):
            self.assertEqual(sorted(e["file"] for e in server.exports("a")), ["a-720p-30fps.gif", "a-720p.webm", "a.mp4"])
            self.assertEqual([e["file"] for e in server.exports("a-b")], ["a-b.mp4"])


class ServerTests(unittest.TestCase):
    def test_named_new_video_and_copy_preserve_source_and_reject_collisions(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(spec, 'SPECS', d):
            source = {'title': 'Original', 'props': {'zoom': 2.5}, 'edit': [{'in': 3, 'out': 9}]}
            Path(d, 'original.json').write_text(json.dumps(source))
            srv = ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
            thread = threading.Thread(target=srv.serve_forever, daemon=True); thread.start()
            def create(name, body):
                c = http.client.HTTPConnection(*srv.server_address)
                c.request('POST', f'/api/new/{name}', json.dumps(body), {'Content-Type': 'application/json'})
                r = c.getresponse(); status = r.status; r.read(); c.close(); return status
            try:
                self.assertEqual(create('named', {'title': '  Ar-Raḥmān  '}), 200)
                self.assertEqual(json.loads(Path(d, 'named.json').read_text()), {'title': 'Ar-Raḥmān'})
                self.assertEqual(create('copy', {'from': 'original', 'title': 'My copy'}), 200)
                self.assertEqual(json.loads(Path(d, 'copy.json').read_text()), {**source, 'title': 'My copy'})
                self.assertEqual(json.loads(Path(d, 'original.json').read_text()), source)
                self.assertEqual(create('copy', {'title': 'Overwrite'}), 409)
                self.assertEqual(json.loads(Path(d, 'copy.json').read_text())['title'], 'My copy')
                for title in (' ', [], 1):
                    self.assertEqual(create('invalid', {'title': title}), 400)
                    self.assertFalse(Path(d, 'invalid.json').exists())
            finally: srv.shutdown(); srv.server_close(); thread.join()

    def test_export_deletion_is_scoped_versioned_and_excludes_active_renders(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(spec, 'OUT', d), patch.object(spec, 'SPECS', d), patch.object(server, 'JOB_DIR', d), patch.object(server.Jobs, '_loop', lambda _: None), patch.object(server.Jobs, '_start', lambda _, j: j.update(state='running')):
            jobs = server.Jobs()
            paths = {f: Path(d, f) for f in ('video.mp4', 'video-720p-30fps.webm', 'other.mp4', 'video.json', 'take.mkv')}
            for path in paths.values(): path.write_bytes(b'keep')
            srv = ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
            with patch.object(server, 'JOBS', jobs):
                thread = threading.Thread(target=srv.serve_forever, daemon=True); thread.start()
                def delete(name, file, revision, origin=None):
                    c = http.client.HTTPConnection(*srv.server_address)
                    headers = {'Content-Type': 'application/json', **({'Origin': origin} if origin else {})}
                    c.request('DELETE', f'/api/exports/{name}/{file}', json.dumps({'revision': revision}), headers)
                    r = c.getresponse(); status, body = r.status, json.loads(r.read()); c.close()
                    return status, body
                def revision(file): return next(e['revision'] for e in server.exports('video') if e['file'] == file)
                try:
                    old = revision('video.mp4')
                    self.assertEqual(delete('video', 'video.mp4', old, 'https://example.org')[0], 403)
                    self.assertEqual(delete('video', 'other.mp4', old)[0], 400)
                    self.assertEqual(delete('video', '%2e%2e%2fother.mp4', old)[0], 404)
                    self.assertEqual(delete('video', 'video.json', old)[0], 400)
                    # Replacement preserves size and mtime; its inode still
                    # distinguishes the new export from the displayed one.
                    st = paths['video.mp4'].stat()
                    replacement = Path(d, 'replacement')
                    replacement.write_bytes(b'next')
                    os.utime(replacement, ns=(st.st_atime_ns, st.st_mtime_ns))
                    os.replace(replacement, paths['video.mp4'])
                    self.assertEqual(delete('video', 'video.mp4', old)[0], 409)
                    current = revision('video.mp4')
                    job = jobs.submit('render', 'other-video', ['render', 'other-video'], export_file='video.mp4')
                    self.assertEqual(delete('video', 'video.mp4', current)[0], 409)
                    jobs.jobs[job['id']]['state'] = 'queued'
                    self.assertEqual(delete('video', 'video.mp4', current)[0], 409)
                    # Another format stays removable while that path is owned.
                    status, body = delete('video', 'video-720p-30fps.webm', revision('video-720p-30fps.webm'))
                    self.assertEqual(status, 200)
                    self.assertEqual([e['file'] for e in body['exports']], ['video.mp4'])
                    jobs.jobs[job['id']].update(state='done', result=str(paths['video.mp4']))
                    self.assertTrue(jobs.public(jobs.jobs[job['id']])['export_exists'])
                    self.assertEqual(delete('video', 'video.mp4', current)[0], 200)
                    self.assertFalse(jobs.public(jobs.jobs[job['id']])['export_exists'])
                    self.assertEqual(delete('video', 'video.mp4', current)[0], 404)
                    self.assertFalse(paths['video.mp4'].exists())
                    for file in ('other.mp4', 'video.json', 'take.mkv'):
                        self.assertEqual(paths[file].read_bytes(), b'keep')
                finally: srv.shutdown(); srv.server_close(); thread.join()

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

    def test_job_rules(self):
        """Duplicates are refused, renders run one at a time, captures one per emulator."""
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(server, "JOB_DIR", d), \
                patch.object(server.Jobs, "_loop", lambda self: None), \
                patch.object(server.Jobs, "_start", lambda self, j: j.update(state="running", pid=None, _proc=None)):
            jobs = server.Jobs()
            first = jobs.submit("render", "a", ["render", "a"])
            with self.assertRaises(ValueError): jobs.submit("render", "a", ["render", "a"])
            second = jobs.submit("render", "b", ["render", "b"])
            self.assertEqual(second["state"], "queued")
            jobs.cancel(second["id"])
            c1 = jobs.submit("capture", "x", ["capture", "x"], serial="emulator-5650")
            c2 = jobs.submit("capture", "y", ["capture", "y"], serial="emulator-5652")
            c3 = jobs.submit("capture", "z", ["capture", "z"], serial="emulator-5650")
            self.assertEqual([c1["state"], c2["state"], c3["state"]], ["queued", "queued", "queued"])
            jobs.get(first["id"])["state"] = "done"
            jobs._pump()
            self.assertEqual([jobs.get(j["id"])["state"] for j in (c1, c2, c3)], ["running", "running", "queued"])
            render = jobs.submit("render", "c", ["render", "c"])
            self.assertEqual(render["state"], "queued", "export yields the host while emulators record")
            self.assertIn("recording", render["msg"])
            jobs.cancel(c3["id"])
            self.assertEqual(jobs.get(c3["id"])["state"], "cancelled")
            self.assertEqual(set(jobs.busy_serials()), {"emulator-5650", "emulator-5652"})

    def test_job_progress_file(self):
        """A job's JSON lines become its stage, progress, result and final state."""
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(server, "JOB_DIR", d), \
                patch.object(server.Jobs, "_loop", lambda self: None):
            jobs = server.Jobs()
            f = Path(d, "j.jsonl")
            lines = [{"type": "stage", "key": "record", "msg": "Recording"}, {"type": "progress", "value": 0.5, "msg": "half"},
                     {"type": "log", "msg": "detail"}, {"type": "result", "value": "/take"}]
            f.write_text("\n".join(json.dumps(x) for x in lines) + "\nnot json\n{\"type\": \"prog")    # a partial last line waits
            class Done:
                def poll(self): return 0
            j = {"id": "j", "kind": "capture", "name": "n", "state": "running", "stage": None, "stages": [], "progress": 0,
                 "msg": "", "log": [], "error": None, "file": str(f), "_offset": 0, "_proc": Done(), "pid": None}
            self.assertTrue(jobs._follow(j))
            self.assertEqual((j["state"], j["stage"]["key"], j["result"]), ("done", "record", "/take"))
            self.assertIn("not json", j["log"])
            self.assertLess(j["_offset"], f.stat().st_size, "the partial line is read next time")

    def test_shared_take_jobs_and_cancelled_queue_leave_the_running_capture_alone(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(server, "JOB_DIR", d), \
                patch.object(server.Jobs, "_loop", lambda self: None), \
                patch.object(server.Jobs, "_start", lambda self, j: j.update(state="running")), \
                patch.object(server.emus, "adb") as adb:
            jobs = server.Jobs()
            s = spec.resolve({"title": "submitted"})
            a = jobs.submit("capture", "a", ["capture", "a"], serial="emulator-5650", snapshot=s)
            b = jobs.submit("capture", "b", ["capture", "b"], serial="emulator-5652", snapshot=s)
            r = jobs.submit("render", "r", ["render", "r"], snapshot=s)
            self.assertEqual([a["state"], b["state"], r["state"]], ["running", "queued", "queued"])
            s["title"] = "changed after submission"
            self.assertEqual(spec.load(a["args"][1])["title"], "submitted")
            jobs.cancel(b["id"])
            adb.assert_not_called()
            self.assertEqual(jobs.get(a["id"])["state"], "running")

    def test_restart_replays_a_success_and_preserves_waiting_work(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(server, "JOB_DIR", d), \
                patch.object(server.Jobs, "_loop", lambda self: None), \
                patch.object(server.Jobs, "_start", lambda self, j: j.update(state="running", pid=999999999)):
            jobs = server.Jobs()
            a = jobs.submit("render", "a", ["render", "a"])
            b = jobs.submit("render", "b", ["render", "b"])
            Path(a["file"]).write_text(json.dumps({"type": "result", "value": "/export.mp4"}) + "\n")
            recovered = server.Jobs()
            recovered._follow(recovered.get(a["id"]))
            self.assertEqual(recovered.get(a["id"])["state"], "done")
            self.assertEqual(recovered.get(b["id"])["state"], "queued")
            recovered._pump()
            self.assertEqual(recovered.get(b["id"])["state"], "running")

    def test_concurrent_job_saves_always_leave_valid_persistence(self):
        from concurrent.futures import ThreadPoolExecutor
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(server, "JOB_DIR", d), \
                patch.object(server.Jobs, "_loop", lambda self: None):
            jobs = server.Jobs()
            with ThreadPoolExecutor(8) as pool:
                list(pool.map(lambda _: jobs._save(), range(80)))
            self.assertEqual(json.loads(Path(d, "jobs.json").read_text()), [])


if __name__ == "__main__": unittest.main()
