#!/usr/bin/env bash
# One-time: the Python environment for GPU exports (docs/PROMO_STUDIO_PERFORMANCE.md).
# A venv that shares the system packages (numpy, OpenCV, Pillow) and adds CuPy,
# so the system Python is left alone. Render jobs re-exec into it when it exists.
#   tools/promo_studio/setup_gpu.sh            # create / update
#   BQ_PROMO_GPU_VENV=/path ...                # elsewhere than ~/.cache/bq-promo/gpu-venv
set -euo pipefail
VENV="${BQ_PROMO_GPU_VENV:-$HOME/.cache/bq-promo/gpu-venv}"
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command -v nvidia-smi >/dev/null || { echo "error: no NVIDIA driver (nvidia-smi)" >&2; exit 1; }
nvidia-smi -L >/dev/null || { echo "error: the NVIDIA driver is not working (reboot after a driver update?)" >&2; exit 1; }
# CuPy wheels are per CUDA major version; the driver reports the newest it supports.
# (older nvidia-smi prints "CUDA Version: 13.0", newer "CUDA UMD Version: 13.5")
CUDA_MAJOR="$(nvidia-smi | grep -oE 'CUDA (UMD )?Version: *[0-9]+' | grep -oE '[0-9]+$' | head -1)"
[[ "$CUDA_MAJOR" =~ ^(12|13)$ ]] || { echo "error: unsupported CUDA version '$CUDA_MAJOR' (need 12 or 13)" >&2; exit 1; }
python3 -m venv --system-site-packages "$VENV"
"$VENV/bin/pip" install --quiet --upgrade "cupy-cuda${CUDA_MAJOR}x"
cd "$HERE"
"$VENV/bin/python" -c "
import sys; sys.path.insert(0, '.')
import gpu_compose as G
ok, info = G.available()
print('GPU export:', info if ok else 'NOT available: ' + info)
print('NVENC H.264:', G.nvenc_ok('h264_nvenc'), ' HEVC:', G.nvenc_ok('hevc_nvenc'))
sys.exit(0 if ok else 1)"
