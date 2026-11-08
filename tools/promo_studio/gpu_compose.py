"""The compositor on the GPU: compose.Renderer.frame as CUDA, for fast exports.

Every output frame is still a pure function of (spec, t) and uses the CPU
compositor's own maths (the same matrices, sampling conventions and order of
operations), fused into one kernel per shutter sub-frame:

    plate sample (reflect border) -> brightness -> vignette -> phone shadow
    -> phone over it -> average the sub-frames -> half-LSB dither -> 8 bit

The phone is area-filtered down to its on-screen size first, like
cv2.INTER_AREA, here as two matrix products with box-overlap weights. The
result matches the CPU frame to within 1-2 of 255 levels; the dither is a
per-pixel hash instead of numpy's RNG, so the noise (not the picture) differs.

Needs CuPy (pip install cupy-cuda13x, see setup_gpu.sh) and an NVIDIA GPU.
Importing this module never fails without them: use available().
"""
import math
import subprocess
import sys

import numpy as np

try:
    import cupy as cp
    import cupyx.scipy.ndimage as cndi
except Exception:                       # no CuPy in this interpreter
    cp = cndi = None

from anim import state_at
import compose as C

SRC = r'''
__device__ __forceinline__ int refl(int i, int n) { int m = 2 * n; i %= m; if (i < 0) i += m; return i >= n ? m - 1 - i : i; }

// bilinear, cv2.warpAffine convention (integer = pixel centre); mode 0 = constant 0, 1 = BORDER_REFLECT
template<int CH, bool REFLECT>
__device__ __forceinline__ void samp(const float* img, int w, int h, float sx, float sy, float* o) {
  int x0 = (int)floorf(sx), y0 = (int)floorf(sy);
  float fx = sx - x0, fy = sy - y0;
  float w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
  #pragma unroll
  for (int c = 0; c < CH; c++) o[c] = 0.f;
  for (int k = 0; k < 4; k++) {
    int xx = x0 + (k & 1), yy = y0 + (k >> 1);
    float wt = k == 0 ? w00 : k == 1 ? w10 : k == 2 ? w01 : w11;
    if (REFLECT) { xx = refl(xx, w); yy = refl(yy, h); }
    else if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
    const float* p = img + ((long)yy * w + xx) * CH;
    #pragma unroll
    for (int c = 0; c < CH; c++) o[c] += wt * p[c];
  }
}

extern "C" __global__ void accum(float* acc, int W, int H,
    const float* plate, int pw, int ph, const float* ib /*6 inverse bg matrix*/, float bright, float vign,
    int has_phone, float sh_strength, const float* shadow, int shw, int shh, const float* is /*6*/,
    const float* ps, const float* pal, int psw, int psh, const float* ip /*6*/)
{
  int x = blockIdx.x * blockDim.x + threadIdx.x, y = blockIdx.y * blockDim.y + threadIdx.y;
  if (x >= W || y >= H) return;
  float bg[3];
  samp<3, true>(plate, pw, ph, ib[0] * x + ib[1] * y + ib[2], ib[3] * x + ib[4] * y + ib[5], bg);
  #pragma unroll
  for (int c = 0; c < 3; c++) {
    if (bright > 0) bg[c] = bg[c] + fminf(1.f, bright) * (1 - bg[c]);
    else if (bright < 0) bg[c] = bg[c] * (1 + fmaxf(-1.f, bright));
  }
  float rx = (x - W * 0.5f) / (W * 0.5f), ry = (y - H * 0.5f) / (H * 0.5f);
  float r = fminf(1.f, sqrtf(rx * rx + ry * ry) / 1.41421356f);
  float vg = 1.f - vign * r * r;
  #pragma unroll
  for (int c = 0; c < 3; c++) bg[c] *= vg;
  if (has_phone) {
    if (sh_strength > 0) {
      float s1[1];
      samp<1, false>(shadow, shw, shh, is[0] * x + is[1] * y + is[2], is[3] * x + is[4] * y + is[5], s1);
      float k = 1.f - sh_strength * s1[0];
      #pragma unroll
      for (int c = 0; c < 3; c++) bg[c] *= k;
    }
    float sx = ip[0] * x + ip[1] * y + ip[2], sy = ip[3] * x + ip[4] * y + ip[5];
    float rgb[3], a[1];
    samp<3, false>(ps, psw, psh, sx, sy, rgb);
    samp<1, false>(pal, psw, psh, sx, sy, a);
    #pragma unroll
    for (int c = 0; c < 3; c++) bg[c] = bg[c] * (1.f - a[0]) + rgb[c];
  }
  float* o = acc + ((long)y * W + x) * 3;
  o[0] += bg[0]; o[1] += bg[1]; o[2] += bg[2];
}

__device__ __forceinline__ float hash01(unsigned x, unsigned y, unsigned f) {
  unsigned h = x * 374761393u + y * 668265263u + f * 2246822519u;
  h = (h ^ (h >> 13)) * 1274126177u; h ^= h >> 16;
  return (h & 0xFFFFFF) * (1.0f / 16777216.0f);
}
__device__ __forceinline__ float q8(float v) { return fminf(255.f, fmaxf(0.f, floorf(v * 255.f + 0.5f))); }

// acc/n + half-LSB dither -> rgb24
extern "C" __global__ void finish_rgb(const float* acc, unsigned char* out, int W, int H, float inv_n, unsigned frame) {
  int x = blockIdx.x * blockDim.x + threadIdx.x, y = blockIdx.y * blockDim.y + threadIdx.y;
  if (x >= W || y >= H) return;
  float n = hash01(x, y, frame) - 0.5f;
  const float* a = acc + ((long)y * W + x) * 3; unsigned char* o = out + ((long)y * W + x) * 3;
  #pragma unroll
  for (int c = 0; c < 3; c++) o[c] = (unsigned char)q8(a[c] * inv_n + n / 255.f);
}

// acc/n + dither -> NV12 (BT.601 limited like swscale's default), one thread per 2x2 block
extern "C" __global__ void finish_nv12(const float* acc, unsigned char* yp, unsigned char* uvp, int W, int H, float inv_n, unsigned frame) {
  int bx = blockIdx.x * blockDim.x + threadIdx.x, by = blockIdx.y * blockDim.y + threadIdx.y;
  if (bx * 2 >= W || by * 2 >= H) return;
  float sr = 0, sg = 0, sb = 0;
  for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
    int x = bx * 2 + i, y = by * 2 + j;
    float n = (hash01(x, y, frame) - 0.5f) / 255.f;
    const float* a = acc + ((long)y * W + x) * 3;
    float r = fminf(1.f, fmaxf(0.f, floorf((a[0] * inv_n + n) * 255.f + 0.5f) / 255.f));
    float g = fminf(1.f, fmaxf(0.f, floorf((a[1] * inv_n + n) * 255.f + 0.5f) / 255.f));
    float b = fminf(1.f, fmaxf(0.f, floorf((a[2] * inv_n + n) * 255.f + 0.5f) / 255.f));
    yp[(long)y * W + x] = (unsigned char)fminf(255.f, fmaxf(0.f, 16.f + 65.481f * r + 128.553f * g + 24.966f * b + 0.5f));
    sr += r; sg += g; sb += b;
  }
  sr *= .25f; sg *= .25f; sb *= .25f;
  unsigned char* uv = uvp + ((long)by * (W / 2) + bx) * 2;
  uv[0] = (unsigned char)fminf(255.f, fmaxf(0.f, 128.f - 37.797f * sr - 74.203f * sg + 112.f * sb + 0.5f));
  uv[1] = (unsigned char)fminf(255.f, fmaxf(0.f, 128.f + 112.f * sr - 93.786f * sg - 18.214f * sb + 0.5f));
}

// phone canvas: P = Fp, with the screen rectangle = Fp + screen*A
extern "C" __global__ void phone_canvas(float* P, const float* Fp, const unsigned char* scr, const float* A3, int Wc, int Hc, int x0, int y0, int sw, int sh) {
  int x = blockIdx.x * blockDim.x + threadIdx.x, y = blockIdx.y * blockDim.y + threadIdx.y;
  if (x >= sw || y >= sh) return;
  long ci = ((long)(y0 + y) * Wc + (x0 + x)) * 3, si = ((long)y * sw + x) * 3;
  for (int c = 0; c < 3; c++) P[ci + c] = Fp[ci + c] + (scr[si + c] * (1.f / 255.f)) * A3[si + c];
}
extern "C" __global__ void u8_to_f32(const unsigned char* a, float* b, long n) {
  long i = (long)blockIdx.x * blockDim.x + threadIdx.x; if (i < n) b[i] = a[i] * (1.f / 255.f);
}
'''

