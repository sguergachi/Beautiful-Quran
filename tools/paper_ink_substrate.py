"""Use the production pure Kotlin substrate in offline shader tests/renders.

Requires JDK 21 and kotlinc in addition to the renderer's Python dependencies.
The compiled helper is temporary; no generated material is checked in.
"""
from functools import lru_cache
from pathlib import Path
import subprocess
import tempfile

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
_TEMP = None


@lru_cache(maxsize=1)
def _helper():
    global _TEMP
    _TEMP = tempfile.TemporaryDirectory(prefix='bq-ink-substrate-')
    folder = Path(_TEMP.name)
    runner = folder / 'Main.kt'
    runner.write_text('''
import com.beautifulquran.ui.theme.paperink.PaperInkSubstrate
fun main(args: Array<String>) {
    val pixels = PaperInkSubstrate.generate(args[0].toInt(), args[1].toInt(),
        args[2].toFloat(), args[3].toInt())
    val out = java.io.DataOutputStream(System.out)
    pixels.forEach { out.writeInt(it) }
    out.flush()
}
''')
    jar = folder / 'substrate.jar'
    subprocess.run(['kotlinc', str(ROOT / 'app/src/main/java/com/beautifulquran/ui/theme/paperink/PaperInkSubstrate.kt'),
                    str(runner), '-include-runtime', '-d', str(jar)], check=True, capture_output=True)
    return jar


@lru_cache(maxsize=64)
def substrate(width, height, unit, text):
    seed = 0
    units = text.encode("utf-16-be")
    for at in range(0, len(units), 2):
        seed = (seed * 31 + int.from_bytes(units[at:at + 2], "big")) & 0xffffffff
    if seed >= 0x80000000:
        seed -= 0x100000000
    raw = subprocess.run(['java', '-jar', str(_helper()), str(width), str(height), str(unit), str(seed)],
                         check=True, capture_output=True).stdout
    packed = np.frombuffer(raw, dtype='>u4').reshape(height, width)
    return np.stack([(packed >> shift) & 255 for shift in (16, 8, 0, 24)], axis=-1).astype('uint8')
