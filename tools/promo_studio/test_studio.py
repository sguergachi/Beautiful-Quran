"""Offline regressions: python3 -m unittest discover -s tools/promo_studio -p 'test_*.py'."""
import glob
import http.client
import http.server
import json
import math
import os
from pathlib import Path
import random
import shutil as shutil_mod
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
from http.server import ThreadingHTTPServer

import numpy as np

import anim
import audio
import compose
import formats
import gpu_compose
import gpu_env
import library
import phone
import server
import spec
import spec_store
import capture

SCRATCH = Path(spec.CACHE) / "test"
SCRATCH.mkdir(parents=True, exist_ok=True)
spec_store.HISTORY = str(SCRATCH / "spec-history")        # tests never touch the real history


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

    def test_gaps_are_finite_nonnegative_and_preserve_source_clock(self):
        for gap in (-1, float('nan'), float('inf'), True, '2'):
            with self.subTest(gap=gap), self.assertRaises(ValueError):
                spec.resolve({'edit': [{'in': 4, 'out': 8, 'gap': gap}]})
        s = spec.resolve({'edit': [{'in': 4, 'out': 8, 'gap': 3}, {'in': 10, 'out': 12, 'gap': 2}]})
        self.assertEqual(s['duration'], 11)
        self.assertEqual(spec.take_needed(s), 12)
        self.assertEqual(spec.resolve(s)['edit'], s['edit'])
        self.assertEqual(anim.source_time(s, 0), (0, None))
        self.assertEqual(anim.source_time(s, 3)[0], 4)
        self.assertEqual(anim.source_time(s, 7), (0, None))
        self.assertEqual(anim.source_time(s, 9)[0], 10)
        self.assertEqual(anim.video_times(s, 4, 12), [(3, 7), (9, 11)])
        self.assertEqual(anim.sound_onset(s, 5), 4)

    def test_editor_math_parity(self):
        rng, cases = random.Random(886), []
        for i in range(32):
            s = spec.resolve({"aspect": list(spec.ASPECTS)[i % 4], "edit": [{"in": .5, "out": 2, "gap": .3 if i % 2 else 0}, {"in": .2, "out": 1, "gap": .6 if i % 3 else 0}],
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

    def test_audio_gaps_have_exact_duration_and_silence_between_clips(self):
        wav = str(self.d / 'tone.wav')
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=1', wav], check=True)
        s = spec.resolve({'edit': [{'in': 0, 'out': .25, 'gap': .25}, {'in': .5, 'out': .75, 'gap': .5}]})
        raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', wav, '-filter_complex', compose.audio_graph(s),
                              '-map', '[cut]', '-ac', '1', '-f', 'f32le', '-'], capture_output=True, check=True).stdout
        samples = np.frombuffer(raw, np.float32)
        self.assertEqual(len(samples), round(s['duration'] * 48000))
        for a, b in ((0, .25), (.5, 1)):
            self.assertLess(float(np.max(np.abs(samples[round(a * 48000):round(b * 48000)]))), 1e-6)
        for a, b in ((.28, .48), (1.03, 1.23)):
            self.assertGreater(float(np.max(np.abs(samples[round(a * 48000):round(b * 48000)]))), .05)

    def test_gap_frames_do_not_decode_or_reuse_recording_pixels(self):
        s = spec.resolve({'edit': [{'in': 4, 'out': 5, 'gap': 1}, {'in': 8, 'out': 9, 'gap': 2}]})
        with patch.object(compose, 'Frames') as frames:
            frames.return_value.read.return_value = 'recording pixels'
            decoder = compose.EditFrames(s, 'unused', 16, 16, 60)
            self.assertIsNone(decoder.read(.5))
            frames.assert_not_called()
            self.assertEqual(decoder.read(1), 'recording pixels')
            self.assertEqual(frames.call_args.args[-1], 4)
            self.assertIsNone(decoder.read(2))
            frames.return_value.close.assert_called_once()
            self.assertIsNone(decoder.read(3))
            self.assertEqual(decoder.read(4), 'recording pixels')
            self.assertEqual(frames.call_args.args[-1], 8)
            decoder.close()

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
        meta = capture.conform(str(self.d), log=lambda _: None, marks={'intro': .5, 'play': 2.9, 'dwell': 1},
                               build={'version': '0.12 (13)', 'commit': 'ac1246014', 'extra': 'dropped'})
        self.assertEqual(meta['build'], {'version': '0.12 (13)', 'commit': 'ac1246014'}, 'the take records the app build it shows')
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
        for t, pixels in [(.5, light), (1.5, light), (.5, None), (.5, dark)]:
            np.testing.assert_array_equal(r.frame(t, pixels, plate), renderer().frame(t, pixels, plate))
        gap = r.frame(.5, None, plate)
        s['phone']['shadow'] = 0
        np.testing.assert_array_equal(gap, r.frame(.5, None, plate), 'a gap must not retain the phone shadow')
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