_K = {}


def _kernels():
    """Compile once per process (CuPy also caches the cubin on disk)."""
    if not _K:
        mod = cp.RawModule(code=SRC, options=("-std=c++17",))
        for n in ("accum", "finish_rgb", "finish_nv12", "phone_canvas", "u8_to_f32"):
            _K[n] = mod.get_function(n)
    return _K


B = (16, 16, 1)


def grid(w, h):
    return ((w + 15) // 16, (h + 15) // 16, 1)


def f32(a):
    return cp.asarray(a, dtype=cp.float32)


_STATUS = {}


def available():
    """(ok, reason): a CUDA device that can run our kernels, probed once.
    Catches a missing CuPy, no device, and a driver/library mismatch."""
    if "gpu" not in _STATUS:
        try:
            if cp is None:
                raise RuntimeError("CuPy is not installed in this Python (tools/promo_studio/setup_gpu.sh)")
            if cp.cuda.runtime.getDeviceCount() < 1:
                raise RuntimeError("no CUDA device")
            a = cp.zeros(4, cp.uint8)
            b = cp.empty(4, cp.float32)
            _kernels()["u8_to_f32"]((1,), (4,), (a, b, np.int64(4)))
            cp.cuda.Device().synchronize()
            name = cp.cuda.runtime.getDeviceProperties(0)["name"]
            _STATUS["gpu"] = (True, name.decode() if isinstance(name, bytes) else str(name))
        except Exception as e:
            _STATUS["gpu"] = (False, f"{type(e).__name__}: {e}"[:300])
    return _STATUS["gpu"]


def nvenc_ok(codec):
    """Whether this machine's ffmpeg can open the NVENC encoder `codec`
    (h264_nvenc / hevc_nvenc) on this GPU; probed once per codec."""
    k = "nvenc:" + codec
    if k not in _STATUS:
        try:
            r = subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=s=256x256:d=0.1:r=10",
                                "-c:v", codec, "-f", "null", "-"], capture_output=True, timeout=30)
            _STATUS[k] = r.returncode == 0
        except (OSError, subprocess.SubprocessError):
            _STATUS[k] = False
    return _STATUS[k]


