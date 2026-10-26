"""Timestamped emulator frames + PCM, encoded on the host rather than Android.

Uses the authenticated, loopback-only EmulatorController API shipped with the
SDK. No screenshot scaling above the native framebuffer, shared-memory tearing,
or reconstructed recitation audio. Optional dependencies live in a cached venv.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import queue
import signal
import subprocess
import sys
import threading
import time
from fractions import Fraction


def python(cache, log=print):
    """Prepare the host recorder's isolated, pinned runtime once per machine."""
    root = Path(cache) / 'capture-venv'
    requirements = Path(__file__).with_name('requirements-capture.txt')
    fingerprint = hashlib.sha256(requirements.read_bytes()).hexdigest()
    stamp = root / '.requirements'
    Path(cache).mkdir(parents=True, exist_ok=True)
    with open(Path(cache) / 'capture-runtime.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not stamp.exists() or stamp.read_text() != fingerprint:
            log('Preparing host recording dependencies (first use)')
            subprocess.run([sys.executable, '-m', 'venv', str(root)], check=True)
            subprocess.run([str(root / 'bin/python'), '-m', 'pip', 'install', '--disable-pip-version-check',
                            '-r', str(requirements)], check=True, stdout=subprocess.DEVNULL)
            stamp.write_text(fingerprint)
    return str(root / 'bin/python')


def audio_position(timestamp_us, origin_us, next_sample, samples, rate=48000):
    """Keep PCM contiguous through small delivery jitter; preserve real gaps.

    Both media clocks are emulator host timestamps. Corrections over 30 ms
    preserve pauses/discontinuities instead of shifting later sound in time.
    Returns (output start, leading samples to trim, silence to insert).
    """
    target = round((timestamp_us - origin_us) * rate / 1_000_000)
    if abs(target - next_sample) <= round(rate * .03):
        return next_sample, 0, 0
    trim = min(samples, max(0, next_sample - target))
    return max(target, next_sample), trim, max(0, target - next_sample)


def atomic_json(path, data):
    temp = Path(str(path) + '.tmp')
    temp.write_text(json.dumps(data))
    os.replace(temp, path)


def controller(serial, cache):
    import grpc
    import grpc_tools
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk'))
    proto = sdk / 'emulator/lib/emulator_controller.proto'
    if not proto.exists():
        raise RuntimeError(f'Android emulator API schema is missing: {proto}')
    target = Path(cache) / ('emulator-api-' + hashlib.sha256(proto.read_bytes()).hexdigest()[:12])
    target.mkdir(parents=True, exist_ok=True)
    with open(target / 'schema.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not (target / 'emulator_controller_pb2.py').exists():
            subprocess.run([sys.executable, '-m', 'grpc_tools.protoc', '-I' + str(proto.parent),
                            '-I' + str(Path(grpc_tools.__file__).parent / '_proto'),
                            '--python_out=' + str(target), str(proto)], check=True)
    sys.path.insert(0, str(target))
    import emulator_controller_pb2 as pb
    result = subprocess.check_output(['adb', '-s', serial, 'emu', 'avd', 'discoverypath'], text=True, timeout=10)
    path = next((Path(x.strip()) for x in result.splitlines() if x.startswith('/')), None)
    if path is None or not path.is_file():
        raise RuntimeError('This emulator has no host capture endpoint. Restart it from Emulators.')
    settings = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
    # The token stays in process memory, never command arguments or recording metadata.
    metadata = (('authorization', 'Bearer ' + settings['grpc.token']),)
    channel = grpc.insecure_channel('127.0.0.1:' + settings['grpc.port'], options=[
        ('grpc.max_receive_message_length', 64 * 1024 * 1024)])
    return channel, metadata, pb


def record(args):
    import av
    import grpc
    channel, metadata, pb = controller(args.serial, args.cache)
    rpc = '/android.emulation.control.EmulatorController/'
    get = channel.unary_unary(rpc + 'getScreenshot', request_serializer=pb.ImageFormat.SerializeToString,
                             response_deserializer=pb.Image.FromString)
    native = get(pb.ImageFormat(format=1), metadata=metadata, timeout=15)
    nw, nh = native.format.width, native.format.height
    if nw < args.width or nh < args.height or nw * args.height != nh * args.width:
        raise RuntimeError(f'Native framebuffer is {nw}×{nh}; {args.width}×{args.height} capture requires a native 2K emulator. Restart it from Emulators.')
    first = native if (nw, nh) == (args.width, args.height) else get(
        pb.ImageFormat(format=1, width=args.width, height=args.height), metadata=metadata, timeout=15)
    w, h = first.format.width, first.format.height
    if (w, h) != (args.width, args.height):
        raise RuntimeError(f'Emulator returned {w}×{h}, expected {args.width}×{args.height}')
    stopped = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopped.set())
    frames, sound = queue.Queue(3), queue.Queue(200)
    calls, errors = [], []
    stats = {'backend': 'emulator-grpc', 'native_width': nw, 'native_height': nh,
             'width': w, 'height': h, 'video_frames': 0, 'dropped_frames': 0, 'audio_samples': 0}
    out = av.open(args.output, 'w', format='matroska')
    vs = out.add_stream('h264_nvenc', rate=60)
    vs.width, vs.height, vs.pix_fmt = w, h, 'rgba'
    vs.time_base = Fraction(1, 1_000_000)
    vs.options = {'preset': 'p1', 'tune': 'ull', 'rc': 'constqp', 'qp': '14', 'bf': '0'}
    # RGBA goes straight to NVENC: CPU RGB→YUV conversion halved the cadence.
    # Fail before the on-camera sequence if this host lacks an encoder.
    try:
        vs.codec_context.open()
    except Exception as error:
        out.close(); channel.close()
        raise RuntimeError('Host 2K recording needs a working NVIDIA H.264 encoder (NVENC).') from error
    aud = out.add_stream('flac', rate=48000)
    aud.layout = 'stereo'
    origin = time.time_ns() // 1000
    started = time.monotonic()
    stats.update(encoder='h264_nvenc', started_wall_us=origin, clock='emulator-host-us')

    def reader(kind, method, request, deserialize, target):
        call = channel.unary_stream(rpc + method, request_serializer=type(request).SerializeToString,
                                    response_deserializer=deserialize)(request, metadata=metadata)
        calls.append(call)
        try:
            for message in call:
                if stopped.is_set(): break
                stamp = message.timestampUs if kind == 'video' else message.timestamp
                if stamp - origin > (time.monotonic() - started + 1) * 1_000_000:
                    raise RuntimeError('Emulator media clock jumped during recording')
                if kind == 'video':
                    if not message.image: continue
                    if (message.format.width, message.format.height) != (w, h):
                        raise RuntimeError('Display size changed during recording')
                    try: target.put_nowait(message)
                    except queue.Full: stats['dropped_frames'] += 1
                else:
                    while not stopped.is_set():
                        try: target.put(message, timeout=.1); break
                        except queue.Full: pass
        except Exception as error:
            if not stopped.is_set(): errors.append(f'{kind} stream: {error}'); stopped.set()

    threads = [threading.Thread(target=reader, args=('video', 'streamScreenshot', pb.ImageFormat(
        format=1, width=w, height=h), pb.Image.FromString, frames), daemon=True),
        threading.Thread(target=reader, args=('audio', 'streamAudio', pb.AudioFormat(
            samplingRate=48000, channels=1, format=1), pb.AudioPacket.FromString, sound), daemon=True)]
    last_video, next_audio, last_image = -1, 0, first.image

    def picture(data, timestamp):
        nonlocal last_video
        if timestamp <= last_video: return
        frame = av.VideoFrame(w, h, 'rgba')
        frame.planes[0].update(data)
        frame.pts, frame.time_base = timestamp, Fraction(1, 1_000_000)
        for packet in vs.encode(frame): out.mux(packet)
        last_video = timestamp
        stats['video_frames'] += 1

    def pcm(data, position):
        nonlocal next_audio
        if not data: return
        count = len(data) // 4
        frame = av.AudioFrame(format='s16', layout='stereo', samples=count)
        frame.planes[0].update(data)
        frame.sample_rate, frame.pts, frame.time_base = 48000, position, Fraction(1, 48000)
        for packet in aud.encode(frame): out.mux(packet)
        next_audio = position + count
        stats['audio_samples'] += count

    def audio_packet(message, end_sample=None):
        position, trim, gap = audio_position(message.timestamp, origin, next_audio, len(message.audio) // 4)
        if end_sample is not None and position >= end_sample: return
        if gap:
            while next_audio < position:
                pcm(bytes(min(4800, position - next_audio) * 4), next_audio)
        data = message.audio[trim * 4:]
        if end_sample is not None: data = data[:max(0, end_sample - position) * 4]
        pcm(data, position)

    try:
        picture(last_image, 0)
        pcm(bytes(480 * 4), 0)
        for thread in threads: thread.start()
        atomic_json(args.ready, stats)
        while not stopped.is_set() and time.monotonic() - started < args.limit:
            # Audio has its own small queue: a burst cannot evict video frames.
            for _ in range(20):
                try: message = sound.get_nowait()
                except queue.Empty: break
                audio_packet(message)
            try: message = frames.get(timeout=.005)
            except queue.Empty: continue
            last_image = message.image
            picture(last_image, max(0, message.timestampUs - origin))
    finally:
        duration_us = round((time.monotonic() - started) * 1_000_000)
        stopped.set()
        for call in calls: call.cancel()
        for thread in threads:
            if thread.ident: thread.join(timeout=2)
        channel.close()
        # Extend the last real image through a still hold. Fresh frames keep
        # their production timestamps; no interpolated motion is invented.
        picture(last_image, max(last_video + 1, duration_us))
        end_sample = round(duration_us * 48000 / 1_000_000)
        while not sound.empty(): audio_packet(sound.get_nowait(), end_sample)
        while next_audio < end_sample:
            pcm(bytes(min(4800, end_sample - next_audio) * 4), next_audio)
        for stream in (vs, aud):
            for packet in stream.encode(None): out.mux(packet)
        out.close()
        stats.update(duration_us=duration_us, errors=errors)
        atomic_json(args.metadata, stats)
    if errors: raise RuntimeError('; '.join(errors))
    print(json.dumps(stats), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--cache', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--ready', required=True)
    parser.add_argument('--metadata', required=True)
    parser.add_argument('--width', type=int, required=True)
    parser.add_argument('--height', type=int, required=True)
    parser.add_argument('--limit', type=float, default=1800)
    record(parser.parse_args())