class AudioRepairTests(unittest.TestCase):
    RATE = 48000

    def voice(self, seconds, f0=140.0, seed=0):
        """A vowel-like tone: a few harmonics, a little breath noise, 16-bit safe."""
        t = np.arange(int(seconds * self.RATE)) / self.RATE
        x = sum(a * np.sin(2 * np.pi * f0 * k * t + k) for k, a in enumerate((.5, .3, .2, .12, .08), 1))
        x = x + np.random.default_rng(seed).normal(0, .004, len(t))
        return np.stack([x, .9 * x], axis=1).astype(np.float32) * .5

    def test_a_dropout_cut_into_sound_is_found_and_a_pause_is_not(self):
        x = self.voice(1.0)
        x[20000:21700] = 0                                  # 35 ms of exact silence mid-sound
        quiet = self.voice(1.0); quiet[:] *= .001; quiet[10000:14000] = 0
        self.assertEqual(audio.find_dropouts(x, self.RATE), [(20000, 21700)])
        self.assertEqual(audio.find_dropouts(quiet, self.RATE), [], "a quiet passage is not a dropout")
        lead = np.concatenate([np.zeros((30000, 2), np.float32), self.voice(.5)])
        self.assertEqual(audio.find_dropouts(lead, self.RATE), [], "silence before the sound starts is not a dropout")
        short = self.voice(1.0); short[20000:20040] = 0     # 0.8 ms: a glitch, found even though it is brief
        self.assertEqual(audio.find_dropouts(short, self.RATE), [(20000, 20040)])

    def test_the_fill_follows_the_voice_and_joins_both_edges(self):
        x = self.voice(1.0)
        for n in (600, 1700):                               # 12 ms and 35 ms
            hole = x.copy(); hole[20000:20000 + n] = 0
            y, rep = audio.conceal(hole, [(20000, 20000 + n)], self.RATE)
            truth, got = x[20000:20000 + n], y[20000:20000 + n]
            snr = 10 * np.log10(np.mean(truth ** 2) / np.mean((got - truth) ** 2))
            self.assertGreater(snr, 12, f"{n} samples: the fill is close to the real sound ({snr:.1f} dB)")
            mid = got[n // 3:2 * n // 3, 0]
            self.assertGreater(float(np.sqrt(np.mean(mid ** 2))), .6 * float(np.sqrt(np.mean(truth[n // 3:2 * n // 3, 0] ** 2))), "the level does not pinch mid-gap")
            steps = np.abs(np.diff(y[:, 0]))
            self.assertLess(steps[19990:20010 + n].max(), 1.5 * np.abs(np.diff(x[:, 0])).max(), "no click at either edge")
            np.testing.assert_array_equal(y[:20000], hole[:20000])
            np.testing.assert_array_equal(y[20000 + n:], hole[20000 + n:])
            self.assertEqual(rep, [(20000 / self.RATE, n * 1000 / self.RATE, True)])

    def test_a_long_hole_is_faded_not_invented(self):
        x = self.voice(2.0); x[30000:30000 + 24000] = 0     # half a second missing
        y, rep = audio.conceal(x, audio.find_dropouts(x, self.RATE), self.RATE)
        self.assertFalse(rep[0][2])
        self.assertEqual(float(np.abs(y[30000:30000 + 24000]).max()), 0.0, "nothing made up")
        self.assertLess(abs(float(y[29999, 0])), abs(float(x[29999, 0])) + 1e-9, "the sound ahead of the hole fades out")
        self.assertLess(abs(float(y[54000, 0])), abs(float(x[54000, 0])) + 1e-9, "and in")

    def test_heal_wav_repairs_in_place_once_and_keeps_the_original(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            path = os.path.join(d, "audio.wav")
            x = self.voice(1.5); x[30000:31700] = 0
            import wave
            with wave.open(path, "wb") as w:
                w.setnchannels(2); w.setsampwidth(2); w.setframerate(self.RATE)
                w.writeframes((x * 32768).astype(np.int16).tobytes())
            rec = audio.heal_wav(path)
            self.assertEqual([(r[0], round(r[1])) for r in rec["dropouts"]], [(round(30000 / self.RATE, 3), 35)])
            with wave.open(path) as w: healed = np.frombuffer(w.readframes(w.getnframes()), np.int16)
            with wave.open(os.path.join(d, "audio.orig.wav")) as w: orig = np.frombuffer(w.readframes(w.getnframes()), np.int16)
            self.assertEqual(len(healed), len(orig), "same length: the take's clock is untouched")
            self.assertTrue(np.all(orig.reshape(-1, 2)[30000:31700] == 0), "the original keeps its hole")
            self.assertGreater(np.abs(healed.reshape(-1, 2)[30000:31700]).max(), 1000, "the repaired file does not")
            stamp = os.stat(path).st_mtime_ns
            self.assertEqual(audio.heal_wav(path), rec)
            self.assertEqual(os.stat(path).st_mtime_ns, stamp, "an already-healed take is not rewritten")
            self.assertIsNone(audio.heal_wav(os.path.join(d, "missing.wav")))

    def test_a_new_take_never_inherits_the_previous_takes_backup(self):
        import wave
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            path = os.path.join(d, "audio.wav")
            def write(x):
                with wave.open(path, "wb") as w:
                    w.setnchannels(2); w.setsampwidth(2); w.setframerate(self.RATE); w.writeframes((x * 32768).astype(np.int16).tobytes())
            first = self.voice(1.5); first[30000:31700] = 0
            write(first); audio.heal_wav(path)
            self.assertTrue(os.path.exists(os.path.join(d, "audio.orig.wav")))
            second = self.voice(1.5, f0=170.0, seed=3)               # a re-take lands in the same directory
            write(second)
            rec = audio.heal_wav(path)
            self.assertEqual(rec["dropouts"], [], "the new take is clean and is left alone")
            with wave.open(path) as w: got = np.frombuffer(w.readframes(w.getnframes()), np.int16)
            np.testing.assert_array_equal(got, (second * 32768).astype(np.int16).reshape(-1))
            self.assertFalse(os.path.exists(os.path.join(d, "audio.orig.wav")), "the old take's backup is gone")

    def test_intro_ticks_are_silenced_but_a_real_intro_is_kept(self):
        import wave
        for intro_level, silenced in ((.004, True), (.3, False)):
            with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
                path = os.path.join(d, "audio.wav")
                x = np.concatenate([self.voice(3.0) * intro_level / .15, self.voice(2.0)])        # 3 s before the recitation
                x[48000 * 1, :] += .004                                                           # a tick
                with wave.open(path, "wb") as w:
                    w.setnchannels(2); w.setsampwidth(2); w.setframerate(self.RATE); w.writeframes((x * 32768).astype(np.int16).tobytes())
                Path(d, "meta.json").write_text(json.dumps({"lead": 3.0, "length": 5.0, "marks": {}}))
                rec = audio.heal_wav(path)
                with wave.open(path) as w: got = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, 2)
                self.assertEqual(rec["muted_before_s"] > 0, silenced)
                self.assertEqual(int(np.abs(got[:int(2.5 * self.RATE)]).max()) == 0, silenced)
                np.testing.assert_array_equal(got[3 * self.RATE:], (x * 32768).astype(np.int16)[3 * self.RATE:], "the recitation is never touched")

    def speech(self, seconds, seed=0):
        """Aperiodic, voice-like sound (wandering pitch, syllable envelope, breath): every moment is unique, so alignment has one answer."""
        rng = np.random.default_rng(seed)
        n = int(seconds * self.RATE); t = np.arange(n) / self.RATE
        f0 = 120 + 40 * np.cumsum(rng.normal(0, .0004, n)).clip(-1, 1)
        phase = 2 * np.pi * np.cumsum(f0) / self.RATE
        env = np.clip(np.sin(2 * np.pi * np.cumsum(2.5 + rng.normal(0, .002, n)) / self.RATE) ** 2 + .15, 0, 1)
        x = env * sum(a * np.sin(k * phase) for k, a in enumerate((.5, .3, .2, .1), 1)) + .02 * rng.normal(0, 1, n)
        return np.stack([x, x], axis=1).astype(np.float32) * .6

    def test_a_take_that_slipped_off_the_apps_clock_is_put_back_at_its_starting_offset(self):
        import wave
        rate, lead, offset = self.RATE, 3.0, .140
        ref = self.speech(40.0)
        at = int((lead - offset) * rate)
        # what the recorder produced: the recitation from `at`, with ~22.7 ms of it dropped every 5 s from 12 s on, and a zero gap
        take = np.zeros((int(38 * rate), 2), np.float32)
        pos, src = at, 0
        pieces = []
        for t_drop in (12, 17, 22, 27, 32):
            n = int((t_drop - pos / rate) * rate)
            pieces.append(ref[src:src + n]); src += n + int(.0227 * rate); pos += n
        pieces.append(ref[src:src + int(5 * rate)])
        body = np.concatenate(pieces)
        take[at:at + len(body)] = body[:len(take) - at]
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            path = os.path.join(d, "audio.wav")
            with wave.open(path, "wb") as w:
                w.setnchannels(2); w.setsampwidth(2); w.setframerate(rate); w.writeframes((take * 32768).astype(np.int16).tobytes())
            Path(d, "meta.json").write_text(json.dumps({"lead": lead, "length": 38.0, "app": {"reciter": "alafasy", "surah": 2, "ayah": 156, "repeat": "off"}, "marks": {}}))
            before = audio.recitation_lags(take, ref, lead, step=2.0)
            self.assertGreater(max(l for _, l, _ in before) - min(l for _, l, _ in before), 100, "the recording slipped by ~113 ms")
            with patch.object(audio, "clean_recitation", return_value=ref):
                rec = audio.heal_wav(path)
            self.assertAlmostEqual(rec["app_clock"]["offset_ms"], 140.0, delta=.1)
            self.assertGreater(rec["app_clock"]["drift_ms"], 100)
            self.assertEqual(rec["dropouts"], [], "a rebuilt take has nothing left to report, so it is not recorded again")
            with wave.open(path) as w: got = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, 2).astype(np.float32) / 32768
            self.assertEqual(len(got), len(take), "same length")
            after = audio.recitation_lags(got, ref, lead, step=2.0)
            self.assertLess(max(l for _, l, _ in after) - min(l for _, l, _ in after), .1, "one constant offset from the first word to the last")
            self.assertEqual(float(np.abs(got[:at]).max()), 0.0, "nothing before the recitation")
            i0, i1 = int((lead + 1) * rate), int((lead + 8) * rate)                       # before the first slip: the recording is untouched in effect
            err = got[i0:i1] - take[i0:i1]
            self.assertGreater(10 * np.log10(np.mean(take[i0:i1] ** 2) / np.mean(err ** 2)), 40)

    def test_a_take_that_never_left_the_apps_clock_is_left_alone(self):
        import wave
        rate, lead = self.RATE, 3.0
        ref = self.speech(30.0, seed=2)
        take = np.zeros((int(28 * rate), 2), np.float32)
        at = int((lead - .1) * rate); take[at:] = ref[:len(take) - at]
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            path = os.path.join(d, "audio.wav")
            with wave.open(path, "wb") as w:
                w.setnchannels(2); w.setsampwidth(2); w.setframerate(rate); w.writeframes((take * 32768).astype(np.int16).tobytes())
            Path(d, "meta.json").write_text(json.dumps({"lead": lead, "length": 28.0, "app": {"reciter": "alafasy", "surah": 2, "ayah": 156, "repeat": "off"}, "marks": {}}))
            with patch.object(audio, "clean_recitation", return_value=ref):
                rec = audio.heal_wav(path)
            self.assertEqual(rec["app_clock"], {}, "no slip, nothing rebuilt")
            with wave.open(path) as w: got = np.frombuffer(w.readframes(w.getnframes()), np.int16)
            want = (take * 32768).astype(np.int16).reshape(-1, 2)
            k = int(lead * rate)
            np.testing.assert_array_equal(got.reshape(-1, 2)[k:], want[k:], "the recitation is exactly as recorded")


class AccelTests(unittest.TestCase):
    def test_accel_option_validates_and_defaults_to_auto(self):
        self.assertEqual(formats.options(None)["accel"], "auto")
        for a in formats.ACCELS:
            self.assertEqual(formats.options({"accel": a})["accel"], a)
        with self.assertRaises(ValueError):
            formats.options({"accel": "tpu"})

    def test_nvenc_quality_follows_the_cpu_scale(self):
        self.assertEqual([formats.nvenc_cq("h264", q) for q in formats.QUALITIES], [19, 22, 26, 31])
        self.assertEqual([formats.nvenc_cq("hevc", q) for q in formats.QUALITIES], [21, 24, 28, 33])

    def test_accel_choice_falls_back_only_when_asked_to(self):
        with patch.object(gpu_compose, "available", return_value=(False, "CuPy is not installed")):
            self.assertEqual(compose._pick_accel("auto"), (False, "no GPU export: CuPy is not installed"))
            self.assertEqual(compose._pick_accel("cpu")[0], False)
            with self.assertRaisesRegex(RuntimeError, "not available: CuPy"):
                compose._pick_accel("gpu")
        with patch.object(gpu_compose, "available", return_value=(True, "RTX")):
            self.assertEqual(compose._pick_accel("auto"), (True, "RTX"))
            self.assertEqual(compose._pick_accel("cpu")[0], False, "CPU stays an explicit choice")

    def test_chunks_have_equal_cost_not_equal_frames(self):
        # still frames cost 1, a camera move 24: equal frame counts would hand two workers 90% of the work
        costs = np.array([1.] * 100 + [24.] * 50 + [1.] * 100 + [24.] * 50)
        for parts in (4, 8):
            b = compose.balanced_bounds(costs, parts)
            self.assertEqual((b[0], b[-1]), (0, len(costs)))
            self.assertTrue(all(x < y for x, y in zip(b, b[1:])), "boundaries strictly increase")
            share = [costs[b[i]:b[i + 1]].sum() / costs.sum() for i in range(len(b) - 1)]
            self.assertLess(max(share) * parts, 1.25, f"{parts} parts: {share}")
        self.assertEqual(compose.balanced_bounds(np.ones(10), 1), [0, 10])
        self.assertEqual(compose.balanced_bounds(np.ones(3), 5)[0::len(compose.balanced_bounds(np.ones(3), 5)) - 1], [0, 3], "more parts than frames still covers every frame")

    def test_frame_costs_price_motion_blur_samples(self):
        r = compose.Renderer.__new__(compose.Renderer)
        r.fps = 60
        r.subframe_count = lambda t: 24 if 1 <= t < 2 else 1
        c = compose.frame_costs(r, 240, base=4)
        self.assertEqual(c[0], 5)
        self.assertEqual(c[90], 28)
        self.assertEqual(len(c), 240)

    def test_chunk_encoder_is_nvenc_for_h264_and_hevc_only(self):
        o = lambda f, **k: formats.options({"format": f, **k})
        with patch.object(gpu_compose, "nvenc_ok", return_value=True):
            pix, a = compose.gpu_video_args(o("h264", quality="high"), 60)
            self.assertEqual((pix, a[a.index("-c:v") + 1], a[a.index("-cq") + 1]), ("nv12", "h264_nvenc", "22"))
            self.assertEqual(compose.gpu_video_args(o("hevc"), 60)[1][1], "hevc_nvenc")
            pix, a = compose.gpu_video_args(o("prores"), 60)
            self.assertEqual((pix, a), ("rgb24", compose.video_args(o("prores"), 60)), "other formats keep their CPU encoder")
        with patch.object(gpu_compose, "nvenc_ok", return_value=False):
            self.assertEqual(compose.gpu_video_args(o("h264"), 60), ("rgb24", compose.video_args(o("h264"), 60)))

    def test_render_jobs_stay_on_the_system_python_without_the_gpu_venv(self):
        with patch.object(gpu_env, "VENV", "/nonexistent/venv"), patch("os.execv") as ex:
            self.assertIsNone(gpu_env.python())
            gpu_env.reexec_if_needed()
            ex.assert_not_called()
            self.assertFalse(gpu_env.status(refresh=True)["available"])
        gpu_env.status(refresh=True)


@unittest.skipUnless(gpu_compose.available()[0], "needs CuPy and an NVIDIA GPU (tools/promo_studio/setup_gpu.sh)")
class GpuCompositorTests(unittest.TestCase):
    """The GPU frame is the CPU frame: same maths, so within a couple of
    levels of 255 (the dither noise differs, the picture does not)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.d = Path(self.tmp.name)
        (self.d / 'meta.json').write_text(json.dumps({'width': 96, 'height': 212, 'length': 8, 'lead': 1}))
        self.addCleanup(self.tmp.cleanup)

    def renderers(self, s, scale=.12):
        with patch.object(compose, 'take_dir', return_value=str(self.d)), patch.object(compose, 'CACHE', str(self.d)), \
                patch.object(library, 'plate', return_value='unused'), patch.object(library, 'probe', return_value=(64, 64, 1)):
            return compose.Renderer(s, scale), gpu_compose.GpuRenderer(s, scale)

    def gpu_frame(self, g, t, screen, plate, n_frame=0):
        cp = gpu_compose.cp
        n = g.compose(t, None if screen is None else cp.asarray(screen), cp.asarray(plate))
        return cp.asnumpy(g.finish_rgb(n, n_frame))

    def assert_same(self, cpu, gpu, label):
        d = np.abs(cpu.astype(np.int16) - gpu.astype(np.int16))
        self.assertLessEqual(int(d.max()), 4, f"{label}: worst pixel differs by {int(d.max())}")
        self.assertLess(float(d.mean()), .6, f"{label}: mean difference {float(d.mean()):.3f}")

    def test_gpu_frames_match_the_cpu_compositor(self):
        s = spec.resolve({'motion_blur': 2, 'props': {'bg_brightness': .2}, 'tracks': {
            'pan_x': [{'t': 0, 'v': .3}, {'t': 1, 'v': .8}], 'rotate': [{'t': 0, 'v': 0}, {'t': 1, 'v': 20}],
            'zoom': [{'t': 1, 'v': 1}, {'t': 2, 'v': 1.8}], 'phone_tilt': [{'t': 0, 'v': 0}, {'t': 2, 'v': 25}]}})
        c, g = self.renderers(s)
        yy, xx = np.mgrid[0:c.ph, 0:c.pw]
        plate = np.stack([xx * 255 // c.pw, yy * 255 // c.ph, (xx + yy) * 255 // (c.pw + c.ph)], axis=2).astype(np.uint8)
        yy, xx = np.mgrid[0:212, 0:96]
        screen = np.stack([xx * 2, yy, 255 - yy], axis=2).astype(np.uint8)
        for t, scr, label in [(.5, screen, 'moving, blurred'), (1.5, screen, 'zooming'), (.5, None, 'a gap shows no phone')]:
            self.assert_same(c.frame(t, scr, plate), self.gpu_frame(g, t, scr, plate), label)

    def test_extra_background_blur_matches(self):
        s = spec.resolve({'motion_blur': 0, 'tracks': {'bg_blur': [{'t': 0, 'v': 0}, {'t': 1, 'v': 12}]}})
        c, g = self.renderers(s)
        yy, xx = np.mgrid[0:c.ph, 0:c.pw]
        plate = (((xx // 4 + yy // 4) % 2) * 255).astype(np.uint8)[..., None].repeat(3, axis=2)
        self.assert_same(c.frame(.8, None, plate), self.gpu_frame(g, .8, None, plate), 'blur ramps up past the plate\'s own')

    def test_nv12_has_the_cpu_exports_colours(self):
        s = spec.resolve({'motion_blur': 0})
        c, g = self.renderers(s)
        plate = np.full((c.ph, c.pw, 3), (200, 90, 40), np.uint8)
        cp = gpu_compose.cp
        n = g.compose(.5, None, cp.asarray(plate))
        rgb, nv = cp.asnumpy(g.finish_rgb(n, 3)), cp.asnumpy(g.finish_nv12(n, 3))
        def yuv(raw, fmt):
            return np.frombuffer(subprocess.run(['ffmpeg', '-v', 'error', '-f', 'rawvideo', '-pix_fmt', fmt, '-s', f'{c.W}x{c.H}', '-i', '-',
                                                 '-f', 'rawvideo', '-pix_fmt', 'yuv420p', '-'], input=raw.tobytes(), capture_output=True, check=True).stdout, np.uint8).astype(int)
        d = np.abs(yuv(rgb, 'rgb24') - yuv(nv, 'nv12'))
        self.assertLessEqual(int(d.max()), 3, "the GPU's RGB->NV12 is swscale's default BT.601 conversion")


class ServiceTests(unittest.TestCase):
    """The server is built not to stay down: systemd notify + watchdog, a build
    that cannot stop it, a busy port waited for, a unit that always restarts."""

    def test_notify_reaches_systemd_and_is_a_noop_outside_it(self):
        import socket as _socket
        import service
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("NOTIFY_SOCKET", None)
            self.assertFalse(service.notify("READY=1"))
        with tempfile.TemporaryDirectory(dir="/tmp") as d:
            path = os.path.join(d, "notify")
            srv = _socket.socket(_socket.AF_UNIX, _socket.SOCK_DGRAM); srv.bind(path); srv.settimeout(2)
            self.addCleanup(srv.close)
            with patch.dict(os.environ, {"NOTIFY_SOCKET": path}):
                self.assertTrue(service.notify("READY=1"))
            self.assertEqual(srv.recv(64), b"READY=1")

    def test_watchdog_pings_only_while_the_server_answers(self):
        import service
        self.assertIsNone(service.start_watchdog("127.0.0.1", 1, interval=None, ping=lambda m: None)) if "WATCHDOG_USEC" not in os.environ else None
        with patch.dict(os.environ, {"WATCHDOG_USEC": "60000000"}):
            self.assertEqual(service.watchdog_interval(), 30.0)
        pings = []
        class Health(http.server.BaseHTTPRequestHandler):
            alive = True
            def do_GET(self):
                self.send_response(200 if Health.alive else 500); self.end_headers(); self.wfile.write(b"{}")
            def log_message(self, *a): pass
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), Health)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        service.start_watchdog("127.0.0.1", httpd.server_address[1], interval=.1, ping=pings.append)
        time.sleep(.45)
        self.assertGreaterEqual(len(pings), 2)
        self.assertEqual(set(pings), {"WATCHDOG=1"})
        Health.alive = False                                    # a server that stops answering stops pinging: systemd restarts it
        time.sleep(.25); n = len(pings); time.sleep(.45)
        self.assertEqual(len(pings), n)

    def test_a_failed_build_serves_the_last_good_one_and_never_stops_the_server(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            app, dist = Path(d, "app"), Path(d, "app", "dist")
            (app / "src").mkdir(parents=True); dist.mkdir()
            (dist / "index.html").write_text("old"); os.utime(dist / "index.html", (1, 1))
            (app / "src" / "a.ts").write_text("x")                       # newer than the build: a rebuild is due
            (app / "node_modules").mkdir()
            boom = subprocess.CalledProcessError(1, "npm")
            with patch.object(server, "APP_DIR", str(app)), patch.object(server, "DIST", str(dist)), \
                    patch.object(shutil_mod, "which", return_value="/usr/bin/npm"), patch.object(subprocess, "run", side_effect=boom):
                server.ensure_built()                                    # does not raise: index.html exists
                (dist / "index.html").unlink()
                with self.assertRaises(subprocess.CalledProcessError):
                    server.ensure_built()                                # nothing to serve at all: that is fatal

    def test_a_busy_port_is_waited_for_not_fatal(self):
        import errno
        busy = OSError(errno.EADDRINUSE, "Address already in use")
        calls = []
        def fake(addr, handler):
            calls.append(addr)
            if len(calls) < 3: raise busy
            return "server"
        with patch.object(server, "ThreadingHTTPServer", fake), patch.object(time, "sleep", lambda s: None):
            self.assertEqual(server.bind("127.0.0.1", 8765), "server")
        self.assertEqual(len(calls), 3)
        with patch.object(server, "ThreadingHTTPServer", side_effect=OSError(errno.EACCES, "denied")):
            with self.assertRaises(OSError): server.bind("127.0.0.1", 80)

    def test_the_unit_always_restarts_and_leaves_running_jobs_alone(self):
        here = Path(__file__).parent
        text = (here / "promo-studio.service.in").read_text()
        for needle in ("Restart=always", "StartLimitIntervalSec=0", "WatchdogSec=", "Type=notify", "KillMode=process", "WantedBy=default.target"):
            self.assertIn(needle, text)
        rendered = text.replace("@HERE@", str(here)).replace("@PYTHON@", sys.executable)
        self.assertNotIn("@", rendered.replace("%h", ""), "every placeholder is filled")
        if shutil_mod.which("systemd-analyze"):
            with tempfile.TemporaryDirectory(dir="/tmp") as d:
                unit = Path(d, "promo-studio.service"); unit.write_text(rendered)
                r = subprocess.run(["systemd-analyze", "--user", "verify", str(unit)], capture_output=True, text=True)
                self.assertNotIn("Unknown key", r.stderr + r.stdout)
                self.assertNotIn("Unknown section", r.stderr + r.stdout)


class SpecStoreTests(unittest.TestCase):
    """Edits are never lost: atomic and fsynced, refused when stale, versioned outside the repo, recoverable."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(self.tmp.cleanup)
        self.d = self.tmp.name
        self.specs, self.hist = os.path.join(self.d, "specs"), os.path.join(self.d, "history")
        os.makedirs(self.specs)
        for target, name, value in ((spec, "SPECS", self.specs), (spec_store, "HISTORY", self.hist)):
            patcher = patch.object(target, name, value); patcher.start(); self.addCleanup(patcher.stop)

    A = {"title": "A", "edit": [{"in": 0, "out": 10}], "tracks": {"zoom": [{"t": 0, "v": 1}, {"t": 2, "v": 2}]}}
    B = {"title": "B", "edit": [{"in": 0, "out": 10}], "tracks": {"zoom": [{"t": 0, "v": 1}, {"t": 3, "v": 3}]}}

    def test_a_crash_while_saving_leaves_the_old_version_and_no_litter(self):
        spec_store.write("v", self.A)
        before = Path(self.specs, "v.json").read_bytes()
        with patch("os.replace", side_effect=OSError("power cut")):
            with self.assertRaises(OSError):
                spec_store.write("v", self.B)
        self.assertEqual(Path(self.specs, "v.json").read_bytes(), before)
        self.assertEqual([f for f in os.listdir(self.specs) if f.endswith(".part")], [], "no temp file left behind")

    def test_data_and_directory_are_synced_to_disk_before_a_save_counts(self):
        with patch("os.fsync", wraps=os.fsync) as sync:
            spec_store.write("v", self.A)
        self.assertGreaterEqual(sync.call_count, 2, "the file, then its directory")

    def test_a_stale_save_is_refused_with_the_current_version_and_nothing_is_lost(self):
        r1 = spec_store.write("v", self.A)
        r2 = spec_store.write("v", self.B, expect=r1)
        self.assertNotEqual(r1, r2)
        stale = {**self.A, "title": "from a tab that was open before"}
        with self.assertRaises(spec_store.SpecConflict) as c:
            spec_store.write("v", stale, expect=r1)
        self.assertEqual((c.exception.raw["title"], c.exception.rev), ("B", r2))
        self.assertEqual(spec_store.read("v")[0]["title"], "B", "the refused save changed nothing")
        spec_store.stash("v", stale)
        spec_store.write("v", stale, expect=r1, force=True)               # "keep mine"
        self.assertEqual(spec_store.read("v")[0]["title"], stale["title"])
        kept = {h["reason"]: h for h in spec_store.history("v")}
        self.assertIn("replaced", kept, "the version it overwrote is kept")
        self.assertIn("unsaved", kept, "and so is the version that was refused")

    def test_every_replaced_version_can_be_restored_and_a_deleted_video_comes_back(self):
        r = spec_store.write("v", self.A)
        with patch.object(spec_store, "THROTTLE_S", 0):
            r = spec_store.write("v", self.B, expect=r)
        versions = spec_store.history("v")
        self.assertTrue(any(v["title"] == "A" for v in versions), "A was kept when B replaced it")
        a = next(v for v in versions if v["title"] == "A")
        raw, rev = spec_store.restore("v", a["id"])
        self.assertEqual(raw["title"], "A")
        self.assertTrue(any(v["reason"] == "before-restore" and v["title"] == "B" for v in spec_store.history("v")), "restoring keeps what it replaces")
        spec_store.delete("v")
        self.assertFalse(os.path.exists(os.path.join(self.specs, "v.json")))
        self.assertEqual([d["name"] for d in spec_store.deleted()], ["v"])
        dead = spec_store.deleted()[0]
        spec_store.restore("v", dead["id"])
        self.assertEqual(spec_store.read("v")[0]["title"], "A")
        self.assertEqual(spec_store.deleted(), [])

    def test_history_is_thinned_but_never_loses_what_was_marked(self):
        now = time.time()
        for i in range(120):                                              # one per minute over two hours
            spec_store.snapshot("v", spec_store.encode({**self.A, "title": f"t{i}"}), "save", at=now - i * 60)
        spec_store.snapshot("v", spec_store.encode({**self.A, "title": "doomed"}), "deleted", at=now - 100 * 86400)
        spec_store.prune("v", now)
        got = spec_store.history("v")
        self.assertLessEqual(len(got), spec_store.KEEP_RECENT + 6, "two hours of minutes are thinned to the recent ones plus one per hour (and the marked one)")
        self.assertLess(len(got), 60, "far fewer than the 121 versions saved")
        self.assertEqual(got[0]["title"], "t0", "newest first")
        self.assertTrue(any(v["title"] == "doomed" for v in got), "a deleted video's last version is kept")

    def test_a_broken_spec_is_put_back_on_start_and_crash_litter_is_cleared(self):
        spec_store.write("good", self.A)
        spec_store.snapshot("good", spec_store.encode(self.A), "created")
        Path(self.specs, "good.json").write_text('{"title": "half writ')          # torn by something outside the store
        Path(self.specs, "empty.json").write_text("")
        spec_store.snapshot("empty", spec_store.encode(self.B), "created")
        Path(self.specs, ".good.json.abc.part").write_text("{")
        os.utime(Path(self.specs, ".good.json.abc.part"), (1, 1))
        lonely = Path(self.specs, "lonely.json"); lonely.write_text(json.dumps(self.A))
        said = []
        fixed = dict(spec_store.recover(said.append))
        self.assertEqual(spec_store.read("good")[0]["title"], "A")
        self.assertEqual(spec_store.read("empty")[0]["title"], "B")
        self.assertEqual(set(fixed), {"good", "empty"})
        self.assertFalse(list(Path(self.specs).glob("*.part")))
        self.assertTrue(spec_store.history("lonely"), "every spec gets a version to return to")
        self.assertTrue(any(v["reason"] == "corrupt" for v in spec_store.history("good")), "the broken file is kept too")

    def test_new_copy_and_rename_are_exclusive_and_keep_history(self):
        spec_store.create("one", self.A)
        with self.assertRaises(FileExistsError): spec_store.create("one", self.B)
        spec_store.duplicate("one", "two")
        with self.assertRaises(FileExistsError): spec_store.duplicate("one", "two")
        with patch.object(spec_store, "THROTTLE_S", 0):
            spec_store.write("two", self.B)
        spec_store.rename("two", "three")
        self.assertFalse(os.path.exists(os.path.join(self.specs, "two.json")))
        self.assertEqual(spec_store.read("three")[0]["title"], "B")
        self.assertTrue(any(v["title"] == "A" for v in spec_store.history("three")), "the history moved with the name")

    def test_many_writers_at_once_always_leave_a_valid_spec_and_history(self):
        def worker(i):
            for k in range(15):
                spec_store.write("v", {**self.A, "title": f"w{i}-{k}"})
        threads = [threading.Thread(target=worker, args=(i,)) for i in range(6)]
        for t in threads: t.start()
        for t in threads: t.join()
        raw, rev = spec_store.read("v")
        self.assertEqual(rev, spec_store.current_revision("v"))
        for v in spec_store.history("v"):
            self.assertTrue(v["pieces"] == 1)
        self.assertFalse(list(Path(self.specs).glob("*.part")))

    def test_the_http_save_refuses_empty_stale_and_destructive_writes(self):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        thread = threading.Thread(target=srv.serve_forever, daemon=True); thread.start()
        def req(method, path, body=None, headers=None):
            c = http.client.HTTPConnection(*srv.server_address)
            c.request(method, path, body, headers or {})
            r = c.getresponse(); data = r.read(); c.close()
            return r.status, (json.loads(data) if data[:1] in (b"{", b"[") else data)
        try:
            st, r = req("PUT", "/api/spec/v", json.dumps(self.A)); self.assertEqual(st, 200); rev1 = r["rev"]
            self.assertEqual(req("GET", "/api/spec/v")[1]["rev"], rev1, "a load tells the editor its revision")
            before = Path(self.specs, "v.json").read_bytes()
            self.assertEqual(req("PUT", "/api/spec/v", "")[0], 400, "an empty body is refused")
            self.assertEqual(req("PUT", "/api/spec/v", "{}")[0], 400, "an empty video never replaces a real one")
            self.assertEqual(req("PUT", "/api/spec/v", "[1,2]")[0], 400)
            self.assertEqual(Path(self.specs, "v.json").read_bytes(), before)
            st, r = req("PUT", "/api/spec/v", json.dumps(self.B), {"If-Match": rev1}); self.assertEqual(st, 200); rev2 = r["rev"]
            st, r = req("PUT", "/api/spec/v", json.dumps(self.A), {"If-Match": rev1})
            self.assertEqual((st, r["conflict"], r["rev"], r["raw"]["title"]), (409, True, rev2, "B"), "a stale tab is told what is there now")
            self.assertEqual(spec_store.read("v")[0]["title"], "B")
            st, r = req("PUT", "/api/spec/v", json.dumps(self.A), {"If-Match": rev1, "X-Force": "1"}); self.assertEqual(st, 200)
            self.assertEqual(req("DELETE", "/api/spec/v")[0], 200)
            self.assertEqual([d["name"] for d in req("GET", "/api/trash")[1]["deleted"]], ["v"])
            st, hist = req("GET", "/api/spec/v/history"); self.assertEqual(st, 200)
            self.assertTrue(any(v["reason"] == "deleted" for v in hist["versions"]))
            deleted = next(v for v in hist["versions"] if v["reason"] == "deleted")
            st, r = req("POST", "/api/spec/v/restore", json.dumps({"id": deleted["id"]})); self.assertEqual(st, 200)
            self.assertEqual(req("GET", "/api/spec/v")[1]["raw"]["title"], "A")
            self.assertEqual(req("POST", "/api/spec/v/restore", json.dumps({"id": "../../etc/passwd"}))[0], 400)
            self.assertEqual(req("POST", "/api/spec/v/stash", json.dumps({"raw": self.B}))[0], 200)
            self.assertFalse(list(Path(self.specs).glob("*.part")))
        finally:
            srv.shutdown(); srv.server_close(); thread.join()


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


class TakeBuildTests(unittest.TestCase):
    """A take remembers the app build it was recorded with; the editor warns once the app has moved on."""

    def _check(self, build, *, in_repo=True, behind=0, head_version="0.13 (14)"):
        def fake(*args, **kwargs):
            if args[:2] == ("rev-parse", "--short=9"):
                return "b" * 9
            if args[0] == "cat-file":
                return "" if in_repo else None
            if args[0] == "rev-list":
                self.assertIn("app", args, "only commits that touch the app count")
                return str(behind)
            if args[0] == "log":
                return "\n".join(f"app change {i}" for i in range(behind))
            raise AssertionError(f"unexpected git call: {args}")
        server.emus._compare_build.cache_clear()
        server.emus._HEAD[0] = 0.0
        with patch.object(server.emus, "_git", fake), patch.object(server.emus, "gradle_version", lambda: head_version):
            return server.emus.build_check(build)

    def test_a_take_with_no_recorded_build_is_untracked_not_stale(self):
        for b in (None, {}, {"version": None, "commit": None}):
            self.assertEqual(self._check(b)["state"], "untracked")

    def test_app_commits_since_the_takes_commit_make_it_older(self):
        r = self._check({"version": "0.12 (13)", "commit": "ac1246014"}, behind=3)
        self.assertEqual((r["state"], r["behind"], len(r["changes"])), ("older", 3, 3))
        self.assertEqual((r["take"]["commit"], r["current"]["commit"]), ("ac1246014", "b" * 9))

    def test_no_app_commits_since_means_current_even_if_head_moved(self):
        r = self._check({"version": "0.13 (14)", "commit": "ac1246014"}, behind=0)
        self.assertEqual((r["state"], r["behind"], r["changes"]), ("current", 0, []))

    def test_without_a_known_commit_the_version_code_orders_the_builds(self):
        self.assertEqual(self._check({"version": "0.12 (13)", "commit": None})["state"], "older")
        self.assertEqual(self._check({"version": "0.13 (14)", "commit": None})["state"], "unknown", "same version: cannot tell")
        self.assertEqual(self._check({"version": "0.14 (15)", "commit": None})["state"], "current", "newer than this checkout is no warning")
        self.assertEqual(self._check({"version": "0.12 (13)", "commit": "deadbeef0"}, in_repo=False)["state"], "older", "a commit this checkout lacks falls back to the version")
        self.assertEqual(self._check({"version": "weird", "commit": "deadbeef0"}, in_repo=False)["state"], "unknown")


class PlaybackLogTests(unittest.TestCase):
    def test_playback_reports_append_one_line_each_and_reject_anything_else(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(server, "PLAYBACK_LOG", str(Path(d, "playback.log"))):
            server.log_client({"kind": "playback", "from": 1.5, "jumps": [{"t": 3, "ms": 140}]}, "Zen")
            server.log_client({"kind": "playback", "from": 9}, "Zen")
            lines = [json.loads(l) for l in Path(d, "playback.log").read_text().splitlines()]
            self.assertEqual([l["from"] for l in lines], [1.5, 9])
            self.assertEqual(lines[0]["jumps"][0]["ms"], 140)
            with self.assertRaises(ValueError):
                server.log_client({"kind": "other"})


class PreviewCopyTests(unittest.TestCase):
    """The editor's light copy of a take: same frames and clock, a quarter of the pixels, never during a recording."""

    def _clip(self, d, w, h, secs=1):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=s={w}x{h}:r=60:d={secs}",
                        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(Path(d, "clip.mp4"))], check=True)

    def _probe(self, f):
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets", "-show_entries",
                              "stream=width,height,nb_read_packets,r_frame_rate", "-of", "csv=p=0", str(f)],
                             capture_output=True, text=True, check=True).stdout.strip()
        w, h, rate, n = out.split(",")
        return int(w), int(h), rate, int(n)

    def test_a_large_take_gets_a_frame_exact_copy_at_preview_height(self):
        import preview
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            self._clip(d, 720, 3200)
            self.assertTrue(preview.needed(d))
            self.assertFalse(preview.fresh(d))
            preview.make(d, log=lambda _: None)
            self.assertTrue(preview.fresh(d))
            w, h, rate, n = self._probe(Path(d, preview.NAME))
            self.assertEqual((h, rate), (preview.HEIGHT, "60/1"))
            self.assertEqual(n, self._probe(Path(d, "clip.mp4"))[3], "same frames on the same clock")
            self.assertAlmostEqual(w / h, 720 / 3200, delta=0.01)
            os.utime(Path(d, "clip.mp4"), (time.time() + 5, time.time() + 5))
            self.assertFalse(preview.fresh(d), "a re-recorded take makes the copy stale")

    def test_a_small_take_plays_as_it_is(self):
        import preview
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            self._clip(d, 320, 640)
            self.assertFalse(preview.needed(d))
            self.assertFalse(preview.ensure(d, log=lambda _: None))

    def test_the_copy_waits_while_a_take_is_recording(self):
        import preview
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            self._clip(d, 360, 1800)
            busy = [True]
            with patch.object(preview, "BUSY_POLL", 0.05), patch.object(preview, "make") as make:
                self.assertTrue(preview.ensure(d, busy=lambda: busy[0], log=lambda _: None))
                self.assertTrue(preview.ensure(d, busy=lambda: busy[0], log=lambda _: None), "one copy at a time")
                time.sleep(0.3)
                make.assert_not_called()
                busy[0] = False
                for _ in range(100):
                    if make.called:
                        break
                    time.sleep(0.02)
                make.assert_called_once()


class ApkTests(unittest.TestCase):
    """The Build Latest card: what the APK was built from, and rebuilding it."""

    def _git(self, log_before="f" * 40 + "\x00abcdef123\x00an old commit", dirty=""):
        def fake(*args, **kwargs):
            if args[:2] == ("rev-parse", "HEAD"):
                return "a" * 40
            if args[:2] == ("rev-parse", "--short=9"):
                return "a" * 9
            if args[:3] == ("log", "-1", "--format=%s"):
                return "head commit"
            if args[0] == "status":
                return dirty
            if args[0] == "log":
                return log_before
            raise AssertionError(f"unexpected git call: {args}")
        return fake

    def _apk_paths(self, d):
        apk = str(Path(d, "app-release.apk"))
        return apk, apk + ".json"

    def test_missing_apk_still_reports_head(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            apk, side = self._apk_paths(d)
            with patch.object(server.emus, "DEFAULT_APK", apk), patch.object(server.emus, "APK_SIDECAR", side), \
                    patch.object(server.emus, "_git", self._git()), \
                    patch.object(server.emus, "gradle_version", lambda: "0.12 (13)"):
                info = server.emus.apk_info()
        self.assertEqual((info["exists"], info["up_to_date"], info["commit"]), (False, False, None))
        self.assertEqual((info["head_commit"], info["head_version"]), ("a" * 9, "0.12 (13)"))

    def test_sidecar_reports_the_exact_revision(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            apk, side = self._apk_paths(d)
            Path(apk).write_bytes(b"fake-apk")
            st = os.stat(apk)
            Path(side).write_text(json.dumps({"version": "0.12 (13)", "commit": "a" * 9, "commit_full": "a" * 40,
                                              "commit_message": "head commit", "built_at": st.st_mtime,
                                              "mtime_ns": st.st_mtime_ns, "size_bytes": st.st_size}))
            with patch.object(server.emus, "DEFAULT_APK", apk), patch.object(server.emus, "APK_SIDECAR", side), \
                    patch.object(server.emus, "_git", self._git()), \
                    patch.object(server.emus, "gradle_version", lambda: "0.12 (13)"):
                info = server.emus.apk_info()
        self.assertEqual((info["exact"], info["version"], info["commit"]), (True, "0.12 (13)", "a" * 9))
        self.assertTrue(info["up_to_date"])

    def test_dirty_app_sources_mark_a_matching_build_stale(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            apk, side = self._apk_paths(d)
            Path(apk).write_bytes(b"fake-apk")
            st = os.stat(apk)
            Path(side).write_text(json.dumps({"version": "0.12 (13)", "commit": "a" * 9, "commit_full": "a" * 40,
                                              "commit_message": "head commit", "built_at": st.st_mtime,
                                              "mtime_ns": st.st_mtime_ns, "size_bytes": st.st_size}))
            with patch.object(server.emus, "DEFAULT_APK", apk), patch.object(server.emus, "APK_SIDECAR", side), \
                    patch.object(server.emus, "_git", self._git(dirty=" M app/src/main/Foo.kt")), \
                    patch.object(server.emus, "gradle_version", lambda: "0.12 (13)"):
                info = server.emus.apk_info()
        self.assertTrue(info["head_dirty"])
        self.assertFalse(info["up_to_date"])

    def test_hand_built_apk_is_dated_not_exact(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            apk, side = self._apk_paths(d)
            Path(apk).write_bytes(b"fake-apk")
            with patch.object(server.emus, "DEFAULT_APK", apk), patch.object(server.emus, "APK_SIDECAR", side), \
                    patch.object(server.emus, "_git", self._git()), \
                    patch.object(server.emus, "gradle_version", lambda: "0.12 (13)"):
                info = server.emus.apk_info()
        self.assertEqual((info["exact"], info["commit"], info["commit_message"], info["version"]),
                         (False, "abcdef123", "an old commit", None))
        self.assertFalse(info["up_to_date"])

    def test_apk_build_yields_to_recordings_and_exports(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d, patch.object(server, "JOB_DIR", d), \
                patch.object(server.Jobs, "_loop", lambda self: None), \
                patch.object(server.Jobs, "_start", lambda self, j: j.update(state="running")):
            jobs = server.Jobs()
            cap = jobs.submit("capture", "x", ["capture", "x"], serial="emulator-5650")
            build = jobs.submit("apk-build", "release APK", ["apk-build"])
            self.assertEqual((cap["state"], build["state"]), ("running", "queued"))
            self.assertIn("recording", build["msg"])
            with self.assertRaises(ValueError):
                jobs.submit("apk-build", "release APK", ["apk-build"])
            jobs.get(cap["id"])["state"] = "done"
            jobs._pump()
            self.assertEqual(jobs.get(build["id"])["state"], "running")
            render = jobs.submit("render", "r", ["render", "r"])
            self.assertEqual(render["state"], "queued", "an export waits for the APK build")

    def test_find_jdk21_prefers_a_java_home_with_21(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            bindir = Path(d, "jdk", "bin")
            bindir.mkdir(parents=True)
            java = bindir / "java"
            java.write_text('#!/bin/sh\necho \'openjdk version "21.0.7" 2025-04-15\' >&2\n')
            java.chmod(0o755)
            with patch.dict(os.environ, {"JAVA_HOME": str(Path(d, "jdk"))}):
                self.assertEqual(server.emus._find_jdk21(), str(Path(d, "jdk")))

    def _fake_build(self, apk, code=0, lines=("> Task :app:assembleRelease", "BUILD SUCCESSFUL")):
        class Proc:
            def __init__(self, *a, **k):
                self.stdout = list(lines)
                self.returncode = code
                if code == 0:
                    Path(apk).write_bytes(b"fake-apk")
            def poll(self): return 0
            def terminate(self): pass
        return Proc

    def test_build_apk_streams_tasks_and_records_its_revision(self):
        class Rep:
            def __init__(self): self.stages, self.progresses, self.logs = [], [], []
            def stage(self, key, msg=None, progress=None): self.stages.append(key)
            def progress(self, value, msg=None): self.progresses.append(value)
            def log(self, msg): self.logs.append(msg)
            def result(self, value): pass
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            apk, side = self._apk_paths(d)
            rep = Rep()
            with patch.object(server.emus, "DEFAULT_APK", apk), patch.object(server.emus, "APK_SIDECAR", side), \
                    patch.object(server.emus, "_find_jdk21", lambda: "/fake/jdk"), \
                    patch.object(server.emus, "_git", self._git()), \
                    patch.object(server.emus, "gradle_version", lambda: "0.12 (13)"), \
                    patch("subprocess.Popen", self._fake_build(apk)):
                self.assertEqual(server.emus.build_apk(rep), apk)
            self.assertIn("> Task :app:assembleRelease", rep.logs)
            self.assertEqual(rep.progresses[-1], 1.0)
            written = json.loads(Path(side).read_text())
            self.assertEqual((written["version"], written["commit"], written["size_bytes"]),
                             ("0.12 (13)", "a" * 9, len(b"fake-apk")))

    def test_build_apk_failure_reports_the_tail(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            apk, side = self._apk_paths(d)
            with patch.object(server.emus, "DEFAULT_APK", apk), patch.object(server.emus, "APK_SIDECAR", side), \
                    patch.object(server.emus, "_find_jdk21", lambda: "/fake/jdk"), \
                    patch("subprocess.Popen", self._fake_build(apk, code=1, lines=("> Task :app:kotlin", ":app: FAILED", "e: boom.kt"))):
                with self.assertRaisesRegex(RuntimeError, "FAILED"):
                    server.emus.build_apk()


class InstallTests(unittest.TestCase):
    """Each emulator row names its installed build."""

    DUMPSYS = ("Packages:\n  Package [com.beautifulquran] (deadbeef):\n"
               "    versionCode=13 minSdk=26 targetSdk=35\n    versionName=0.12\n")

    def setUp(self):
        server.emus._APP_CACHE.clear()
        self.addCleanup(server.emus._APP_CACHE.clear)

    def _adb(self, dumpsys=DUMPSYS, boot="boot-1", fail=()):
        def fake(serial, *a, **k):
            if a in fail:
                raise subprocess.TimeoutExpired("adb", 10)
            return {("shell", "getprop", "sys.boot_completed"): "1",
                    ("shell", "pm", "path", server.emus.APP): "package:/data/app/base.apk",
                    ("shell", "nproc"): "6",
                    ("shell", "wm", "size"): "Physical size: 1440x3200",
                    ("shell", "dumpsys", "package", server.emus.APP): dumpsys,
                    ("shell", "cat", "/proc/sys/kernel/random/boot_id"): boot}[a]
        return fake

    def test_dumpsys_version_parses_name_and_code(self):
        self.assertEqual(server.emus.parse_dumpsys_version(self.DUMPSYS), "0.12 (13)")
        self.assertIsNone(server.emus.parse_dumpsys_version("no such package"))

    def test_installed_app_matches_record_on_same_boot(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            recs = str(Path(d, "emu_installs.json"))
            Path(recs).write_text(json.dumps({"emulator-5650": {"version": "0.12 (13)", "commit": "a" * 9,
                                                                "installed_at": 1.0, "boot_id": "boot-1"}}))
            with patch.object(server.emus, "INSTALL_RECORDS", recs), \
                    patch.object(server.emus, "adb", self._adb()):
                inst = server.emus.installed_app("emulator-5650")
        self.assertEqual(inst, {"version": "0.12 (13)", "commit": "a" * 9})

    def test_installed_app_forgets_commit_after_reboot_or_hand_install(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            recs = str(Path(d, "emu_installs.json"))
            Path(recs).write_text(json.dumps({"emulator-5650": {"version": "0.12 (13)", "commit": "a" * 9,
                                                                "installed_at": 1.0, "boot_id": "boot-1"}}))
            with patch.object(server.emus, "INSTALL_RECORDS", recs):
                with patch.object(server.emus, "adb", self._adb(boot="boot-2")):
                    self.assertEqual(server.emus.installed_app("emulator-5650"),
                                     {"version": "0.12 (13)", "commit": None})
                server.emus._APP_CACHE.clear()
                with patch.object(server.emus, "adb", self._adb(dumpsys=self.DUMPSYS.replace("0.12", "0.11"))):
                    self.assertEqual(server.emus.installed_app("emulator-5650"),
                                     {"version": "0.11 (13)", "commit": None})

    def test_record_install_writes_version_commit_and_boot(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            recs = str(Path(d, "emu_installs.json"))
            with patch.object(server.emus, "INSTALL_RECORDS", recs), \
                    patch.object(server.emus, "apk_info", lambda: {"version": "0.12 (13)", "commit": "b" * 9}), \
                    patch.object(server.emus, "adb", self._adb()):
                server.emus.record_install("emulator-5650")
            written = json.loads(Path(recs).read_text())["emulator-5650"]
        self.assertEqual((written["version"], written["commit"], written["boot_id"]), ("0.12 (13)", "b" * 9, "boot-1"))

    def test_record_install_leaves_custom_apk_revision_blank(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            recs = str(Path(d, "emu_installs.json"))
            with patch.object(server.emus, "INSTALL_RECORDS", recs), \
                    patch.object(server.emus, "adb", self._adb()):
                server.emus.record_install("emulator-5650", apk="/tmp/other.apk")
            written = json.loads(Path(recs).read_text())["emulator-5650"]
        self.assertEqual((written["version"], written["commit"]), (None, None))

    def test_info_lists_build_and_survives_dumpsys_failure(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as d:
            recs = str(Path(d, "emu_installs.json"))
            Path(recs).write_text(json.dumps({"emulator-5650": {"version": "0.12 (13)", "commit": "a" * 9,
                                                                "installed_at": 1.0, "boot_id": "boot-1"}}))
            with patch.object(server.emus, "INSTALL_RECORDS", recs), \
                    patch.object(server.emus, "adb", self._adb()):
                d = server.emus.info("emulator-5650")
        self.assertEqual((d["app"], d["app_version"], d["app_commit"], d["error"]),
                         (True, "0.12 (13)", "a" * 9, None))
        server.emus._APP_CACHE.clear()
        with patch.object(server.emus, "adb", self._adb(fail={("shell", "dumpsys", "package", server.emus.APP)})):
            d = server.emus.info("emulator-5650")
        self.assertEqual((d["app"], d["app_version"], d["app_commit"], d["error"]), (True, None, None, None))


class BootReadbackTests(unittest.TestCase):
    """A fresh boot proves its readback path before any take records on it."""

    POISONED = ("INFO | Boot completed in 22837 ms\n"
                "emu-37-2-release/gles_v2_imp.cpp:glReadPixels:3299 error 0x506\n")

    class Rep:
        def __init__(self): self.logs = []
        def stage(self, *a): pass
        def progress(self, *a): pass
        def log(self, msg): self.logs.append(msg)

    def test_readback_errors_flags_failing_reads_only(self):
        self.assertEqual(len(server.emus.readback_errors(self.POISONED)), 1)
        self.assertEqual(server.emus.readback_errors("INFO | Boot completed in 20023 ms\n"), [])
        self.assertEqual(server.emus.readback_errors(
            "emu-37-2-release/gles_v2_imp.cpp:glDeleteTextures:1403 error null ctx\n"), [])

    def _boot(self, healthy, running=()):
        rep = self.Rep()
        with patch.object(server.emus, "serials", lambda: list(running)), \
                patch.object(server.emus, "_boot_fresh", Mock(return_value="emulator-5650")) as fresh, \
                patch.object(server.emus, "_capture_readback_healthy", Mock(side_effect=healthy)) as check, \
                patch.object(server.emus, "stop", Mock()) as stop:
            try:
                result = server.emus.boot(5650, 6, rep)
            except RuntimeError as e:
                result = e
        return result, rep, fresh, check, stop

    def test_boot_adopts_running_emulator_without_check(self):
        result, _, fresh, check, _ = self._boot([], running=["emulator-5650"])
        self.assertEqual(result, "emulator-5650")
        fresh.assert_not_called()
        check.assert_not_called()

    def test_boot_returns_clean_first_try(self):
        result, _, fresh, check, stop = self._boot([True])
        self.assertEqual(result, "emulator-5650")
        self.assertEqual((fresh.call_count, check.call_count), (1, 1))
        stop.assert_not_called()

    def test_boot_reboots_once_on_poison(self):
        result, rep, fresh, _, stop = self._boot([False, True])
        self.assertEqual(result, "emulator-5650")
        self.assertEqual(fresh.call_count, 2)
        stop.assert_called_once_with("emulator-5650")
        self.assertTrue(any("rebooting once" in m for m in rep.logs))

    def test_boot_refuses_twice_poisoned(self):
        result, _, fresh, _, _ = self._boot([False, False])
        self.assertIsInstance(result, RuntimeError)
        self.assertIn("twice", str(result))
        self.assertEqual(fresh.call_count, 2)


if __name__ == "__main__": unittest.main()
