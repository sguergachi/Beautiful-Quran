# Promo studio performance

Measured on 2026-10-20 against `47b647fc`, on the i7-8700K host (6 physical
cores, 12 threads). All exports use the same cached Android take, background,
1080×1080 output, 60 fps, motion blur and x264 medium/CRF 14. These are local
lab measurements, not field results for every LAN client.

| Measurement | Before | After |
|---|---:|---:|
| Warm timeline API, median of five requests | 704 ms | 1.7 ms |
| Six-second preview, 496×496 display | 43 fps | 60 fps |
| Preview frame interval, p95 | 33.4 ms | 16.7 ms |
| Preview intervals exceeding 25 ms | 100 | 0 |
| Three-second export, complete process | 18.84 s | 15.31 s |
| Peak aggregate export process RSS | 8,154 MiB | 3,752 MiB |
| Peak aggregate export process threads | 1,329 | 92 |

The preview test used headless Chromium with SwiftShader, without CPU or
network throttling. The final native-video preview also delivered about
57 animation frames/s during a concurrent export (p95 16.8 ms). Animation
cadence does not imply that every captured video frame is unique; the take
retains its recorded cadence. The cold-load trace already had 149 ms LCP
and 0.03 CLS; the final trace had 212 ms LCP and 0.03 CLS. The native
phone image is eligible for LCP, unlike the old canvas. Loading resources
was not the main issue.

The export comparison ran sequentially with no other benchmark work in
parallel. Four workers beat six and eight in that comparison (15.31, 16.64,
19.40 s respectively) and used less memory. Absolute times vary with host
load and the number of motion-blur samples. Export speed is still sensitive
to concurrent applications and emulator work.

## Changes

- Cached playlist WAVs expose an exact sample count in their headers. Reading
  it replaces one ffprobe subprocess per ayah. Timeline fetching no longer
  holds up preview media, and background/geometry load together.
- The live phone uses the browser's native video compositor, clipped and
  positioned with the same geometry as export. It avoids copying the full
  1080×2400 recording into Canvas on every tick. The background canvas is
  opaque and desynchronized; reflected padding covers only the visible crop
  and blur kernel. A cached phone-space shadow follows the camera transform.
- During playback, small recording/timeline clock differences converge with
  a muted-video rate adjustment bounded to 0.96–1.04, with a 15 ms deadband.
  Repeated hard seeks at 70 ms of drift could replay or skip scrolling frames
  inside the moving phone. Scrubs, edit cuts and delays over 300 ms still
  seek exactly; pending seeks keep the latest explicit target. Pause restores
  normal speed. Recitation audio and the export renderer are unchanged.
- The Look tab's phone thumbnail draws from the existing decoded recording
  directly into its small canvas, capped at 1.5 device pixels per CSS pixel.
  Visible playback refreshes at most eight times per second; scrolling it out
  of view stops redraws. It adds no video decoder or full-size scratch image.
- Each export defaults to at most four workers. Decoder/filter/encoder pools
  are bounded, OpenCV uses one thread per worker, and workers run at niceness
  5 or lower priority. The alpha cache is small; float buffers are reused and
  screen premultiplication uses interleaved SIMD operations.
- Recordings and exports queue instead of running together on the same host.
  Separate emulators may still record concurrently. Queue messages identify
  the resource being waited for. Direct CLI renders bypass the editor queue;
  avoid running those during a recording.

## Fidelity and regression checks

Sixteen pre-encode frames matched the old compositor pixel-for-pixel across
camera movement, rotation/tilt, blur and brightness changes. Twelve browser
canvas comparisons differed by at most one 8-bit channel level (including
the cached shadow). Native video retains the original recording resolution;
the exported resolution, ink, timing, sampling, dithering, audio and motion
blur settings are preserved.

Validation: 16 Python regressions, 10 Node tests, strict TypeScript/Vite build,
25 real-input browser checks and the Android JVM suite. Browser checks cover
scrubbing, key drags, resizing, trims, undo, blank videos and duplication.

To repeat a short export benchmark from `tools/promo_studio`, using an
existing take and separate output:

```python
import os, time
import compose, spec
s = spec.load("03-baqarah-alafasy-royal-repeat")
s.update(name="perf-check", edit=[{"in": 0, "out": 3}], duration=3)
start = time.perf_counter()
compose.export(s, out=os.path.join(spec.CACHE, "perf", "perf-check.mp4"))
print("Elapsed seconds:", time.perf_counter() - start)
```

For comparable measurements, use the same spec snapshot, take, cached plate
and machine load. The default queue keeps exports sequential. Performance
work must preserve the recorded wash and the original frame quality.

The preview drift regression uses the cached Al-Baqarah take during the
camera move at 18–24 s. In headless Chromium with SwiftShader, introducing
120 ms of relative clock drift made the old screen seek backward by 117 ms.
The corrected preview had no screen seeks or backward presented frames;
its error converged to 26 ms after five seconds. Both traces delivered
about 60 animation ticks/s (p95 17.3/17.4 ms). This controlled check covers
the timing-correction mechanism, rather than certifying every client GPU.

## Native 2K capture

Android's [software AVC encoder](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android15-release/media/codec2/components/avc/C2SoftAvcEnc.cpp)
limits each dimension to 2560, so capture now bypasses it. The shared emulator
launcher cold-boots capture instances at a native 1440×3200 framebuffer;
merely changing the requested recording size or Android's logical size would
not provide those pixels. Density 560 keeps the same logical reader layout.

The authenticated [EmulatorController API](https://android.googlesource.com/platform/prebuilts/android-emulator/+/master/linux-x86_64/lib/emulator_controller.proto)
supplies RGBA frames and PCM with emulator host timestamps. Pinned PyAV/gRPC
dependencies live in a cached venv. NVENC accepts RGBA directly: CPU RGB→YUV
conversion in the first prototype reduced output to about 26 frames/s, while
direct input reached about 50. The reader takes recorded 52–53 fps after audio onset,
58.8 fps median active cadence, p90 active interval 33 ms and zero recorder
queue drops on the RTX 3080 host. These describe source cadence; the editing
copy repeats frames to 60 fps. PCM peaked at −5.3 dB after verifying the guest
volume, instead of about −39 dB with the old ineffective shell volume command.

The bounded frame queue holds at most three images. Sound has a separate
queue and keeps continuous samples through delivery jitter; genuine gaps
over 30 ms retain their time. Both streams flush on stop. The actual camera
cutout stays in the captured image; the mockup does not add a second camera.
Display corners come from physical display information, rather than the
activity's transient fullscreen insets. 1080p and 2K take keys stay separate.

Verification: 35 Python and 27 Node tests, strict TypeScript/Vite build and the
Android JVM suite. Real browser quality switches loaded separate takes at
1440, 800, 390 and 320 px. The native 2K preview with a visible phone thumbnail
kept a median 16.6 ms animation interval, p95 23.1 ms in local SwiftShader.
In a captured header all 2,358 bright right-side status pixels survived the
mockup mask; the actual camera centroid was x=719.6 on the 1,440-pixel screen.

## Background detail

Background blur now permits zero in both preview and export. The source
plate uses the least blur needed by any key, including zero, so a sharp key
does not inherit baked blur. Zero skips the Gaussian filter, and low-blur
plates retain full spatial resolution rather than the small strongly blurred
cache. Strongly blurred looks keep their existing cache size and performance.