def area_weights(src, dst):
    """cv2.INTER_AREA weights: (dst, src) matrix of box overlaps."""
    scale = src / dst
    W = np.zeros((dst, src), np.float32)
    for i in range(dst):
        a, b = i * scale, (i + 1) * scale
        j0, j1 = int(math.floor(a)), min(src, int(math.ceil(b)))
        for j in range(j0, j1):
            W[i, j] = max(0.0, min(b, j + 1) - max(a, j)) / scale
    return W


class GpuRenderer(C.Renderer):
    cpu_buffers = False            # the full-frame float buffers live on the GPU instead

    def __init__(self, s, scale=1.0):
        super().__init__(s, scale)
        self.Fp_g = f32(self.Fp)
        self.A3_g = f32(self._screen_alpha)
        self.Pa_g = f32(self.Pa)
        self.shadow_g = f32(self.shadow)
        self.P_g = cp.empty_like(self.Fp_g)
        self.acc_g = cp.empty((self.H, self.W, 3), cp.float32)
        self._wcache = {}
        self._alpha_g = {}
        # the host copies of what now lives on the GPU
        self.Fp = self.A = self.Pa = self._screen_alpha = self._phone_rgb = None

    def _weights(self, src, dst):
        k = (src, dst)
        w = self._wcache.get(k)
        if w is None:
            if len(self._wcache) > 64: self._wcache.clear()
            w = self._wcache[k] = cp.asarray(area_weights(src, dst))
        return w

    def _resize(self, P, tw, th):
        Hc, Wc = P.shape[:2]
        wy, wx = self._weights(Hc, th), self._weights(Wc, tw)
        t1 = (wy @ P.reshape(Hc, Wc * 3)).reshape(th, Wc, 3)                       # (th, Wc, 3)
        t2 = cp.ascontiguousarray(t1.transpose(1, 0, 2)).reshape(Wc, th * 3)         # (Wc, th*3)
        t3 = (wx @ t2).reshape(tw, th, 3)                                           # (tw, th, 3)
        return cp.ascontiguousarray(t3.transpose(1, 0, 2))                          # (th, tw, 3)

    def _phone_params(self, P, M):
        """GPU image + alpha to sample, and the 2x3 inverse (output px -> image px)."""
        sc = math.hypot(M[0, 0], M[1, 0])
        Hc, Wc = P.shape[:2]
        if sc < 0.98:
            tw, th = max(8, int(round(Wc * sc))), max(8, int(round(Hc * sc)))
            cached = self._resized_phone
            if cached is not None and cached[0] == (tw, th): Ps = cached[1]
            else:
                Ps = self._resize(P, tw, th); self._resized_phone = ((tw, th), Ps)
            a = self._alpha_g.get((tw, th))
            if a is None:
                if len(self._alpha_g) > 4: self._alpha_g.clear()
                a = self._alpha_g[(tw, th)] = cp.ascontiguousarray(self._resize(self.Pa_g[..., None].repeat(3, axis=2), tw, th)[..., 0])
            S = np.array([[Wc / tw, 0, 0], [0, Hc / th, 0], [0, 0, 1]])
            Mi = (np.vstack([M, [0, 0, 1]]) @ S)[:2]
            Mi[:, 2] += (M[:, :2] @ np.array([0.5 * (Wc / tw - 1), 0.5 * (Hc / th - 1)]))
            return Ps, a, Mi
        return P, self.Pa_g, M

    @staticmethod
    def _inv(M):
        return cp.asarray(np.linalg.inv(np.vstack([M, [0, 0, 1]]))[:2].astype(np.float32).reshape(-1))

    def compose(self, t, screen_u8_g, plate_u8_g, frame_no=0):
        """Accumulate the frame into self.acc_g; returns the sub-frame count."""
        s, K = self.s, _kernels()
        plate = cp.empty(plate_u8_g.shape, cp.float32)
        K['u8_to_f32'](((plate.size + 255) // 256,), (256,), (plate_u8_g, plate, np.int64(plate.size)))
        P = None
        if screen_u8_g is not None:
            self.P_g[...] = self.Fp_g   # static bezel; only the screen rectangle changes
            y0, x0, sw, sh = self.scr[0].start, self.scr[1].start, self.sw, self.sh
            K['phone_canvas'](grid(sw, sh), B, (self.P_g, self.Fp_g, screen_u8_g, self.A3_g, np.int32(self.P_g.shape[1]), np.int32(self.P_g.shape[0]), np.int32(x0), np.int32(y0), np.int32(sw), np.int32(sh)))
            P = self.P_g
        self._resized_phone = None
        shutter = float(s.get("motion_blur", 1.0)) / self.fps
        states = [state_at(s, t)]
        if shutter > 0:
            a = self.screen_corners(state_at(s, t - shutter / 2)); b = self.screen_corners(state_at(s, t + shutter / 2))
            n = int(min(24, max(1, math.ceil(float(np.abs(a - b).max()) / 1.5))))
            if n > 1: states = [state_at(s, t + shutter * (i / (n - 1) - 0.5)) for i in range(n)]
        self.acc_g.fill(0)
        sh_strength = float(s["phone"]["shadow"]); vign = float(s["background"]["vignette"])
        blurred = {}
        for st in states:
            extra = math.sqrt(max(0.0, (st["bg_blur"] * self.output_scale) ** 2 - self.bg["blur"] ** 2)) * self.pk
            pl = plate
            if extra > 0.3:
                key = round(extra, 3)
                pl = blurred.get(key)
                if pl is None:
                    pl = blurred[key] = cndi.gaussian_filter(plate, (extra, extra, 0), mode="reflect", truncate=4.0)
            ib = self._inv(self.bg_matrix(st))
            if P is not None:
                M = self.phone_matrix(st)
                q, pad = self.shadow_q, self.shadow_pad
                Ms = (np.vstack([M, [0, 0, 1]]) @ np.array([[q, 0, -pad], [0, q, -pad], [0, 0, 1]]))[:2].copy()
                Ms[1, 2] += self.H * 0.022 * st["zoom"] * st["phone_scale"]
                Ps, al, Mi = self._phone_params(P, M)
                args = (self.acc_g, np.int32(self.W), np.int32(self.H), pl, np.int32(pl.shape[1]), np.int32(pl.shape[0]), ib,
                        np.float32(st["bg_brightness"]), np.float32(vign), np.int32(1), np.float32(sh_strength), self.shadow_g,
                        np.int32(self.shadow_g.shape[1]), np.int32(self.shadow_g.shape[0]), self._inv(Ms),
                        Ps, al, np.int32(Ps.shape[1]), np.int32(Ps.shape[0]), self._inv(Mi))
            else:
                z = cp.zeros(1, cp.float32)
                args = (self.acc_g, np.int32(self.W), np.int32(self.H), pl, np.int32(pl.shape[1]), np.int32(pl.shape[0]), ib,
                        np.float32(st["bg_brightness"]), np.float32(vign), np.int32(0), np.float32(0), z, np.int32(1), np.int32(1), self._inv(np.eye(3)[:2]),
                        z, z, np.int32(1), np.int32(1), self._inv(np.eye(3)[:2]))
            K['accum'](grid(self.W, self.H), B, args)
        return len(states)

    def finish_rgb(self, n, frame_no, out=None):
        out = out if out is not None else cp.empty((self.H, self.W, 3), cp.uint8)
        _kernels()['finish_rgb'](grid(self.W, self.H), B, (self.acc_g, out, np.int32(self.W), np.int32(self.H), np.float32(1.0 / n), np.uint32(frame_no)))
        return out

    def finish_nv12(self, n, frame_no, out=None):
        out = out if out is not None else cp.empty(self.W * self.H * 3 // 2, cp.uint8)
        _kernels()['finish_nv12'](grid(self.W // 2, self.H // 2), B, (self.acc_g, out[: self.W * self.H], out[self.W * self.H:], np.int32(self.W), np.int32(self.H), np.float32(1.0 / n), np.uint32(frame_no)))
        return out
